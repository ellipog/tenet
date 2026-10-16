package dev.ellipog.tenet.quest.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TagKeys;
import dev.ellipog.tenet.quest.TaskCommon;
import dev.ellipog.tenet.quest.TaskContext;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Break a block — by id, by tag, optionally narrowed to one block state.
 *
 * <pre>{@code { "type": "tenet:break", "block": "minecraft:stone", "value": 10 } }</pre>
 * <pre>{@code { "type": "tenet:break", "blockState": "minecraft:wheat[age=7]", "value": 20 } }</pre>
 *
 * <p>The block-breaking sibling of the kill task, and event-sourced for the same reason.
 * A lifetime mining statistic can say how much stone a player has ever broken, but not
 * whether the wheat that just broke was fully grown — so this type hangs off the break
 * event and the engine records what the event reports. Progress arrives through
 * {@link dev.ellipog.tenet.progress.ProgressService#onBlockBroken}.
 *
 * <p>{@code current} is therefore always zero: the count lives in recorded progress, which is what
 * makes it survive a restart, pool across a party and reset with a repeatable round, without this
 * type owning a single counter of its own.
 *
 * <p>What counts is the <b>act</b>, not the drop: silk touch, fortune and a voided drop all
 * count, because the event fires for the break either way. A cancelled break — a protected
 * block — never reaches the event at all, so it counts nothing.
 *
 * <p>Breaks in creative mode count, like every other task type counts there: creative trivializes
 * all progression equally (a gift completes an item task the same way), so no task filters by game
 * mode and a per-type filter would be incoherent. Verified live, not assumed.
 *
 * <p>Breaks by a fake player — a contraption's hands rather than a person's — count only when the
 * task opts in with {@code countFakePlayers}. The default is off, so automation cannot farm a
 * quest unasked; a pack that wants "build a machine that farms a thousand wheat" sets the flag
 * and the machine's breaks count. Asked of the loader through Armature, because vanilla marks
 * nothing and each loader answers it its own way.
 */
public record BreakTask(TaskCommon common, Optional<Identifier> block,
                        Optional<Identifier> blockTag, Optional<String> blockState, int value,
                        boolean countFakePlayers)
        implements QuestTask {

    public static final Identifier TYPE = Identifier.fromNamespaceAndPath(Tenet.MOD_ID, "break");

    public static final Set<String> FIELDS =
            Set.of("block", "blockTag", "blockState", "value", "countFakePlayers");

    public static final MapCodec<BreakTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TaskCommon.mapCodec(20).forGetter(BreakTask::common),
            Identifier.CODEC.optionalFieldOf("block").forGetter(BreakTask::block),
            // Its own field rather than a `#` inside `block`, as the kill task has it: a tag written
            // where an id is expected is a validator error in every other Tenet field, and this one
            // being the exception is how a pack author learns the wrong rule.
            Identifier.CODEC.optionalFieldOf("blockTag").forGetter(BreakTask::blockTag),
            // A blockstate predicate in the datapack's own spelling, e.g.
            // `minecraft:wheat[age=7]`: the farming filter, narrowing whatever `block` or
            // `blockTag` names — or standing alone, implying its own block.
            Codec.STRING.optionalFieldOf("blockState").forGetter(BreakTask::blockState),
            Codec.intRange(1, 1_000_000).optionalFieldOf("value", 1).forGetter(BreakTask::value),
            // Off unless a pack asks: automation farms nothing by default, and the wheat-farming
            // contraption counts only for the quest that names it.
            Codec.BOOL.optionalFieldOf("countFakePlayers", false).forGetter(BreakTask::countFakePlayers)
    ).apply(instance, BreakTask::new));

    @Override
    public Identifier type() {
        return TYPE;
    }

    /** Whether a broken state is one this task counts. Pure, so a test can hand it any state. */
    public boolean matches(BlockState state) {
        boolean blockMatches;
        if (blockTag().isPresent()) {
            blockMatches = state.is(TagKeys.of(Registries.BLOCK, blockTag().get()));
        }
        else if (block().isPresent()) {
            blockMatches = block().get().equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        }
        else if (blockState().isPresent()) {
            // A lone state predicate implies its own block: there is nothing else to match
            // against, so the predicate below is the whole answer.
            blockMatches = true;
        }
        else {
            blockMatches = true;
        }
        if (!blockMatches) {
            return false;
        }

        if (blockState().isPresent()) {
            // The observation task's own matcher, rather than a second one: a state answers
            // one question here and one question there, and two matchers would be two
            // chances to disagree about what `minecraft:wheat[age=7]` means. Fail-closed
            // like the kill task's NBT filter — a predicate that does not parse matches
            // nothing, and the validator should have caught it at the file's line.
            return ObservationTask.matchesBlock(state, ObservationTask.ObserveType.BLOCK_STATE,
                    blockState().get());
        }
        return true;
    }

    public static final TaskBehaviour<BreakTask> BEHAVIOUR = new TaskBehaviour<>() {

        @Override
        public int required(BreakTask task) {
            return task.value();
        }

        @Override
        public int current(BreakTask task, TaskContext context) {
            // Event-sourced: see the class note. Recorded progress is the count.
            return 0;
        }

        @Override
        public boolean canSubmitByHand(BreakTask task, boolean chapterDefault) {
            return false;
        }

        @Override
        public int onBlockBroken(BreakTask task, ServerPlayer player, BlockState state) {
            if (!countsBreaker(task, isFakeBreaker(player))) {
                return 0;
            }
            return task.matches(state) ? 1 : 0;
        }
    };

    /**
     * Whether this breaker counts for this task: a person always does, and a fake player only
     * does for a task that opts in. Pure, so a test can hold the whole decision table without
     * a loader — the platform call stays a one-liner in the behaviour above.
     */
    static boolean countsBreaker(BreakTask task, boolean fake) {
        return !fake || task.countFakePlayers();
    }

    /**
     * Whether this breaker is a fake player. The platform layer is not installed in headless
     * unit tests — and there are no fake players there — so an uninstalled platform reads as
     * a person rather than throwing. The same shape as the config-dir read: see
     * {@code PinnedQuests}, which answers an unreadable platform the same way.
     */
    private static boolean isFakeBreaker(ServerPlayer player) {
        try {
            return ArmatureApi.platform().isFakePlayer(player);
        }
        catch (RuntimeException e) {
            return false;
        }
    }

    public static final Function<BreakTask, TaskDisplay> DISPLAY = task -> {
        // The block is the subject; the count rides the row's own progress chip, so the sentence
        // reads "Break Stone" with "0 / 10" beside it rather than saying the number twice.
        TaskDisplay display = TaskDisplay.ofTranslatableText("tenet.task.break", "Break " + task.target(),
                task.target(), task.value());
        // The block's own item as the row's picture — but only when one block is named: a tag takes
        // precedence in matching (see matches), so a tagged task keeps the pickaxe rather than an
        // item that would name the wrong block. A block with no item form (air, a fluid, a
        // piston head) keeps it too, as does a lone state predicate, whose block is implied
        // rather than named.
        if (task.block().isPresent() && task.blockTag().isEmpty()) {
            Identifier id = task.block().get();
            if (BuiltInRegistries.ITEM.containsKey(id)) {
                return display.withTypePicture(new ItemRef(id, 1));
            }
        }
        return display;
    };

    /** The target alone — the block id, its tag, its state, or the word for "any" — for the row's subject. */
    public String target() {
        return blockTag().map(tag -> "#" + tag)
                .or(() -> block().map(Identifier::toString))
                .or(() -> blockState())
                .orElse("anything");
    }

    /** The target, said plainly, for the row and for the file's own diagnostics. */
    public String description() {
        String what = target();
        return value() + " x " + what;
    }
}
