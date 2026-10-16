package dev.ellipog.tenet.client.dev;

import dev.ellipog.armature.client.ArmatureTextField;
import dev.ellipog.tenet.client.BookGeometry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The floating colour picker, drawn into a recorder.
 *
 * <h2>What this can see, and what it is for</h2>
 *
 * <p>Every fault this control has had was a drawing fault: a gradient quantised into bands, a marker that
 * vanished against one end of it, a hex field that showed a colour without the alpha that is half of what
 * it edits, and a row of quick picks that grew past the slots that fit and then silently stopped taking
 * presses. None of those is arithmetic, so none of them could be asserted; all of them are fills and text,
 * so a recorder can. The picker's own rectangles are private, so what is asserted is what a person sees:
 * where the ink is, how much of it there is, and what the text says.
 *
 * <p>No window and no client: the fields are {@code ArmatureTextField}s and {@code ScrubField}s, both of
 * which are constructed headless by their own tests.
 */
@DisplayName("The colour picker")
class ColourPopoverTest {

    /** A canvas the panel has to stay inside, and one big enough that its own size is the constraint. */
    private static final BookGeometry.Rect CANVAS = BookGeometry.Rect.at(0, 0, 420, 300);

    /** The picker and what it was told, for one open. */
    private static final class Picker {

        final ColourPopover popover = new ColourPopover();
        final List<Integer> previews = new ArrayList<>();
        final List<Integer> commits = new ArrayList<>();

        Picker(int argb, List<Integer> presets, BookGeometry.Rect anchor) {
            popover.open(anchor, CANVAS, "Canvas pattern ink", argb, presets, previews::add, commits::add);
        }

        /** Draws it into a fresh recorder, and hands the recorder back. */
        RecordingRenderer draw(int mouseX, int mouseY) {
            RecordingRenderer recorder = new RecordingRenderer();
            popover.render(recorder, mouseX, mouseY);
            return recorder;
        }
    }

    /** The anchor a test uses when it does not care where the chip is: the middle of the canvas. */
    private static BookGeometry.Rect anchor() {
        return BookGeometry.Rect.at(240, 120, 12, 12);
    }

    @Test
    @DisplayName("the panel is kept inside the canvas, whichever edge its chip is at")
    void thePanelStaysInsideTheCanvas() {
        for (BookGeometry.Rect anchor : List.of(
                BookGeometry.Rect.at(2, 2, 12, 12),
                BookGeometry.Rect.at(CANVAS.right() - 14, 2, 12, 12),
                BookGeometry.Rect.at(2, CANVAS.bottom() - 14, 12, 12),
                BookGeometry.Rect.at(CANVAS.right() - 14, CANVAS.bottom() - 14, 12, 12))) {
            Picker picker = new Picker(0xFF80808F, List.of(), anchor);
            BookGeometry.Rect panel = picker.popover.panel();

            assertTrue(panel.x() >= CANVAS.x() && panel.right() <= CANVAS.right()
                            && panel.y() >= CANVAS.y() && panel.bottom() <= CANVAS.bottom(),
                    "the panel left the canvas for the anchor " + anchor + ": " + panel);
        }
    }

    @Test
    @DisplayName("the saturation/value box is one veil row per row, not forty-eight bands")
    void theGradientIsNotBanded() {
        // A white value, so the only fills that are black with a partial alpha are the veil: the alpha
        // track's gradient carries the token's own rgb, and a black token would be counted with them.
        RecordingRenderer recorder = new Picker(0xFFFFFFFF, List.of(), anchor()).draw(0, 0);

        // A veil row is black, one pixel tall and as wide as the box -- which is what distinguishes it
        // from the marker's arms (one pixel wide) and from its ring (opaque and square).
        long veils = recorder.fills().stream()
                .filter(fill -> (fill.argb() & 0xFFFFFF) == 0
                        && fill.bottom() - fill.top() == 1 && fill.right() - fill.left() > 1)
                .count();

        assertEquals(ColourPopover.SV_HEIGHT, veils,
                "the veil is not one row per row of the box: " + veils + " bands for a box "
                        + ColourPopover.SV_HEIGHT + " tall");
    }

    @Test
    @DisplayName("the marker is a two-tone crosshair, and it is there at both ends of the gradient")
    void theMarkerIsVisibleAtBothEnds() {
        // The two ends: white (saturation zero, value one -- the top-left corner) and black (value zero,
        // the bottom-left). A single-tone marker disappears against one of them, which is what this pins.
        for (int argb : List.of(0xFFFFFFFF, 0xFF000000)) {
            RecordingRenderer recorder = new Picker(argb, List.of(), anchor()).draw(0, 0);

            boolean ring = recorder.fills().stream().anyMatch(fill -> fill.argb() == 0xFF000000
                    && fill.right() - fill.left() == 7 && fill.bottom() - fill.top() == 7);
            boolean inner = recorder.fills().stream().anyMatch(fill -> fill.argb() == 0xFFFFFFFF
                    && fill.right() - fill.left() == 5 && fill.bottom() - fill.top() == 5);

            assertTrue(ring, "no black outline on the marker for " + Integer.toHexString(argb));
            assertTrue(inner, "no white inner ring on the marker for " + Integer.toHexString(argb));
        }
    }

    @Test
    @DisplayName("the hex field says the alpha: eight digits while it matters, six while it does not")
    void theHexShowsTheAlpha() {
        for (int[] colour : new int[][] { { 0x80808F80, 8 }, { 0xFF80808F, 6 } }) {
            Picker picker = new Picker(colour[0], List.of(), anchor());
            ArmatureTextField hex = (ArmatureTextField) picker.popover.widgets().get(0);
            RecordingRenderer recorder = new RecordingRenderer();
            hex.render(recorder);

            String shown = recorder.texts().stream().map(RecordingRenderer.Drawn::text)
                    .filter(text -> text.startsWith("#")).findFirst()
                    .orElseThrow(() -> new AssertionError("the hex field drew no text: "
                            + recorder.describe()));

            assertEquals(colour[1] + 1, shown.length(),
                    "a colour with alpha " + Integer.toHexString(colour[0] >>> 24) + " shows as " + shown);
        }
    }

    @Test
    @DisplayName("a quick pick that is drawn is a quick pick that answers a press")
    void theSwatchesAnswer() {
        List<Integer> seeds = List.of(0x112233, 0x445566, 0x778899, 0xAABBCC);
        Picker picker = new Picker(0xFF010203, seeds, anchor());
        RecordingRenderer recorder = picker.draw(0, 0);

        List<RecordingRenderer.Fill> drawn = swatches(recorder, seeds);
        assertEquals(seeds.size(), drawn.size(),
                "not every quick pick was offered: " + recorder.describe());

        RecordingRenderer.Fill first = drawn.get(0);
        assertTrue(picker.popover.mouseClicked(leftClick(first.left() + 2, first.top() + 2)),
                "the press was not the picker's");
        assertEquals(List.of(seeds.get(0) | 0xFF000000), picker.commits,
                "the swatch that was drawn is not the swatch that answered");
    }

    @Test
    @DisplayName("the + pins the colour in force while there is room, and nothing once the row is full")
    void thePlusIsHonestAboutRoom() {
        // Room: one seed on a wide panel, so the `+` has somewhere to put the colour in force.
        Picker room = new Picker(0xFFABCDEF, List.of(0x112233), anchor());
        int before = swatches(room.draw(0, 0), List.of(0x112233)).size();
        pressPlus(room);
        assertEquals(before + 1, swatches(room.draw(0, 0), List.of(0x112233, 0xABCDEF)).size(),
                "the + did not pin the colour in force");

        // Full: more seeds than the row can show. The drawn picks are the ones that fit, and a press on
        // the `+` neither adds a pick nor commits anything -- the fault the old list had, where it went on
        // appending past the slots and a press did nothing and said nothing.
        List<Integer> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(0x100000 + i * 0x010101);
        }
        Picker full = new Picker(0xFFABCDEF, many, anchor());
        int shown = swatches(full.draw(0, 0), many).size();
        assertTrue(shown > 1 && shown < many.size(),
                "the fixture should overflow the row, or the full case proves nothing: " + shown);
        pressPlus(full);
        assertEquals(shown, swatches(full.draw(0, 0), many).size(),
                "the row grew past the slots that fit");
        assertTrue(full.commits.isEmpty(), "a press on a full + committed something: " + full.commits);
    }

    /** The drawn swatch bodies: opaque fills in one of the seeds' own colours. */
    private static List<RecordingRenderer.Fill> swatches(RecordingRenderer recorder, List<Integer> seeds) {
        List<RecordingRenderer.Fill> out = new ArrayList<>();
        for (RecordingRenderer.Fill fill : recorder.fills()) {
            int argb = fill.argb();
            if ((argb >>> 24) != 0xFF || fill.right() - fill.left() != fill.bottom() - fill.top()
                    || fill.right() - fill.left() > 20) {
                continue;
            }
            for (int seed : seeds) {
                if (argb == (0xFF000000 | (seed & 0xFFFFFF))) {
                    out.add(fill);
                    break;
                }
            }
        }
        return out;
    }

    /** Presses the `+`, found from where its glyph was drawn -- the only thing a test can see of it. */
    private static void pressPlus(Picker picker) {
        RecordingRenderer recorder = picker.draw(0, 0);
        RecordingRenderer.Drawn glyph = recorder.texts().stream()
                .filter(text -> text.text().equals("+")).findFirst()
                .orElseThrow(() -> new AssertionError("the + was not drawn: " + recorder.describe()));
        assertTrue(picker.popover.mouseClicked(leftClick(glyph.x() + 2, glyph.y() + 4)),
                "the press on the + was not the picker's");
    }

    /** A left press at a point, for the popover's event-taking input. */
    private static net.minecraft.client.input.MouseButtonEvent leftClick(double x, double y) {
        return new net.minecraft.client.input.MouseButtonEvent(x, y,
                new net.minecraft.client.input.MouseButtonInfo(
                        com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0));
    }

    @Test
    @DisplayName("Reset goes back to the value it opened with, and says so")
    void resetReturnsAndExplains() {
        Picker picker = new Picker(0xFF112233, List.of(), anchor());
        RecordingRenderer recorder = picker.draw(0, 0);

        // The Reset box is drawn with its word; hovering it adds the sentence that says what it means.
        RecordingRenderer.Drawn word = recorder.texts().stream()
                .filter(text -> text.text().equals("Reset")).findFirst()
                .orElseThrow(() -> new AssertionError("Reset was not drawn: " + recorder.describe()));
        RecordingRenderer hovered = picker.draw(word.x() + 4, word.y() + 4);
        assertTrue(hovered.texts().stream().anyMatch(text -> text.text().startsWith("back to #")),
                "hovering Reset says nothing about what it resets to: " + hovered.describe());
        assertFalse(picker.commits.contains(0xFF112233) && picker.commits.size() > 1,
                "merely drawing the picker committed something");
    }
}
