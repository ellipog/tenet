package dev.ellipog.tenet.quest;

import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.Problems;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every loaded quest, indexed, with the cross-file checks done.
 *
 * <h2>Why this is separate from the validator</h2>
 *
 * <p>Because it needs to see all of them. A file on its own cannot know that {@code punch_a_tree}
 * already exists in another file, or that a {@code dependsOn} points at a quest that was deleted.
 * That is a different job from "is this file well-formed", and keeping them apart means the validator
 * stays a per-file check while this runs once over everything.
 *
 * <p>Duplicate identifiers are reported <b>only</b> here, including duplicates within one file. The
 * validator has no cross-quest state, so it cannot see them, and having exactly one place that
 * decides whether an id is taken means there is never a case where two checks disagree about it.
 *
 * <h2>Identifiers, and why aliases matter</h2>
 *
 * <p>A quest is looked up by id <b>or alias</b>, everywhere: in {@code dependsOn}, in commands, in the
 * editor. That is what makes renaming a quest safe. Progress is stored against whatever id the quest
 * had when a player completed it, so without aliases a rename silently discards every player's
 * progress through that quest — a mistake the legacy format lets you make.
 *
 * <p>So there is one table keyed by both, and a clash between them is an error: if one quest's id
 * collides with another's alias, a lookup is ambiguous, and ambiguity here means somebody's progress
 * lands on the wrong quest.
 *
 * <h2>Lookups are case-insensitive; files stay lowercase</h2>
 *
 * <p>Every key in the three tables is stored lowercased, and every lookup lowercases first — so an
 * id, an alias and any case mix of either resolve to the same entry. That is for the legacy format, whose
 * ids are uppercase hexadecimal and whose own reads never cared about case: a string an author, a
 * command or a script wrote down may be in either case, and "the case is wrong" is not a failure
 * the legacy format has ever had. Each entry keeps its own id, so nothing displayed or stored changes — player
 * progress is keyed by canonical id and is untouched.
 *
 * <p>The validator still requires lowercase ids and dependency strings, and aliases may use
 * uppercase: the validator's job is to keep files uniform, the lookup's job is to not break a
 * reference somebody wrote correctly in another case. An alias that normalises onto another
 * object's id or alias is still a hard error, and an alias that normalises onto its own id is
 * also still an error — it differs from the id only in case, so it says nothing.
 *
 * <h2>A {@code #tag} resolves to the first object carrying it</h2>
 *
 * <p>the legacy tag lookup: quests, chapters and groups each carry {@code tags}, and a lookup
 * string of {@code "#village"} resolves to the first object of the asked kind with that tag, in
 * declaration order. The three kinds resolve separately -- see {@link #questWithTag} -- so a tag
 * shared by a quest and a chapter answers whichever the caller asked for. Dependency edges keep
 * their id-or-alias charset (the validator refuses a {@code #} there), so this is for commands,
 * scripts and clicks: the surfaces that name one thing to open or check, not the edges that gate.
 */
public final class QuestIndex {

    /**
     * The tree, in declaration order: every group, then every chapter, then every quest.
     *
     * <h2>Why three stored lists rather than five copies of the same walk</h2>
     *
     * <p>Because five separate places used to walk <i>the files</i> and flatten the tree themselves,
     * each with its own nested loop: this class's own {@code quests()}, {@link
     * dev.ellipog.tenet.net.QuestSync}'s tree writer, {@link
     * dev.ellipog.tenet.progress.ProgressionEngine}'s chapter list, {@code TenetCommand}'s group
     * list, and two test helpers. Five loops, one shape, and nothing keeping them in step — so a
     * change to what "a chapter" is had to be made in five places, and the four that were missed
     * would compile and produce a shorter list than expected rather than an error.
     *
     * <p>The order is <b>declaration order</b>: groups in the order the loader found them, a group's
     * chapters in the order its manifest lists them, and a chapter's quests in the order its manifest
     * lists them. That last one is load-bearing rather than cosmetic — a LINEAR chapter's progression
     * <i>is</i> its quest list order — which is why the position is carried on the entry rather than
     * being counted again wherever it is needed. See {@link QuestEntry#orderInChapter}.
     *
     * <p>A fourth derived map joined them with chapter dependencies: each chapter's own quests, keyed by
     * chapter id ({@link #questsIn}). A chapter's state is a question about the quests inside it, and it
     * is asked once per chapter per pass, so the grouping is built once here rather than filtered out of
     * the flat list wherever it is wanted — the same argument, one level down.
     */
    private final List<GroupEntry> groups;
    private final List<ChapterEntry> chapters;
    private final List<QuestEntry> quests;

    /**
     * Each chapter's own quests, in declaration order.
     *
     * <p>A fourth derived list, and it earns its place the same way the other three did: a chapter's
     * state is a question about <i>the quests inside it</i> — is any of them started, are all of the
     * ones it declares finished — and that answer is asked once per chapter per resolution pass. Built
     * here rather than filtered out of the flat list at each call site, because four filters is four
     * descriptions of one fact, which is exactly the fault the three lists above exist to remove.
     *
     * <p>Keyed by the chapter's <b>own id</b> and not by an alias: a quest carries the chapter object it
     * was assembled under, so the id a quest names is always canonical. A chapter that is not in the
     * tree has no entry here and no quests, which is the honest reading of a chapter that was dropped.
     */
    private final Map<String, List<QuestEntry>> questsByChapterId;

    private final Map<String, QuestEntry> byIdentifier;
    private final Map<String, ChapterEntry> chaptersByIdentifier;
    private final Map<String, GroupEntry> groupsByIdentifier;

    /**
     * A quest, and where it came from.
     *
     * <h2>Why the chapter and the position are on the entry</h2>
     *
     * <p>Both are properties of the chapter rather than of the quest, and both are here for the same
     * reason: every consumer that needs one would otherwise look it up, and there are three such
     * consumers. The chapter is cheap — it is a shared reference, not a copy — and holding it means
     * {@code progressionMode}, {@code theme} and {@code defaultPrerequisiteMode} come off the entry
     * rather than out of a map.
     *
     * <p>{@code orderInChapter} is the important one, and it is not an optimisation. It is the
     * position the quest holds <b>in the same list</b> a LINEAR chapter gates on, taken at the moment
     * that list is walked. A version recounted somewhere else is a second description of one fact,
     * and the way that goes wrong is the way that cannot be seen: a prefix of the chapter that is
     * wrong in the <i>inclusive</i> direction requires too little, so a LINEAR chapter unlocks
     * several quests at once — or all of them — with no error, no log line, and nothing on screen to
     * suggest a rule failed to apply. Carrying it from the walk makes the two the same number.
     *
     * <p>{@code chapterId} is derived rather than stored, because a second copy of a chapter's own id
     * is a second thing that can disagree with the first.
     */
    public record QuestEntry(String groupId, Chapter chapter, Quest quest, int orderInChapter,
                             String file, JsonDocument document, String path) {

        /** The id of the chapter this quest sits in. */
        public String chapterId() {
            return chapter.id();
        }

        /** Where this quest is, for a message: {@code quests/01_stone_age.json:14:9}. */
        public String location() {
            return file + ":" + document.nearestLocation(path);
        }
    }

    /** A chapter, and where it came from. */
    public record ChapterEntry(String groupId, Chapter chapter, String file, JsonDocument document, String path) {
        public String location() {
            return file + ":" + document.nearestLocation(path);
        }
    }

    /** A chapter group, and where it came from. */
    public record GroupEntry(ChapterGroup group, String file, JsonDocument document, String path) {
        public String location() {
            return file + ":" + document.nearestLocation(path);
        }
    }

    private QuestIndex(List<GroupEntry> groups,
                       List<ChapterEntry> chapters,
                       List<QuestEntry> quests,
                       Map<String, List<QuestEntry>> questsByChapterId,
                       Map<String, QuestEntry> byIdentifier,
                       Map<String, ChapterEntry> chaptersByIdentifier,
                       Map<String, GroupEntry> groupsByIdentifier) {
        this.groups = List.copyOf(groups);
        this.chapters = List.copyOf(chapters);
        this.quests = List.copyOf(quests);
        this.questsByChapterId = Map.copyOf(questsByChapterId);
        this.byIdentifier = Map.copyOf(byIdentifier);
        this.chaptersByIdentifier = Map.copyOf(chaptersByIdentifier);
        this.groupsByIdentifier = Map.copyOf(groupsByIdentifier);
    }

    /**
     * Builds the index, reporting duplicate identifiers and unresolvable references into
     * {@code problems}.
     *
     * <p>Files that failed validation are not here — the loader filters them out first. So every quest
     * in this index decoded, and a reference failing to resolve means the reference is wrong rather
     * than that the target failed to load. That distinction is what makes the error message say
     * "no such quest" with confidence.
     */
    public static QuestIndex build(List<LoadedQuestFile> files, Problems problems) {
        return assemble(QuestTree.of(files), problems);
    }

    /**
     * Builds the index from a tree whose every piece knows which document it was written in.
     *
     * <h2>Why this takes a tree rather than a list of files</h2>
     *
     * <p>Because the two file layouts disagree about what a "file" is, and this method is the one place
     * that must not care. A version-1 file is a whole tree in one document; a version-2 tree is one
     * document per group, per chapter and per quest. Given the files, this method would have to
     * <i>choose</i> a path convention — and the one it used to choose, {@code
     * $.chapterGroups[g].chapters[c].quests[q]}, is simply not a path in any document version 2 reads.
     *
     * <p>So it takes pieces, each of which carries the document and path it was written at, and it never
     * computes a position. Every message below therefore names the file an author can open and a line in
     * it, in both layouts, for the same reason: the position came from the file that was read.
     *
     * <p>{@link #build(List, Problems)} is the version-1 adapter, and {@link QuestTree#of} is where the
     * version-1 paths live — the only place either layout's paths are written.
     */
    public static QuestIndex assemble(QuestTree tree, Problems problems) {
        List<GroupEntry> groupList = new ArrayList<>();
        List<ChapterEntry> chapterList = new ArrayList<>();
        List<QuestEntry> questList = new ArrayList<>();

        Map<String, QuestEntry> quests = new LinkedHashMap<>();
        Map<String, ChapterEntry> chapters = new LinkedHashMap<>();
        Map<String, GroupEntry> groups = new LinkedHashMap<>();

        // A group or a chapter that lost an id takes its subtree out of the tree, so the walk remembers
        // which. Leaving the children in would be the same fault one level down and harder to see: a
        // chapter that cannot be opened is not drawn, so its quests would be invisible and still live --
        // resolving by id, accruing progress and blocking dependencies on a canvas nobody can reach.
        //
        // The pieces arrive depth-first -- a group, then its chapters, then each chapter's quests -- so a
        // chapter whose group was dropped is recognised by the group id it carries, and a quest whose
        // chapter was dropped by the chapter object itself. `==` and not `equals` there, and that is the
        // same identity `Chapter.indexOf` relies on for LINEAR progression: the loader builds each chapter
        // once and hands the same object to the chapter's piece, to the group's chapter list and to every
        // quest's back-pointer.
        String droppedGroup = null;
        Chapter droppedChapter = null;

        for (QuestTree.Piece piece : tree.pieces()) {
            switch (piece) {
                case QuestTree.Piece.GroupPiece pieceGroup -> {
                    ChapterGroup group = pieceGroup.group();
                    JsonDocument document = pieceGroup.source().document();
                    String path = pieceGroup.source().path();
                    GroupEntry groupEntry = new GroupEntry(group, pieceGroup.source().file(), document, path);

                    // Claimed before it is listed, and not listed at all if the id was taken. A row the map
                    // cannot reach is worse than an absent one: it draws, it can be clicked, and the click
                    // opens whichever group claimed the id first. Its aliases go unclaimed with it, or they
                    // would point at a row that is not in the tree.
                    if (!claimIdentifier(groups, quests, chapters, group.id(), groupEntry,
                            "chapter group", document, path + ".id", problems)) {
                        droppedGroup = group.id();
                        continue;
                    }
                    groupList.add(groupEntry);
                    for (String alias : group.aliases()) {
                        claimAlias(groups, quests, chapters, alias, groupEntry,
                                group.id(), "chapter group", document, path + ".aliases", problems);
                    }
                }

                case QuestTree.Piece.ChapterPiece pieceChapter -> {
                    Chapter chapter = pieceChapter.chapter();
                    JsonDocument document = pieceChapter.source().document();
                    String path = pieceChapter.source().path();
                    ChapterEntry chapterEntry = new ChapterEntry(pieceChapter.groupId(), chapter,
                            pieceChapter.source().file(), document, path);

                    if (droppedGroup != null && droppedGroup.equals(pieceChapter.groupId())) {
                        // Its group is not in the tree, so neither is this -- but its id is still *checked*.
                        // See `reportTaken`: a clash here is a separate fault the author has to fix, and
                        // skipping the check would mean hearing about it only on the load after the group
                        // was repaired, which is two rounds of fixing one mistake.
                        droppedChapter = chapter;
                        reportTaken(groups, quests, chapters, chapter.id(), chapterEntry, "chapter",
                                document, path + ".id", problems);
                        reportAliases(groups, quests, chapters, chapter.aliases(), chapterEntry, "chapter",
                                document, path + ".aliases", problems);
                        continue;
                    }
                    if (!claimIdentifier(groups, quests, chapters, chapter.id(), chapterEntry,
                            "chapter", document, path + ".id", problems)) {
                        droppedChapter = chapter;
                        continue;
                    }
                    chapterList.add(chapterEntry);
                    for (String alias : chapter.aliases()) {
                        claimAlias(groups, quests, chapters, alias, chapterEntry, chapter.id(),
                                "chapter", document, path + ".aliases", problems);
                    }
                }

                case QuestTree.Piece.QuestPiece pieceQuest -> {
                    Quest quest = pieceQuest.quest();
                    JsonDocument document = pieceQuest.source().document();
                    String path = pieceQuest.source().path();
                    // The position comes off the piece, and the piece is the only thing that ever
                    // decided it -- it was taken while the chapter's own quest list was built, which is
                    // the list a LINEAR chapter gates on. See QuestEntry's note on why a second
                    // description of that number is the dangerous kind of mistake.
                    QuestEntry questEntry = new QuestEntry(pieceQuest.groupId(), pieceQuest.chapter(),
                            quest, pieceQuest.orderInChapter(), pieceQuest.source().file(), document, path);

                    if (droppedChapter != null && pieceQuest.chapter() == droppedChapter) {
                        reportTaken(groups, quests, chapters, quest.id(), questEntry, "quest",
                                document, path + ".id", problems);
                        reportAliases(groups, quests, chapters, quest.aliases(), questEntry, "quest",
                                document, path + ".aliases", problems);
                        continue;
                    }
                    // The duplicate is reported and the quest is left out, and the checks below are left
                    // out with it: one mistake, one message. Reporting a lost quest's placement as well
                    // would bury the sentence that says what to do, and the author has to rename the file
                    // before the next load can say anything else about it anyway.
                    if (!claimIdentifier(groups, quests, chapters, quest.id(), questEntry,
                            "quest", document, path + ".id", problems)) {
                        continue;
                    }
                    questList.add(questEntry);
                    for (String alias : quest.aliases()) {
                        claimAlias(groups, quests, chapters, alias, questEntry, quest.id(),
                                "quest", document, path + ".aliases", problems);
                    }

                    checkSelfDependency(document, path, quest, problems);
                    checkMinRequired(document, path, quest, problems);
                }
            }
        }

        Map<String, List<QuestEntry>> byChapter = new LinkedHashMap<>();
        for (QuestEntry entry : questList) {
            byChapter.computeIfAbsent(entry.chapterId(), id -> new ArrayList<>()).add(entry);
        }

        QuestIndex index = new QuestIndex(groupList, chapterList, questList, byChapter, quests, chapters,
                groups);
        index.checkDependencies(problems);
        index.checkGhosts(problems);
        index.checkChapterRules(problems);
        index.checkElementRequirements(problems);
        index.checkLinks(problems);
        index.checkDuplicatePositions(problems);
        // Not the same question as checkDuplicatePositions: two quests at 0,0 are stacked, two at 32,0
        // are *crowded* -- each is fine on its own and the two together cannot both show a title.
        index.checkCrowdedRows(problems);
        return index;
    }

    // ------------------------------------------------------------------
    // Claiming identifiers
    // ------------------------------------------------------------------

    /**
     * Takes an identifier for an entry, or reports that it is already taken.
     *
     * <p>The three tables are separate because an id being reused across kinds is fine — a chapter
     * and a quest may both be called {@code stone_age} — while an id being reused within a kind is
     * not. Passing all three keeps that distinction in one place instead of three.
     *
     * <p><b>The answer is what decides whether the entry joins the tree</b>, and that is the point of
     * returning it rather than only reporting. Two quests with one id are two files with one progress
     * record and one lookup, and the lookup has to resolve somewhere — so the first to claim the id keeps
     * it and the second is <i>not loaded</i>. Leaving the loser in the list would put a node on the canvas
     * that no map points at: it draws, it can be clicked, and the click opens the other quest. See
     * {@code ClientQuestCache.byId}, which keeps the first for the same reason and says so.
     *
     * @return whether the entry claimed the identifier, and so whether the caller should list it
     */
    private static boolean claimIdentifier(Map<String, GroupEntry> groups,
                                           Map<String, QuestEntry> quests,
                                           Map<String, ChapterEntry> chapters,
                                           String identifier, Object entry, String what,
                                           JsonDocument document, String path, Problems problems) {
        Object existing = lookup(groups, quests, chapters, kindOf(entry), identifier);
        if (existing == null) {
            put(groups, quests, chapters, kindOf(entry), identifier, entry);
            return true;
        }
        if (existing.equals(entry)) {
            // The same object claimed twice, which cannot happen from a walk over distinct elements.
            return true;
        }
        problems.add(document.name(), document.nearestLocation(path), DataProblem.Severity.ERROR,
                duplicateMessage(what, identifier, existing));
        return false;
    }

    /**
     * Reports an id that is already taken <b>without taking it</b>, for a piece whose parent is not in the
     * tree.
     *
     * <p>A dropped subtree is not loaded, but its files are still the author's and their clashes are still
     * theirs to fix. Skipping the check would mean a duplicate inside one of them surfaced only on the load
     * <i>after</i> the parent was repaired — two rounds of fixing one mistake — and the alternative of
     * claiming it anyway is worse: a lookup resolving to a row that is not in the tree is exactly the fault
     * the drop exists to remove.
     *
     * <p>Aliases are deliberately not checked here, so an alias clash inside a dropped subtree is reported
     * on the next load. That is the same one-mistake-one-round trade the loader already makes for a chapter
     * whose manifest will not validate: the chapter's quests are not decoded, and their index-level checks
     * wait with them.
     */
    private static void reportTaken(Map<String, GroupEntry> groups,
                                    Map<String, QuestEntry> quests,
                                    Map<String, ChapterEntry> chapters,
                                    String identifier, Object entry, String what,
                                    JsonDocument document, String path, Problems problems) {
        Object existing = lookup(groups, quests, chapters, kindOf(entry), identifier);
        if (existing == null || existing.equals(entry)) {
            return;
        }
        problems.add(document.name(), document.nearestLocation(path), DataProblem.Severity.ERROR,
                duplicateMessage(what, identifier, existing));
    }

    /** The sentence a duplicate id gets, wherever it is noticed. One wording, one place. */
    private static String duplicateMessage(String what, String identifier, Object existing) {
        return "duplicate " + what + " id \"" + identifier + "\" - already used by " + describe(existing)
                + "\n    the first one to claim the id is the one every lookup resolves to, so this one is"
                + " not loaded: rename it, or give it an alias nothing else uses";
    }

    private static void claimAlias(Map<String, GroupEntry> groups,
                                   Map<String, QuestEntry> quests,
                                   Map<String, ChapterEntry> chapters,
                                   String alias, Object entry, String canonicalId, String what,
                                   JsonDocument document, String path, Problems problems) {
        // Checked before the lookup, because the lookup would find the entry itself: an alias
        // that lands on its own id is "declared twice" by the map's reading, and that message
        // would send an author hunting for a second declaration that does not exist.
        if (key(alias).equals(key(canonicalId))) {
            // It differs from its own id only in case, so it says nothing: the id itself
            // already resolves every spelling of it. Kept as an error rather than ignored,
            // because a file carrying a name that means nothing is a file somebody will
            // "fix" by pointing a reference at it.
            problems.add(document.name(), document.nearestLocation(path), DataProblem.Severity.ERROR,
                    "the alias \"" + alias + "\" differs from this " + what + "'s own id only in"
                            + " case, so it names nothing the id does not already name: remove it");
            return;
        }
        // Normalisation does not loosen duplicate detection: an alias that lands on another
        // object's id or alias is still a hard error, and it never picks a winner.
        Object existing = lookup(groups, quests, chapters, kindOf(entry), alias);
        if (existing == null) {
            put(groups, quests, chapters, kindOf(entry), alias, entry);
            return;
        }
        if (existing.equals(entry)) {
            problems.add(document.name(), document.nearestLocation(path), DataProblem.Severity.ERROR,
                    aliasTwiceMessage(alias, what));
            return;
        }
        problems.add(document.name(), document.nearestLocation(path), DataProblem.Severity.ERROR,
                aliasClashMessage(alias, what, existing));
    }

    /**
     * Every alias of a piece whose parent is not in the tree, <b>checked and not claimed</b>.
     *
     * <p>The counterpart of {@link #reportTaken}, and it exists for the same reason: dropping a subtree must
     * not also drop the faults written inside it, or the author hears about them one load later, after
     * fixing the parent — two rounds for one mistake. What it must not do is claim the alias, because an
     * alias resolving to a row that is not in the tree is the fault the drop removes.
     *
     * <p>The two faults are told apart here by a set of what this entry has already said, rather than by
     * asking the map: an orphaned entry was never put in it, so a second occurrence of one alias would find
     * nothing there and the "declared twice" case would go unreported. That is the difference between
     * checking and claiming, and it is why this is not simply {@code claimAlias} without the put.
     */
    private static void reportAliases(Map<String, GroupEntry> groups,
                                      Map<String, QuestEntry> quests,
                                      Map<String, ChapterEntry> chapters,
                                      List<String> aliases, Object entry, String what,
                                      JsonDocument document, String path, Problems problems) {
        Set<String> seen = new LinkedHashSet<>();
        for (String alias : aliases) {
            if (!seen.add(key(alias))) {
                problems.add(document.name(), document.nearestLocation(path), DataProblem.Severity.ERROR,
                        aliasTwiceMessage(alias, what));
                continue;
            }
            Object existing = lookup(groups, quests, chapters, kindOf(entry), alias);
            if (existing != null && !existing.equals(entry)) {
                problems.add(document.name(), document.nearestLocation(path), DataProblem.Severity.ERROR,
                        aliasClashMessage(alias, what, existing));
            }
        }
    }

    /** The sentence an alias declared twice on one entry gets. One wording, two places that notice it. */
    private static String aliasTwiceMessage(String alias, String what) {
        return "the alias \"" + alias + "\" is declared twice on the same " + what;
    }

    /** The sentence an alias another thing already holds gets. Likewise. */
    private static String aliasClashMessage(String alias, String what, Object existing) {
        return "the alias \"" + alias + "\" is already used by another " + what + " ("
                + describe(existing) + "). An alias has to be unique within its kind - a lookup of \""
                + alias + "\" would otherwise be ambiguous, and player progress could land on the "
                + "wrong one.";
    }

    private enum Kind { GROUP, CHAPTER, QUEST }

    private static Kind kindOf(Object entry) {
        if (entry instanceof GroupEntry) {
            return Kind.GROUP;
        }
        if (entry instanceof ChapterEntry) {
            return Kind.CHAPTER;
        }
        return Kind.QUEST;
    }

    private static Object lookup(Map<String, GroupEntry> groups, Map<String, QuestEntry> quests,
                                 Map<String, ChapterEntry> chapters, Kind kind, String identifier) {
        return switch (kind) {
            case GROUP -> groups.get(key(identifier));
            case CHAPTER -> chapters.get(key(identifier));
            case QUEST -> quests.get(key(identifier));
        };
    }

    private static void put(Map<String, GroupEntry> groups, Map<String, QuestEntry> quests,
                            Map<String, ChapterEntry> chapters, Kind kind, String identifier, Object entry) {
        switch (kind) {
            case GROUP -> groups.put(key(identifier), (GroupEntry) entry);
            case CHAPTER -> chapters.put(key(identifier), (ChapterEntry) entry);
            case QUEST -> quests.put(key(identifier), (QuestEntry) entry);
        }
    }

    /**
     * The table key for an identifier or alias: lowercased, so every spelling resolves to the one
     * entry. Entries keep their own ids — this is only the lookup key, never what is displayed,
     * stored in progress, or written back to a file.
     */
    private static String key(String identifier) {
        return identifier.toLowerCase(Locale.ROOT);
    }

    private static String describe(Object entry) {
        if (entry instanceof QuestEntry quest) {
            return "the quest \"" + quest.quest().id() + "\" in " + quest.file()
                    + ":" + quest.document().nearestLocation(quest.path());
        }
        if (entry instanceof ChapterEntry chapter) {
            return "the chapter \"" + chapter.chapter().id() + "\" in " + chapter.file()
                    + ":" + chapter.document().nearestLocation(chapter.path());
        }
        if (entry instanceof GroupEntry group) {
            return "the chapter group \"" + group.group().id() + "\" in " + group.file()
                    + ":" + group.document().nearestLocation(group.path());
        }
        return String.valueOf(entry);
    }

    // ------------------------------------------------------------------
    // Cross-file checks
    // ------------------------------------------------------------------

    /** Every {@code dependsOn} must point at something that exists. */
    private void checkDependencies(Problems problems) {
        for (QuestEntry entry : quests()) {
            for (QuestRef dependency : entry.quest().dependencies()) {
                if (byIdentifier.containsKey(key(dependency.id()))) {
                    continue;
                }
                Optional<String> suggestion = nearestIdentifier(dependency.id());
                problems.error(entry.document(), entry.path() + ".dependsOn",
                        "no quest with id or alias \"" + dependency.id() + "\" exists"
                                + suggestion.map(s -> " - did you mean \"" + s + "\"?").orElse("")
                                + "\n    a dependency that resolves to nothing means this quest can never be unlocked");
            }
        }
    }

    /**
     * A chapter's own gate and completion, checked across the whole pack.
     *
     * <h2>Four faults, and why each is an error rather than a warning</h2>
     *
     * <p>Every one of them produces a chapter that <b>can never be opened</b>, which is the quietest
     * failure a questline has: nothing is logged while it happens, and the author's only clue is a
     * chapter that sits there dimmed. So each is reported the way a dangling quest dependency is, with
     * the consequence spelled out rather than described.
     *
     * <p>The last check is the one a hand-written file gets wrong and no single file can see: a chapter
     * that asks its dependencies to be <b>completed</b> while one of them declares no
     * {@code completesWhen} — the field that says what finished means. It is counted rather than reported
     * per edge, because {@code minRequired} makes the honest question "can enough of them ever be
     * completed", not "can this one".
     */
    private void checkChapterRules(Problems problems) {
        for (ChapterEntry entry : chapters()) {
            Chapter chapter = entry.chapter();
            ChapterRules rules = chapter.rules();

            for (ChapterRef dependency : rules.dependsOn()) {
                if (chapter.matches(dependency.id())) {
                    problems.error(entry.document(), entry.path() + ".dependsOn",
                            "this chapter waits on itself (\"" + dependency.id()
                                    + "\"), so it can never be opened");
                    continue;
                }
                if (chaptersByIdentifier.containsKey(key(dependency.id()))) {
                    continue;
                }
                Optional<String> suggestion = nearest(chaptersByIdentifier.keySet(), dependency.id());
                problems.error(entry.document(), entry.path() + ".dependsOn",
                        "no chapter with id or alias \"" + dependency.id() + "\" exists"
                                + suggestion.map(s -> " - did you mean \"" + s + "\"?").orElse("")
                                + "\n    a chapter dependency that resolves to nothing means this chapter"
                                + " can never be opened");
            }

            if (rules.minRequired() > rules.dependsOn().size()) {
                problems.error(entry.document(), entry.path() + ".minRequired", "minRequired is "
                        + rules.minRequired() + " but there are only " + rules.dependsOn().size()
                        + " chapter dependencies, so this chapter can never be opened");
            }

            for (QuestRef milestone : rules.completesWhen()) {
                if (byIdentifier.containsKey(key(milestone.id()))) {
                    continue;
                }
                Optional<String> suggestion = nearest(byIdentifier.keySet(), milestone.id());
                problems.error(entry.document(), entry.path() + ".completesWhen",
                        "no quest with id or alias \"" + milestone.id() + "\" exists"
                                + suggestion.map(s -> " - did you mean \"" + s + "\"?").orElse("")
                                + "\n    this chapter is completed when every quest named here is, so a"
                                + " name that resolves to nothing means it never reports completed");
            }

            // The quest this chapter centres on when selected. Must resolve, and must live in this
            // chapter: centring one canvas on a quest drawn on another is a file that says nothing
            // the client can honour, and a name that resolves to nothing centres on nothing.
            if (rules.autofocus().isPresent()) {
                String target = rules.autofocus().get().id();
                if (!byIdentifier.containsKey(key(target))) {
                    Optional<String> suggestion = nearest(byIdentifier.keySet(), target);
                    problems.error(entry.document(), entry.path() + ".autofocus",
                            "no quest with id or alias \"" + target + "\" exists"
                                    + suggestion.map(s -> " - did you mean \"" + s + "\"?").orElse("")
                                    + "\n    a chapter that centres on nothing centres on its bounding"
                                    + " box instead - remove the field for that");
                }
                else if (entry.chapter().quest(target).isEmpty()) {
                    problems.error(entry.document(), entry.path() + ".autofocus",
                            "no quest \"" + target + "\" in this chapter"
                                    + "\n    autofocus centres this chapter's canvas, so a quest drawn on"
                                    + " another canvas is nothing this canvas can centre on");
                }
            }
        }

        checkCompletedEdgesHaveCompletions(problems);
    }

    /**
     * A canvas element's {@code requires}, resolved across the whole pack.
     *
     * <h2>Why this is a cross-file check rather than a validator one</h2>
     *
     * <p>Because a chapter cannot see another chapter's quests, and an element on the first chapter may
     * well wait on a quest in the last: an image that announces a tier belongs on the canvas the tier
     * starts on, and the quest it waits for is wherever that quest lives. One file has no way to answer
     * that, so the question belongs to the pass that holds the whole index — the same reason a dangling
     * {@code dependsOn} is reported here and its <i>shape</i> is reported by the validator.
     *
     * <p>Only quests resolve. An element's id is a name in the chapter's own namespace, and the tempting
     * convenience — let {@code requires} find an element as well — would be a silent wrong answer the day
     * an element and a quest happened to share a name, which is exactly what happens to a converted pack
     * whose ids are all sixteen hex digits.
     */
    private void checkElementRequirements(Problems problems) {
        for (ChapterEntry entry : chapters()) {
            java.util.List<CanvasElement> elements = entry.chapter().elements();
            for (int i = 0; i < elements.size(); i++) {
                CanvasElement element = elements.get(i);
                String at = entry.path() + ".elements[" + i + "]";

                Optional<String> requires = element.requires();
                if (requires.isPresent() && !byIdentifier.containsKey(key(requires.get()))) {
                    Optional<String> suggestion = nearestIdentifier(requires.get());
                    problems.error(entry.document(), at + ".requires",
                            "no quest with id or alias \"" + requires.get() + "\" exists"
                                    + suggestion.map(s -> " - did you mean \"" + s + "\"?").orElse("")
                                    + "\n    an element gated on a quest that does not exist is never drawn,"
                                    + " and nothing else about the chapter is wrong");
                }

                // And the same question for a press, which the single file cannot answer either: an
                // `open_quest` names a quest by id or alias, and that quest may live in any chapter. Reported
                // rather than left to the client, because a press that does nothing reads as a broken control
                // rather than as a gap in the mod -- and because this is the side that can name the line.
                if (element instanceof CanvasElement.Image image
                        && image.click().type() == ClickAction.Type.OPEN_QUEST
                        && !byIdentifier.containsKey(key(image.click().data()))) {
                    Optional<String> suggestion = nearestIdentifier(image.click().data());
                    problems.error(entry.document(), at + ".click.data",
                            "no quest with id or alias \"" + image.click().data() + "\" exists"
                                    + suggestion.map(s -> " - did you mean \"" + s + "\"?").orElse("")
                                    + "\n    pressing this element would do nothing at all");
                }
            }
        }
    }

    /**
     * A chapter's links, resolved across the whole pack.
     *
     * <h2>Why this is a cross-file check rather than a validator one</h2>
     *
     * <p>For the reason the element's gate gives at length: one file cannot see another chapter's
     * quests, and a link may point anywhere in the book — the marker belongs on the canvas the
     * gate guards, and the quest it mirrors lives wherever that quest lives. Shape is the
     * validator's; existence is the index's.
     *
     * <p>Only quests resolve, and a link's id must resolve to nothing at all. A link sharing its
     * target's name is the shape every converted pack takes — sixteen hex digits either side — so
     * the collision half of this check is not paranoia: without it a canvas addresses two nodes by
     * one name, and the press opens whichever the lookup finds first. Element ids are a separate
     * namespace this check does not join: elements are addressed by edit operations, links by the
     * canvas, and neither reaches through the other.
     *
     * <p>Links are deliberately absent from every progression input: they are not dependencies, not
     * milestones, and not dependants. A quest with only inbound links still warns as a ghost, and a
     * link never completes, unlocks or counts anything.
     */
    private void checkLinks(Problems problems) {
        for (ChapterEntry entry : chapters()) {
            java.util.List<QuestLink> links = entry.chapter().links();
            for (int i = 0; i < links.size(); i++) {
                QuestLink link = links.get(i);
                String at = entry.path() + ".links[" + i + "]";

                if (quest(link.quest().id()).isEmpty()) {
                    Optional<String> suggestion = nearestIdentifier(link.quest().id());
                    problems.error(entry.document(), at + ".quest",
                            "no quest with id or alias \"" + link.quest().id() + "\" exists"
                                    + suggestion.map(s -> " - did you mean \"" + s + "\"?").orElse("")
                                    + "\n    a link with no target draws a mirror of nothing, and"
                                    + " pressing it would do nothing at all");
                }

                // Links are never claimed in any lookup table, so any hit is a real object — a
                // quest, a chapter or a group — that this link would shadow on the canvas.
                Object shadowed = byIdentifier.containsKey(key(link.id()))
                        ? byIdentifier.get(key(link.id()))
                        : chaptersByIdentifier.containsKey(key(link.id()))
                                ? chaptersByIdentifier.get(key(link.id()))
                                : groupsByIdentifier.get(key(link.id()));
                if (shadowed != null) {
                    problems.error(entry.document(), at + ".id",
                            "a link's id must not equal any quest, chapter or group id or alias:"
                                    + " \"" + link.id() + "\" names " + describe(shadowed)
                                    + "\n    two nodes addressed by one name means a press opens"
                                    + " whichever the lookup finds first");
                }
            }
        }
    }

    /**
     * A dependency that asks for <i>completed</i> needs a chapter that declares what finished means.
     *
     * <p>Counted over the edge's own rule rather than per dependency: {@code one_completed} with three
     * dependencies needs one of them to be completable, and {@code minRequired: 2} of three needs two.
     * An edge that cannot reach its own count is the fault, and the message names one of the chapters
     * responsible so the author has somewhere to start.
     */
    private void checkCompletedEdgesHaveCompletions(Problems problems) {
        for (ChapterEntry entry : chapters()) {
            ChapterRules rules = entry.chapter().rules();
            // A started-based bar asks for "opened", which every chapter reaches on its own gate, so no
            // completion has to be declared for it.
            if (rules.dependsOn().isEmpty() || rules.prerequisiteMode().countsWhenStarted()) {
                continue;
            }
            List<Chapter> withoutCompletion = new ArrayList<>();
            for (ChapterRef dependency : rules.dependsOn()) {
                chapter(dependency.id()).map(ChapterEntry::chapter)
                        .filter(target -> target.rules().completesWhen().isEmpty())
                        .ifPresent(withoutCompletion::add);
            }
            int required = rules.requiredCount();
            int completable = rules.dependsOn().size() - withoutCompletion.size();
            if (completable >= required || withoutCompletion.isEmpty()) {
                continue;
            }
            Chapter culprit = withoutCompletion.get(0);
            problems.error(entry.document(), entry.path() + ".dependsOn",
                    "this chapter waits on " + required + " of " + rules.dependsOn().size()
                            + " chapter(s) being completed, but only " + completable + " of them declare a"
                            + " completesWhen, so it can never be opened"
                            + "\n    \"" + culprit.id() + "\" declares no completesWhen, so it never reports"
                            + " completed: give it one, or wait on it with \"one_started\" or"
                            + " \"all_started\"");
        }
    }

    /** A quest cannot depend on itself: it would never unlock. */
    private static void checkSelfDependency(JsonDocument document, String questPath, Quest quest,
                                            Problems problems) {
        for (QuestRef dependency : quest.dependencies()) {
            if (quest.matches(dependency.id())) {
                problems.error(document, questPath + ".dependsOn", "this quest depends on itself (\""
                        + dependency.id() + "\"), so it can never be started");
            }
        }
    }

    /**
     * A quest with nothing to do, nothing to give, and nothing gated behind it.
     *
     * <h2>Why a junction is fine and a ghost is not</h2>
     *
     * <p>legacy authors use taskless quests as visual junctions, milestones and chapter gates, and a
     * branch behind one has to open — so a quest with no tasks and no rewards that <i>does</i> gate
     * something is a legitimate node, and warning about it would be noise on every converted pack.
     * What is left is the ghost: nothing to do in it, nothing to give from it, and nothing gated
     * behind it — almost always a quest someone started and did not finish writing.
     *
     * <h2>Why this runs after assembly rather than per piece</h2>
     *
     * <p>Whether anything depends on a quest is a question about the whole tree, which does not
     * exist while the pieces are still being claimed — the same reason dangling dependencies are
     * reported here and not by the validator. The dependant set is computed from every quest's
     * {@code dependsOn}, resolved by id or by normalised alias, so a gate written through an alias
     * still spares its junction.
     *
     * <p>Links and image gates pointing at the quest do not count as dependants: a link mirrors
     * without gating, and an element's {@code requires} draws without unlocking — neither opens
     * anything, so a quest with only inbound links still warns.
     */
    private void checkGhosts(Problems problems) {
        Set<String> hasDependants = new LinkedHashSet<>();
        for (QuestEntry entry : quests()) {
            for (QuestRef dependency : entry.quest().dependencies()) {
                QuestEntry target = byIdentifier.get(key(dependency.id()));
                if (target != null) {
                    hasDependants.add(target.quest().id());
                }
            }
        }
        for (QuestEntry entry : quests()) {
            Quest quest = entry.quest();
            if (!quest.tasks().isEmpty() || !quest.rewards().isEmpty()) {
                continue;
            }
            if (hasDependants.contains(quest.id())) {
                continue;
            }
            problems.warn(entry.document(), entry.path(),
                    "this quest has no tasks and no rewards, and nothing depends on it, so there is"
                            + " nothing to do in it and nothing it unlocks. A junction or a milestone"
                            + " needs a dependant; anything else needs a task."
                            + "\n    quest links and image gates pointing here do not count - only"
                            + " dependsOn makes a dependant");
        }
    }

    private static void checkMinRequired(JsonDocument document, String questPath, Quest quest,
                                         Problems problems) {
        if (quest.minRequired() > quest.dependencies().size()) {
            problems.error(document, questPath + ".minRequired", "minRequired is " + quest.minRequired()
                    + " but there are only " + quest.dependencies().size()
                    + " dependencies, so this quest can never be unlocked");
        }
    }

    /**
     * Two quests at the same coordinates.
     *
     * <p>A warning, not an error: the questline still works, and the overlap may be deliberate — a
     * quest hidden behind another until it unlocks. But it is far more often a copy-paste, and finding
     * it by eye on a canvas where two nodes sit exactly on top of each other is impossible.
     */
    private void checkDuplicatePositions(Problems problems) {
        Map<String, QuestEntry> taken = new LinkedHashMap<>();
        for (QuestEntry entry : quests()) {
            QuestLayout layout = entry.quest().layout();
            // Keyed by chapter as well as position, exactly as the crowding check is.
            //
            // Two chapters are two separate canvases drawn one at a time, so two quests at 0,0 in
            // different chapters are not stacked on anything -- and until a second shipped file used
            // 0,0 this never came up. Every chapter starts at its own origin, which is what makes a new
            // chapter easy to write, and a check that reports the first quest of every chapter as a
            // duplicate makes that impossible to do.
            String key = entry.groupId() + "/" + entry.chapterId() + "/" + layout.x() + "," + layout.y();
            QuestEntry other = taken.putIfAbsent(key, entry);
            if (other != null) {
                problems.warn(entry.document(), entry.path(),
                        "another quest (\"" + other.quest().id() + "\") is at the same position "
                                + layout.x() + "," + layout.y() + ", so one will be drawn over the other");
            }
        }
    }

    /**
     * The narrowest column spacing at which every label can be drawn in full.
     *
     * <p>From the client: a node label is capped at 120px wide and needs a few pixels of gap either
     * side. Below that the book still works — it truncates by width and drops a label that would sit
     * over another node — but a questline authored tighter than this will read as cramped, so the
     * author is told at load rather than at play.
     *
     * <p>64 rather than the label's own width, deliberately. The migration tool lays converted packs
     * out on a 64-pixel grid, so a threshold at or above that would warn on every converted row with
     * two long titles — noise on a pack whose layout the author never chose. The client copes with
     * 64 pixels of room by truncating, which is what makes the lower threshold honest rather than
     * lenient; below it even short titles collide and the nodes themselves crowd.
     */
    private static final int MIN_LABEL_SPACING = 64;

    /**
     * A rough width per character, for deciding whether a title will be truncated.
     *
     * <p>An estimate, and knowingly so. The alternative is a font metric, and this check runs on the
     * server where there is no font — so an accurate answer is not available at the moment the answer
     * is useful. Six pixels is a fair average for Minecraft's default font at GUI scale 1.
     *
     * <p>An estimate is acceptable here because of which way it can be wrong. This is a warning about
     * readability, not an error: a false negative means an author finds out by looking, which is what
     * happened before this check existed. A false positive would be noise, which is why the threshold
     * is generous rather than tight.
     */
    private static final int APPROX_CHAR_WIDTH = 6;

    /**
     * Quests placed so close together in a row that their titles cannot both be drawn.
     *
     * <h2>Why this is a warning and not merely a matter of taste</h2>
     *
     * <p>Because it produced a bug that took a while to identify. The shipped example questline had
     * nodes 64 pixels apart carrying titles around 90 pixels wide, so three labels centred on three
     * nodes 64px apart were drawn straight through each other. On screen that is
     * <em>"Punch a SomewherStone To…"</em> — which reads as a corrupted string, or as a font problem,
     * or as a renderer bug. It is none of those: it is three correct labels in a space that cannot hold
     * them.
     *
     * <p>The client now copes (it truncates to the measured room and skips a label that would overlap a
     * node), so this is no longer a rendering fault. It is still a layout that will not show what the
     * author wrote, and the useful moment to say so is when the file loads.
     *
     * <p>Only adjacent pairs in the same row are compared, because those are the only pairs that can
     * collide: a label sits below its node, so labels on different rows never meet.
     *
     * <p>And only pairs where <b>both</b> quests ask for their name to be drawn. Titles are off by
     * default, so a chapter of unnamed nodes can be as tight as the author likes and nothing will
     * collide — because nothing is drawn in the space between them.
     */
    private void checkCrowdedRows(Problems problems) {
        Map<String, List<QuestEntry>> rows = new LinkedHashMap<>();
        for (QuestEntry entry : quests()) {
            QuestLayout layout = entry.quest().layout();
            // Keyed by chapter as well as row: two chapters are different canvases, so a quest in each
            // at y=0 cannot collide with the other.
            rows.computeIfAbsent(entry.groupId() + "/" + entry.chapterId() + "/" + layout.y(),
                    key -> new ArrayList<>()).add(entry);
        }

        for (List<QuestEntry> row : rows.values()) {
            if (row.size() < 2) {
                continue;
            }
            List<QuestEntry> leftToRight = new ArrayList<>(row);
            // The lambda's parameter is typed explicitly. With inference the target type is not
            // settled at this point, and `comparingInt(entry -> ...)` fails to infer T -- which reads
            // as a mysterious "cannot infer type-variable(s) T" on a line with nothing wrong with it.
            leftToRight.sort(Comparator.comparingInt((QuestEntry entry) -> entry.quest().layout().x()));

            for (int i = 1; i < leftToRight.size(); i++) {
                QuestEntry left = leftToRight.get(i - 1);
                QuestEntry right = leftToRight.get(i);

                // Only a pair that is actually named can crowd. This is the change the labels' new
                // default forced: titles are now drawn only where a quest asks for one, so two quests
                // 64 pixels apart with no names between them are not a layout problem at all -- they
                // are two icons with room to breathe, which is what the default is for.
                //
                // Warning about them anyway would be the worst kind of check: noise that is right
                // about the arithmetic and wrong about the screen, which is how a warning gets
                // suppressed and then stays suppressed for the case that mattered. A named quest beside
                // an unnamed one gets the whole gap to itself, because the unnamed one draws nothing
                // there to collide with -- so this pair, not every pair, is the honest question.
                if (!left.quest().showTitle() || !right.quest().showTitle()) {
                    continue;
                }

                int gap = right.quest().layout().x() - left.quest().layout().x();
                if (gap == 0 || gap >= MIN_LABEL_SPACING) {
                    // 0 is a genuine duplicate, which checkDuplicatePositions already reports -- saying
                    // it twice would be noise, and a line of text where two nodes overlap is not the
                    // interesting part of that problem.
                    continue;
                }

                String leftTitle = left.quest().title().value();
                String rightTitle = right.quest().title().value();
                int widest = Math.max(approxWidth(leftTitle), approxWidth(rightTitle));
                if (widest <= gap - 8) {
                    continue;
                }

                problems.warn(right.document(), right.path(),
                        "this quest and \"" + left.quest().id() + "\" are " + gap
                                + " pixels apart in the same row, but their titles want about " + widest
                                + " - the quest book draws one label per node under the node, so these two "
                                + "will overlap and run together\n"
                                + "    the book needs about " + MIN_LABEL_SPACING
                                + " between two quests in a row for both titles to be shown in full"
                                + " (label widths are estimated, so this is approximate)");
            }
        }
    }

    /** A rough pixel width for a title, for {@link #checkCrowdedRows}. */
    private static int approxWidth(String title) {
        return title.length() * APPROX_CHAR_WIDTH;
    }

    /**
     * Levenshtein over the known identifiers, for a "did you mean" on an unresolved dependency.
     *
     * <p>Answered with a canonical id rather than the table key: keys are lowercased, and a
     * suggestion should spell the name the way its file does.
     *
     * <p>The length check skips candidates that cannot win: an edit distance is never less than
     * the lengths' difference, so a candidate that far away cannot beat the best so far. Same
     * answer, fewer dynamic-programming tables — and the miss is folded once rather than per
     * candidate, for the same reason.
     */
    private Optional<String> nearestIdentifier(String missed) {
        String want = key(missed);
        String best = null;
        int bestDistance = 3;
        for (QuestEntry entry : quests) {
            String have = key(entry.quest().id());
            if (Math.abs(want.length() - have.length()) >= bestDistance) {
                continue;
            }
            int distance = editDistance(want, have);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry.quest().id();
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The nearest name in one table, for a "did you mean".
     *
     * <p>Parameterised by the names rather than by the table because a chapter reference needs the
     * suggestion as much as a quest one does — the two tables hold different things and the same
     * arithmetic — and a second copy of this loop is a second answer to "which name did they mean".
     *
     * <p>Same length-difference skip as {@link #nearestIdentifier}: identical suggestions, fewer
     * tables. Only broken packs reach here — a resolved reference never asks — so this is load
     * hygiene for the error path rather than a hot loop.
     */
    private static Optional<String> nearest(java.util.Collection<String> names, String missed) {
        String want = missed.toLowerCase(Locale.ROOT);
        String best = null;
        int bestDistance = 3;
        for (String candidate : names) {
            if (Math.abs(want.length() - candidate.length()) >= bestDistance) {
                continue;
            }
            int distance = editDistance(want, candidate.toLowerCase(Locale.ROOT));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    // ------------------------------------------------------------------
    // Lookup
    // ------------------------------------------------------------------

    /** A quest by id or alias, in any letter case. */
    public Optional<QuestEntry> quest(String identifier) {
        if (identifier != null && identifier.startsWith("#") && identifier.length() > 1) {
            return questWithTag(identifier.substring(1));
        }
        return Optional.ofNullable(byIdentifier.get(key(identifier)));
    }

    /** A chapter by id or alias, in any letter case. */
    public Optional<ChapterEntry> chapter(String identifier) {
        if (identifier != null && identifier.startsWith("#") && identifier.length() > 1) {
            return chapterWithTag(identifier.substring(1));
        }
        return Optional.ofNullable(chaptersByIdentifier.get(key(identifier)));
    }

    /** A chapter group by id or alias, in any letter case. */
    public Optional<GroupEntry> group(String identifier) {
        if (identifier != null && identifier.startsWith("#") && identifier.length() > 1) {
            return groupWithTag(identifier.substring(1));
        }
        return Optional.ofNullable(groupsByIdentifier.get(key(identifier)));
    }

    /**
     * The first quest carrying {@code tag}, in declaration order, if any.
     *
     * <p>the legacy {@code #tag} lookup: a string naming a tag resolves to the first object with
     * it. Matched without regard to letter case, like every other lookup here, though tags
     * themselves are lowercase by the validator's rule. Quests, chapters and groups each resolve
     * within their own kind -- a {@code "#village"} asked of {@link #quest} finds the first
     * <i>quest</i> with it, never a chapter -- because the three tables are separate lookups and
     * a cross-kind answer would be ambiguous about what the caller gets back.
     */
    public Optional<QuestEntry> questWithTag(String tag) {
        for (QuestEntry entry : quests) {
            for (String held : entry.quest().tags()) {
                if (held.equalsIgnoreCase(tag)) {
                    return Optional.of(entry);
                }
            }
        }
        return Optional.empty();
    }

    /** The first chapter carrying {@code tag}, in declaration order, if any. See {@link #questWithTag}. */
    public Optional<ChapterEntry> chapterWithTag(String tag) {
        for (ChapterEntry entry : chapters) {
            for (String held : entry.chapter().rules().tags()) {
                if (held.equalsIgnoreCase(tag)) {
                    return Optional.of(entry);
                }
            }
        }
        return Optional.empty();
    }

    /** The first chapter group carrying {@code tag}, in declaration order, if any. See {@link #questWithTag}. */
    public Optional<GroupEntry> groupWithTag(String tag) {
        for (GroupEntry entry : groups) {
            for (String held : entry.group().tags()) {
                if (held.equalsIgnoreCase(tag)) {
                    return Optional.of(entry);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Every chapter group, once each, in declaration order.
     *
     * <p>Once each and not once per alias: the lookup table beside this holds an entry under every
     * name a thing has, group id and alias alike, so walking <i>that</i> would visit a group as many
     * times as it has names. These lists are the tree, and the maps are for lookup.
     */
    public List<GroupEntry> groups() {
        return groups;
    }

    /** Every chapter, once each, in declaration order. */
    public List<ChapterEntry> chapters() {
        return chapters;
    }

    /**
     * Every quest, once each, in declaration order.
     *
     * <p>A stored list rather than a walk, and it used to be a walk: this method rebuilt the whole
     * tree from the files on every call, and it is called from the dependency check, the position
     * check, the crowding check, the tree serialiser and the progress listing. So the same walk ran
     * five or six times per load and once per frame anywhere a screen asked, and every copy computed
     * its own JSON paths — which is what made a typo in a quest file point at the wrong place.
     */
    public List<QuestEntry> quests() {
        return quests;
    }

    /**
     * One chapter's own quests, in declaration order, or an empty list for a chapter with none.
     *
     * <p>An empty list covers two cases that read the same to every caller: a chapter that holds no
     * quests yet — the state between creating it and writing the first file — and a name that is not a
     * chapter at all. Neither has quests to report, and a caller that had to tell them apart would be
     * asking a question about the tree rather than about the quests.
     */
    public List<QuestEntry> questsIn(String chapterId) {
        return questsByChapterId.getOrDefault(chapterId, List.of());
    }

    public int questCount() {
        return quests.size();
    }

    /**
     * One quest's fresh decode, in manifest order, for a chapter being refreshed without a reload.
     */
    public record FreshQuest(Quest quest, String file, JsonDocument document, String path) {
    }

    /**
     * One chapter's fresh decode, for a chapter being refreshed without a reload: the chapter id
     * the refresh was asked for, the decoded chapter, its manifest document and display, and its
     * quests in manifest order.
     */
    public record FreshChapter(String chapterId, Chapter chapter, JsonDocument manifestDocument,
                               String manifestDisplay, java.util.List<FreshQuest> quests) {
    }

    /**
     * This index with one chapter's entries replaced by fresh decodes — the targeted half of a
     * cosmetic refresh, which re-reads the edited chapter instead of the whole tree.
     *
     * <p>Only cosmetic edits may come this way: positions, names and pictures, never ids, aliases,
     * edges or tasks, so every identifier still claims exactly what it claimed and the maps need
     * new values under old keys rather than a new claiming walk. Tag lookups need nothing at all:
     * they walk these lists, so swapped entries answer with their new words by themselves.
     *
     * <p>The swap is total within the chapter — its entry, its quests, and its object inside its
     * group's chapter list all become the new ones together — because the loader hands one Chapter
     * object to all three places and they must never disagree about which one it is. The flat
     * quest list is rebuilt chapter by chapter rather than spliced, so declaration order survives
     * whatever order the old list happened to be in.
     *
     * @return the patched index, or null when this index holds no such chapter (the caller
     *         answers that with a full reload, which is always correct)
     */
    public QuestIndex withRefreshedChapter(FreshChapter fresh) {
        String chapterId = fresh.chapterId();
        Chapter chapter = fresh.chapter();
        java.util.List<FreshQuest> quests = fresh.quests();
        ChapterEntry oldChapter = chaptersByIdentifier.get(chapterId);
        if (oldChapter == null) {
            return null;
        }
        String groupId = oldChapter.groupId();
        ChapterEntry chapterEntry = new ChapterEntry(groupId, chapter, oldChapter.file(),
                fresh.manifestDocument(), fresh.manifestDisplay());

        List<QuestEntry> chapterQuests = new ArrayList<>(quests.size());
        for (int index = 0; index < quests.size(); index++) {
            FreshQuest decoded = quests.get(index);
            chapterQuests.add(new QuestEntry(groupId, chapter, decoded.quest(), index, decoded.file(),
                    decoded.document(), decoded.path()));
        }

        List<ChapterEntry> chapterList = new ArrayList<>(chapters.size());
        for (ChapterEntry entry : chapters) {
            chapterList.add(entry.chapter().id().equals(chapterId) ? chapterEntry : entry);
        }

        List<GroupEntry> groupList = new ArrayList<>(groups.size());
        for (GroupEntry entry : groups) {
            if (!entry.group().id().equals(groupId)) {
                groupList.add(entry);
                continue;
            }
            List<Chapter> memberChapters = new ArrayList<>(entry.group().chapters().size());
            for (Chapter member : entry.group().chapters()) {
                memberChapters.add(member.id().equals(chapterId) ? chapter : member);
            }
            ChapterGroup group = entry.group();
            groupList.add(new GroupEntry(
                    new ChapterGroup(group.id(), group.title(), group.description(), group.icon(),
                            group.aliases(), group.collapsedByDefault(), group.tags(), memberChapters),
                    entry.file(), entry.document(), entry.path()));
        }

        List<QuestEntry> questList = new ArrayList<>(this.quests.size());
        Map<String, List<QuestEntry>> byChapter = new LinkedHashMap<>(questsByChapterId);
        byChapter.put(chapterId, List.copyOf(chapterQuests));
        for (ChapterEntry entry : chapterList) {
            questList.addAll(byChapter.getOrDefault(entry.chapter().id(), List.of()));
        }

        Map<String, QuestEntry> claimed = new LinkedHashMap<>();
        for (Map.Entry<String, QuestEntry> named : byIdentifier.entrySet()) {
            if (!named.getValue().chapterId().equals(chapterId)) {
                claimed.put(named.getKey(), named.getValue());
            }
        }
        for (QuestEntry entry : chapterQuests) {
            claimed.putIfAbsent(entry.quest().id(), entry);
            for (String alias : entry.quest().aliases()) {
                claimed.putIfAbsent(alias, entry);
            }
        }
        Map<String, ChapterEntry> claimedChapters = new LinkedHashMap<>();
        for (Map.Entry<String, ChapterEntry> named : chaptersByIdentifier.entrySet()) {
            if (!named.getValue().chapter().id().equals(chapterId)) {
                claimedChapters.put(named.getKey(), named.getValue());
            }
        }
        claimedChapters.put(chapterId, chapterEntry);
        for (String alias : chapter.aliases()) {
            claimedChapters.putIfAbsent(alias, chapterEntry);
        }
        GroupEntry groupEntry = null;
        for (GroupEntry entry : groupList) {
            if (entry.group().id().equals(groupId)) {
                groupEntry = entry;
            }
        }
        Map<String, GroupEntry> claimedGroups = new LinkedHashMap<>();
        for (Map.Entry<String, GroupEntry> named : groupsByIdentifier.entrySet()) {
            if (groupEntry == null || !named.getValue().group().id().equals(groupId)) {
                claimedGroups.put(named.getKey(), named.getValue());
            }
        }
        if (groupEntry != null) {
            claimedGroups.put(groupId, groupEntry);
            for (String alias : groupEntry.group().aliases()) {
                claimedGroups.putIfAbsent(alias, groupEntry);
            }
        }

        return new QuestIndex(groupList, chapterList, questList, byChapter, claimed, claimedChapters,
                claimedGroups);
    }

    /**
     * How many chapters there are.
     *
     * <p>This counted the lookup table's distinct values, because the list did not exist. Counting a
     * map's values is counting aliases as well as the things they name, and {@code distinct()} on a
     * record only collapses them when their contents match — so the number was right for the shipped
     * content and wrong in principle. The list is the tree, so its size is the answer.
     */
    public int chapterCount() {
        return chapters.size();
    }

    /** How many chapter groups there are. Likewise once each rather than once per name. */
    public int groupCount() {
        return groups.size();
    }

    public boolean isEmpty() {
        return questCount() == 0;
    }
}
