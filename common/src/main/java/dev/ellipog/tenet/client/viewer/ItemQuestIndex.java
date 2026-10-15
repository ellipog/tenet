package dev.ellipog.tenet.client.viewer;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Which quests use an item, which award it, and which do either -- the one thing every viewer asks.
 *
 * <p>All three viewers find an item's page through their own ingredient index, so why does this exist?
 * Because the index they build is of <i>pages</i>, and a page is only registered for the quests a
 * viewer knows about: EMI registers them in one batch on a worker thread, and JEI and REI generate
 * them at lookup time. Every one of those paths needs the same answer -- "which quests, in what
 * order, and in which role" -- and three implementations of that walk would be three places for the
 * snapshot to be read wrongly.
 *
 * <p>Two roles, kept apart rather than merged into a boolean, because a viewer words them
 * differently: an item under <i>uses</i> is a task, under <i>recipes</i> a reward. A quest that both
 * uses and awards one item appears once in {@link #questsFor} -- required first -- so a lookup never
 * shows the same quest twice.
 *
 * <p>Built by the content side (it has the quest data) and read by the adapters (they have the
 * viewers). The builder is the only mutable part; a built index is immutable and safe to read from
 * any thread, which is the property EMI's registration depends on.
 */
public final class ItemQuestIndex {

    /**
     * One reference: the item, the quest, and the roles.
     *
     * <p>{@code required} is a task that hands the item in; {@code awarded} is a reward that grants it.
     * An entry with neither is a caller bug and is refused at build time -- the alternative is an
     * index that quietly answers "no quests" to a query it was handed the wrong shape for.
     */
    public record Entry(Identifier item, QuestRef quest, boolean required, boolean awarded) {

        public Entry {
            Objects.requireNonNull(item, "an index entry needs an item");
            Objects.requireNonNull(quest, "an index entry needs a quest");
            if (!required && !awarded) {
                throw new IllegalArgumentException(
                        "an index entry for " + quest.id() + " must be required, awarded, or both");
            }
        }
    }

    private static final ItemQuestIndex EMPTY =
            new ItemQuestIndex(Map.of(), Set.of(), Set.of(), Set.of());

    private final Map<Identifier, List<Entry>> byItem;
    private final Set<Identifier> items;
    private final Set<Identifier> required;
    private final Set<Identifier> awarded;

    private ItemQuestIndex(Map<Identifier, List<Entry>> byItem, Set<Identifier> items,
                           Set<Identifier> required, Set<Identifier> awarded) {
        this.byItem = byItem;
        this.items = items;
        this.required = required;
        this.awarded = awarded;
    }

    /** The index with nothing in it -- what an adapter reads before the tree has arrived. */
    public static ItemQuestIndex empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return byItem.isEmpty();
    }

    /** Every item some quest references, in no particular order. */
    public Set<Identifier> items() {
        return items;
    }

    /**
     * Whether any quest requires this item, or awards it — asked without building anything.
     *
     * <h2>Why a boolean is not a short {@link #questsUsing}</h2>
     *
     * <p>Because the callers that want a boolean want it in bulk. A viewer asks "does anything use
     * this" once per ingredient it is about to show a tab for, so an answer that allocates a list, a
     * set and a copy is three collections per ingredient — and the answer is almost always "no", which
     * is the cheapest possible question to ask badly.
     *
     * <p>Membership rather than an emptiness test on the collected list, so these two read the same
     * entries the lists do: a set cannot disagree with the list it was built from, because both come
     * out of one walk over one {@code byItem} map.
     */
    public boolean uses(Identifier item) {
        return required.contains(item);
    }

    /** The awarding half of {@link #uses}, and asked the same way. */
    public boolean awards(Identifier item) {
        return awarded.contains(item);
    }

    /**
     * The quests that award the item, in insertion order, each once.
     *
     * <p>Includes a quest that requires it too. That is the correct reading of "awards" and it is worth
     * stating, because the version this replaced answered "awards and does not require" — see
     * {@code pass} for what that cost.
     */
    public List<QuestRef> questsAwarding(Identifier item) {
        return collect(item, false);
    }

    /** The quests that require the item, in insertion order, each once. */
    public List<QuestRef> questsUsing(Identifier item) {
        return collect(item, true);
    }

    /**
     * The quests that either require or award the item: the required ones first, then the awarded
     * ones that are not already there. The order matters to a row list, which reads top to bottom,
     * and so does the dedupe, which is what stops one quest appearing as both.
     */
    public List<QuestRef> questsFor(Identifier item) {
        List<Entry> hits = byItem.get(item);
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<QuestRef> out = new ArrayList<>(hits.size());
        Set<String> seen = new HashSet<>(hits.size());
        pass(hits, true, seen, out);
        pass(hits, false, seen, out);
        return List.copyOf(out);
    }

    private List<QuestRef> collect(Identifier item, boolean required) {
        List<Entry> hits = byItem.get(item);
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<QuestRef> out = new ArrayList<>(hits.size());
        Set<String> seen = new HashSet<>(hits.size());
        pass(hits, required, seen, out);
        return List.copyOf(out);
    }

    private static void pass(List<Entry> hits, boolean required, Set<String> seen, List<QuestRef> out) {
        for (Entry entry : hits) {
            // The role that was asked about, not "is it not the other one". Those two are the same
            // question only while no entry has both roles, and one can: a quest that hands in eight
            // logs and gives back a stack of them is an entry that requires *and* awards. Filtering the
            // awarding pass on `!required` therefore dropped it from every viewer's output side -- the
            // quest was unfindable from the item it pays out -- while `questsFor` happened to stay
            // right, because the required pass had already added it. Found by a test asserting the two
            // answers agree; see ItemQuestIndexTest.
            boolean inRole = required ? entry.required() : entry.awarded();
            if (inRole && seen.add(entry.quest().id())) {
                out.add(entry.quest());
            }
        }
    }

    /** Fills an index. One builder per snapshot; not thread-safe, and never shared. */
    public static final class Builder {

        private final Map<Identifier, List<Entry>> byItem = new HashMap<>();
        private final Set<Identifier> items = new LinkedHashSet<>();
        // The two roles, accumulated as the entries arrive rather than derived at build time: the
        // entries are already being walked here, and a second walk to sort them into two sets would be
        // the same work done twice for the same answer.
        private final Set<Identifier> required = new LinkedHashSet<>();
        private final Set<Identifier> awarded = new LinkedHashSet<>();

        private Builder() {
        }

        public Builder add(Entry entry) {
            Objects.requireNonNull(entry, "entry");
            byItem.computeIfAbsent(entry.item(), key -> new ArrayList<>(2)).add(entry);
            items.add(entry.item());
            if (entry.required()) {
                required.add(entry.item());
            }
            if (entry.awarded()) {
                awarded.add(entry.item());
            }
            return this;
        }

        /** Convenience for the common shape: a quest that uses an item, awards one, or both. */
        public Builder add(Identifier item, QuestRef quest, boolean required, boolean awarded) {
            return add(new Entry(item, quest, required, awarded));
        }

        public ItemQuestIndex build() {
            if (byItem.isEmpty()) {
                return EMPTY;
            }
            Map<Identifier, List<Entry>> frozen = new HashMap<>(byItem.size());
            byItem.forEach((item, entries) -> frozen.put(item, List.copyOf(entries)));
            return new ItemQuestIndex(Collections.unmodifiableMap(frozen),
                    Collections.unmodifiableSet(new LinkedHashSet<>(items)),
                    Collections.unmodifiableSet(new LinkedHashSet<>(required)),
                    Collections.unmodifiableSet(new LinkedHashSet<>(awarded)));
        }
    }
}
