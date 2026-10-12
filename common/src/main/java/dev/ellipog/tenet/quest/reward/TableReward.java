package dev.ellipog.tenet.quest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.armature.api.data.Codecs;
import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.quest.LazyCodec;
import dev.ellipog.tenet.quest.QuestReward;
import dev.ellipog.tenet.quest.TenetQuests;
import dev.ellipog.tenet.quest.loot.RewardTable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A reward that rolls from a table: {@code random}, {@code loot}, {@code all_table} and
 * {@code choice}.
 *
 * <pre>{@code { "type": "tenet:loot", "table": "dungeon" } }</pre>
 *
 * <p>One record with four modes rather than four records, because they share every field and differ
 * only in what they do with the roll — and the legacy four types differ the same way. The mode is
 * fixed at registration, so a file cannot turn a {@code random} into a {@code choice} by adding a
 * field; the type id <i>is</i> the mode.
 *
 * <h2>Choice does not roll at claim time</h2>
 *
 * <p>{@code random}, {@code loot} and {@code all_table} resolve the moment the reward is granted.
 * {@code choice} cannot: the player picks, so the claim marks nothing, sends the entries to the
 * client, and the answer comes back as its own operation — see
 * {@code ProgressService.claimChoice}. A choice reward therefore stays outstanding until it is
 * answered, which also means a crash between the offer and the pick loses nothing.
 */
public record TableReward(RewardCommon common, Mode mode, Optional<String> table,
                          Optional<RewardTable> inline) implements QuestReward {

    public static final ResourceLocation TYPE_RANDOM =
            ResourceLocation.fromNamespaceAndPath(dev.ellipog.tenet.Tenet.MOD_ID, "random");
    public static final ResourceLocation TYPE_LOOT =
            ResourceLocation.fromNamespaceAndPath(dev.ellipog.tenet.Tenet.MOD_ID, "loot");
    public static final ResourceLocation TYPE_ALL_TABLE =
            ResourceLocation.fromNamespaceAndPath(dev.ellipog.tenet.Tenet.MOD_ID, "all_table");
    public static final ResourceLocation TYPE_CHOICE =
            ResourceLocation.fromNamespaceAndPath(dev.ellipog.tenet.Tenet.MOD_ID, "choice");

    public static final Set<String> FIELDS = Set.of("table", "inline");

    /**
     * A choice cannot be auto-granted: its payout is the player's pick.
     *
     * <p>The other three modes roll or list their entries and pay immediately, so they are as
     * automatic as any item reward. This override is what keeps an auto-claim from marking a choice
     * collected and granting nothing — the offer waits for the claim flow instead.
     */
    @Override
    public boolean autoGrantable() {
        return mode != Mode.CHOICE;
    }

    /** Which of the four table rewards this is. */
    public enum Mode {
        RANDOM,
        LOOT,
        ALL_TABLE,
        CHOICE;

        public static final Codec<Mode> CODEC = Codecs.enumByName(Mode.class);

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * Every mode, in the order a picker cycles them.
         *
         * <p>The ring an author steps through with the preview chip, and the order
         * {@code /tenet table roll} offers its argument in — one list, because a chip that cycled
         * through the four in one order and a command that named them in another is two descriptions
         * of "the four modes".
         */
        public static java.util.List<Mode> all() {
            return java.util.List.of(values());
        }

        /**
         * The mode a wire spelling names, for a payload or a command argument.
         *
         * <p>Empty rather than a fallback. A mode arrives from a client or from something a person
         * typed, and both have a place to be told "no such mode": a report silently read as
         * {@code random} would answer a question nobody asked, with numbers that look right.
         */
        public static java.util.Optional<Mode> ofWire(String wire) {
            for (Mode mode : values()) {
                if (mode.wire().equals(wire)) {
                    return java.util.Optional.of(mode);
                }
            }
            return java.util.Optional.empty();
        }
    }

    /**
     * Whether this reward is a {@code choice}, wherever it is met.
     *
     * <p>One predicate for the three walks that have to decide it — the grant
     * ({@link #resolve}), the report ({@code TableRoller}) and the export
     * ({@code TableExport}) — each of which wrote its own {@code instanceof TableReward} plus a mode
     * test, with its own amount of noise when the answer was yes. A choice cannot be an entry in a
     * table, because a roll hands its entries out rather than offering them; that is the validator's
     * error, and this is what the runtime does about it.
     */
    public static boolean isChoice(QuestReward reward) {
        return reward instanceof TableReward table && table.mode() == Mode.CHOICE;
    }

    /**
     * Why a reward type cannot be an entry in a reward table, or empty when it can.
     *
     * <p>One sentence in one place, because three readers ask it: the validator refuses the entry where
     * it is written, the editor's type picker draws the row blocked with this as its reason, and the
     * grant skips an entry that got in another way. Written out separately in each, the picker and the
     * validator would eventually disagree about what a table may hold — and the picker is the one an
     * author believes, because it is the one that looks like it knows.
     */
    public static String entryRefusal(ResourceLocation type) {
        if (TYPE_CHOICE.equals(type)) {
            return "a choice reward cannot be an entry in a table: a roll hands its entries out rather "
                    + "than offering them, so this entry would be skipped - move it out of the table, or "
                    + "make it an item or a random reward";
        }
        return "";
    }

    /**
     * The codec a mode's registration uses; the mode is bound here, never read from the file.
     *
     * <p>The {@code inline} field goes through {@link LazyCodec} rather than naming
     * {@code RewardTable.CODEC} outright, and that is load-bearing rather than tidiness: the table's
     * entry codec needs the reward dispatch codec, which is built from this very method, so an eager
     * reference here makes the three classes initialise each other in a loop. See {@link LazyCodec}.
     */
    private static final Codec<RewardTable> INLINE_TABLE = LazyCodec.of(() -> RewardTable.CODEC);

    public static MapCodec<TableReward> mapCodec(Mode mode) {
        return RecordCodecBuilder.mapCodec(instance -> instance.group(
                RewardCommon.MAP_CODEC.forGetter(TableReward::common),
                Codec.STRING.optionalFieldOf("table").forGetter(TableReward::table),
                INLINE_TABLE.optionalFieldOf("inline").forGetter(TableReward::inline)
        ).apply(instance, (common, table, inline) -> new TableReward(common, mode, table, inline)));
    }

    @Override
    public ResourceLocation type() {
        return switch (mode) {
            case RANDOM -> TYPE_RANDOM;
            case LOOT -> TYPE_LOOT;
            case ALL_TABLE -> TYPE_ALL_TABLE;
            case CHOICE -> TYPE_CHOICE;
        };
    }

    /**
     * The named table this reward points at, for the loader's cross-file check.
     *
     * <p>Empty for an inline table: there is no reference to dangle.
     */
    @Override
    public Optional<String> tableId() {
        return inline.isPresent() ? Optional.empty() : table;
    }

    /** The table in force: the named one, or the inline one. Empty when neither resolves. */
    public Optional<RewardTable> resolvedTable() {
        return resolvedTable(id -> TenetQuests.rewardTables().get(id));
    }

    /**
     * The same, resolved through a caller's own lookup rather than the loaded questline.
     *
     * <p>What a tool needs: the roll report walks a table graph on the server without caring where the
     * tables came from, and a test builds a graph of three tables in a map. Reading the global registry
     * is the right default for a grant and the wrong dependency for either.
     */
    public Optional<RewardTable> resolvedTable(java.util.function.Function<String, RewardTable> tables) {
        if (inline.isPresent()) {
            return inline;
        }
        return table.map(tables::apply).filter(java.util.Objects::nonNull);
    }

    /** A warning once per failed lookup, and a grant that does nothing: an author's typo, not a crash. */
    private static void reportMissing(TableReward reward) {
        Constants.LOG.warn("{} reward points at a table that is not loaded ({}) and granted nothing",
                reward.mode().wire(), reward.table().orElse("?"));
    }

    public static final RewardBehaviour<TableReward> BEHAVIOUR = (reward, context) -> {
        Optional<RewardTable> resolved = reward.resolvedTable();
        if (resolved.isEmpty()) {
            reportMissing(reward);
            return;
        }
        RewardTable table = resolved.get();
        switch (reward.mode()) {
            case CHOICE -> {
                // Handled by the claim flow: offered to the client, granted when the pick comes back.
            }
            case ALL_TABLE -> grantAll(table.all(), context, 0);
            case RANDOM -> grantAll(table.roll(context.player().getRandom(), false), context, 0);
            case LOOT -> grantAll(table.roll(context.player().getRandom(), true), context, 0);
        }
    };

    /**
     * The leaves a table hands over, with every nested table rolled <b>now</b>.
     *
     * <h2>Why the roll is separated from the grant</h2>
     *
     * <p>Because a strict claim has to ask "does this fit?" before it hands anything over, and a table's
     * contents do not exist until they are rolled. Resolving first means the check and the grant see the
     * same leaves — the alternative is rolling twice, which would check one set of items and hand over
     * another.
     *
     * <p>The depth limit is the cycle guard: a named table whose entry points back at itself is a file
     * an author can write, and unbounded recursion from a data file is a crash, not a message. The
     * limit is {@link RewardTable#MAX_NESTING}, shared with the report and the export so all three
     * give up at the same place.
     */
    public static List<QuestReward> resolve(List<QuestReward> rewards, RandomSource random, int depth) {
        if (depth > RewardTable.MAX_NESTING) {
            Constants.LOG.warn("a reward table nested more than {} deep was cut off -- "
                    + "a table probably references itself", RewardTable.MAX_NESTING);
            return List.of();
        }
        List<QuestReward> out = new java.util.ArrayList<>();
        for (QuestReward entry : rewards) {
            if (entry instanceof TableReward nested) {
                Optional<RewardTable> table = nested.resolvedTable();
                if (table.isEmpty()) {
                    reportMissing(nested);
                    continue;
                }
                if (isChoice(nested)) {
                    // Named rather than "an entry was skipped": the author has to find this in a file,
                    // and the table it points at is what identifies the line.
                    Constants.LOG.warn("a choice reward inside a table cannot be offered, "
                            + "because a roll hands its entries out rather than offering them; "
                            + "skipped the entry naming \"{}\"", nested.table().orElse("an inline table"));
                    continue;
                }
                switch (nested.mode()) {
                    case ALL_TABLE -> out.addAll(resolve(table.get().all(), random, depth + 1));
                    case RANDOM -> out.addAll(resolve(table.get().roll(random, false), random, depth + 1));
                    case LOOT -> out.addAll(resolve(table.get().roll(random, true), random, depth + 1));
                    // Handled above: a choice is skipped before the switch, which is what keeps the
                    // switch exhaustive over the three modes that can actually be rolled.
                    case CHOICE -> {
                    }
                }
            }
            else {
                out.add(entry);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Grants a rolled list, dispatching each leaf through the registry — which is what lets a table
     * hold any reward, including another table.
     */
    public static void grantAll(List<QuestReward> rewards, RewardContext context, int depth) {
        for (QuestReward leaf : resolve(rewards, context.player().getRandom(), depth)) {
            RewardTypes.behaviourOf(leaf).ifPresent(behaviour -> {
                try {
                    behaviour.grant(leaf, context);
                }
                catch (RuntimeException e) {
                    // Same rule as the engine's own grant loop: one bad entry must not stop the
                    // rest of the roll.
                    Constants.LOG.error("granting a {} from a table failed", leaf.type(), e);
                }
            });
        }
    }

    /** The mode as a person reads it. */
    public String description() {
        return mode.wire().replace('_', ' ');
    }

    public static final java.util.function.Function<TableReward, RewardDisplay> DISPLAY = reward -> {
        // The table is the subject, and a table is named by its id -- there is nothing to prettify, so
        // the row spells it. An inline table has no id and says so.
        String what = reward.table().orElse("inline");
        return RewardDisplay.ofTranslatableText("tenet.reward.table", "Roll the " + what + " table",
                what, 1);
    };
}
