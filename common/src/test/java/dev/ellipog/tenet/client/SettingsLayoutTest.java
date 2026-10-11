package dev.ellipog.tenet.client;

import dev.ellipog.tenet.client.SettingsLayout.Entry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings card's composition, without a screen.
 *
 * <p>The card is <b>the player's own view and nothing about the pack's</b>: how large the text draws, and
 * where their HUD things sit. The look belongs to the pack and lives in the tools panel, and Motion is a
 * switch in that same panel -- so the test below that these are <i>not</i> rows is the point rather than an
 * omission.
 *
 * <p>It was written with one row and asserted that there was one, "adding another is a decision, not a
 * convenience". The HUD row is that decision: a placed button is the second thing on this card that is about
 * the player's screen rather than the book's appearance, and the count moved with the reason written down.
 * The chapter-list row is the third: docked or floating is a placement, like the HUD row's, and the header
 * is the book's busiest row — a choice made once lives here rather than in it.
 */
@DisplayName("the settings card's rows")
class SettingsLayoutTest {

    @Test
    @DisplayName("the card is the text-size row, the chapter-list row and the HUD row")
    void theCardIsTheSliderAndHud() {
        List<Entry> rows = SettingsLayout.entries(1.25D);

        assertEquals(3, rows.size(), "three rows: adding a fourth is a decision, not a convenience");
        assertEquals(SettingsLayout.TEXT_KEY, rows.get(0).key());
        assertEquals("Text size", rows.get(0).label().getString(),
                "the value is the slider's message, which follows the thumb without a rebuild");
        assertEquals(SettingsLayout.SIDEBAR_KEY, rows.get(1).key(),
                "and the chapter-list row sits above the HUD row: placement first, editor second");
        assertEquals(SettingsLayout.HUD_KEY, rows.get(2).key(),
                "and the HUD row is the way into the editor for somebody who never opens Controls");
    }

    @Test
    @DisplayName("a row's label is a component, so a translator can reach it")
    void labelsAreComponents() {
        List<Entry> rows = SettingsLayout.entries(1.0D);

        // The label used to be a bare string, which meant only English ever saw the row's own name while
        // the value beside it was translated. A key with no translation renders as the key, so this checks
        // that the HUD row is a key this build actually has -- see `en_us.json`.
        assertEquals("HUD layout", rows.get(2).label().getString(),
                "the HUD row reads from the language file rather than from a literal in this class");
        assertEquals("Floating chapter list", rows.get(1).label().getString(),
                "and so does the chapter-list row: placement, not just a name");
    }

    @Test
    @DisplayName("no theme, follow, radius or motion row")
    void noOtherRows() {
        List<Entry> rows = SettingsLayout.entries(1.0D);
        assertTrue(rows.stream().noneMatch(row -> row.key().startsWith("theme:")),
                "the palette list lives in the tools panel, where an author edits it");
        assertTrue(rows.stream().noneMatch(row -> row.key().equals("follow")),
                "nothing to follow back from when there is no choice to drop");
        assertTrue(rows.stream().noneMatch(row -> row.key().equals("radius")),
                "the radius is a field on the theme, and the tools panel's stepper owns it");
        assertTrue(rows.stream().noneMatch(row -> row.key().equals("motion")),
                "motion is a switch in the tools panel, beside the other mode switches");
    }
}
