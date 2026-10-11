package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.progress.QuestState;

/**
 * Whether a chapter is a row at all, for the person looking at the book.
 *
 * <h2>Three rules, and the second one is the interesting one</h2>
 *
 * <p><b>A chapter with nothing to show is not a row.</b> That is the base rule, and it covers three
 * cases that a reader cannot tell apart and should not have to: a chapter whose quests are all still
 * hidden behind their own prerequisites, a chapter whose every quest is an easter egg nobody has
 * stumbled onto, and a chapter that holds no quests at all — one between being created and having its
 * first file written. In each case the sidebar row would be a door into an empty canvas, and a book
 * that lists those reads as a book with missing content rather than as one that is holding something
 * back. What "visible" means for a quest is {@link QuestVisibility}'s, and it is asked here rather than
 * answered again: this class only aggregates it.
 *
 * <p><b>A chapter whose own gate is unmet is a row only if its author did not ask otherwise.</b> That
 * flag is {@code hideUntilDependenciesComplete} on the chapter, and it is deliberately <i>not</i> the
 * same as the rule above: by default a gated chapter is listed and drawn dimmed, with what it is
 * waiting for on hover, because a map that shows a closed road is more use than one that omits it. The
 * author may withhold the row instead.
 *
 * <p><b>A chapter flagged always-invisible is a row for nobody but an author.</b> That flag is
 * {@code alwaysInvisible} on the chapter -- the legacy {@code always_invisible} -- and it holds
 * whatever the gate says: a reader never sees the row, while an author still does, or the flag
 * could not be authored. The gate itself is unaffected, and so is the chapter's progress, which
 * reads 100%.
 *
 * <h2>What this is not</h2>
 *
 * <p>Not the gate. A chapter hidden here is still gated by the engine, and a chapter shown here is
 * still refused to a player whose gate is unmet: this decides what a screen draws and nothing else.
 *
 * <p>Not the whole rule either, because an <b>author</b> sees every chapter — a row that vanished
 * while its content was still being written is a row that cannot be edited. That is the same split
 * {@code questsIn} makes for quests, and it is a parameter here rather than a caller's business so the
 * whole rule is assertable in one place.
 *
 * <p>Game-free on purpose, like {@link QuestVisibility}: the screen cannot be instantiated by a test,
 * and "a chapter with fifty quests, none of them visible, is not listed" is exactly the kind of rule
 * that would otherwise only ever be checked by looking at a book.
 */
public final class ChapterVisibility {

    /**
     * What the rules need to know, without the cache.
     *
     * <p>{@code hasVisibleQuest} is a question about the quests inside the chapter, and the answer is
     * the caller's: it already holds the per-quest visibility cache and the filtering that goes with it,
     * and asking it here would mean this class knowing what a quest is.
     */
    public interface Lookup {

        /** Whether the chapter asks to be withheld from a reader until its own gate is met. */
        boolean hidesUntilDependenciesComplete(String chapterId);

        /** Whether the chapter is withheld from every reader, whatever its gate says. */
        boolean alwaysInvisible(String chapterId);

        /** How far the chapter has got, as the server resolved it. */
        QuestState state(String chapterId);

        /** Whether any quest in the chapter is one this reader can see. */
        boolean hasVisibleQuest(String chapterId);
    }

    /**
     * Whether the chapter is a row for this viewer.
     *
     * @param authoring whether the viewer may edit. True answers true: an author sees every chapter,
     *     including an empty one and one every quest of which is still hidden.
     */
    public static boolean visible(String chapterId, Lookup lookup, boolean authoring) {
        if (chapterId == null) {
            // No chapter at all, which is what a screen asks while the tree is still arriving. Nothing
            // to hide, and answering "hidden" there would shut a book over a fact nobody stated.
            return true;
        }
        if (authoring) {
            return true;
        }
        if (lookup.alwaysInvisible(chapterId)) {
            // Withheld from every reader, whatever the gate says: the legacy always_invisible.
            // The gate itself is unaffected -- this decides what a screen draws and nothing else.
            return false;
        }
        if (lookup.hidesUntilDependenciesComplete(chapterId)
                && lookup.state(chapterId) == QuestState.LOCKED) {
            return false;
        }
        // Note what is *not* here: the chapter's own state. A gated chapter whose quests are drawn (as
        // locked nodes) has something to show -- the road ahead -- so it stays a row. Only a chapter
        // with nothing visible at all goes away, which is why the state is not consulted.
        return lookup.hasVisibleQuest(chapterId);
    }

    private ChapterVisibility() {
    }
}
