package dev.ellipog.tenet.quest;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.api.data.Problems;
import dev.ellipog.tenet.editor.EditorOp;
import dev.ellipog.tenet.editor.EditorOps;
import dev.ellipog.tenet.editor.QuestEditor;
import dev.ellipog.tenet.editor.ServerEditors;
import dev.ellipog.tenet.editor.ServerTables;
import dev.ellipog.tenet.editor.TableOp;
import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.Tenet;

import com.google.gson.JsonElement;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Optional;

/**
 * The loaded questline, and the one place that loads it.
 *
 * <h2>Which event, and why it matters</h2>
 *
 * <p>Loaded from {@code SERVER_STARTED}, not {@code SERVER_STARTING}. The two fire at genuinely
 * different points on the two loaders — on Fabric, {@code SERVER_STARTING} arrives before the game has
 * even logged starting the server, with no level in existence. Reading files there would work, but
 * anything that wanted the level would not, and the same code would then break on one loader only.
 *
 * <p>{@code SERVER_STARTED} is well-defined on both. Cheap to get right, and the alternative is a
 * bug that appears on one loader and not the other.
 *
 * <h2>Reload</h2>
 *
 * <p>{@link #reload} is what makes hand-editing bearable: change a file, run {@code /tenet reload},
 * see the tree and any problems in the log, without restarting. That loop is the reason the
 * line-numbered validator exists at all.
 */
public final class TenetQuests {

    /** How many lines of quest tree to put in the log. A real pack has hundreds; a log nobody can scroll is unread. */
    private static final int TREE_LOG_BUDGET = 200;

    private static volatile QuestIndex index;

    /**
     * The tree's own settings, from {@code index.json}.
     *
     * <p>Here beside the index rather than on it because they are a different thing: the index is the
     * chapters and quests the server serves, and this is how the book as a whole behaves. A reload
     * replaces both, and they must come from the same read of the same file.
     */
    private static volatile QuestSettings settings = QuestSettings.DEFAULTS;

    /**
     * The reward tables, by id, from {@code reward_tables/*.json}.
     *
     * <p>Replaced wholesale by a reload, like the index and the settings — a table that is renamed or
     * removed stops existing at the same moment the reference to it does.
     */
    private static volatile java.util.Map<String, dev.ellipog.tenet.quest.loot.RewardTable> rewardTables =
            java.util.Map.of();

    /**
     * The table files that did not load, by id, each with its reason — the other half of the folder.
     *
     * <p>Held rather than derived because there is nothing to derive it from: a refused file has no
     * {@code RewardTable} to look at, and the problems that explain it are thrown away with the load's
     * result. The client's Assets panel is what reads this, so that a broken table is a row an author can
     * see rather than a line in a log they have to go and find.
     */
    private static volatile java.util.Map<String, String> refusedTables = java.util.Map.of();

    /**
     * The pack's own translations, from {@code lang/*.json}.
     *
     * <p>Here rather than inside {@link QuestIndex} because a locale is an overlay on the book rather
     * than part of it: the tree's shape is identical whichever language it is read in, so a reload of
     * one does not have to disturb the other, and the client can be sent a locale without being sent
     * a tree. See {@link QuestLanguages} for the file format and the resolution chain.
     *
     * <p>Replaced wholesale by a reload, like the index and the settings.
     */
    private static volatile QuestLanguages languages = QuestLanguages.EMPTY;

    /**
     * What the last full load found wrong, for a player who was not there when it happened.
     *
     * <h2>Why it is held rather than derived</h2>
     *
     * <p>The same reason {@link #refusedTables} is: the load's problems are thrown away with its result,
     * and their only other reader is the log. That was enough while the report was something an <i>edit</i>
     * produced — {@code refreshTree} is the only caller of the sender, so a player who joined after the last
     * reload was sent the tree and nothing else. A pack with a dangling dependency therefore looked exactly
     * like a pack without one, to the one person who might be able to fix it.
     *
     * <p><b>Set by {@link #reload} and not by {@link #reloadTables}.</b> A full load reads the quests and
     * the tables, so its problems are the whole set; a table-only reload's are a subset of a set this field
     * already holds, and replacing it there would drop every quest fault from the report. The cost is
     * narrow and worth naming: a table fault that is not a refusal — a cycle between tables — reaches the
     * players who were online for it and not one who joins later.
     */
    private static volatile Problems problems = new Problems();
    /**
     * The chapters this server has open for editing.
     *
     * <p>Here rather than in the payload handler because this class owns the quest directory: the editors and
     * the index must be reading the same files, and two places resolving that path is two places to get it
     * wrong. Dropped by {@code /tenet reload} and nothing else — see {@link ServerEditors#forget}.
     */
    private static final ServerEditors EDITORS = new ServerEditors(TenetQuests::questRoot);

    /**
     * The reward tables this server has open for editing.
     *
     * <p>Beside {@link #EDITORS} and for the same reason: it owns drafts over the same folder, so the
     * two must be reading the same files. It reads the loaded tables and the index through suppliers
     * rather than reaching for them, so a test can hand it its own — see {@link ServerTables}.
     */
    private static final ServerTables TABLES = new ServerTables(
            EDITORS,
            TenetQuests::questRoot,
            TenetQuests::rewardTables,
            TenetQuests::index);

    private TenetQuests() {
    }

    /** Where the quest files live: one expression, so no two callers can disagree about the folder. */
    private static java.nio.file.Path questRoot() {
        return QuestEditor.root(ArmatureApi.platform().configDir());
    }

    /** The chapters open for editing on this server. */
    public static ServerEditors editors() {
        return EDITORS;
    }

    /** The reward tables open for editing on this server. */
    public static ServerTables tables() {
        return TABLES;
    }

    /**
     * Every recoverable delete under the quest folder: what is set aside, and what each would come back as.
     *
     * <p>Read off the disk rather than from any model, because that is the whole point of it: the editors'
     * trees skip tombstones, and the history that recorded the delete is the server's memory. The copy is
     * the only durable thing, so the copy is what is listed.
     */
    public static List<Removed> removed() {
        return QuestFiles.removedFiles(questRoot());
    }

    /**
     * Puts one set-aside thing back, through the family of op that owns it.
     *
     * <h2>Why the listing decides the route</h2>
     *
     * <p>Because the kind is a fact about what is on disk, and the listing is the one place that has
     * already looked: a table is a {@code TableOp} over a file, a quest file is a chapter's own model (so it
     * needs the chapter that holds it, which is the folder the tombstone sits in), and a folder is a shape
     * change that needs no session at all — which is the state a book with no chapters is in, and the one
     * where a restore matters most.
     *
     * <p>An unknown name is refused with a sentence naming the command that lists the real ones, rather
     * than with a filesystem error.
     */
    public static EditorOps.Applied restore(String path,
                                             com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops) {
        Removed wanted = removed().stream()
                .filter(each -> each.path().equals(path))
                .findFirst()
                .orElse(null);
        if (wanted == null) {
            return EditorOps.Applied.refused("nothing is set aside under \"" + path
                    + "\" - /tenet removed lists the names that are");
        }
        return switch (wanted.kind()) {
            case TABLE -> TABLES.apply(new TableOp.Restore(path), ops);
            case QUEST -> EDITORS.apply(wanted.chapter(), new EditorOp.RestoreRemoved(path), ops);
            case GROUP, CHAPTER -> EDITORS.apply("", new EditorOp.RestoreRemoved(path), ops);
        };
    }

    /** The current questline, or an empty index if nothing has loaded yet. Never null. */
    public static QuestIndex index() {
        QuestIndex current = index;
        return current != null ? current : QuestIndex.build(List.of(), new dev.ellipog.armature.api.data.Problems());
    }

    /**
     * Loads from disk and reports into the log.
     *
     * <p>Called on server start, and by {@code /tenet reload}.
     *
     * @return the load result, for a command to report back
     */
    public static QuestLoader.Result reload() {
        return reload(JsonOps.INSTANCE);
    }

    /**
     * Loads from disk and reports into the log, decoding against the server's registries.
     *
     * <p>Called on server start, and by {@code /tenet reload} — both have a server, and the
     * registry-backed fields (data components, holders, tags) only decode against one. The
     * server-less {@link #reload()} keeps plain JSON ops: same files, but registry-backed
     * values cannot be judged and fail as unusable.
     *
     * @param server the running server, for its registries
     * @return the load result, for a command to report back
     */
    public static QuestLoader.Result reload(MinecraftServer server) {
        return reload(server.registryAccess().createSerializationContext(JsonOps.INSTANCE));
    }

    private static QuestLoader.Result reload(DynamicOps<JsonElement> ops) {
        QuestLoader.Result result = QuestLoader.load(ArmatureApi.platform().configDir(), ops);
        index = result.index();
        settings = QuestSettings.load(QuestEditor.root(ArmatureApi.platform().configDir()));
        rewardTables = result.rewardTables();
        refusedTables = refusals(result.problems(), result.refusedTables());
        // The translations are read into the *same* problem list the tree's load filled, and read
        // before that list is published: a mistyped translation is then reported to the log, to
        // `/tenet reload` and to every author in game, through the one report that already exists,
        // rather than only to whoever happened to be reading the log.
        languages = QuestLanguages.load(QuestEditor.root(ArmatureApi.platform().configDir()),
                result.problems());
        // Keys for deleted quests stay behind silently, so they are reported here, against the
        // same report: warnings, never errors, so a pack with leftover keys still loads cleanly.
        languages.warnStale(index, rewardTables.keySet(), result.problems());
        problems = result.problems();

        report(result);
        return result;
    }

    /**
     * The pack's own translations. Never null; empty until a tree ships any.
     *
     * <p>Read by the join path and by the broadcast, both of which send one locale to one player —
     * see {@code TenetNetworking.sendLocaleTo}.
     */
    public static QuestLanguages languages() {
        return languages;
    }

    /**
     * What the last full load found wrong, or an empty set. Never null.
     *
     * <p>For the join path: the report an edit produces reaches whoever is online for it, and this is what
     * lets the same facts reach a player who arrives afterwards. See {@link #problems}.
     */
    public static Problems problems() {
        return problems;
    }

    /**
     * Patches dirty chapters into the loaded index without re-reading the tree, for a cosmetic
     * flush: every quest file, manifest and table re-read and re-validated is what makes a drag
     * cost a second on a big pack, and a cosmetic op cannot have moved anything that re-read
     * would find — positions, names and pictures are what changed, and the save that wrote them
     * already validated them.
     *
     * <p>Read from the open editors rather than from disk: the op just saved through them, so the
     * model is the saved state with no walk and no second read. Anything the refresh cannot
     * answer — a chapter with no open editor, a decode that fails — returns false, and the
     * caller answers that with the full reload, which is always correct. Progress, cycles and
     * problems are untouched, which is exactly what cosmetic means: no stored progress moved, no
     * edge changed, no new fault exists to report.
     *
     * @param chapters the dirty chapter ids, in arrival order; empty answers false
     * @param ops      the ops codecs decode with: the server's registry ops, the same ones the
     *                 save validated with, so a decode here agrees with the save about every file
     * @return true when every named chapter patched and the index was swapped
     */
    public static boolean refreshChapters(java.util.Set<String> chapters,
                                          com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops) {
        if (chapters == null || chapters.isEmpty() || index == null) {
            return false;
        }
        QuestIndex patched = index;
        for (String chapter : chapters) {
            java.util.Optional<QuestIndex.FreshChapter> fresh = EDITORS.refreshModel(chapter, ops);
            if (fresh.isEmpty()) {
                return false;
            }
            QuestIndex next = patched.withRefreshedChapter(fresh.get());
            if (next == null) {
                return false;
            }
            patched = next;
        }
        index = patched;
        return true;
    }

    /**
     * Re-reads the reward tables alone, keeping the index and the settings.
     *
     * <p>What a table edit needs: the quests did not move, and {@link #reload()} would re-read and
     * re-validate all of them — plus rebuild the index — to learn that.
     *
     * @return the problems the table folder reported, for the caller to log
     */
    public static dev.ellipog.armature.api.data.Problems reloadTables() {
        return reloadTables(JsonOps.INSTANCE);
    }

    /**
     * Re-reads the reward tables alone, decoding against the server's registries.
     *
     * @param server the running server, for its registries
     * @return the problems the table folder reported, for the caller to log
     */
    public static dev.ellipog.armature.api.data.Problems reloadTables(MinecraftServer server) {
        return reloadTables(server.registryAccess().createSerializationContext(JsonOps.INSTANCE));
    }

    private static dev.ellipog.armature.api.data.Problems reloadTables(DynamicOps<JsonElement> ops) {
        dev.ellipog.armature.api.data.Problems problems =
                new dev.ellipog.armature.api.data.Problems();
        QuestLoader.Tables tables = QuestLoader.loadTables(ArmatureApi.platform().configDir(), problems, ops);
        rewardTables = tables.loaded();
        refusedTables = refusals(problems, tables.refused());
        if (!problems.all().isEmpty()) {
            Constants.LOG.warn("{} problem(s) in the reward tables:", problems.all().size());
            for (var problem : problems.all()) {
                Constants.LOG.warn("  {}", problem.render());
            }
        }
        return problems;
    }

    /**
     * The refused table files, each with the first thing wrong with it.
     *
     * <p>The loader names the ids; the reason is the error already reported against {@code <id>.json},
     * taken from the same {@code Problems} the load filled — so the sentence the Assets panel shows an
     * author is the sentence the log and the reload report show, and there is one description of what is
     * wrong with a file rather than three that drift.
     *
     * <p>Keyed and ordered by id, so the panel's list does not reshuffle between reloads.
     */
    private static java.util.Map<String, String> refusals(
            dev.ellipog.armature.api.data.Problems problems, java.util.Set<String> refused) {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        for (String id : refused) {
            out.put(id, problems.forFile(id + ".json").stream()
                    .filter(problem ->
                            problem.severity() == dev.ellipog.armature.api.data.DataProblem.Severity.ERROR)
                    .map(dev.ellipog.armature.api.data.DataProblem::message)
                    .findFirst()
                    // A refused file with no error against it is a contradiction the loader cannot
                    // currently produce; saying so beats an empty row if that ever changes.
                    .orElse("this file did not load, and the load reported no reason for it"));
        }
        return java.util.Map.copyOf(out);
    }

    /** The tree's own settings. Never null; the defaults until something declares otherwise. */
    public static QuestSettings settings() {
        return settings;
    }

    /** The reward tables, by id. Never null; empty until a tree declares any. */
    public static java.util.Map<String, dev.ellipog.tenet.quest.loot.RewardTable> rewardTables() {
        return rewardTables;
    }

    /**
     * The table files that are there and did not load, by id, each with its reason. Never null.
     *
     * <p>A subset of the folder rather than of {@link #rewardTables()}: the two are disjoint by
     * construction, because a file that refuses is one that never became a table.
     */
    public static java.util.Map<String, String> refusedTables() {
        return refusedTables;
    }

    /**
     * Writes the outcome to the log.
     *
     * <p>Every problem, at its own line, in the order they appear in the files — which is the whole
     * point of tracking positions. A load that produced errors says so and names the files, rather
     * than quietly loading a subset and leaving someone to wonder why their quests are missing.
     */
    private static void report(QuestLoader.Result result) {
        var problems = result.problems();

        if (!problems.isEmpty()) {
            // Capped like the tree dump below it: a broken pack can carry thousands of problems,
            // and every one at its own warn line is a log nobody scrolls. The shown messages are
            // exact; the count says how many more there are, and the full report still reaches
            // players through the problems sync.
            java.util.List<DataProblem> all = problems.all();
            Constants.LOG.warn("{} problem(s) in the quest files:", all.size());
            int shown = Math.min(all.size(), TREE_LOG_BUDGET);
            for (int i = 0; i < shown; i++) {
                Constants.LOG.warn("  {}", all.get(i).render());
            }
            if (shown < all.size()) {
                Constants.LOG.warn("  ... and {} more problem(s) not shown here", all.size() - shown);
            }
        }

        // Three outcomes, and they are genuinely different. An earlier version had two, and reported
        // "loaded 2 file(s) cleanly" for a file with a circular dependency in it -- because it counted
        // files that had *decoded* and treated that as "had no problems". Decoding successfully and
        // being correct are not the same thing, and a log line that says otherwise sends someone
        // looking in the wrong place.
        if (result.filesFound() == 0) {
            Constants.LOG.info("no quest files found");
        }
        else if (result.filesWithErrors() == 0) {
            Constants.LOG.info("loaded {} file(s) cleanly", result.filesDecoded());
        }
        else {
            Constants.LOG.warn("loaded {} of {} file(s); {} had errors - see above",
                    result.filesDecoded(), result.filesFound(), result.filesWithErrors());
        }

        QuestLoader.logTree(result.index(), TREE_LOG_BUDGET);
    }

    /** Loads once, from the server-started hook. Logs, and never throws. */
    public static void loadOnServerStart() {
        loadOnServerStart(null);
    }

    /**
     * Loads once, from the server-started hook, decoding against registries when a server is
     * given. Logs, and never throws.
     */
    public static void loadOnServerStart(net.minecraft.server.MinecraftServer server) {
        try {
            if (server == null) {
                reload();
            }
            else {
                reload(server);
            }
        }
        catch (RuntimeException e) {
            // A broken quest file must not stop a server from starting. The world matters more than
            // the questline, and an author with a half-written file should still be able to play.
            Constants.LOG.error("the quest files could not be loaded at all", e);
        }
    }

    /**
     * One line describing what is loaded, for the boot log.
     *
     * <p>Counted in groups, chapters and quests, and it used to end "from N file(s)". That term has no
     * meaning in the folder layout — a group is a folder holding a manifest, and a chapter is a folder
     * inside it — so the number it reported would have been a file count that no longer corresponded
     * to anything a reader could point at. The loader's own result still reports files found and
     * decoded, which is where that question belongs.
     */
    public static String summary() {
        QuestIndex current = index();
        return current.questCount() + " quest(s) in " + current.chapterCount() + " chapter(s), "
                + current.groupCount() + " chapter group(s)";
    }

    /** A quest by id, alias, or {@code #tag}, if it is loaded. See {@link QuestIndex#quest}. */
    public static Optional<QuestIndex.QuestEntry> find(String idOrAlias) {
        return index().quest(idOrAlias);
    }
}
