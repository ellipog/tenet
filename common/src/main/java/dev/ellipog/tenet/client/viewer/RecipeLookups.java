package dev.ellipog.tenet.client.viewer;

import dev.ellipog.tenet.Constants;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * The seam the book points back through: "show me how this is made".
 *
 * <h2>The other direction, and why it needs its own seam</h2>
 *
 * <p>{@link Integrations} carries quests into a viewer. This carries a click the other way: a player
 * reads a task or a reward, presses its row, and the chosen viewer opens on the recipes that produce
 * what the row names. The book cannot call a viewer itself — a viewer's packages may appear in one
 * file each, and the book names none of them — so the chosen adapter installs one of these, and the
 * book holds only the neutral type.
 *
 * <h2>One installs, and it is the adapter that already won</h2>
 *
 * <p>Each adapter installs only after {@link Viewers#mayInstall} says it is the one registering, so
 * the same priority chain that decides which viewer draws quest pages decides which one answers a
 * row press. With no viewer installed nothing is installed here, and every call is a no-op — the
 * book's click path must not be the one place a soft dependency becomes hard.
 *
 * <h2>Tags are part of the vocabulary, and not every viewer has them</h2>
 *
 * <p>A task can ask for a tag rather than an item, and "what do I even deliver" is the question the
 * press exists to answer, so a tag is a target rather than something the caller flattens into one
 * arbitrary member. Whether a viewer can open one is its own answer: {@link Lookup#supportsTags()}
 * is how the book knows to keep the press inert, and the hint hidden, when it cannot.
 */
public final class RecipeLookups {

    /**
     * What a row asks a viewer to open: an item, or an item tag. Exactly one of the two is set.
     */
    public record Target(ItemStack item, TagKey<Item> tag) {

        public Target {
            item = item == null ? ItemStack.EMPTY : item;
        }

        /** The item a row names. */
        public static Target of(ItemStack item) {
            return new Target(item, null);
        }

        /** The tag a row names. */
        public static Target ofTag(TagKey<Item> tag) {
            Objects.requireNonNull(tag, "tag");
            return new Target(ItemStack.EMPTY, tag);
        }

        public boolean isTag() {
            return tag != null;
        }

        /** Nothing to open: an unresolved item, or a tag the caller should not have built. */
        public boolean isEmpty() {
            return !isTag() && item.isEmpty();
        }
    }

    /**
     * One viewer's answer to a row press, installed by that viewer's adapter.
     */
    public interface Lookup {

        /** Opens the viewer on the recipes that produce what the target names. Client thread. */
        void recipesFor(Target target);

        /**
         * Whether this viewer can open a tag at all.
         *
         * <p>False by default, and the one viewer that leaves it so is JEI: the pinned API has no
         * public way to focus a tag, and focusing one arbitrary member instead would be a wrong
         * answer that looks like a right one.
         */
        default boolean supportsTags() {
            return false;
        }
    }

    private static volatile Lookup installed;

    private RecipeLookups() {
    }

    /**
     * Installs the chosen viewer's lookup. Called from the adapter that registered — the same call
     * the viewer chain already gates, so no second priority rule exists here.
     */
    public static void install(Lookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        if (installed != null && installed != lookup) {
            Constants.LOG.debug("recipe lookup replaced by {}", lookup.getClass().getName());
        }
        else {
            Constants.LOG.debug("recipe lookup installed by {}", lookup.getClass().getName());
        }
        installed = lookup;
    }

    /**
     * Whether a press on this target would do anything: a lookup is installed and can take it.
     *
     * <p>What the row's hint is gated on, so a tooltip never offers a press that would do nothing.
     */
    public static boolean canOpen(Target target) {
        return accepts(installed, target);
    }

    /** Opens the target, and answers whether it was opened. */
    public static boolean open(Target target) {
        Lookup lookup = installed;
        if (!accepts(lookup, target)) {
            return false;
        }
        lookup.recipesFor(target);
        return true;
    }

    /**
     * The decision, on its own so a test can ask it of a lookup that is not the installed one.
     *
     * <p>Split out rather than duplicated in {@link #canOpen} and {@link #open}: the tested thing
     * and the used thing are one function, the same reason {@link Viewers#choose} takes a predicate.
     */
    static boolean accepts(Lookup lookup, Target target) {
        if (lookup == null || target == null || target.isEmpty()) {
            return false;
        }
        return !target.isTag() || lookup.supportsTags();
    }
}
