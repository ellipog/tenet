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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An item-tag task's icon rotation: what it serves, and when the quest counts as cycling.
 *
 * <p>A tag names many items yet draws one, so it rotates through its contents at the filter's
 * cadence — rows, nodes and HUD pins cycle together off the one clock. The entries come through
 * the real sync like {@code AdoptedQuestTitleTest}'s, because the rotation reads resolved
 * entries rather than JSON.
 *
 * <p>Tags are the one thing a bare JVM cannot bind: it has the registries but not the datapack,
 * so every tag query answers empty here. The multi-item case — a bound tag cycling, a lone item
 * staying still — is therefore pinned for filters, whose matches enumerate live registry items,
 * while tags pin their fallback: unresolvable draws exactly what it drew before, still.
 */
@DisplayName("an item-tag task's icon rotation")
class TagRotationTest {

    @BeforeAll
    static void bootstrap() {
        // Filter matches enumerate the registry's holders, so the registry has to exist.
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

    private static String quest(String id, String tasks) {
        return "{\"id\": \"" + id + "\", \"title\": \"New quest\", \"tasks\": [" + tasks + "]}";
    }

    private static final String MANY_FRAMES =
            "{\"type\": \"tenet:filter\", \"filter\": \"mod(minecraft)\", \"count\": 1}";
    private static final String ONE_FRAME =
            "{\"type\": \"tenet:filter\", \"filter\": \"item(minecraft:stone)\", \"count\": 1}";
    private static final String TAG_TASK =
            "{\"type\": \"tenet:item_tag\", \"tag\": \"minecraft:logs\", \"count\": 1}";

    @Test
    @DisplayName("a filter naming many items serves them all in order")
    void multiFrameFilterServesMany() {
        accept(quest("a", MANY_FRAMES));

        var frames = ClientQuestCache.rotationFrames(entryOf("a").tasks().get(0));
        assertTrue(frames.size() > 1, "mod(minecraft) names thousands, so the rotation is real");
        assertTrue(ClientQuestCache.adoptionCycles(entryOf("a")),
                "more than one frame is what makes the stamp carry the second");
    }

    @Test
    @DisplayName("a filter naming one item is still")
    void singleFrameFilterIsStill() {
        accept(quest("a", ONE_FRAME));

        var frames = ClientQuestCache.rotationFrames(entryOf("a").tasks().get(0));
        assertEquals(1, frames.size(), "one match is one frame, drawn always");
        assertFalse(ClientQuestCache.adoptionCycles(entryOf("a")),
                "a still icon must not restamp the canvas every second");
    }

    @Test
    @DisplayName("an unresolvable tag falls back to still, never to empty")
    void unresolvableTagFallsBackToStill() {
        // No datapack binds tags on a bare JVM, so minecraft:logs resolves to nothing here —
        // which is exactly the unresolvable case: the row keeps the glyph it always drew.
        accept(quest("a", TAG_TASK));

        var frames = ClientQuestCache.rotationFrames(entryOf("a").tasks().get(0));
        assertTrue(frames.isEmpty(), "nothing bound means nothing to rotate through");
        assertTrue(ClientQuestCache.filterFrame(entryOf("a").tasks().get(0)).isEmpty(),
                "and no frame means the still rule answers, not an empty draw");
        assertFalse(ClientQuestCache.adoptionCycles(entryOf("a")));
    }

    @Test
    @DisplayName("an explicit icon never cycles, however many frames a task names")
    void explicitIconNeverCycles() {
        accept("{\"id\": \"a\", \"title\": \"New quest\", "
                + "\"icon\": { \"item\": \"minecraft:oak_log\", \"count\": 1 }, "
                + "\"tasks\": [" + MANY_FRAMES + "]}");

        assertFalse(ClientQuestCache.adoptionCycles(entryOf("a")),
                "the author's picture wins over every rotation");
    }

    @Test
    @DisplayName("a rotation frame is one of the frames, on the shared one-second clock")
    void frameIsOneOfTheFrames() {
        accept(quest("a", MANY_FRAMES));

        var task = entryOf("a").tasks().get(0);
        var frames = ClientQuestCache.rotationFrames(task);
        assertTrue(frames.contains(ClientQuestCache.filterFrame(task)),
                "the drawn frame is always a served one — rows, nodes and HUD cannot disagree");
    }
}
