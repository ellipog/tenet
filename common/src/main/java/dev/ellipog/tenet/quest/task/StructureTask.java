package dev.ellipog.tenet.quest.task;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.RegistryRef;
import dev.ellipog.tenet.quest.TagKeys;
import dev.ellipog.tenet.quest.TaskCommon;
import dev.ellipog.tenet.quest.TaskContext;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.Set;
import java.util.function.Function;

/**
 * Be inside a structure, or inside one of a structure tag.
 *
 * <pre>{@code { "type": "tenet:structure", "structure": "minecraft:village_plains" } }</pre>
 *
 * <p>Asked every twenty ticks like the biome task, and answered by walking the structures that
 * contain the player's position and comparing registry keys — which keeps one code path for the id
 * and the tag form rather than two lookups that could disagree.
 */
public record StructureTask(TaskCommon common, RegistryRef structure) implements QuestTask {

    public static final Identifier TYPE = Identifier.fromNamespaceAndPath(Tenet.MOD_ID, "structure");

    public static final Set<String> FIELDS = Set.of("structure");

    public static final MapCodec<StructureTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TaskCommon.mapCodec(20).forGetter(StructureTask::common),
            RegistryRef.CODEC.fieldOf("structure").forGetter(StructureTask::structure)
    ).apply(instance, StructureTask::new));

    @Override
    public Identifier type() {
        return TYPE;
    }

    public static final TaskBehaviour<StructureTask> BEHAVIOUR = new TaskBehaviour<>() {

        @Override
        public int required(StructureTask task) {
            return 1;
        }

        @Override
        public int current(StructureTask task, TaskContext context) {
            ServerLevel level = context.player().level();
            var containing = level.structureManager().getAllStructuresAt(context.player().blockPosition());
            if (containing.isEmpty()) {
                return 0;
            }
            var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            for (Structure structure : containing.keySet()) {
                boolean found = task.structure().tag()
                        ? registry.wrapAsHolder(structure)
                                .is(TagKeys.of(Registries.STRUCTURE, task.structure().id()))
                        : task.structure().id().equals(registry.getKey(structure));
                if (found) {
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public boolean canSubmitByHand(StructureTask task, boolean chapterDefault) {
            return false;
        }
    };

    public static final Function<StructureTask, TaskDisplay> DISPLAY = task ->
            TaskDisplay.ofTranslatableText("tenet.task.structure", "Find " + task.structure(),
                    task.structure().toString(), 1);
}
