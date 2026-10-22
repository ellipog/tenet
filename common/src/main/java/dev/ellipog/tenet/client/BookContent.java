package dev.ellipog.tenet.client;

import dev.ellipog.tenet.progress.QuestState;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The quest book's content: which chapters and quests the book shows, read from the client's cache,
 * and which chapter is the one being looked at.
 *
 * <p>Moved out of {@link QuestBookScreen} as it stood, with the state only it reads; the screen
 * reaches this through its {@code bookContent} field.
 */
final class BookContent {

    private final QuestBookScreen book;

    BookContent(QuestBookScreen book) {
        this.book = book;
    }

    // ------------------------------------------------------------------
    // Content
    // ------------------------------------------------------------------

    /**
     * Every chapter the book can show, by id, in the order the sidebar draws them.
     *
     * <h2>Why this is remembered rather than rebuilt</h2>
     *
     * <p>Because it is not a small map: it walks every chapter <b>and every quest</b>, and the frame path
     * asks for it constantly — {@link QuestBookScreen#effectiveChapter()} is that map's first key, and it is read by the
     * drawing, the theme lookups, the draft reconciliation and every panel, several times a frame. On a
     * nine-hundred-quest pack that was nine hundred map insertions per call, twenty-odd times a frame, for
     * an answer that cannot change until the tree does.
     *
     * <p>The tree revision is the whole of the key: both inputs are the cache's chapter list and its
     * entries, and neither moves without a tree arriving. Progress is deliberately not in it — a quest
     * completing cannot rename a chapter.
     *
     * <p>A chapter withheld until its gate is met is left out, which is the one thing here that is not a
     * rename: this map is what {@link QuestBookScreen#effectiveChapter} opens the book <i>by</i>, so a hidden chapter
     * kept in it would be the chapter a reader with nothing selected lands on. The gate's own state
     * therefore does move this answer, and the caller that hides a chapter is the same predicate that
     * makes {@link QuestBookScreen#sidebar()} rebuild on progress — see {@link #chapterHidden}.
     */
    static Map<String, String> chapters() {
        long revision = ClientQuestCache.treeRevision();
        // Progress and the editing flag are in the key as well as the tree, because since chapter gates
        // this map's *contents* move with both: a chapter leaves it when the gate it was hiding behind is
        // met, and an author sees every chapter. The note above says the tree was the whole of the key --
        // that was true while the only thing this map did was name chapters.
        long progress = ClientQuestCache.progressRevision();
        if (chaptersHeld != null && chaptersRevision == revision && chaptersProgress == progress
                && chaptersEditing == BookCanvas.editingView) {
            return chaptersHeld;
        }
        Map<String, String> chapters = new LinkedHashMap<>();
        // The explicit list first, then the quests: the same merge, in the same order, as the sidebar's
        // own build. An empty chapter has to be selectable here too, or the book would draw a row for it
        // and then refuse to open on it -- the selection falls back to the first chapter that a *quest*
        // names.
        for (ClientQuestCache.ChapterEntry chapter : ClientQuestCache.chapters()) {
            if (chapterHidden(chapter.id())) {
                // A chapter its author asked to withhold until its gate is met is not a chapter the book
                // knows about yet -- and this map is what the book opens *by*, so leaving it in would
                // make the hidden chapter the one a reader lands on with nothing selected.
                continue;
            }
            chapters.putIfAbsent(chapter.id(), chapter.titleText());
        }
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            if (chapterHidden(entry.chapterId())) {
                continue;
            }
            chapters.putIfAbsent(entry.chapterId(), entry.chapterTitleText());
        }
        chaptersHeld = chapters;
        chaptersRevision = revision;
        chaptersProgress = progress;
        chaptersEditing = BookCanvas.editingView;
        return chapters;
    }

    /** The chapter map as it was last built, and the tree it was built from. See {@link #chapters}. */
    private static Map<String, String> chaptersHeld;
    private static long chaptersRevision = Long.MIN_VALUE;

    /** And the other two inputs that map's contents now depend on. See {@link #chapters}. */
    private static long chaptersProgress = Long.MIN_VALUE;
    private static boolean chaptersEditing;

    static String computeEffectiveChapter() {
        Map<String, String> chapters = chapters();
        if (chapters.isEmpty()) {
            return null;
        }
        if (BookScreenState.selectedChapter != null && chapters.containsKey(BookScreenState.selectedChapter)) {
            return BookScreenState.selectedChapter;
        }
        return chapters.keySet().iterator().next();
    }

    /** The chapter's quests as they were last built, and every input they were built from. */
    List<ClientQuestCache.Entry> questsInHeld;
    String questsInChapter;
    long questsInTree = Long.MIN_VALUE;
    long questsInProgress = Long.MIN_VALUE;
    boolean questsInAuthoring;

    /**
     * Whether a quest is drawn at all: {@code QuestVisibility}'s rules, read off the client's cache.
     *
     * <p>The three functions the rules take are the client's three answers: the team's state for a
     * quest, whether its prerequisite rule is met (through {@code DependencyProgress}, so
     * {@code minRequired} and the started-based modes are the same here as everywhere else), and how
     * many of its tasks have any progress.
     */
    static boolean questVisible(String id) {
        // Remembered per tree and per progress, and those two are the whole of the answer's inputs: the
        // rules read the quest's own flags (the tree), its state, its prerequisite rule and how many of its
        // tasks have progress (progress) — and the exclusion answer below reads the file's setting (the
        // tree) and the server's mark (progress). Nothing here reads a draft or the view.
        //
        // It is worth remembering because `drawNode` asks it for **every node it draws**, every frame, in
        // edit mode — and each call is a recursive walk of the prerequisite chain that allocates a deque
        // before it starts. At nine hundred nodes that is nine hundred graph walks a frame to decide whether
        // to draw a dashed "hidden" mark.
        long tree = ClientQuestCache.treeRevision();
        long progress = ClientQuestCache.progressRevision();
        if (visibleCache == null || visibleTree != tree || visibleProgress != progress) {
            visibleCache = new HashMap<>();
            visibleTree = tree;
            visibleProgress = progress;
        }
        Boolean known = visibleCache.get(id);
        if (known != null) {
            return known;
        }
        // The exclusion answer travels with the rule's own inputs — the file's setting on the tree,
        // the server's mark on the progress — so the tree-plus-progress key above stays the whole of
        // what this answer depends on. See `QuestVisibility.visible` for the rule itself.
        boolean answer = dev.ellipog.tenet.client.dev.QuestVisibility.visible(id, VISIBILITY_LOOKUP,
                ClientQuestCache::stateOf, BookContent::prerequisiteRuleMet,
                BookContent::tasksWithProgress, ClientQuestCache::excludedOf,
                ClientQuestCache.hideExcludedQuests());
        visibleCache.put(id, answer);
        return answer;
    }

    /** The visibility answers for one tree and one progress revision. See {@link #questVisible}. */
    private static Map<String, Boolean> visibleCache;
    private static long visibleTree = Long.MIN_VALUE;
    private static long visibleProgress = Long.MIN_VALUE;

    /**
     * Whether a chapter is withheld from this viewer.
     *
     * <h2>The rule is {@code ChapterVisibility}'s; this is the cache and the answers</h2>
     *
     * <p>Three things hide a chapter from a reader: its author asking for it to be always
     * invisible, its author asking to be withheld until its own gate is met, and it having
     * <b>nothing visible in it</b> — every quest still hidden behind its own
     * prerequisites, every quest an unmoved easter egg, or no quests at all. The third is the base rule
     * and it is why the earlier version of this method was wrong: it only asked about the author's flag,
     * so a chapter of fifty quests that none of which were visible yet still drew a row into an empty
     * canvas — and so did a chapter with a single note in it and nothing else.
     *
     * <p>An author bypasses all three rules, which is a parameter of the rule rather than a second check here,
     * so the whole of it can be asserted in {@code ChapterVisibilityTest} — the screen cannot be
     * instantiated by a test, and this is behaviour a person only ever sees as a missing row.
     */
    static boolean chapterHidden(String chapterId) {
        return !dev.ellipog.tenet.client.dev.ChapterVisibility.visible(chapterId,
                CHAPTER_VISIBILITY_LOOKUP, BookCanvas.editingView);
    }

    /** The cache's answer to {@code ChapterVisibility}'s questions. */
    private static final dev.ellipog.tenet.client.dev.ChapterVisibility.Lookup CHAPTER_VISIBILITY_LOOKUP =
            new dev.ellipog.tenet.client.dev.ChapterVisibility.Lookup() {
                @Override
                public boolean hidesUntilDependenciesComplete(String chapterId) {
                    ClientQuestCache.ChapterEntry chapter = chapterEntryFor(chapterId);
                    return chapter != null && chapter.hideUntilDependenciesComplete();
                }

                @Override
                public boolean alwaysInvisible(String chapterId) {
                    ClientQuestCache.ChapterEntry chapter = chapterEntryFor(chapterId);
                    return chapter != null && chapter.alwaysInvisible();
                }

                @Override
                public QuestState state(String chapterId) {
                    return ClientQuestCache.chapterStateOf(chapterId);
                }

                @Override
                public boolean hasVisibleQuest(String chapterId) {
                    return chaptersWithVisibleQuests().contains(chapterId);
                }
            };

    /**
     * Which chapters hold at least one quest this reader can see.
     *
     * <h2>Why a set rather than a per-chapter walk</h2>
     *
     * <p>Because it is asked for every chapter, by the sidebar, {@code chapters()} and the jump paths, and
     * a walk of every quest per chapter is a walk of the whole pack per chapter — a nine-hundred-quest
     * book would pay for itself twelve times to answer one question twelve ways. One pass collects the
     * answer for all of them, and it is remembered per tree and per progress, which is the whole of its
     * inputs: {@code questVisible} is itself cached on those two, so this pass is a set insert per entry
     * rather than a second graph walk.
     *
     * <p>Deliberately <b>not</b> keyed on the editing flag, unlike {@link #chapters()}: this is the
     * reader's answer, and an author never asks it — {@code ChapterVisibility} answers "visible" for an
     * author before consulting it at all.
     */
    private static Set<String> chaptersWithVisibleQuests() {
        long tree = ClientQuestCache.treeRevision();
        long progress = ClientQuestCache.progressRevision();
        if (visibleChapters != null && visibleChaptersTree == tree
                && visibleChaptersProgress == progress) {
            return visibleChapters;
        }
        Set<String> shown = new HashSet<>();
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            if (questVisible(entry.id())) {
                shown.add(entry.chapterId());
            }
        }
        visibleChapters = Set.copyOf(shown);
        visibleChaptersTree = tree;
        visibleChaptersProgress = progress;
        return visibleChapters;
    }

    /** The chapters with something visible in them, and the tree and progress they were read from. */
    private static Set<String> visibleChapters;
    private static long visibleChaptersTree = Long.MIN_VALUE;
    private static long visibleChaptersProgress = Long.MIN_VALUE;

    /**
     * The server's own record of a chapter, or null for one only the quests name.
     *
     * <p>Null rather than a synthesised entry, because a chapter that arrived as a property of its quests
     * is a chapter of a server older than the explicit list — and such a server sends no gate either, so
     * "not in the list" and "no gate" are the same answer here.
     */
    static ClientQuestCache.ChapterEntry chapterEntryFor(String chapterId) {
        for (ClientQuestCache.ChapterEntry chapter : ClientQuestCache.chapters()) {
            if (chapter.id().equals(chapterId)) {
                return chapter;
            }
        }
        return null;
    }

    /** The cache's answer to the visibility rules' questions, for any quest id -- other chapters too. */
    private static final dev.ellipog.tenet.client.dev.QuestVisibility.Lookup VISIBILITY_LOOKUP =
            new dev.ellipog.tenet.client.dev.QuestVisibility.Lookup() {
                @Override
                public boolean invisible(String id) {
                    ClientQuestCache.Entry entry = cacheEntryFor(id);
                    return entry != null && entry.invisible();
                }

                @Override
                public int invisibleUntilTasks(String id) {
                    ClientQuestCache.Entry entry = cacheEntryFor(id);
                    return entry == null ? 0 : entry.invisibleUntilTasks();
                }

                @Override
                public boolean hideUntilDependenciesComplete(String id) {
                    ClientQuestCache.Entry entry = cacheEntryFor(id);
                    return entry != null && entry.hideUntilDependenciesComplete();
                }

                @Override
                public boolean hideUntilDependenciesVisible(String id) {
                    ClientQuestCache.Entry entry = cacheEntryFor(id);
                    return entry != null && entry.hideUntilDependenciesVisible();
                }

                @Override
                public List<String> dependencies(String id) {
                    ClientQuestCache.Entry entry = cacheEntryFor(id);
                    return entry == null ? List.of() : QuestBookScreen.dependenciesOf(entry);
                }
            };

    /** Whether a quest's prerequisite rule is satisfied. An unknown quest has none, so it is met. */
    private static boolean prerequisiteRuleMet(String id) {
        ClientQuestCache.Entry entry = cacheEntryFor(id);
        return entry == null || BookSettings.dependencyProgressOf(entry).met(ClientQuestCache::stateOf);
    }

    /** How many of a quest's tasks have any progress: what `invisibleUntilTasks` counts. */
    private static int tasksWithProgress(String id) {
        ClientQuestCache.Entry entry = cacheEntryFor(id);
        if (entry == null) {
            return 0;
        }
        int touched = 0;
        for (int i = 0; i < entry.tasks().size(); i++) {
            if (ClientQuestCache.taskProgressOf(id, i) > 0) {
                touched++;
            }
        }
        return touched;
    }

    /** The same lookup, static: the visibility rules are static and have to reach the cache too. */
    static ClientQuestCache.Entry cacheEntryFor(String questId) {
        if (questId == null) {
            return null;
        }
        // By id or alias, in any letter case: the tree carries the aliases since version 16, and a
        // press naming a pre-rename spelling opens the quest rather than reporting a broken control.
        // An older server sends none, and the match falls back to the id alone.
        return ClientQuestCache.entries().stream()
                .filter(entry -> entry.matches(questId))
                .findFirst()
                .orElse(null);
    }
}
