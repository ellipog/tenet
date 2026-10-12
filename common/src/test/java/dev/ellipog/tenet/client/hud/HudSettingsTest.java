package dev.ellipog.tenet.client.hud;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the HUD's things are, and what a bad file does.
 *
 * <h2>Why the file is worth this many tests</h2>
 *
 * <p>Because every way it can be wrong is silent. A layout that fails to load leaves everything at its
 * default and looks like a first run; a layout that loads a nonsense coordinate puts a control half off the
 * window with nothing to say about why; and a file that would not parse at all must cost the layout rather
 * than the client, on a code path that runs while the game is starting. All three are asserted here rather
 * than argued about, and the direction every failure takes is the one that cannot lie about what is on
 * screen.
 *
 * <h2>The one shape worth naming</h2>
 *
 * <p>{@code hud.json} is a <b>diff from the defaults</b>: an element nobody has moved has no entry, a value
 * put back to its default leaves the entry, and an entry with nothing left in it goes. That is what makes
 * the file readable for what the player changed -- the rule {@code DevMode.panelWidths} follows -- and it is
 * asserted in both directions, because "nothing is stored" and "the default was stored" are the same
 * behaviour and only one of them keeps the file honest.
 */
@DisplayName("the HUD layout")
class HudSettingsTest {

    @AfterEach
    void forget() {
        // A static map with a static path: a test that leaves either set changes the next one.
        HudSettings.reset();
    }

    @Test
    @DisplayName("every element is on, at its default, until a file says otherwise")
    void defaults() {
        for (HudElement element : HudElement.values()) {
            assertTrue(HudSettings.on(element), element + " is drawn before anybody switches it off");
            assertEquals(element.defaultX(), HudSettings.x(element));
            assertEquals(element.defaultY(), HudSettings.y(element));
            assertEquals(element.defaultDim(), HudSettings.dim(element),
                    element + " dims at its shipped strength until the slider moves");
        }
        assertEquals(HudElement.INVENTORY_BUTTON, HudElement.named("inventory_button"),
                "the key the file holds");
        assertEquals(HudElement.INVENTORY_BUTTON, HudElement.named("INVENTORY_BUTTON"),
                "and the constant's own name, which is what somebody reading the enum would guess");
        assertNull(HudElement.named("nonsense"), "a key this build does not have names nothing");
        assertNull(HudElement.named(null), "and so does no key at all");
        assertEquals("tenet.hud.inventory_button", HudElement.INVENTORY_BUTTON.labelKey(),
                "the label key is derived, so a rename here renames the row and the language entry together");
        assertTrue(HudSettings.changed().isEmpty(), "and nothing has been changed yet");
        assertNull(HudSettings.file(), "or read from anywhere");
    }

    @Test
    @DisplayName("moving, switching off and resetting, and what each writes")
    void theFileIsADiff(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(HudSettings.FILE_NAME);
        HudSettings.load(file);
        assertEquals(file, HudSettings.file(), "the file it read is the file it will write");

        // Nothing moved: no entry, and no "elements" object with nothing in it either.
        assertEquals("{\"elements\":{}}", HudSettings.write(Map.of()),
                "an untouched layout writes an empty table");

        HudSettings.setPosition(HudElement.INVENTORY_BUTTON, 30, 12);
        assertEquals(30, HudSettings.x(HudElement.INVENTORY_BUTTON));
        assertEquals("{\"elements\":{\"inventory_button\":{\"x\":30,\"y\":12}}}",
                HudSettings.write(HudSettings.changed()),
                "a moved element writes both coordinates and says nothing about being switched on");

        HudSettings.setOn(HudElement.INVENTORY_BUTTON, false);
        assertEquals("{\"elements\":{\"inventory_button\":{\"x\":30,\"y\":12,\"on\":false}}}",
                HudSettings.write(HudSettings.changed()),
                "switching it off joins the entry rather than replacing it");
        assertFalse(HudSettings.on(HudElement.INVENTORY_BUTTON));

        // And the file itself round-trips, which is the half the strings above cannot see.
        HudSettings.load(file);
        assertFalse(HudSettings.on(HudElement.INVENTORY_BUTTON), "the switch survived the file");
        assertEquals(30, HudSettings.x(HudElement.INVENTORY_BUTTON), "and so did the position");

        // Back to the defaults: each field leaves as it becomes the default, and the entry goes with the last.
        HudSettings.setPosition(HudElement.INVENTORY_BUTTON,
                HudElement.INVENTORY_BUTTON.defaultX(), HudElement.INVENTORY_BUTTON.defaultY());
        assertEquals("{\"elements\":{\"inventory_button\":{\"on\":false}}}",
                HudSettings.write(HudSettings.changed()),
                "a position back at its default is not stored beside it: two spellings of one state");
        HudSettings.setOn(HudElement.INVENTORY_BUTTON, true);
        assertTrue(HudSettings.changed().isEmpty(),
                "and with nothing left to say, the element has no entry at all");
        assertEquals("{\"elements\":{}}", HudSettings.write(HudSettings.changed()));
    }

    @Test
    @DisplayName("the dim slider writes a number, clamps it, and forgets it at the default")
    void dimIsADiffToo(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(HudSettings.FILE_NAME);
        HudSettings.load(file);

        HudSettings.setDim(HudElement.PINNED_QUESTS, 0.25);
        assertEquals(0.25, HudSettings.dim(HudElement.PINNED_QUESTS));
        assertEquals("{\"elements\":{\"pinned_quests\":{\"dim\":0.25}}}",
                HudSettings.write(HudSettings.changed()),
                "a dimmed element writes only what it changed");

        // And the file itself round-trips, which is the half the string above cannot see.
        HudSettings.load(file);
        assertEquals(0.25, HudSettings.dim(HudElement.PINNED_QUESTS), "the strength survived the file");

        // Clamped rather than refused: a slider cannot produce these, so they arrived by hand, and a file
        // that silently corrected itself would be one the player cannot read back.
        HudSettings.setDim(HudElement.PINNED_QUESTS, 2.0);
        assertEquals(1.0, HudSettings.dim(HudElement.PINNED_QUESTS), "over one pins at full");
        HudSettings.setDim(HudElement.PINNED_QUESTS, -1.0);
        assertEquals(0.0, HudSettings.dim(HudElement.PINNED_QUESTS), "under zero pins at none");

        // Back to the default: the field leaves, like every other setting here.
        HudSettings.setDim(HudElement.PINNED_QUESTS, HudElement.PINNED_QUESTS.defaultDim());
        assertTrue(HudSettings.changed().isEmpty(), "a default strength is not stored beside itself");
    }

    @Test
    @DisplayName("a dim of the wrong type costs its entry and nothing else")
    void aBadDimCostsTheEntry(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(HudSettings.FILE_NAME);
        Files.writeString(file, "{\"elements\":{\"pinned_quests\":{\"dim\":\"half\"}}}",
                StandardCharsets.UTF_8);
        HudSettings.load(file);

        assertEquals(HudElement.PINNED_QUESTS.defaultDim(), HudSettings.dim(HudElement.PINNED_QUESTS));
        assertTrue(HudSettings.changed().isEmpty());
    }

    @Test
    @DisplayName("reset puts one element back and leaves the others alone")
    void resetIsPerElement(@TempDir Path dir) throws IOException {
        HudSettings.load(dir.resolve(HudSettings.FILE_NAME));
        HudSettings.setPosition(HudElement.INVENTORY_BUTTON, 40, 20);
        HudSettings.resetElement(HudElement.INVENTORY_BUTTON);

        assertEquals(HudElement.INVENTORY_BUTTON.defaultX(), HudSettings.x(HudElement.INVENTORY_BUTTON));
        assertEquals(HudElement.INVENTORY_BUTTON.defaultY(), HudSettings.y(HudElement.INVENTORY_BUTTON));
        assertTrue(HudSettings.on(HudElement.INVENTORY_BUTTON), "reset is the shipped layout, switch included");
        assertTrue(HudSettings.changed().isEmpty());
    }

    @Test
    @DisplayName("a file written by a newer build keeps the entries this one knows")
    void unknownElementsCostTheirEntry(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(HudSettings.FILE_NAME);
        // `not_a_thing` rather than a plausible-looking id: this test used `pinned_quests` while that was a
        // name no build had, and it stopped testing anything the moment the element arrived -- silently, by
        // passing the wrong count. A key this build genuinely does not define is what the case is about.
        Files.writeString(file, "{\"elements\":{\"not_a_thing\":{\"x\":9,\"y\":9},"
                + "\"inventory_button\":{\"x\":7,\"y\":8}}}", StandardCharsets.UTF_8);
        HudSettings.load(file);

        assertEquals(7, HudSettings.x(HudElement.INVENTORY_BUTTON),
                "the entry this build understands is read");
        assertEquals(8, HudSettings.y(HudElement.INVENTORY_BUTTON));
        assertEquals(1, HudSettings.changed().size(), "and the one it does not is dropped, not the file");
    }

    @Test
    @DisplayName("an entry with a field of the wrong type costs that entry and nothing else")
    void aBadFieldCostsTheEntry(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(HudSettings.FILE_NAME);
        Files.writeString(file, "{\"elements\":{\"inventory_button\":{\"x\":\"wide\",\"y\":8}}}",
                StandardCharsets.UTF_8);
        HudSettings.load(file);

        // Half an entry would leave the element somewhere nobody asked for, which is harder to explain than
        // the default. Same granularity as `panelWidths`, and the same reason.
        assertEquals(HudElement.INVENTORY_BUTTON.defaultX(), HudSettings.x(HudElement.INVENTORY_BUTTON));
        assertEquals(HudElement.INVENTORY_BUTTON.defaultY(), HudSettings.y(HudElement.INVENTORY_BUTTON));
        assertTrue(HudSettings.changed().isEmpty());
    }

    @Test
    @DisplayName("a file that is missing, empty or not JSON at all leaves the defaults")
    void aBadFileIsTheDefaults(@TempDir Path dir) throws IOException {
        Path missing = dir.resolve("not-there.json");
        HudSettings.load(missing);
        assertTrue(HudSettings.changed().isEmpty(), "a first run says nothing and moves nothing");

        Path empty = dir.resolve("empty.json");
        Files.writeString(empty, "", StandardCharsets.UTF_8);
        HudSettings.load(empty);
        assertTrue(HudSettings.changed().isEmpty(), "an empty file is not JSON, and is not a crash");

        Path wrong = dir.resolve("wrong.json");
        Files.writeString(wrong, "[1,2,3]", StandardCharsets.UTF_8);
        HudSettings.load(wrong);
        assertTrue(HudSettings.changed().isEmpty(), "a list where an object belongs");

        Path noTable = dir.resolve("no-table.json");
        Files.writeString(noTable, "{\"dev\":true}", StandardCharsets.UTF_8);
        HudSettings.load(noTable);
        assertTrue(HudSettings.changed().isEmpty(), "a file without the table is a file with nothing in it");

        assertTrue(Files.exists(missing.getParent()), "the directory was not consumed by any of them");
    }

    @Test
    @DisplayName("a hand-edited coordinate is kept as written and clamped where it is used")
    void nonsenseIsKeptAndClampedAtUse(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(HudSettings.FILE_NAME);
        Files.writeString(file, "{\"elements\":{\"inventory_button\":{\"x\":-500,\"y\":9000}}}",
                StandardCharsets.UTF_8);
        HudSettings.load(file);

        // Not rewritten on the way in: the only bound that means anything is the window, which is known
        // where the element is placed. A file that silently corrected itself would be a file the player
        // cannot read back.
        assertEquals(-500, HudSettings.x(HudElement.INVENTORY_BUTTON));
        assertEquals(9000, HudSettings.y(HudElement.INVENTORY_BUTTON));
        assertEquals(0, HudLayout.placed(HudSettings.x(HudElement.INVENTORY_BUTTON), 300),
                "and it lands at the window's edge rather than nowhere");
        assertEquals(300, HudLayout.placed(HudSettings.y(HudElement.INVENTORY_BUTTON), 300));
    }

    @Test
    @DisplayName("without a file the layout still works, for this session only")
    void noFileStillWorks() {
        assertTrue(HudSettings.on(HudElement.INVENTORY_BUTTON), "on to begin with");
        HudSettings.setOn(HudElement.INVENTORY_BUTTON, false);
        assertFalse(HudSettings.on(HudElement.INVENTORY_BUTTON),
                "the switch works with nowhere to write it, which is the useful half");
        HudSettings.setPosition(HudElement.INVENTORY_BUTTON, 12, 13);
        assertEquals(12, HudSettings.x(HudElement.INVENTORY_BUTTON));
        assertNull(HudSettings.file());
    }

    @Test
    @DisplayName("every element ships measured from the window until the button opts into the panel")
    void originDefaultsToWindow(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(HudSettings.FILE_NAME);
        HudSettings.load(file);

        for (HudElement element : HudElement.values()) {
            assertEquals(HudElement.Origin.WINDOW, HudSettings.origin(element),
                    element + " is a window pixel before anybody chooses otherwise");
        }
        assertEquals("{\"elements\":{}}", HudSettings.write(HudSettings.changed()),
                "and a default nobody chose is not stored beside itself");

        HudSettings.setOrigin(HudElement.INVENTORY_BUTTON, HudElement.Origin.INVENTORY);
        assertEquals(HudElement.Origin.INVENTORY, HudSettings.origin(HudElement.INVENTORY_BUTTON));
        assertEquals("{\"elements\":{\"inventory_button\":{\"anchor\":\"inventory\"}}}",
                HudSettings.write(HudSettings.changed()),
                "while a chosen panel anchor writes only what it changed");

        HudSettings.load(file);
        assertEquals(HudElement.Origin.INVENTORY, HudSettings.origin(HudElement.INVENTORY_BUTTON),
                "the anchor survived the file");

        HudSettings.setOrigin(HudElement.INVENTORY_BUTTON, HudElement.Origin.WINDOW);
        assertEquals(HudElement.Origin.WINDOW, HudSettings.origin(HudElement.INVENTORY_BUTTON));
        assertTrue(HudSettings.changed().isEmpty(),
                "and a choice put back to the default leaves the entry with it");
    }

    @Test
    @DisplayName("an anchor on anything but the button reads as the window")
    void originIsTheButtonsAlone(@TempDir Path dir) throws IOException {
        HudSettings.load(dir.resolve(HudSettings.FILE_NAME));

        HudSettings.setOrigin(HudElement.PINNED_QUESTS, HudElement.Origin.INVENTORY);
        assertEquals(HudElement.Origin.WINDOW, HudSettings.origin(HudElement.PINNED_QUESTS),
                "the pins have no panel to be measured from");
        assertTrue(HudSettings.changed().isEmpty(),
                "and a choice nothing reads is not stored beside anything");
    }

    @Test
    @DisplayName("an anchor of the wrong type costs its entry, an unknown one reads as the default")
    void aBadAnchorCostsWhatItShould(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(HudSettings.FILE_NAME);
        Files.writeString(file, "{\"elements\":{\"inventory_button\":{\"x\":30,\"anchor\":4}}}",
                StandardCharsets.UTF_8);
        HudSettings.load(file);

        assertEquals(HudElement.INVENTORY_BUTTON.defaultX(), HudSettings.x(HudElement.INVENTORY_BUTTON),
                "a field of the wrong type costs the entry, like every other field here");
        assertTrue(HudSettings.changed().isEmpty());

        // Unknown vocabulary, but the right type: a newer build's origin, or a typo by hand. The
        // position beside it is still the player's, so the entry stays and the anchor reads as the
        // default -- a window pixel is the reading that cannot strand a control.
        Files.writeString(file, "{\"elements\":{\"inventory_button\":{\"x\":30,\"anchor\":\"drawer\"}}}",
                StandardCharsets.UTF_8);
        HudSettings.load(file);

        assertEquals(30, HudSettings.x(HudElement.INVENTORY_BUTTON), "the position survived");
        assertEquals(HudElement.Origin.WINDOW, HudSettings.origin(HudElement.INVENTORY_BUTTON),
                "and the anchor nobody here defines reads as the default");
        assertEquals(1, HudSettings.changed().size(), "with the entry kept, not dropped");
    }
}
