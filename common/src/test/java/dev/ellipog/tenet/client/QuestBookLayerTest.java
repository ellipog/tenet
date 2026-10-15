package dev.ellipog.tenet.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The quest book's drawing layers, and what still pins them.
 *
 * <h2>What this used to assert</h2>
 *
 * <p>A screenshot showed the sidebar's rows, its row icons, the zoom cluster and the header controls
 * standing on top of an open quest card. One cause was {@code modalRedraws} holding controls that
 * belong behind the card; the other was depth. {@code GuiGraphics.renderItem} wrote depth <b>150
 * above the pose it inherits</b>, so a band that has to cover the one below it must clear that band's
 * item icons by more than 150 -- and three tests pinned the arithmetic between the {@code CHROME_Z},
 * {@code POPOVER_Z} and {@code TOOLTIP_Z} constants.
 *
 * <h2>Why the arithmetic is gone and what replaces it</h2>
 *
 * <p>On 26.x extraction records every draw in call order, so draw order <i>is</i> the layering: the
 * book, then the chrome, then the picker, then tooltips, then the dev overlay, all in sequence in
 * {@code extractRenderState}. There is no number left to assert -- a reorder is a visible diff in
 * the one method that draws, not a quiet constant tweak -- so the three band tests went with the
 * constants. What they prevented is now prevented by that sequence, and by eyes: the port's
 * in-game list asks for a look at icons under panels, because order cannot prove itself.
 *
 * <p>What remains pinned here is the dev overlay's switch, which is still a fact about
 * {@code devOverlayLines} assertable with no client, no window and no pixels.
 */
class QuestBookLayerTest {

    // ------------------------------------------------------------------
    // The dev overlay: whose instrument it is
    // ------------------------------------------------------------------

    /**
     * The rule these pin, and why it is asserted rather than looked at.
     *
     * <p>The overlay was edit mode's for as long as the tools existed, and the frame rate was separated from
     * it first -- which left the ten counter lines still appearing for every author, and a counting renderer
     * measuring every drawing call to produce them. One switch draws the whole overlay now, and "edit mode
     * alone draws nothing" is a fact about {@code devOverlayLines} rather than about the panel drawn around
     * it, so it can be asserted here with no client, no window and no pixels. Nothing else in this repository
     * can instantiate the screen.
     */

    @Test
    @DisplayName("edit mode alone draws no overlay, whatever has been counted")
    void editModeDrawsNoOverlay() {
        String[] counters = { "fills 41", "texts 12" };
        assertEquals(0, QuestBookScreen.devOverlayLines(false, counters, 144, 6_000_000L, 100).length,
                "without the operator's switch there is nothing to draw -- the counters are not edit mode's");
    }

    @Test
    @DisplayName("the command draws the frame rate first, then every counter in its own order")
    void theCommandDrawsTheFrameRateAndTheCounters() {
        String[] counters = { "fills 41", "texts 12" };
        assertArrayEquals(new String[] { "fps 144   6 ms   100%", "fills 41", "texts 12" },
                QuestBookScreen.devOverlayLines(true, counters, 144, 6_000_000L, 100));
    }

    @Test
    @DisplayName("and the frame rate is drawn before the first counter report has arrived")
    void theFrameRateArrivesBeforeTheCounters() {
        // The counters are written once a second by a counting renderer; the frame rate is the game's own
        // and is there on the first frame. An overlay that waited for the counters would appear a second
        // after the gesture that asked for it.
        assertArrayEquals(new String[] { "fps 60   16 ms   100%" },
                QuestBookScreen.devOverlayLines(true, new String[0], 60, 16_500_000L, 100));
    }

    @Test
    @DisplayName("the zoom is on the frame-rate line, because every reading is taken at one")
    void theZoomIsOnTheFrameRateLine() {
        // **The header's percentage is quest completion, not scale.** Filing a canvas reading under the wrong
        // zoom is a mistake this project has already made once, and the reading's whole meaning depends on
        // which zoom it was taken at -- the canvas's cost is a function of exactly that.
        assertArrayEquals(new String[] { "fps 144   6 ms   35%" },
                QuestBookScreen.devOverlayLines(true, new String[0], 144, 6_000_000L, 35),
                "the scale travels with the frame it describes");
    }

    @Test
    @DisplayName("with no canvas drawn the scale is absent rather than zero")
    void noCanvasMeansNoScale() {
        // -1 is the caller's "there is no canvas on this screen": a screen that draws no canvas has no zoom,
        // and printing `0%` would be a reading of something that does not exist.
        assertArrayEquals(new String[] { "fps 144   6 ms" },
                QuestBookScreen.devOverlayLines(true, new String[0], 144, 6_000_000L, -1),
                "an absent zoom prints nothing, not a zero");
    }
}
