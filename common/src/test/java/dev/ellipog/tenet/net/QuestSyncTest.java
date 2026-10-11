package dev.ellipog.tenet.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ellipog.tenet.client.ClientLocale;
import dev.ellipog.tenet.client.ClientQuestCache;
import dev.ellipog.tenet.progress.ProgressService;
import dev.ellipog.tenet.progress.ProgressionEngine;
import dev.ellipog.tenet.progress.QuestProgress;
import dev.ellipog.tenet.progress.QuestState;
import dev.ellipog.tenet.progress.TeamProgress;
import dev.ellipog.tenet.quest.CanvasElement;
import dev.ellipog.tenet.quest.DependencyStyle;
import dev.ellipog.tenet.quest.Fixtures;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.Quest;
import dev.ellipog.tenet.quest.QuestIndex;
import dev.ellipog.tenet.quest.QuestLoader;
import dev.ellipog.tenet.quest.QuestShape;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.ellipog.tenet.quest.Fixtures.q;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server's writer against the client's reader.
 *
 * <h2>The bug this exists to catch</h2>
 *
 * <p>{@link QuestSync} writes JSON by hand and {@link ClientQuestCache} reads it by hand, and the two
 * never meet in the compiler. So if the writer emits {@code "chapterTitle"} and the reader asks for
 * {@code "chapter_title"}, both sides compile, the packet sends, nothing throws, and every chapter in
 * the quest book is titled with an empty string. There is no error to find — only a screen that looks
 * wrong in a way nobody can trace back to a string.
 *
 * <p>These tests do the round trip in one process: build a real {@link QuestIndex}, serialise it the
 * way the server does, hand the bytes to the client cache exactly as the payload handler would, and
 * read the values back. A mismatch on either side fails here, in the build, naming the field.
 *
 * <h2>Why it needs vanilla bootstrapped</h2>
 *
 * <p>Because the client resolves item ids to stacks as the tree arrives, so that a screen does not do
 * a registry lookup per node per frame. That resolution reads {@code BuiltInRegistries.ITEM}, and an
 * unbootstrapped registry does not merely return nothing — {@code BuiltInRegistries}'s own class
 * initialiser throws, from inside a vanilla class, with a message about a registry that has nothing
 * to do with this test. So it is bootstrapped first, exactly as a real client is by the time any
 * packet arrives.
 */
@DisplayName("QuestSync round trip")
class QuestSyncTest {

    /** An arbitrary server tick, for cooldown arithmetic. */
    private static final long NOW = 10_000L;

    /** An arbitrary client tick, deliberately different from the server's. */
    private static final long CLIENT_TICK = 1_000L;

    @BeforeAll
    static void bootstrapMinecraft() {
        // The tree parser resolves item ids against the item registry, so the registry has to exist.
        MinecraftTestBootstrap.boot();
    }

    @BeforeEach
    @AfterEach
    void clearCache() {
        // Both, so a failure in one test cannot leave entries for the next one to find and pass on.
        ClientQuestCache.clear();
        // And the overlay, for the same reason and with the same hazard: a locale left by a test that
        // translated a key would answer for the next test's conventional lookup, which would pass
        // without the wire having carried anything.
        ClientLocale.clear();
    }

    // ------------------------------------------------------------------
    // The tree
    // ------------------------------------------------------------------

    private static QuestIndex twoQuests() {
        return Fixtures.indexOf(Fixtures.file(
                q("punch_a_tree").build(),
                q("make_a_table").dependsOn("punch_a_tree").build()));
    }

    @Test
    @DisplayName("the raw JSON names the fields the reader looks for")
    void fieldNamesMatch() {
        // Named explicitly, because this is the contract that has no compiler behind it. If either
        // side renames one of these, this test says which, in one line, instead of leaving a blank
        // string somewhere on a screen.
        String json = new String(QuestSync.treeAsJson(twoQuests()), StandardCharsets.UTF_8);

        for (String key : List.of("\"version\"", "\"groups\"", "\"chapterGroupId\"", "\"quests\"",
                "\"chapterId\"", "\"chapterTitle\"", "\"chapterIcon\"", "\"id\"",
                "\"title\"", "\"icon\"", "\"x\"", "\"y\"", "\"size\"", "\"shape\"",
                "\"iconScale\"", "\"showTitle\"", "\"invisible\"", "\"chapterLinear\"", "\"order\"",
                "\"description\"", "\"dependsOn\"", "\"tasks\"", "\"rewards\"")) {
            assertTrue(json.contains(key), "the tree JSON has no " + key + " field");
        }
    }

    @Test
    @DisplayName("the icon scale and the name flag arrive, since the validator accepts both")
    void appearanceFieldsArrive() {
        // The same defect as `shape`, which is why this test exists in the same shape as that one: a
        // field the format accepts, the validator checks and `/tenet` prints -- and the wire never
        // carries. It reads as supported and does nothing, which is worse than a field that is absent,
        // because an author would look for the bug in the drawing code.
        //
        // Both of these are exactly that risk: they are presentation, so nothing on the server behaves
        // differently when they are wrong, and no test that runs on the server can notice.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                q("plain").build(),
                q("named").showTitle(true).iconScale(0.4).build()));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry plain = entryFor("plain");
        ClientQuestCache.Entry named = entryFor("named");

        assertEquals(0.75, plain.iconScale(), 1.0E-9,
                "a quest with no iconScale in its file should arrive at the default");
        assertFalse(plain.showTitle(),
                "a quest with no showTitle in its file should arrive with the name NOT drawn");

        assertEquals(0.4, named.iconScale(), 1.0E-9, "an explicit icon scale should survive the wire");
        assertTrue(named.showTitle(), "and an explicit showTitle should survive it too");

        String json = new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"showTitle\":true"), "showTitle should travel as a boolean");
    }

    @Test
    @DisplayName("an optional quest says so on the wire, and a mandatory one sends nothing")
    void optionalFlagArrives() {
        // Sparse, like the other rule flags: absent means the quest gates its dependants, which is
        // every quest but the side branches. The client needs it for the same counts the engine
        // keeps, so the card's "2 of 3 met" and the unlock cannot disagree.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                q("plain").build(),
                q("side").optional(true).build()));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertFalse(entryFor("plain").optional(),
                "a quest with no optional in its file should arrive gating its dependants");
        assertTrue(entryFor("side").optional(), "an explicit optional should survive the wire");

        String json = new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"optional\":true"), "optional should travel as a boolean");
    }

    @Test
    @DisplayName("the flexible flag arrives resolved, and absent means strict")
    void flexibleFlagArrives() {
        // Sparse, like optional: absent means the quest measures only while playable. The
        // client needs the resolved answer — its own flag or its chapter's default — because
        // it holds no chapter record, and without it a flexible quest with unmet dependencies
        // reads UNLOCKED and whole flexible chapters look open.
        QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapter(
                "\"defaultFlexibleProgress\": true,",
                q("chapter_flexible").build(),
                q("own_flexible").flexibleProgress(true).build()));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertTrue(entryFor("chapter_flexible").flexible(),
                "the chapter default should resolve onto the quest");
        assertTrue(entryFor("own_flexible").flexible(), "an explicit flag should survive the wire");

        QuestIndex strict = Fixtures.indexOf(Fixtures.file(q("plain").build()));
        ClientQuestCache.acceptTree(strict.questCount(), strict.chapterCount(),
                QuestSync.treeAsJson(strict));
        assertFalse(entryFor("plain").flexible(), "absent means strict");

        String json = new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"flexible\":true"), "flexible should travel as a boolean");
    }

    @Test
    @DisplayName("a quest's presentation crosses the wire, with the width resolved against the chapter")
    void presentationFlagsArrive() {
        // The width travels resolved: the quest's own wins, else the chapter's default, else 0 for the
        // kind's default. The edge flag and the toast flag are the quest's own; absence is the ordinary
        // case and means drawn edges and announced completion.
        QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapter("\"defaultMinWidth\": 350,",
                """
                        {"id": "plain", "title": "Plain"}""",
                """
                        {"id": "wide", "title": "Wide", "minWidth": 250,
                         "hideDependentLines": true, "disableToast": true}""",
                """
                        {"id": "chapter_wide", "title": "Chapter Wide"}"""));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertEquals(250, entryFor("wide").minWidth(), "the quest's own width wins");
        assertEquals(350, entryFor("chapter_wide").minWidth(), "else the chapter's default");
        assertEquals(350, entryFor("plain").minWidth(), "a quest with no width reads the chapter's");
        assertTrue(entryFor("wide").hideDependentLines(), "the outgoing edge flag survives the wire");
        assertFalse(entryFor("plain").hideDependentLines(), "absent means drawn");
        assertTrue(entryFor("wide").disableToast(), "the quiet flag survives the wire");
        assertFalse(entryFor("plain").disableToast(), "absent means announced");

        // And a chapter that says nothing leaves the kind to decide: zero, which is what a
        // version-16 server always said by sending nothing at all.
        QuestIndex bare = Fixtures.indexOf(Fixtures.file(
                """
                        {"id": "bare", "title": "Bare"}"""));
        ClientQuestCache.acceptTree(bare.questCount(), bare.chapterCount(), QuestSync.treeAsJson(bare));
        assertEquals(0, entryFor("bare").minWidth(), "unset means the panel kind decides");

        String json = new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"hideDependentLines\":true"), "the edge flag travels as a boolean");
        assertTrue(json.contains("\"disableToast\":true"), "the quiet flag travels as a boolean");
    }

    @Test
    @DisplayName("a quest hidden from recipe viewers says so on the wire, and a listed one sends nothing")
    void hideFromViewersArrives() {
        // Sparse, like the other presentation flags: absent means shown, which is every quest a
        // version-19 server ever sent. The value is already resolved against the file's default —
        // the client holds no file record, so the wire carries the answer rather than the question.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                """
                        {"id": "plain", "title": "Plain"}""",
                """
                        {"id": "hidden", "title": "Hidden", "disableRecipeMod": true}""",
                """
                        {"id": "optout", "title": "Opt Out", "disableRecipeMod": false}"""));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertFalse(entryFor("plain").hideFromViewers(), "a quest that says nothing is listed");
        assertTrue(entryFor("hidden").hideFromViewers(), "an explicit hide survives the wire");
        assertFalse(entryFor("optout").hideFromViewers(), "an explicit show survives the wire");

        String json = new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"hideFromViewers\":true"), "the hidden flag travels as a boolean");
        assertFalse(json.contains("\"disableRecipeMod\""),
                "the tristate itself never crosses: only the resolved answer does");
    }

    @Test
    @DisplayName("a quest hiding its lock mark says so on the wire, and a quiet one sends nothing (T26)")
    void hideLockIconArrives() {
        // Sparse and unversioned, like the viewer flag above: absent means the quest does not opt
        // out, and whether a mark draws is the file's answer on the root beside it; either
        // silence wins, and the canvas reads both.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                """
                        {"id": "plain", "title": "Plain"}""",
                """
                        {"id": "quiet", "title": "Quiet", "hideLockIcon": true}"""));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertFalse(entryFor("plain").hideLockIcon(), "a quest that says nothing does not opt out");
        assertTrue(entryFor("quiet").hideLockIcon(), "an explicit hide survives the wire");

        String json = new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"hideLockIcon\":true"), "the hidden flag travels as a boolean");
    }

    @Test
    @DisplayName("the file's behaviour answers cross on the root, sparse and unversioned (T26)")
    void fileBehaviourArrives() {
        // The test settings are the defaults — the tree writer reads the live file, which no test
        // replaces — so absence is the whole assertion: a pack that never heard of these fields
        // sends exactly the bytes it sent before, and the client draws the defaults.
        QuestIndex index = twoQuests();
        String json = new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8);
        for (String key : List.of("\"showLockIcons\"", "\"hideExcludedQuests\"", "\"pauseGame\"",
                "\"disableGui\"", "\"lockMessage\"", "\"disableCanvasLod\"")) {
            assertFalse(json.contains(key), "a defaulting pack sends no " + key);
        }
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
        assertFalse(ClientQuestCache.showLockIcons(), "absent hides every mark");
        assertFalse(ClientQuestCache.hideExcludedQuests(), "absent hides nothing");
        assertFalse(ClientQuestCache.pauseGame(), "absent keeps the world ticking");
        assertFalse(ClientQuestCache.guiDisabled(), "absent opens the book");
        assertFalse(ClientQuestCache.bookLodDisabled(), "absent tiers the canvas");
        assertTrue(ClientQuestCache.lockMessage().isEmpty(), "absent keeps the client's own word");

        // And a server that sets them is read the same way: the root carries the keys, and the
        // cache answers with them. Written by hand onto the writer's own bytes, because the file
        // the writer reads is the live one — the keys' presence is the fact, exactly as it is for
        // every other sparse field on this wire.
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        root.addProperty("showLockIcons", true);
        root.addProperty("hideExcludedQuests", true);
        root.addProperty("pauseGame", true);
        root.addProperty("disableGui", true);
        root.addProperty("lockMessage", "Sealed");
        root.addProperty("disableCanvasLod", true);
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(),
                root.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(ClientQuestCache.showLockIcons(), "the file shows every mark");
        assertTrue(ClientQuestCache.hideExcludedQuests(), "the file hides the shut-out");
        assertTrue(ClientQuestCache.pauseGame(), "the file stills the world");
        assertTrue(ClientQuestCache.guiDisabled(), "the file closes the book");
        assertTrue(ClientQuestCache.bookLodDisabled(), "the file tiers nothing");
        assertEquals("Sealed", ClientQuestCache.lockMessage(), "the author's own locked word");
    }

    @Test
    @DisplayName("an excluded quest is marked on the progress wire, and a merely locked one is not (T26)")
    void excludedMarkArrives() {
        // The mark is the reason, not the state: LOCKED is also what "not yet unlocked" looks
        // like, so the reader that hides excluded quests reads this rather than the state.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                q("sword").exclusiveGroup("spec").build(),
                q("pick").exclusiveGroup("spec").build(),
                q("late").dependsOn("sword").build()));
        TeamProgress progress = progressWith(index, "sword",
                QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(true));
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, progress, NOW);
        byte[] bytes = QuestSync.progressAsJson(resolution, progress, index);
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"excluded\":true"), "the shut-out travels as a boolean");

        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW, bytes, CLIENT_TICK);
        assertTrue(ClientQuestCache.excludedOf("pick"), "the sibling the choice shut out");
        assertFalse(ClientQuestCache.excludedOf("sword"), "not the quest that took the group");
        assertFalse(ClientQuestCache.excludedOf("late"),
                "and not a quest that is merely not yet unlocked");
    }

    @Test
    @DisplayName("a quiet task and a quiet reward say so on the wire, and loud ones send nothing")
    void quietTasksAndRewardsArrive() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                """
                        {"id": "q", "title": "Q",
                         "tasks": [{"type": "tenet:checkmark", "title": "loud"},
                                   {"type": "tenet:checkmark", "title": "quiet", "disableToast": true}],
                         "rewards": [{"type": "tenet:xp", "amount": 1},
                                     {"type": "tenet:xp", "amount": 2, "disableToast": true}]}"""));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry entry = entryFor("q");
        assertFalse(entry.tasks().get(0).disableToast(), "a task that says nothing is announced");
        assertTrue(entry.tasks().get(1).disableToast(), "a quiet task survives the wire");
        assertFalse(entry.rewards().get(0).disableToast(), "a reward that says nothing is announced");
        assertTrue(entry.rewards().get(1).disableToast(), "a quiet reward survives the wire");
    }

    @Test
    @DisplayName("a chapter's autofocus crosses the wire, and a chapter without one sends nothing")
    void autofocusArrives() {
        QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapters(
                Fixtures.chapterWith("first", "\"autofocus\": \"one\",",
                        Fixtures.q("one").build()),
                Fixtures.chapter("second", Fixtures.q("two").build())));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.ChapterEntry first = ClientQuestCache.chapters().stream()
                .filter(chapter -> chapter.id().equals("first")).findFirst().orElseThrow();
        ClientQuestCache.ChapterEntry second = ClientQuestCache.chapters().stream()
                .filter(chapter -> chapter.id().equals("second")).findFirst().orElseThrow();
        assertEquals("one", first.autofocus(), "the focus quest survives the wire");
        assertTrue(second.autofocus().isEmpty(), "absent means the bounding-box centre");

        String json = new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"autofocus\":\"one\""), "autofocus should travel as a name");
    }

    @Test
    @DisplayName("a quest's texture, sprite and entity icons cross the wire with their kind beside them")
    void iconKindsArrive() {
        // Absent kind means the item arm, which is every icon a version-17 tree ever sent. A texture
        // travels as its path with kind "texture"; a sprite as its id with kind "sprite"; an entity
        // as its id with kind "entity", resolved on arrival to its egg.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                """
                        {"id": "plain", "title": "Plain"}""",
                """
                        {"id": "pictured", "title": "Pictured",
                         "icon": {"texture": "my_pack:textures/gui/emblem.png"}}""",
                """
                        {"id": "region", "title": "Region",
                         "icon": {"sprite": "occultism:block/chalk_glyph/0"}}""",
                """
                        {"id": "creeper", "title": "Creeper",
                         "icon": {"entity": "minecraft:creeper"}}"""));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertTrue(entryFor("plain").textureIcon().isEmpty(), "an item icon carries no texture");
        assertEquals("my_pack:textures/gui/emblem.png", entryFor("pictured").textureIcon(),
                "the texture path survives the wire");
        assertTrue(entryFor("pictured").icon().isEmpty(), "and no stack pretends to be it");
        assertTrue(entryFor("pictured").iconId().isEmpty(),
                "so no missing-item branch fires for a picture");
        assertEquals("occultism:block/chalk_glyph/0", entryFor("region").spriteIcon(),
                "the sprite id survives the wire beside the kind");
        assertTrue(entryFor("region").icon().isEmpty(), "and no stack pretends to be it either");
        assertTrue(entryFor("region").textureIcon().isEmpty(), "nor the other picture arm");
        assertFalse(entryFor("creeper").icon().isEmpty(),
                "a creeper draws its egg: " + entryFor("creeper").iconId());
        assertEquals("minecraft:creeper", entryFor("creeper").iconId());

        String json = new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"iconKind\":\"texture\""), "the texture kind travels beside its path");
        assertTrue(json.contains("\"iconKind\":\"sprite\""), "and the sprite kind beside its id");
        assertTrue(json.contains("\"iconKind\":\"entity\""), "and the entity kind beside its id");
    }

    @Test
    @DisplayName("an entity with no egg arrives as a named missing icon rather than nothing")
    void egglessEntityArrivesNamed() {
        // A modded entity with no egg under the vanilla naming convention: nothing to draw, but the
        // id is kept, so the node says what is missing rather than drawing nothing.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                """
                        {"id": "odd", "title": "Odd",
                         "icon": {"entity": "no_such_mod:some_mob"}}"""));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertTrue(entryFor("odd").icon().isEmpty(), "no egg draws nothing");
        assertEquals("no_such_mod:some_mob", entryFor("odd").iconId(),
                "but the id is kept, so the node says what is missing");
    }

    @Test
    @DisplayName("an author's texture on a task and a reward crosses the wire beside the stack")
    void authorTexturesArrive() {
        // The texture path travels as its own key; the stack stays the requirement, so no missing
        // branch fires for a picture. An author item or egg travels in `picture` beside it since
        // version 21 — see authorPicturesArrive — and this pins the texture half.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                """
                        {"id": "q", "title": "Q",
                         "tasks": [{"type": "tenet:checkmark", "title": "a"},
                                   {"type": "tenet:checkmark", "title": "b",
                                    "icon": {"texture": "my_pack:textures/gui/emblem.png"}}],
                         "rewards": [{"type": "tenet:xp", "amount": 1},
                                     {"type": "tenet:xp", "amount": 2,
                                      "icon": {"texture": "my_pack:textures/gui/emblem.png"}}]}"""));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry entry = entryFor("q");
        assertTrue(entry.tasks().get(0).textureIcon().isEmpty(), "a task that says nothing sends none");
        assertEquals("my_pack:textures/gui/emblem.png", entry.tasks().get(1).textureIcon());
        assertTrue(entry.rewards().get(0).textureIcon().isEmpty());
        assertEquals("my_pack:textures/gui/emblem.png", entry.rewards().get(1).textureIcon());

        String json = new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"textureIcon\":\"my_pack:textures/gui/emblem.png\""),
                "the texture path travels as its own key");
    }

    @Test
    @DisplayName("an author's sprite on a task and a reward crosses the wire beside the stack")
    void authorSpritesArrive() {
        // The sprite id travels as its own key, like the texture path beside it; the stack stays
        // the requirement, so no missing branch fires for a picture.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                """
                        {"id": "q", "title": "Q",
                         "tasks": [{"type": "tenet:checkmark", "title": "a"},
                                   {"type": "tenet:checkmark", "title": "b",
                                    "icon": {"sprite": "occultism:block/chalk_glyph/0"}}],
                         "rewards": [{"type": "tenet:xp", "amount": 1},
                                     {"type": "tenet:xp", "amount": 2,
                                      "icon": {"sprite": "occultism:block/chalk_glyph/0"}}]}"""));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry entry = entryFor("q");
        assertTrue(entry.tasks().get(0).spriteIcon().isEmpty(), "a task that says nothing sends none");
        assertEquals("occultism:block/chalk_glyph/0", entry.tasks().get(1).spriteIcon());
        assertTrue(entry.tasks().get(1).textureIcon().isEmpty(), "nor the other picture arm");
        assertTrue(entry.rewards().get(0).spriteIcon().isEmpty());
        assertEquals("occultism:block/chalk_glyph/0", entry.rewards().get(1).spriteIcon());

        String json = new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"spriteIcon\":\"occultism:block/chalk_glyph/0\""),
                "the sprite id travels as its own key");
    }

    @Test
    @DisplayName("an author's item picture crosses beside the requirement, which keeps the recipes")
    void authorPicturesArrive() {
        // A tag task wearing an oak log still needs the tag: the requirement stays in `item`/`tag`
        // and the picture travels in `picture`. Before version 21 the picture rode in the item, so
        // the row opened oak-log recipes instead of the tag.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                """
                        {"id": "q", "title": "Q",
                         "tasks": [{"type": "tenet:item_tag", "tag": "minecraft:logs", "count": 4,
                                    "icon": {"item": "minecraft:oak_log"}},
                                   {"type": "tenet:item", "item": "minecraft:stone", "count": 8,
                                    "icon": {"item": "minecraft:diamond"}},
                                   {"type": "tenet:checkmark", "title": "a",
                                    "icon": {"item": "minecraft:torch"}}]}"""));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry entry = entryFor("q");
        ClientQuestCache.TaskEntry tag = entry.tasks().get(0);
        assertEquals("minecraft:logs", tag.tagId(), "the tag still travels");
        assertFalse(tag.hasItem(), "and no stack pretends to be it");
        assertFalse(tag.picture().isEmpty(), "while the picture travels beside it");

        ClientQuestCache.TaskEntry item = entry.tasks().get(1);
        assertTrue(item.hasItem(), "an item task still needs its item");
        assertFalse(item.picture().isEmpty(), "and draws the picture");

        ClientQuestCache.TaskEntry check = entry.tasks().get(2);
        assertFalse(check.hasItem(), "a checkmark needs nothing, picture or not");
        assertFalse(check.picture().isEmpty());

        String json = new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"picture\":\"minecraft:oak_log\""),
                "the picture travels as its own key");
    }

    @Test
    @DisplayName("an out-of-range icon scale from a server is clamped rather than drawn")
    void anOutOfRangeIconScaleIsClamped() {
        // The codec already bounds this on the *server*, over that server's files. What arrives is a
        // number from possibly a different version, and the screen must not draw outside its node
        // because of one -- the same reasoning as QuestShape.span clamping its own output.
        String handWritten = "{\"version\":1,\"quests\":[{\"chapterId\":\"c\",\"chapterTitle\":\"C\","
                + "\"id\":\"wild\",\"title\":\"Wild\",\"icon\":\"minecraft:stone\",\"x\":0,\"y\":0,"
                + "\"size\":48,\"shape\":\"rounded\",\"iconScale\":99.0,\"showTitle\":false,"
                + "\"description\":[],\"dependsOn\":[],\"tasks\":[],\"rewards\":[]},"
                + "{\"chapterId\":\"c\",\"chapterTitle\":\"C\",\"id\":\"none\",\"title\":\"None\","
                + "\"icon\":\"minecraft:stone\",\"x\":64,\"y\":0,\"size\":48,\"shape\":\"rounded\","
                + "\"iconScale\":-5.0,\"showTitle\":false,\"description\":[],\"dependsOn\":[],"
                + "\"tasks\":[],\"rewards\":[]}]}";
        ClientQuestCache.acceptTree(2, 1, handWritten.getBytes(StandardCharsets.UTF_8));

        assertEquals(QuestShape.MAX_ICON_SCALE, entryFor("wild").iconScale(), 1.0E-9);
        assertEquals(QuestShape.MIN_ICON_SCALE, entryFor("none").iconScale(), 1.0E-9);
    }

    @Test
    @DisplayName("an item's custom data crosses the wire and arrives on the icon's stack")
    void itemComponentsArrive() {
        // The wire sends the patch beside the id, in the file's own spelling, through the same codec
        // `ItemRef` uses. This is the read half; without it, a renamed item's name would be a field
        // the server sends and the client never applies -- the failure mode this whole test class is
        // about.
        String handWritten = "{\"version\":1,\"quests\":[{\"chapterId\":\"c\",\"chapterTitle\":\"C\","
                + "\"id\":\"battered\",\"title\":\"Battered\",\"icon\":\"minecraft:diamond_sword\","
                + "\"iconComponents\":{\"minecraft:damage\":5},"
                + "\"x\":0,\"y\":0,\"size\":48,\"shape\":\"rounded\",\"iconScale\":1.0,"
                + "\"showTitle\":false,\"description\":[],\"dependsOn\":[],\"tasks\":[],\"rewards\":[]}]}";
        ClientQuestCache.acceptTree(1, 1, handWritten.getBytes(StandardCharsets.UTF_8));

        assertEquals(5, entryFor("battered").icon().getDamageValue(),
                "the component arrived and was applied to the stack");
        assertEquals("minecraft:diamond_sword", entryFor("battered").iconId(),
                "and the id is kept beside it, which is what lets a missing item say so");
    }

    @Test
    @DisplayName("a chapter's icon and a group's icon both cross the wire")
    void chapterAndGroupIconsArrive() {
        // Two rows, two sources: a chapter row draws the chapter's own icon, which rides on every quest
        // of it -- the client has no chapter record to hang it on, the same shape `chapterTheme` has --
        // and a heading draws the group's, which is optional in the file. Absent means "fall back to the
        // first chapter", and that fallback is a client decision rather than a value the server invents.
        String file = """
                {
                  "version": 1,
                  "chapterGroups": [
                    {
                      "id": "group",
                      "title": "Group",
                      "icon": { "item": "minecraft:anvil" },
                      "chapters": [
                        {
                          "id": "chapter",
                          "title": "Chapter",
                          "icon": { "item": "minecraft:crafting_table" },
                          "quests": [ %s ]
                        }
                      ]
                    }
                  ]
                }
                """.formatted(q("punch_a_tree").build());
        QuestIndex index = Fixtures.indexOf(file);
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry entry = entryFor("punch_a_tree");
        assertEquals(Items.CRAFTING_TABLE, entry.chapterIcon().getItem(),
                "the chapter's icon arrived on its quest");
        assertEquals("minecraft:crafting_table", entry.chapterIconId(),
                "and the id is kept beside it, for the row that has no stack to draw");

        ClientQuestCache.GroupEntry group = ClientQuestCache.groups().get(0);
        assertEquals(Items.ANVIL, group.icon().getItem(), "the group's own icon arrived");
        assertEquals("minecraft:anvil", group.iconId());
    }

    @Test
    @DisplayName("a linear chapter arrives marked linear, with its quests in order")
    void aLinearChapterTravels() {
        // A linear chapter declares no dependencies at all -- the list order is the progression -- so
        // without these two fields the client draws a row of unconnected nodes for a chapter that is,
        // in fact, a road. The lines have to come from somewhere, and the server is the only thing that
        // knows.
        QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapter(
                "\"progressionMode\": \"linear\",",
                q("first").build(), q("second").build(), q("third").build()));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            assertTrue(entry.chapterLinear(),
                    entry.id() + " is in a linear chapter, but arrived as flexible");
        }
        assertEquals(0, entryFor("first").orderInChapter());
        assertEquals(1, entryFor("second").orderInChapter());
        assertEquals(2, entryFor("third").orderInChapter());

        // And the default is not linear, because a chapter that declares nothing must not be treated as
        // declaring an order -- FLEXIBLE is what the codec defaults to, and this is the same default
        // arriving at the client.
        QuestIndex flexible = Fixtures.indexOf(Fixtures.file(q("alone").build()));
        ClientQuestCache.acceptTree(flexible.questCount(), flexible.chapterCount(),
                QuestSync.treeAsJson(flexible));
        assertFalse(entryFor("alone").chapterLinear());
    }

    @Test
    @DisplayName("the node shape arrives, so a circle is drawn as a circle and not as a square")
    void shapeArrives() {
        // Added after the fact, and it is the test that would have caught the defect: `shape` was in
        // the quest file format, validated, printed by `/tenet` and requested by the shipped
        // questline -- while this method did not write it at all, so the client could not honour it
        // and drew every node as a square. Nothing failed. The field list above simply did not
        // mention it, and a list of names is only as good as the names on it.
        QuestIndex index = twoQuests();
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry first = ClientQuestCache.entries().get(0);
        assertEquals(QuestShape.ROUNDED, first.shape(),
                "a quest with no shape in the file should default to ROUNDED, not to null");

        // And a shape that is not the default survives, because a default that swallows everything is
        // indistinguishable from a field that never travels.
        String json = new String(QuestSync.treeAsJson(twoQuests()), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"shape\":\"rounded\""),
                "the shape should travel lowercase, spelled the way a quest file spells it");
    }

    @Test
    @DisplayName("a shape name this client does not know reads as ROUNDED rather than throwing")
    void unknownShapeFallsBack() {
        // A server running a newer version can name a shape this client has never heard of. The choice
        // is between a node drawn as the default and a screen that throws while a player stands in
        // front of it -- and this is a different decision from the validator's, where an unknown name
        // is an error because the author is still able to fix it.
        assertEquals(QuestShape.ROUNDED, QuestShape.byName("dodecahedron", QuestShape.ROUNDED));
        assertEquals(QuestShape.ROUNDED, QuestShape.byName("", QuestShape.ROUNDED));
        assertEquals(QuestShape.ROUNDED, QuestShape.byName(null, QuestShape.ROUNDED));
        // And the names that ARE known are still recognised, in any case, so the fallback does not
        // quietly swallow everything.
        assertEquals(QuestShape.CIRCLE, QuestShape.byName("circle", QuestShape.ROUNDED));
        assertEquals(QuestShape.HEXAGON, QuestShape.byName("Hexagon", QuestShape.ROUNDED));
        assertEquals(QuestShape.TOME, QuestShape.byName("TOME", QuestShape.ROUNDED));
    }

    @Test
    @DisplayName("the task and reward fields the reader looks for are all present")
    void taskAndRewardFieldNamesMatch() {
        String json = new String(QuestSync.treeAsJson(twoQuests()), StandardCharsets.UTF_8);

        for (String key : List.of("\"type\"", "\"item\"", "\"count\"", "\"label\"",
                "\"labelFallback\"", "\"labelArg\"", "\"optional\"", "\"manual\"")) {
            assertTrue(json.contains(key), "the task JSON has no " + key + " field");
        }
    }

    @Test
    @DisplayName("a quest survives the trip through the client's parser intact")
    void questFieldsArrive() {
        QuestIndex index = twoQuests();
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertTrue(ClientQuestCache.hasTree(), "the cache did not register a tree at all");
        assertTrue(ClientQuestCache.hasData());
        assertEquals(2, ClientQuestCache.questCount());

        ClientQuestCache.Entry first = ClientQuestCache.entries().get(0);
        assertEquals("chapter", first.chapterId());
        assertEquals("Chapter", first.chapterTitle(), "the chapter title did not cross the wire");
        assertEquals("punch_a_tree", first.id());
        assertEquals("punch_a_tree", first.title(), "the quest title did not cross the wire");
        assertEquals(1, first.tasks().size());
        assertEquals(0, first.rewards().size());
        assertEquals(List.of(), first.dependencies());
        assertFalse(first.invisible());
        assertEquals(48, first.size(), "the default node size did not cross the wire");
    }

    @Test
    @DisplayName("a dependency list arrives, rather than always being empty")
    void dependenciesArrive() {
        // The failure mode if "dependsOn" were misspelled is a dependency list that is always empty
        // -- and an empty list is what most quests have, so it would look correct.
        QuestIndex index = twoQuests();
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry second = ClientQuestCache.entries().get(1);
        assertEquals("make_a_table", second.id());
        assertEquals(List.of("punch_a_tree"), second.dependencies());
    }

    @Test
    @DisplayName("an invisible quest travels with its flag, so the client can hide it")
    void invisibleFlagArrives() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                q("seen").build(),
                q("secret").invisible(true).build()));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertFalse(entryFor("seen").invisible());
        assertTrue(entryFor("secret").invisible(),
                "the invisible flag did not cross the wire, so a hidden quest would be shown");
    }

    @Test
    @DisplayName("a quest with no icon sends none, so the client can adopt live")
    void defaultIconOmitted() {
        // The fixture default is ItemRef.DEFAULT_ICON, which is paper — but paper on the wire
        // would read as explicit and block the client's first-task adoption. So the default is
        // omitted; an explicitly authored icon still crosses.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(q("no_icon").build()));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry entry = entryFor("no_icon");
        assertTrue(entry.icon().isEmpty(), "the default icon should not cross the wire");
        assertEquals("", entry.iconId(), "no id was authored, so none should arrive");
    }

    @Test
    @DisplayName("a quest with no icon adopts its first task's item")
    void iconlessQuestAdoptsTaskItem() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\", \"count\": 8} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ItemStack adopted = ClientQuestCache.adoptedQuestIcon(entryFor("a"));
        assertFalse(adopted.isEmpty(), "the quest should wear its task's item");
        assertEquals(Items.OAK_LOG, adopted.getItem());
    }

    @Test
    @DisplayName("a quest that authored paper sends it, so the client draws paper")
    void authoredPaperCrosses() {
        // Paper is a valid icon, not an absence: the file below says paper the way any other
        // file says oak logs, and the wire must not swallow it back into the default.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"icon\": {\"item\": \"minecraft:paper\"}, \"tasks\": "
                        + "[ {\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\", \"count\": 8} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry entry = entryFor("a");
        assertEquals(Items.PAPER, entry.icon().getItem(),
                "authored paper is paper, not an absence the client would adopt over");
        assertEquals("minecraft:paper", entry.iconId(), "and the id crosses with it");
        assertTrue(ClientQuestCache.adoptedQuestIcon(entry).isEmpty(),
                "an explicit icon blocks adoption, even paper");
    }

    @Test
    @DisplayName("a quest with an explicit icon keeps it over adoption")
    void explicitIconBlocksAdoption() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"icon\": {\"item\": \"minecraft:diamond\"}, \"tasks\": "
                        + "[ {\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\", \"count\": 8} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry entry = entryFor("a");
        assertEquals(Items.DIAMOND, entry.icon().getItem(), "the explicit icon should cross the wire");
        assertTrue(ClientQuestCache.adoptedQuestIcon(entry).isEmpty(),
                "an explicit icon blocks adoption");
    }

    @Test
    @DisplayName("a kill task naming one mob arrives wearing its egg")
    void killTaskWearsItsEgg() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tenet:kill\", \"entity\": \"minecraft:creeper\", \"value\": 3} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.TaskEntry task = entryFor("a").tasks().get(0);
        assertFalse(task.hasItem(), "an egg is a picture, not a requirement");
        assertEquals("minecraft:creeper_spawn_egg", task.picture().getItem().toString(),
                "the row draws the mob, not the sword");
        assertEquals("minecraft:creeper_spawn_egg",
                ClientQuestCache.adoptedQuestIcon(entryFor("a")).getItem().toString(),
                "and the iconless quest adopts it");
    }

    @Test
    @DisplayName("a tagged or mob-less kill keeps the sword; an author picture still wins")
    void killEggLimits() {
        QuestIndex tagged = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tenet:kill\", \"entityTypeTag\": \"minecraft:skeletons\", \"value\": 3} ]}"));
        ClientQuestCache.acceptTree(tagged.questCount(), tagged.chapterCount(), QuestSync.treeAsJson(tagged));
        assertTrue(entryFor("a").tasks().get(0).picture().isEmpty(),
                "a tag names no one mob, so no egg");

        QuestIndex eggless = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tenet:kill\", \"entity\": \"no_such_mod:some_mob\", \"value\": 3} ]}"));
        ClientQuestCache.acceptTree(eggless.questCount(), eggless.chapterCount(), QuestSync.treeAsJson(eggless));
        assertTrue(entryFor("a").tasks().get(0).picture().isEmpty(),
                "an entity with no egg keeps the glyph");

        QuestIndex dressed = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tenet:kill\", \"entity\": \"minecraft:creeper\", \"value\": 3,"
                        + " \"icon\": {\"item\": \"minecraft:torch\"}} ]}"));
        ClientQuestCache.acceptTree(dressed.questCount(), dressed.chapterCount(), QuestSync.treeAsJson(dressed));
        assertEquals(Items.TORCH, entryFor("a").tasks().get(0).picture().getItem(),
                "an author picture wins over the egg");
    }

    @Test
    @DisplayName("a quest of bare checkmarks adopts the type glyph instead of paper")
    void checkmarkQuestAdoptsGlyph() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tenet:checkmark\", \"title\": \"Read the sign\"} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertEquals(Items.KNOWLEDGE_BOOK, ClientQuestCache.adoptedQuestIcon(entryFor("a")).getItem(),
                "a book says things to do; paper says nothing");
    }

    @Test
    @DisplayName("an item task arrives with its own item, count and no manual button")
    void itemTaskArrives() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\", \"count\": 8} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.TaskEntry task = entryFor("a").tasks().get(0);
        assertTrue(task.hasItem(), "an item task should carry its item");
        assertEquals(Items.OAK_LOG, task.item().getItem());
        assertEquals(8, task.count(), "the requirement did not cross the wire");
        assertFalse(task.optional());
        assertFalse(task.manual(),
                "a presence-only item task completes by itself, so it should have no submit button");
    }

    @Test
    @DisplayName("an item task that consumes does offer a manual button")
    void consumingItemTaskIsManual() {
        // The whole reason `manual` is on the wire: a task that takes your items is one a player may
        // want to hand over deliberately, and the button should only exist where it does something.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": [ {\"type\": \"tenet:item\", "
                        + "\"item\": \"minecraft:diamond\", \"count\": 1, \"consumeItems\": true} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertTrue(entryFor("a").tasks().get(0).manual());
    }

    @Test
    @DisplayName("a task that waits for the press is offered its button when the press would be accepted")
    void aWaitingTaskIsOfferedItsButtonWhenThePressWouldBeTaken() {
        // One item task that consumes eight diamonds: the press is the only way this can finish, so the
        // button has to exist -- and only then -- when the server would actually take the press. The
        // rule that used to live in the screen asked `progress < count`, which for this task is true
        // exactly while the press would be *refused* and false exactly when it would be accepted.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": [ {\"type\": \"tenet:item\", "
                        + "\"item\": \"minecraft:diamond\", \"count\": 8, \"consumeItems\": true} ]}"));

        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
        assertTrue(entryFor("a").tasks().get(0).waits(),
                "the row has to be told that the press is what finishes this task, not the tick");

        UUID team = UUID.randomUUID();

        // Four held: the count is not met, so a press would be refused and there must be no button.
        sendProgress(index, 0, 4, team);
        assertEquals(4, ClientQuestCache.taskProgressOf("a", 0),
                "the row draws what the player is holding, which is the whole point of the live count");
        assertEquals(-1, ClientQuestCache.firstSubmitTask("a"),
                "a button whose only effect is a refusal is worse than no button");

        // Eight held, nothing handed in: ready, and it is the one task the row offers.
        sendProgress(index, 0, 8, team);
        assertEquals(8, ClientQuestCache.taskProgressOf("a", 0), "at eight held the row reads 8 of 8");
        assertEquals(0, ClientQuestCache.firstSubmitTask("a"),
                "and that is the moment the press would be accepted, so the button appears");

        // Handed in: recorded eight, nothing held. The same 8 of 8 arrives, and there is no button.
        sendProgress(index, 8, 0, team);
        assertEquals(8, ClientQuestCache.taskProgressOf("a", 0),
                "which is why the display value alone cannot decide the button -- see taskReady");
        assertEquals(-1, ClientQuestCache.firstSubmitTask("a"),
                "a handed-in task offers nothing, whatever its count says");
        assertFalse(ClientQuestCache.taskReadyOf("a", 0), "and the server said so rather than the client");
    }

    @Test
    @DisplayName("a fluid row counts millibuckets like its progress does, not buckets")
    void fluidRowsCountMillibuckets() {
        // The unit-mismatch this exists for: the row, its bar, its done-check and the task-done
        // notice all divide progress by the tree's count, while progress travels in millibuckets.
        // A bucket count there read a half-held two thousand as "2 / 2" complete — full bar, green
        // row, checkmark and a task-done toast for one bucket.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": [ {\"type\": \"tenet:fluid\", "
                        + "\"fluid\": \"minecraft:water\", \"amount\": 2000} ]}"));

        String tree = new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8);
        assertTrue(tree.contains("\"count\":2000"),
                "the tree carries the millibucket requirement, not a bucket count");

        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
        assertEquals(2000, entryFor("a").tasks().get(0).count(),
                "and the row draws against the same unit the progress arrives in");

        UUID team = UUID.randomUUID();
        sendProgress(index, 0, 1000, team);
        assertEquals(1000, ClientQuestCache.taskProgressOf("a", 0),
                "one bucket held reads as one thousand, not two of anything");
        assertFalse(ClientQuestCache.taskDone("a", 0),
                "so a half-held task is not done, announces nothing and wears no checkmark");
        assertEquals(-1, ClientQuestCache.firstSubmitTask("a"),
                "and offers no press a short inventory would only refuse");
    }

    @Test
    @DisplayName("a manual-only task travels its flag and is offered its button with nothing held")
    void aManualTaskTravelsItsFlagAndOffersItsButton() {
        // The wire half of T21: the flag travels sparse — only when true — and the row's button
        // rule reads it rather than the live count, because the live count of a task the tick
        // never measures is zero by construction. Without the flag the button would hide forever
        // and the press would exist only as a slash command.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": [ {\"type\": \"tenet:item\", "
                        + "\"item\": \"minecraft:coal\", \"count\": 4, \"consumeItems\": false,"
                        + " \"manualOnly\": true} ]}"));
        String tree = new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8);
        assertTrue(tree.contains("\"manualOnly\":true"),
                "the flag travels on the tree, sparse like every other additive key");

        QuestIndex plain = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": [ {\"type\": \"tenet:item\", "
                        + "\"item\": \"minecraft:coal\", \"count\": 4, \"consumeItems\": false} ]}"));
        assertFalse(new String(QuestSync.treeAsJson(plain), StandardCharsets.UTF_8)
                        .contains("manualOnly"),
                "and absence means the tick measures, which is every task an older tree ever sent");

        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
        assertTrue(entryFor("a").tasks().get(0).manualOnly(),
                "the row is told the tick will never measure this task");
        assertTrue(entryFor("a").tasks().get(0).waits(),
                "and that the press is what finishes it");

        UUID team = UUID.randomUUID();
        sendProgress(index, 0, 0, team);
        assertEquals(0, ClientQuestCache.firstSubmitTask("a"),
                "nothing held and the button is still there: the press measures, so hiding it "
                        + "until the count is met would hide it forever");
    }

    /** One team's progress, with the server's live count for task 0 of the fixture's quest {@code a}. */
    private static void sendProgress(QuestIndex index, int recorded, int held, UUID team) {
        TeamProgress progress = recorded == 0
                ? TeamProgress.empty()
                : progressWith(index, "a", QuestProgress.NONE.recordTask(0, recorded));
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, progress, NOW);
        dev.ellipog.tenet.progress.ProgressService.Live live =
                (questId, taskIndex) -> questId.equals("a") && taskIndex == 0 ? held : 0;

        ClientQuestCache.acceptProgress(team, NOW,
                QuestSync.progressDelta(resolution, progress, index, null,
                        (questId, taskIndex) -> Map.of(), java.util.Set.of(), Map.of(), live).json(),
                CLIENT_TICK);
    }

    @Test
    @DisplayName("a locked quest offers no Submit button, while its open neighbour does")
    void lockedQuestOffersNoButton() {
        // The screenshot this exists for: a locked quest's checkmark read 0 of 1 with "hand in"
        // beside it, and the press was silently refused -- the server only accepts a playable
        // quest's press. The button rule is the count rule plus the playability rule, and the
        // lock is the half the count cannot see.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": [{\"type\": \"tenet:checkmark\","
                        + " \"title\": \"Go\"}]},"
                        + "{\"id\": \"b\", \"title\": \"b\", \"dependsOn\": [\"a\"],"
                        + " \"tasks\": [{\"type\": \"tenet:checkmark\", \"title\": \"Follow\"}]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
        UUID team = UUID.randomUUID();

        TeamProgress progress = TeamProgress.empty();
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, progress, NOW);
        dev.ellipog.tenet.progress.ProgressService.Live live = (questId, taskIndex) -> 0;
        ClientQuestCache.acceptProgress(team, NOW,
                QuestSync.progressDelta(resolution, progress, index, null,
                        (questId, taskIndex) -> Map.of(), java.util.Set.of(), Map.of(), live).json(),
                0);

        assertEquals(QuestState.LOCKED, ClientQuestCache.stateOf("b"),
                "fixture sanity: the dependency is unmet");
        assertEquals(-1, ClientQuestCache.firstSubmitTask("b"),
                "a locked quest refuses every press, so it offers none");
        assertEquals(0, ClientQuestCache.firstSubmitTask("a"),
                "and the open neighbour still offers its button");
    }

    @Test
    @DisplayName("a description keeps its tokens for the prose renderer while its title strips")
    void descriptionKeepsTokens() {
        // The regression this exists for: descriptions resolved through the same stripping reader
        // as titles, so page breaks, pictures, colours and links arrived already read out -- while
        // markdown survived, which read as half a feature. Titles strip because their surfaces draw
        // one ink; the description surface draws run by run, so it resolves raw.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"&aGreen\", \"description\": [\"&cRed {@pagebreak}next\","
                        + " \"{image:pack:textures/a.png width:10 height:10}\"]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertEquals("Green", entryFor("a").titleText(), "the title reads the words without the ink");
        assertEquals(List.of("&cRed {@pagebreak}next",
                "{image:pack:textures/a.png width:10 height:10}"), entryFor("a").descriptionText(),
                "but the description keeps every token for the renderer");
    }

    @Test
    @DisplayName("a repeatable round cooling down offers no Submit button, and offers it again after")
    void coolingRepeatableHidesItsButton() {
        // The bug this exists for: the round's tasks read 0 of 1 while cooling, so the count rule
        // offered "hand in" -- and the press was silently refused, because the server only accepts
        // a press the cooldown has released. A button whose only effect is a refusal is worse than
        // no button, and the button has to come back on its own when the wait is over.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"repeatable\": true, "
                        + "\"repeatCooldownTicks\": 100,"
                        + " \"tasks\": [{\"type\": \"tenet:checkmark\", \"title\": \"Again\"}]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
        UUID team = UUID.randomUUID();

        // The round just ended: claimed, counted, tasks cleared, clock running -- the same shape
        // the claim path leaves behind (completed, then the round over). Accepted at client tick
        // zero, so the test owns the clock: advancing the real ticker below counts the cooldown
        // down from here.
        TeamProgress progress =
                progressWith(index, "a", QuestProgress.NONE.completedAt(NOW).repeatRoundOver(NOW));
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, progress, NOW);
        dev.ellipog.tenet.progress.ProgressService.Live live = (questId, taskIndex) -> 0;
        ClientQuestCache.acceptProgress(team, NOW,
                QuestSync.progressDelta(resolution, progress, index, null,
                        (questId, taskIndex) -> Map.of(), java.util.Set.of(), Map.of(), live).json(),
                0);

        assertEquals(QuestState.COMPLETED, ClientQuestCache.stateOf("a"),
                "cooling reads as completed, as it should");
        assertEquals(-1, ClientQuestCache.firstSubmitTask("a"),
                "but there is no button while the press would be refused");

        // A hundred client ticks later the wait is over, with no new sync: the countdown is local,
        // and the button comes back on its own. The ticker is process-wide, so it is reset before
        // leaving -- no other test may observe time having passed here.
        try {
            for (int i = 0; i < 100; i++) {
                dev.ellipog.tenet.client.ClientTicker.advance();
            }
            assertEquals(0, ClientQuestCache.firstSubmitTask("a"),
                    "the wait is over and the press would be accepted, so the button is back");
        }
        finally {
            dev.ellipog.tenet.client.ClientTicker.reset();
        }
    }

    @Test
    @DisplayName("an item-tag task carries its tag, and an item task carries none")
    void aTagTaskCarriesItsTag() {
        // The tag is a field rather than a fragment of the row's sentence: it is what lets a recipe
        // viewer answer "which quests want one of these logs", and display text is not data. It rides
        // the existing tree payload, exactly like the observation fields beside it.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": ["
                        + "{\"type\": \"tenet:item_tag\", \"tag\": \"minecraft:logs\", \"count\": 4},"
                        + "{\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\", \"count\": 1}]}"));

        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertEquals("minecraft:logs", entryFor("a").tasks().get(0).tagId());
        assertEquals("", entryFor("a").tasks().get(1).tagId(), "an item task names an item, not a tag");
    }

    @Test
    @DisplayName("a break task carries its block and tag, and reads its sentence")
    void breakTaskCarriesItsBlock() {
        // The block fields ride the existing tree payload beside the tag task's tag: they are what
        // let a recipe viewer answer "which quests want this stone broken", and display text is
        // not data. The row itself draws the sentence with the block's own picture.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": ["
                        + "{\"type\": \"tenet:break\", \"block\": \"minecraft:stone\", \"value\": 4},"
                        + "{\"type\": \"tenet:break\", \"blockTag\": \"minecraft:logs\", \"value\": 4}]}"));

        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.TaskEntry single = entryFor("a").tasks().get(0);
        assertEquals("minecraft:stone", single.block());
        assertEquals("", single.blockTag(), "a block task names a block, not a tag");
        assertFalse(single.hasItem(), "a break task draws its sentence, not an item");
        assertEquals("Break minecraft:stone", single.text().getString(),
                "the fallback reads, because the key has no translation in this JVM");
        assertFalse(single.picture().isEmpty(), "the block's own item travels as the picture");

        ClientQuestCache.TaskEntry tagged = entryFor("a").tasks().get(1);
        assertEquals("", tagged.block());
        assertEquals("minecraft:logs", tagged.blockTag());
        assertTrue(tagged.picture().isEmpty(),
                "a tag names no one block, so there is no picture to travel");
        assertFalse(tagged.icon().isEmpty(), "and the row falls back to the type's own pickaxe");
    }

    @Test
    @DisplayName("a checkmark arrives as text, with the author's own words as the fallback")
    void checkmarkArrives() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tenet:checkmark\", \"title\": \"I read the sign\"} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.TaskEntry task = entryFor("a").tasks().get(0);
        assertFalse(task.hasItem(), "a checkmark has no item to draw");
        assertTrue(task.manual(), "a checkmark only completes when someone hands it over");
        // The literal title went as the label, since a plain string in the file is not translatable.
        assertEquals("I read the sign", task.text().getString());
    }

    @Test
    @DisplayName("a translatable checkmark keeps its key and its English words apart")
    void translatableCheckmarkArrives() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": [ {\"type\": \"tenet:checkmark\", "
                        + "\"title\": {\"translate\": \"tenet.test.read_it\", \"fallback\": \"Read it\"}} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.TaskEntry task = entryFor("a").tasks().get(0);
        // The fallback is what is shown, because the key has no translation in this test JVM. That is
        // the behaviour that matters: a missing translation must not show a raw key.
        assertEquals("Read it", task.text().getString());
    }

    // ------------------------------------------------------------------
    // Per-locale text: the wire's two halves, and what a player reads
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a translatable title arrives as a key with its words, and reads as the words")
    void translatableTitleReadsAsItsFallback() {
        // The bug this exists for: the tree used to send `QuestText.value()`, which for a translatable
        // text is the *key*, so the client drew `tenet.test.punch` on the node. The fallback field's
        // presence is now what tells the client it is a key rather than a literal.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": {\"translate\": \"tenet.test.punch\", "
                        + "\"fallback\": \"Punch a Tree\"}}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry entry = entryFor("a");
        assertEquals("tenet.test.punch", entry.title(), "the wire still carries the author's key");
        assertEquals("Punch a Tree", entry.titleFallback(), "and the words beside it");
        assertEquals("Punch a Tree", entry.titleText(), "a player must never be shown the raw key");
    }

    @Test
    @DisplayName("a plain string arrives with no fallback at all, so nothing can mistake it for a key")
    void literalTitleCarriesNoFallback() {
        // The empty fallback is load-bearing rather than incidental: it is the client's only way to
        // tell a literal from a key. A server that sent the text in both fields would make a literal
        // resolvable as a key, and a resource pack that happened to define that string would silently
        // replace an author's plain words.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"Punch a Tree\"}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry entry = entryFor("a");
        assertEquals("", entry.titleFallback());
        assertEquals("Punch a Tree", entry.titleText());
    }

    @Test
    @DisplayName("a title carrying legacy colour codes reads the words without the ink")
    void codedTitleReadsStripped() {
        // The shape every converted chapter title arrives in. The one-ink surfaces draw through the
        // same resolver, so stripping here is stripping everywhere: the sidebar, the canvas, the card
        // header and the toasts all read this and never the raw field.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"&aChapter 2&r: &6The ATM Star\"}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry entry = entryFor("a");
        assertEquals("Chapter 2: The ATM Star", entry.titleText(),
                "a player must never be shown the raw codes");
        assertEquals("&aChapter 2&r: &6The ATM Star", entry.title(),
                "and the raw field is untouched, because the editor seeds its text fields from it");
    }

    @Test
    @DisplayName("a translatable title with no fallback still marks itself as a key")
    void translatableWithoutFallbackIsStillAKey() {
        // An author may write `{"translate": key}` and nothing else, and the validator warns about it.
        // The wire still sends a fallback -- the key itself -- because the field's *presence* is what
        // tells the client to try the pack's conventional key. Sending nothing would make it a literal
        // and take that road away, which is the difference between a translated title and a raw key.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": {\"translate\": \"tenet.test.punch\"}}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry entry = entryFor("a");
        assertEquals("tenet.test.punch", entry.titleFallback());
        assertEquals("tenet.test.punch", entry.titleText(),
                "nothing can translate it, so the key is the only text there is");
    }

    @Test
    @DisplayName("the pack's conventional key translates a plain string without touching the file")
    void conventionalKeyTranslatesALiteral() {
        // The road a converted pack uses: `quest.<id>.title` needs nothing in the quest file, which is
        // what lets a translation be added without editing the questline at all.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"punch_a_tree\", \"title\": \"Punch a Tree\"}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientLocale.accept("hu_hu", "hu_hu",
                java.util.Map.of("quest.punch_a_tree.title", "Vagj egy f\u00e1t"));

        assertEquals("Vagj egy f\u00e1t", entryFor("punch_a_tree").titleText());
        // And the raw field is untouched, because the editor seeds its text fields from it and a
        // translated string written back would replace the author's own words.
        assertEquals("Punch a Tree", entryFor("punch_a_tree").title());
    }

    @Test
    @DisplayName("the author's own key wins over the pack's conventional one")
    void authoredKeyBeatsConventionalKey() {
        // Two roads to the same text, and the more specific one has to win: the author who wrote a
        // translate key for this exact quest meant it, where the conventional key is what a pack-wide
        // overlay says about the id.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": {\"translate\": \"my_pack.greeting\", "
                        + "\"fallback\": \"Hello\"}}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientLocale.accept("hu_hu", "hu_hu", java.util.Map.of(
                "my_pack.greeting", "Szia",
                "quest.a.title", "Konvencionalis"));

        assertEquals("Szia", entryFor("a").titleText());
    }

    @Test
    @DisplayName("description fallbacks pair with their paragraphs, and the ends are cut together")
    void descriptionFallbacksPairByIndex() {
        // The pairing is by index, and the two lists are cut to the *same* ends. Trimming the
        // fallbacks by their own blankness would be wrong in a way that is hard to see: a literal
        // paragraph in the middle carries an empty fallback, so a rule that dropped blanks would drop
        // that entry and shift every paragraph after it onto the wrong translation. See `Prose.Ends`.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"description\": ["
                        + "\"\", "
                        + "\"First\", "
                        + "{\"translate\": \"my_pack.second\", \"fallback\": \"Second\"}, "
                        + "\"Third\", "
                        + "\"\"]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertEquals(java.util.List.of("First", "Second", "Third"), entryFor("a").descriptionText(),
                "the blank ends are cut from both lists, so nothing shifts onto the wrong paragraph");
    }

    @Test
    @DisplayName("one key for the whole description overrides the paragraph count entirely")
    void wholeDescriptionOverridesTheCount() {
        // The road a translator uses when they condensed three paragraphs into one or split one into
        // four. It is first because it is the only one that cannot half-fall-back against the
        // canonical count.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"description\": [\"One\", \"Two\", \"Three\"]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientLocale.accept("hu_hu", "hu_hu",
                java.util.Map.of("quest.a.description", "Egy\nKetto"));

        assertEquals(java.util.List.of("Egy", "Ketto"), entryFor("a").descriptionText(),
                "a description written as two paragraphs replaces one written as three");
    }

    @Test
    @DisplayName("a description a locale supplies is kept even when the canonical one is empty")
    void aTranslationCanBeTheOnlyDescription() {
        // The road above, at the edge that is easy to get wrong: a quest with no description at all and
        // a locale file that writes one. Returning early on the empty canonical list would drop the
        // translator's work silently -- a key that reads as supported and does nothing.
        QuestIndex index = Fixtures.indexOf(Fixtures.file("{\"id\": \"a\", \"title\": \"a\"}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
        assertTrue(entryFor("a").descriptionText().isEmpty());

        ClientLocale.accept("hu_hu", "hu_hu",
                java.util.Map.of("quest.a.description", "Egy leiras"));

        assertEquals(java.util.List.of("Egy leiras"), entryFor("a").descriptionText());
    }

    @Test
    @DisplayName("a chapter and a group and a quest all answer to their own conventional keys")
    void everyTextBearingObjectHasAConventionalKey() {
        QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapter(
                "\"title\": \"Chapter One\",", "{\"id\": \"a\", \"title\": \"A\"}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientLocale.accept("hu_hu", "hu_hu", java.util.Map.of(
                "chapter.chapter.title", "Elso fejezet",
                "group.group.title", "Elso csoport",
                "quest.a.title", "Egy"));

        assertEquals("Elso fejezet", entryFor("a").chapterTitleText());
        assertEquals("Elso fejezet", ClientQuestCache.chapters().get(0).titleText());
        assertEquals("Elso csoport", ClientQuestCache.groups().get(0).titleText());
        assertEquals("Egy", entryFor("a").titleText());
    }

    @Test
    @DisplayName("a chapter subtitle arrives, and the pack's conventional key translates it")
    void chapterSubtitleReadsTranslated() {
        // The road a converted pack uses: `chapter.<id>.subtitle` needs nothing in the chapter file,
        // which is what lets a translation be added without editing the questline at all. The
        // fixture's chapter is `chapter`, so the conventional key is `chapter.chapter.subtitle`.
        QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapter(
                "\"title\": \"Chapter\", \"subtitle\": \"Five quests, no tricks\",",
                "{\"id\": \"a\", \"title\": \"A\"}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertEquals("Five quests, no tricks", entryFor("a").chapterSubtitle(),
                "the wire still carries the author's own words");
        assertEquals("Five quests, no tricks", entryFor("a").chapterSubtitleText());
        assertEquals("Five quests, no tricks", ClientQuestCache.chapterSubtitleText("chapter"));

        ClientLocale.accept("hu_hu", "hu_hu",
                java.util.Map.of("chapter.chapter.subtitle", "Ot kuldetes, trukk nelkul"));

        assertEquals("Ot kuldetes, trukk nelkul", entryFor("a").chapterSubtitleText());
        assertEquals("Ot kuldetes, trukk nelkul", ClientQuestCache.chapterSubtitleText("chapter"));
        // And the raw field is untouched, because the editor seeds its text fields from it and a
        // translated string written back would replace the author's own words.
        assertEquals("Five quests, no tricks", entryFor("a").chapterSubtitle());
    }

    @Test
    @DisplayName("a translatable chapter subtitle arrives as a key with its words")
    void translatableChapterSubtitleReadsAsItsFallback() {
        // The same two halves the chapter's title has carried since version 13: the key, and the
        // English words a player reads when nothing translated it. The author's own key wins over
        // the pack's conventional one, like every other text on this wire.
        QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapter(
                "\"title\": \"Chapter\","
                        + " \"subtitle\": {\"translate\": \"my_pack.chapter_sub\","
                        + " \"fallback\": \"Five quests\"},",
                "{\"id\": \"a\", \"title\": \"A\"}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry entry = entryFor("a");
        assertEquals("my_pack.chapter_sub", entry.chapterSubtitle(),
                "the wire still carries the author's key");
        assertEquals("Five quests", entry.chapterSubtitleFallback(), "and the words beside it");
        assertEquals("Five quests", entry.chapterSubtitleText(),
                "a player must never be shown the raw key");

        ClientLocale.accept("hu_hu", "hu_hu", java.util.Map.of(
                "my_pack.chapter_sub", "Sajat",
                "chapter.chapter.subtitle", "Konvencionalis"));

        assertEquals("Sajat", entryFor("a").chapterSubtitleText(),
                "the author's own key wins over the pack's conventional one");
    }

    @Test
    @DisplayName("a chapter with no subtitle sends nothing, and reads as nothing")
    void aChapterWithNoSubtitleSendsNothing() {
        // Sparse, like the quest's own subtitle: absence means no subtitle, which is every chapter
        // a version-19 server ever sent. A key saying "nothing here" would be the largest thing in
        // a tree of sixty-six chapters for the ordinary case.
        QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapter(
                "\"title\": \"Chapter\",", "{\"id\": \"a\", \"title\": \"A\"}"));

        String json = new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8);
        assertFalse(JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("quests")
                        .get(0).getAsJsonObject().has("chapterSubtitle"),
                "a chapter that names no subtitle should send no key for one");
        assertFalse(JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("quests")
                        .get(0).getAsJsonObject().has("chapterSubtitleFallback"));

        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertEquals("", entryFor("a").chapterSubtitle());
        assertEquals("", entryFor("a").chapterSubtitleText(),
                "and an older server's tree reads the same way, through the same absence");
        assertEquals("", ClientQuestCache.chapterSubtitleText("chapter"));
        assertEquals("", ClientQuestCache.chapterSubtitleText("no_such_chapter"),
                "an unknown chapter is no subtitle rather than a throw");
    }

    @Test
    @DisplayName("the tree carries no locale but the canonical one")
    void theTreeCarriesOneLocaleOnly() {
        // The whole point of the separate channel: the tree is broadcast, so fifteen locales on it
        // would send every player fourteen they cannot read, on the message that is already the
        // largest one the mod sends. This is the check rather than the sentence.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"Punch a Tree\"}"));
        ClientLocale.accept("hu_hu", "hu_hu",
                java.util.Map.of("quest.a.title", "Vagj egy f\u00e1t"));
        String tree = new String(QuestSync.treeAsJson(index), java.nio.charset.StandardCharsets.UTF_8);

        assertFalse(tree.contains("Vagj egy f\u00e1t"), "a locale's text reached the tree payload");
        assertTrue(tree.contains("Punch a Tree"),
                "and the canonical string is what the tree carries as the fallback");
    }

    @Test
    @DisplayName("an optional task arrives flagged optional, so it can be drawn differently")
    void optionalTaskArrives() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                Fixtures.q("a").tasks(2).optional(1).build()));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertFalse(entryFor("a").tasks().get(0).optional());
        assertTrue(entryFor("a").tasks().get(1).optional());
    }

    @Test
    @DisplayName("a reward arrives with its item and count")
    void rewardArrives() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": [], \"rewards\": "
                        + "[ {\"type\": \"tenet:item\", \"item\": \"minecraft:diamond\", \"count\": 4} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.RewardEntry reward = entryFor("a").rewards().get(0);
        assertTrue(reward.hasItem());
        assertEquals(Items.DIAMOND, reward.item().getItem());
        assertEquals(4, reward.count());
        assertEquals("Diamond", reward.text().getString(),
                "an item reward should read as the item's own name");
    }

    @Test
    @DisplayName("an experience reward arrives as text, since there is no item to look up")
    void xpRewardArrives() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": [], \"rewards\": "
                        + "[ {\"type\": \"tenet:xp\", \"amount\": 5, \"levels\": true} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.RewardEntry reward = entryFor("a").rewards().get(0);
        assertFalse(reward.hasItem(), "experience has no item");
        assertEquals(5, reward.count());
        assertTrue(reward.text().getString().contains("level"),
                "five levels should read as levels, not as points: " + reward.text().getString());
    }

    @Test
    @DisplayName("a row's subject crosses the wire, so the key renders the sentence it was written for")
    void subjectsCrossTheWire() {
        // The reported bug at its own example: a stage task and a stage reward read "1" on the card,
        // because the count was the only argument the client had to give a key written for a subject.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tenet:stage\", \"stage\": \"my_pack:inducted\"} ], \"rewards\": "
                        + "[ {\"type\": \"tenet:stage\", \"stage\": \"my_pack:inducted\"} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.TaskEntry task = entryFor("a").tasks().get(0);
        assertEquals("my_pack:inducted", task.labelArg(), "the task's subject did not cross the wire");
        // With no language loaded this JVM resolves the fallback, which is the same sentence the key
        // holds -- the naming sweep reads the file's own English for every type.
        assertEquals("Have the stage my_pack:inducted", task.text().getString(),
                "the task must read as its sentence, not as the bare count");

        ClientQuestCache.RewardEntry reward = entryFor("a").rewards().get(0);
        assertEquals("my_pack:inducted", reward.labelArg(), "the reward's subject did not cross the wire");
        assertEquals("Grant the stage my_pack:inducted", reward.text().getString(),
                "the reward must read as its sentence, not as the bare count");
    }

    @Test
    @DisplayName("a payload from an older server, with no subject on it, still renders the count-shaped keys")
    void anOlderPayloadStillReads() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": [], \"rewards\": "
                        + "[ {\"type\": \"tenet:xp\", \"amount\": 5, \"levels\": true} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(),
                withoutSubjects(QuestSync.treeAsJson(index)));

        ClientQuestCache.RewardEntry reward = entryFor("a").rewards().get(0);
        assertEquals("", reward.labelArg(), "an older writer sends no subject");
        // The count is still the argument the sentence is built from -- this is the pairing an older
        // server's keys rely on, and the reason the client falls back to the count rather than to "".
        assertEquals("5 levels", reward.text().getString());
    }

    /** The tree as an older writer would have sent it: every {@code labelArg} taken back out. */
    private static byte[] withoutSubjects(byte[] tree) {
        JsonObject json = JsonParser.parseString(new String(tree, StandardCharsets.UTF_8))
                .getAsJsonObject();
        for (JsonElement quest : json.getAsJsonArray("quests")) {
            for (String member : List.of("tasks", "rewards")) {
                if (!quest.getAsJsonObject().has(member)) {
                    continue;
                }
                for (JsonElement entry : quest.getAsJsonObject().getAsJsonArray(member)) {
                    entry.getAsJsonObject().remove("labelArg");
                }
            }
        }
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a tree with no quests still arrives as a tree, and a chapter with no quests is data")
    void emptyTreeArrives() {
        // A server with no quest files, which is what a fresh install is. The client has to be able
        // to tell "nothing loaded" from "nothing received", and hasTree is that distinction.
        //
        // Since version 3 the chapter list travels on its own, so "no quests" and "nothing to show" are
        // no longer the same sentence: `Fixtures.file()` is one group holding one chapter with no quests,
        // and that chapter is a real row in the sidebar -- which is exactly what makes a newly created
        // chapter visible before anybody writes a quest into it.
        QuestIndex empty = Fixtures.indexOf(Fixtures.file());
        ClientQuestCache.acceptTree(empty.questCount(), empty.chapterCount(), QuestSync.treeAsJson(empty));

        assertTrue(ClientQuestCache.hasTree(), "an empty tree should still count as received");
        assertTrue(ClientQuestCache.entries().isEmpty(), "there are no quests to show");
        assertEquals(1, ClientQuestCache.chapters().size(),
                "but there is a chapter, and a chapter is something to draw");
        assertTrue(ClientQuestCache.hasData(), "a chapter with no quests is still a chapter");

        // And a tree with nothing in it at all is still "nothing to show", which is the state a fresh
        // install with no chapters is in.
        ClientQuestCache.acceptTree(0, 0, """
                {"version":3,"groups":[],"chapters":[],"quests":[]}
                """.getBytes(StandardCharsets.UTF_8));
        assertTrue(ClientQuestCache.hasTree());
        assertFalse(ClientQuestCache.hasData(), "no chapters and no quests is nothing to show");
    }

    @Test
    @DisplayName("line styles arrive as the chapter's resolved default plus the overrides as written")
    void dependencyStylesRoundTrip(@TempDir Path temp) throws IOException {
        // Built through the real loader, because the two halves being tested are the writer and the
        // reader and the file shape between them: a chapter default that has to be resolved, and a
        // quest's override that must arrive unsaid on the axes it does not name.
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests.resolve("group/chapter"));
        Files.writeString(quests.resolve("group/group.json"), """
                { "id": "group", "title": "Group", "chapters": ["chapter"] }
                """);
        Files.writeString(quests.resolve("group/chapter/chapter.json"), """
                { "$schema": "../../../_schema/chapter.schema.json",
                  "id": "chapter", "title": "Chapter",
                  "dependencyStyle": { "form": "curved", "weight": "thick", "arrowHead": "diamond" },
                  "quests": ["one.json", "two.json"] }
                """);
        Files.writeString(quests.resolve("group/chapter/one.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "one", "title": "One" }
                """);
        Files.writeString(quests.resolve("group/chapter/two.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "two", "title": "Two",
                  "dependsOn": ["one"],
                  "dependencyLines": { "one": { "arrows": "none", "dash": "dashed" } } }
                """);

        QuestLoader.Result loaded = QuestLoader.load(temp);
        assertTrue(loaded.ok(), () -> "the fixture has to load cleanly:\n"
                + loaded.problems().all().stream().map(problem -> problem.render())
                        .reduce("", (a, b) -> a + "\n" + b));
        QuestIndex index = loaded.index();
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry two = entryFor("two");
        DependencyStyle chapter = two.chapterDependencyStyle();
        assertEquals(DependencyStyle.Form.CURVED, chapter.formOr(null));
        assertEquals(DependencyStyle.Weight.THICK, chapter.weightOr(null));
        assertEquals(DependencyStyle.ArrowHead.DIAMOND, chapter.headOr(null),
                "a new axis the chapter did name arrives as itself");
        assertEquals(DependencyStyle.ArrowPlace.TARGET, chapter.placeOr(null),
                "an axis the chapter did not name arrives as the built-in, already resolved");
        assertEquals(DependencyStyle.ArrowDensity.MEDIUM.spacing(), chapter.arrowSpacing());
        assertTrue(chapter.arrows().isEmpty(),
                "the resolved default speaks the current vocabulary; the legacy axis only travels raw");

        DependencyStyle line = two.dependencyLines().get("one");
        assertEquals(DependencyStyle.Arrows.NONE, line.arrowsOr(null),
                "an old override arrives exactly as written, unsaid on the new axes");
        assertEquals(DependencyStyle.ArrowHead.NONE, line.headOr(null),
                "and the legacy value still reads as the glyph it always meant");
        assertTrue(line.arrowHead().isEmpty());
        assertEquals(DependencyStyle.Dash.DASHED, line.dashOr(null));
        assertTrue(line.form().isEmpty(), "the line named no form, so the chapter's stays in force");
        assertTrue(entryFor("one").dependencyLines().isEmpty(),
                "a quest that overrides nothing carries no map at all");
    }

    @Test
    @DisplayName("a chapter's theme patch rides the wire raw, and the cache hands it back")
    void chapterThemePatchRoundTrips(@TempDir Path temp) throws IOException {
        // Raw rather than parsed: the client is the side that composes a patch, and the server's job is
        // to carry what the file said. Asserted through the real loader because the two ends are the
        // writer and the reader, and the shape between them is the thing that can disagree.
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests.resolve("group/chapter"));
        Files.writeString(quests.resolve("group/group.json"), """
                { "id": "group", "title": "Group", "chapters": ["chapter"] }
                """);
        Files.writeString(quests.resolve("group/chapter/chapter.json"), """
                { "$schema": "../../../_schema/chapter.schema.json",
                  "id": "chapter", "title": "Chapter",
                  "theme": "tome",
                  "themePatch": { "colours": { "raised": "#FF24242E" }, "cornerRadius": 4 },
                  "quests": ["one.json"] }
                """);
        Files.writeString(quests.resolve("group/chapter/one.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "one", "title": "One" }
                """);

        QuestLoader.Result loaded = QuestLoader.load(temp);
        assertTrue(loaded.ok(), () -> "the fixture has to load cleanly:\n"
                + loaded.problems().all().stream().map(problem -> problem.render())
                        .reduce("", (a, b) -> a + "\n" + b));
        QuestIndex index = loaded.index();
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertEquals("tome", ClientQuestCache.chapterTheme("chapter"),
                "the name still travels as it did");
        JsonObject patch = ClientQuestCache.chapterThemePatch("chapter");
        assertNotNull(patch, "the patch must survive the wire");
        assertEquals("#FF24242E",
                patch.getAsJsonObject("colours").get("raised").getAsString(),
                "a colour arrives as the file wrote it");
        assertEquals(4, patch.get("cornerRadius").getAsInt(), "and so does the radius");
    }

    // ------------------------------------------------------------------
    // Chapter groups, which version 2 added
    // ------------------------------------------------------------------

    /**
     * The two additions of version 2: a flat {@code groups[]} at the root, and a {@code chapterGroupId}
     * on every quest.
     *
     * <h2>Why this is a nested class of its own rather than four more methods above</h2>
     *
     * <p>Because it is the one part of the wire with a <b>compatibility claim</b> attached, and the claim
     * is what needs testing rather than the fields. The writer and the reader are both hand-written JSON,
     * so nothing but this test connects them; and on top of that the design promises that every change
     * here is additive, which means an old client on a new server keeps drawing today's flat list.
     *
     * <p>That promise is the reason three of the tests below are about what a tree <i>without</i> the new
     * fields does, and what an unexpectedly high version number does. Those are the cases a future
     * change breaks silently — nothing throws, no test about the new field notices, and the symptom is
     * somebody's quest book drawn as a different shape from the one they wrote.
     */
    @Nested
    @DisplayName("the tree's chapter groups")
    class Groups {

        /**
         * Two groups, deliberately written out of alphabetical order.
         *
         * <p>{@code zzz_written_first} before {@code aaa_written_second}, so that "declaration order" and
         * "sorted order" are different answers and a client that sorted cannot pass by accident. Only the
         * characters differ — this is the same trap the loader's folder-name order creates, arriving at
         * the opposite end of the wire, and it is worth catching on both sides independently because
         * neither end can see the other's sort.
         *
         * <p>Written as a version-1 file on purpose. The wire format is version 1's <i>tree</i> plus two
         * fields, and building it through the version-1 codec keeps this test about the wire rather than
         * about the version-2 folder layout — which the acceptance test covers and which would otherwise
         * have to load here too.
         */
        private static final String TWO_GROUPS = """
                {
                  "version": 1,
                  "chapterGroups": [
                    {
                      "id": "zzz_written_first",
                      "title": "Written First",
                      "collapsedByDefault": true,
                      "chapters": [
                        {
                          "id": "first_steps",
                          "title": "First Steps",
                          "quests": [
                            { "id": "punch_a_tree", "title": "Punch a Tree",
                              "tasks": [ { "type": "tenet:checkmark", "title": "t" } ] }
                          ]
                        }
                      ]
                    },
                    {
                      "id": "aaa_written_second",
                      "title": "Written Second",
                      "chapters": [
                        {
                          "id": "second_steps",
                          "title": "Second Steps",
                          "quests": [
                            { "id": "build_a_house", "title": "Build a House",
                              "tasks": [ { "type": "tenet:checkmark", "title": "t" } ] }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;

        private static QuestIndex twoGroups() {
            return Fixtures.indexOf(TWO_GROUPS);
        }

        private static void send(QuestIndex index) {
            ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(),
                    QuestSync.treeAsJson(index));
        }

        @Test
        @DisplayName("the raw JSON carries the chapter list, which is what makes an empty chapter real")
        void theChapterListIsOnTheWire() {
            // Version 3's addition, and the reason it exists: a chapter used to reach the client only as
            // a property of the quests inside it, so one with no quests could not be seen, selected or
            // edited. Asserted here as raw JSON as well as round-tripped through the cache, because the
            // two ends are hand-written and a field name is exactly what they can disagree about.
            QuestIndex index = twoGroups();
            JsonObject root = JsonParser.parseString(
                    new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8)).getAsJsonObject();

            assertEquals(QuestSync.TREE_VERSION, root.get("version").getAsInt());
            JsonArray chapters = root.getAsJsonArray("chapters");
            assertNotNull(chapters, "version 3 has to send the chapter list, however short it is");
            assertEquals(index.chapters().size(), chapters.size());
            JsonObject first = chapters.get(0).getAsJsonObject();
            assertEquals("first_steps", first.get("id").getAsString());
            assertEquals("zzz_written_first", first.get("groupId").getAsString());
            assertEquals("First Steps", first.get("title").getAsString());
            assertTrue(first.has("icon"),
                    "the chapter's own icon, so a chapter with no quests has one of its own rather than "
                            + "borrowing from a quest that does not exist");

            // And the reader holds it, under the same names.
            send(index);
            assertEquals(2, ClientQuestCache.chapters().size());
            assertEquals("first_steps", ClientQuestCache.chapters().get(0).id());
            assertEquals("zzz_written_first", ClientQuestCache.chapters().get(0).groupId());
        }

        @Test
        @DisplayName("the version the chapter subtitle arrived at is pinned, so bumping it is a decision")
        void theAliasVersionIsPinned() {
            // Deliberately a literal rather than `QuestSync.TREE_VERSION` on both sides, which is how the
            // case above is written and why it could not notice this feature: a reader importing the
            // writer's constant is right for the *reader*, and a test that does the same is comparing a
            // number with itself. This is the one place the number is written down twice on purpose.
            //
            // Version 14 added the elements; version 15 added the links beside them; version 16 added
            // the aliases; version 17 added the authoring flags; version 18 added each icon's kind;
            // version 19 added each item, item-tag and fluid task's manualOnly; version 20 added each
            // chapter's subtitle beside its title; version 21 added each task's and reward's picture
            // beside its requirement. The ledger in
            // QuestSync carries the history; this pins the present, because bumping is
            // a deliberate break rather than a side effect of an edit.
            assertEquals(21, QuestSync.TREE_VERSION,
                    "version 21 added each task's and reward's picture beside its requirement. "
                            + "Bumping this is a "
                            + "deliberate break rather than a side effect of an edit -- see the ledger in "
                            + "QuestSync for what each version added.");
        }

        @Test
        @DisplayName("a chapter's elements travel, in the order the file wrote them")
        void chapterElementsTravel() {
            // The tree carries the elements rather than a summary of them, and carries them in
            // **declaration** order rather than in draw order. That is deliberate: the file's order is what
            // breaks a tie between two elements with one `order`, so it is a fact the server has and the
            // client needs, and sorting it on the wire would throw away the only tie-break there is.
            QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapter(
                    "\"elements\": [ { \"type\": \"text\", \"id\": \"label\", \"order\": 2,"
                            + " \"text\": { \"translate\": \"element.label.text\","
                            + " \"fallback\": \"Chapter 1\" }, \"scale\": 1.5 },"
                            + " { \"type\": \"rect\", \"id\": \"box\", \"order\": 1,"
                            + " \"width\": 64, \"height\": 64, \"fillColor\": \"#40101018\" } ],",
                    q("a").build()));

            JsonObject root = JsonParser.parseString(
                    new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject chapter = root.getAsJsonArray("chapters").get(0).getAsJsonObject();
            JsonArray elements = chapter.getAsJsonArray("elements");
            assertNotNull(elements, "a chapter with elements has to send them");
            assertEquals(2, elements.size());

            JsonObject label = elements.get(0).getAsJsonObject();
            assertEquals("text", label.get("type").getAsString());
            assertEquals("label", label.get("id").getAsString());
            assertEquals(2, label.get("order").getAsInt());
            // A translatable text travels as the object a *file* writes, which is the one place this wire
            // does not use the `<name>`/`<name>Fallback` pair: the element's own codec writes it, so a field
            // added to an arm arrives without anybody remembering to send it. The client decodes it back
            // into the same QuestText, so nothing downstream can tell the difference.
            JsonObject text = label.getAsJsonObject("text");
            assertEquals("element.label.text", text.get("translate").getAsString());
            assertEquals("Chapter 1", text.get("fallback").getAsString());
            assertEquals(1.5, label.get("scale").getAsDouble());
            assertEquals("rect", elements.get(1).getAsJsonObject().get("type").getAsString(),
                    "and the second is where the file put it, not where it draws");

            // And the reader holds them, sorted into draw order -- the one thing the tree does not do.
            send(index);
            List<CanvasElement> held = ClientQuestCache.elements("chapter");
            assertEquals(2, held.size());
            assertEquals("box", held.get(0).id(), "order 1 draws before order 2");
            assertEquals("label", held.get(1).id());
            assertEquals(1.5, assertInstanceOf(CanvasElement.Text.class, held.get(1)).scale(),
                    "and the fields survived the trip");
        }

        @Test
        @DisplayName("a chapter with no elements sends no key at all, and reads as a plain canvas")
        void aChapterWithNoElementsSendsNothing() {
            // Absence is the ordinary case and it is the same reading an older server gets: a canvas with
            // nothing drawn on it. A key per chapter saying "nothing here" would be the largest thing in a
            // tree of sixty-six chapters, which is why it is sent only when there is something to say.
            QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapter("", q("a").build()));

            JsonObject root = JsonParser.parseString(
                    new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8)).getAsJsonObject();
            assertFalse(root.getAsJsonArray("chapters").get(0).getAsJsonObject().has("elements"),
                    "an empty list is sent as nothing rather than as an empty array");

            // A version-13 tree, which is what a server without this feature sends: the client draws the
            // chapter with no decoration rather than refusing the tree. That is the whole additive promise.
            send(index);
            assertTrue(ClientQuestCache.elements("chapter").isEmpty());
            assertEquals(1, ClientQuestCache.chapters().size(), "and the chapter itself is still there");
        }

        @Test
        @DisplayName("clearing the cache forgets a chapter's elements, like everything else about a server")
        void clearingForgetsElements() {
            // A clear is a change of server or of world, and a decoration describes the pack it came from
            // as surely as a chapter does. Leaving it would draw the last world's labels over the next
            // one's canvas -- which reads as a pack that shipped somebody else's chapter art.
            QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapter(
                    "\"elements\": [ { \"type\": \"rect\", \"id\": \"box\" } ],", q("a").build()));

            send(index);
            assertEquals(1, ClientQuestCache.elements("chapter").size(), "the fixture has to hold one");

            ClientQuestCache.clear();
            assertTrue(ClientQuestCache.elements("chapter").isEmpty(), "a clear forgets them");
            assertTrue(ClientQuestCache.elements("anything").isEmpty(),
                    "and an unknown chapter is empty rather than null, before and after a clear");
        }

        @Test
        @DisplayName("a chapter's links travel, in the order the file wrote them")
        void chapterLinksTravel() {
            // The tree carries the links rather than a summary of them, in declaration order like the
            // elements: the file's order is what an author sees in the chapter tab, so it is a fact
            // the server has and the client needs.
            QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapter(
                    "\"links\": [ { \"id\": \"first_hint\", \"quest\": \"a\", \"x\": 64, \"y\": -32 },"
                            + " { \"id\": \"second_hint\", \"quest\": \"a\", \"shape\": \"hexagon\","
                            + " \"size\": 64 } ],",
                    q("a").build()));

            JsonObject root = JsonParser.parseString(
                    new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject chapter = root.getAsJsonArray("chapters").get(0).getAsJsonObject();
            JsonArray links = chapter.getAsJsonArray("links");
            assertNotNull(links, "a chapter with links has to send them");
            assertEquals(2, links.size());

            JsonObject first = links.get(0).getAsJsonObject();
            assertEquals("first_hint", first.get("id").getAsString());
            assertEquals("a", first.get("quest").getAsString());
            assertEquals(64, first.get("x").getAsInt());
            assertEquals("hexagon", links.get(1).getAsJsonObject().get("shape").getAsString(),
                    "and the second is where the file put it, with the shape it named");

            // And the reader holds them, under the same names.
            send(index);
            List<dev.ellipog.tenet.quest.QuestLink> held = ClientQuestCache.links("chapter");
            assertEquals(2, held.size());
            assertEquals("first_hint", held.get(0).id());
            assertEquals("a", held.get(0).quest().id());
            assertEquals(dev.ellipog.tenet.quest.QuestShape.HEXAGON, held.get(1).shape(),
                    "and the fields survived the trip");
        }

        @Test
        @DisplayName("a chapter with no links sends no key at all, and reads as a canvas with no markers")
        void aChapterWithNoLinksSendsNothing() {
            // Absence is the ordinary case and it is the same reading an older server gets: a canvas
            // with no markers. A key per chapter saying "nothing here" would be the largest thing in
            // a tree of sixty-six chapters, which is why it is sent only when there is something to
            // say. A version-15 tree, which is what a server without links sends, reads the
            // same way.
            QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapter("", q("a").build()));

            JsonObject root = JsonParser.parseString(
                    new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8)).getAsJsonObject();
            assertFalse(root.getAsJsonArray("chapters").get(0).getAsJsonObject().has("links"),
                    "an empty list is sent as nothing rather than as an empty array");

            send(index);
            assertTrue(ClientQuestCache.links("chapter").isEmpty());
            assertEquals(1, ClientQuestCache.chapters().size(), "and the chapter itself is still there");
        }

        @Test
        @DisplayName("clearing the cache forgets a chapter's links, like everything else about a server")
        void clearingForgetsLinks() {
            // A clear is a change of server or of world, and a marker describes the pack it came from
            // as surely as a chapter does. Leaving it would draw a shortcut to a quest on the last
            // world's canvas over the next one's.
            QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapter(
                    "\"links\": [ { \"id\": \"gate_hint\", \"quest\": \"a\" } ],", q("a").build()));

            send(index);
            assertEquals(1, ClientQuestCache.links("chapter").size(), "the fixture has to hold one");

            ClientQuestCache.clear();
            assertTrue(ClientQuestCache.links("chapter").isEmpty(), "a clear forgets them");
            assertTrue(ClientQuestCache.links("anything").isEmpty(),
                    "and an unknown chapter is empty rather than null, before and after a clear");
        }

        @Test
        @DisplayName("a quest's aliases travel sparsely, and resolve on the client by id or alias")
        void questAliasesTravel() {
            // Sparse: absent means "no aliases", which is every quest that was never renamed -- and a
            // version-15 tree, which is what a server without this feature sends, reads the same way.
            // The client needs them for the same lookups the server does, so a press naming an alias
            // opens the quest rather than reporting a broken control.
            QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapter("",
                    q("renamed").alias("old_name").build()));

            JsonObject root = JsonParser.parseString(
                    new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject quest = root.getAsJsonArray("quests").get(0).getAsJsonObject();
            assertTrue(quest.has("aliases"), "a renamed quest has to send them");
            assertEquals(1, quest.getAsJsonArray("aliases").size());
            assertEquals("old_name", quest.getAsJsonArray("aliases").get(0).getAsString());

            QuestIndex plain = Fixtures.indexOf(Fixtures.fileWithChapter("", q("a").build()));
            JsonObject plainQuest = JsonParser.parseString(
                    new String(QuestSync.treeAsJson(plain), StandardCharsets.UTF_8)).getAsJsonObject()
                    .getAsJsonArray("quests").get(0).getAsJsonObject();
            assertFalse(plainQuest.has("aliases"),
                    "an empty list is sent as nothing rather than as an empty array");

            // And the reader resolves, in any letter case, with an empty list when the server sent none.
            send(index);
            ClientQuestCache.Entry entry = ClientQuestCache.entries().stream()
                    .filter(each -> each.id().equals("renamed")).findFirst().orElseThrow();
            assertTrue(entry.matches("renamed"), "the id resolves");
            assertTrue(entry.matches("old_name"), "and so does the alias");
            assertTrue(entry.matches("OLD_NAME"), "in any letter case, because lookups are");
            assertFalse(entry.matches("nope"), "and nothing else does");

            send(plain);
            ClientQuestCache.Entry bare = ClientQuestCache.entries().stream()
                    .filter(each -> each.id().equals("a")).findFirst().orElseThrow();
            assertTrue(bare.matches("a"));
            assertFalse(bare.matches("old_name"), "a quest with no aliases answers to its id alone");
        }

        @Test
        @DisplayName("a quest's tags and guide page travel sparsely, and a plain quest sends neither (T30)")
        void questTagsAndGuidePageTravel() {
            // Sparse and unversioned, like the aliases above: absent means "none", which is what
            // every older server says, and an old reader ignores both keys.
            QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapter("",
                    "{\"id\": \"a\", \"title\": \"A\", \"tags\": [\"village\"],"
                            + " \"guidePage\": \"my_pack:early_game\"}"));

            JsonObject root = JsonParser.parseString(
                    new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject quest = root.getAsJsonArray("quests").get(0).getAsJsonObject();
            assertTrue(quest.has("tags"), "a tagged quest has to send them");
            assertEquals(1, quest.getAsJsonArray("tags").size());
            assertEquals("village", quest.getAsJsonArray("tags").get(0).getAsString());
            assertEquals("my_pack:early_game", quest.get("guidePage").getAsString());

            QuestIndex plain = Fixtures.indexOf(Fixtures.fileWithChapter("", q("a").build()));
            JsonObject plainQuest = JsonParser.parseString(
                    new String(QuestSync.treeAsJson(plain), StandardCharsets.UTF_8)).getAsJsonObject()
                    .getAsJsonArray("quests").get(0).getAsJsonObject();
            assertFalse(plainQuest.has("tags"),
                    "an empty list is sent as nothing rather than as an empty array");
            assertFalse(plainQuest.has("guidePage"), "and so is an unnamed page");

            // And the reader resolves a #tag the way the server does, with an empty list and an
            // empty page when the server sent none.
            send(index);
            ClientQuestCache.Entry entry = ClientQuestCache.entries().stream()
                    .filter(each -> each.id().equals("a")).findFirst().orElseThrow();
            assertEquals(List.of("village"), entry.tags());
            assertEquals("my_pack:early_game", entry.guidePage());
            assertTrue(entry.matches("#village"), "a press naming the tag opens the quest");
            assertTrue(entry.matches("#VILLAGE"), "in any letter case, because lookups are");
            assertFalse(entry.matches("#nowhere"), "and nothing else does");

            send(plain);
            ClientQuestCache.Entry bare = ClientQuestCache.entries().stream()
                    .filter(each -> each.id().equals("a")).findFirst().orElseThrow();
            assertTrue(bare.tags().isEmpty());
            assertEquals("", bare.guidePage());
            assertFalse(bare.matches("#village"), "a quest with no tags answers to its id alone");
        }

        @Test
        @DisplayName("an always-invisible chapter says so on the wire, and a shown one sends nothing (T30)")
        void alwaysInvisibleTravels() {
            // Sparse and unversioned: absent means shown, which is what every older server says,
            // and an old reader ignores the key and lists the chapter.
            QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapter(
                    "\"alwaysInvisible\": true,", q("a").build()));

            JsonObject root = JsonParser.parseString(
                    new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject chapter = root.getAsJsonArray("chapters").get(0).getAsJsonObject();
            assertTrue(chapter.has("alwaysInvisible"), "a hidden chapter has to send it");
            assertTrue(chapter.get("alwaysInvisible").getAsBoolean());

            QuestIndex plain = Fixtures.indexOf(Fixtures.fileWithChapter("", q("a").build()));
            JsonObject plainChapter = JsonParser.parseString(
                    new String(QuestSync.treeAsJson(plain), StandardCharsets.UTF_8)).getAsJsonObject()
                    .getAsJsonArray("chapters").get(0).getAsJsonObject();
            assertFalse(plainChapter.has("alwaysInvisible"),
                    "absence is the ordinary case, and means shown");

            send(index);
            assertTrue(ClientQuestCache.chapters().get(0).alwaysInvisible(),
                    "and the reader holds it for the visibility rule");
            send(plain);
            assertFalse(ClientQuestCache.chapters().get(0).alwaysInvisible());
        }

        @Test
        @DisplayName("a chapter's LOD answer travels when set either way, and absence defers")
        void chapterLodTravels() {
            // Unlike the sparse-only-true flags above: absence is "defer to the book", so an
            // explicit false must cross too — it is how a chapter keeps its tiers under a
            // tierless book, and sparse-only-true could not say it.
            QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapters(
                    Fixtures.chapterWith("tierless", "\"disableCanvasLod\": true,",
                            q("a").build()),
                    Fixtures.chapterWith("tiered", "\"disableCanvasLod\": false,",
                            q("b").build()),
                    Fixtures.chapterWith("quiet", "", q("c").build())));

            JsonObject root = JsonParser.parseString(
                    new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, JsonObject> chapters = new java.util.HashMap<>();
            for (var element : root.getAsJsonArray("chapters")) {
                JsonObject chapter = element.getAsJsonObject();
                chapters.put(chapter.get("id").getAsString(), chapter);
            }
            assertTrue(chapters.get("tierless").get("disableCanvasLod").getAsBoolean());
            assertTrue(chapters.get("tiered").has("disableCanvasLod"),
                    "an explicit false crosses: it overrides the book");
            assertFalse(chapters.get("tiered").get("disableCanvasLod").getAsBoolean());
            assertFalse(chapters.get("quiet").has("disableCanvasLod"),
                    "absence is the ordinary case, and means defer");

            // The book says tierless: the explicit chapter answers stand, the quiet one follows.
            root.addProperty("disableCanvasLod", true);
            ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(),
                    root.toString().getBytes(StandardCharsets.UTF_8));
            assertTrue(ClientQuestCache.bookLodDisabled(), "the reader holds the book's answer");
            assertTrue(ClientQuestCache.lodDisabledFor("tierless"));
            assertFalse(ClientQuestCache.lodDisabledFor("tiered"),
                    "an explicit false wins over a tierless book");
            assertTrue(ClientQuestCache.lodDisabledFor("quiet"), "silence defers to the book");
            assertTrue(ClientQuestCache.lodDisabledFor(null), "no chapter reads as the book");
            assertTrue(ClientQuestCache.lodDisabledFor("unknown"), "and so does an unknown one");
            for (var entry : ClientQuestCache.chapters()) {
                if (entry.id().equals("quiet")) {
                    assertNull(entry.lodDisabled(), "the record keeps absent distinct from false");
                }
            }

            // And a defaulting pack tiers everywhere, as before.
            QuestIndex plain = Fixtures.indexOf(Fixtures.fileWithChapter("", q("a").build()));
            String plainJson = new String(QuestSync.treeAsJson(plain), StandardCharsets.UTF_8);
            assertFalse(plainJson.contains("\"disableCanvasLod\""),
                    "a defaulting pack sends no such key");
            send(plain);
            assertFalse(ClientQuestCache.bookLodDisabled(), "absent tiers");
            assertFalse(ClientQuestCache.lodDisabledFor("chapter"), "at the book and the chapter");
        }

        @Test
        @DisplayName("a chapter that authored no icon sends an empty id, and the client draws nothing")
        void anUnauthoredChapterIconTravelsAsAbsent() {            // The end-to-end property, and the one that was missing: the codec's "absent is the default
            // instance" behaviour was asserted in QuestManifestTest, but nothing asserted that
            // `QuestSync` *acts* on it. A chapter that declares no icon must reach the client as absent
            // rather than as the model's paper default -- a paper item at eighteen pixels reads as a
            // blank white square, which is exactly what the claim menu's banner drew for every chapter
            // that did not author one.
            QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapter("",
                    Fixtures.q("a").build()));
            JsonObject root = JsonParser.parseString(
                    new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject chapter = root.getAsJsonArray("chapters").get(0).getAsJsonObject();

            assertEquals("", chapter.get("icon").getAsString(),
                    "an unauthored chapter icon is an empty id, which is this wire's 'no icon'");
            assertFalse(chapter.has("iconComponents"),
                    "and an absent icon has no component patch to send either");

            // And the reader agrees: no stack, so nothing is drawn. The banner and the sidebar both read
            // this pair, and both already treat an empty stack as "nothing to draw".
            send(index);
            assertTrue(ClientQuestCache.chapters().get(0).icon().isEmpty(),
                    "the client resolves an empty id to no icon at all");

            // A chapter that *does* author one still sends it, so this is a distinction rather than a
            // blanket suppression.
            QuestIndex authored = Fixtures.indexOf(Fixtures.fileWithChapter(
                    "\"icon\": { \"item\": \"minecraft:anvil\" },", Fixtures.q("a").build()));
            JsonObject named = JsonParser.parseString(
                            new String(QuestSync.treeAsJson(authored), StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("chapters").get(0).getAsJsonObject();
            assertEquals("minecraft:anvil", named.get("icon").getAsString(),
                    "an authored chapter icon still travels");
        }

        @Test
        @DisplayName("the raw JSON carries the version, the headings and each quest's group")
        void groupFieldsMatch() {
            // The same contract check as `fieldNamesMatch` above, tightened onto the three things
            // version 2 added. Named here as well as in that list because that list is a `for` over
            // names, and a name *removed* from it would show up as one fewer iteration rather than as a
            // failure -- this one cannot be weakened without deleting the line.
            String json = new String(QuestSync.treeAsJson(twoGroups()), StandardCharsets.UTF_8);

            assertTrue(json.contains("\"version\":" + QuestSync.TREE_VERSION),
                    "the tree should declare its version, so a reader can tell what it is looking at: " + json);
            assertTrue(json.contains("\"groups\""),
                    "the tree has no groups array, so the client has nothing to build headings from: " + json);
            assertTrue(json.contains("\"chapters\""),
                    "the tree has no chapter list, so an empty chapter would be invisible again: " + json);
            assertTrue(json.contains("\"chapterGroupId\""),
                    "no quest says which group its chapter is in, so the headings would have nothing "
                            + "under them: " + json);

            // And the value, not just the key: a `chapterGroupId` written as a constant would satisfy
            // the check above for every quest.
            assertTrue(json.contains("\"chapterGroupId\":\"zzz_written_first\""), json);
            assertTrue(json.contains("\"chapterGroupId\":\"aaa_written_second\""), json);
        }

        @Test
        @DisplayName("the headings arrive, in the order the server declared them")
        void groupsArrive() {
            // Order is asserted because it is the one thing a client can get wrong without any symptom
            // that points here: sorting these would draw a perfectly good book with the groups in the
            // wrong places, and nothing on screen would say so.
            QuestIndex index = twoGroups();
            send(index);

            assertEquals(2, ClientQuestCache.groups().size(),
                    "the headings did not arrive: " + ClientQuestCache.groups());
            assertEquals(List.of("zzz_written_first", "aaa_written_second"),
                    ClientQuestCache.groups().stream().map(ClientQuestCache.GroupEntry::id).toList(),
                    "declaration order, not sorted order");
            assertEquals("Written First", ClientQuestCache.groups().get(0).title(),
                    "the heading's title did not cross the wire");
        }

        @Test
        @DisplayName("the authored collapsed flag arrives, which is what the sidebar seeds from")
        void collapsedByDefaultArrives() {
            // This is the *only* thing the server says about whether a group starts open, and it applies
            // once, on the first sight of a tree. What the player toggles afterwards is theirs -- which is
            // why the flag travels in one direction only and there is no "collapsed" field on the way
            // back. Both values are asserted, because a flag that always arrived true would pass a test
            // that only checked the true case.
            QuestIndex index = twoGroups();
            send(index);

            assertTrue(ClientQuestCache.groups().get(0).collapsedByDefault(),
                    "the authored collapsed flag did not survive the wire, so a group meant to start "
                            + "closed would start open");
            assertFalse(ClientQuestCache.groups().get(1).collapsedByDefault(),
                    "a group that declares nothing must arrive open -- `collapsedByDefault` defaults to "
                            + "false, and a wire that sent true for it would collapse every group");
        }

        @Test
        @DisplayName("every quest says which group its chapter is in")
        void questsCarryTheirGroupId() {
            // `groups[]` says what the headings are called; this is what says what hangs under them.
            // Without it the client would have headings and no way to place a single chapter.
            QuestIndex index = twoGroups();
            send(index);

            assertEquals("zzz_written_first", entryFor("punch_a_tree").chapterGroupId(),
                    "the quest did not carry its chapter's group");
            assertEquals("aaa_written_second", entryFor("build_a_house").chapterGroupId(),
                    "and the two quests should name *different* groups -- one shared constant would pass "
                            + "a single-assertion version of this test");
        }

        @Test
        @DisplayName("a server older than groups still draws, with no headings and no group id")
        void anOlderServerStillWorks() {
            // The additive promise in the direction this client must honour: it asks for `groups` and
            // `chapterGroupId`, and a tree that has neither has to load anyway. A reader that required
            // either would turn every old server into an empty quest book.
            //
            // Hand-written and version 1, because that is exactly what an older server sends -- and
            // writing it by hand is the point: it is not this build's writer output, so it cannot
            // accidentally contain a field the writer learned to send.
            String older = "{\"version\":1,\"quests\":[{\"chapterId\":\"c\",\"chapterTitle\":\"C\","
                    + "\"id\":\"a\",\"title\":\"A\",\"icon\":\"minecraft:stone\",\"x\":0,\"y\":0,"
                    + "\"size\":48,\"shape\":\"rounded\",\"iconScale\":0.75,\"showTitle\":false,"
                    + "\"description\":[],\"dependsOn\":[],\"tasks\":[],\"rewards\":[]}]}";
            ClientQuestCache.acceptTree(1, 1, older.getBytes(StandardCharsets.UTF_8));

            assertTrue(ClientQuestCache.hasData(), "an older server's tree must still draw");
            assertTrue(ClientQuestCache.groups().isEmpty(),
                    "no groups array means no headings -- and that is the flat chapter list's cue, so it "
                            + "must be empty rather than invented or defaulted");
            assertEquals("", entryFor("a").chapterGroupId(),
                    "and a quest with no group field belongs to no group, which is the same cue at the "
                            + "other level");
        }

        @Test
        @DisplayName("a newer server's tree is drawn with what this client knows, rather than refused")
        void aNewerServerIsNotRefused() {
            // The one thing the version number must never be used for, and the reason it is read and only
            // warned about. Refusing a version this build does not know would break precisely the case the
            // additive design exists to keep working: an install that has not been updated, against a
            // server that has. The alternative -- drawing what it understands and saying so in the log --
            // degrades in the direction that keeps a player playing.
            QuestIndex index = twoGroups();
            String thisBuild = new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8);

            // One revision above this build's, plus a field it has never heard of, which is what a newer
            // server's tree actually looks like: the fields it knows, and one more. Built from the
            // constant rather than from a literal, because the literal this replaced was `"version":2` --
            // a number no tree has emitted since the third revision, so the "newer" half of this test was
            // quietly a second copy of the current version.
            String newer = thisBuild
                    .replace("\"version\":" + QuestSync.TREE_VERSION,
                            "\"version\":" + (QuestSync.TREE_VERSION + 1))
                    .replace("\"quests\":", "\"somethingThisBuildHasNeverSeen\":[1,2,3],\"quests\":");
            assertTrue(newer.contains("\"version\":" + (QuestSync.TREE_VERSION + 1)),
                    "the fixture has to be a *newer* tree, or this test is about the current version");

            ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(),
                    newer.getBytes(StandardCharsets.UTF_8));

            assertTrue(ClientQuestCache.hasData(),
                    "a newer tree must still be drawn with whatever this build understands");
            assertEquals(2, ClientQuestCache.groups().size(),
                    "and the fields it *does* know must still arrive");
            assertEquals("zzz_written_first", entryFor("punch_a_tree").chapterGroupId());
        }
    }

    // ------------------------------------------------------------------
    // The tree revision
    // ------------------------------------------------------------------

    /**
     * The counter that lets a screen tell "still my tree" from "a new one arrived".
     *
     * <h2>The behaviour it exists for, stated as a test rather than as a comment</h2>
     *
     * <p>A screen builds a collapsible outline and keeps the player's toggles in it. It has to be able to
     * ask whether the tree under that outline is still the one it described — and the answer must be "yes"
     * for a resize, a scroll, a toggle and a redraw, and "no" when a reload replaces the tree. Getting the
     * second half wrong leaves a stale outline; getting the first half wrong silently resets the player's
     * collapses every frame, which reads as the toggle buttons not working.
     *
     * <p>Every axis a screen might read is touched in {@link #standsStillWhileNothingArrives}, because the
     * failure to guard against is a reader that bumped the counter as a side effect of answering a
     * question — which is the kind of thing a getter never does until somebody caches something.
     */
    @Nested
    @DisplayName("the tree revision")
    class Revision {

        @Test
        @DisplayName("moves when a tree arrives, so a screen knows its outline is stale")
        void movesWhenATreeArrives() {
            long before = ClientQuestCache.treeRevision();
            QuestIndex index = twoQuests();
            ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

            assertNotEquals(before, ClientQuestCache.treeRevision(),
                    "a tree arrived, so a screen holding an outline built from the previous one has no way "
                            + "to find out -- its outline would keep the rows of a tree the cache has "
                            + "replaced");
        }

        @Test
        @DisplayName("moves on a clear as well, because clearing empties the cache too")
        void movesWhenCleared() {
            // The half that is easy to forget, and the one whose absence has the worst symptom: a
            // disconnect empties the cache, and a screen that seeded an outline at the old revision would
            // go on drawing that tree's rows for a cache with nothing in it.
            QuestIndex index = twoQuests();
            ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
            long loaded = ClientQuestCache.treeRevision();

            ClientQuestCache.clear();

            assertNotEquals(loaded, ClientQuestCache.treeRevision(),
                    "the cache now holds a different tree -- an empty one -- and the revision has to say so");
        }

        @Test
        @DisplayName("moves for a tree that could not be read, since that empties it too")
        void movesForAnUnreadableTree() {
            // A third path out of `acceptTree` that changes what the cache holds, and the one a
            // success-only counter would miss. The tree it had is gone either way; the difference is only
            // in why.
            QuestIndex index = twoQuests();
            ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
            long loaded = ClientQuestCache.treeRevision();

            ClientQuestCache.acceptTree(5, 1, "not json at all".getBytes(StandardCharsets.UTF_8));

            assertNotEquals(loaded, ClientQuestCache.treeRevision(),
                    "the cache was emptied by the failed parse, so a screen must re-seed -- otherwise it "
                            + "draws rows for a tree that is no longer cached");
        }

        @Test
        @DisplayName("moves on a reload, so the authored defaults seed the outline again")
        void movesOnAReload() {
            QuestIndex index = twoQuests();
            QuestSync.treeAsJson(index);

            ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
            long first = ClientQuestCache.treeRevision();
            ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

            assertNotEquals(first, ClientQuestCache.treeRevision(),
                    "a reload means the files changed, and the authored state is the honest one for a tree "
                            + "nobody has seen -- so the revision has to move even when the content is "
                            + "byte-identical, because the unit is 'a tree arrived' and not 'the tree "
                            + "differs'");
        }

        @Test
        @DisplayName("does not move while a screen reads what is cached")
        void standsStillWhileNothingArrives() {
            // The property the counter is *for*: everything a screen does to draw and interact with a
            // tree is a read, and no read may move this. A resize, a scroll, a toggle and a redraw are all
            // in the list below, and every one of them is something a player does constantly.
            QuestIndex index = twoGroupsForRevision();
            ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
            long settled = ClientQuestCache.treeRevision();

            for (int frame = 0; frame < 5; frame++) {
                assertFalse(ClientQuestCache.entries().isEmpty(), "the fixture should have loaded");
                ClientQuestCache.groups();
                ClientQuestCache.chapterTheme("first_steps");
                ClientQuestCache.stateOf("punch_a_tree");
                ClientQuestCache.canClaimFor(UUID.randomUUID(), "punch_a_tree");
                assertEquals(settled, ClientQuestCache.treeRevision(),
                        "frame " + frame + ": reading the cache moved the revision, so a screen that "
                                + "rebuilt its outline from it would reset the player's toggles every "
                                + "frame -- which looks like the toggles not working");
            }
        }

        private static QuestIndex twoGroupsForRevision() {
            return Fixtures.indexOf("""
                    {
                      "version": 1,
                      "chapterGroups": [
                        {
                          "id": "g",
                          "title": "G",
                          "chapters": [
                            {
                              "id": "first_steps",
                              "title": "First Steps",
                              "quests": [
                                { "id": "punch_a_tree", "title": "Punch a Tree",
                                  "tasks": [ { "type": "tenet:checkmark", "title": "t" } ] }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                    """);
        }
    }

    // ------------------------------------------------------------------
    // Progress
    // ------------------------------------------------------------------

    private static TeamProgress progressWith(QuestIndex index, String id, QuestProgress progress) {
        return TeamProgress.empty().put(Fixtures.quest(index, id), progress);
    }

    @Test
    @DisplayName("the raw progress JSON names the fields the reader looks for")
    void progressFieldNamesMatch() {
        QuestIndex index = twoQuests();
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, TeamProgress.empty(), NOW);
        String json = new String(QuestSync.progressAsJson(resolution, TeamProgress.empty(), index),
                StandardCharsets.UTF_8);

        for (String key : List.of("\"quests\"", "\"state\"", "\"tasks\"")) {
            assertTrue(json.contains(key), "the progress JSON has no " + key + " field");
        }
    }

    @Test
    @DisplayName("quest states arrive, so the book can show what is playable")
    void statesArrive() {
        QuestIndex index = twoQuests();
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, TeamProgress.empty(), NOW);

        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(resolution, TeamProgress.empty(), index), CLIENT_TICK);

        assertEquals(QuestState.UNLOCKED, ClientQuestCache.stateOf("punch_a_tree"));
        assertEquals(QuestState.LOCKED, ClientQuestCache.stateOf("make_a_table"));
    }

    @Test
    @DisplayName("who is contributing arrives with the quest, and reaches the client's cache")
    void contributionsArrive() {
        // The whole of the feature's plumbing in one test: the engine's per-member picture, through the
        // JSON the client is sent, into the cache the book draws from. Every part of it was already
        // there except the picture, which the engine computed and threw away.
        QuestIndex index = twoQuests();
        TeamProgress progress = progressWith(index, "punch_a_tree",
                QuestProgress.NONE.recordTask(0, 4));
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, progress, NOW);

        UUID ellio = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID friend = UUID.fromString("00000000-0000-0000-0000-00000000000b");

        // Ordered, as the engine hands it over: the client's delta compares JSON text, so a picture
        // that came out in a different order for the same numbers would re-send the quest forever.
        Map<UUID, Integer> picture = new LinkedHashMap<>();
        picture.put(ellio, 4);
        picture.put(friend, 2);

        dev.ellipog.tenet.progress.ProgressService.Contributors contributors = (questId, taskIndex) ->
                questId.equals("punch_a_tree") && taskIndex == 0 ? picture : Map.of();

        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(resolution, progress, index, contributors), CLIENT_TICK);

        Map<UUID, Integer> arrived = ClientQuestCache.contributorsOf("punch_a_tree", 0);
        assertEquals(4, arrived.get(ellio),
                "the member holding four logs has to arrive holding four");
        assertEquals(2, arrived.get(friend), "and so does the one holding two");
        assertEquals(4, ClientQuestCache.taskProgressOf("punch_a_tree", 0),
                "and the party's own total is unchanged by any of this");
        assertTrue(ClientQuestCache.contributorsOf("punch_a_tree", 1).isEmpty(),
                "a task nobody is carrying anything toward names nobody");
    }

    @Test
    @DisplayName("a completed quest arrives as completed, and unlocks what follows")
    void completionArrives() {
        QuestIndex index = twoQuests();
        TeamProgress progress = progressWith(index, "punch_a_tree",
                QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(true));
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, progress, NOW);

        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(resolution, progress, index), CLIENT_TICK);

        assertEquals(QuestState.COMPLETED, ClientQuestCache.stateOf("punch_a_tree"));
        assertEquals(QuestState.UNLOCKED, ClientQuestCache.stateOf("make_a_table"));
    }

    @Test
    @DisplayName("per-task progress arrives, which is what a 5 / 8 line is drawn from")
    void taskProgressArrives() {
        // The single most useful line in a quest book, and the reason per-task progress is on the
        // wire at all rather than just the quest's state.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\", \"count\": 8} ]}"));
        TeamProgress progress = progressWith(index, "a", QuestProgress.NONE.recordTask(0, 5));
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, progress, NOW);

        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(resolution, progress, index), CLIENT_TICK);

        assertEquals(5, ClientQuestCache.taskProgressOf("a", 0));
        assertEquals(0, ClientQuestCache.taskProgressOf("a", 7), "an unknown task index must read as zero");
        assertEquals(0, ClientQuestCache.taskProgressOf("nonexistent", 0));
    }

    @Test
    @DisplayName("a finished quest with rewards still to collect arrives as claimable, and stops once collected")
    void claimableArrives() {
        // The field the Claim button hangs on, and it was not on the wire at all until now -- which is
        // the same shape of gap as `shape` before it: a value the engine records, `/tenet progress`
        // prints, and no client ever hears about, so the button could not have existed.
        //
        // Both halves are asserted, because a flag that is only ever set is indistinguishable from one
        // that is always true. The collected half is what makes this a test of the guard.
        QuestIndex index = rewardedQuest();
        Quest quest = Fixtures.quest(index, "a");
        // The claim view reads the reward's own team flag from the tree, so the tree must be loaded
        // -- which it always is when the book is open.
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        TeamProgress waiting = TeamProgress.empty().put(quest,
                QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(false));
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(ProgressionEngine.resolve(index, waiting, NOW), waiting, index),
                CLIENT_TICK);

        assertEquals(QuestState.COMPLETED, ClientQuestCache.stateOf("a"), "fixture sanity");
        UUID player = UUID.randomUUID();
        assertTrue(ClientQuestCache.canClaimFor(player, "a"),
                "a finished quest whose rewards nobody has collected must read as claimable on the "
                        + "client, or there is nothing for a Claim button to be drawn from");

        TeamProgress collected = waiting.put(quest, waiting.progressOf(quest)
                .withClaims(dev.ellipog.tenet.progress.QuestClaims.NONE.withPlayerClaim(player, 0)));
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(ProgressionEngine.resolve(index, collected, NOW), collected, index),
                CLIENT_TICK);

        assertFalse(ClientQuestCache.canClaimFor(player, "a"),
                "and not once they have collected their own copy");
        assertTrue(ClientQuestCache.canClaimFor(UUID.randomUUID(), "a"),
                "a teammate still has their own copy to collect -- the whole point of per-player claims");
    }

    @Test
    @DisplayName("the badge counts agree with the button: per quest, per chapter, per player")
    void theBadgeCountsAgreeWithTheButton() {
        // The counts the node badge and the sidebar read come from the same loop `canClaimFor` does, and
        // this is the assertion that keeps them from drifting: a badge saying "3" over a Claim button
        // that refuses, or a chapter row counting a quest the panel does not list, is the kind of
        // mismatch that reads as a broken count rather than as a bug in one of the two.
        QuestIndex index = rewardedQuest();
        Quest quest = Fixtures.quest(index, "a");
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        TeamProgress waiting = TeamProgress.empty().put(quest,
                QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(false));
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(ProgressionEngine.resolve(index, waiting, NOW), waiting, index),
                CLIENT_TICK);

        UUID player = UUID.randomUUID();
        String chapter = ClientQuestCache.entry("a").chapterId();

        assertEquals(1, ClientQuestCache.outstandingRewards(player, "a"),
                "one reward is waiting, and the badge says how many");
        assertTrue(ClientQuestCache.canClaimFor(player, "a"), "and the button is offered for the same quest");
        assertEquals(1, ClientQuestCache.outstandingByQuest(player).get("a"));
        assertEquals(1, ClientQuestCache.claimableByChapter(player).get(chapter),
                "the chapter's row counts the quest, not its rewards");

        TeamProgress collected = waiting.put(quest, waiting.progressOf(quest)
                .withClaims(dev.ellipog.tenet.progress.QuestClaims.NONE.withPlayerClaim(player, 0)));
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(ProgressionEngine.resolve(index, collected, NOW), collected, index),
                CLIENT_TICK);

        assertEquals(0, ClientQuestCache.outstandingRewards(player, "a"));
        assertFalse(ClientQuestCache.canClaimFor(player, "a"), "the count and the button are one loop");
        assertTrue(ClientQuestCache.outstandingByQuest(player).isEmpty(), "no badge for nothing waiting");
        assertTrue(ClientQuestCache.claimableByChapter(player).isEmpty(), "and no count on the chapter row");
        assertEquals(1, ClientQuestCache.outstandingRewards(UUID.randomUUID(), "a"),
                "a teammate's badge is still there -- the counts are per player like the claims");
    }

    @Test
    @DisplayName("a chapter's auto-claim mode rides the wire, and the quest's own wins")
    void autoClaimRoundTrips(@TempDir Path temp) throws IOException {
        // The client needs the effective mode for one thing -- whether a completion announces itself --
        // and it has no chapter record, so the chapter's value travels resolved against the pack setting
        // exactly as the prerequisite default does. The quest's own value travels only when it says one,
        // so the client can tell "this quest chose" from "this quest deferred".
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests.resolve("group/chapter"));
        Files.writeString(quests.resolve("group/group.json"), """
                { "id": "group", "title": "Group", "chapters": ["chapter"] }
                """);
        Files.writeString(quests.resolve("group/chapter/chapter.json"), """
                { "$schema": "../../../_schema/chapter.schema.json",
                  "id": "chapter", "title": "Chapter", "autoClaim": "enabled",
                  "quests": ["quiet.json", "loud.json"] }
                """);
        Files.writeString(quests.resolve("group/chapter/quiet.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "quiet", "title": "Quiet" }
                """);
        Files.writeString(quests.resolve("group/chapter/loud.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "loud", "title": "Loud",
                  "autoClaim": "disabled" }
                """);

        QuestLoader.Result loaded = QuestLoader.load(temp);
        assertTrue(loaded.ok(), () -> "the fixture has to load cleanly:\n"
                + loaded.problems().all().stream().map(problem -> problem.render())
                        .reduce("", (a, b) -> a + "\n" + b));
        QuestIndex index = loaded.index();
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        assertEquals(dev.ellipog.tenet.quest.reward.RewardAutoClaim.ENABLED,
                entryFor("quiet").effectiveAutoClaim(),
                "a quest that says nothing takes the chapter's mode -- the middle rung");
        assertEquals(dev.ellipog.tenet.quest.reward.RewardAutoClaim.DISABLED,
                entryFor("loud").effectiveAutoClaim(),
                "and a quest that says something keeps it");
    }

    @Test
    @DisplayName("an unfinished quest is not claimable, however many rewards it carries")
    void unfinishedIsNotClaimable() {
        // The other half of the guard, and the half a "does it have rewards" shortcut gets wrong: a
        // quest that is merely unlocked and carrying rewards must not offer a Claim button, or the
        // button would be on screen from the moment the quest appeared.
        QuestIndex index = rewardedQuest();
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
        TeamProgress fresh = TeamProgress.empty();

        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(ProgressionEngine.resolve(index, fresh, NOW), fresh, index),
                CLIENT_TICK);

        assertEquals(QuestState.UNLOCKED, ClientQuestCache.stateOf("a"), "fixture sanity");
        assertFalse(ClientQuestCache.canClaimFor(UUID.randomUUID(), "a"));
    }

    /**
     * One quest with a reward, built through the real codecs.
     *
     * <p>A legitimate prize worth having, and deliberately not a consumable one: the item never has to
     * be granted here, because these tests are about what travels on the wire. What matters is that the
     * quest has a non-empty reward list, which is the first clause of the claimable guard.
     */
    private static QuestIndex rewardedQuest() {
        return Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": ["
                        + " { \"type\": \"tenet:checkmark\", \"title\": \"done\"} ],"
                        + " \"rewards\": ["
                        + " { \"type\": \"tenet:item\", \"item\": \"minecraft:wooden_axe\", \"count\": 1} ]}"));
    }

    @Test
    @DisplayName("a stage-locked quest arrives LOCKED and with no claim, whatever the team's progress says")
    void aStageLockedQuestArrivesLocked() {
        // The third per-player overlay on this wire, after the contributors and the claimable flag. A
        // gated quest's stored state is the team's, and whether *this* player may see and collect it is
        // not -- so the client is told locked. (A `claimable` key used to ride this delta as well; it was
        // parsed and read by nothing, so it is gone -- the per-player answer is `canClaimFor` below.)
        QuestIndex index = gatedRewardedQuest();
        Quest quest = Fixtures.quest(index, "a");
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        // Finished with the reward still waiting: exactly the state that must not draw a Claim button
        // for a player the gate has shut out.
        TeamProgress waiting = TeamProgress.empty().put(quest,
                QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(false));
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, waiting, NOW);
        UUID player = UUID.randomUUID();

        byte[] gated = QuestSync.progressDelta(resolution, waiting, index, null,
                (questId, taskIndex) -> Map.of(), java.util.Set.of("a")).json();
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW, gated, CLIENT_TICK);

        assertEquals(QuestState.LOCKED, ClientQuestCache.stateOf("a"),
                "the overlay is the whole of this player's view of the quest");
        assertFalse(ClientQuestCache.canClaimFor(player, "a"),
                "and a quest this player cannot open must not offer them a claim");

        // The control: the same progress with the gate open reads exactly as the stored state does, so
        // the assertions above are about the overlay and not about the fixture.
        byte[] open = QuestSync.progressDelta(resolution, waiting, index, null,
                (questId, taskIndex) -> Map.of(), java.util.Set.of()).json();
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW, open, CLIENT_TICK);

        assertEquals(QuestState.COMPLETED, ClientQuestCache.stateOf("a"),
                "with the stage held, the same progress is the completed quest it always was");
        assertTrue(ClientQuestCache.canClaimFor(player, "a"),
                "and it is claimable again");
    }

    /** The rewarded quest, behind a stage: the fixture the overlay test needs both halves of. */
    private static QuestIndex gatedRewardedQuest() {
        return Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"requiresStage\": \"my_pack:marked\","
                        + " \"tasks\": [ { \"type\": \"tenet:checkmark\", \"title\": \"done\"} ],"
                        + " \"rewards\": ["
                        + " { \"type\": \"tenet:item\", \"item\": \"minecraft:wooden_axe\", \"count\": 1} ]}"));
    }

    // ------------------------------------------------------------------
    // Conditions
    // ------------------------------------------------------------------

    /**
     * One quest whose rows carry conditions: a stage gate, an item gate, and a reward with two.
     *
     * <p>Both display shapes are in here on purpose -- a text gate and an item gate -- because the
     * line a hover shows is built differently for each and only the item one carries a count.
     */
    private static QuestIndex conditionedQuest() {
        return Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": ["
                        + " { \"type\": \"tenet:checkmark\", \"title\": \"done\","
                        + "   \"conditions\": [ { \"type\": \"tenet:stage\", \"stage\": \"my_pack:marked\" } ] },"
                        + " { \"type\": \"tenet:checkmark\", \"title\": \"more\","
                        + "   \"conditions\": [ { \"type\": \"tenet:item\", \"item\": \"minecraft:cobblestone\","
                        + "                       \"count\": 8 } ] } ],"
                        + " \"rewards\": ["
                        + " { \"type\": \"tenet:item\", \"item\": \"minecraft:wooden_axe\", \"count\": 1,"
                        + "   \"conditions\": ["
                        + "     { \"type\": \"tenet:item_tag\", \"tag\": \"minecraft:logs\", \"count\": 8 },"
                        + "     { \"type\": \"tenet:party_size\", \"min\": 2 } ] } ]}"));
    }

    @Test
    @DisplayName("a task's and a reward's conditions cross the wire, and each reads as a sentence")
    void conditionDisplayCrossesTheWire() {
        QuestIndex index = conditionedQuest();
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.Entry entry = ClientQuestCache.entry("a");
        assertNotNull(entry, "the tree must have arrived");

        ClientQuestCache.TaskEntry stageGated = entry.tasks().get(0);
        assertEquals(1, stageGated.conditions().size(), "the task's gate must be on the wire");
        assertEquals("Have the stage my_pack:marked", stageGated.conditions().get(0).line(),
                "a text gate reads as the sentence the key and the subject make");

        ClientQuestCache.TaskEntry itemGated = entry.tasks().get(1);
        assertEquals(1, itemGated.conditions().size());
        assertEquals("Cobblestone \u00d78", itemGated.conditions().get(0).line(),
                "an item gate reads as the item's own name and count, which is the one line that "
                        + "carries its number outside the sentence");

        ClientQuestCache.RewardEntry reward = entry.rewards().get(0);
        assertEquals(2, reward.conditions().size(), "both of the reward's gates");
        assertEquals("Have #minecraft:logs \u00d78", reward.conditions().get(0).line(),
                "a tag gate names the tag and the count in its subject");
        assertEquals("Be in a party of 2 or more", reward.conditions().get(1).line(),
                "and a party gate reads as the sentence its key writes");
    }

    @Test
    @DisplayName("a lock arrives per row, absence reads unlocked, and a locked reward is not offered")
    void conditionLocksArriveAndAbsenceReadsUnlocked() {
        QuestIndex index = conditionedQuest();
        Quest quest = Fixtures.quest(index, "a");
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        // Finished with the reward waiting: the state where a locked row would otherwise offer a
        // Claim button the server refuses.
        TeamProgress waiting = TeamProgress.empty().put(quest,
                QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(false));
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, waiting, NOW);
        UUID player = UUID.randomUUID();

        Map<String, ProgressService.LockView> locks = Map.of("a", new ProgressService.LockView(
                Map.of(0, List.of(0), 1, List.of(0)), Map.of(0, List.of(0, 1))));
        byte[] locked = QuestSync.progressDelta(resolution, waiting, index, null,
                (questId, taskIndex) -> Map.of(), java.util.Set.of(), locks).json();
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW, locked, CLIENT_TICK);

        assertEquals(List.of(0), ClientQuestCache.taskLockOf("a", 0), "the stage gate's row");
        assertEquals(List.of(0), ClientQuestCache.taskLockOf("a", 1), "the item gate's row");
        assertEquals(List.of(0, 1), ClientQuestCache.rewardLockOf("a", 0),
                "both unmet conditions, ascending, because the hover names exactly those");
        assertTrue(ClientQuestCache.taskLockOf("a", 5).isEmpty(),
                "a row the server said nothing about reads unlocked -- absence is the safe default "
                        + "here, the opposite of `claimable`");
        assertFalse(ClientQuestCache.canClaimFor(player, "a"),
                "a reward this player is locked out of is not offered a button");

        // The control, which is also the shape an older server sends: no lock keys at all.
        byte[] none = QuestSync.progressDelta(resolution, waiting, index, null,
                (questId, taskIndex) -> Map.of(), java.util.Set.of(), Map.of()).json();
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW, none, CLIENT_TICK);

        assertTrue(ClientQuestCache.taskLockOf("a", 0).isEmpty(), "nothing is locked without locks");
        assertTrue(ClientQuestCache.canClaimFor(player, "a"),
                "and the claim is offered again -- the two answers come from the same map");
    }

    @Test
    @DisplayName("a lock flip changes the quest's text, and an unchanged picture sends nothing")
    void aLockFlipChangesTheDelta() {
        // The delta is a comparison of the per-quest text, so a lock the client would draw must be in
        // that text or a lock appearing would produce no message at all. The same property makes the
        // refresh affordable: an unchanged picture serialises identically and the delta stays empty.
        QuestIndex index = conditionedQuest();
        Quest quest = Fixtures.quest(index, "a");
        TeamProgress waiting = TeamProgress.empty().put(quest,
                QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(false));
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, waiting, NOW);

        Map<String, ProgressService.LockView> locks = Map.of("a", new ProgressService.LockView(
                Map.of(0, List.of(0)), Map.of()));
        QuestSync.Delta with = QuestSync.progressDelta(resolution, waiting, index, null,
                (questId, taskIndex) -> Map.of(), java.util.Set.of(), locks);
        QuestSync.Delta without = QuestSync.progressDelta(resolution, waiting, index, null,
                (questId, taskIndex) -> Map.of(), java.util.Set.of(), Map.of());
        assertNotEquals(new String(with.json(), StandardCharsets.UTF_8),
                new String(without.json(), StandardCharsets.UTF_8),
                "a lock must be visible in the text the delta is made of");

        QuestSync.Delta settled = QuestSync.progressDelta(resolution, waiting, index, with.snapshot(),
                (questId, taskIndex) -> Map.of(), java.util.Set.of(), locks);
        assertEquals(0, questsOf(settled.json()).size(),
                "the same picture again must produce nothing to send, or the refresh would resend "
                        + "every quest every second: " + new String(settled.json(), StandardCharsets.UTF_8));
    }

    private static JsonObject questsOf(byte[] json) {
        return JsonParser.parseString(new String(json, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("quests");
    }

    @Test
    @DisplayName("a state name the client does not know reads as LOCKED rather than throwing")
    void unknownStateIsSafe() {
        // Happens when the client and server are different versions. LOCKED is the safe reading and
        // the honest one: the client cannot show progress it does not understand, and showing it as
        // complete would be a lie in the more dangerous direction.
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                "{\"quests\":{\"a\":{\"state\":\"TELEPORTED\",\"tasks\":[]}}}".getBytes(StandardCharsets.UTF_8),
                CLIENT_TICK);

        assertEquals(QuestState.LOCKED, ClientQuestCache.stateOf("a"));
    }

    @Test
    @DisplayName("an unknown quest reads as LOCKED, so a stale client does not show a phantom")
    void unknownQuestIsSafe() {
        assertEquals(QuestState.LOCKED, ClientQuestCache.stateOf("never_heard_of_it"));
    }

    @Test
    @DisplayName("the team id arrives, including the awkward one")
    void teamIdArrives() {
        UUID team = UUID.fromString("3f2a1b4c-5d6e-7f80-9a0b-1c2d3e4f5061");
        byte[] progress = "{\"quests\":{}}".getBytes(StandardCharsets.UTF_8);

        ClientQuestCache.acceptProgress(team, NOW, progress, CLIENT_TICK);
        assertEquals(team, ClientQuestCache.teamId().orElseThrow());

        // All-ones: both halves are -1 as signed longs, which is the case a hand-rolled split gets
        // wrong. The payload carries the bits and the reader rebuilds from them.
        UUID awkward = new UUID(-1L, -1L);
        ClientQuestCache.acceptProgress(awkward, NOW, progress, CLIENT_TICK);
        assertEquals(awkward, ClientQuestCache.teamId().orElseThrow());
    }

    // ------------------------------------------------------------------
    // Cooldowns
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a cooldown counts down on the client, from the tick it arrived at")
    void cooldownCountsDown() {
        // The server sends "500 ticks left"; the client subtracts its own elapsed ticks. Nothing
        // further goes over the wire, which is the point -- a packet per second per cooling quest
        // would be absurd.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                q("daily").repeatable(true).repeatCooldownTicks(600).build()));
        TeamProgress progress = progressWith(index, "daily",
                QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(true));

        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, progress, NOW + 100);
        assertEquals(500, resolution.cooldownOf(Fixtures.quest(index, "daily")), "fixture sanity");

        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW + 100,
                QuestSync.progressAsJson(resolution, progress, index), CLIENT_TICK);

        assertEquals(500, ClientQuestCache.cooldownOf("daily", CLIENT_TICK),
                "the cooldown did not survive the wire");
        assertEquals(400, ClientQuestCache.cooldownOf("daily", CLIENT_TICK + 100),
                "100 client ticks later, 100 fewer should remain");
        assertEquals(0, ClientQuestCache.cooldownOf("daily", CLIENT_TICK + 5_000),
                "a cooldown that has run out must not go negative");
    }

    @Test
    @DisplayName("a quest with no cooldown reports none, rather than inventing one")
    void noCooldown() {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(q("once").build()));
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, TeamProgress.empty(), NOW);

        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(resolution, TeamProgress.empty(), index), CLIENT_TICK);

        assertEquals(0, ClientQuestCache.cooldownOf("once", CLIENT_TICK));
    }

    @Test
    @DisplayName("the completion count arrives, and an unrepeated quest sends none")
    void timesCompletedArrives() {
        // Sparse like the cooldown: absent means never repeated, which reads as zero rather than
        // inventing a history. The card's count and the command's read this same number.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                q("daily").repeatable(true).repeatCooldownTicks(600).build(),
                q("once").build()));
        TeamProgress progress = progressWith(index, "daily",
                QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(true).repeatRoundOver(NOW));
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, progress, NOW);

        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(resolution, progress, index), CLIENT_TICK);

        assertEquals(1, ClientQuestCache.timesCompletedOf("daily"),
                "the count did not survive the wire");
        assertEquals(0, ClientQuestCache.timesCompletedOf("once"),
                "an unrepeated quest reports zero, not a missing value");

        String json = new String(QuestSync.progressAsJson(resolution, progress, index),
                StandardCharsets.UTF_8);
        assertTrue(json.contains("\"timesCompleted\":1"), "the count should travel as a number");
    }

    // ------------------------------------------------------------------
    // Clearing and failure
    // ------------------------------------------------------------------

    @Test
    @DisplayName("clearing forgets the tree, the progress and the team")
    void clearForgetsEverything() {
        // What a disconnect does. Without it, leaving one server and joining another shows the first
        // server's questline until the new sync arrives -- which looks exactly like a sync failure.
        QuestIndex index = twoQuests();
        TeamProgress empty = TeamProgress.empty();
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(ProgressionEngine.resolve(index, empty, NOW), empty, index),
                CLIENT_TICK);

        assertTrue(ClientQuestCache.hasData(), "fixture sanity: there should be something to clear");

        ClientQuestCache.clear();

        assertFalse(ClientQuestCache.hasData());
        assertFalse(ClientQuestCache.hasTree(), "a cleared cache must not claim to have a tree");
        assertEquals(0, ClientQuestCache.questCount());
        assertEquals(0, ClientQuestCache.entries().size());
        assertTrue(ClientQuestCache.teamId().isEmpty());
        assertEquals(QuestState.LOCKED, ClientQuestCache.stateOf("punch_a_tree"));
    }

    @Test
    @DisplayName("a malformed tree leaves the cache empty rather than half-filled")
    void malformedTreeIsDiscarded() {
        // A half-parsed tree would render as a quest list with entries missing in the middle and
        // nothing saying why, which is the hardest kind of bug to report.
        ClientQuestCache.acceptTree(5, 1, "not json at all".getBytes(StandardCharsets.UTF_8));

        assertFalse(ClientQuestCache.hasData());
        assertEquals(0, ClientQuestCache.entries().size());
    }

    @Test
    @DisplayName("a malformed progress payload leaves the states empty rather than throwing")
    void malformedProgressIsDiscarded() {
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                "}{ not json".getBytes(StandardCharsets.UTF_8), CLIENT_TICK);

        assertEquals(QuestState.LOCKED, ClientQuestCache.stateOf("anything"));
    }

    @Test
    @DisplayName("an item the client does not have resolves to nothing, not to a crash")
    void unknownItemResolvesToNothing() {
        // A client missing a mod the server has is a normal situation. The alternative -- throwing
        // inside a payload handler -- disconnects the player over a missing icon.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": [ {\"type\": \"tenet:item\", "
                        + "\"item\": \"someothermod:widget\", \"count\": 1} ]}"));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        ClientQuestCache.TaskEntry task = entryFor("a").tasks().get(0);
        assertFalse(task.hasItem(), "an uninstalled mod's item should resolve to nothing");
        // And the row still renders something rather than a blank line.
        assertFalse(task.text().getString().isEmpty(), "a task with no item must still have a label");
    }

    // ------------------------------------------------------------------
    // Chapter gates
    // ------------------------------------------------------------------

    /**
     * Two chapters, the second waiting on the first and hidden until its gate is met.
     *
     * <p>The same shape {@code ChapterDependencyTest} and {@code ProgressionEngineTest} use, built through
     * the v1 fixture so this file keeps testing the wire rather than the loader — the index is only the
     * input here, and what is under test is that the gate crosses it.
     */
    private static QuestIndex twoGatedChapters() {
        return Fixtures.indexOf(Fixtures.fileWithChapters(
                Fixtures.chapterWith("first", "\"completesWhen\": [\"a\"],",
                        "{\"id\": \"a\", \"title\": \"A\"}"),
                Fixtures.chapterWith("second",
                        "\"dependsOn\": [\"first\"], \"prerequisiteMode\": \"one_started\","
                                + " \"minRequired\": 1, \"hideUntilDependenciesComplete\": true,",
                        "{\"id\": \"b\", \"title\": \"B\"}")));
    }

    @Test
    @DisplayName("a chapter's own gate travels, and the reader holds it under the same names")
    void chapterGatesTravel() {
        // The two ends are hand-written, so the field names are exactly what they can disagree about --
        // which is why this asserts the raw JSON as well as the round trip. A gate that reached the
        // client as nothing would draw every chapter as open, and the author's hiding flag with it.
        QuestIndex index = twoGatedChapters();
        JsonObject root = JsonParser.parseString(
                new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray chapters = root.getAsJsonArray("chapters");

        JsonObject first = chapters.get(0).getAsJsonObject();
        assertEquals("first", first.get("id").getAsString());
        assertFalse(first.has("dependsOn"), "a chapter that waits on nothing sends no list at all");
        assertEquals("all_completed", first.get("prerequisiteMode").getAsString(),
                "the gate's mode is always sent: nothing sits above a chapter for it to inherit from");
        assertEquals(0, first.get("minRequired").getAsInt());
        assertFalse(first.has("hideUntilDependenciesComplete"),
                "absent is the ordinary case: shown, and drawable");

        JsonObject second = chapters.get(1).getAsJsonObject();
        assertEquals("first", second.getAsJsonArray("dependsOn").get(0).getAsString());
        assertEquals("one_started", second.get("prerequisiteMode").getAsString());
        assertEquals(1, second.get("minRequired").getAsInt());
        assertTrue(second.get("hideUntilDependenciesComplete").getAsBoolean(),
                "the author's hiding flag crosses, or the reader cannot honour it");

        // And the reader. Absent keys read as the same defaults the file gives, so this also pins what a
        // version-11 tree means.
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
        ClientQuestCache.ChapterEntry readFirst = ClientQuestCache.chapters().get(0);
        assertFalse(readFirst.waits(), "no dependencies, so nothing to wait for");
        assertEquals(dev.ellipog.tenet.quest.PrerequisiteMode.ALL_COMPLETED, readFirst.prerequisiteMode());
        assertFalse(readFirst.hideUntilDependenciesComplete());

        ClientQuestCache.ChapterEntry readSecond = ClientQuestCache.chapters().get(1);
        assertEquals(List.of("first"), readSecond.dependsOn());
        assertEquals(dev.ellipog.tenet.quest.PrerequisiteMode.ONE_STARTED, readSecond.prerequisiteMode());
        assertEquals(1, readSecond.minRequired());
        assertTrue(readSecond.hideUntilDependenciesComplete(), "and the hiding flag survived the trip");
    }

    @Test
    @DisplayName("chapter states ride the progress channel, and an unknown chapter reads as open")
    void chapterStatesArrive() {
        // The other half of a chapter gate: the tree says what a chapter waits for, and this says how far
        // it has got. Computed on the server because the client's own view of the canvas is filtered --
        // an author sees hidden quests and a reader does not -- so a chapter state derived here would
        // disagree with the server's for the person most likely to notice.
        QuestIndex index = twoGatedChapters();

        TeamProgress empty = TeamProgress.empty();
        byte[] full = QuestSync.progressAsJson(ProgressionEngine.resolve(index, empty, NOW), empty, index);
        String raw = new String(full, StandardCharsets.UTF_8);
        assertTrue(raw.contains("\"chapters\""),
                "the chapter states have to be in the message at all: " + raw);
        JsonObject written = JsonParser.parseString(raw).getAsJsonObject().getAsJsonObject("chapters");
        assertEquals("UNLOCKED", written.get("first").getAsString(), raw);
        assertEquals("LOCKED", written.get("second").getAsString(),
                "the same uppercase spelling the per-quest state beside it uses: " + raw);
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW, full, CLIENT_TICK);

        assertEquals(QuestState.UNLOCKED, ClientQuestCache.chapterStateOf("first"),
                "'a' is not started, so its chapter is open with nothing done");
        assertEquals(QuestState.LOCKED, ClientQuestCache.chapterStateOf("second"),
                "and the chapter waiting on it is shut");
        assertEquals(QuestState.UNLOCKED, ClientQuestCache.chapterStateOf("never_heard_of_it"),
                "an unknown chapter reads as open, which is the reading that hides least");

        // Starting the milestone moves the chapter, and the chapter's own state follows it in the same
        // message -- which is what makes the sidebar row appear on the tick the gate is met.
        TeamProgress touched = TeamProgress.empty()
                .put(Fixtures.quest(index, "a"), QuestProgress.NONE.recordTask(0, 1));
        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW,
                QuestSync.progressAsJson(ProgressionEngine.resolve(index, touched, NOW), touched, index),
                CLIENT_TICK);

        assertEquals(QuestState.STARTED, ClientQuestCache.chapterStateOf("first"));
        assertEquals(QuestState.UNLOCKED, ClientQuestCache.chapterStateOf("second"),
                "one_started asks for a touch, and it has one");
    }

    @Test
    @DisplayName("a version-11 tree and a progress message with no chapters leave every chapter open")
    void olderServersHideNothing() {
        // The additive rule, asserted the way it matters: a server that has never heard of chapter gates
        // sends neither the tree fields nor the progress map, and the honest reading of both silences is
        // that every chapter is open -- which is exactly what this client did before the feature.
        ClientQuestCache.acceptTree(1, 1, """
                {"version":11,"groups":[],"chapters":[{"id":"chapter","groupId":"","title":"Chapter",
                 "icon":""}],"quests":[]}
                """.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, ClientQuestCache.chapters().size(), "the chapter list itself still arrives");
        assertFalse(ClientQuestCache.chapters().get(0).waits(), "and it waits on nothing");

        ClientQuestCache.acceptProgress(UUID.randomUUID(), NOW, """
                {"version":1,"quests":{}}
                """.getBytes(StandardCharsets.UTF_8), CLIENT_TICK);

        assertEquals(QuestState.UNLOCKED, ClientQuestCache.chapterStateOf("chapter"),
                "no chapter map means every chapter is open, not shut");
    }

    @Test
    @DisplayName("the chapter's reveal defaults reach the wire as one resolved boolean per quest")
    void revealDefaultsResolvePerQuest() {
        // The chapter says what its quests do by default; a quest that says nothing inherits, and a quest
        // that says `false` really does opt out -- which is the whole reason the two fields are three-state
        // rather than booleans. Resolved on the side that writes the wire, so the client reads one answer
        // and owns no ladder of its own.
        QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapters(
                Fixtures.chapterWith("chapter",
                        "\"defaultHideUntilDependenciesComplete\": true,"
                                + " \"defaultHideUntilDependenciesVisible\": true,",
                        Fixtures.q("silent").build(),
                        Fixtures.q("opted_out").hideUntilDependenciesComplete(false)
                                .hideUntilDependenciesVisible(false).build(),
                        Fixtures.q("opted_in").hideUntilDependenciesComplete(true).build())));

        byte[] tree = QuestSync.treeAsJson(index);

        assertTrue(revealFlag(tree, "silent", "hideUntilDependenciesComplete"),
                "a quest that says nothing takes the chapter's default");
        assertTrue(revealFlag(tree, "silent", "hideUntilDependenciesVisible"));
        assertFalse(revealFlag(tree, "opted_out", "hideUntilDependenciesComplete"),
                "and one that says false overrides a chapter default of true");
        assertFalse(revealFlag(tree, "opted_out", "hideUntilDependenciesVisible"));
        assertTrue(revealFlag(tree, "opted_in", "hideUntilDependenciesComplete"));
        assertTrue(revealFlag(tree, "opted_in", "hideUntilDependenciesVisible"),
                "a quest with no opinion on the *other* flag still inherits that one");

        // A chapter that says nothing sends false for both, which is what every pack written before these
        // fields existed means by saying nothing.
        byte[] plain = QuestSync.treeAsJson(twoGatedChapters());
        assertFalse(revealFlag(plain, "b", "hideUntilDependenciesComplete"));
        assertFalse(revealFlag(plain, "b", "hideUntilDependenciesVisible"));

        // And the chapter's own record does not carry them. They are authoring fields whose only effect is
        // the per-quest value above, so the tree grows no keys and TREE_VERSION does not move -- if that
        // ever changes, this fails and whoever changed it has to decide about the version.
        JsonObject chapter = JsonParser.parseString(new String(tree, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("chapters").get(0).getAsJsonObject();
        assertFalse(chapter.has("defaultHideUntilDependenciesComplete"),
                "the reveal defaults are resolved per quest rather than sent as chapter state");
        assertFalse(chapter.has("defaultHideUntilDependenciesVisible"));
        assertEquals(QuestSync.TREE_VERSION, JsonParser.parseString(new String(tree, StandardCharsets.UTF_8))
                        .getAsJsonObject().get("version").getAsInt(),
                "fixture sanity: this is the tree this build writes");
    }

    /** One reveal flag of one quest, off the tree as raw JSON. */
    private static boolean revealFlag(byte[] tree, String questId, String key) {
        JsonArray quests = JsonParser.parseString(new String(tree, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("quests");
        for (JsonElement element : quests) {
            JsonObject one = element.getAsJsonObject();
            if (one.get("id").getAsString().equals(questId)) {
                return one.get(key).getAsBoolean();
            }
        }
        throw new AssertionError("no quest " + questId + " in the tree");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static ClientQuestCache.Entry entryFor(String questId) {
        return ClientQuestCache.entries().stream()
                .filter(entry -> entry.id().equals(questId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry for " + questId + " in "
                        + ClientQuestCache.entries().stream().map(ClientQuestCache.Entry::id).toList()));
    }
}
