package dev.ellipog.tenet.client.viewer;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * What a mod with quests supplies to the viewer seam: the snapshots a page is built from, and the
 * live reads its numbers come from.
 *
 * <p>The split is the whole design, and it is a threading rule before it is anything else. A viewer
 * adapter registers recipes at moments no screen is open and, for EMI, on a thread that is not the
 * client thread. So the data that crosses into registration is immutable snapshots
 * ({@link #index()}, {@link #pages()}), and everything that reads a live cache stays behind methods
 * this interface documents as client-thread-only. An adapter that respects the split cannot race the
 * game; one that calls {@link #liveTask} from a registration thread has broken the one rule.
 *
 * <p>{@link #tick()} is where a content implementation notices its own world changing: it is called
 * on the client thread every client tick, and a cheap revision comparison there is what turns "the
 * tree arrived" into a new snapshot without any viewer having to poll anything.
 *
 * <p>The category members are values, not names: an adapter file never hardcodes a category, so the
 * id, the title and the icon all arrive from here. The words are the content's, which is also where
 * their translations live.
 */
public interface QuestContent {

    /**
     * Called on the client thread every client tick. Must be cheap when nothing has changed; a content
     * implementation compares its own revision and rebuilds its snapshots only when it moved.
     */
    void tick();

    /**
     * The revision the current snapshots were built from. A viewer that registers statically (EMI)
     * compares this against what it last registered to know whether a reload is owed; the others
     * never need it.
     */
    long revision();

    /** The item to quest lookup, as of the last snapshot. Safe to read from any thread. */
    ItemQuestIndex index();

    /** Every quest page, as of the last snapshot. Safe to read from any thread. */
    List<QuestPage> pages();

    /**
     * The page for one quest id, or null — for a viewer that has ids from somewhere else.
     *
     * <h2>Why this is not "walk {@link #pages} and compare"</h2>
     *
     * <p>Because two adapters would write that walk, and both would write it the expensive way round: a
     * lookup answers with the pages for the refs an item index returned, so walking every page per ref is
     * O(pages × refs) for a question whose answer is in a map. That is the same argument
     * {@link ItemQuestIndex} makes for existing at all, one lookup further along — so the map is built
     * where the pages are, and an adapter asks for one.
     *
     * <p>Null is the answer for an id the snapshot does not carry, which is not an error: a ref can name
     * a quest whose page was never built, because a quest with no item rows has no page.
     *
     * <p>Safe to read from any thread, like {@link #pages()} — it is part of the same snapshot.
     */
    QuestPage page(String questId);

    /** A task row with its numbers as they are now. Client thread only. */
    QuestRow liveTask(String questId, int index);

    /** A reward row with its state as it is now. Client thread only. */
    QuestRow liveReward(String questId, int index);

    /** The quest's state, already in the player's language. Client thread only. */
    String stateText(String questId);

    /** The colour that state is drawn in. From the content, because only it knows its own states. */
    int stateColour(String questId);

    /**
     * A reward's standing, so an adapter can draw a status instead of a progress bar.
     *
     * <p>A reward with a bar reads as a task the player still owes; the two rows carry the same icon
     * and count, so the page has to say which is which.
     */
    enum RewardStatus {
        /** Finished quest, conditions met, nothing collected yet. */
        READY,
        /** A condition or a prerequisite holds it shut. */
        LOCKED,
        /** Already collected by this player. */
        CLAIMED
    }

    /** The word for a reward status, in the player's language. */
    Component rewardStatusLabel(RewardStatus status);

    /** Open the book on this quest. Client thread only; a no-op for an id that is gone. */
    void openQuest(String questId);

    /** The viewer category id, e.g. a namespace and path the content owns. */
    Identifier categoryId();

    /** The viewer category's title, for the viewers that take a component rather than a key. */
    Component categoryTitle();

    /** The heading above a page's task rows. The words are the content's, so they translate there. */
    Component tasksLabel();

    /** The heading above a page's reward rows, so a reward is never mistaken for a task. */
    Component rewardsLabel();

    /** The viewer category's icon. */
    ItemStack categoryIcon();
}
