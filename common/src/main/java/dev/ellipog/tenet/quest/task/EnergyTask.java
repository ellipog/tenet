package dev.ellipog.tenet.quest.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.armature.api.energy.EnergyAccesses;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;
import dev.ellipog.tenet.quest.TaskContext;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.function.Function;

/**
 * Hand over stored energy, in Forge Energy units, carried in the player's items.
 *
 * <pre>{@code { "type": "tenet:energy", "value": 1000000 } }</pre>
 *
 * <p>the legacy {@code forge_energy} task, which is filled by piping energy into a task screen
 * block. Tenet has no such block, so this is the carried-container path — batteries, capacitors
 * and charged tools in the inventory — the same divergence the fluid task documents. A submit
 * drains what it counted; the emptied items stay where they are.
 *
 * <p>{@code maxInput} caps what each item may contribute: zero or absent means unlimited. It is
 * the carried-items translation of the legacy per-insertion input limit — a single large battery
 * cannot finish a quest alone when it is set — with one deliberate difference: the legacy format treats an
 * absent limit as accepting nothing, which would make the migrated ATM10 quest (no
 * {@code max_input} written) unfillable, so here absent means unlimited.
 *
 * <p>Fabric reads Team Reborn Energy's item lookup when the library is installed and zero
 * without it; NeoForge reads the energy-storage capability -- both through Armature's energy
 * seam, which its entry points install. The validator cannot tell the loaders apart, so this
 * is documented rather than warned.
 */
public record EnergyTask(TaskCommon common, int value, int maxInput, boolean manualOnly)
        implements QuestTask {

    public static final Identifier TYPE = Identifier.fromNamespaceAndPath(Tenet.MOD_ID, "energy");

    public static final Set<String> FIELDS = Set.of("value", "maxInput", "manualOnly");

    public static final MapCodec<EnergyTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TaskCommon.mapCodec(20).forGetter(EnergyTask::common),
            Codec.intRange(1, Integer.MAX_VALUE).fieldOf("value").forGetter(EnergyTask::value),
            Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("maxInput", 0)
                    .forGetter(EnergyTask::maxInput),
            Codec.BOOL.optionalFieldOf("manualOnly", false).forGetter(EnergyTask::manualOnly)
    ).apply(instance, EnergyTask::new));

    @Override
    public Identifier type() {
        return TYPE;
    }

    public static final TaskBehaviour<EnergyTask> BEHAVIOUR = new TaskBehaviour<>() {

        @Override
        public int required(EnergyTask task) {
            return task.value();
        }

        @Override
        public boolean readsInventory() {
            return true;
        }

        @Override
        public int current(EnergyTask task, TaskContext context) {
            int stored = EnergyAccesses.current().storedOf(context.player(), task.maxInput());
            return Math.min(stored, task.value());
        }

        @Override
        public boolean canSubmitByHand(EnergyTask task, boolean chapterDefault) {
            return true;
        }

        @Override
        public boolean takesResources(EnergyTask task, boolean chapterDefault) {
            // Always: the energy is what is being asked for, so handing it over is the task.
            return true;
        }

        @Override
        public int take(EnergyTask task, ServerPlayer player, int count) {
            return EnergyAccesses.current().drainFrom(player, count, task.maxInput());
        }

        @Override
        public boolean manualOnly(EnergyTask task) {
            return task.manualOnly();
        }
    };

    public static final Function<EnergyTask, TaskDisplay> DISPLAY = task -> {
        String what = task.value() + " FE";
        return TaskDisplay.ofTranslatableText("tenet.task.energy", "Hand in " + what, what, task.value());
    };
}
