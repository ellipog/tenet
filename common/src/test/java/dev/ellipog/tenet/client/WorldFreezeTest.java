package dev.ellipog.tenet.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether the world behind the book is held still, as a file and as a rule.
 *
 * <p>The capture itself is GPU work and is checked in the game. What is asserted here is what decides it:
 * the file's one word, read forgivingly, and the rule that a still world is only ever shown under the book,
 * and under the default only while nothing behind the book can move.
 */
@DisplayName("the still world behind the book")
class WorldFreezeTest {

    @AfterEach
    void restore() {
        WorldFreeze.set(WorldFreeze.DEFAULT_MODE);
    }

    @Test
    @DisplayName("a missing file means paused")
    void aMissingFileIsTheDefault() {
        WorldFreeze.set(WorldFreeze.Mode.ALWAYS);
        WorldFreeze.load(Path.of("no", "such", WorldFreeze.FILE_NAME));

        assertEquals(WorldFreeze.Mode.PAUSED, WorldFreeze.mode());
    }

    @Test
    @DisplayName("each of the three words is read, in any case and with spaces around it")
    void theThreeWords() {
        assertEquals(WorldFreeze.Mode.OFF, WorldFreeze.parse("{\"freeze\": \"off\"}"));
        assertEquals(WorldFreeze.Mode.PAUSED, WorldFreeze.parse("{\"freeze\": \"Paused\"}"));
        assertEquals(WorldFreeze.Mode.ALWAYS, WorldFreeze.parse("{\"freeze\": \" ALWAYS \"}"));
    }

    @Test
    @DisplayName("a word that names nothing, a number, or no field at all, means paused")
    void anythingElseIsTheDefault() {
        assertEquals(WorldFreeze.Mode.PAUSED, WorldFreeze.parse("{\"freeze\": \"sometimes\"}"));
        assertEquals(WorldFreeze.Mode.PAUSED, WorldFreeze.parse("{\"freeze\": 1}"));
        assertEquals(WorldFreeze.Mode.PAUSED, WorldFreeze.parse("{\"freeze\": false}"));
        assertEquals(WorldFreeze.Mode.PAUSED, WorldFreeze.parse("{}"));
    }

    @Test
    @DisplayName("a file that is not JSON leaves paused, and the file is read from disk")
    void aBrokenFileAndARealOne(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(WorldFreeze.FILE_NAME);

        Files.writeString(file, "{ not json", StandardCharsets.UTF_8);
        WorldFreeze.load(file);
        assertEquals(WorldFreeze.Mode.PAUSED, WorldFreeze.mode());

        Files.writeString(file, "{\"freeze\": \"off\"}", StandardCharsets.UTF_8);
        WorldFreeze.load(file);
        assertEquals(WorldFreeze.Mode.OFF, WorldFreeze.mode());
    }

    @Test
    @DisplayName("the world is only held still under the book, and paused waits for the pause")
    void theRule() {
        for (WorldFreeze.Mode mode : WorldFreeze.Mode.values()) {
            assertFalse(WorldFreeze.wanted(mode, false, true), mode + " with no book");
            assertFalse(WorldFreeze.wanted(mode, false, false), mode + " with no book");
        }
        assertFalse(WorldFreeze.wanted(WorldFreeze.Mode.OFF, true, true));
        assertTrue(WorldFreeze.wanted(WorldFreeze.Mode.PAUSED, true, true));
        assertFalse(WorldFreeze.wanted(WorldFreeze.Mode.PAUSED, true, false));
        assertTrue(WorldFreeze.wanted(WorldFreeze.Mode.ALWAYS, true, false));
    }
}
