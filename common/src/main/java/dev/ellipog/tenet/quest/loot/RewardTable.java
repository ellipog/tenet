package dev.ellipog.tenet.quest.loot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.QuestReward;
import dev.ellipog.tenet.quest.reward.RewardDisplay;
import dev.ellipog.tenet.quest.reward.RewardTypes;

import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A named roll of rewards: the payload of {@code random}, {@code loot}, {@code all_table} and
 * {@code choice}.
 *
 * <pre>{@code
 * { "emptyWeight": 10, "lootSize": 2, "title": "Tier 1 Minerals",
 *   "icon": { "item": "minecraft:iron_ingot" },
 *   "entries": [ { "weight": 0, "reward": { "type": "tenet:item", "item": "minecraft:stone" } },
 *                { "weight": 5, "reward": { "type": "tenet:xp", "amount": 30 } } ] }
 * }</pre>
 *
 * <h2>The roll follows the legacy format, weights and all</h2>
 *
 * <p>A weight-zero entry is <b>always</b> granted, once per roll call — it is how a table says "and
 * everyone also gets this". Every positive weight shares the rest of the probability space, and
 * {@code lootSize} is how many times the dice are thrown. {@code emptyWeight} is the chance of
 * nothing on a throw, and only the {@code loot} mode includes it: a {@code random} reward promises
 * something, and a {@code loot} reward is allowed to promise nothing.
 *
 * <p>Kept as pure arithmetic on a {@link RandomSource} handed in, so the distribution is testable
 * with a seeded source rather than by playing. {@link #rollIndices} is the arithmetic itself — a roll
 * as <i>positions</i> rather than rewards — which is what lets a caller tally a distribution
 * ({@link #odds} explains the chances, and the editor's test roll counts the positions) without a
 * second description of the same dice.
 *
 * <h2>Title, icon and handle</h2>
 *
 * <p>Three fields exist for the visual editor rather than for the roll, and all three are optional so
 * that every file written before them still reads:
 *
 * <ul>
 *   <li>{@code title} — what the table is called in the browser and on a reward's badge. Absent (or
 *       blank, which the compact constructor treats as absent) means the id, prettified.</li>
 *   <li>{@code icon} — the stack a browser row draws. Absent means the first entry that has an item,
 *       then that entry's type icon; see {@link #displayIcon()}.</li>
 *   <li>{@code uid} — the handle of a table written <b>inline</b> in a quest or another table. A file's
 *       root table has no use for one, and the loader ignores it there; see {@code InlineTables} for
 *       why an inline table is addressed by a handle rather than by its position.</li>
 *   <li>{@code useTitle} — draw the table's title on a reward row that rolls it, rather than the
 *       generic "roll the X table" sentence. the legacy {@code use_title}, which ATM10 sets on its
 *       tables.</li>
 *   <li>{@code hideTooltip} — draw no item tooltip on that row. the legacy {@code hide_tooltip}.</li>
 * </ul>
 */
public record RewardTable(double emptyWeight, int lootSize, List<Entry> entries,
                          Optional<String> title, Optional<ItemRef> icon, Optional<String> uid,
                          boolean useTitle, boolean hideTooltip) {

    /** The fields this contributes, for the validator and the editor. */
    public static final Set<String> FIELDS = Set.of("emptyWeight", "lootSize", "entries", "title",
            "icon", "uid", "useTitle", "hideTooltip");

    /**
     * How many times a table may throw, named once.
     *
     * <p>The codec, the validator and the editor's stepper each wrote {@code 1} and {@code 1000}
     * separately, so the bound the panel enforced and the bound the loader enforced were two claims
     * about one rule. The stepper is the one that drifts silently: a bound loosened here and not there
     * is a control that stops one short of what the file allows, and nothing reports it.
     */
    public static final int LOOT_SIZE_MIN = 1;
    public static final int LOOT_SIZE_MAX = 1000;

    /**
     * The deepest a table may nest before a walk gives up, named once.
     *
     * <p>A table whose entry points back at itself is a file an author can write, so every walk over the
     * graph needs a floor under it. There are <b>four</b>: the grant ({@code TableReward.resolve}), the
     * report ({@link TableRoller}), the export ({@link TableExport}) and the loader's dangling-reference
     * check ({@link RewardTableRefs}). Three of them wrote {@code 8} and the fourth wrote it too, which
     * is four descriptions of one rule and four chances for the report to disagree with the grant about
     * what "too deep" means — and the report is the one an author believes.
     *
     * <p>They also have to agree about the <b>comparison</b>, which they did not: the grant and the
     * reference walk cut at {@code depth > MAX_NESTING} while the report and the export cut at
     * {@code >=}, so the two an author reads stopped one level before the one that pays. All four use
     * {@code >} now, which is what this note means by "the deepest a table may nest": nesting <i>at</i>
     * this depth is allowed, and a walk gives up past it.
     */
    public static final int MAX_NESTING = 8;

    /** One entry: a reward, and how much of the table's weight it takes. */
    public record Entry(double weight, QuestReward reward) {

        public static final Set<String> FIELDS = Set.of("weight", "reward");

        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.DOUBLE.optionalFieldOf("weight", 1.0).forGetter(Entry::weight),
                RewardTypes.dispatchCodec().fieldOf("reward").forGetter(Entry::reward)
        ).apply(instance, Entry::new));
    }

    public RewardTable {
        entries = List.copyOf(entries);
        // A blank title is not a title. Normalised here rather than at every read site, because
        // "present but empty" is a value no caller wants: a browser card or a reward label that falls
        // back to the id and one that draws nothing are the same intent, and only one of them is
        // usable. The file itself is normalised on write by the editor's own setter.
        title = title.filter(text -> !text.isBlank());
        uid = uid.filter(text -> !text.isBlank());
    }

    /** A table with no title, icon or handle: what a file that declares none of them means. */
    public RewardTable(double emptyWeight, int lootSize, List<Entry> entries) {
        this(emptyWeight, lootSize, entries, Optional.empty(), Optional.empty(), Optional.empty(),
                false, false);
    }

    public static final MapCodec<RewardTable> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            // Deliberately not `doubleRange(0, ...)`: a negative or non-finite value must still
            // *decode*, so the validator can say what is wrong with it at its own line and column
            // rather than the file vanishing from the load with one generic complaint. The arithmetic
            // reads a negative as zero (see `rollIndices`), which is why such a file still works --
            // and why the validator has two severities for it rather than one.
            Codec.DOUBLE.optionalFieldOf("emptyWeight", 0.0).forGetter(RewardTable::emptyWeight),
            Codec.intRange(LOOT_SIZE_MIN, LOOT_SIZE_MAX).optionalFieldOf("lootSize", 1)
                    .forGetter(RewardTable::lootSize),
            Entry.CODEC.listOf().fieldOf("entries").forGetter(RewardTable::entries),
            Codec.STRING.optionalFieldOf("title").forGetter(RewardTable::title),
            ItemRef.CODEC.optionalFieldOf("icon").forGetter(RewardTable::icon),
            Codec.STRING.optionalFieldOf("uid").forGetter(RewardTable::uid),
            Codec.BOOL.optionalFieldOf("useTitle", false).forGetter(RewardTable::useTitle),
            Codec.BOOL.optionalFieldOf("hideTooltip", false).forGetter(RewardTable::hideTooltip)
    ).apply(instance, RewardTable::new));

    public static final Codec<RewardTable> CODEC = MAP_CODEC.codec();

    /**
     * The weighted roll, as the positions it landed on.
     *
     * <p>The one place the dice are described. Guaranteed entries ({@code weight <= 0}) come first,
     * once each; then {@code lootSize} throws, each landing on one positive-weight entry or on the
     * empty band. {@code includeEmpty} says whether that band is in play — false for {@code random},
     * true for {@code loot}.
     *
     * <p>A table whose positive weights total zero (every entry guaranteed, or no entries at all) is
     * answered without touching the dice: there is nothing to divide by and nothing to pick, and the
     * guaranteed entries <i>are</i> the whole answer. That is a case a freshly made table reaches on
     * its first save, so it is a guard rather than an edge.
     *
     * @return entry positions, guaranteed ones first
     */
    public List<Integer> rollIndices(RandomSource random, boolean includeEmpty) {
        List<Integer> granted = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).weight() <= 0) {
                granted.add(index);
            }
        }

        double empty = includeEmpty ? Math.max(0, emptyWeight) : 0;
        double total = empty;
        for (Entry entry : entries) {
            if (entry.weight() > 0) {
                total += entry.weight();
            }
        }
        if (total <= 0) {
            // Nothing weighted and nothing empty: the guaranteed entries are the whole answer, and
            // picking from an empty probability space is the one thing this must not do.
            return List.copyOf(granted);
        }

        for (int attempt = 0; attempt < lootSize; attempt++) {
            double pick = random.nextDouble() * total;
            if (pick < empty) {
                // This throw landed in the empty band: nothing for it.
                continue;
            }
            double threshold = empty;
            for (int index = 0; index < entries.size(); index++) {
                Entry entry = entries.get(index);
                if (entry.weight() <= 0) {
                    continue;
                }
                threshold += entry.weight();
                if (pick < threshold) {
                    granted.add(index);
                    break;
                }
            }
        }
        return List.copyOf(granted);
    }

    /**
     * The weighted roll: what was granted, the guaranteed entries first.
     *
     * @param includeEmpty whether the empty band is in play — false for {@code random}, true for
     *                     {@code loot}
     */
    public List<QuestReward> roll(RandomSource random, boolean includeEmpty) {
        List<Integer> landed = rollIndices(random, includeEmpty);
        List<QuestReward> granted = new ArrayList<>(landed.size());
        for (int index : landed) {
            granted.add(entries.get(index).reward());
        }
        return List.copyOf(granted);
    }

    /** Every entry once, weights and the empty roll ignored: the all-table reward's grant. */
    public List<QuestReward> all() {
        List<QuestReward> all = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            all.add(entry.reward());
        }
        return List.copyOf(all);
    }

    /** One entry by its position, for the choice picker. Empty when the index is not one. */
    public Optional<QuestReward> choice(int index) {
        return index >= 0 && index < entries.size()
                ? Optional.of(entries.get(index).reward())
                : Optional.empty();
    }

    public int entryCount() {
        return entries.size();
    }

    // ------------------------------------------------------------------
    // What the editor draws
    // ------------------------------------------------------------------

    /**
     * One entry's chance on a single throw.
     *
     * @param always  {@code weight <= 0}: granted whatever the dice say, so a percentage is a lie
     * @param perRoll the share of one throw, in {@code 0..1}; meaningless while {@link #always}
     */
    public record Chance(boolean always, double perRoll) {

        /**
         * The chance of at least one hit across independent throws.
         *
         * <p>What an author actually wants to know when {@code lootSize} is more than one: three
         * throws at 20% each is not a 20% chance of seeing the entry. An always-granted entry is
         * certain, and a table that is never rolled grants nothing.
         */
        public double atLeastOnce(int rolls) {
            if (rolls <= 0) {
                return 0.0;
            }
            return always ? 1.0 : 1.0 - Math.pow(1.0 - perRoll, rolls);
        }
    }

    /**
     * The table's chances as the editor shows them: one per entry, in order, and the empty band's.
     *
     * <p>Computed by the same rule {@link #rollIndices} rolls by, so the percentage on screen and the
     * dice in the file cannot drift apart — the alternative is an author tuning weights against
     * arithmetic nobody runs.
     *
     * @param empty the empty band's share of one throw, {@code 0..1}
     * @param each  one entry's chance per throw, in the table's own order
     */
    public record Odds(double empty, List<Chance> each) {

        public Odds {
            each = List.copyOf(each);
        }
    }

    /**
     * The chances, given the mode this table is being previewed as.
     *
     * <p>{@code includeEmpty} is a property of the <i>mode</i> rather than of the table: a {@code loot}
     * reward includes the empty band, a {@code random} one does not, and the same file can be pointed
     * at by both. So the caller decides — the editor's preview toggle is where an author says which
     * reading they want — and the arithmetic stays the table's.
     */
    public Odds odds(boolean includeEmpty) {
        double empty = includeEmpty ? Math.max(0, emptyWeight) : 0;
        double total = empty;
        for (Entry entry : entries) {
            if (entry.weight() > 0) {
                total += entry.weight();
            }
        }
        List<Chance> each = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            boolean always = entry.weight() <= 0 || total <= 0;
            each.add(new Chance(always, always ? 0.0 : entry.weight() / total));
        }
        return new Odds(total <= 0 ? 0.0 : empty / total, each);
    }

    /**
     * What to call this table, given the id it was loaded under.
     *
     * <p>The declared title, or the id with its underscores opened out — {@code tier_1_ores} reads as
     * "Tier 1 ores" in a browser card without the author having to name anything, which is what makes
     * an unnamed table usable rather than a blank row.
     */
    public String displayTitle(String id) {
        return title.orElseGet(() -> prettify(id));
    }

    /** {@code tier_1_ores} to {@code Tier 1 ores}: readable without shouting. */
    public static String prettify(String id) {
        String opened = id == null ? "" : id.replace('_', ' ').replace('/', ' ').trim();
        if (opened.isEmpty()) {
            return "table";
        }
        // Only the first letter: a namespaced id keeps its namespace lowercase, because
        // "tenet: tier 1 ores" says which pack it came from and an addon's id is not this mod's
        // to re-case.
        return Character.toUpperCase(opened.charAt(0)) + opened.substring(1);
    }

    /**
     * The stack a browser row or a reward badge draws for this table.
     *
     * <p>The declared icon; else the first entry's <b>own</b> item, so a table of ore drops shows an
     * ore rather than the generic chest every table reward type carries; else the first entry's
     * <i>type</i> icon, which is the honest answer for a table of experience or commands; else paper.
     *
     * <p>Never a nested table's contents, by construction: this walks entries of <i>this</i> table and
     * asks each one's registered display, and a table reward's display carries no item of its own. A
     * recursion here would also be a recursion through a data file, which is a crash rather than a
     * message — the same reason {@code TableReward.grantAll} caps its depth.
     */
    public ItemRef displayIcon() {
        if (icon.isPresent()) {
            return icon.get();
        }
        for (Entry entry : entries) {
            RewardDisplay display = RewardTypes.displayOf(entry.reward());
            if (display.item().isPresent()) {
                return display.item().get();
            }
        }
        return entries.isEmpty() ? ItemRef.DEFAULT_ICON : RewardTypes.iconOf(entries.get(0).reward().type());
    }
}
