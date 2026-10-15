package dev.ellipog.tenet.neoforge;

import dev.ellipog.tenet.api.TenetEvents;
import dev.ellipog.tenet.api.TenetScripts;
import dev.ellipog.tenet.quest.Quest;
import dev.ellipog.tenet.quest.QuestReward;
import dev.ellipog.tenet.quest.reward.CustomReward;
import dev.ellipog.tenet.quest.task.CustomTask;

import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventGroupRegistry;
import dev.latvian.mods.kubejs.event.EventHandler;
import dev.latvian.mods.kubejs.event.KubeEvent;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingRegistry;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.ScriptType;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * The KubeJS integration: what a script sees of Tenet.
 *
 * <h2>NeoForge only, and that is KubeJS's doing rather than a choice</h2>
 *
 * <p>KubeJS 7 — the 1.21 line — is NeoForge-only: its Fabric artifact stops at 1.20.1. So this class lives in
 * {@code :neoforge}, and the operations it exposes live in {@code :common}'s {@link TenetScripts}, which
 * names none of KubeJS's types and would be reusable unchanged if a Fabric integration ever exists again.
 *
 * <h2>How KubeJS finds this class</h2>
 *
 * <p>Through {@code kubejs.plugins.txt}, a plain resource in this module listing plugin class names — KubeJS
 * reads it out of every mod's jar (its own has no service file to copy, and no annotation: the file is the
 * marker). Its absence is silent, which is why the file and the class are written together and why the
 * dev run that proves them is part of the same change rather than a later idea.
 *
 * <h2>Verifying it, and what the verification found</h2>
 *
 * <p>It was run, not assumed: {@code :neoforge:runServer} with KubeJS on the run classpath and a script at
 * {@code run/kubejs/server_scripts/} that prints what it can see. The log then carries KubeJS's own
 * {@code Found plugin source tenet} — the file was read — followed by the script's lines: the {@code Tenet}
 * binding is an object, its methods are callable, a call answers ({@code state(null, "nothing")} is
 * {@code UNKNOWN}), a {@code TenetEvents.stageAdded} listener attaches, and a JS arrow function converts
 * into a custom-type handler — {@code registerTask} and {@code registerReward} land in their registries,
 * which is the one thing here that could not be read off the artifact. Two things that only a real run
 * reveals, both now written into the build: the dev run needs Armature's own mod on the classpath (a player's
 * instance has it; a dev run did not), and modern KubeJS requires a recent NeoForge — hence the version in
 * {@code gradle.properties}.
 *
 * <h2>Two halves to a plugin</h2>
 *
 * <p>{@link #init} connects Tenet's own events to the script events, one for one, so a script listens at the
 * same moments a mod does. {@link #registerBindings} and {@link #registerEvents} publish the {@code Tenet}
 * object and the {@code TenetEvents} group. Both halves must be present for a script to do anything
 * useful, and neither does anything when KubeJS is not installed — this class is only loaded when KubeJS
 * reads the file above.
 */
public final class TenetKubeJsPlugin implements KubeJSPlugin {

    /** The script-visible event group: {@code TenetEvents.questCompleted(event => …)}. */
    private static final EventGroup EVENTS = EventGroup.of("TenetEvents");

    // One per Tenet event, all server-side: a quest's progress is the server's business and a script
    // acting on it must run where the progress is.
    private static final EventHandler QUEST_STARTED = EVENTS.server("questStarted", () -> QuestEvent.class);
    private static final EventHandler QUEST_COMPLETED = EVENTS.server("questCompleted", () -> QuestEvent.class);
    private static final EventHandler TASK_COMPLETED = EVENTS.server("taskCompleted", () -> TaskEvent.class);
    private static final EventHandler REWARD_CLAIMED = EVENTS.server("rewardClaimed", () -> RewardEvent.class);
    private static final EventHandler STAGE_ADDED = EVENTS.server("stageAdded", () -> StageEvent.class);
    private static final EventHandler STAGE_REMOVED = EVENTS.server("stageRemoved", () -> StageEvent.class);
    // And the canvas press, by the id the file named: one handler sorts every pack's presses, because
    // the event carries which id fired rather than being one handler per id.
    private static final EventHandler CLICK_EVENT = EVENTS.server("clickEvent", () -> ClickEvent.class);

    @Override
    public void init() {
        TenetEvents.QUEST_STARTED.register(
                (player, quest) -> QUEST_STARTED.post(new QuestEvent(player, quest)));
        TenetEvents.QUEST_COMPLETED.register(
                (player, quest) -> QUEST_COMPLETED.post(new QuestEvent(player, quest)));
        TenetEvents.TASK_COMPLETED.register(
                (player, quest, index) -> TASK_COMPLETED.post(new TaskEvent(player, quest, index)));
        TenetEvents.REWARD_CLAIMED.register(
                (player, quest, reward) -> REWARD_CLAIMED.post(new RewardEvent(player, quest, reward)));
        TenetEvents.STAGE_ADDED.register(
                (player, stage) -> STAGE_ADDED.post(new StageEvent(player, stage.toString())));
        TenetEvents.STAGE_REMOVED.register(
                (player, stage) -> STAGE_REMOVED.post(new StageEvent(player, stage.toString())));
        TenetEvents.CLICK_EVENT.register(
                (player, id, chapter, element) -> CLICK_EVENT.post(new ClickEvent(player, id, chapter,
                        element)));
    }

    @Override
    public void registerEvents(EventGroupRegistry registry) {
        registry.register(EVENTS);
    }

    /**
     * Forgets the handlers the previous <b>server</b> scripts registered.
     *
     * <p>Called before every (re)load, including the first, and for whichever manager is reloading --
     * which is why the type is checked rather than assumed. KubeJS reloads the client manager on F3+T
     * and on `/kubejs reload client-scripts`, and clearing the registries then would zero every custom
     * task and reward a server script registered, in a single-player session, until a server-script
     * reload or a restart. Only a server reload is a statement about the handlers those scripts left.
     *
     * <p>Registration replaces by id, so without this a handler whose script was deleted or renamed
     * would go on measuring tasks for the rest of the session -- and the failure it produces is a
     * custom task that keeps working after the file that defines it is gone, which reads as the reload
     * not having happened. A startup reload no longer clears them either: the alternative, clearing on
     * everything but the client, leaves the same wipe reachable through `/kubejs reload
     * startup-scripts`.
     */
    @Override
    public void beforeScriptsLoaded(ScriptManager manager) {
        if (manager.scriptType != ScriptType.SERVER) {
            return;
        }
        CustomTask.CustomTasks.clear();
        CustomReward.CustomRewards.clear();
    }

    @Override
    public void registerBindings(BindingRegistry event) {
        // An instance rather than the class: what KubeJS exposes to a script is an object's methods, and
        // handing it a class would depend on its static-binding rule instead of on this line.
        event.add("Tenet", new Bindings());
    }

    /**
     * The object scripts see as {@code Tenet}.
     *
     * <p>Every method is one line over {@link TenetScripts}, which is where the operations and their
     * reasoning live. This class exists only so that a script's call has something to land on — and so that
     * the KubeJS-shaped part of the integration is small enough to check by eye.
     */
    public static final class Bindings {

        public boolean hasStage(ServerPlayer player, String stage) {
            return TenetScripts.hasStage(player, stage);
        }

        public boolean addStage(ServerPlayer player, String stage) {
            return TenetScripts.addStage(player, stage);
        }

        public boolean removeStage(ServerPlayer player, String stage) {
            return TenetScripts.removeStage(player, stage);
        }

        public List<String> stages(ServerPlayer player) {
            return TenetScripts.stages(player);
        }

        public boolean hasTeamStage(ServerPlayer player, String stage) {
            return TenetScripts.hasTeamStage(player, stage);
        }

        public boolean addTeamStage(ServerPlayer player, String stage) {
            return TenetScripts.addTeamStage(player, stage);
        }

        public boolean removeTeamStage(ServerPlayer player, String stage) {
            return TenetScripts.removeTeamStage(player, stage);
        }

        public List<String> teamStages(ServerPlayer player) {
            return TenetScripts.teamStages(player);
        }

        /** {@code LOCKED}, {@code UNLOCKED}, {@code STARTED}, {@code COMPLETED} or {@code UNKNOWN}. */
        public String state(ServerPlayer player, String questId) {
            return TenetScripts.state(player, questId);
        }

        /** Finishes a quest for this player, as {@code /tenet complete} does. */
        public boolean complete(ServerPlayer player, String questId) {
            return TenetScripts.complete(player, questId);
        }

        /** The ids a {@code tenet:custom} task can name, so a script can see what is registered. */
        public List<String> customTaskIds() {
            return TenetScripts.customTaskIds();
        }

        public List<String> customRewardIds() {
            return TenetScripts.customRewardIds();
        }

        /**
         * Registers what a {@code tenet:custom} task measures.
         *
         * <p>The handler is a function {@code (task, context) => number}: how far along the player is,
         * counted the way the task's own {@code value} counts. Registered before the scripts that use
         * it, so a quest naming this id finds it the moment the tree loads.
         */
        public void registerTask(String id, CustomTask.CustomTasks.Handler handler) {
            TenetScripts.registerTask(id, handler);
        }

        /** Registers what a {@code tenet:custom} reward does: a function {@code (player, context) => ...}. */
        public void registerReward(String id, CustomReward.CustomRewards.Handler handler) {
            TenetScripts.registerReward(id, handler);
        }
    }

    /**
     * A quest event, as a script reads it.
     *
     * <p>Public final fields rather than getters, which is the shape KubeJS reads: the event object is a
     * description of what happened, not behaviour, so there is nothing for a method to add.
     */
    public static final class QuestEvent implements KubeEvent {

        public final ServerPlayer player;
        public final String quest;
        public final String title;

        QuestEvent(ServerPlayer player, Quest quest) {
            this.player = player;
            this.quest = quest.id();
            this.title = quest.title().value();
        }
    }

    /** One task of a quest was satisfied. */
    public static final class TaskEvent implements KubeEvent {

        public final ServerPlayer player;
        public final String quest;
        /** The task's position in the quest's own list, which is what the engine keys progress by. */
        public final int taskIndex;

        TaskEvent(ServerPlayer player, Quest quest, int taskIndex) {
            this.player = player;
            this.quest = quest.id();
            this.taskIndex = taskIndex;
        }
    }

    /** A reward was handed over. */
    public static final class RewardEvent implements KubeEvent {

        public final ServerPlayer player;
        public final String quest;
        /** The reward's type id — {@code tenet:item}, or whatever a type registered — not its value. */
        public final String type;

        RewardEvent(ServerPlayer player, Quest quest, QuestReward reward) {
            this.player = player;
            this.quest = quest.id();
            this.type = reward.type().toString();
        }
    }

    /** A stage was granted or taken away. */
    public static final class StageEvent implements KubeEvent {

        public final ServerPlayer player;
        public final String stage;

        StageEvent(ServerPlayer player, String stage) {
            this.player = player;
            this.stage = stage;
        }
    }

    /** A canvas press whose click is a script event. */
    public static final class ClickEvent implements KubeEvent {

        public final ServerPlayer player;
        /** The {@code namespace:path} id the file named. */
        public final String id;
        /** The chapter holding the pressed element. */
        public final String chapter;
        /** The pressed element's id. */
        public final String element;

        ClickEvent(ServerPlayer player, Identifier id, String chapter, String element) {
            this.player = player;
            this.id = id.toString();
            this.chapter = chapter;
            this.element = element;
        }
    }
}
