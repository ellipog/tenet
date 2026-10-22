package dev.ellipog.tenet.client;

import dev.ellipog.tenet.client.dev.QuestWalks;
import dev.ellipog.tenet.client.hud.HudElement;
import dev.ellipog.tenet.client.hud.HudOverlay;
import dev.ellipog.tenet.client.hud.HudSettings;
import dev.ellipog.tenet.quest.reward.RewardAutoClaim;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The one detector of completions, tasks, claims and chapters, and the client half that says so.
 *
 * <h2>Why there is exactly one</h2>
 *
 * <p>The book used to diff the cache itself, in its own {@code tick} — so a completion while the
 * book was closed was never noticed, and a notifier added beside it would have announced the same
 * completion twice whenever it was open. This class owns the diff ({@link QuestNotifications}) and
 * the book is a <b>sink</b>: it is asked to show the sentence, it does not decide that there is one.
 *
 * <h2>The three sinks, and why the routing is here</h2>
 *
 * <p>The book's own stack is the right notice while the book is on screen — chat is unreadable
 * behind a screen, and the stack is themed and tested. A player who is not looking at the book is
 * told the same sentence one of two ways: as a row on the HUD's own notice element, where they put
 * it, or — if they have switched that element off — as a real toast ({@link QuestToast}), which the
 * game draws over whatever they are doing. All three carry the same sentence, so they can never
 * disagree about what happened, and this method is the only place the three are chosen between.
 *
 * <h2>One ordered list, biggest news first</h2>
 *
 * <p>A claim-all, a party's shared progress or a counter finishing several tasks can move a dozen
 * things in one sample, and the cap means only the first few are told. So the notices are <b>ordered by
 * significance before the cap is applied</b>: a chapter finishing, then a quest finishing, then the tasks
 * that arrived. Ordered by id or by the cache's own walk, the one notice that matters most would be the one
 * a burst dropped. The cap itself is the book's stack's own number, so the two sinks bound a burst
 * identically.
 *
 * <h2>Lifecycle, which is where the interesting bugs are</h2>
 *
 * <p>The diff is reset on a disconnect (both loaders call {@link #reset()}) and whenever the team
 * changes or the cache empties — because a static notifier outlives a screen, and one that kept its
 * map across a world change would read the next server's tree against the previous server's states
 * and announce a completion that never happened. See {@link QuestNotifications#sample}.
 */
public final class QuestNotifier {

    /** The diff, one per client. */
    private static final QuestNotifications DIFF = new QuestNotifications();

    /**
     * How many notices one sample may announce.
     *
     * <p>Capped because a claim-all or a party's shared progress can move a dozen quests at once,
     * and the toast queue is not a place to find out: vanilla holds five slots and queues the rest,
     * so an uncapped batch is a parade. The same number the book's own stack keeps, so the two
     * sinks bound a burst identically.
     */
    private static final int MAX_PER_SAMPLE = ToastStack.MAX;

    private static long lastRevision = -1L;
    private static UUID lastTeam;

    private QuestNotifier() {
    }

    /**
     * Called once per client tick, from each loader's client entry point.
     *
     * <p>Cheap when nothing moved: a revision comparison, which is what the book used to do while it
     * was open and what this now does whether or not it is.
     */
    public static void tick() {
        long revision = ClientQuestCache.progressRevision();
        UUID team = ClientQuestCache.teamId().orElse(null);
        if (revision == lastRevision && Objects.equals(team, lastTeam)) {
            return;
        }
        lastRevision = revision;
        if (!Objects.equals(team, lastTeam)) {
            // A different team is different progress: the previous map says nothing about this one.
            lastTeam = team;
            DIFF.reset();
        }

        // What the message named decides how much of the cache this sample has to look at, and the two
        // paths differ in more than size: a delta names the quests that moved, so only those need a
        // picture taken, while a full sync is the whole of the server's answer and says nothing about
        // what moved. See ProgressTouch for why the flag travels with the ids, and the diff's
        // sampleSome for why a partial sample must not be pruned like a whole one.
        ClientQuestCache.ProgressTouch touch = ClientQuestCache.lastProgressTouch();
        // The chapters are asked on both paths, and that is not a shortcut: the server sends the chapter map
        // whole in a delta as well as a full sync, so this is the one part of the message that is never
        // partial. See QuestSync's own note where it writes the map.
        List<QuestNotifications.Notice> chapters = DIFF.chapters(chapterSnapshots());
        List<QuestNotifications.Notice> quests = touch.full()
                ? DIFF.sample(snapshots())
                : DIFF.sampleSome(touch.ids(), snapshotsOf(touch.ids()));
        if (chapters.isEmpty() && quests.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        for (QuestNotifications.Notice notice : bySignificance(chapters, quests)) {
            announce(minecraft, notice);
        }
    }

    /**
     * Every notice, ordered so that the cap spends itself on the biggest news.
     *
     * <p>Chapters first, then the quests, then the tasks — and the two passes over the quest list are what
     * makes that one order rather than three lists to keep in step. The cap is applied by the caller, on this
     * list, which is the whole point of building it.
     */
    private static List<QuestNotifications.Notice> bySignificance(
            List<QuestNotifications.Notice> chapters, List<QuestNotifications.Notice> quests) {
        List<QuestNotifications.Notice> ordered = new ArrayList<>(
                Math.min(chapters.size() + quests.size(), MAX_PER_SAMPLE));
        for (QuestNotifications.Notice notice : chapters) {
            ordered.add(notice);
        }
        for (QuestNotifications.Notice notice : quests) {
            if (!notice.namesTask()) {
                ordered.add(notice);
            }
        }
        for (QuestNotifications.Notice notice : quests) {
            if (notice.namesTask()) {
                ordered.add(notice);
            }
        }
        return ordered.size() <= MAX_PER_SAMPLE ? ordered : ordered.subList(0, MAX_PER_SAMPLE);
    }

    /** The cache, as the diff reads it: identity, state, what is waiting, the author's silence, the tasks. */
    private static List<QuestNotifications.Snapshot> snapshots() {
        UUID self = selfId();
        List<QuestNotifications.Snapshot> snapshots = new ArrayList<>();
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            snapshots.add(snapshotOf(entry, self));
        }
        // Counted with the observation tick's own walk, because both are the same defect measured on
        // two paths: one sample per progress change is fine, and one whole-cache sample per change is a
        // number that should fall to the handful of quests the message actually named.
        QuestWalks.walked("notifier", snapshots.size());
        return snapshots;
    }

    /**
     * The same picture, for the ids one message named — which is the whole point of
     * {@link ClientQuestCache.ProgressTouch}: a delta about one quest's counter used to take a picture
     * of every quest in the pack, calling {@code canClaimFor} once per quest to do it.
     *
     * <p>An id the tree does not hold is skipped rather than pictured as unknown. That is the same
     * answer the whole-cache sample gives it — an entry is what the loop above walks — and the
     * difference matters for a removal: a quest the server has just removed is one the diff should
     * forget, which it does by not being named in the next sample rather than by being named with a
     * state it does not have.
     */
    private static List<QuestNotifications.Snapshot> snapshotsOf(Set<String> ids) {
        UUID self = selfId();
        List<QuestNotifications.Snapshot> snapshots = new ArrayList<>(ids.size());
        for (String id : ids) {
            ClientQuestCache.Entry entry = ClientQuestCache.entry(id);
            if (entry != null) {
                snapshots.add(snapshotOf(entry, self));
            }
        }
        QuestWalks.walked("notifier", snapshots.size());
        return snapshots;
    }

    /** One entry as the diff needs it — the one description of what a snapshot is. */
    private static QuestNotifications.Snapshot snapshotOf(ClientQuestCache.Entry entry, UUID self) {
        RewardAutoClaim mode = entry.effectiveAutoClaim();
        List<Boolean> tasks = new ArrayList<>(entry.tasks().size());
        List<Boolean> muted = new ArrayList<>(entry.tasks().size());
        for (int i = 0; i < entry.tasks().size(); i++) {
            // The public predicate rather than the entry-taking overload, which is the cache's own: the rule
            // for "is this task finished" must have one reader, and that reader is the cache.
            tasks.add(ClientQuestCache.taskDone(entry.id(), i));
            muted.add(entry.tasks().get(i).disableToast());
        }
        return new QuestNotifications.Snapshot(
                entry.id(),
                ClientQuestCache.stateOf(entry.id()),
                self != null && ClientQuestCache.canClaimFor(self, entry.id()),
                // The quest's own quiet flag ORs with the silent auto-claim modes: either silence wins,
                // and it quiets the task notices of that quest too, because the two are one author's
                // answer to "do not tell me about this quest".
                (mode.automatic() && !mode.notifies()) || entry.disableToast(),
                tasks,
                muted);
    }

    /**
     * Every chapter, as the diff reads it.
     *
     * <p>Walked from {@code chapters()} rather than counted from the quests the sample happens to hold: a
     * chapter whose last quest was removed from the tree is still a chapter, and a chapter state the server
     * computed is what the notice is about — see {@code ClientQuestCache.chapterStateOf} for why deriving one
     * here would disagree with the server for the person most likely to notice.
     */
    private static List<QuestNotifications.ChapterSnapshot> chapterSnapshots() {
        List<QuestNotifications.ChapterSnapshot> snapshots = new ArrayList<>();
        for (ClientQuestCache.ChapterEntry chapter : ClientQuestCache.chapters()) {
            snapshots.add(new QuestNotifications.ChapterSnapshot(chapter.id(),
                    ClientQuestCache.chapterStateOf(chapter.id())));
        }
        QuestWalks.walked("notifier.chapters", snapshots.size());
        return snapshots;
    }

    /** The local player's id, or null where there is none to be had. */
    private static UUID selfId() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == null ? null : minecraft.player.getUUID();
    }

    /**
     * One notice, as the three sinks need it.
     *
     * <p>The facts travel together because they are one thing said three ways: the sentence the book's
     * stack and the HUD's draw, and the label, title, icon and token a {@code QuestToast} needs. Resolved
     * once, so a notice that reached the HUD and one that reached the toast cannot say different words.
     */
    private record Said(Component message, String token, ItemStack icon, String texture, String sprite,
                         Component label, Component title) {
    }

    /** Says one notice the way its moment asks for. */
    private static void announce(Minecraft minecraft, QuestNotifications.Notice notice) {
        if (notice.kind() == QuestNotifications.Kind.CLAIMED) {
            // The server confirmed it: what was owed is not any more. Played here rather than on the
            // press, because a refusal is not a claim and a press that is refused must stay silent.
            minecraft.getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.ITEM_PICKUP, 1.0F, 1.0F));
            return;
        }

        Said said = switch (notice.kind()) {
            case COMPLETED -> questSaid(notice.subjectId());
            case TASK_COMPLETED -> taskSaid(notice);
            case CHAPTER_COMPLETED -> chapterSaid(notice.subjectId());
            // Answered above, by the sound and the early return: a claim says nothing.
            case CLAIMED -> null;
        };
        if (said == null) {
            return;   // the tree moved between the sample and here; nothing to name
        }

        if (minecraft.gui.screen() instanceof QuestBookScreen book) {
            book.notifyNotice(said.message());
        }
        else if (HudSettings.on(HudElement.NOTIFICATIONS)) {
            // The HUD's own stack, where the player put it. Not a toast as well: the element being on is
            // the player saying that is where they want to read it, and two sinks for one event is the
            // duplicate this class exists to prevent, one screen over.
            HudOverlay.notice(said.message().getString(), false, Util.getMillis());
        }
        else if (minecraft.gui.toastManager().getToast(QuestToast.class, said.token()) == null) {
            // Not already being announced: a repeatable quest finished twice in quick succession is
            // told once, and the token is what makes the two notices the same notice. One token per kind,
            // so a quest's completion and a task of it are not confused for each other.
            minecraft.gui.toastManager().addToast(
                    new QuestToast(said.token(), said.icon(), said.texture(), said.sprite(), said.label(),
                            said.title()));
        }
        // A soft chime rather than the advancement fanfare this first shipped with: a pack with a
        // hundred quests plays this a hundred times, and the challenge sting is a celebration-sized
        // sound for a fifty-times-a-session event. The orb pickup at reduced volume reads as "noted",
        // which is what a completion notice is.
        minecraft.getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 0.6F));
    }

    /** A quest's completion, or null for one the tree no longer holds. */
    private static Said questSaid(String questId) {
        ClientQuestCache.Entry entry = ClientQuestCache.entry(questId);
        if (entry == null) {
            return null;
        }
        return new Said(
                Component.translatable("tenet.quest.completed", entry.titleText()),
                QuestToast.tokenFor(questId),
                entry.icon(),
                entry.textureIcon(),
                entry.spriteIcon(),
                Component.translatable("tenet.toast.completed"),
                Component.literal(entry.titleText()));
    }

    /**
     * A task's completion, or null for one the tree no longer holds.
     *
     * <p>The sentence names the task rather than the quest, because "a quest moved" is not news to somebody
     * who is collecting ten logs -- and {@code TaskEntry.text} is the one derivation of that sentence, so the
     * row the HUD draws and the notice here cannot word it two ways.
     */
    private static Said taskSaid(QuestNotifications.Notice notice) {
        ClientQuestCache.Entry entry = ClientQuestCache.entry(notice.subjectId());
        if (entry == null || notice.index() < 0 || notice.index() >= entry.tasks().size()) {
            return null;
        }
        ClientQuestCache.TaskEntry task = entry.tasks().get(notice.index());
        String text = task.text().getString();
        // A bare checkmark's notice wears its state box. An author picture wins when present,
        // like on every other surface.
        String texture = dev.ellipog.tenet.client.CheckmarkArt.wearsBox(task)
                ? dev.ellipog.tenet.client.CheckmarkArt.forState(
                        ClientQuestCache.taskDone(notice.subjectId(), notice.index())).toString()
                : "";
        return new Said(
                Component.translatable("tenet.notice.task_completed", text),
                QuestToast.tokenForTask(notice.subjectId(), notice.index()),
                task.icon(),
                texture,
                "",
                Component.translatable("tenet.toast.task_completed"),
                Component.literal(text));
    }

    /** A chapter's completion, or null for one the tree no longer lists. */
    private static Said chapterSaid(String chapterId) {
        for (ClientQuestCache.ChapterEntry chapter : ClientQuestCache.chapters()) {
            if (chapter.id().equals(chapterId)) {
                return new Said(
                        Component.translatable("tenet.notice.chapter_completed", chapter.titleText()),
                        QuestToast.tokenForChapter(chapterId),
                        chapter.icon(),
                        chapter.textureIcon(),
                        chapter.spriteIcon(),
                        Component.translatable("tenet.toast.chapter_completed"),
                        Component.literal(chapter.titleText()));
            }
        }
        return null;
    }

    /**
     * A grant overflowed: items are on the floor.
     *
     * <p>The server plays the click and shows the action-bar line, and that is where a player outside
     * the book reads it — so this does nothing there rather than adding a third copy. Inside the book
     * the HUD is not drawn at all, which is the one case this exists for: the sentence goes to the
     * book's own stack, the same routing, for the same reason, as a completion notice.
     *
     * <p>Called from the payload handler, which both loaders run on the client thread — the thread the
     * screen is read and written on; see {@code FabricClientNetworking} for where that was established.
     */
    public static void rewardOverflow(int stacks) {
        if (stacks <= 0) {
            return;
        }
        if (Minecraft.getInstance().gui.screen() instanceof QuestBookScreen book) {
            book.notifyRewardOverflow(stacks);
        }
    }

    /**
     * How a sweep ended: claimed N of M, and whether it stopped for want of room.
     *
     * <p>Routed like the overflow notice, and for the same reason — the action bar the server also
     * sends is part of the HUD, which is not drawn behind the book. A sweep that stopped and one that
     * finished both make rows disappear, so the sentence is what tells them apart.
     */
    public static void claimSummary(int claimed, int total, boolean halted) {
        if (Minecraft.getInstance().gui.screen() instanceof QuestBookScreen book) {
            book.notifyClaimSummary(claimed, total, halted);
        }
    }

    /**
     * A reward-level message: a toast reward's description, or a command reward's feedback line.
     *
     * <p>Routed like the overflow notice, and for the same reason — the chat the server also sends is
     * unreadable behind the book, so the sentence goes to the book's own stack while it is open and
     * nowhere else. Outside the book the chat is where that sentence belongs.
     */
    public static void rewardToast(Component message) {
        if (Minecraft.getInstance().gui.screen() instanceof QuestBookScreen book) {
            book.notifyNotice(message);
        }
    }

    /**
     * The server's verdict on a choice pick, routed to the card that asked.
     *
     * <p>Handed to the screen rather than acted on here: only the screen knows whether the offer the
     * verdict names is still the one on display. Escape during the round trip is a legitimate way out,
     * and a verdict for an offer nobody is showing must be dropped rather than matched to whatever
     * slid into its place.
     */
    public static void choiceResult(String questId, int rewardIndex,
                                    dev.ellipog.tenet.net.ClaimChoiceResultPayload.Result result) {
        if (Minecraft.getInstance().gui.screen() instanceof QuestBookScreen book) {
            book.bookOverlay.notifyChoiceResult(questId, rewardIndex, result);
        }
    }

    /**
     * Forgets everything.
     *
     * <p>Called from each loader's disconnect hook, beside {@code ClientQuestCache.clear()}: the
     * cache empties itself, and this must not outlive it. The HUD's own notice stack is cleared beside it,
     * by the same hook, for the same reason -- a sentence about the world just left must not be the first
     * thing read in the next one.
     */
    public static void reset() {
        DIFF.reset();
        lastRevision = -1L;
        lastTeam = null;
        // Offers describe a connection's questions, so they go with it: a queue kept across a
        // disconnect would greet the next server with a picker for a quest it has never heard of.
        ClientChoiceOffers.clear();
    }
}
