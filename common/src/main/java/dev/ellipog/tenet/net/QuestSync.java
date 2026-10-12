package dev.ellipog.tenet.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.editor.EditPhases;
import dev.ellipog.tenet.progress.ProgressService;
import dev.ellipog.tenet.progress.ProgressionEngine;
import dev.ellipog.tenet.progress.QuestClaims;
import dev.ellipog.tenet.progress.QuestProgress;
import dev.ellipog.tenet.progress.QuestState;
import dev.ellipog.tenet.progress.TeamProgress;
import dev.ellipog.tenet.quest.CanvasElement;
import dev.ellipog.tenet.quest.Chapter;
import dev.ellipog.tenet.quest.ChapterGroup;
import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.Quest;
import dev.ellipog.tenet.quest.QuestIndex;
import dev.ellipog.tenet.quest.QuestLanguages;
import dev.ellipog.tenet.quest.QuestLink;
import dev.ellipog.tenet.quest.QuestRef;
import dev.ellipog.tenet.quest.QuestReward;
import dev.ellipog.tenet.quest.QuestSettings;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.QuestText;
import dev.ellipog.tenet.quest.TenetQuests;
import dev.ellipog.tenet.quest.condition.ConditionDisplay;
import dev.ellipog.tenet.quest.condition.ConditionTypes;
import dev.ellipog.tenet.quest.condition.QuestCondition;
import dev.ellipog.tenet.quest.reward.RewardDisplay;
import dev.ellipog.tenet.quest.reward.RewardTypes;
import dev.ellipog.tenet.quest.task.TaskDisplay;
import dev.ellipog.tenet.quest.task.TaskTypes;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns the server's questline into the flat shape the client needs.
 *
 * <h2>Why a separate shape rather than sending the records</h2>
 *
 * <p>A client needs a title, an icon, a position, and what the tasks ask for. It does not need
 * dependencies as objects, codecs, or reward behaviours, and it cannot act on any of them. Sending
 * the records would put the addon API on both sides of the wire and mean a new task type broke the
 * client.
 *
 * <p>So this is a projection, written by hand, and the hand-writing is the point: it is the list of
 * what the client is allowed to know. Adding a field here is a decision about what crosses the wire,
 * which should not happen by accident because a record gained a component.
 *
 * <h2>The apostrophe problem, and why JSON is the right call for now</h2>
 *
 * <p>Text goes over as UTF-8 JSON. A hand-written binary encoding would be smaller — the plan says so
 * — but it would also be a second serialiser to keep in step, and a format nobody can read in a packet
 * dump while the shape is still moving. Binary is for a later stage, and the plan lists it there.
 */
public final class QuestSync {

    /**
     * What each player was last sent, so the next message can be a delta.
     *
     * <p>Keyed by <b>player</b> rather than by team, because it describes one connection's knowledge:
     * two members of one party are sent the same progress and each has their own history of it. A
     * team-keyed snapshot would be wrong for the second player to join, who has been sent nothing.
     *
     * <p>On the server thread only — every writer is a command handler, a tick callback or a payload
     * handler, all of which the server runs on the server thread — so a plain {@code HashMap} is
     * correct and a concurrent one would be a claim about contention that does not exist.
     */
    private static final Map<UUID, Sent> SENT = new HashMap<>();

    /**
     * Each locale's message, packed and chunked once, keyed by the locale actually served.
     *
     * <h2>Why this is cached when nothing else about the broadcast is</h2>
     *
     * <p>Because the tree is the same bytes for everybody and the locale is not: it depends on one
     * thing, the language the player reads, and a server with twenty players on three languages would
     * otherwise pay twenty encodes and twenty deflates to produce three messages. The tree already pays
     * one encode per recipient and is measured for it — see {@link WireTiming} — so adding a second
     * per-recipient encode would be the wrong direction twice over.
     *
     * <p>Bounded by the pack's own files rather than by anything a client can ask for: the key is the
     * value {@code QuestLanguages.servedLocale} returned, which is either empty or one of the locales
     * the pack actually ships. A player asking for a language the pack has never heard of shares the
     * one empty entry, so there is no request a client can make that grows this map.
     */
    private static final Map<String, List<byte[]>> LOCALE_CHUNKS = new HashMap<>();

    /** Which set of translations the cache above was built from, so a reload cannot serve a stale one. */
    private static volatile QuestLanguages cachedLanguages;

    /**
     * Nobody is contributing to anything: the answer for a caller with no team behind it.
     *
     * <p>The full-progress form and the tests both use it, and it exists rather than a null so that
     * {@code oneQuestAsJson} has one thing to call.
     */
    private static final ProgressService.Contributors NOBODY = (questId, taskIndex) -> Map.of();

    /**
     * No live counts: the answer for a caller that only has stored progress to send.
     *
     * <p>{@link ProgressService.Live} answers zero for every task that is not waiting for a press, so
     * a caller with no team behind it and a caller with a team agree on everything but the waiting
     * tasks — which is exactly the difference this exists to name.
     */
    private static final ProgressService.Live NO_LIVE = (questId, taskIndex) -> 0;

    /** One player's last sent state: the team, the exact JSON sent per quest, and the locks it carried. */
    private record Sent(UUID teamId, Map<String, String> quests, String locks) {
    }

    /**
     * One message that was produced: why, and to whom.
     *
     * <p>Both halves are needed and neither implies the other. The reason is what a client branches
     * on — it is what decides a full sync over a delta — so counting without it would pass while every
     * message said the wrong thing. The recipient is what makes "who was told" assertable, and a total
     * cannot answer that: two messages that both reached the <i>same</i> player look identical to two
     * that reached one player each, and the second is the entire point of the team-change push.
     */
    private record Told(int reason, UUID player) {
    }

    /**
     * How many progress messages have been produced, per reason and recipient.
     *
     * <h2>Why this exists, rather than a test reading the wire</h2>
     *
     * <p>Because the half of the sync that is easy to get wrong has no other observable. A message
     * goes out, a booted server writes nothing to the log, and the payload is handed to a loader that
     * a test JVM does not have — so "was the client told" is not a question a test can ask of the
     * transport. Counting messages answers it without inventing a second code path: the count is
     * incremented at the one place a message is produced, so a caller that reaches it by a route
     * nobody thought of is counted too. A test that asserted on a route instead would pass while the
     * route it did not know about stayed broken — which is precisely the shape of the bug this
     * counter was added to close, where the only thing pushing progress was the Submit handler.
     *
     * <p>Static and never reset, like the snapshot map above it, so a test asserts a <i>delta</i>
     * rather than an absolute value. Two test classes sharing one JVM cannot then disagree about a
     * number that belongs to neither of them.
     *
     * <p>Deliberately not a count of <i>packets</i>: one logical message is several packets once it is
     * chunked, and "how many times did the server have something to say" is the question. Counting
     * chunks would make a large pack look like a chatty one.
     */
    private static final Map<Told, Integer> MESSAGES = new HashMap<>();

    private QuestSync() {
    }

    /**
     * The version of the tree, as it goes over the wire.
     *
     * <h2>What 2 added, and the rule that keeps bumping it safe</h2>
     *
     * <p>Version 2 added {@code groups[]} at the root and {@code chapterGroupId} to every quest. Both
     * are <b>additions</b>: no field moved, no field was renamed, and every field a version-1 reader
     * asked for is still there meaning the same thing. That is the whole compatibility strategy, and it
     * is worth stating plainly because it is a constraint rather than a description — every future
     * change to {@link #treeAsJson} has to be of the additive kind, or be a deliberate break by a
     * reader that checks this number.
     *
     * <p>Version 3 added {@code chapters[]} at the root, for the same additive reason as the version
     * before it: it is the list of chapters themselves, which the per-quest fields could not carry for
     * a chapter that holds no quests yet. A version-2 reader ignores the array entirely and goes on
     * deriving its rows from the quests, which is what it already did.
     *
     * <p>Version 6 added the reward base mechanics to every reward — {@code auto}, {@code team} and
     * {@code excludeFromClaimAll}, resolved against the tree's settings — for the rewards panel. The
     * same additive kind: a version-5 reader draws its reward rows from the fields it knows and never
     * asks for the three.
     *
     * <p>Version 7 added the observation fields to a task that has them — {@code observeType},
     * {@code observeTarget}, {@code observeTicks} — which is what lets the client do the watching. A
     * version-6 reader ignores them and draws an observation task as a label, which is the honest
     * fallback: it cannot count what it cannot recognise.
     *
     * <p>Version 8 added a task's or a reward's {@code conditions} — the display sentence of each gate
     * it carries — and nothing else. Additive again: a version-7 reader draws the row without the
     * explanation, which is the honest fallback. Which rows are <i>locked</i> for a player does not
     * travel here at all; that is per-player state and lives on the progress channel, where every
     * per-player fact belongs.
     *
     * <p>Version 11 added the reward tables — {@code rewardTables} and {@code refusedTables} — which the
     * editor's browser lists and a reward's table badge draws. A version-10 reader ignores both. The
     * client pins this one by name, which is why it is here.
     *
     * <p>Version 12 added each chapter's own gate to {@code chapters[]} — {@code dependsOn},
     * {@code prerequisiteMode}, {@code minRequired} and {@code hideUntilDependenciesComplete} — plus the
     * chapter states on the progress channel, which is the other half of the same feature: the tree says
     * what a chapter waits for, and the progress message says how far it has got. Additive in the same
     * way as every bump before it: a version-11 reader reads the four fields it knows about a chapter and
     * draws every chapter exactly as it does today, which is the right fallback for a server that has
     * learned about chapter gates and a client that has not.
     *
     * <p>Version 13 added the <b>English half of every translatable text</b> — {@code titleFallback},
     * {@code subtitleFallback}, {@code chapterTitleFallback} and {@code descriptionFallbacks} — so a
     * client can tell a translation key from a literal and show the author's own words when nothing has
     * translated the key. A version-12 reader ignores them and draws what the field it does read holds,
     * which for a translatable text is the key: the same wrong string it drew before this version
     * existed, and the reason this bump is a fix rather than only a feature. The locale <i>overlay</i>
     * is deliberately not here at all — it travels on its own channel, to one player, because the tree
     * is broadcast and fifteen locales are not fifteen copies of the same questline. See
     * {@code LocaleSyncPayload}.
     *
     * <p>Version 14 added each chapter's canvas <b>{@code elements}</b> — the pictures, labels, lines and
     * boxes it draws behind its quests — to {@code chapters[]}, and only for a chapter that has any. The
     * same additive kind as every bump before it: a version-13 reader ignores the array and draws a
     * chapter with no decoration, which is a plainer picture rather than a wrong one. What is
     * deliberately <b>not</b> here is anything about an element's <i>state</i>, and the reason is the
     * whole design of the feature rather than a saving: an element holds no progress and gates nothing, so
     * there is no per-player fact about one to send and nothing for the progress channel to carry.
     *
     * <p>Version 15 added each chapter's <b>{@code links}</b> — the markers pointing at other quests —
     * beside the elements, and only for a chapter that has any. The same additive kind: a version-14
     * reader ignores the array and draws a chapter with no markers, which misses a shortcut rather
     * than a quest. What is deliberately <b>not</b> here, for the same reason as the elements, is a
     * link's <i>state</i>: a link mirrors its target quest, whose state already travels on the
     * progress channel, so sending it again would be a second answer that can disagree with the
     * first.
     *
     * <p>Version 16 added each quest's <b>{@code aliases}</b> — the former ids a renamed quest still
     * answers to — and only for a quest that has any. The same additive kind: a version-15 reader
     * ignores the array and resolves by id alone, which misses a pre-rename spelling rather than a
     * quest. What the client needs them for is the same lookups the server does, so an
     * {@code open_quest} press carrying an alias opens the quest instead of reporting a control
     * that does nothing.
     *
     * <p>Version 17 added the authoring flags — each quest's <b>{@code minWidth}</b>,
     * <b>{@code hideDependentLines}</b> and <b>{@code disableToast}</b>, each task's and each
     * reward's {@code disableToast}, each chapter's <b>{@code autofocus}</b>, and each reward
     * table's <b>{@code useTitle}</b> and <b>{@code hideTooltip}</b>. The same additive kind: a
     * version-16 reader ignores the keys and draws the old panel width, the old edges and the old
     * toasts, which is a plainer picture rather than a wrong one. {@code minWidth} travels
     * already resolved against the chapter's {@code defaultMinWidth}, like the reveal flags, so
     * the client reads one number and never a ladder.
     *
     * <p>Version 18 added each icon's <b>kind</b> — {@code iconKind} beside a quest's, a chapter's
     * (both the chapters[] copy and the per-quest copy), a group's and the book's icon — and each
     * task's and reward's <b>{@code textureIcon}</b>, the author's texture override. Absent kind
     * means the item arm, which is every icon a version-17 tree ever sent: a version-17 reader draws
     * a texture path or an entity id as a missing item, which names the picture it cannot draw rather
     * than drawing nothing. Components travel only on the item arm, because only an item has any.
     * An author item or egg travelled in the display's own item until version 21, which carries it
     * beside the requirement instead — see the version-21 note.
     *
     * <p>Version 19 added each item, item-tag and fluid task's <b>{@code manualOnly}</b> — the legacy
     * {@code task_screen_only} under Tenet's name. Only when true: absence means the tick
     * measures, which is every task a version-18 tree ever sent. A version-18 reader draws the row
     * and withholds the button the way it withholds any press it cannot see coming, while the
     * server still accepts the press — the same direction every other additive key leans.
     *
     * <p>Version 20 added each chapter's <b>{@code chapterSubtitle}</b> beside its
     * {@code chapterTitle} on every quest of it — the one line under the chapter's name, with its
     * English half in {@code chapterSubtitleFallback} since version 13's rule. Only when the chapter
     * names one, like the quest's own {@code subtitle}: absence means no subtitle, which is every
     * chapter a version-19 tree ever sent. A version-19 reader ignores both keys and draws the
     * sidebar row without its second hover line, which is a plainer tooltip rather than a wrong one.
     *
     * <p>Version 21 added each task's and reward's <b>{@code picture}</b> beside its {@code item} —
     * the author's item picture, with {@code pictureComponents} when it carries data. Before this the
     * author's item travelled in the display's own item, so a tag task wearing an oak log opened
     * oak-log recipes instead of the tag, a checkmark wearing a torch gained recipes it never asked
     * for, and an item task wearing a texture lost its recipes entirely. Now {@code item} is always
     * the requirement and {@code picture} is always the picture: a version-20 reader ignores both
     * keys and draws the requirement, which shows what the row needs rather than the picture it
     * cannot draw.
     *
     * <p><b>This list names the versions a reader branches on, not every bump.</b> Nine and ten added
     * nothing a client has to know and left no prose anywhere to reconstruct them from, so a rung for
     * each would be a history this file cannot support. {@link #TREE_VERSION} is the authority; this is
     * the map of the places it matters.
     *
     * <p>The consequence, in the direction that matters most: <b>an old client on a new server still
     * draws today's flat list.</b> It reads the fields it knows and ignores the two it does not, which
     * is what Gson does with a key nobody asks for, so an install that has not been updated keeps
     * working against a server that has.
     *
     * <h2>This is read in exactly one place, and deliberately not as a gate</h2>
     *
     * <p>{@link dev.ellipog.tenet.client.ClientQuestCache} compares it and logs a warning when the
     * server is ahead, and then reads the tree anyway. A reader that <i>refused</i> a version it did
     * not know would break the very case the additive design exists to keep working, so refusing is the
     * one thing this field must never be used for. It is a description of the tree and a diagnostic for
     * the mismatch; the field's presence and absence are what the reader actually branches on.
     *
     * <p>The reader importing this constant from the writer is a considered choice rather than an
     * oversight. The two classes are two halves of one hand-written contract — which is the whole point
     * of {@code QuestSyncTest} — so a second copy of this number in the client is precisely the "second
     * answer that can disagree" this codebase keeps finding. {@code net} and {@code client} already
     * reference each other, so this adds an instance of a coupling that is already there rather than a
     * new kind of one.
     */
    public static final int TREE_VERSION = 21;

    /**
     * The quest tree, as JSON.
     *
     * <h2>Flat at both levels, and for the same reason</h2>
     *
     * <p>Every quest carries its {@code chapterId} and its {@code chapterGroupId} rather than being
     * nested under either, so the client groups however it likes without walking a tree. Nesting would
     * be smaller and would fix the client's layout to the server's, which is backwards — grouping is a
     * display decision.
     *
     * <p>The group <b>headings</b> travel in a flat list of their own at the root, and they have to:
     * a quest can say which group it is in, and cannot say what that group is called or whether its
     * chapters start closed. Those are two facts about one object, so they are two fields — and the
     * alternative, nesting the quests inside their group, would buy a single place for both at the cost
     * of the client having no say in the shape of its own sidebar.
     *
     * <p>{@code invisible} travels as a flag rather than the quest being withheld. Withholding it is
     * tidier right up until a player completes something and the quest should appear, at which point
     * the whole tree has to be re-sent. Sending the flag keeps the tree immutable for as long as the
     * quest files are.
     */
    public static byte[] treeAsJson(QuestIndex index) {
        JsonArray quests = new JsonArray();

        // One entry per quest, in declaration order, taken from the index rather than from a walk over
        // the files. That walk was a fifth copy of the same nested loop, and it recounted each quest's
        // position within its chapter with a local counter -- a second description of the number a
        // LINEAR chapter gates on. The entry carries the position the chapter's own list gives it.
        //
        // The position and the linearity stay per-quest on the wire even though both are properties of
        // the chapter, because the client groups entries by `chapterId` and has no chapter record to
        // hang them on. They are sent rather than worked out on the client because a linear chapter
        // declares no dependencies at all -- the list order *is* the progression -- so from the quests
        // alone the client cannot tell a road from three unrelated nodes. It would draw three
        // unconnected boxes for a chapter that is a sequence, which looks like a missing feature rather
        // than a missing field.
        for (QuestIndex.QuestEntry entry : index.quests()) {
            quests.add(questAsJson(entry.groupId(), entry.chapter(), entry.quest(), entry.orderInChapter()));
        }

        // The headings, in declaration order — which for the folder layout is folder-name order, and is
        // the order the book draws its rows in.
        //
        // Sent even when there are none, so that a version-2 tree is self-describing: a reader, or a
        // packet dump, can see that this server describes groups at all rather than having to guess
        // whether a missing key means "older server" or "no groups".
        //
        // Worth being exact about what that does and does not buy, because the tempting overclaim is
        // that the reader *needs* the distinction. It does not. A client draws the flat chapter list
        // whenever it holds no group headings, and that is the right screen for both an older server
        // and a version-2 server with an empty questline — the two cases want the same drawing, so the
        // client never has to ask which one it is looking at. This is a diagnostic, not a
        // compatibility mechanism, and nothing should be built on it as one.
        JsonArray groups = new JsonArray();
        for (QuestIndex.GroupEntry entry : index.groups()) {
            ChapterGroup group = entry.group();
            JsonObject one = new JsonObject();
            one.addProperty("id", group.id());
            textAsJson(group.title(), "title", one);
            one.addProperty("collapsedByDefault", group.collapsedByDefault());
            // Optional, and sent only when the group declares one: a group with no icon falls back on
            // the client to the first chapter under it, which is a client-side choice rather than a
            // value the file has to spell out. See `QuestBookScreen.buildSidebar()`.
            group.icon().ifPresent(icon -> iconAsJson(icon, one, "icon", "iconComponents", "iconKind"));
            groups.add(one);
        }

        // The chapters, in declaration order, since version 3.
        //
        // This is what makes a chapter real before it holds anything. Until it, a chapter reached the
        // client only as a property of the quests inside it, so a chapter somebody had just created --
        // or one they had emptied -- did not exist in the book at all, and there was no id to select,
        // rename or move. The per-quest `chapterTitle`/`chapterIcon` fields stay exactly as they were,
        // because a client older than this version still reads them and this change has to be additive.
        JsonArray chapters = new JsonArray();
        for (QuestIndex.ChapterEntry entry : index.chapters()) {
            Chapter chapter = entry.chapter();
            JsonObject one = new JsonObject();
            one.addProperty("id", chapter.id());
            // Empty for a chapter the index places at the root, which is the same "no group" sentinel
            // the per-quest field already uses and the sidebar already draws as a root row.
            one.addProperty("groupId", entry.groupId());
            textAsJson(chapter.title(), "title", one);
            chapterIcon(chapter, one, "icon", "iconComponents");

            // The chapter's own gate, since version 12. The *rules* travel, not the state: what a reader
            // needs to explain a shut chapter is what it is waiting for, and how far along that is comes
            // on the progress channel with everything else that moves. Sent resolved rather than
            // optionally, unlike a quest's own mode: nothing sits above a chapter for these to inherit
            // from, so the two are the same value and an absent field would be a second way to say a
            // default.
            if (chapter.rules().waits()) {
                JsonArray dependsOn = new JsonArray();
                for (var dependency : chapter.rules().dependsOn()) {
                    dependsOn.add(dependency.id());
                }
                one.add("dependsOn", dependsOn);
            }
            one.addProperty("prerequisiteMode",
                    chapter.rules().prerequisiteMode().name().toLowerCase(java.util.Locale.ROOT));
            one.addProperty("minRequired", chapter.rules().minRequired());
            // Only when true: absence is the ordinary case, and it means "listed and drawable" -- which is
            // what a reader of a version-11 tree does with a chapter it hears nothing about.
            if (chapter.rules().hideUntilDependenciesComplete()) {
                one.addProperty("hideUntilDependenciesComplete", true);
            }
            // Whether this chapter is withheld from every reader, whatever its gate says. Only
            // when true: absence means shown, which is what every older reader does with a
            // chapter it hears nothing about. Sparse and unversioned, like the quest flags
            // below: an old reader ignores the key and lists the chapter, which is the same
            // additive shape as every bumped field, without moving TREE_VERSION.
            if (chapter.rules().alwaysInvisible()) {
                one.addProperty("alwaysInvisible", true);
            }
            // Whether this chapter's canvas draws without the zoom tiers. Written whenever the
            // file says anything — true *or* false — because absence is not "off" but "defer
            // to the book": a chapter re-enabling the tiers under a tierless book is an
            // explicit false on the wire, and sparse-only-true could not say it. An old
            // reader ignores the key and tiers as before.
            chapter.rules().disableCanvasLod()
                    .ifPresent(lod -> one.addProperty("disableCanvasLod", lod));
            // The quest this chapter centres on when selected, since version 17. Only when set:
            // absence means the old bounding-box centre, which is what a version-16 reader does with
            // a chapter it hears nothing about.
            chapter.rules().autofocus().ifPresent(ref -> one.addProperty("autofocus", ref.id()));
            // `completesWhen` deliberately does not cross. What a reader draws is the chapter's state, and
            // the milestone list is the author's own account of how that state is reached -- the chapter
            // tab reads it from the file replica, which is where every other authoring field comes from.
            //
            // And the canvas's decoration, since version 14 -- pictures, labels, lines and boxes, which is
            // what a chapter draws behind its quests. **Only when the chapter has any**: most chapters have
            // none, and a key per chapter saying "nothing here" would be the largest thing in the tree.
            //
            // What travels is each element's own object, written by its own codec rather than by hand --
            // the one place in this file that does not spell out what it sends. That is deliberate: an
            // element has fifteen fields across four arms, and a hand-written writer here would be a second
            // description of the format that the next field added would silently outgrow. The cost is that
            // a translatable text travels as the object a *file* writes (`{translate, fallback}`) rather
            // than as the `<name>`/`<name>Fallback` pair this file uses for a chapter's own fields. Nothing
            // downstream can tell: the client decodes the element back into the same `QuestText` and
            // resolves it when it draws, which is where every other piece of text is resolved too.
            if (!chapter.elements().isEmpty()) {
                JsonArray elements = new JsonArray();
                for (CanvasElement element : chapter.elements()) {
                    elements.add(CanvasElement.asJson(element));
                }
                one.add("elements", elements);
            }
            // And the chapter's markers, since version 15 — one object per link, written by the link's
            // own codec for the same reason as the elements above. Only when the chapter has any, for
            // the same reason: most chapters have none, and an empty array per chapter would be bytes
            // saying nothing on every tree a pack without links sends.
            if (!chapter.links().isEmpty()) {
                JsonArray links = new JsonArray();
                for (QuestLink link : chapter.links()) {
                    links.add(QuestLink.asJson(link));
                }
                one.add("links", links);
            }
            chapters.add(one);
        }

        JsonObject root = new JsonObject();
        root.addProperty("version", TREE_VERSION);
        // The pack's main theme, absent when it has none. See `packTheme` for why this rides on the
        // tree rather than in a message of its own, and why it is sent both here and in the payload.
        String theme = packTheme(index);
        if (theme != null) {
            root.addProperty("theme", theme);
        }
        // The pack's own name and icon for the book, when its index declares them. Absent keys mean the
        // client's own translatable title and no icon -- the same reading every optional field here
        // gets. The icon is an id the client resolves, so a missing item is drawn as the header's own
        // mark rather than refused on this side.
        QuestSettings settings = TenetQuests.settings();
        if (!settings.bookTitle().isEmpty()) {
            root.addProperty("bookTitle", settings.bookTitle());
        }
        // The book's own icon, when the index declares one. An item id the client resolves, a texture
        // it blits, or an entity it reads as an egg — the kind travels beside the id since version 18,
        // and absence means no icon, which is what every pack that predates the field gets.
        settings.bookIcon().ifPresent(icon -> iconAsJson(icon, root, "bookIcon", "bookIconComponents",
                "bookIconKind"));
        // The file's own answers the client draws with, unversioned and sparse: each key travels
        // only when it differs from the default, so a pack that never heard of these fields sends
        // exactly the bytes it sent before — and a reader that never heard of them draws the
        // defaults, which are the same. No version bump, for the reason the version's own note
        // gives: every change here is of the additive kind. Absence always means the default.
        if (settings.showLockIcons()) {
            root.addProperty("showLockIcons", true);
        }
        if (settings.hideExcludedQuests()) {
            root.addProperty("hideExcludedQuests", true);
        }
        if (settings.pauseGame()) {
            root.addProperty("pauseGame", true);
        }
        if (settings.disableGui()) {
            root.addProperty("disableGui", true);
        }
        if (settings.disableCanvasLod()) {
            root.addProperty("disableCanvasLod", true);
        }
        if (settings.giveBookOnFirstJoin()) {
            root.addProperty("giveBookOnFirstJoin", true);
        }
        if (!settings.lockMessage().isEmpty()) {
            root.addProperty("lockMessage", settings.lockMessage());
        }
        root.add("groups", groups);
        root.add("chapters", chapters);
        root.add("quests", quests);
        // And the reward tables, as summaries: what the editor's browser lists and what a reward's
        // table badge draws. The tables themselves are files an editor asks for when it opens one --
        // a summary is tens of bytes and belongs with the rest of what the book shows, while a table's
        // entries are only wanted by the one panel that is editing it.
        root.add("rewardTables", tableSummaries());
        // And the tables that did NOT load, with the reason each one was refused. A separate list rather
        // than a flag on the summaries above, because the two sets are disjoint by construction: a table
        // that refused has no title, no icon and no entry count to summarise, and putting a half-empty
        // summary in the list every other part of the client reads would make "a table" two shapes.
        root.add("refusedTables", refusedTableSummaries());
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The reward table files that are there and did not load.
     *
     * <h2>Why the reason travels with the id</h2>
     *
     * <p>Because the whole point of listing them is that the author can act: "dice" is not a problem,
     * "dice: unknown field \"tabl\"" is. The sentence is the one the load already reported into the log
     * and into {@code /tenet reload}'s output — read from {@code TenetQuests.refusedTables()}, which
     * takes it from the same {@code Problems} — so the panel, the log and the command all say one thing.
     */
    private static JsonArray refusedTableSummaries() {
        JsonArray refused = new JsonArray();
        for (java.util.Map.Entry<String, String> entry : TenetQuests.refusedTables().entrySet()) {
            JsonObject one = new JsonObject();
            one.addProperty("id", entry.getKey());
            one.addProperty("why", entry.getValue());
            refused.add(one);
        }
        return refused;
    }

    /**
     * The reward tables, as the client's browser needs them.
     *
     * <p>Read from {@code TenetQuests.rewardTables()} rather than passed in, the way the settings are:
     * both are loaded state that a reload replaces, and the tree is written right after a reload, so
     * there is one place that decides what is current.
     *
     * <p>The icon travels as an item id plus its components, exactly as a quest's own icon does, so the
     * client resolves it with the helper it already has and a table with no icon of its own shows the
     * item its first entry grants.
     */
    private static JsonArray tableSummaries() {
        JsonArray tables = new JsonArray();
        for (java.util.Map.Entry<String, dev.ellipog.tenet.quest.loot.RewardTable> entry
                : new java.util.TreeMap<>(TenetQuests.rewardTables()).entrySet()) {
            String id = entry.getKey();
            dev.ellipog.tenet.quest.loot.RewardTable table = entry.getValue();
            JsonObject one = new JsonObject();
            one.addProperty("id", id);
            one.addProperty("title", table.displayTitle(id));
            one.addProperty("entries", table.entryCount());
            dev.ellipog.tenet.quest.ItemRef icon = table.displayIcon();
            one.addProperty("icon", icon.item().toString());
            componentsAsJson(icon, "iconComponents", one);
            // Table presentation, since version 17. Only when true: absence means the old row — the
            // generic roll sentence and the item tooltip — which is what a version-16 reader draws.
            if (table.useTitle()) {
                one.addProperty("useTitle", true);
            }
            if (table.hideTooltip()) {
                one.addProperty("hideTooltip", true);
            }
            tables.add(one);
        }
        return tables;
    }

    /**
     * The pack's own main theme, or null when it declares none.
     *
     * <h2>Why this rides on the tree rather than in a payload of its own</h2>
     *
     * <p>Because it is a property of the questline, and the tree is what arrives when the questline
     * does. A second message would be a second thing to keep in step with the first, and it would need
     * its own answer to "what happens on a server that sends a theme and no tree" — a question with no
     * good answer, which is usually a sign that two things are one thing.
     *
     * <p>It travels in the payload as well as in the JSON, and the duplication is deliberate: the
     * payload's copy is read before the tree is decompressed, so a client can apply the pack's look on
     * the frame the sync arrives rather than a frame later. Nothing here reads the JSON's copy — it is
     * the tree's own record of how it was drawn, which is what a test can assert on where a payload
     * cannot be inspected without a network.
     *
     * <p><b>This returns null today, and that is a deliberate order rather than an unfinished
     * feature.</b> A quest file cannot declare a pack theme yet: no such field exists in the file
     * model, so there is nothing here to read. The field went in first on the wire because a client
     * that <i>reads</i> a field nobody writes is strictly better than a client that has to be changed
     * on the day the writer arrives — the read path is exercised, the payload codec is exercised, and
     * the only edit left when the model gains the field is the one line below. What it must not become
     * is a field that is parsed, validated and printed while nothing reads it, which is the failure
     * this codebase has recorded three times.
     */
    private static String packTheme(QuestIndex index) {
        // When the file model gains a pack-level theme, this is the single line that changes. Left
        // explicit rather than removed so that the two call sites above cannot drift apart.
        return null;
    }

    private static JsonObject questAsJson(String groupId, Chapter chapter, Quest quest, int orderInChapter) {
        JsonObject json = new JsonObject();
        // The group its chapter is in, which is the membership the heading list on its own cannot
        // express: `groups[]` says what the headings are called and nothing about what hangs under them.
        //
        // Sent per quest rather than once per chapter, for the same reason `chapterTheme` is -- the
        // client has no chapter record to hang it on, and a second message saying "chapter c is in group
        // g" would be a second thing to keep in step with the first.
        json.addProperty("chapterGroupId", groupId);
        json.addProperty("chapterId", chapter.id());
        // Both halves, since version 13: see `textAsJson` for why the fallback cannot be folded into
        // the text. The client resolves it against the pack's `chapter.<id>.title` when the chapter
        // wrote a key of its own.
        textAsJson(chapter.title(), "chapterTitle", json);
        // Both halves of the chapter's subtitle, since version 20: see `textAsJson` for why the
        // fallback cannot be folded into the text. Only when the chapter names one, like the quest's
        // own subtitle below -- absence means no subtitle, which is every chapter a version-19 tree
        // ever sent. The client resolves it against the pack's `chapter.<id>.subtitle`.
        chapter.subtitle().ifPresent(subtitle -> textAsJson(subtitle, "chapterSubtitle", json));

        // The chapter's own icon, on every quest of it for the same reason `chapterTheme` is below: the
        // client groups entries by `chapterId` and has no chapter record to hang it on.
        //
        // Sent only when the chapter authored one -- see `chapterIcon` for why a defaulted paper is not
        // sent as though it were a choice.
        chapterIcon(chapter, json, "chapterIcon", "chapterIconComponents");

        // A chapter may ask to be drawn in a theme of its own, and that rides on every quest in it
        // for the same reason `chapterLinear` does: the client groups entries by `chapterId` and has
        // no chapter record to hang it on.
        //
        // Sent only when the chapter names one, so absence means "no opinion" rather than "the
        // default". Those read the same today and diverge the moment a player sets their own theme —
        // and losing to an accidental default is exactly the bug this field must not introduce.
        //
        // Unlike `invisible`, an unrecognised name is not dropped here. The client is the side that
        // knows which themes exist, so it is the side that can say so, and it does — once, naming the
        // chapter. Filtering it out at the server would be silently discarding an author's mistake,
        // which is the failure mode this project keeps finding: a field that reads as supported.
        chapter.theme().ifPresent(name -> json.addProperty("chapterTheme", name));
        // And the chapter's token-level overrides, carried raw: the client is the side that parses and
        // composes them, and the validator has already checked them on the side that can name the line.
        // Absent means the named theme (or the player's own) stands alone.
        chapter.themePatch().ifPresent(patch -> json.add("chapterThemePatch", patch));
        // The chapter's default line style, resolved: the client draws with it directly and has no
        // chapter record to read one from -- the same reason `chapterDefaultPrerequisiteMode` rides here.
        json.add("chapterDependencyStyle", chapter.dependencyStyle().resolved().asJson());
        // And this quest's per-line overrides, sent only when it has any: absence is the common case and
        // it means "every line of mine follows the chapter", which is what a file that never mentions
        // them says.
        if (!quest.dependencyLines().isEmpty()) {
            JsonObject lines = new JsonObject();
            quest.dependencyLines().forEach((dependency, style) -> lines.add(dependency, style.asJson()));
            json.add("dependencyLines", lines);
        }
        json.addProperty("id", quest.id());
        // The quest's former ids, when it has any. Sparse: absent means "no aliases", which is every
        // quest that was never renamed -- and the client needs them for the same lookups the server
        // does, so a press naming an alias opens the quest rather than reporting a broken control.
        if (!quest.aliases().isEmpty()) {
            JsonArray aliases = new JsonArray();
            for (String alias : quest.aliases()) {
                aliases.add(alias);
            }
            json.add("aliases", aliases);
        }
        // Words this quest answers to in lookups by tag, when it has any. Sparse: absent means
        // "no tags", which is every quest an older server ever sent -- and the client needs them
        // for the same `#tag` lookups the server answers, so a press naming a tag opens the
        // first quest carrying it. Unversioned, like the flags below: an old reader ignores the
        // key and resolves by id alone.
        if (!quest.tags().isEmpty()) {
            JsonArray tags = new JsonArray();
            for (String tag : quest.tags()) {
                tags.add(tag);
            }
            json.add("tags", tags);
        }
        // The guide book page this quest belongs to, when it names one. Sparse, for the same
        // reason: absent means none, which is every quest an older server sent. A reference the
        // quest card shows; Tenet has no guide integration, so nothing reads it further.
        if (!quest.guidePage().isEmpty()) {
            json.addProperty("guidePage", quest.guidePage());
        }
        // Both halves of each, since version 13. See `textAsJson`. The quest's own title is the field
        // that used to draw a raw key on a node, so this is the fix as much as it is the feature.
        textAsJson(quest.title(), "title", json);
        quest.subtitle().ifPresent(subtitle -> textAsJson(subtitle, "subtitle", json));
        // The quest's picture since version 18: an item id (with its components), a texture path, or
        // an entity id, with the kind beside it. Absent kind means the item arm, which is every quest
        // a version-17 server ever sent — and a version-17 reader draws a texture path or an entity id
        // as a missing item, which is the honest fallback for a picture it cannot draw.
        //
        // Omitted when the file named none: the client adopts the first task's picture instead
        // (cycling for a filter), and a paper default on the wire would read as explicit and block
        // the adoption.
        //
        // <p>"Named none" is identity, not value: {@code optionalFieldOf("icon", Icon.DEFAULT_ICON)}
        // substitutes that <b>same instance</b> when the field is absent (see {@code chapterIcon} for
        // the chapters' half of this), so {@code !=} separates "the file said nothing" from "the file
        // said paper" — and an author who truly wants paper keeps it, on every surface that draws an
        // explicit icon. Comparing by value would not work: an absent icon and an authored paper are
        // equal, and the second would vanish from the wire exactly like the first.
        if (quest.icon() != dev.ellipog.tenet.quest.Icon.DEFAULT_ICON) {
            iconAsJson(quest.icon(), json, "icon", "iconComponents", "iconKind");
        }
        json.addProperty("x", quest.layout().x());
        json.addProperty("y", quest.layout().y());
        json.addProperty("size", quest.layout().size());
        // Lowercase, because that is the spelling a quest file uses and the spelling the validator
        // accepts. The enum's own name is uppercase, and sending that would make the wire format
        // differ from the file format for no reason a reader could guess -- and `byName` on the client
        // is case-insensitive precisely so this cannot matter.
        json.addProperty("shape", quest.layout().shape().name().toLowerCase(java.util.Locale.ROOT));
        // The icon's size as a share of the node, and whether to draw the name under it. Both are
        // presentation, both are per quest, and both are decisions the server holds and the client
        // honours -- so both have to cross.
        //
        // `showTitle` travelling is the same lesson as `shape` before it: a field the validator accepts
        // and the client never hears about is a field that reads as supported and does nothing.
        json.addProperty("iconScale", quest.layout().iconScale());
        // Degrees clockwise, and it crosses for the same reason `shape` does: the client is what draws
        // and hit-tests the node, so a rotation the server held and the client never heard about would
        // be a field that reads as supported and does nothing.
        json.addProperty("rotation", quest.layout().rotation());
        // The dependency *rules*, not just the edges. The client is what draws the lines and the card,
        // and without these it can only ask "is this prerequisite completed" -- which is the wrong
        // question for two of the four modes, and made a satisfied prerequisite draw as unmet.
        //
        // Absent rather than defaulted for the mode, because absence is a meaning: the quest has no
        // opinion and the chapter's default applies. Sending "all_completed" for a quest that said
        // nothing would make the client unable to tell the two apart.
        quest.prerequisiteMode().ifPresent(mode ->
                json.addProperty("prerequisiteMode", mode.name().toLowerCase(java.util.Locale.ROOT)));
        // The chapter's default, because the client has no chapter record and an absent
        // `prerequisiteMode` means "whatever the chapter says". Sent per quest like `chapterLinear` is,
        // for the same reason and with the same cost.
        json.addProperty("chapterDefaultPrerequisiteMode",
                chapter.defaultPrerequisiteMode().name().toLowerCase(java.util.Locale.ROOT));
        // Auto-claim travels the same way and for the same reason: the client needs the effective mode
        // for a quest that says nothing (to suppress a toast the author asked not to see), and it has no
        // chapter record. The quest's own mode goes only when it sets one -- absence means "whatever the
        // chapter says" -- while the chapter's is sent already resolved against the pack setting, so the
        // client's answer matches the server's granting exactly.
        quest.rules().autoClaim().ifPresent(mode ->
                json.addProperty("autoClaim", mode.name().toLowerCase(java.util.Locale.ROOT)));
        json.addProperty("chapterAutoClaim",
                chapter.autoClaim().resolved(TenetQuests.settings().defaultAutoClaim()).name()
                        .toLowerCase(java.util.Locale.ROOT));
        json.addProperty("minRequired", quest.minRequired());
        json.addProperty("maxCompletableDependents", quest.rules().maxCompletableDependents());
        // Whether this quest is marked a side quest. Sparse: absent means it is not, and the
        // flag is a marker — an optional quest gates exactly like any other — which the client
        // reads for its card.
        if (quest.rules().optional()) {
            json.addProperty("optional", true);
        }
        // Whether this quest measures before its gate opens: its own flag or its chapter's
        // default, resolved here because the client holds no chapter record. Sparse: absent
        // means strict, which is what every older server says. The client needs it so a
        // flexible quest with unmet dependencies can draw gated while staying measurable —
        // without it every such quest reads UNLOCKED and whole flexible chapters look open.
        if (ProgressionEngine.isFlexible(quest, chapter)) {
            json.addProperty("flexible", true);
        }
        // The reveal flags. Every one of them is a presentation decision the client makes against state
        // it already has -- dependency states, task progress -- so they travel as data and the client
        // needs no engine of its own.
        //
        // The first two are three-state in the file -- absent means "the chapter decides" -- and this is
        // where that is resolved, deliberately the only place: the chapter's own defaults are an
        // authoring convenience, and both sides reading them would be two answers to one question. What
        // goes on the wire is a boolean, which is what the client has always read.
        json.addProperty("hideUntilDependenciesComplete",
                quest.rules().hideUntilDependenciesComplete(
                        chapter.rules().defaultHideUntilDependenciesComplete()));
        json.addProperty("hideUntilDependenciesVisible",
                quest.rules().hideUntilDependenciesVisible(
                        chapter.rules().defaultHideUntilDependenciesVisible()));
        json.addProperty("hideDependencyLines", quest.rules().hideDependencyLines());
        json.addProperty("hideTextUntilComplete", quest.rules().hideTextUntilComplete());
        json.addProperty("hideDetailsUntilStartable", quest.rules().hideDetailsUntilStartable());
        // How this quest presents itself, since version 17. The width travels already resolved
        // against the chapter's default — like the reveal flags above — so the client reads one
        // number: the quest's own when it sets one, else the chapter's, else 0 for the kind's
        // default. The edge flag and the toast flag are the quest's own; absence is the ordinary
        // case and means drawn edges and announced completion, which is what a version-16 reader
        // does with a quest it hears nothing about.
        int minWidth = quest.minWidth() != 0
                ? quest.minWidth()
                : chapter.rules().defaultMinWidth();
        json.addProperty("minWidth", minWidth);
        if (quest.hideDependentLines()) {
            json.addProperty("hideDependentLines", true);
        }
        if (quest.disableToast()) {
            json.addProperty("disableToast", true);
        }
        // Whether this quest wears no lock mark of its own. Sparse and unversioned, like the
        // viewer flag below: absent means the quest does not opt out, and whether a mark draws
        // is the file's answer on the root beside it; either silence wins, and the canvas
        // reads both.
        if (quest.hideLockIcon()) {
            json.addProperty("hideLockIcon", true);
        }
        // Whether recipe viewers list this quest: the quest's own tristate resolved against the
        // file's default, since the client holds no file record to resolve one from. Sparse and
        // unversioned — sent only when hidden, so absence means shown, which is what every tree a
        // version-19 server ever sent says. An old reader ignores the key and lists everything,
        // which is the same additive shape as every bumped field, without moving TREE_VERSION.
        if (!quest.showInRecipeMod(TenetQuests.settings().defaultDisableRecipeMod())) {
            json.addProperty("hideFromViewers", true);
        }
        json.addProperty("invisibleUntilTasks", quest.rules().invisibleUntilTasks());
        quest.exclusiveGroup().ifPresent(group -> json.addProperty("exclusiveGroup", group));
        json.addProperty("showTitle", quest.showTitle());
        json.addProperty("invisible", quest.invisible());
        // Lowercase for the same reason `shape` is: a linear chapter is written lowercase, and the enum
        // name is uppercase. `byName` on the client would cope either way; matching the file format
        // means a packet dump reads like a quest file.
        json.addProperty("chapterLinear",
                chapter.progressionMode() == dev.ellipog.tenet.quest.ProgressionMode.LINEAR);
        json.addProperty("order", orderInChapter);

        JsonArray description = new JsonArray();
        // The English words for the paragraphs that are translation keys, paired by index with the
        // paragraphs above. Absent when no paragraph is translatable, which is the common case and the
        // one that has to cost nothing: a pack that writes its text out plainly sends exactly the bytes
        // it sent before this field existed.
        //
        // An empty entry means "this paragraph is a literal", and that empty is load-bearing -- it is
        // the client's only way to tell a literal from a key, and a paragraph it got wrong is a raw key
        // drawn on a card. See `ClientQuestCache.Entry#descriptionText`.
        JsonArray descriptionFallbacks = new JsonArray();
        boolean anyTranslatable = false;
        for (var paragraph : quest.description()) {
            description.add(paragraph.value());
            if (paragraph.translatable()) {
                anyTranslatable = true;
                // The author's own words, or the key itself when they wrote none. The field's presence
                // is what marks the paragraph as a key, so it is sent either way -- a translatable
                // paragraph with no fallback still has the pack's conventional key to be found by.
                descriptionFallbacks.add(paragraph.fallback().orElse(paragraph.value()));
            }
            else {
                descriptionFallbacks.add("");
            }
        }
        json.add("description", description);
        if (anyTranslatable) {
            json.add("descriptionFallbacks", descriptionFallbacks);
        }

        JsonArray dependencies = new JsonArray();
        for (QuestRef dependency : quest.dependencies()) {
            dependencies.add(dependency.id());
        }
        json.add("dependsOn", dependencies);

        JsonArray tasks = new JsonArray();
        boolean chapterConsumes = chapter.defaultConsumeItems()
                || TenetQuests.settings().defaultConsumeItems();
        for (QuestTask task : quest.tasks()) {
            tasks.add(taskAsJson(task, chapterConsumes));
        }
        json.add("tasks", tasks);

        JsonArray rewards = new JsonArray();
        for (QuestReward reward : quest.rewards()) {
            rewards.add(rewardAsJson(reward));
        }
        json.add("rewards", rewards);

        return json;
    }

    /**
     * One task, as the client draws it.
     *
     * <p>Three things beyond the display: whether it is optional, whether a player can hand it over
     * rather than have it taken, and how much is required. All three are needed to draw a row that
     * says the right thing — a greyed-out optional task, a button only where a button works, and
     * {@code 5 / 8}.
     */
    private static JsonObject taskAsJson(QuestTask task, boolean chapterConsumes) {
        TaskDisplay display = TaskTypes.displayOf(task);

        JsonObject json = new JsonObject();
        json.addProperty("type", task.type().toString());
        // The type's own icon, so a task with no item still has something to draw.
        json.addProperty("icon", TaskTypes.iconOf(task.type()).item().toString());
        json.addProperty("item", display.item().map(ref -> ref.item().toString()).orElse(""));
        display.item().ifPresent(ref -> componentsAsJson(ref, "itemComponents", json));
        json.addProperty("count", display.count());
        json.addProperty("label", display.label());
        json.addProperty("labelFallback", display.labelFallback());
        // The subject the key is formatted with -- the biome, the stage, the mob. Sent rather than left
        // to the client to derive, because only the type knows what its sentence is about; see
        // TaskDisplay#labelArg and ClientQuestCache.TaskEntry#text for the "1" that reading the count
        // into every key produced.
        json.addProperty("labelArg", display.labelArg());
        json.addProperty("optional", task.optional());
        // Whether this task's completion is announced, since version 17. Only when true: absence
        // means announced, which is what a version-16 reader does with a task it hears nothing
        // about. Read beside the quest's own flag where notices are decided.
        if (task.common().disableToast()) {
            json.addProperty("disableToast", true);
        }
        // The author's texture, since version 18: the row blits it rather than the stack. Only when
        // set — absence means the type's own picture, which is every task a version-17 tree sent.
        // Since version 21 the author's item or egg travels beside the requirement rather than in
        // it: see the picture keys below. Before that it travelled in the display's own item, so a
        // tag task wearing an oak log opened oak-log recipes instead of the tag.
        if (!display.textureIcon().isEmpty()) {
            json.addProperty("textureIcon", display.textureIcon());
        }
        // The author's atlas sprite: the row draws it from the atlas rather than the stack.
        // Only when set, like the texture arm beside it.
        if (!display.spriteIcon().isEmpty()) {
            json.addProperty("spriteIcon", display.spriteIcon());
        }
        // The author's item picture, since version 21: the row draws it instead of the requirement,
        // and recipe lookups, viewer indexes and choice offers all read the requirement. Only when
        // set — absence means the type's own picture, which is every task a version-20 tree sent. A
        // version-20 reader ignores both keys and draws the requirement, which is the honest fallback:
        // it shows what the task needs rather than the picture it cannot draw.
        display.picture().ifPresent(ref -> {
            json.addProperty("picture", ref.item().toString());
            componentsAsJson(ref, "pictureComponents", json);
        });
        // Whether the row shows a Submit button, asked with the chapter's consume-items default: an
        // item task that does not say whether it consumes inherits it, and a row that hid the button
        // while the take still happened is the promise this field exists to keep. See
        // TaskBehaviour#waitsForSubmit for the other half.
        var behaviour = TaskTypes.behaviourOf(task);
        json.addProperty("manual", behaviour
                .map(known -> known.canSubmitByHand(task, chapterConsumes))
                .orElse(false));
        // Whether the tick leaves this task alone and the press is what finishes it. The row's button
        // rule is not the same for the two cases: a checkmark is handed in before its count is met --
        // its count is the press itself -- while a task that takes is handed in once it is, because
        // there the count is the price. `taskReady` in the progress delta carries the second half.
        if (behaviour.map(known -> known.waitsForSubmit(task, chapterConsumes)).orElse(false)) {
            json.addProperty("waits", true);
        }
        // Whether the tick never measures this task at all, since version 19. Only when true:
        // absence means the tick measures, which is every task a version-18 tree ever sent. The
        // button rule reads this rather than the live count, because the live count of a task the
        // tick never measures is always zero — see `submitOffered`.
        if (behaviour.map(known -> known.manualOnly(task)).orElse(false)) {
            json.addProperty("manualOnly", true);
        }
        // The observation fields, which are the only per-type data the client needs to do work with:
        // it ray-traces against them and submits when the timer is done. `manual` stays false above --
        // no button -- and the submission is accepted because the type says so, not because of a flag
        // on the wire.
        if (task instanceof dev.ellipog.tenet.quest.task.ObservationTask observation) {
            json.addProperty("observeType", observation.observeType().wire());
            json.addProperty("observeTarget", observation.toObserve());
            json.addProperty("observeTicks", observation.timer());
        }
        // A tag task's tag, as a field rather than scraped out of its sentence. The row reads
        // "#minecraft:logs" either way, but a recipe viewer has to know *which items* that is to answer
        // "which quests use this log", and display text is not data. It travels on the existing tree
        // payload: the same message, one more field, and only for the type that has one.
        if (task instanceof dev.ellipog.tenet.quest.task.ItemTagTask tag) {
            json.addProperty("tag", tag.tag().toString());
        }
        // A break task's block and block tag, as fields rather than scraped out of its sentence.
        // The row reads "Break stone" either way, but a recipe viewer has to know *which block*
        // that is to answer "which quests want this stone broken", and display text is not data.
        // Same message, two more fields, only for the type that has them; absent means every
        // task an older tree sent, which none of them are — sparse and unversioned, like the
        // quest flags, without moving TREE_VERSION.
        if (task instanceof dev.ellipog.tenet.quest.task.BreakTask broken) {
            broken.block().ifPresent(block -> json.addProperty("block", block.toString()));
            broken.blockTag().ifPresent(tag -> json.addProperty("blockTag", tag.toString()));
        }
        // A filter task's expression, as a field rather than scraped out of its sentence. The row
        // cycles through what it names and previews the matches on click, and both need the
        // expression itself: display text is not data. It travels on the existing tree payload:
        // the same message, one more field, and only for the type that has one. Absent means
        // every task an older tree sent, which none of them are.
        if (task instanceof dev.ellipog.tenet.quest.task.FilterTask filter) {
            json.addProperty("filter", filter.filter().toString());
        }
        conditionsAsJson(task.common().conditions(), json);
        return json;
    }

    private static JsonObject rewardAsJson(QuestReward reward) {
        RewardDisplay display = RewardTypes.displayOf(reward);

        JsonObject json = new JsonObject();
        json.addProperty("type", reward.type().toString());
        json.addProperty("icon", RewardTypes.iconOf(reward.type()).item().toString());
        json.addProperty("item", display.item().map(ref -> ref.item().toString()).orElse(""));
        display.item().ifPresent(ref -> componentsAsJson(ref, "itemComponents", json));
        json.addProperty("count", display.count());
        json.addProperty("label", display.label());
        json.addProperty("labelFallback", display.labelFallback());
        json.addProperty("labelArg", display.labelArg());
        // The base mechanics, resolved against the tree's own settings here rather than on the client:
        // the client draws what the server would do, and a client resolving a file default would be a
        // second opinion about the file. See RewardCommon.
        QuestSettings settings = TenetQuests.settings();
        json.addProperty("auto", reward.common().autoClaim(settings.defaultAutoClaim()).name()
                .toLowerCase(java.util.Locale.ROOT));
        json.addProperty("team", reward.common().teamReward(settings.defaultTeamReward()));
        json.addProperty("excludeFromClaimAll", reward.common().excludeFromClaimAll());
        // Recorded since version 17 for the reward-level notice. Only when true: absence means
        // announced, which is what a version-16 reader does. No notice reads it yet — only quest
        // and task notices exist — so this travels as data for the notice that will.
        if (reward.common().disableToast()) {
            json.addProperty("disableToast", true);
        }
        // The author's texture, since version 18: the row blits it rather than the stack. Only when
        // set, for the reason the task's own gives above. Since version 21 the author's item travels
        // beside the payout rather than in it, like the task's own.
        if (!display.textureIcon().isEmpty()) {
            json.addProperty("textureIcon", display.textureIcon());
        }
        // The author's atlas sprite: the row draws it from the atlas rather than the stack.
        // Only when set, like the texture arm beside it.
        if (!display.spriteIcon().isEmpty()) {
            json.addProperty("spriteIcon", display.spriteIcon());
        }
        // The author's item picture, since version 21: see the task's own keys above.
        display.picture().ifPresent(ref -> {
            json.addProperty("picture", ref.item().toString());
            componentsAsJson(ref, "pictureComponents", json);
        });
        conditionsAsJson(reward.common().conditions(), json);
        return json;
    }

    /**
     * The gates a task or a reward carries, as the client draws them.
     *
     * <p>Only when there are any, so the tree a pack without conditions sends is byte-identical to what
     * it sent before this feature: the version number is a description, and the common case should pay
     * nothing for it. The list's order is the authored order and carries meaning — the unmet mask on
     * the progress channel indexes into it — so it is written as it stands and never sorted.
     */
    private static void conditionsAsJson(List<QuestCondition> conditions, JsonObject json) {
        if (conditions.isEmpty()) {
            return;
        }
        JsonArray array = new JsonArray();
        for (QuestCondition condition : conditions) {
            ConditionDisplay display = ConditionTypes.displayOf(condition);
            JsonObject entry = new JsonObject();
            display.item().ifPresent(ref -> {
                entry.addProperty("item", ref.item().toString());
                entry.addProperty("count", ref.count());
                componentsAsJson(ref, "itemComponents", entry);
            });
            entry.addProperty("label", display.label());
            entry.addProperty("labelFallback", display.labelFallback());
            entry.addProperty("labelArg", display.labelArg());
            array.add(entry);
        }
        json.add("conditions", array);
    }

    /**
     * A chapter's icon and its component patch, or an empty id when the chapter authored none.
     *
     * <h2>Why a defaulted icon is not sent as though it were a choice</h2>
     *
     * <p>{@code Chapter.icon} defaults to {@link ItemRef#DEFAULT_ICON}, which is
     * {@code minecraft:paper} — so a chapter that declares no icon was reaching the client as a
     * deliberate-looking paper item, and a paper item at eighteen pixels reads as a blank white square.
     * On the claim menu's banner, which is the one row that draws a chapter's icon large, every
     * unauthored chapter looked like a missing texture.
     *
     * <p>The wire already has the distinction this needs: an <b>empty id</b> means "no icon" and a
     * non-empty id with an empty stack means "a missing item", which is what the sidebar's rows and the
     * banner both read. So an unauthored chapter sends nothing rather than sending paper.
     *
     * <h2>How "authored" is decided, and why identity is the right test</h2>
     *
     * <p>{@code optionalFieldOf("icon", Icon.DEFAULT_ICON)} substitutes that <b>same instance</b>
     * when the field is absent, so {@code ==} separates "the file said nothing" from "the file said
     * paper" — and the second is preserved, because a chapter that genuinely wants paper keeps it.
     * Comparing by value would not work: an authored paper is equal to the default. The behaviour this
     * rests on is asserted in {@code QuestSyncTest} rather than assumed, because it is a property of a
     * codec in a library this project does not own.
     */
    private static void chapterIcon(Chapter chapter, JsonObject json, String idField, String componentsField) {
        if (chapter.icon() == dev.ellipog.tenet.quest.Icon.DEFAULT_ICON) {
            json.addProperty(idField, "");
            return;
        }
        iconAsJson(chapter.icon(), json, idField, componentsField, idField + "Kind");
    }

    /**
     * One icon: the id the client resolves, blits or reads as an egg, with the arm beside it.
     *
     * <p>The kind travels only when the icon is not an item: absence means the item arm, which is
     * every icon a version-17 tree ever sent. An older reader therefore draws a texture path or an
     * entity id as a missing item — the honest fallback, and the additive kind this file's own
     * ledger demands. Components travel only on the item arm, because only an item has any.
     */
    private static void iconAsJson(dev.ellipog.tenet.quest.Icon icon, JsonObject json, String idField,
                                   String componentsField, String kindField) {
        json.addProperty(idField, icon.wireId());
        if (icon instanceof dev.ellipog.tenet.quest.Icon.Item item) {
            componentsAsJson(item.ref(), componentsField, json);
            return;
        }
        json.addProperty(kindField, icon.wireKind());
    }

    /**
     * A {@link QuestText} as the two fields a client needs: the text, and the English words when the
     * text is a key.
     *
     * <h2>Why the fallback is a field of its own rather than the text</h2>
     *
     * <p>Because a client cannot tell a key from a literal by looking at one string, and getting it
     * wrong is visible: {@code QuestText.value()} is the <b>key</b> for a translatable text, so a tree
     * that sent only that drew {@code quest.tenet.punch_a_tree} on the node. That was the bug. The
     * field's <i>presence</i> is what marks the text as translatable, and its value is what a player
     * reads when nothing translated the key — the same arrangement {@code label}/{@code labelFallback}
     * already uses for a task's or a reward's sentence.
     *
     * <p>A translatable text with no fallback of its own still sends one, and it sends the key. That
     * keeps the presence meaningful — the client needs to know to look the conventional key up — and
     * leaves the author's key as the last resort, which is the only text that file ever named.
     *
     * <p>A literal sends no fallback field at all, so a pack that writes its text out plainly sends
     * exactly the bytes it sent before this existed.
     */
    private static void textAsJson(QuestText text, String field, JsonObject json) {
        json.addProperty(field, text.value());
        if (text.translatable()) {
            json.addProperty(field + "Fallback", text.fallback().orElse(text.value()));
        }
    }

    /**
     * The item's component patch, beside its id, or nothing when it has none.
     *
     * <p>A JSON object in the datapack's spelling, which is the same shape the quest file uses -- so
     * the wire and the file cannot disagree about what a component looks like. Encoding it here with
     * the codec rather than building it by hand keeps that true as versions change what components
     * exist.
     */
    private static void componentsAsJson(ItemRef ref, String field, JsonObject json) {
        if (ref.components().isEmpty()) {
            return;
        }
        net.minecraft.core.component.DataComponentPatch.CODEC
                .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, ref.components())
                .result().ifPresent(value -> json.add(field, value));
    }

    // ------------------------------------------------------------------
    // Progress
    // ------------------------------------------------------------------

    /**
     * One team's progress, as JSON, sending every quest.
     *
     * <p>The full form, and now a caller of the delta form rather than a parallel implementation.
     * Kept because a full sync is what a joining client needs and what a reload needs, and because
     * having one name for "all of it" keeps the delta's own name honest.
     */
    public static byte[] progressAsJson(ProgressionEngine.Resolution resolution,
                                        TeamProgress progress,
                                        QuestIndex index) {
        return progressAsJson(resolution, progress, index, NOBODY);
    }

    /**
     * The same, with the per-member pictures the engine keeps: who is holding what toward each task.
     *
     * <p>A separate form rather than a wider one because the pictures are not part of a quest's
     * <i>state</i> -- they are live numbers that go down as well as up, and a caller that has no team
     * behind it (a dump, a test) has none to give.
     */
    public static byte[] progressAsJson(ProgressionEngine.Resolution resolution,
                                        TeamProgress progress,
                                        QuestIndex index,
                                        ProgressService.Contributors contributors) {
        return progressDelta(resolution, progress, index, null, contributors).json();
    }

    /**
     * A JSON message and the snapshot of what was sent in it.
     *
     * <p>Two things come out of one call because they cannot be allowed to disagree. The caller stores
     * the snapshot so the next call can compute a delta, and if the snapshot described anything other
     * than the bytes that went out, the next delta would be computed against a fiction — reporting a
     * quest as unchanged that the client has never seen, or as changed on every tick forever.
     *
     * @param json     the bytes to send
     * @param snapshot every quest id and the exact JSON sent for it
     */
    public record Delta(byte[] json, Map<String, String> snapshot) {
    }

    /**
     * One team's progress as JSON, sending only what differs from {@code previous}.
     *
     * <h2>The defect this fixes</h2>
     *
     * <p>Before this, every progress message carried the whole questline and the client replaced its
     * whole map. A quest's state is recomputed on every player tick, so on any tick where something
     * moved, the entire tree crossed the wire to every member of the team — and the more content a pack
     * has, the more it cost, for a change that was almost always one quest's task counter.
     *
     * <p>{@code previous == null} means a full sync, and that is the only way to get one. So the only
     * caller that can produce a full is one that has no snapshot, which is exactly the set of callers
     * that need one: a join, a reconnect, a reload, and a player whose party changed.
     *
     * <h2>What it costs, stated rather than implied</h2>
     *
     * <p>The <b>wire</b> shrinks and the <b>CPU does not</b>. Every quest is still serialised on every
     * call, because the serialised form is what the comparison is made of — there is no cheaper way to
     * ask "is this quest's resolved state different from what I sent" without keeping a parallel
     * structure of resolved states per player, and that structure would be a second description of the
     * same thing. So this is a bandwidth optimisation for large packs, not a general one, and
     * pretending otherwise would be the kind of claim that gets remembered wrongly.
     *
     * <h2>Removals are named, because absence cannot mean removed</h2>
     *
     * <p>A delta says "these quests are now thus". It cannot say "this quest is gone" by omitting it,
     * because omission is also what an unchanged quest looks like. So ids that were in the previous
     * snapshot and are not in the index are listed explicitly under {@code removed}, and the client
     * deletes them. Without it, a quest deleted from a file would live on in the client's cache until
     * the player reconnected — a ghost node on the canvas that cannot be clicked and cannot be
     * explained.
     *
     * @param previous the snapshot from the last message this player was sent, or null for a full sync
     */
    public static Delta progressDelta(ProgressionEngine.Resolution resolution,
                                      TeamProgress progress,
                                      QuestIndex index,
                                      Map<String, String> previous) {
        return progressDelta(resolution, progress, index, previous, NOBODY);
    }

    /** The same, with the per-member pictures. See {@link #progressAsJson} for why they are separate. */
    public static Delta progressDelta(ProgressionEngine.Resolution resolution,
                                      TeamProgress progress,
                                      QuestIndex index,
                                      Map<String, String> previous,
                                      ProgressService.Contributors contributors) {
        return progressDelta(resolution, progress, index, previous, contributors, java.util.Set.of());
    }

    /**
     * The same, with the quests whose stage gate this player does not pass.
     *
     * <p>The third per-player overlay on this wire, after the contributors and the claimable flag, and for
     * the same reason: a quest's stored state is the team's, and what <i>this</i> player may see and collect
     * is not. A gated quest reads as LOCKED for a player without the stage and as whatever the engine says
     * for one with it, which is what makes a stage gate look like every other gate on the canvas.
     */
    public static Delta progressDelta(ProgressionEngine.Resolution resolution,
                                      TeamProgress progress,
                                      QuestIndex index,
                                      Map<String, String> previous,
                                      ProgressService.Contributors contributors,
                                      java.util.Set<String> stageLocked) {
        return progressDelta(resolution, progress, index, previous, contributors, stageLocked, Map.of());
    }

    /**
     * The same, with every condition lock this player has.
     *
     * <p>The fourth per-player overlay on this wire — after the contributors, the claimable flag and
     * the stage gate — and the same kind of fact: a task's or a reward's conditions are asked of one
     * player, so two members of a party can look at one quest and see different rows shut. What a
     * locked row <i>is</i> travels with the tree ({@code conditions}); this carries only which rows
     * and which of their conditions failed, which is the part that changes with the world.
     */
    public static Delta progressDelta(ProgressionEngine.Resolution resolution,
                                      TeamProgress progress,
                                      QuestIndex index,
                                      Map<String, String> previous,
                                      ProgressService.Contributors contributors,
                                      java.util.Set<String> stageLocked,
                                      Map<String, ProgressService.LockView> locks) {
        return progressDelta(resolution, progress, index, previous, contributors, stageLocked, locks,
                NO_LIVE);
    }

    /**
     * The same, with the live counts of the tasks that wait for a press.
     *
     * <p>A task that takes what it asks for records nothing until the press, so its stored progress is
     * zero while the player has everything they need. The row's count is therefore the live one, which
     * is also what lights the Submit button — and it is the only reading a player can be shown without
     * lying about either the count or the button.
     */
    public static Delta progressDelta(ProgressionEngine.Resolution resolution,
                                      TeamProgress progress,
                                      QuestIndex index,
                                      Map<String, String> previous,
                                      ProgressService.Contributors contributors,
                                      java.util.Set<String> stageLocked,
                                      Map<String, ProgressService.LockView> locks,
                                      ProgressService.Live live) {
        JsonObject changed = new JsonObject();
        Map<String, String> snapshot = new LinkedHashMap<>();

        // Which quests an exclusive choice has shut out for good, decided once per message rather
        // than once per quest: the engine's own first pass over stored progress, which is also
        // what the resolver reads — so the mark and the LOCKED state cannot disagree. See
        // `ProgressionEngine.excludedIds`.
        java.util.Set<String> excluded = ProgressionEngine.excludedIds(index, progress);

        for (QuestIndex.QuestEntry entry : index.quests()) {
            Quest quest = entry.quest();
            boolean consumes = entry.chapter().defaultConsumeItems()
                    || TenetQuests.settings().defaultConsumeItems();
            String encoded = oneQuestAsJson(resolution, progress, quest, contributors, stageLocked,
                    locks.getOrDefault(quest.id(), ProgressService.LockView.NONE), live,
                    consumes, excluded.contains(quest.id()));

            snapshot.put(quest.id(), encoded);
            if (previous == null || !encoded.equals(previous.get(quest.id()))) {
                changed.add(quest.id(), JsonParser.parseString(encoded));
            }
        }

        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("quests", changed);

        // How far every chapter has got, by chapter id. Sent whole in both a full sync and a delta, and
        // that is not a shortcut: the map is a handful of short strings, and a delta for it would need a
        // second baseline to compare against for a fact that can only move when a quest's state did --
        // which is a quest the same message already carries. The reading on the other side is
        // "replace what you have", which cannot leave a stale chapter behind.
        //
        // A chapter the tree does not describe cannot appear here, and a chapter absent from here reads
        // as open on the client -- the direction that hides least, and the one that makes an older server
        // keep drawing every chapter.
        JsonObject chapters = new JsonObject();
        for (Map.Entry<String, QuestState> state : resolution.chapterStates().entrySet()) {
            // The enum's own name, uppercase, which is the spelling the per-quest `state` beside this uses.
            // Two spellings of one vocabulary in one message would be a reader's trap, and the client's
            // reader is the same `valueOf` for both.
            chapters.addProperty(state.getKey(), state.getValue().name());
        }
        root.add("chapters", chapters);

        if (previous != null) {
            JsonArray removed = new JsonArray();
            for (String id : previous.keySet()) {
                if (!snapshot.containsKey(id)) {
                    removed.add(id);
                }
            }
            if (!removed.isEmpty()) {
                root.add("removed", removed);
            }
        }

        return new Delta(root.toString().getBytes(StandardCharsets.UTF_8), Map.copyOf(snapshot));
    }

    /**
     * One quest's resolved state, as a JSON object's text.
     *
     * <p>Serialised to a string and parsed back by the caller, which looks wasteful and is the point:
     * the string is what the delta comparison is made of, so a quest whose text is byte-identical to
     * what was sent is provably unchanged. Building the {@code JsonObject} directly and comparing
     * those would compare object identity, and two equal-looking trees would read as different on
     * every tick — the delta would send everything and the whole exercise would be a no-op that
     * looked like it worked.
     *
     * <h2>Key order is therefore load-bearing, and stable by construction</h2>
     *
     * <p>{@code JsonObject} preserves insertion order, so the same input produces the same text. If
     * that ever stopped being true the failure would be a delta that always sends everything, which
     * is exactly the behaviour this replaced — so it would look like the optimisation had never been
     * made rather than like a bug.
     */
    private static String oneQuestAsJson(ProgressionEngine.Resolution resolution,
                                         TeamProgress progress,
                                         Quest quest,
                                         ProgressService.Contributors contributors,
                                         java.util.Set<String> stageLocked,
                                         ProgressService.LockView locks,
                                         ProgressService.Live live,
                                         boolean chapterConsumes,
                                         boolean excluded) {
        QuestProgress stored = progress.progressOf(quest);

        JsonObject one = new JsonObject();
        // The gate first: a quest this player has not unlocked reads as locked whatever the team's stored
        // state says, and the claimable flag below is decided from this same answer rather than separately.
        boolean gated = stageLocked.contains(quest.id());
        one.addProperty("state", (gated ? QuestState.LOCKED : resolution.stateOf(quest)).name());

        // Shut out for good by an exclusive choice — a taken group, or a reached dependent cap —
        // rather than merely not yet unlocked. Sparse: absent means still in the running, which is
        // every quest on a server that predates the mark. The reader that hides excluded quests
        // reads this rather than the state, which cannot tell the two apart.
        if (excluded) {
            one.addProperty("excluded", true);
        }

        // Whether the quest is finished with something still to collect used to be sent here, as a
        // `claimable` flag built from ProgressService.anyoneCouldClaim. Nothing ever read it: the client
        // answers the per-player question itself from `claims` below, which is why the flag could not
        // have been right even in principle -- it is the team's answer to a player's question. Removed
        // rather than kept as a hint, because a field with no reader is a field the next person has to
        // prove is dead before touching anything near it.

        // Who has collected what. Per player, because a claim is a player's own -- the client answers
        // "does the player at this keyboard have something to collect" from this map with its own
        // UUID, which a single team-wide boolean could never do. Sparse: an empty side is absent.
        QuestProgress storedProgress = progress.progressOf(quest);
        QuestClaims claims = storedProgress.claims();
        if (!claims.team().isEmpty()) {
            JsonArray team = new JsonArray();
            claims.team().stream().sorted().forEach(team::add);
            one.add("teamClaims", team);
        }
        if (!claims.players().isEmpty()) {
            JsonObject players = new JsonObject();
            claims.players().forEach((player, indices) -> {
                JsonArray list = new JsonArray();
                indices.stream().sorted().forEach(list::add);
                players.add(player.toString(), list);
            });
            one.add("claims", players);
        }
        // A pre-per-player save's "collected": collected for everyone, for good. Sent so the client
        // does not offer a claim the server would refuse.
        if (storedProgress.legacySettled()) {
            one.addProperty("settled", true);
        }
        // And whether the team's payouts are held, so the panel can say why a claim would be refused
        // rather than showing a live button the server rejects. See TeamProgress#rewardsBlocked.
        if (progress.rewardsBlocked()) {
            one.addProperty("rewardsBlocked", true);
        }

        long remaining = resolution.cooldownOf(quest);
        if (remaining > 0) {
            one.addProperty("cooldown", remaining);
        }

        // How many times a repeatable quest has been finished. Sparse: absent means never, which
        // is every quest but the repeated ones — and the card's count and the command's read the
        // same number the engine keeps, because a second spelling of the count is how the two
        // would come to disagree.
        int timesDone = stored.timesCompleted();
        if (timesDone > 0) {
            one.addProperty("timesCompleted", timesDone);
        }

        // Per-task progress, indexed by task position -- the same keying QuestProgress uses, and it
        // carries the same limitation: reordering a quest's tasks moves progress to a different task.
        // Sent anyway because "you have 5 of 8 logs" is the single most useful line in a quest book,
        // and a book that cannot show it is not worth opening.
        //
        // The number is the display value rather than the stored one, because a task that waits for a
        // press has no stored progress until the press: its stored count is zero while the player has
        // everything, so sending that would draw "0 of 8" at 8 of 8 and leave the Submit button dark.
        // `live` answers zero for every other task, so for them this is the stored value unchanged.
        JsonArray tasks = new JsonArray();
        // And which of those waiting tasks the server would accept a press on *now*. It cannot be read
        // off the numbers above: a waiting task records nothing until the press, so "holding eight" and
        // "eight already handed in" are both 8 of 8 on this wire. The two facts that tell them apart --
        // what is recorded and what is merely held -- only meet on the server, so it answers.
        JsonArray ready = new JsonArray();
        for (int i = 0; i < quest.tasks().size(); i++) {
            int held = live.of(quest.id(), i);
            tasks.add(Math.max(stored.progressOf(i), held));
            if (pressAccepted(quest.tasks().get(i), stored.progressOf(i), held, chapterConsumes)) {
                ready.add(i);
            }
        }
        one.add("tasks", tasks);
        if (!ready.isEmpty()) {
            one.add("taskReady", ready);
        }

        // The conditions this player does not meet, per row: the row index, then which of its
        // conditions failed, ascending. Absence means unlocked -- the opposite default from
        // `claimable` above, and deliberately: a client that defaulted to locked would draw every row
        // of every older server's pack as gated. Keyed by row position, the same keying the counts
        // above use and carrying the same limitation about reordering.
        if (!locks.tasks().isEmpty()) {
            JsonObject lockedTasks = new JsonObject();
            locks.tasks().forEach((index, unmet) -> lockedTasks.add(String.valueOf(index), ints(unmet)));
            one.add("taskLocks", lockedTasks);
        }
        if (!locks.rewards().isEmpty()) {
            JsonObject lockedRewards = new JsonObject();
            locks.rewards().forEach((index, unmet) -> lockedRewards.add(String.valueOf(index), ints(unmet)));
            one.add("rewardLocks", lockedRewards);
        }

        // Who is holding what toward each task, by task position -- the "Ellio has four of the eight"
        // line a quest book could never draw, because the engine added those parts up and threw them
        // away. See `ProgressService.CONTRIBUTIONS`, including why a finished task still names its
        // contributors: for a consuming task that picture is the only record of who did the work.
        //
        // Sparse in both directions: a task nobody is carrying anything toward is absent, and so is a
        // member holding nothing. A party's whole picture is a few small numbers, and a player who is
        // alone has none of it until they pick something up.
        JsonObject who = new JsonObject();
        for (int i = 0; i < quest.tasks().size(); i++) {
            Map<UUID, Integer> picture = contributors.of(quest.id(), i);
            if (picture.isEmpty()) {
                continue;
            }
            JsonObject holders = new JsonObject();
            for (Map.Entry<UUID, Integer> each : picture.entrySet()) {
                holders.addProperty(each.getKey().toString(), each.getValue());
            }
            who.add(String.valueOf(i), holders);
        }
        if (!who.isEmpty()) {
            one.add("who", who);
        }

        return one.toString();
    }

    /**
     * Whether the server would accept a press on this task right now.
     *
     * <p>Two facts, and both have to hold: the task is one the press finishes rather than the tick
     * (see {@code TaskBehaviour#waitsForSubmit}), enough is <b>held</b> to pay for it, and it has
     * <b>not</b> been recorded yet — which is what tells "holding eight" apart from "eight already
     * handed in", the two states that look identical on the wire because a waiting task records
     * nothing until the press.
     *
     * <p>Here rather than on the client for exactly that reason: the client holds the display value
     * and the count, and neither of them can answer it.
     */
    private static boolean pressAccepted(QuestTask task, int recorded, int held, boolean chapterConsumes) {
        return TaskTypes.behaviourOf(task).map(behaviour -> {
            int required = behaviour.required(task);
            return behaviour.waitsForSubmit(task, chapterConsumes) && recorded < required && held >= required;
        }).orElse(false);
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /**
     * Sends the tree to one player, compressed and chunked. Called on join, and after a reload.
     *
     * <p>Not a delta, and it does not need to be: the tree is immutable for as long as the quest files
     * are, so there is nothing for a delta to describe. A reload re-sends all of it, and the client
     * replaces what it had — which is correct rather than lazy, because a reload can remove a quest and
     * a tree delta would have to carry removals to say so.
     */
    public static void sendTreeTo(ServerPlayer player, QuestIndex index) {
        // The two halves of the broadcast that are worth separating: building the JSON is a walk of every
        // quest, and deflating it is the other half of the same cost. They are timed separately because the
        // remedies differ -- one is a data-shape problem and the other is a compression-level one -- and
        // because the whole point of the measurement is that **this runs per player**: P editors cost P
        // encodes and P deflates of byte-identical bytes, which is a claim only a per-recipient number can
        // settle. See `WireTiming`.
        boolean timing = EditPhases.on();
        long encodeStarted = timing ? System.nanoTime() : 0L;
        byte[] json = treeAsJson(index);
        long encodedAt = timing ? System.nanoTime() : 0L;
        byte[] packed = SyncWire.pack(json);
        if (timing) {
            WIRE_ENCODE_NANOS += encodedAt - encodeStarted;
            WIRE_DEFLATE_NANOS += System.nanoTime() - encodedAt;
        }
        List<byte[]> parts = SyncWire.chunk(packed);
        int transferId = SyncWire.newTransferId();

        for (int i = 0; i < parts.size(); i++) {
            send(player, new QuestSyncPayload(
                    index.questCount(),
                    index.chapterCount(),
                    // Read from the same place `treeAsJson` does, so the payload's copy and the tree's
                    // copy cannot disagree -- which they would the moment one of the two was changed.
                    packTheme(index),
                    new SyncChunk(transferId, i, parts.size(), true),
                    parts.get(i)));
        }

        if (parts.size() > 1) {
            Constants.LOG.debug("tenet: sent the tree for {} quest(s) in {} chunk(s)",
                    index.questCount(), parts.size());
        }
    }

    /**
     * Sends one player the quest text in the language they read.
     *
     * <h2>What decides the language, and why it is not asked for</h2>
     *
     * <p>The client's own language arrives with the login handshake and is readable as
     * {@code clientInformation().language()}, so a join needs no request and no round trip. What the
     * server cannot see is a player who changes language <b>mid-session</b>, which is why
     * {@code LocaleRequestPayload} exists — see {@link #sendLocaleFor}.
     *
     * <p>Sent even when the pack has no file for the language, and that is not a wasted message: it is
     * how the client learns it is current, and it is what clears a locale the player has just switched
     * away from. See {@link LocaleSyncPayload}.
     */
    public static void sendLocaleTo(ServerPlayer player) {
        String asked = player.clientInformation() == null
                ? "" : player.clientInformation().language();
        sendLocaleFor(player, asked);
    }

    /**
     * The same, for a language the client named itself.
     *
     * <p>The one entry point for both roads — the join handshake's language and a client's own
     * {@code LocaleRequestPayload} — so the two cannot resolve a locale differently.
     *
     * <p>{@code asked} is a string a client chose, so it is normalised by the same rule the file names
     * are and is then only ever a <b>map key</b>. It never becomes a path: the files were read once at
     * load, and this looks a language up among them. A value that is not a locale id at all resolves to
     * nothing, which is answered rather than refused.
     */
    public static void sendLocaleFor(ServerPlayer player, String asked) {
        QuestSettings settings = TenetQuests.settings();
        QuestLanguages languages = TenetQuests.languages();
        String canonical = settings.canonicalLocale();
        String locale = QuestLanguages.normalise(asked);
        String served = languages.servedLocale(locale, canonical);
        List<byte[]> parts = localeChunks(languages, served, canonical);
        int transferId = SyncWire.newTransferId();

        for (int i = 0; i < parts.size(); i++) {
            send(player, new LocaleSyncPayload(locale, served,
                    new SyncChunk(transferId, i, parts.size(), true), parts.get(i)));
        }
    }

    /**
     * One locale's message, packed and chunked, built once and kept.
     *
     * <p>Keyed by the <b>served</b> locale rather than by the language a player asked for, because two
     * players who asked for different things may read the same text: an {@code es_mx} and an
     * {@code es_ar} player on a pack with one Spanish file share one message, and keying by the request
     * would build it twice.
     *
     * <p>Keyed that way it is also bounded without a cap. {@code served} is only ever empty or one of
     * the locales the pack ships, so the number of entries is the number of locale files plus one —
     * there is no request a client can send that adds a second entry.
     *
     * <p>The build is timed into the same counters the tree's is, and only on the miss: a number that
     * stays flat while players join is this cache working, which is worth being able to see rather than
     * infer.
     */
    private static List<byte[]> localeChunks(QuestLanguages languages, String served, String canonical) {
        if (languages != cachedLanguages) {
            // A reload replaces the translations wholesale, so everything packed from the old set is
            // wrong -- including the empty one, which may no longer be empty.
            LOCALE_CHUNKS.clear();
            cachedLanguages = languages;
        }
        List<byte[]> cached = LOCALE_CHUNKS.get(served);
        if (cached != null) {
            return cached;
        }

        boolean timing = EditPhases.on();
        long started = timing ? System.nanoTime() : 0L;
        byte[] json = localeAsJson(languages.forLocale(served, canonical));
        long encodedAt = timing ? System.nanoTime() : 0L;
        byte[] packed = SyncWire.pack(json);
        if (timing) {
            WIRE_ENCODE_NANOS += encodedAt - started;
            WIRE_DEFLATE_NANOS += System.nanoTime() - encodedAt;
        }

        List<byte[]> parts = SyncWire.chunk(packed);
        LOCALE_CHUNKS.put(served, parts);
        if (!served.isEmpty()) {
            Constants.LOG.debug("tenet: packed the {} locale for {} key(s)", served, countKeys(json));
        }
        return parts;
    }

    /** One locale's entries as the flat JSON object the client reads. */
    private static byte[] localeAsJson(Map<String, String> entries) {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, String> each : entries.entrySet()) {
            root.addProperty(each.getKey(), each.getValue());
        }
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** How many entries a packed locale holds, for the one debug line. Cheap, and only on a miss. */
    private static int countKeys(byte[] json) {
        return JsonParser.parseString(new String(json, StandardCharsets.UTF_8))
                .getAsJsonObject().size();
    }

    /** One flush's broadcast cost, summed over its recipients. See {@link #drainWireTiming}. */
    public record WireTiming(long encodeNanos, long deflateNanos) {
    }

    /** Accumulated by {@link #sendTreeTo} while {@link EditPhases#on()}, and read once per flush. */
    private static long WIRE_ENCODE_NANOS;
    private static long WIRE_DEFLATE_NANOS;

    /**
     * The broadcast cost since the last drain, and a reset.
     *
     * <p>Drained rather than read, because the numbers belong to <b>one flush</b>: they are summed over
     * that flush's recipients and then reported, and a counter that kept accumulating would report the
     * cost of every broadcast the session had ever made under the name of the latest one.
     *
     * <p>Server-thread only, like every other field on this path — see the class note on {@code SENT} for
     * why that is the whole of the synchronisation story here.
     */
    public static WireTiming drainWireTiming() {
        WireTiming taken = new WireTiming(WIRE_ENCODE_NANOS, WIRE_DEFLATE_NANOS);
        WIRE_ENCODE_NANOS = 0L;
        WIRE_DEFLATE_NANOS = 0L;
        return taken;
    }

    /**
     * Sends one player their team's current progress — a full sync or a delta, as appropriate.
     *
     * <p>The single place progress is pushed from, so every caller gets the same resolution, the same
     * chunking and the same snapshot bookkeeping. Called on join, on a change, on a reload, and when a
     * party changes — all of which want the same thing with a different reason.
     *
     * <h2>When it sends a full sync rather than a delta</h2>
     *
     * <p>Three cases, and each one is a case where a delta would be applied onto nothing:
     *
     * <ul>
     *   <li><b>No snapshot.</b> Nothing has been sent to this player since they joined, so there is
     *       nothing for a delta to be relative to.</li>
     *   <li><b>The reason says so.</b> A join or a reload is a request for the whole thing — the
     *       client has cleared, or the tree under it has changed.</li>
     *   <li><b>The team changed.</b> A player who left a party has a client holding the previous
     *       team's progress. A delta would merge one party's questline onto another's, which produces
     *       a cache that is a mixture of two and looks like neither.</li>
     * </ul>
     *
     * <p>The team check is why the snapshot stores the team id rather than only the quests: a party
     * change is not an event this class is told about, and it does not need to be, because the next
     * call can see it by comparison.
     */
    public static void sendProgress(MinecraftServer server, ServerPlayer player, int reason) {
        QuestIndex index = TenetQuests.index();
        if (index.isEmpty()) {
            return;
        }

        UUID owner = ProgressService.progressOwner(server, player);
        long gameTime = server.overworld().getGameTime();
        Sent last = SENT.get(player.getUUID());

        boolean full = last == null
                || !last.teamId().equals(owner)
                || reason == ProgressSyncPayload.REASON_JOIN
                || reason == ProgressSyncPayload.REASON_RELOAD;

        TeamProgress progress = ProgressService.progressFor(server, owner);
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, progress, gameTime);

        Map<String, ProgressService.LockView> locks =
                ProgressService.lockView(server, index, player, owner);

        Delta delta = progressDelta(resolution, progress, index, full ? null : last.quests(),
                ProgressService.contributors(owner),
                // The quests this player's stage gate shuts: the one per-player fact the per-quest text
                // cannot carry, because the text is built from the team's progress. See progressDelta.
                ProgressService.stageLockedQuests(server, player, index),
                // And the rows this player's conditions shut, for the same reason.
                locks,
                // And how much the tasks that wait for a press are holding right now, since none of it
                // is recorded yet. See oneQuestAsJson.
                ProgressService.live(owner));
        SENT.put(player.getUUID(), new Sent(owner, delta.snapshot(), lockText(locks)));

        // Counted here rather than at the `send` calls below. One logical message can be several
        // chunks, and it is counted once for all of them: "how many times did the server have
        // something to say" is the question, and counting chunks would make a large pack look like a
        // chatty one.
        MESSAGES.merge(new Told(reason, player.getUUID()), 1, Integer::sum);

        byte[] packed = SyncWire.pack(delta.json());
        List<byte[]> parts = SyncWire.chunk(packed);
        int transferId = SyncWire.newTransferId();

        for (int i = 0; i < parts.size(); i++) {
            send(player, new ProgressSyncPayload(
                    owner,
                    gameTime,
                    new SyncChunk(transferId, i, parts.size(), full),
                    parts.get(i),
                    reason));
        }
    }

    /** How often the lock refresh runs, in server ticks: a second, which is as fresh as a gate needs to feel. */
    private static final int LOCK_REFRESH_TICKS = 20;

    /** The last server and game tick the lock refresh ran on. Server thread only, like {@code SENT}. */
    private static MinecraftServer lastLockServer;
    private static Long lastLockTick;

    /**
     * Re-asks every online player's conditions, and pushes only the players whose picture changed.
     *
     * <h2>Why the send-time overlay is not enough on its own</h2>
     *
     * <p>The stage gate's lock is computed when progress is sent, and that is enough for a gate whose
     * input a command changes — the command sends. A condition's inputs are the world: an inventory, a
     * score, an advancement, who is online. Picking up the last log unlocks a row with no progress
     * event to carry the news, so a picture computed only at send time would go on saying locked until
     * something unrelated happened.
     *
     * <p>So the tick re-asks, once a second, and sends only when the canonical picture differs from
     * what this player was last sent — a picture that has not changed costs nothing on the wire, and
     * the idle-tick invariant survives. The whole pass is skipped when no quest declares a condition,
     * which is what keeps it free for the packs that do not use them.
     *
     * <p>Called from the player-tick hook, which runs per player per tick, so the guard is a
     * comparison against the game time rather than a counter — a counter would tick twice as fast with
     * two players online. A clock that has gone backwards is due, the same rule {@code isDue} follows
     * for the same reason: game time is per-world while this state lives for the life of the process.
     */
    public static void refreshLocks(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        if (server == lastLockServer && lastLockTick != null
                && now - lastLockTick < LOCK_REFRESH_TICKS && now >= lastLockTick) {
            return;
        }
        lastLockServer = server;
        lastLockTick = now;

        QuestIndex index = TenetQuests.index();
        if (index.isEmpty() || !ProgressService.hasConditions(index)) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID owner = ProgressService.progressOwner(server, player);
            String picture = lockText(ProgressService.lockView(server, index, player, owner));
            Sent sent = SENT.get(player.getUUID());
            if (sent != null && picture.equals(sent.locks())) {
                continue;
            }
            sendProgress(server, player, ProgressSyncPayload.REASON_CHANGED);
        }
    }

    /**
     * The canonical text of a lock picture.
     *
     * <p>What the refresh compares, so equal pictures must produce equal strings: the map is sorted and
     * every list in it is ascending by construction. This is the same property the per-quest delta text
     * rests on, and its failure looks the same from the outside — a quest resent forever.
     */
    private static String lockText(Map<String, ProgressService.LockView> locks) {
        return new java.util.TreeMap<>(locks).toString();
    }

    /** A JSON array of integers, for the sparse lock fields. */
    private static JsonArray ints(List<Integer> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    /**
     * Sends the tree and this player's progress together — what a join needs.
     *
     * <p>The snapshot is forgotten first, which is what makes a rejoin a full sync. Without it, a
     * player who disconnects and reconnects with the client having cleared its cache would be sent a
     * delta against progress it no longer has — and the result is a book showing a handful of quests
     * and every other one locked, which looks exactly like a working sync of a very small pack.
     */
    public static void sendEverythingTo(ServerPlayer player, MinecraftServer server) {
        QuestIndex index = TenetQuests.index();
        forget(player.getUUID());

        // The player's language goes first, and that ordering is the whole of the fix for a visible
        // seam: the play channel is ordered per connection, so a locale that is sent first is a locale
        // the client already holds when the tree below it is parsed, and the first frame draws the
        // player's own language rather than the canonical one. Correctness does not rest on it -- text
        // is resolved when it is drawn, so the other order is right one frame later -- but a book that
        // flashes English before settling is a thing somebody reports as a bug.
        sendLocaleTo(player);

        // The tree is sent even when it is empty, so the client can tell "nothing loaded" from
        // "nothing received" and say the right one of those to a player.
        sendTreeTo(player, index);
        if (!index.isEmpty()) {
            sendProgress(server, player, ProgressSyncPayload.REASON_JOIN);
        }
    }

    /** Forgets what a player was last sent, so their next sync is a full one. */
    public static void forget(UUID player) {
        SENT.remove(player);
    }

    /**
     * Forgets every snapshot, so the next sync to anybody is a full one.
     *
     * <p><b>Nothing calls this, and the reload path does not need it.</b> A reload is
     * {@code REASON_RELOAD}, and {@link #sendProgress} sends a full sync for that reason on its own —
     * so a caller that forgot every snapshot first would change nothing, and it would also forget the
     * snapshots of players the reload is not about. It is kept because forcing a full sync for a
     * player who never disconnected is exactly what it does and the next reason to want that will not
     * be a reload; the reader who finds it dead should feel free to delete it.
     */
    public static void forgetAll() {
        SENT.clear();
    }

    /** How many progress messages have been produced for one reason, to anybody. Diagnostics. */
    public static int messagesSent(int reason) {
        int total = 0;
        for (Map.Entry<Told, Integer> each : MESSAGES.entrySet()) {
            if (each.getKey().reason() == reason) {
                total += each.getValue();
            }
        }
        return total;
    }

    /** How many progress messages one player has been sent for one reason. Diagnostics. */
    public static int messagesSentTo(int reason, UUID player) {
        return MESSAGES.getOrDefault(new Told(reason, player), 0);
    }

    /** How many progress messages have been produced in total, whatever the reason. */
    public static int messagesSent() {
        int total = 0;
        for (int count : MESSAGES.values()) {
            total += count;
        }
        return total;
    }

    /** Sends progress to every member of a team, for a change one of them caused. */
    public static void sendProgressToTeam(MinecraftServer server,
                                          Collection<ServerPlayer> members,
                                          int reason) {
        for (ServerPlayer member : members) {
            sendProgress(server, member, reason);
        }
    }

    /**
     * Sends one payload, tolerating a player who has gone.
     *
     * <p>The check is not defensive noise. A sync is produced by several paths that run on a tick —
     * the engine reporting a change, a team membership change, a command — and a player can disconnect
     * between the moment a change is noticed and the moment it is sent. Sending to a disconnected
     * player is a no-op on Fabric and throws on NeoForge, which is the worst combination: it works on
     * one loader and breaks the other, from the same source file.
     */
    private static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection == null || player.hasDisconnected()) {
            return;
        }
        dev.ellipog.armature.api.net.ArmatureNetwork.sendToPlayer(player, payload);
    }
}
