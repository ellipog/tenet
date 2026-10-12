package dev.ellipog.tenet.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.ellipog.armature.api.data.Checks;
import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.JsonLocation;
import dev.ellipog.armature.api.data.Problems;
import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.progress.ProgressionEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code config/tenet/quests/} and turns it into a {@link QuestIndex}.
 *
 * <h2>The pipeline, and why in that order</h2>
 *
 * <ol>
 *   <li><b>Parse</b> with {@link JsonDocument}, so every value has a line and a column.</li>
 *   <li><b>Validate</b> against the format — required fields, unknown fields, enum names, item
 *       existence. If this finds anything fatal, the file is <b>not decoded</b>.</li>
 *   <li><b>Decode</b> with the codecs, which is the only step that produces a {@link QuestFile}.</li>
 *   <li><b>Index</b> across all files, which is where duplicate ids and dangling dependencies are
 *       caught.</li>
 * </ol>
 *
 * <p>Step 2 before step 3 is the load-bearing bit. A codec ignores fields it does not recognise and
 * reports failures without positions, so decoding first would mean an author sees one message per
 * reload, in the wrong place. Validating first means one pass reports everything, each at its own
 * line.
 *
 * <p>Files that fail are skipped rather than aborting the load. A questline of ten files with one
 * broken should still load the other nine — that is the difference between a typo being a nuisance
 * and it being a dead server.
 *
 * <h2>It reads, and only reads</h2>
 *
 * <p>Nothing here creates, writes, renames or deletes anything — not even the quest directory itself.
 * That is a rule, and it was arrived at by getting it wrong twice.
 *
 * <p>The first version copied a questline out of the jar whenever the directory was <i>absent</i>. So
 * a shipped file could only ever reach an install at the moment that install was created, and adding
 * a second one to the jar reached nobody: every existing install already had the directory. The
 * second version fixed that by recording which files had been offered and offering the rest — a
 * bigger machine for a smaller problem, and still a mod writing content into a directory that belongs
 * to whoever is playing.
 *
 * <p>The rule that replaced both: <b>the mod ships no quests.</b> A quest file belongs to the person
 * who wrote it, and a loader that also writes has an opinion about content it has no business having
 * an opinion about — a player who installs Tenet wants a quest engine, not three example chapters
 * to delete, and every file a mod ships is a file that has to keep working forever against a format
 * that is still moving.
 *
 * <p>So the worked examples live in the repository, at {@code tenet/tools/quests}, and reach a
 * config directory because somebody ran {@code tenet/tools/seed_quests.py}. That script's header
 * says why it is there rather than here; the short version is that deleting a file you wrote is a
 * different act from discovering one you did not.
 *
 * <p>Two consequences are visible from outside, and both are intended:
 *
 * <ul>
 *   <li><b>An absent directory is reported, not created.</b> The warning names the full path, which
 *       is the useful half of what the old code did, without the write.</li>
 *   <li><b>A fresh install has no quests.</b> An empty book is the designed state rather than a
 *       failure, and it is what a pack author starts from.</li>
 * </ul>
 */
public final class QuestLoader {

    /** Relative to the config directory. */
    public static final String DIRECTORY = "tenet/quests";

    // There is deliberately no list of shipped quest files here.
    //
    // There used to be: a `SHIPPED_FILES` constant naming three files in the jar's resources, which
    // the loader copied into the config directory, plus a `.seeded` marker recording which of them
    // had been offered so a deleted one would stay deleted. The class comment explains why both are
    // gone. The short version is that the mod should not be the delivery mechanism for content it
    // does not own, so the examples moved to `tenet/tools/quests` and out of the jar entirely.
    //
    // The tests followed them there. That is a small improvement in its own right: the file under
    // test is now the file an author is pointed at, rather than a copy that travels through the
    // build's resource processing and could differ from it.

    private QuestLoader() {
    }

    /** What a load produced. */
    public record Result(QuestIndex index, Problems problems, int filesFound, int filesDecoded,
                         int filesWithErrors,
                         /** The reward tables, keyed by their file name without the suffix. */
                         java.util.Map<String, dev.ellipog.tenet.quest.loot.RewardTable> rewardTables,
                         /**
                          * The ids of table files that are there and did not load, in name order.
                          *
                          * <p>Carried out of the load rather than recomputed, because a file that does not
                          * decode never becomes a {@code RewardTable} and so cannot be found anywhere else:
                          * without this, a broken table is invisible to every panel that lists tables, and
                          * the author's only clue is a line in the log. The <i>reason</i> is not here — it
                          * is the problems reported against {@code <id>.json}, which the caller already has
                          * and which this would only duplicate.
                          */
                         java.util.Set<String> refusedTables) {

        /**
         * Whether every file that matched was usable.
         *
         * <p>Counts files with <b>errors</b>, not files that failed to decode. Those are different
         * things, and conflating them produced a log line that said "loaded 2 files cleanly" when one
         * of them had a circular dependency — see {@link #filesWithErrors}.
         */
        public boolean ok() {
            return filesWithErrors == 0;
        }
    }

    /**
     * Reads every quest file under {@code <configDir>/tenet/quests}.
     *
     * <p>A directory that is not there is reported and <b>not</b> created, which is the rule stated
     * on the class. An absent or empty directory is a normal state rather than a failure: Tenet
     * ships no quests, so this is what a fresh install looks like, and the warning names the full
     * path so the fix is obvious from the message alone.
     */
    public static Result load(Path configDir) {
        return load(configDir, com.mojang.serialization.JsonOps.INSTANCE);
    }

    /**
     * Reads every quest file under {@code <configDir>/tenet/quests}, decoding against registries.
     *
     * <p>Registry-backed fields (data components, holders, tags) only decode against a registry
     * context: the plain-JSON form cannot see them and fails every such value, so a load that
     * must judge them takes the server's ops. Callers without a server use {@link #load(Path)}.
     *
     * @param configDir the config root holding {@code tenet/quests}
     * @param ops       the ops codecs decode with, usually the server's registry ops
     */
    public static Result load(Path configDir, com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops) {
        Path directory = configDir.resolve(DIRECTORY);
        Problems problems = new Problems();

        if (!Files.isDirectory(directory)) {
            problems.add(DIRECTORY, new JsonLocation(1, 1, "$"), DataProblem.Severity.WARNING,
                    "no quest directory at " + directory + ", so there are no quests to load. Tenet"
                            + " ships no quests of its own; this directory is where they go.");
            return new Result(QuestIndex.build(List.of(), problems), problems, 0, 0, 0,
                    java.util.Map.of(), java.util.Set.of());
        }

        // Which files are quests, what each one declares, and what each one names underneath it — all
        // decided by QuestFiles, which is the one description of the folder rules. This method's job from
        // here is to decode what was found and assemble it, and it holds no opinion about where anything
        // sits on disk.

        // Phase timings, and they are kept because the load's cost was the one number nobody could
        // answer: an edit burst reloads the whole pack, and whether that reload is spent on the disk,
        // on decoding, or on the graph passes decides which of the three is worth making cheaper. At
        // debug level, because a reload is rare and this exists to settle an argument rather than to be
        // watched continuously.
        long readStarted = System.nanoTime();
        QuestFiles.Discovery found = QuestFiles.discover(directory);
        long readDone = System.nanoTime();
        problems.addAll(found.problems().all());

        QuestTree tree = assemble(found.declarations(), problems, ops);
        long assembled = System.nanoTime();
        QuestIndex index = QuestIndex.assemble(tree, problems);
        long indexed = System.nanoTime();

        // Cycle detection, after the index is built because a cycle can run across files -- a
        // dependency in one file pointing at a quest in another, which points back. Checked here
        // rather than in QuestIndex.build so that the index stays a lookup structure and this
        // stays a diagnostic pass over it.
        //
        // A cycle is the worst failure a questline can have and the quietest: the quest simply never
        // unlocks, nothing is logged, and the author has no reason to suspect the two quests they
        // wired to each other by mistake. So it is an error, with the whole loop printed.
        for (List<String> cycle : ProgressionEngine.findCycles(index)) {
            String chain = String.join(" -> ", cycle);
            // Reported against the first quest in the loop, which is where the file and line come
            // from. The chain names the rest, so the message is actionable as written.
            index.quest(cycle.get(0)).ifPresent(entry -> problems.error(entry.document(), entry.path(),
                    "circular dependency: " + chain
                            + "\n    no quest in this loop can ever be unlocked. Break it by removing one dependsOn."));
        }

        // And the same question one level up, over the chapter graph. Two kinds of edge make a loop
        // here -- a chapter waiting on another, and a chapter that is finished by a quest inside
        // another -- so the message names both ways out of it. A chapter cycle is reported against the
        // chapter's own manifest, which is where an author can act on it.
        for (List<String> cycle : ProgressionEngine.findChapterCycles(index)) {
            String chain = String.join(" -> ", cycle);
            index.chapter(cycle.get(0)).ifPresent(entry -> problems.error(entry.document(), entry.path(),
                    "circular chapter dependency: " + chain
                            + "\n    no chapter in this loop can ever be opened. Break it by removing a"
                            + " dependsOn, or by moving a completesWhen quest out of the loop."));
        }
        long checked = System.nanoTime();

        // The reward tables, which live beside the book rather than inside it. Read after the tree
        // because the checks run both ways: a table entry may point at another table, and a quest's
        // reward may point at any table. Both join the same problem list, so a bad table is counted
        // and reported like any other file.
        Tables tables = loadRewardTables(directory, problems, ops);
        for (QuestIndex.QuestEntry entry : index.quests()) {
            // Through the one reference walk, so a reward that points at a table from inside its own
            // inline table is checked like any other. It used to be checked by nothing at all: the
            // loop read a quest's top-level rewards and stopped there.
            for (dev.ellipog.tenet.quest.loot.RewardTableRefs.Ref ref
                    : dev.ellipog.tenet.quest.loot.RewardTableRefs.refsOf(entry.quest().rewards(), "$.rewards")) {
                checkTableId(ref, entry.document(), tables.loaded(), tables.refused(), problems);
            }
        }
        long tabled = System.nanoTime();

        // How many files have at least one error against them, whatever stage found it. Counted from
        // the problems rather than tracked alongside, so a check added later is counted automatically
        // instead of being forgotten.
        int filesWithErrors = (int) problems.all().stream()
                .filter(problem -> problem.severity() == DataProblem.Severity.ERROR)
                .map(DataProblem::file)
                .distinct()
                .count();

        // Files understood: every declaration whose own file came through with no error against it.
        //
        // Counted from the problems rather than tracked alongside the loop, for the reason above -- a
        // check added later is counted automatically instead of being forgotten. And it is not the same
        // number as "declarations that were decoded", because a declaration can decode and still be
        // reported: a duplicate id, a dangling dependency and a cycle are all found after decoding, and
        // all of them mean the file has a problem an author has to fix.
        int filesDecoded = (int) found.declarations().stream()
                .map(QuestFiles.Declaration::display)
                .distinct()
                .filter(display -> !problems.hasErrorsIn(display))
                .count();

        reportPhases(readStarted, readDone, assembled, indexed, checked, tabled, found.filesExamined());

        return new Result(index, problems, found.filesExamined(), filesDecoded, filesWithErrors,
                tables.loaded(), tables.refused());
    }

    /**
     * The load's own cost, phase by phase, at debug.
     *
     * <h2>What each phase is, in the terms the question is asked in</h2>
     *
     * <p>{@code read+decode} is {@link QuestFiles#discover}: the directory walk and every file's bytes
     * parsed, validated and decoded. {@code assemble} is the tree built from those declarations,
     * {@code index} the cross-file pass that catches duplicate ids and dangling dependencies, and
     * {@code cycles} the graph walk over the result. {@code tables} is the reward tables read and then
     * every quest's reward references checked against them.
     *
     * <p>The split is the point: <b>the answers to "is this disk, decode or graph?" are different
     * fixes</b>, and a single total cannot tell them apart. A load dominated by {@code read+decode}
     * wants a per-file cache keyed on what changed; one dominated by the graph passes would mean the
     * passes themselves are the cost, which is a different change with a different risk. Reading the
     * total and guessing which half it was is how a fix gets written for the wrong phase.
     *
     * <p>The timers are taken unconditionally — six clock reads on a load — and only the <i>line</i> is
     * conditional on the level. A branch saving six {@code nanoTime} calls on an operation that reads
     * every file in the pack would be a branch that costs more to read than it saves.
     */
    private static void reportPhases(long started, long readDone, long assembled, long indexed,
                                     long checked, long tabled, int filesExamined) {
        // **Info, and the trade is stated rather than hidden.** This was debug, and the mod has no way to
        // raise the log level, so the line had never once been written -- an instrument nothing can read.
        // Unlike the other three it has no switch of its own (see the note above on why the timers are
        // unconditional), so at info it prints on every reload. A reload is coalesced to one per server tick
        // and is already the moment a burst of edits becomes one, so that is one line per tick while
        // somebody is editing and nothing at all otherwise -- which is the cost of the reading existing.
        Constants.LOG.info("load phases -- {} file(s) examined; read+decode {} ms, assemble {} ms,"
                        + " index {} ms, cycles {} ms, tables {} ms, total {} ms",
                filesExamined, millis(started, readDone), millis(readDone, assembled),
                millis(assembled, indexed), millis(indexed, checked), millis(checked, tabled),
                millis(started, tabled));
    }

    /** Whole milliseconds between two {@code nanoTime} readings. */
    private static long millis(long from, long to) {
        return (to - from) / 1_000_000L;
    }

    /**
     * The reward tables under a config directory, read and validated on their own.
     *
     * <p>For a refresh after a table edit, which cannot have changed a quest: re-reading and
     * re-validating every quest file to learn that the tables moved is the work this exists to skip.
     * The cross-checks between tables — dangling references, cycles — run as they always do; the check
     * that a <i>quest</i> points at a table that exists is part of the full load, and is skipped here
     * (the editor refuses to delete a referenced table, which is the case that would create one).
     *
     * <p>Returns both halves for the same reason {@link Result} carries them: a table that did not load
     * is a table no other part of the program can mention.
     */
    public static Tables loadTables(Path configDir, Problems problems) {
        return loadTables(configDir, problems, com.mojang.serialization.JsonOps.INSTANCE);
    }

    /**
     * The reward tables under a config directory, decoded against registries.
     *
     * @param configDir the config root holding {@code tenet/quests}
     * @param problems  the problem list table faults join
     * @param ops       the ops codecs decode with, usually the server's registry ops
     */
    public static Tables loadTables(Path configDir, Problems problems,
                                     com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops) {
        return loadRewardTables(configDir.resolve(DIRECTORY), problems, ops);
    }

    /**
     * What the table folder produced: the tables that loaded, and the ids whose file was refused.
     *
     * <p>The second set is what lets a reference say which of the two problems it has — a name nothing
     * answers to, or a file that is there and did not load — and it is only knowable here, where the
     * files are read. The client's Assets panel lists exactly this set, for the same reason: a table an
     * author cannot load is the one they most need to be told about.
     */
    public record Tables(java.util.Map<String, dev.ellipog.tenet.quest.loot.RewardTable> loaded,
                         java.util.Set<String> refused) {
    }

    /**
     * Reads {@code reward_tables/*.json}, validates each, and checks its entries' table references.
     *
     * <p>Two passes because a reference can point at any table in the folder, including one later in
     * name order. The second pass runs once every id is known, so "the table is not there" is a fact
     * rather than an ordering accident — and it is also where the question only the whole folder can
     * answer is asked: whether the references form a loop.
     */
    private static Tables loadRewardTables(Path questRoot, Problems problems,
                                               com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops) {
        List<Path> files = QuestFiles.rewardTableFiles(questRoot);
        if (files.isEmpty()) {
            return new Tables(java.util.Map.of(), java.util.Set.of());
        }
        java.util.Map<String, dev.ellipog.tenet.quest.loot.RewardTable> tables =
                new java.util.LinkedHashMap<>();
        java.util.Map<String, JsonDocument> documents = new java.util.LinkedHashMap<>();
        // The ids whose file is there and was refused, so a reference to one can say which of the two
        // problems it is: a name nothing answers to, or a table that exists and did not load.
        java.util.Set<String> refused = new java.util.LinkedHashSet<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            String id = name.substring(0, name.length() - ".json".length());
            Optional<JsonDocument> parsed = QuestFiles.parseFile(file, name, problems);
            if (parsed.isEmpty()) {
                refused.add(id);
                continue;
            }
            JsonDocument document = parsed.get();
            QuestValidator.validateRewardTableDocument(document, problems, ops);
            if (problems.hasErrorsIn(name)) {
                refused.add(id);
                continue;
            }
            decode(dev.ellipog.tenet.quest.loot.RewardTable.CODEC, document, name, problems, ops)
                    .ifPresentOrElse(table -> {
                        tables.put(id, table);
                        documents.put(id, document);
                    }, () -> refused.add(id));
        }

        for (java.util.Map.Entry<String, dev.ellipog.tenet.quest.loot.RewardTable> loaded
                : tables.entrySet()) {
            JsonDocument document = documents.get(loaded.getKey());
            for (dev.ellipog.tenet.quest.loot.RewardTableRefs.Ref ref
                    : dev.ellipog.tenet.quest.loot.RewardTableRefs.refsOf(loaded.getValue(), "$")) {
                checkTableId(ref, document, tables, refused, problems);
            }
        }
        reportCycles(tables, documents, problems);
        return new Tables(java.util.Map.copyOf(tables), java.util.Set.copyOf(refused));
    }

    /**
     * Reports a reward naming a table that is not loaded, at the reward's own path.
     *
     * <p>Two sentences, because they are two different mistakes: a name no file answers to, and a file
     * that is there but was refused. The second is the one an author would otherwise chase — the file
     * exists, so "no reward table named inner" reads as a lie until they find the errors inside it.
     */
    private static void checkTableId(dev.ellipog.tenet.quest.loot.RewardTableRefs.Ref ref,
                                     JsonDocument document,
                                     java.util.Map<String, dev.ellipog.tenet.quest.loot.RewardTable> tables,
                                     java.util.Set<String> refused, Problems problems) {
        if (tables.containsKey(ref.id())) {
            return;
        }
        if (refused.contains(ref.id())) {
            problems.error(document, ref.path() + ".table", "reward_tables/" + ref.id() + ".json did not"
                    + " load, so this reference has nothing to point at - fix the problems reported"
                    + " against that file");
            return;
        }
        problems.error(document, ref.path() + ".table", "no reward table named \"" + ref.id() + "\" - add "
                + QuestFiles.REWARD_TABLES_DIRECTORY + "/" + ref.id() + ".json, or fix the reference");
    }

    /**
     * Reports the first loop in the reference graph, on the file the loop starts in.
     *
     * <p>The same shape as a circular {@code dependsOn}: an error, with the whole chain printed and a
     * sentence saying how to break it. The runtime already refuses to recurse past eight levels, so
     * this is not what stops a server hanging — it is what tells an author what they did, which the
     * log line it replaces did not.
     */
    private static void reportCycles(java.util.Map<String, dev.ellipog.tenet.quest.loot.RewardTable> tables,
                                     java.util.Map<String, JsonDocument> documents, Problems problems) {
        dev.ellipog.tenet.quest.loot.TableCycles.find(tables).ifPresent(chain -> {
            JsonDocument document = documents.get(chain.get(0));
            if (document == null) {
                return;
            }
            problems.error(document, "$", "circular table reference: " + String.join(" -> ", chain)
                    + "\n    a roll through these tables can never finish, so it is cut off past "
                    + dev.ellipog.tenet.quest.loot.RewardTable.MAX_NESTING + " levels and pays only what"
                    + " it reached -- every entry above the loop is paid again on each pass. Break it by"
                    + " pointing one of them at a different table.");
        });
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    /**
     * Decodes every declaration discovery found, and assembles them into one tree.
     *
     * <h2>Two phases, because a group cannot be built before its chapters are</h2>
     *
     * <p>A {@link ChapterGroup} holds its {@code Chapter}s, and a {@link Chapter} holds its
     * {@code Quest}s — so a group's piece cannot be emitted until everything underneath it has been
     * decoded, while the declarations arrive group-first. Phase one walks the declarations in order and
     * nests each one under the group or chapter it belongs to; phase two builds the records bottom-up
     * and emits the pieces.
     *
     * <p>Nesting by the {@code parentDisplay} each declaration already carries, rather than by matching
     * order, is what makes a chapter whose manifest failed to decode take only <i>its own</i> quests down
     * with it. The quests are still under it in the walk — they hang off the chapter's manifest — so they
     * are skipped with it, which is right: a quest is not loaded if the chapter it belongs to is not.
     *
     * <h2>{@code $schema} and the per-kind validators</h2>
     *
     * <p>Each kind is validated by <i>its own</i> entry point, so the field sets are per-kind — a chapter
     * field in a group's manifest is an error — and the positions are the file's own, because the path
     * for a per-kind document is {@code $}. Validation first, decode second, exactly as for version 1
     * and for the same reason: one mistake should produce one message, not a validator complaint
     * followed by a codec complaint about the same field.
     */
    private static QuestTree assemble(List<QuestFiles.Declaration> declarations, Problems problems,
                                          com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops) {
        List<GroupBuild> groups = new ArrayList<>();
        GroupBuild currentGroup = null;
        ChapterBuild currentChapter = null;

        // Chapters the index places at the root, which belong to no group. Kept apart from `groups`
        // because there is no GroupBuild to hang them on, and emitted after the groups for the same
        // reason: assembling is bottom-up, so every chapter has to be built once regardless of where
        // its declaration came from.
        List<ChapterBuild> looseChapters = new ArrayList<>();

        // Version-1 pieces, built as they are met, and merged back in declaration order below so that a
        // flat file sitting between two group folders keeps its place in the book.
        Map<String, List<QuestTree.Piece>> flatPieces = new LinkedHashMap<>();

        for (QuestFiles.Declaration declaration : declarations) {
            switch (declaration.kind()) {
                case FLAT_V1 -> {
                    // A whole version-1 tree in one document. Its paths are the nested ones, and
                    // QuestTree.of is the only place they are written.
                    QuestValidator.validate(declaration.document(), problems, ops);
                    if (problems.hasErrorsIn(declaration.display())) {
                        continue;
                    }
                    decode(QuestFile.CODEC, declaration.document(), declaration.display(), problems, ops)
                            .ifPresent(file -> flatPieces.put(declaration.display(),
                                    QuestTree.of(List.of(new LoadedQuestFile(declaration.path(),
                                            declaration.display(), declaration.document(), file))).pieces()));
                    currentGroup = null;
                    currentChapter = null;
                }

                case GROUP -> {
                    QuestValidator.validateGroupDocument(declaration.document(), problems, ops);
                    currentChapter = null;
                    if (problems.hasErrorsIn(declaration.display())) {
                        currentGroup = null;
                        continue;
                    }
                    currentGroup = decode(GroupManifest.CODEC, declaration.document(),
                            declaration.display(), problems, ops)
                            .map(manifest -> new GroupBuild(declaration, manifest))
                            .orElse(null);
                    if (currentGroup != null) {
                        groups.add(currentGroup);
                    }
                }

                case CHAPTER -> {
                    QuestValidator.validateChapterDocument(declaration.document(), problems, ops);
                    currentChapter = null;
                    if (problems.hasErrorsIn(declaration.display())) {
                        continue;
                    }
                    currentChapter = decode(ChapterManifest.CODEC, declaration.document(),
                            declaration.display(), problems, ops)
                            .map(manifest -> new ChapterBuild(declaration, manifest))
                            .orElse(null);
                    if (currentChapter == null) {
                        continue;
                    }
                    // A chapter with no manifest above it is one the index.json format allows at the
                    // root: it belongs to no group. The null parent is how discovery marks it -- a
                    // group's own chapters always carry that group manifest's display -- and the empty
                    // group id is what the rest of the pipeline already understands as "no group".
                    if (declaration.parentDisplay() == null) {
                        looseChapters.add(currentChapter);
                    }
                    else if (currentGroup != null) {
                        currentGroup.chapters.add(currentChapter);
                    }
                }

                case QUEST -> {
                    QuestValidator.validateQuestDocument(declaration.document(), problems, ops);
                    if (currentChapter == null || problems.hasErrorsIn(declaration.display())) {
                        continue;
                    }
                    // Copied to a final local because the lambda below is deferred, while
                    // `currentChapter` is reassigned by the next CHAPTER declaration. The compiler
                    // enforces this, and it is worth knowing what it is enforcing: a captured
                    // reference would attach the quest to whichever chapter the loop had reached by
                    // the time the lambda ran, which is a bug that only shows up if decoding becomes
                    // lazy. It is not lazy today, so this is the compiler closing a hazard that would
                    // otherwise be latent rather than live.
                    final ChapterBuild owning = currentChapter;
                    decode(Quest.CODEC, declaration.document(), declaration.display(), problems, ops)
                            .ifPresent(quest -> owning.quests.add(new QuestBuild(declaration, quest)));
                }
            }
        }

        // Bottom-up: each chapter is built once, so the same object serves the chapter's piece, the
        // group's chapter list and every quest's back-pointer. Building it twice would give two chapters
        // that are equal but not identical -- and `Chapter.indexOf` compares by reference, so LINEAR
        // progression would stop finding a quest's position and silently gate on nothing.
        for (GroupBuild group : groups) {
            for (ChapterBuild chapter : group.chapters) {
                chapter.assembled = chapter.manifest.toChapter(
                        chapter.quests.stream().map(quest -> quest.quest).toList());
            }
        }
        for (ChapterBuild chapter : looseChapters) {
            chapter.assembled = chapter.manifest.toChapter(
                    chapter.quests.stream().map(quest -> quest.quest).toList());
        }

        // A map from the file a declaration was read from to the piece it became, so the merge below
        // can replay the declarations in their original order.
        Map<String, QuestTree.Piece> pieceByDisplay = new LinkedHashMap<>();
        for (GroupBuild group : groups) {
            for (ChapterBuild chapter : group.chapters) {
                for (int q = 0; q < chapter.quests.size(); q++) {
                    QuestBuild quest = chapter.quests.get(q);
                    pieceByDisplay.put(quest.declaration.display(), new QuestTree.Piece.QuestPiece(
                            group.manifest.id(), chapter.assembled, quest.quest, q, quest.source()));
                }
                pieceByDisplay.put(chapter.declaration.display(), new QuestTree.Piece.ChapterPiece(
                        group.manifest.id(), chapter.assembled, chapter.source()));
            }
            pieceByDisplay.put(group.declaration.display(), new QuestTree.Piece.GroupPiece(
                    group.manifest.toGroup(group.chapters.stream().map(c -> c.assembled).toList()),
                    group.source()));
        }
        // And the root chapters, with the empty group id the client already draws as "no group".
        for (ChapterBuild chapter : looseChapters) {
            for (int q = 0; q < chapter.quests.size(); q++) {
                QuestBuild quest = chapter.quests.get(q);
                pieceByDisplay.put(quest.declaration.display(), new QuestTree.Piece.QuestPiece(
                        "", chapter.assembled, quest.quest, q, quest.source()));
            }
            pieceByDisplay.put(chapter.declaration.display(), new QuestTree.Piece.ChapterPiece(
                    "", chapter.assembled, chapter.source()));
        }

        // Declaration order, so a version-1 file keeps its place among the group folders beside it.
        List<QuestTree.Piece> pieces = new ArrayList<>();
        for (QuestFiles.Declaration declaration : declarations) {
            List<QuestTree.Piece> flat = flatPieces.get(declaration.display());
            if (flat != null) {
                pieces.addAll(flat);
                continue;
            }
            QuestTree.Piece piece = pieceByDisplay.get(declaration.display());
            if (piece != null) {
                pieces.add(piece);
            }
        }

        return new QuestTree(List.copyOf(pieces));
    }

    /** A group manifest that decoded, and the chapters under it. Mid-assembly, so mutable. */
    private static final class GroupBuild {

        private final QuestFiles.Declaration declaration;
        private final GroupManifest manifest;
        private final List<ChapterBuild> chapters = new ArrayList<>();

        private GroupBuild(QuestFiles.Declaration declaration, GroupManifest manifest) {
            this.declaration = declaration;
            this.manifest = manifest;
        }

        private QuestTree.Source source() {
            return new QuestTree.Source(declaration.display(), declaration.document(), "$");
        }
    }

    /** A chapter manifest that decoded, and the quests under it. {@code assembled} is filled in later. */
    private static final class ChapterBuild {

        private final QuestFiles.Declaration declaration;
        private final ChapterManifest manifest;
        private final List<QuestBuild> quests = new ArrayList<>();
        private Chapter assembled;

        private ChapterBuild(QuestFiles.Declaration declaration, ChapterManifest manifest) {
            this.declaration = declaration;
            this.manifest = manifest;
        }

        private QuestTree.Source source() {
            return new QuestTree.Source(declaration.display(), declaration.document(), "$");
        }
    }

    /** One quest file that decoded. */
    private record QuestBuild(QuestFiles.Declaration declaration, Quest quest) {

        private QuestTree.Source source() {
            return new QuestTree.Source(declaration.display(), declaration.document(), "$");
        }
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    /**
     * Runs the codecs over a file that has already passed validation.
     *
     * <p>Reaching an error here means the codec rejects something the validator allowed — a range
     * with no matching check, or a cross-field rule. So the position is best-effort: DFU's message
     * names the field it was unhappy about, and where it does, this looks that key up in the document
     * to place the caret on it. Where it does not, the problem is reported at the root of the file,
     * which is honest rather than precise.
     *
     * <p>The validator is the thing that produces good positions; this is the backstop for the cases
     * it does not cover.
     */
    private static <T> Optional<T> decode(Codec<T> codec, JsonDocument document, String display,
                                          Problems problems,
                                          com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops) {
        // Through Checks.parse, which contains a codec whose own functions throw. A raw throwable here
        // would leave the whole load rather than this file -- see that method for the mistake that
        // already cost this project every file in the tree, and for why an addon's codec is the case
        // this cannot afford to assume about.
        DataResult<T> result = Checks.parse(codec, document.root(), ops);

        Optional<T> decoded = result.result();
        if (decoded.isPresent()) {
            return decoded;
        }

        String message = result.error().map(DataResult.Error::message).orElse("the codec rejected this file");
        String path = guessPath(document, message);
        problems.add(display, document.nearestLocation(path), DataProblem.Severity.ERROR,
                "this file is well-formed JSON but the format rejected it:\n    " + message.replace("\n", "\n    ")
                        + "\n    (this is a gap in the validator - please report it)");
        return Optional.empty();
    }

    /** DFU says {@code No key x in ...} and {@code ... field "x" ...}; either is worth trying. */
    private static final Pattern KEY_PATTERN = Pattern.compile("No key (\\S+) in|field \"([^\"]+)\"");

    private static String guessPath(JsonDocument document, String message) {
        Matcher matcher = KEY_PATTERN.matcher(message);
        while (matcher.find()) {
            String key = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            for (String candidate : List.of("$." + key, "$.chapterGroups[0]." + key,
                    "$.chapterGroups[0].chapters[0]." + key)) {
                if (document.has(candidate)) {
                    return candidate;
                }
            }
        }
        return "$";
    }

    // ------------------------------------------------------------------
    // Logging
    // ------------------------------------------------------------------

    /**
     * Writes the tree to the log, with task and reward counts per quest.
     *
     * <p>This is the "done when" of the stage: a hand-written questline logs its tree. It is also how
     * an author confirms what actually loaded, which is otherwise invisible — the quest screen does not
     * exist yet, and a quest that failed to appear gives no clue as to why.
     *
     * <p>Capped, because a real pack has hundreds of quests and a log nobody can scroll is a log
     * nobody reads.
     *
     * <h2>The per-file line is gone, and its absence is deliberate</h2>
     *
     * <p>This used to print each file's name as a heading and then that file's groups underneath. That
     * is the right shape for version 1, where a file <i>is</i> a tree, and the wrong shape for the
     * folder layout, where a group is not in a file at all — it is in a folder, beside the chapter
     * folders it names. So the heading was a line that would have had to say "the folder called
     * getting_started" and be followed by groups belonging to nobody in particular.
     *
     * <p>The tree is printed as a tree, from the index, and the index no longer remembers which file a
     * declaration came from — every message about one names its own file, which is where that
     * information is wanted. What the log is for is confirming what loaded, and this still does that
     * with one less level of indentation.
     */
    public static void logTree(QuestIndex index, int maxLines) {
        if (index.isEmpty()) {
            Constants.LOG.info("no quests loaded");
            return;
        }

        StringBuilder out = new StringBuilder();
        int lines = 0;
        boolean truncated = false;

        for (QuestIndex.GroupEntry groupEntry : index.groups()) {
            ChapterGroup group = groupEntry.group();
            if (lines++ >= maxLines) {
                truncated = true;
                break;
            }
            out.append("\n  ").append(group.title().value());
            for (Chapter chapter : group.chapters()) {
                if (lines++ >= maxLines) {
                    truncated = true;
                    break;
                }
                out.append("\n    ").append(chapter.title().value())
                        .append("  [").append(chapter.id()).append(']');
                for (Quest quest : chapter.quests()) {
                    if (lines++ >= maxLines) {
                        truncated = true;
                        break;
                    }
                    out.append("\n      ").append(quest.title().value())
                            .append("  [").append(quest.id()).append(']')
                            .append("  ").append(quest.tasks().size()).append(" task(s)")
                            .append(", ").append(quest.rewards().size()).append(" reward(s)");
                    if (!quest.dependencies().isEmpty()) {
                        out.append(", after ")
                                .append(quest.dependencies().stream().map(QuestRef::id).toList());
                    }
                }
            }
        }

        if (truncated) {
            out.append("\n    … more quests omitted from this log line budget");
        }
        Constants.LOG.info("quest tree ({} quests in {} chapters){}",
                index.questCount(), index.chapterCount(), out);
    }
}
