package dev.ellipog.tenet.quest.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;
import dev.ellipog.tenet.quest.TaskContext;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.function.Function;

/**
 * Hand over experience: a number of points, or a number of whole levels.
 *
 * <pre>{@code { "type": "tenet:xp", "value": 30, "points": true } }</pre>
 *
 * <p>Manual only, as the legacy XP task is: experience is not something the engine should take the
 * moment a player happens to be carrying enough. The submit button is the consent, and the press
 * takes it.
 */
public record XpTask(TaskCommon common, int value, boolean points) implements QuestTask {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tenet.MOD_ID, "xp");

    public static final Set<String> FIELDS = Set.of("value", "points");

    public static final MapCodec<XpTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TaskCommon.mapCodec(20).forGetter(XpTask::common),
            Codec.intRange(1, 100_000).fieldOf("value").forGetter(XpTask::value),
            Codec.BOOL.optionalFieldOf("points", true).forGetter(XpTask::points)
    ).apply(instance, XpTask::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    /** How much the player has, in the task's own unit. */
    public static int held(ServerPlayer player, boolean points) {
        return points ? player.totalExperience : player.experienceLevel;
    }

    public static final TaskBehaviour<XpTask> BEHAVIOUR = new TaskBehaviour<>() {

        @Override
        public int required(XpTask task) {
            return task.value();
        }

        @Override
        public int current(XpTask task, TaskContext context) {
            return held(context.player(), task.points());
        }

        @Override
        public boolean canSubmitByHand(XpTask task, boolean chapterDefault) {
            return true;
        }

        @Override
        public boolean takesResources(XpTask task, boolean chapterDefault) {
            // Always: a quest that asks for experience and lets you keep it is not a trade, and the
            // xp task exists to be a trade. The chapter's consume-items default is about items.
            return true;
        }

        @Override
        public int take(XpTask task, ServerPlayer player, int count) {
            int taken = Math.min(count, held(player, task.points()));
            if (taken <= 0) {
                return 0;
            }
            if (task.points()) {
                // Negative amounts are how vanilla removes points; it clamps the level and progress
                // itself, so no second arithmetic here can disagree with the HUD.
                player.giveExperiencePoints(-taken);
            }
            else {
                player.giveExperienceLevels(-taken);
            }
            return taken;
        }
    };

    public static final Function<XpTask, TaskDisplay> DISPLAY = task -> {
        String what = task.points() ? task.value() + " XP" : task.value() + " levels";
        return TaskDisplay.ofTranslatableText("tenet.task.xp", "Hand in " + what, what, task.value());
    };
}
