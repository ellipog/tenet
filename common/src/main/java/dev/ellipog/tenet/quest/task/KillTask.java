package dev.ellipog.tenet.quest.task;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TagKeys;
import dev.ellipog.tenet.quest.TaskCommon;
import dev.ellipog.tenet.quest.TaskContext;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Kill an entity — by type, by tag, optionally only ones with a name or matching an NBT filter.
 *
 * <pre>{@code { "type": "tenet:kill", "entity": "minecraft:zombie", "value": 10 } }</pre>
 *
 * <p>The one type that is told rather than asked. A lifetime stat can say how many zombies a player
 * has ever killed, but not whether the one that just died was called "Bob" or carried a named sword,
 * so this type hangs off the death event and the engine records what the event reports. Progress
 * arrives through {@link dev.ellipog.tenet.progress.ProgressService#onEntityDeath}.
 *
 * <p>{@code current} is therefore always zero: the count lives in recorded progress, which is what
 * makes it survive a restart, pool across a party and reset with a repeatable round, without this
 * type owning a single counter of its own.
 */
public record KillTask(TaskCommon common, Optional<ResourceLocation> entity,
                       Optional<ResourceLocation> entityTypeTag, int value,
                       Optional<String> customName, Optional<String> nbtFilter) implements QuestTask {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tenet.MOD_ID, "kill");

    public static final Set<String> FIELDS =
            Set.of("entity", "entityTypeTag", "value", "customName", "nbtFilter");

    public static final MapCodec<KillTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TaskCommon.mapCodec(20).forGetter(KillTask::common),
            ResourceLocation.CODEC.optionalFieldOf("entity").forGetter(KillTask::entity),
            // Its own field rather than a `#` inside `entity`, as the legacy format has it: a tag written
            // where an id is expected is a validator error in every other Tenet field, and this one
            // being the exception is how a pack author learns the wrong rule.
            ResourceLocation.CODEC.optionalFieldOf("entityTypeTag").forGetter(KillTask::entityTypeTag),
            Codec.intRange(1, 1_000_000).optionalFieldOf("value", 1).forGetter(KillTask::value),
            Codec.STRING.optionalFieldOf("customName").forGetter(KillTask::customName),
            Codec.STRING.optionalFieldOf("nbtFilter").forGetter(KillTask::nbtFilter)
    ).apply(instance, KillTask::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    /** Whether a death is one this task counts. */
    public boolean matches(LivingEntity killed) {
        boolean typeMatches;
        if (entityTypeTag().isPresent()) {
            typeMatches = killed.getType().is(TagKeys.of(Registries.ENTITY_TYPE, entityTypeTag().get()));
        }
        else if (entity().isPresent()) {
            typeMatches = entity().get().equals(BuiltInRegistries.ENTITY_TYPE.getKey(killed.getType()));
        }
        else {
            typeMatches = true;
        }
        if (!typeMatches) {
            return false;
        }

        if (customName().isPresent()) {
            String name = killed instanceof Player player
                    ? player.getGameProfile().getName()
                    : killed.getCustomName() == null ? "" : killed.getCustomName().getString();
            if (!customName().get().equals(name)) {
                return false;
            }
        }

        if (nbtFilter().isPresent()) {
            // The filter parsed once per distinct string rather than once per kill: the parse walks
            // the filter's own grammar, and the string never changes under a loaded file. An empty
            // answer matches nothing — the fail-closed fallback, decided here rather than per kill.
            // Ordered before the save on purpose: a broken filter refuses without serialising the
            // whole entity, which is kilobytes per kill the old order paid for nothing.
            Optional<CompoundTag> filter = filterOf(nbtFilter().get());
            if (filter.isEmpty()) {
                return false;
            }
            CompoundTag actual = killed.saveWithoutId(new CompoundTag());
            if (!NbtUtils.compareNbt(filter.get(), actual, true)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parsed NBT filters, by filter string. Absent means the filter does not parse, which matches
     * nothing — see {@link #matches}.
     *
     * <p>Bounded by the pack rather than by a cap: one entry per distinct filter string in the
     * loaded files, and tasks hold the strings, so nothing here can grow past what the files name.
     */
    private static final Map<String, Optional<CompoundTag>> FILTERS = new ConcurrentHashMap<>();

    /** The parsed filter, remembered; empty when it does not parse. See {@link #matches}. */
    private static Optional<CompoundTag> filterOf(String filter) {
        return FILTERS.computeIfAbsent(filter, text -> {
            try {
                return Optional.of(TagParser.parseTag(text));
            }
            catch (CommandSyntaxException unparsable) {
                return Optional.empty();
            }
        });
    }

    public static final TaskBehaviour<KillTask> BEHAVIOUR = new TaskBehaviour<>() {

        @Override
        public int required(KillTask task) {
            return task.value();
        }

        @Override
        public int current(KillTask task, TaskContext context) {
            // Event-sourced: see the class note. Recorded progress is the count.
            return 0;
        }

        @Override
        public boolean canSubmitByHand(KillTask task, boolean chapterDefault) {
            return false;
        }

        @Override
        public int onEntityDeath(KillTask task, ServerPlayer killer, LivingEntity killed) {
            return task.matches(killed) ? 1 : 0;
        }
    };

    public static final Function<KillTask, TaskDisplay> DISPLAY = task -> {
        // The mob is the subject; the count rides the row's own progress chip, so the sentence
        // reads "Kill Zombie" with "0 / 5" beside it rather than saying the number twice.
        TaskDisplay display = TaskDisplay.ofTranslatableText("tenet.task.kill", "Kill " + task.target(),
                task.target(), task.value());
        // The mob's own egg as the row's picture — but only when one mob is named: a tag takes
        // precedence in matching (see matches), so a tagged task keeps the sword rather than an
        // egg that would name the wrong mob. An entity with no egg keeps it too.
        if (task.entity().isPresent() && task.entityTypeTag().isEmpty()) {
            ItemRef egg = TaskTypes.eggOf(task.entity().get());
            if (egg != null) {
                return display.withTypePicture(egg);
            }
        }
        return display;
    };

    /** The target alone — the entity id, its tag, or the word for "any" — for the row's subject. */
    public String target() {
        return entityTypeTag().map(tag -> "#" + tag)
                .or(() -> entity().map(ResourceLocation::toString))
                .orElse("anything");
    }

    /** The target, said plainly, for the row and for the file's own diagnostics. */
    public String description() {
        String what = target();
        return value() + " x " + what;
    }
}
