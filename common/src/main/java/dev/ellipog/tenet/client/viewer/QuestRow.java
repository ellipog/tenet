package dev.ellipog.tenet.client.viewer;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * One line of a quest page: a task to hand in or a reward to claim.
 *
 * <p>The record is both the static shape and the live reading. A snapshot built when the tree arrives
 * fills in the label, the icon and {@code need}, and leaves {@code have} at zero; the same type comes
 * back from {@link QuestContent#liveTask} with the numbers as they are now. That is on purpose: a
 * viewer lays a page out once from the snapshot and redraws its numbers from the live call, so a
 * progress tick never has to rebuild anything.
 *
 * <p>{@code label} is already the player's language -- the content side owns every word, because the
 * words belong to the quest file's author and its translators, not to a viewer adapter.
 *
 * <p>{@code tagId} is {@code namespace:path} when the row names an item tag, empty otherwise, and
 * {@code icon} is empty in that case. It stays a string so this record carries no item-registry
 * knowledge; an adapter turns it into whatever its viewer calls a tag, once per page build rather
 * than once per frame.
 *
 * <p>{@code sourceIndex} is the row's position in the content's own list — the index
 * {@link QuestContent#liveTask} and {@link QuestContent#liveReward} expect. A page's rows are the
 * item-referencing subset of that list, so a page index is <i>not</i> a source index, and an adapter
 * that asked for live numbers by page position would read the wrong row (or none).
 *
 * <p>{@code claimable} is a reward's "ready to collect" — finished quest, conditions met, not yet
 * claimed. It is false on every task row, which has progress instead.
 */
public record QuestRow(ItemStack icon, String label, int have, int need, boolean done, boolean locked,
                       boolean claimable, String tagId, int sourceIndex) {

    public QuestRow {
        if (icon == null) {
            icon = ItemStack.EMPTY;
        }
        if (label == null) {
            label = "";
        }
        if (tagId == null) {
            tagId = "";
        }
    }

    /**
     * A row from a source list whose index it does not carry, with nothing to claim — the blank reads
     * and the two adapters that build a row by hand.
     */
    public QuestRow(ItemStack icon, String label, int have, int need, boolean done, boolean locked,
                    String tagId) {
        this(icon, label, have, need, done, locked, false, tagId, 0);
    }

    /** The same, keeping a source index and still claiming nothing: snapshot rows. */
    public QuestRow(ItemStack icon, String label, int have, int need, boolean done, boolean locked,
                    String tagId, int sourceIndex) {
        this(icon, label, have, need, done, locked, false, tagId, sourceIndex);
    }

    /** True when this row names an item tag rather than a concrete stack. */
    public boolean hasTag() {
        return !tagId.isEmpty();
    }

    /** The tag id, parsed once per page build; empty when the row has none. */
    public Optional<Identifier> tag() {
        return hasTag() ? Optional.of(Identifier.parse(tagId)) : Optional.empty();
    }

    /** True when there is anything at all to show: an icon, a tag, or a label. */
    public boolean isBlank() {
        return icon.isEmpty() && !hasTag() && label.isEmpty();
    }

    /** A copy with the live numbers, for {@link QuestContent}'s live calls to build cheaply. */
    public QuestRow withProgress(int have, boolean done, boolean locked) {
        return new QuestRow(icon, label, have, need, done, locked, claimable, tagId, sourceIndex);
    }
}
