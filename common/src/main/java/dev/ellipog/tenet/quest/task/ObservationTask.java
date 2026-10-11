package dev.ellipog.tenet.quest.task;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.armature.api.data.Codecs;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;
import dev.ellipog.tenet.quest.TaskContext;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Look at something for long enough.
 *
 * <pre>{@code { "type": "tenet:observation", "observeType": "block", "toObserve": "minecraft:beacon", "timer": 40 } }</pre>
 *
 * <p>The one task the <b>client</b> is trusted to decide. There is no server-side way to know what a
 * player is looking at — the server never sees the crosshair — so the client ray-traces each tick,
 * counts how long the target has been held, and submits when the timer is done; the server accepts
 * that submission because the task says {@link #BEHAVIOUR}'s {@code acceptsClientSubmit} is true.
 * Legacy packs do exactly this, and the trust is bounded: the worst a modified client can do is
 * complete an observation task without looking, which is worth no items on its own.
 *
 * <p>{@code current} is zero and {@code canSubmitByHand} is false — there is no button to press,
 * because the watching is the work.
 */
public record ObservationTask(TaskCommon common, ObserveType observeType, String toObserve, int timer)
        implements QuestTask {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tenet.MOD_ID, "observation");

    public static final Set<String> FIELDS = Set.of("observeType", "toObserve", "timer");

    /** What kind of thing {@code toObserve} names. The wire names are the legacy ones. */
    public enum ObserveType {
        BLOCK,
        BLOCK_TAG,
        BLOCK_STATE,
        BLOCK_ENTITY,
        BLOCK_ENTITY_TYPE,
        ENTITY_TYPE,
        ENTITY_TYPE_TAG;

        public static final Codec<ObserveType> CODEC = Codecs.enumByName(ObserveType.class);

        /** The name this takes on the wire and in a file. */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** A wire name back to its value, or null for one this build does not know. */
        public static ObserveType byWire(String name) {
            for (ObserveType value : values()) {
                if (value.wire().equals(name == null ? null : name.toLowerCase(Locale.ROOT))) {
                    return value;
                }
            }
            return null;
        }
    }

    public static final MapCodec<ObservationTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TaskCommon.mapCodec(20).forGetter(ObservationTask::common),
            ObserveType.CODEC.optionalFieldOf("observeType", ObserveType.BLOCK)
                    .forGetter(ObservationTask::observeType),
            Codec.STRING.fieldOf("toObserve").forGetter(ObservationTask::toObserve),
            Codec.intRange(0, 1200).optionalFieldOf("timer", 20).forGetter(ObservationTask::timer)
    ).apply(instance, ObservationTask::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    /**
     * Whether the thing being looked at is the thing named. Split by hit kind so the client's
     * ray-trace is the only part that needs a world — the matching itself is a pure function of what
     * was hit, which is what makes it testable.
     */
    public static boolean matches(HitResult hit, Level level, ObserveType type, String target) {
        if (hit == null || type == null || target == null || target.isEmpty()) {
            return false;
        }
        if (hit instanceof BlockHitResult block) {
            BlockState state = level.getBlockState(block.getBlockPos());
            if (type == ObserveType.BLOCK_ENTITY || type == ObserveType.BLOCK_ENTITY_TYPE) {
                BlockEntity entity = level.getBlockEntity(block.getBlockPos());
                if (entity == null) {
                    return false;
                }
                ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
                if (type == ObserveType.BLOCK_ENTITY_TYPE) {
                    return target.equals(String.valueOf(id));
                }
                // A block entity has no `saveWithoutId` -- that is an Entity method; this is the
                // block-entity counterpart, and the registry access comes from the level it lives in.
                return nbtSubset(target, entity.saveWithoutMetadata(level.registryAccess()));
            }
            if (type == ObserveType.ENTITY_TYPE || type == ObserveType.ENTITY_TYPE_TAG) {
                return false;
            }
            return matchesBlock(state, type, target);
        }
        if (hit instanceof EntityHitResult entityHit) {
            return matchesEntity(entityHit.getEntity(), type, target);
        }
        return false;
    }

    /** Whether a block state answers the filter. Pure, so a test can hand it any state. */
    public static boolean matchesBlock(BlockState state, ObserveType type, String target) {
        return switch (type) {
            case BLOCK -> target.equals(String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())));
            case BLOCK_TAG -> {
                ResourceLocation id = ResourceLocation.tryParse(target);
                yield id != null && state.is(TagKey.create(Registries.BLOCK, id));
            }
            case BLOCK_STATE -> {
                try {
                    var parsed = net.minecraft.commands.arguments.blocks.BlockStateParser
                            .parseForBlock(BuiltInRegistries.BLOCK.asLookup(), target, false);
                    yield parsed.blockState().equals(state);
                }
                catch (CommandSyntaxException e) {
                    yield false;
                }
            }
            default -> false;
        };
    }

    /** Whether an entity answers the filter. Pure for the same reason as {@link #matchesBlock}. */
    public static boolean matchesEntity(Entity entity, ObserveType type, String target) {
        if (entity == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return switch (type) {
            case ENTITY_TYPE -> target.equals(String.valueOf(id));
            case ENTITY_TYPE_TAG -> {
                ResourceLocation tag = ResourceLocation.tryParse(target);
                yield tag != null && entity.getType().is(TagKey.create(Registries.ENTITY_TYPE, tag));
            }
            default -> false;
        };
    }

    /** A target's NBT filter against a block entity's saved tag: subset match, like the kill task's. */
    private static boolean nbtSubset(String filterText, CompoundTag actual) {
        try {
            return NbtUtils.compareNbt(TagParser.parseTag(filterText), actual, true);
        }
        catch (CommandSyntaxException e) {
            return false;
        }
    }

    public static final TaskBehaviour<ObservationTask> BEHAVIOUR = new TaskBehaviour<>() {

        @Override
        public int required(ObservationTask task) {
            return 1;
        }

        @Override
        public int current(ObservationTask task, TaskContext context) {
            // The client counts the ticks and submits; the server records the submission. See the
            // class note for why the trust is here and nowhere else.
            return 0;
        }

        @Override
        public boolean canSubmitByHand(ObservationTask task, boolean chapterDefault) {
            // No button: there is nothing to confirm, and a press would be a lie about the work.
            return false;
        }

        @Override
        public boolean acceptsClientSubmit(ObservationTask task, boolean chapterDefault) {
            // ...but the client that did the looking may submit on the player's behalf.
            return true;
        }
    };

    public static final Function<ObservationTask, TaskDisplay> DISPLAY = task -> {
        String what = task.toObserve() + " for " + task.seconds() + "s";
        return TaskDisplay.ofTranslatableText("tenet.task.observation", "Observe " + what, what, 1);
    };

    /** The timer as a person reads it. */
    public String seconds() {
        return String.format(Locale.ROOT, "%.1f", timer / 20.0);
    }

    /** The tag the block filter names, for a message; unused by matching itself. */
    static TagKey<Block> blockTag(String target) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.parse(target));
    }
}
