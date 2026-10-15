package dev.ellipog.tenet.client;

import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.reward.RewardDisplay;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * A reward's display, as the one line a row draws.
 *
 * <h2>Why this is a class and not three lines in the panels</h2>
 *
 * <p>Because getting it wrong is invisible until a player sees it, and it was wrong in three places at
 * once. {@link RewardDisplay#label()} is a <b>translation key</b> — {@code tenet.reward.xp.points},
 * {@code tenet.reward.table} — and its {@code labelArg} is the subject that key is formatted with; an
 * item reward carries no label at all, because the item's own name is the label. Drawing {@code label()}
 * straight onto a row therefore prints {@code tenet:reward.xp.points}, and passing the <i>count</i> as
 * the key's argument prints {@code Roll the 1 table} for a table whose id is {@code dice}.
 *
 * <p>{@link ClientQuestCache.RewardEntry#text()} already had the rule for the quest tree; this is the
 * same rule for the two panels that hold a {@link RewardDisplay} directly — the table editor's rows and
 * the choice card's offers — so the three can no longer disagree about what a reward is called.
 */
public final class RewardText {

    private RewardText() {
    }

    /** The line for a reward: the item's own name, or the key's text with its subject. */
    public static String of(RewardDisplay display) {
        return of(display.label(), display.labelFallback(), display.labelArg(), display.count(),
                display.item().map(ref -> ref.item().toString()).orElse(""));
    }

    /**
     * The same, for a display that arrived as fields rather than as a record.
     *
     * @param itemId the item to name, or empty; the item's hover name is the label when it has one
     * @param arg    the key's subject; empty means the key counts something and the count is the subject
     */
    public static String of(String label, String fallback, String arg, int count, String itemId) {
        if (itemId != null && !itemId.isEmpty()) {
            ItemStack item = stackOf(itemId);
            // A missing item shows the id it names rather than a blank: an id with no item behind it is
            // a fact worth telling apart from a row that has no name at all.
            return item.isEmpty() ? itemId : item.getHoverName().getString();
        }
        if (label != null && !label.isEmpty() && fallback != null && !fallback.isEmpty()) {
            String subject = arg == null || arg.isEmpty() ? String.valueOf(Math.max(1, count)) : arg;
            // Through the locale resolver rather than straight to the Component: a pack may translate
            // this key in its own lang file, and that translation is the server's overlay rather than
            // anything the client's Language holds. When it does not, this is the Component call
            // unchanged -- see ClientLocale#text(String, String, Object...).
            return ClientLocale.text(label, fallback, subject);
        }
        return label == null || label.isEmpty() ? "?" : label;
    }

    /**
     * A name with its count in front, for a card that has one column: {@code x4 Iron Ingot}.
     *
     * <p>The rewards inbox draws the count in its own faint ink beside the name, because a row there has
     * two columns to spend. A card that lists what a table holds has one, so the count rides in front of
     * the name — the same reading, written down.
     */
    public static String withCount(String name, int count) {
        return count > 1 ? "x" + count + " " + name : name;
    }

    /** The count to show for a display, or 0 when there is nothing to count. */
    public static int countOf(RewardDisplay display) {
        return display.item().map(ItemRef::count).orElse(0);
    }

    private static ItemStack stackOf(String id) {
        Identifier at = Identifier.tryParse(id);
        return at == null ? ItemStack.EMPTY : new ItemRef(at, 1).toStack();
    }
}
