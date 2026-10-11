package dev.ellipog.tenet.client;

import dev.ellipog.tenet.net.QuestSync;
import dev.ellipog.tenet.quest.Fixtures;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestIndex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A quest still titled {@code New quest} reads as its first task's subject.
 *
 * <p>Tenet#29: an alpha tester with a quest farming one blue dye expected the book to
 * call it "Blue Dye" without a manual rename. Quest <i>icons</i> already adopt this
 * way ({@code adoptedQuestIcon}); titles did not, until now.
 *
 * <p>The entries come through the real sync ({@code Fixtures} → {@code QuestIndex} →
 * {@code QuestSync.treeAsJson} → {@code acceptTree}), like
 * {@code RepeatedQuestTitlesTest}, because an {@code Entry} has some forty components
 * and a hand-built one would assert against the test's own idea of the wire.
 */
@DisplayName("a default-titled quest borrows its first task's name")
class AdoptedQuestTitleTest {

    @BeforeAll
    static void bootstrap() {
        // Item tasks resolve their ids to stacks on arrival, so the registry has to exist.
        MinecraftTestBootstrap.boot();
    }

    @BeforeEach
    @AfterEach
    void clearCache() {
        ClientQuestCache.clear();
        ClientLocale.clear();
    }

    private static void accept(String... quests) {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(quests));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
    }

    private static ClientQuestCache.Entry entryOf(String id) {
        return ClientQuestCache.entries().stream()
                .filter(entry -> entry.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no quest '" + id + "' in the cache"));
    }

    private static String quest(String id, String title, String tasks) {
        return "{\"id\": \"" + id + "\", \"title\": " + title + ", \"tasks\": [" + tasks + "]}";
    }

    private static String literal(String text) {
        return "\"" + text.replace("\"", "\\\"") + "\"";
    }

    private static final String BLUE_DYE = "{\"type\": \"tenet:item\", \"item\": \"minecraft:blue_dye\", \"count\": 1}";
    private static final String CHECKMARK = "{\"type\": \"tenet:checkmark\"}";

    @Test
    @DisplayName("a first item task names the quest, in the player's language")
    void itemTaskLendsItsName() {
        accept(quest("a", literal("New quest"), BLUE_DYE));

        assertEquals("Blue Dye", entryOf("a").titleText(),
                "the dye quest should read as its dye without a manual rename");
        assertEquals("Blue Dye", entryOf("a").titleTextRaw(),
                "the coded reading must agree with the stripped one");
        assertEquals("Blue Dye", ClientQuestCache.adoptedQuestTitle(entryOf("a")));
    }

    @Test
    @DisplayName("an author title wins permanently over later tasks")
    void explicitTitleWins() {
        accept(quest("a", literal("My Farm"), BLUE_DYE));

        assertEquals("My Farm", entryOf("a").titleText());
        assertTrue(ClientQuestCache.adoptedQuestTitle(entryOf("a")).isEmpty(),
                "an explicit title is never adopted over");
    }

    @Test
    @DisplayName("a quest with nothing to lend keeps its default")
    void noTasksKeepsDefault() {
        accept(quest("a", literal("New quest"), ""),
                quest("b", literal("New quest"), CHECKMARK));

        assertEquals("New quest", entryOf("a").titleText());
        assertEquals("New quest", entryOf("b").titleText(),
                "a lone checkmark has no noun to lend");
    }

    @Test
    @DisplayName("a leading checkmark does not block the first real name")
    void leadingCheckmarkSkipped() {
        accept(quest("a", literal("New quest"), CHECKMARK + ", " + BLUE_DYE));

        assertEquals("Blue Dye", entryOf("a").titleText());
    }

    @Test
    @DisplayName("a tag task lends its humanized words")
    void tagTaskLendsHumanized() {
        accept(quest("a", literal("New quest"),
                "{\"type\": \"tenet:item_tag\", \"tag\": \"minecraft:logs\", \"count\": 1}"));

        assertEquals("Any Logs", entryOf("a").titleText());
    }

    @Test
    @DisplayName("a break task lends its block")
    void breakTaskLendsBlock() {
        accept(quest("a", literal("New quest"),
                "{\"type\": \"tenet:break\", \"block\": \"minecraft:stone\", \"value\": 1}"));

        assertEquals("Stone", entryOf("a").titleText());
    }

    @Test
    @DisplayName("a break tag lends its humanized words")
    void breakTagLendsHumanized() {
        accept(quest("a", literal("New quest"),
                "{\"type\": \"tenet:break\", \"blockTag\": \"minecraft:logs\", \"value\": 1}"));

        assertEquals("Any Logs", entryOf("a").titleText());
    }

    @Test
    @DisplayName("a kill task lends its mob")
    void killTaskLendsMob() {
        accept(quest("a", literal("New quest"),
                "{\"type\": \"tenet:kill\", \"entity\": \"minecraft:zombie\", \"value\": 1}"));

        assertEquals("Zombie", entryOf("a").titleText());
    }

    @Test
    @DisplayName("a kill-anything task lends nothing")
    void killAnythingLendsNothing() {
        accept(quest("a", literal("New quest"), "{\"type\": \"tenet:kill\", \"value\": 1}"));

        assertEquals("New quest", entryOf("a").titleText());
    }

    @Test
    @DisplayName("a fluid task lends its fluid, without the amount")
    void fluidTaskLendsFluid() {
        accept(quest("a", literal("New quest"),
                "{\"type\": \"tenet:fluid\", \"fluid\": \"minecraft:water\", \"amount\": 1000}"));

        assertEquals("Water", entryOf("a").titleText());
    }

    @Test
    @DisplayName("a pack translation wins over adoption")
    void translationWins() {
        accept(quest("a", literal("New quest"), BLUE_DYE));
        assertEquals("Blue Dye", entryOf("a").titleText());

        ClientLocale.accept("hu_hu", "hu_hu", Map.of("quest.a.title", "Kék Festék"));

        assertEquals("Kék Festék", entryOf("a").titleText(),
                "the translator's words are more specific than the task's");
        assertTrue(ClientQuestCache.adoptedQuestTitle(entryOf("a")).isEmpty(),
                "and adoption reports none while translated");
    }

    @Test
    @DisplayName("a translatable file title is already explicit")
    void translatableFileTitleWins() {
        accept("{\"id\": \"a\", \"title\": {\"translate\": \"my.key\", \"fallback\": \"My Words\"}, "
                + "\"tasks\": [" + BLUE_DYE + "]}");

        assertEquals("My Words", entryOf("a").titleText());
        assertTrue(ClientQuestCache.adoptedQuestTitle(entryOf("a")).isEmpty());
    }

    @Test
    @DisplayName("an emptied title stays empty rather than adopting")
    void emptyTitleStaysEmpty() {
        accept(quest("a", literal(""), BLUE_DYE));

        assertEquals("", entryOf("a").titleText(),
                "clearing the title is an explicit edit too");
    }

    @Test
    @DisplayName("two adopted twins count as duplicates, in the words a player reads")
    void adoptedTwinsAreDuplicates() {
        accept(quest("a", literal("New quest"), BLUE_DYE),
                quest("b", literal("New quest"), BLUE_DYE));

        assertTrue(QuestBookScreen.repeatedTitles(ClientQuestCache.entries()).contains("Blue Dye"),
                "numbering reads the resolved title, so adopted twins must number like any twins");
    }

    @Test
    @DisplayName("a dimension task lends its dimension")
    void dimensionTaskLendsDimension() {
        accept(quest("a", literal("New quest"),
                "{\"type\": \"tenet:dimension\", \"dimension\": \"minecraft:the_nether\"}"));

        assertEquals("The nether", entryOf("a").titleText());
    }

    @Test
    @DisplayName("a biome task lends its biome, or its tag's words")
    void biomeTaskLendsBiome() {
        accept(quest("a", literal("New quest"),
                        "{\"type\": \"tenet:biome\", \"biome\": \"minecraft:plains\"}"),
                quest("b", literal("New quest"),
                        "{\"type\": \"tenet:biome\", \"biome\": \"#minecraft:is_ocean\"}"));

        assertEquals("Plains", entryOf("a").titleText());
        assertEquals("Any Is Ocean", entryOf("b").titleText(),
                "the tag convention the rows use, awkward prefix and all");
    }

    @Test
    @DisplayName("a structure task lends its structure")
    void structureTaskLendsStructure() {
        accept(quest("a", literal("New quest"),
                "{\"type\": \"tenet:structure\", \"structure\": \"minecraft:village_plains\"}"));

        assertEquals("Village plains", entryOf("a").titleText());
    }

    @Test
    @DisplayName("an advancement task lends its advancement, criterion or none")
    void advancementTaskLendsAdvancement() {
        // Headless the client knows no advancement tree, so these read the id's last
        // leg; on a connected client the same quests read the advancement's own title.
        accept(quest("a", literal("New quest"),
                        "{\"type\": \"tenet:advancement\", \"advancement\": \"minecraft:story/mine_diamond\"}"),
                quest("b", literal("New quest"),
                        "{\"type\": \"tenet:advancement\", \"advancement\": \"minecraft:story/mine_diamond\", \"criterion\": \"diamond\"}"));

        assertEquals("Mine diamond", entryOf("a").titleText());
        assertEquals("Mine diamond", entryOf("b").titleText(),
                "a watched criterion resolves to its advancement all the same");
    }

    @Test
    @DisplayName("a stat task lends its stat")
    void statTaskLendsStat() {
        accept(quest("a", literal("New quest"),
                "{\"type\": \"tenet:stat\", \"stat\": \"minecraft:jump\", \"value\": 100}"));

        assertEquals("Jump", entryOf("a").titleText());
    }

    @Test
    @DisplayName("an observation lends what is watched, when that is a name")
    void observationLendsWatched() {
        accept(quest("a", literal("New quest"),
                        "{\"type\": \"tenet:observation\", \"observeType\": \"entity_type\", \"toObserve\": \"minecraft:zombie\"}"),
                quest("b", literal("New quest"),
                        "{\"type\": \"tenet:observation\", \"observeType\": \"block_tag\", \"toObserve\": \"minecraft:logs\"}"),
                quest("c", literal("New quest"),
                        "{\"type\": \"tenet:observation\", \"observeType\": \"block_state\", \"toObserve\": \"minecraft:oak_stairs\"}"));

        assertEquals("Zombie", entryOf("a").titleText());
        assertEquals("Any Logs", entryOf("b").titleText());
        assertEquals("New quest", entryOf("c").titleText(),
                "a state predicate is a matcher rather than a name");
    }

    @Test
    @DisplayName("a filter naming exactly one item lends its name, and no other filter does")
    void filterLendsSingleMatch() {
        accept(quest("a", literal("New quest"),
                        "{\"type\": \"tenet:filter\", \"filter\": \"item(minecraft:blue_dye)\", \"count\": 1}"),
                quest("b", literal("New quest"),
                        "{\"type\": \"tenet:filter\", \"filter\": \"or(item(minecraft:coal)item(minecraft:charcoal))\", \"count\": 1}"),
                quest("c", literal("New quest"),
                        "{\"type\": \"tenet:filter\", \"filter\": \"item(someothermod:widget)\", \"count\": 1}"));

        assertEquals("Blue Dye", entryOf("a").titleText());
        assertEquals("New quest", entryOf("b").titleText(),
                "a title must not cycle, so two matches lend nothing rather than the first");
        assertEquals("New quest", entryOf("c").titleText(),
                "and a filter nothing answers to lends nothing either");
    }

    @Test
    @DisplayName("a titled checkmark lends its own words")
    void titledCheckmarkLendsWords() {
        accept(quest("a", literal("New quest"),
                "{\"type\": \"tenet:checkmark\", \"title\": \"Talk to the elder\"}"));

        assertEquals("Talk to the elder", entryOf("a").titleText(),
                "the one task whose row is its author's title");
    }

    @Test
    @DisplayName("any task's own caption outranks its requirement")
    void authorCaptionOutranksRequirement() {
        accept(quest("a", literal("New quest"),
                        "{\"type\": \"tenet:item\", \"item\": \"minecraft:diamond\", \"title\": \"Bring tribute\", \"count\": 1}"),
                quest("b", literal("New quest"),
                        "{\"type\": \"tenet:kill\", \"entity\": \"minecraft:zombie\", \"title\": \"Cull the horde\", \"value\": 3}"));

        assertEquals("Bring tribute", entryOf("a").titleText(),
                "the author's words are the most specific thing said about the quest");
        assertEquals("Cull the horde", entryOf("b").titleText());
    }

    @Test
    @DisplayName("energy, xp, stages and custom tasks lend nothing, each for its own reason")
    void nounlessTasksLendNothing() {
        accept(quest("a", literal("New quest"), "{\"type\": \"tenet:energy\", \"value\": 1000}"),
                quest("b", literal("New quest"), "{\"type\": \"tenet:xp\", \"value\": 5}"),
                quest("c", literal("New quest"), "{\"type\": \"tenet:stage\", \"stage\": \"my_pack:inducted\"}"),
                quest("d", literal("New quest"),
                        "{\"type\": \"tenet:custom\", \"id\": \"my_mod:thing\", \"value\": 1}"));

        // An amount is not a name, a stage id is pack spelling the rows do not
        // prettify, and an addon's sentence is a shape this build has never seen.
        for (String id : new String[]{"a", "b", "c", "d"}) {
            assertEquals("New quest", entryOf(id).titleText(), "quest " + id + " lent a name it should not have");
        }
    }
}
