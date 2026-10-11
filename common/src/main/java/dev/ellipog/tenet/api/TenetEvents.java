package dev.ellipog.tenet.api;

import dev.ellipog.armature.api.event.Event;

import dev.ellipog.tenet.quest.Quest;
import dev.ellipog.tenet.quest.QuestReward;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * The quest lifecycle, as events other mods — and KubeJS scripts, through the integration — listen to.
 *
 * <h2>Why these seven</h2>
 *
 * <p>The first four are the moments a pack author's code has an opinion about: a quest starting, a quest
 * completing, one task of it being satisfied, and a reward being handed over. The legacy KubeJS
 * integration exposes the same four, and everything a script does with a quest is built from them. The
 * two stage events came with stages themselves: a stage is granted by a command, a reward or a script,
 * and the thing a script most often wants is to run something <i>when</i> it is granted rather than to
 * poll for it. The click event is the legacy {@code custom_event} click action: a canvas press by id, for
 * the packs whose buttons do something no quest does.
 *
 * <h2>Fired beside the write, in two places</h2>
 *
 * <p>The four progress events fire inside {@link dev.ellipog.tenet.progress.ProgressService}, and
 * {@link #STAGE_ADDED}/{@link #STAGE_REMOVED} fire in {@code StageService.add}/{@code remove} — beside
 * the store write and the sync, which is where that class's own note says they belong. Both are at
 * the moment the engine itself records the change, so an event cannot describe something that was
 * refused, and a listener runs before the caller's next statement. Listener exceptions are the listener's problem;
 * the engine does not catch them, because swallowing a script error would make a broken script look
 * like a quest that silently did nothing.
 *
 * <p>Loader-neutral, like Armature's own events: the same code fires on Fabric and NeoForge, and on
 * an integrated server, because all three run this class.
 */
public final class TenetEvents {

    private TenetEvents() {
    }

    /**
     * A quest was touched for the first time: its first task recorded progress.
     *
     * <p>Not "became visible": the legacy `started` event fires when an object starts, which for a
     * quest is the first sign of progress on it. Visibility is the engine's business and changes on
     * every dependency completion; this fires once per round.
     */
    @FunctionalInterface
    public interface QuestStarted {
        void onQuestStarted(ServerPlayer player, Quest quest);
    }

    /** A quest finished. Fired once per completion, after the record is saved. */
    @FunctionalInterface
    public interface QuestCompleted {
        void onQuestCompleted(ServerPlayer player, Quest quest);
    }

    /** One task of a quest was satisfied. */
    @FunctionalInterface
    public interface TaskCompleted {
        void onTaskCompleted(ServerPlayer player, Quest quest, int taskIndex);
    }

    /** A reward was handed over, once per reward — not once per recipient of a team reward. */
    @FunctionalInterface
    public interface RewardClaimed {
        void onRewardClaimed(ServerPlayer player, Quest quest, QuestReward reward);
    }

    /**
     * A player was granted a stage.
     *
     * <p>Fired only when it actually changed — a reward that grants a stage the player already has is a
     * no-op, and an event that fired for it would make every listener do work to discover nothing happened.
     */
    @FunctionalInterface
    public interface StageAdded {
        void onStageAdded(ServerPlayer player, ResourceLocation stage);
    }

    /** A stage was taken away. Fired only when the player had it. */
    @FunctionalInterface
    public interface StageRemoved {
        void onStageRemoved(ServerPlayer player, ResourceLocation stage);
    }

    /**
     * A player pressed a canvas element whose click is a script event.
     *
     * <p>The id is the {@code namespace:path} the file named, so one listener can sort many packs'
     * presses by id rather than registering per press. The chapter and the element travel because a
     * press is a place as well as an event: two pictures firing one id are still two pictures, and
     * a script that cannot tell them apart cannot answer "which one".
     */
    @FunctionalInterface
    public interface ClickEvent {
        void onClickEvent(ServerPlayer player, ResourceLocation id, String chapterId, String elementId);
    }

    public static final Event<QuestStarted> QUEST_STARTED = new Event<>(listeners ->
            (player, quest) -> {
                for (QuestStarted listener : listeners) {
                    listener.onQuestStarted(player, quest);
                }
            });

    public static final Event<QuestCompleted> QUEST_COMPLETED = new Event<>(listeners ->
            (player, quest) -> {
                for (QuestCompleted listener : listeners) {
                    listener.onQuestCompleted(player, quest);
                }
            });

    public static final Event<TaskCompleted> TASK_COMPLETED = new Event<>(listeners ->
            (player, quest, taskIndex) -> {
                for (TaskCompleted listener : listeners) {
                    listener.onTaskCompleted(player, quest, taskIndex);
                }
            });

    public static final Event<RewardClaimed> REWARD_CLAIMED = new Event<>(listeners ->
            (player, quest, reward) -> {
                for (RewardClaimed listener : listeners) {
                    listener.onRewardClaimed(player, quest, reward);
                }
            });

    public static final Event<StageAdded> STAGE_ADDED = new Event<>(listeners ->
            (player, stage) -> {
                for (StageAdded listener : listeners) {
                    listener.onStageAdded(player, stage);
                }
            });

    public static final Event<StageRemoved> STAGE_REMOVED = new Event<>(listeners ->
            (player, stage) -> {
                for (StageRemoved listener : listeners) {
                    listener.onStageRemoved(player, stage);
                }
            });

    public static final Event<ClickEvent> CLICK_EVENT = new Event<>(listeners ->
            (player, id, chapterId, elementId) -> {
                for (ClickEvent listener : listeners) {
                    listener.onClickEvent(player, id, chapterId, elementId);
                }
            });
}
