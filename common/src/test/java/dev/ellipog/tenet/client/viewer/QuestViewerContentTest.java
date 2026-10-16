package dev.ellipog.tenet.client.viewer;

import dev.ellipog.tenet.client.viewer.PagePalette;
import dev.ellipog.tenet.client.viewer.QuestContent;
import dev.ellipog.tenet.client.viewer.QuestPage;
import dev.ellipog.tenet.client.viewer.QuestRef;
import dev.ellipog.tenet.client.viewer.QuestRow;
import dev.ellipog.tenet.client.ClientLocale;
import dev.ellipog.tenet.client.ClientQuestCache;
import dev.ellipog.tenet.net.QuestSync;
import dev.ellipog.tenet.quest.Fixtures;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestIndex;

import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The content Tenet hands the viewer seam: what the snapshot holds, what the live reads say, and
 * the rows a page is allowed to show.
 *
 * <h2>What a page shows, and why the tests say so</h2>
 *
 * <p>A viewer page carries item references — item tasks, tag tasks, item rewards. Text tasks are
 * dropped (a checkmark has no item to look up), a quest with no item rows has no page at all, and a
 * row keeps the index of the source list it came from so live reads do not read the wrong row after
 * filtering. Each of those has a test here, because each is a decision rather than an accident.
 *
 * <h2>Why the tag resolver is a parameter</h2>
 *
 * <p>{@code #minecraft:logs} becoming the items a viewer can find is the whole reason a tag task is
 * visible in an item lookup, and a test JVM loads no datapack — {@code BuiltInRegistries.ITEM.getTag}
 * answers empty for every tag there. So {@link QuestViewerContent#rebuild} takes the resolver, and
 * these tests hand it a fake while production hands it the registry.
 */
@DisplayName("Tenet's viewer content")
class QuestViewerContentTest {

    private static final String ONE_QUEST = """
            {"id": "tree", "title": "Punch a tree", "tasks": [
              {"type": "tenet:item", "item": "minecraft:oak_log", "count": 8},
              {"type": "tenet:item_tag", "tag": "minecraft:logs", "count": 4},
              {"type": "tenet:checkmark", "title": "Read the sign"},
              {"type": "tenet:item", "item": "tenet:does_not_exist", "count": 1}],
             "rewards": [{"type": "tenet:item", "item": "minecraft:diamond", "count": 1}]}
            """;

    private static final String TEXT_ONLY = """
            {"id": "talk", "title": "Just talk", "tasks": [
              {"type": "tenet:checkmark", "title": "Say hello"}],
             "rewards": [{"type": "tenet:xp", "amount": 5}]}
            """;

    private static final String TWO_QUESTS = """
            {"id": "tree", "title": "Punch a tree", "tasks": [
              {"type": "tenet:item", "item": "minecraft:oak_log", "count": 8}]}
            """;

    private static final String SECOND_QUEST = """
            {"id": "craft", "title": "Craft a table", "dependsOn": ["tree"], "tasks": [
              {"type": "tenet:item", "item": "minecraft:oak_planks", "count": 4}]}
            """;

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.boot();
    }

    @BeforeEach
    @AfterEach
    void clearCache() {
        ClientQuestCache.clear();
        QuestBookFocus.clear();
        // The language too: a locale left by one test would translate the next one's title, which
        // would pass without the tree having carried anything.
        ClientLocale.clear();
    }

    private static void accept(String... quests) {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(quests));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
    }

    private static List<String> ids(List<QuestRef> refs) {
        return refs.stream().map(QuestRef::id).toList();
    }

    /**
     * A holder set for the oak-log holder, built to order.
     *
     * <p>Built on first use rather than in a field initialiser: the item registry is not populated until
     * {@code MinecraftTestBootstrap.boot()} has run, and a static initialiser runs before {@code @BeforeAll} —
     * which made an earlier version of this class fail to initialise at all.
     *
     * <p>Two calls with different {@code times} give different objects, and that was **measured rather than
     * assumed**: a probe confirmed {@code HolderSet.direct} does not cache, and that two sets with equal
     * members are {@code equals} but not {@code ==} — which is the whole reason this mechanism compares by
     * identity rather than by equality.
     */
    private static Optional<net.minecraft.core.HolderSet<Item>> oakLogSet(int times) {
        // `getHolderOrThrow` is gone; the id lookup hands back the reference directly.
        var holder = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(Identifier.parse("minecraft:oak_log")).orElseThrow();
        java.util.List<net.minecraft.core.Holder<Item>> holders = new java.util.ArrayList<>();
        for (int i = 0; i < times; i++) {
            holders.add(holder);
        }
        return Optional.of(net.minecraft.core.HolderSet.direct(holders));
    }

    @Test
    @DisplayName("a reload that changes a tag's holder set rebuilds the snapshot, and one that does not, does not")
    void aTagThatMovedRebuildsTheSnapshot() {
        // The input the tree revision does not carry, and the reason this mechanism exists.
        //
        // `#minecraft:logs` becoming the items a viewer can find is a function of the client's **item tags**,
        // which are synced from a datapack rather than from this mod's files. A `/reload` that changes tag
        // membership moves no quest revision at all — so before this, a quest gated on a tag went on being
        // findable from a member the pack had removed, and stayed unfindable from one it had added, until the
        // player relogged. Nothing logged and nothing looked wrong.
        accept(ONE_QUEST);
        QuestViewerContent content = new QuestViewerContent();
        TagKey<Item> logs = TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                Identifier.parse("minecraft:logs"));

        Optional<net.minecraft.core.HolderSet<Item>> stable = oakLogSet(1);
        Optional<net.minecraft.core.HolderSet<Item>> replaced = oakLogSet(2);

        // The fixture's own sanity, asserted **first** and on purpose. An earlier version of this test
        // assumed two calls gave two objects without checking, so when the mechanism appeared not to fire
        // there was no way to tell "the comparison is wrong" from "the two sets were one object" — and four
        // attempts were spent on that ambiguity. This assertion removes it.
        assertNotSame(stable.get(), replaced.get(),
                "the fixture's two sets must be different objects, or this test proves nothing");

        content.setTagHolders(tag -> stable);
        content.tick();
        assertEquals(1, content.pages().size(), "the tag task's quest has a page");
        assertTrue(content.watchedTags().containsKey(logs),
                "and the tag the walk expanded is the one being watched");
        assertFalse(content.tagsMoved(), "and the snapshot was built from the set in force");

        content.tick();
        assertFalse(content.tagsMoved(), "a tick with the same set has nothing to rebuild for");
        assertEquals(1, content.pages().size(), "and nothing was rebuilt");

        // The holder set is replaced, which is what binding tags after a reload does. The next tick has to
        // notice without the tree revision having moved -- which is the whole defect. The decision is asserted
        // before the tick, so a failure says which of the two is wrong.
        content.setTagHolders(tag -> replaced);
        assertTrue(content.tagsMoved(),
                "a tag now read from a different holder set has moved, with the tree untouched");
        content.tick();

        // **The observable is `tagsMoved()` and not `revision()`, and that is the point.** `revision()`
        // reports the *tree* revision the snapshot was built against, and a tag change does not move it — so a
        // test asserting "the revision changed" can never pass, however well the mechanism works. That is what
        // this test got wrong for four attempts, and it is worth stating because the wrong observable looked
        // entirely reasonable. What a rebuild does is take the set now in force as the new baseline, so the
        // honest reading is that the watch has settled and holds the set the snapshot came from.
        assertFalse(content.tagsMoved(),
                "and the snapshot was rebuilt for it, so the new set is now the baseline");
        assertEquals(replaced.get(), content.watchedTags().get(logs),
                "the watch holds the set the snapshot was actually built from");

        // And a tag that has gone away is a change too: a reload that removes a tag must not leave a snapshot
        // serving the members it used to have.
        content.setTagHolders(tag -> Optional.empty());
        assertTrue(content.tagsMoved(), "a tag that is no longer declared has moved");
        content.tick();
        assertFalse(content.tagsMoved(), "and the rebuild settled that too");
        assertFalse(content.watchedTags().containsKey(logs),
                "a tag that is no longer declared is no longer watched");
    }

    @Test
    @DisplayName("the page carries the item tasks, the tag task and the item reward -- and nothing text-only")
    void thePageCarriesItemRowsOnly() {
        accept(ONE_QUEST);
        QuestViewerContent content = new QuestViewerContent();
        content.tick();

        assertEquals(1, content.pages().size());
        QuestPage page = content.pages().get(0);
        assertEquals("tree", page.quest().id());
        assertEquals(3, page.tasks().size(),
                "the item task, the tag task and the missing item keep their rows; the checkmark does not");
        assertEquals(1, page.rewards().size());

        QuestRow itemTask = page.tasks().get(0);
        assertEquals("Oak Log", itemTask.label(), "an item task reads as its item's name");
        assertEquals(8, itemTask.need());
        assertEquals(0, itemTask.sourceIndex(), "and remembers where it came from");
        assertFalse(itemTask.done(), "a fresh snapshot has no progress on it");

        assertEquals(1, page.tasks().get(1).sourceIndex(),
                "the tag task is the second entry of the source list");
        assertTrue(page.tasks().get(1).hasTag());

        QuestRow missing = page.tasks().get(2);
        assertEquals(3, missing.sourceIndex(), "the missing item is the fourth entry, after the checkmark");
        assertTrue(missing.icon().isEmpty(), "an item this build does not have draws no icon");
        assertEquals("tenet:does_not_exist", missing.label(),
                "so its id is the label -- the book's keep-and-mark rule, on a viewer page");

        assertEquals(List.of("tree"), ids(content.index().questsUsing(Identifier.parse("minecraft:oak_log"))));
        assertEquals(List.of("tree"), ids(content.index().questsAwarding(Identifier.parse("minecraft:diamond"))));
        assertTrue(content.index().questsUsing(Identifier.parse("minecraft:diamond")).isEmpty(),
                "a reward is not a use");
    }

    @Test
    @DisplayName("a quest with only text rows has no page, and no lookup")
    void aTextOnlyQuestHasNoPage() {
        accept(TEXT_ONLY);
        QuestViewerContent content = new QuestViewerContent();
        content.tick();

        assertTrue(content.pages().isEmpty(),
                "nothing could lead a player to it, and a blank page is worse than none");
        assertTrue(content.index().isEmpty());
    }

    @Test
    @DisplayName("a quest hidden from recipe viewers has no page, and no lookup")
    void aHiddenQuestHasNoPage() {
        // the legacy `disable_recipe_mod`, resolved server-side against the file's default and
        // carried as `hideFromViewers` — the client holds no file record, so the wire carries the
        // answer rather than the question.
        accept("""
                {"id": "shown", "title": "Shown", "tasks": [
                  {"type": "tenet:item", "item": "minecraft:oak_log", "count": 8}]}""",
                """
                {"id": "hidden", "title": "Hidden", "disableRecipeMod": true, "tasks": [
                  {"type": "tenet:item", "item": "minecraft:oak_log", "count": 8}]}""");
        QuestViewerContent content = new QuestViewerContent();
        content.tick();

        assertTrue(ClientQuestCache.entry("hidden").hideFromViewers(),
                "the resolved answer survives the wire");
        assertFalse(ClientQuestCache.entry("shown").hideFromViewers(),
                "absence means shown, which is what every old tree says");
        assertEquals(1, content.pages().size(), "the hidden quest never becomes a page");
        assertEquals("shown", content.pages().get(0).quest().id());
        assertNull(content.page("hidden"), "and no id lookup finds it either");
        assertEquals(List.of("shown"),
                ids(content.index().questsUsing(Identifier.parse("minecraft:oak_log"))),
                "and no item lookup leads to it");
    }

    @Test
    @DisplayName("a tag task keeps its tag and finds its members through the resolver")
    void aTagTaskCarriesItsTagAndExpandsIt() {
        accept(ONE_QUEST);
        QuestViewerContent content = new QuestViewerContent();
        content.rebuild(ClientQuestCache.treeRevision(), QuestViewerContentTest::expanded,
                tag -> List.of());

        QuestRow tagRow = content.pages().get(0).tasks().get(1);
        assertTrue(tagRow.hasTag(), "the row must say which tag it is about");
        assertEquals("minecraft:logs", tagRow.tagId());

        // The whole point: an item that is a member of the tag finds the quest. A test JVM has no
        // datapack, so the resolver is the fake above; production reads the same shape from the synced
        // client registry.
        assertEquals(List.of("tree"),
                ids(content.index().questsUsing(Identifier.parse("minecraft:birch_log"))));
        assertTrue(content.index().questsUsing(Identifier.parse("minecraft:stone")).isEmpty(),
                "an item outside the tag does not");
    }

    /** The fake datapack: {@code #minecraft:logs} holds two items, everything else is empty. */
    private static List<Identifier> expanded(TagKey<Item> tag) {
        if (!tag.location().toString().equals("minecraft:logs")) {
            return List.of();
        }
        return List.of(Identifier.parse("minecraft:oak_log"),
                Identifier.parse("minecraft:birch_log"));
    }

    private static final String BREAK_QUEST = """
            {"id": "quarry", "title": "Quarry", "tasks": [
              {"type": "tenet:break", "block": "minecraft:stone", "value": 4}]}
            """;

    private static final String BREAK_TAG_QUEST = """
            {"id": "timber", "title": "Timber", "tasks": [
              {"type": "tenet:break", "blockTag": "minecraft:logs", "value": 4}]}
            """;

    @Test
    @DisplayName("a break task keeps its sentence and is found from its block's item")
    void breakTaskIsIndexedByItsBlock() {
        accept(BREAK_QUEST);
        QuestViewerContent content = new QuestViewerContent();
        content.rebuild(ClientQuestCache.treeRevision(), QuestViewerContentTest::expanded,
                tag -> List.of());

        assertEquals(1, content.pages().size(), "a break quest has a page: stone names an item");
        QuestRow row = content.pages().get(0).tasks().get(0);
        assertEquals(0, row.sourceIndex());
        assertEquals(4, row.need());
        assertFalse(row.hasTag(), "a block is not a tag, and no adapter may read it as one");
        assertFalse(row.icon().isEmpty(), "the row wears the block's own item");

        assertEquals(List.of("quarry"),
                ids(content.index().questsUsing(Identifier.parse("minecraft:stone"))));
        assertTrue(content.index().questsUsing(Identifier.parse("minecraft:dirt")).isEmpty(),
                "another block leads nowhere");
    }

    @Test
    @DisplayName("a break by block tag finds its members through the block resolver")
    void breakTagExpandsToMemberItems() {
        accept(BREAK_TAG_QUEST);
        QuestViewerContent content = new QuestViewerContent();
        content.rebuild(ClientQuestCache.treeRevision(), QuestViewerContentTest::expanded,
                QuestViewerContentTest::blockExpanded);

        assertEquals(1, content.pages().size());
        QuestRow row = content.pages().get(0).tasks().get(0);
        assertFalse(row.hasTag(), "a block tag is not an item tag, so the row carries none");

        assertEquals(List.of("timber"),
                ids(content.index().questsUsing(Identifier.parse("minecraft:oak_log"))),
                "a member's item finds the quest");
        assertTrue(content.index().questsUsing(Identifier.parse("minecraft:stone")).isEmpty(),
                "an item outside the tag does not");
    }

    /** The fake datapack's block side: {@code #minecraft:logs} holds two blocks. */
    private static List<Identifier> blockExpanded(TagKey<Block> tag) {
        if (!tag.location().toString().equals("minecraft:logs")) {
            return List.of();
        }
        return List.of(Identifier.parse("minecraft:oak_log"),
                Identifier.parse("minecraft:birch_log"));
    }

    @Test
    @DisplayName("a block-tag change the tree does not carry rebuilds the snapshot")
    void movedBlockTagRebuildsTheSnapshot() {
        // The input the tree revision does not carry, on the block side: a /reload that changes
        // block-tag membership moves no quest revision either, so the watch item tags have gets a
        // block-tag sibling. Same shape as the item-tag test above, against the block registry.
        accept(BREAK_TAG_QUEST);
        QuestViewerContent content = new QuestViewerContent();
        TagKey<Block> logs = TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                Identifier.parse("minecraft:logs"));

        var oak = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .get(Identifier.parse("minecraft:oak_log")).orElseThrow();
        Optional<net.minecraft.core.HolderSet<Block>> stable =
                Optional.of(net.minecraft.core.HolderSet.direct(List.of(oak)));
        Optional<net.minecraft.core.HolderSet<Block>> replaced =
                Optional.of(net.minecraft.core.HolderSet.direct(List.of(oak)));
        assertNotSame(stable.get(), replaced.get(),
                "the fixture's two sets must be different objects, or this test proves nothing");

        content.setBlockTagHolders(tag -> stable);
        content.tick();
        assertEquals(1, content.pages().size(), "the break quest has a page");
        assertTrue(content.watchedBlockTags().containsKey(logs),
                "and the block tag the walk expanded is the one being watched");
        assertFalse(content.blockTagsMoved(), "and the snapshot was built from the set in force");

        content.tick();
        assertFalse(content.blockTagsMoved(), "a tick with the same set has nothing to rebuild for");

        content.setBlockTagHolders(tag -> replaced);
        assertTrue(content.blockTagsMoved(),
                "a block tag now read from a different holder set has moved, with the tree untouched");
        content.tick();
        assertFalse(content.blockTagsMoved(),
                "and the snapshot was rebuilt for it, so the new set is now the baseline");
        assertEquals(replaced.get(), content.watchedBlockTags().get(logs),
                "the watch holds the set the snapshot was actually built from");

        content.setBlockTagHolders(tag -> Optional.empty());
        assertTrue(content.blockTagsMoved(), "a block tag that is no longer declared has moved");
        content.tick();
        assertFalse(content.blockTagsMoved(), "and the rebuild settled that too");
        assertFalse(content.watchedBlockTags().containsKey(logs),
                "a block tag that is no longer declared is no longer watched");
    }

    /** Two tasks gated on one tag, which is what makes the expansion worth doing once. */
    private static final String TWO_TAG_TASKS = """
            {"id": "paired", "title": "Two of a kind", "tasks": [
              {"type": "tenet:item_tag", "tag": "minecraft:logs", "count": 2},
              {"type": "tenet:item_tag", "tag": "minecraft:logs", "count": 4}],
             "rewards": [{"type": "tenet:item", "item": "minecraft:diamond", "count": 1}]}
            """;

    @Test
    @DisplayName("a tag is expanded once per rebuild, whatever names it twice")
    void aTagIsExpandedOncePerRebuild() {
        accept(TWO_TAG_TASKS);
        QuestViewerContent content = new QuestViewerContent();
        Map<String, Integer> asks = new java.util.HashMap<>();

        content.rebuild(ClientQuestCache.treeRevision(), tag -> {
            asks.merge(tag.location().toString(), 1, Integer::sum);
            return expanded(tag);
        }, tag -> List.of());

        assertEquals(1, asks.get("minecraft:logs"),
                "two tasks name the same tag, and the answer cannot differ inside one walk");
        assertEquals(List.of("paired"),
                ids(content.index().questsUsing(Identifier.parse("minecraft:oak_log"))),
                "and both members are still indexed, so the saving is not a lost expansion");
    }

    @Test
    @DisplayName("live reads follow the progress, by source index")
    void liveReadsFollowProgress() {
        accept(ONE_QUEST);
        QuestViewerContent content = new QuestViewerContent();
        content.tick();

        assertEquals(0, content.liveTask("tree", 0).have(), "nothing reported yet");
        assertEquals("Locked", content.stateText("tree"), "no progress means locked");

        String progress = "{\"quests\":{\"tree\":{\"state\":\"STARTED\",\"tasks\":[5,0,0,0],"
                + "\"claimable\":false}}}";
        ClientQuestCache.acceptProgress(UUID.randomUUID(), 100L,
                progress.getBytes(StandardCharsets.UTF_8), 50L);

        assertEquals(5, content.liveTask("tree", 0).have());
        assertFalse(content.liveTask("tree", 0).done(), "5 of 8 is not done");
        assertEquals(0, content.liveTask("tree", 1).have(),
                "the tag task's own row, read by its source index");
        assertEquals("In progress", content.stateText("tree"));
        assertEquals(0, content.liveTask("tree", 99).have(), "an out-of-range row is blank, not a crash");
        assertEquals(0, content.liveTask("gone", 0).have());
    }

    @Test
    @DisplayName("the snapshot is rebuilt when the tree revision moves, and not before")
    void rebuiltOnlyOnANewTree() {
        accept(TWO_QUESTS);
        QuestViewerContent content = new QuestViewerContent();
        content.tick();

        List<QuestPage> first = content.pages();
        long firstRevision = content.revision();
        content.tick();
        assertSame(first, content.pages(), "a tick with no new tree is a no-op");

        accept(TWO_QUESTS, SECOND_QUEST);
        content.tick();
        assertEquals(2, content.pages().size(), "a new tree is a new snapshot");
        assertTrue(content.revision() > firstRevision);
    }

    @Test
    @DisplayName("a language moves the signal the viewers watch, and a tag still does not")
    void aLanguageMovesTheRevisionAndATagDoesNot() {
        // A page carries a quest's title and its chapter's, so a locale arriving changes what every
        // page *says* while the questline does not move at all. The adapters compare `revision()` to
        // decide whether to re-register their entries, and JEI re-measures a page's height from it --
        // which is exactly the case this exists for, because translated text is a different height.
        //
        // The second half of the assertion is the property this class already documented and a
        // previous round spent four attempts on: a **tag** rebuilds the snapshot and must leave this
        // signal alone, because a tag decides which items find a quest rather than what a page says.
        // See `aTagThatMovedRebuildsTheSnapshot`.
        accept(ONE_QUEST);
        QuestViewerContent content = new QuestViewerContent();
        content.tick();
        long built = content.revision();
        assertEquals("Punch a tree", content.pages().get(0).quest().title());

        content.setTagHolders(tag -> oakLogSet(2));
        content.tick();
        assertEquals(built, content.revision(),
                "a tag moved, which is not a change to any page's words");

        ClientLocale.accept("hu_hu", "hu_hu",
                java.util.Map.of("quest.tree.title", "Vagj egy f\u00e1t"));
        content.tick();

        assertEquals("Vagj egy f\u00e1t", content.pages().get(0).quest().title(),
                "the page carries the words a player reads, not the wire's");
        assertNotEquals(built, content.revision(),
                "and the adapters' signal has to move, or the viewers keep the previous language");
    }

    @Test
    @DisplayName("the category and section words are Tenet's, not the viewer's")
    void categoryValuesAreTheContents() {
        QuestViewerContent content = new QuestViewerContent();

        assertEquals(Identifier.parse("tenet:quests"), content.categoryId());
        assertEquals("Quests", content.categoryTitle().getString());
        assertEquals("Tasks", content.tasksLabel().getString());
        assertEquals("Rewards", content.rewardsLabel().getString());
        assertFalse(content.categoryIcon().isEmpty(), "the category needs an icon to be drawn");
    }

    @Test
    @DisplayName("a reward's live row carries a status, never a count")
    void rewardRowsCarryStatusNotProgress() {
        accept(ONE_QUEST);
        QuestViewerContent content = new QuestViewerContent();
        content.tick();

        ClientQuestCache.acceptProgress(UUID.randomUUID(), 100L,
                "{\"quests\":{\"tree\":{\"state\":\"COMPLETED\",\"tasks\":[8,4,0,1],\"claimable\":true}}}"
                        .getBytes(StandardCharsets.UTF_8), 50L);

        QuestRow reward = content.liveReward("tree", 0);
        assertEquals(0, reward.need(), "a reward is not a requirement, so it draws no bar");
        assertTrue(reward.claimable(), "completed, unlocked and unclaimed is Ready");
        assertFalse(reward.done(), "no player in a test JVM has claimed anything");
        assertFalse(reward.locked());
    }

    @Test
    @DisplayName("a reward's claim state is per player, read from the cache")
    void rewardClaimsArePerPlayer() {
        accept(ONE_QUEST);
        UUID player = UUID.randomUUID();
        ClientQuestCache.acceptProgress(UUID.randomUUID(), 100L,
                ("{\"quests\":{\"tree\":{\"state\":\"COMPLETED\",\"tasks\":[8,4,0,1],"
                        + "\"claims\":{\"" + player + "\":[0]}}}}").getBytes(StandardCharsets.UTF_8), 50L);

        assertTrue(ClientQuestCache.rewardClaimedBy(player, "tree", 0));
        assertFalse(ClientQuestCache.rewardClaimedBy(UUID.randomUUID(), "tree", 0),
                "a teammate's claim is not this player's");
        assertFalse(ClientQuestCache.rewardClaimedBy(player, "tree", 1), "row one is unclaimed");
    }

    @Test
    @DisplayName("the state, its colour and the reward status words all come from the content")
    void stateColourAndStatusWords() {
        accept(ONE_QUEST);
        QuestViewerContent content = new QuestViewerContent();
        content.tick();

        assertEquals(PagePalette.LOCKED, content.stateColour("tree"), "no progress reads locked");

        ClientQuestCache.acceptProgress(UUID.randomUUID(), 1L,
                "{\"quests\":{\"tree\":{\"state\":\"UNLOCKED\",\"tasks\":[0,0,0,0]}}}"
                        .getBytes(StandardCharsets.UTF_8), 1L);
        assertEquals(PagePalette.AVAILABLE, content.stateColour("tree"));

        ClientQuestCache.acceptProgress(UUID.randomUUID(), 1L,
                "{\"quests\":{\"tree\":{\"state\":\"STARTED\",\"tasks\":[1,0,0,0]}}}"
                        .getBytes(StandardCharsets.UTF_8), 1L);
        assertEquals(PagePalette.PROGRESS, content.stateColour("tree"));

        ClientQuestCache.acceptProgress(UUID.randomUUID(), 1L,
                "{\"quests\":{\"tree\":{\"state\":\"COMPLETED\",\"tasks\":[8,4,0,1]}}}"
                        .getBytes(StandardCharsets.UTF_8), 1L);
        assertEquals(PagePalette.COMPLETE, content.stateColour("tree"));

        assertEquals("Ready", content.rewardStatusLabel(QuestContent.RewardStatus.READY).getString());
        assertEquals("Locked", content.rewardStatusLabel(QuestContent.RewardStatus.LOCKED).getString());
        assertEquals("Claimed", content.rewardStatusLabel(QuestContent.RewardStatus.CLAIMED).getString());
    }

    @Test
    @DisplayName("a page resolves by id, and an id with no page resolves to nothing")
    void pagesResolveById() {
        // Two adapters used to answer this by walking every page per ref, which is quadratic for a
        // lookup whose answer is a map. The map is the content's now; what this asserts is that it
        // agrees with the list, case for case.
        accept(ONE_QUEST, TEXT_ONLY);
        QuestViewerContent content = new QuestViewerContent();
        content.tick();

        assertFalse(content.pages().isEmpty(), "the fixture has a quest with item rows");
        for (QuestPage page : content.pages()) {
            assertSame(page, content.page(page.quest().id()),
                    "the map and the list must agree about every page");
        }
        assertNull(content.page("talk"),
                "a quest whose rows are all text has no page, so an id from a ref is not always a page");
        assertNull(content.page("nothing_like_this"), "an id the snapshot does not carry");
        assertNull(content.page(null), "a null id is not a crash");
    }
}
