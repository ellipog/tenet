package dev.ellipog.tenet.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.JsonParseException;
import dev.ellipog.armature.api.data.Problems;
import dev.ellipog.tenet.quest.QuestValidator;
import dev.ellipog.tenet.quest.loot.RewardTable;
import dev.ellipog.tenet.quest.loot.TableCycles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One table open for editing: a draft over the file that holds it.
 *
 * <h2>One draft per file, whatever prefix it is at</h2>
 *
 * <p>A file's root table and a table nested inside one of its entries are the <b>same file</b>, so they
 * are the same draft at different prefixes. Two drafts over one file would be two writers, and the
 * second would save over the first's work — the fault this arrangement exists to make impossible. The
 * same is true one level up: an inline table in a quest is edited through that chapter's
 * {@link QuestEditor}, which already owns the file, its history and its save.
 *
 * <h2>Validate on save, and nothing else</h2>
 *
 * <p>A named table's save validates the document with the loader's own validator and then checks the
 * <b>prospective</b> graph for a loop this edit would close — a single file cannot see a cycle, because
 * a cycle runs through other files. All or nothing, like a chapter's save: a refusal writes nothing.
 * An inline table needs neither check of its own: the chapter's save already validates the whole file
 * including the inline tables in it, and an inline table cannot be referenced by id, so an edit to one
 * can never close a named-table loop.
 */
public final class TableEditor {

    /**
     * The file, for a named table. Null for an inline one, which resolves its file per operation --
     * see {@link #file()}.
     */
    private final JsonFile namedFile;
    /** The quest whose file holds an inline table, or empty for a named one. */
    private final String questId;
    /** The path to the table inside the file: empty for a file's root table. */
    private final String prefix;
    /** The named table's id, or empty for an inline table. */
    private final String id;
    /** The chapter that owns the file, for an inline table in a quest; null for a named file. */
    private final QuestEditor chapter;

    private final Deque<String> undo;
    private final Deque<String> redo;

    private TableEditor(JsonFile namedFile, String questId, String prefix, String id,
                        QuestEditor chapter, Deque<String> undo, Deque<String> redo) {
        this.namedFile = namedFile;
        this.questId = questId == null ? "" : questId;
        this.prefix = prefix == null ? "" : prefix;
        this.id = id == null ? "" : id;
        this.chapter = chapter;
        this.undo = undo;
        this.redo = redo;
    }

    /**
     * The file the table is in, resolved <b>now</b>.
     *
     * <p>For a named table that is the file it was opened with. For an inline one it is a lookup, and
     * the lookup is the point: a chapter's undo and its structural refresh <i>replace</i> its quest
     * files with freshly read ones, so a draft holding the object it was opened with would go on
     * editing a copy nothing reads -- an edit that appears to work and is not in the file. Null when
     * the quest is gone, which every mutation treats as a refusal.
     */
    private JsonFile file() {
        if (chapter == null) {
            return namedFile;
        }
        return chapter.quest(questId);
    }

    /**
     * A named table, read from its file.
     *
     * <p>Empty when the file cannot be read or is not JSON: the caller reports that as a refusal, which
     * is the same answer a missing file gets. Nothing is created here — the editor's Create op is what
     * makes a file.
     */
    public static Optional<TableEditor> named(Path file, String id) {
        try {
            JsonFile parsed = JsonFile.parse(file, Files.readString(file, StandardCharsets.UTF_8));
            return Optional.of(new TableEditor(parsed, "", "", id, null,
                    new ArrayDeque<>(), new ArrayDeque<>()));
        }
        catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * A table that is not on disk yet, for the op that is about to write it.
     *
     * <p>Its save is what puts it there, so a Create that is refused leaves no file behind — which is
     * the difference between a refusal and a half-made table.
     */
    public static TableEditor fresh(Path file, String id, JsonObject root) {
        return new TableEditor(JsonFile.of(file, root), "", "", id, null,
                new ArrayDeque<>(), new ArrayDeque<>());
    }

    /**
     * An inline table inside a chapter's quest file, at the prefix its handle resolved to.
     *
     * <p>The prefix is the caller's because the caller is what resolved the handle — and what can
     * report the two paths a collision has, which is a message a draft has no business inventing.
     */
    public static TableEditor inQuest(QuestEditor chapter, String questId, String prefix) {
        return new TableEditor(null, questId, prefix, "", chapter,
                new ArrayDeque<>(), new ArrayDeque<>());
    }

    /**
     * The same file, read at another prefix: the reward that <i>owns</i> a table, or a table nested
     * inside one.
     *
     * <p>A view rather than a second draft, because two drafts over one file would be two writers and
     * the second save would erase the first's work. It shares the file, the chapter and the history —
     * so an edit through a view is one more step in the same undo stack as everything else done to that
     * file, which is what makes Ctrl+Z mean one thing.
     */
    public TableEditor at(String prefix) {
        return new TableEditor(namedFile, questId, prefix, "", chapter, undo, redo);
    }

    /** The named table's id, or empty for an inline table. */
    public String id() {
        return id;
    }


    /** The file this table is written to, for a message. Null when it cannot be found. */
    public Path path() {
        JsonFile file = file();
        return file == null ? null : file.file();
    }

    /** The file as text, for the replica a client reads. */
    public String json() {
        JsonFile file = file();
        return file == null ? "" : file.json();
    }

    /**
     * The table itself, or null when the path no longer holds one.
     *
     * <p>Null is a real answer and the callers treat it as one: a prefix resolved a moment ago can be
     * gone by the time an op arrives, and refusing is the only honest response to "edit the table that
     * was here".
     */
    public JsonObject root() {
        JsonFile file = file();
        if (file == null) {
            return null;
        }
        if (prefix.isEmpty()) {
            return file.root();
        }
        JsonElement found = file.get(prefix);
        return found != null && found.isJsonObject() ? found.getAsJsonObject() : null;
    }

    /** Whether the table is still where this draft says it is. */
    public boolean exists() {
        return root() != null;
    }

    // ------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------

    /**
     * Changes one field of the table, or removes it when {@code value} is null.
     *
     * <p>A blank {@code title} or {@code uid} is stored as <b>absent</b> rather than as an empty string:
     * "present but blank" is a value no reader wants — a title that is there and draws nothing, or a
     * handle nothing can match — and the model normalises it on read for the same reason.
     */
    public boolean set(String path, JsonElement value) {
        JsonFile file = file();
        if (file == null) {
            return false;
        }
        String absolute = pathOf(path);
        JsonElement written = blankMeansAbsent(path, value);
        // An identical value is not an edit, and saying so is what keeps it from being reported as one:
        // the caller's `finish` refuses, so nothing is written, no reload runs and no tree is broadcast.
        // That matters beyond tidiness -- a client rebuilds its widgets when the tree revision moves,
        // and a rebuild replaces the buttons, so a write that changed nothing is what used to swallow a
        // press between its press and its release.
        JsonElement current = file.get(absolute);
        if (current == null ? written == null : current.equals(written)) {
            return false;
        }
        if (!push()) {
            return false;
        }
        try {
            if (written == null) {
                file.remove(absolute);
            }
            else {
                file.setJson(absolute, written);
            }
        }
        catch (JsonFile.UnwritablePath unwritable) {
            drop();
            return false;
        }
        return true;
    }

    /** Inserts one entry into the table's {@code entries}, at a position (clamped). */
    public boolean insert(int index, JsonObject entry) {
        JsonFile file = file();
        if (file == null || entry == null || !push()) {
            return false;
        }
        try {
            file.insert(pathOf("entries"), index, entry);
        }
        catch (JsonFile.UnwritablePath unwritable) {
            drop();
            return false;
        }
        return true;
    }

    /**
     * Writes several fields as <b>one</b> edit: one snapshot, one save, one undo.
     *
     * <p>What a conversion is. Pointing a reward at a table and clearing the inline copy it used to
     * have is one intent, and two edits would leave a state in between — a reward holding both — that
     * nothing would report but a reader would have to reason about. A value of null removes the field.
     */
    public boolean setAll(Map<String, JsonElement> fields) {
        JsonFile file = file();
        if (file == null || fields.isEmpty() || !push()) {
            return false;
        }
        for (Map.Entry<String, JsonElement> field : fields.entrySet()) {
            JsonElement value = blankMeansAbsent(field.getKey(), field.getValue());
            try {
                if (value == null) {
                    file.remove(pathOf(field.getKey()));
                }
                else {
                    file.setJson(pathOf(field.getKey()), value);
                }
            }
            catch (JsonFile.UnwritablePath unwritable) {
                drop();
                return false;
            }
        }
        return true;
    }

    /**
     * Inserts several entries as <b>one</b> edit.
     *
     * <p>What an import is: one snapshot, one validated save, one reload, and one Ctrl+Z for the whole
     * thing. Sending an op per item would be a write and a history step each — the difference between
     * a feature and a way to make the undo stack useless.
     */
    public int insertBatch(int index, List<JsonObject> entries) {
        JsonFile file = file();
        if (file == null || entries.isEmpty() || !push()) {
            return 0;
        }
        int at = index;
        try {
            for (JsonObject entry : entries) {
                file.insert(pathOf("entries"), at, entry);
                at++;
            }
        }
        catch (JsonFile.UnwritablePath unwritable) {
            drop();
            return 0;
        }
        return entries.size();
    }

    /** Removes the entry at a position. False when there is nothing there. */
    public boolean remove(int index) {
        JsonFile file = file();
        if (file == null || !push()) {
            return false;
        }
        if (!file.removeIndex(pathOf("entries"), index)) {
            drop();
            return false;
        }
        return true;
    }

    /** Moves an entry within the table. False when either end is not a position. */
    public boolean move(int from, int to) {
        JsonFile file = file();
        if (file == null || !push()) {
            return false;
        }
        if (!file.moveIndex(pathOf("entries"), from, to)) {
            drop();
            return false;
        }
        return true;
    }

    /** The table's entries as they are, for a caller that has to look before it writes. */
    public List<JsonObject> entries() {
        JsonObject table = root();
        if (table == null) {
            return List.of();
        }
        JsonElement entries = table.get("entries");
        if (entries == null || !entries.isJsonArray()) {
            return List.of();
        }
        List<JsonObject> out = new java.util.ArrayList<>();
        for (JsonElement entry : entries.getAsJsonArray()) {
            if (entry.isJsonObject()) {
                out.add(entry.getAsJsonObject());
            }
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    /**
     * Whether undo is available, which for an inline table is the chapter's history rather than this
     * draft's — an inline table <i>is</i> a quest field, so its undo is the same key as a title edit's.
     */
    public boolean canUndo() {
        return chapter != null ? chapter.canUndo() : !undo.isEmpty();
    }

    public boolean canRedo() {
        return chapter != null ? chapter.canRedo() : !redo.isEmpty();
    }

    /** Puts the last edit back. */
    public boolean undo() {
        if (chapter != null) {
            return chapter.undoHistory();
        }
        JsonFile file = file();
        if (file == null || undo.isEmpty()) {
            return false;
        }
        redo.push(file.json());
        file.replaceWith(undo.pop());
        return true;
    }

    /** The same, forward. */
    public boolean redo() {
        if (chapter != null) {
            return chapter.redoHistory();
        }
        JsonFile file = file();
        if (file == null || redo.isEmpty()) {
            return false;
        }
        undo.push(file.json());
        file.replaceWith(redo.pop());
        return true;
    }

    /**
     * Records the file as it is, before a change. False when this draft has nothing to write to.
     *
     * <p>For an inline table the push goes on the <b>chapter's</b> history, which is what makes one
     * Ctrl+Z undo a weight edit and a quest title edit alike.
     */
    private boolean push() {
        if (chapter != null) {
            JsonFile file = file();
            if (file == null) {
                return false;
            }
            chapter.pushHistory(file);
            return true;
        }
        JsonFile file = file();
        if (file == null) {
            return false;
        }
        undo.push(file.json());
        // The chapter's depth, from the one place it is written: a named table remembers as many
        // steps as a chapter, and an inline table is on the chapter's own stack above. The byte
        // cap with it, for the same reason the chapter has one: a table of huge entries would
        // otherwise keep sixty whole copies of itself. Small tables never reach it.
        while (undo.size() > QuestEditor.HISTORY) {
            undo.removeLast();
        }
        long bytes = 0;
        for (String text : undo) {
            bytes += text.length();
        }
        while (undo.size() > 1 && bytes > QuestEditor.HISTORY_BYTES) {
            bytes -= undo.removeLast().length();
        }
        redo.clear();
        return true;
    }

    /** Undoes the push a change that did not happen left behind. */
    private void drop() {
        if (chapter != null) {
            chapter.dropHistory();
            return;
        }
        if (!undo.isEmpty()) {
            undo.pop();
        }
    }

    /** A blank title or handle is no title and no handle. */
    private static JsonElement blankMeansAbsent(String path, JsonElement value) {
        if (value == null || !("title".equals(path) || "uid".equals(path))) {
            return value;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return value;
        }
        return value.getAsString().isBlank() ? null : value;
    }

    /** A path relative to the table, as the file spells it. */
    public String pathOf(String relative) {
        if (prefix.isEmpty()) {
            return relative;
        }
        return relative == null || relative.isEmpty() ? prefix : prefix + "." + relative;
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    /**
     * Validates and writes, all or nothing.
     *
     * @param loaded every named table the loader has, so a loop this edit would close is caught here
     *               rather than at the next reload — a single file cannot see one
     * @param ops    the ops codecs decode with: the server's registry ops in production, so a save
     *               judges exactly what a load judges (see {@code QuestEditor.save} for the fault
     *               blind ops caused)
     */
    public QuestEditor.SaveResult save(Map<String, RewardTable> loaded,
                                       com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops) {
        if (chapter != null) {
            // The chapter's save is what validates an inline table: it validates every file in the
            // chapter, inline tables included, and writes none of them if any has an error.
            return chapter.save(ops);
        }
        JsonFile file = file();
        if (file == null) {
            return refused(id + ".json", "the file this table lives in could not be found");
        }
        Problems problems = new Problems();
        String name = id + ".json";
        JsonDocument document;
        try {
            document = JsonDocument.parse(name, file.json());
        }
        catch (JsonParseException | RuntimeException notJson) {
            return refused(name, "the editor wrote something that is not JSON: " + notJson.getMessage());
        }
        QuestValidator.validateRewardTableDocument(document, problems, ops);
        reportClosedCycle(document, name, loaded, problems);

        List<DataProblem> errors = problems.all().stream()
                .filter(problem -> problem.severity() == DataProblem.Severity.ERROR)
                .toList();
        if (!errors.isEmpty()) {
            return new QuestEditor.SaveResult(0, errors);
        }
        try {
            file.write();
        }
        catch (IOException unwritable) {
            return refused(name, "could not be written: " + unwritable.getMessage());
        }
        return new QuestEditor.SaveResult(1, List.of());
    }

    /**
     * Reports a loop this file's new content would close.
     *
     * <p>The prospective graph: every loaded table, with this one replaced by what the draft now says.
     * That is the only way to see the loop, because a cycle is a property of the folder rather than of
     * any file in it — and catching it here means the author is told before the write, not by a reload
     * that reports a cycle in a file they just saved.
     */
    private void reportClosedCycle(JsonDocument document, String name,
                                   Map<String, RewardTable> loaded, Problems problems) {
        RewardTable decoded = RewardTable.CODEC
                .parse(com.mojang.serialization.JsonOps.INSTANCE, document.root())
                .result()
                .orElse(null);
        if (decoded == null) {
            // Not decodable: the validator's own checks are the message, and a cycle check over a
            // table that is not a table would invent a second one.
            return;
        }
        Map<String, RewardTable> prospective = new java.util.LinkedHashMap<>(loaded);
        prospective.put(id, decoded);
        TableCycles.find(prospective).ifPresent(chain -> problems.addAll(List.of(new DataProblem(
                name, 0, 0, "", DataProblem.Severity.ERROR,
                "circular table reference: " + String.join(" -> ", chain)
                        + " - this edit would close a loop, so it was not written"))));
    }

    private static QuestEditor.SaveResult refused(String name, String message) {
        return new QuestEditor.SaveResult(0, List.of(new DataProblem(name, 0, 0, "",
                DataProblem.Severity.ERROR, message)));
    }
}
