package dev.ellipog.tenet.quest.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.progress.StageService;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;
import dev.ellipog.tenet.quest.TaskContext;

import net.minecraft.resources.Identifier;

import java.util.Set;
import java.util.function.Function;

/**
 * Have a stage.
 *
 * <pre>{@code { "type": "tenet:stage", "stage": "my_pack:left_the_village" } }</pre>
 *
 * <p>A stage is a named flag a player has -- see {@code ProgressStore}'s note on why it is per player --
 * and this is the task that asks about one. It is the read half of the same feature whose write half is
 * the stage reward, a command, or a script: a pack grants "left_the_village" somewhere and asks about it
 * here, which is how a questline says "you were there" without a bookkeeping item.
 *
 * <p>Nothing is required of the id: a stage exists by being granted, so any resource location is a valid
 * one to ask about, and a typo shows up as a task that never completes rather than as a file that will not
 * load. There is nothing for the validator to check and no list to pick from, which is why the editor draws
 * this as a text field with a hint rather than as a search.
 *
 * <p>Measured, never handed in: whether the flag is set is not something the player can attest to, and a
 * Submit button for it would be a button that either does nothing or lets a player grant their own stages.
 *
 * <p>{@code teamStage} is the legacy {@code team_stage}: ask about the team's stages rather than the
 * player's own. One member's induction then satisfies the task for everybody, which is the multiplayer
 * pack's version of "you were there".
 */
public record StageTask(TaskCommon common, Identifier stage, boolean teamStage) implements QuestTask {

    public static final Identifier TYPE = Identifier.fromNamespaceAndPath(Tenet.MOD_ID, "stage");

    public static final Set<String> FIELDS = Set.of("stage", "teamStage");

    public static final MapCodec<StageTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TaskCommon.mapCodec(20).forGetter(StageTask::common),
            Identifier.CODEC.fieldOf("stage").forGetter(StageTask::stage),
            Codec.BOOL.optionalFieldOf("teamStage", false).forGetter(StageTask::teamStage)
    ).apply(instance, StageTask::new));

    @Override
    public Identifier type() {
        return TYPE;
    }

    public static final TaskBehaviour<StageTask> BEHAVIOUR = new TaskBehaviour<>() {

        /** One flag: you have it or you do not. */
        @Override
        public int required(StageTask task) {
            return 1;
        }

        @Override
        public int current(StageTask task, TaskContext context) {
            net.minecraft.server.MinecraftServer server = context.player().level().getServer();
            if (server == null) {
                // A harness, or a server on its way down: a gate nobody can ask about is not one
                // that blocks, so an unaskable stage reads as absent rather than failing the read.
                return 0;
            }
            if (task.teamStage()) {
                java.util.UUID team =
                        dev.ellipog.armature.api.teams.Teams.teamOf(server,
                                context.player().getUUID()).id();
                return StageService.hasTeam(server, team, task.stage()) ? 1 : 0;
            }
            return StageService.has(server, context.player().getUUID(), task.stage())
                    ? 1 : 0;
        }

        @Override
        public boolean canSubmitByHand(StageTask task, boolean chapterDefault) {
            return false;
        }
    };

    public static final Function<StageTask, TaskDisplay> DISPLAY = task ->
            TaskDisplay.ofTranslatableText("tenet.task.stage", "Have the stage " + task.stage(),
                    task.stage().toString(), 1);
}
