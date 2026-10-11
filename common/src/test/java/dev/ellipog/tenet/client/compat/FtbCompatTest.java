package dev.ellipog.tenet.client.compat;

import dev.ellipog.tenet.client.hud.HudElement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the book's button ships with FTB Library's sidebar around.
 *
 * <h2>Why predicates and never a loader</h2>
 *
 * <p>The decision is the feature and it cannot be tested by installing a mod: {@code present} takes the
 * same predicate {@code Viewers.choose} takes, so the tested thing and the used thing are one function
 * and a test JVM -- where the platform is absent -- reads as "not loaded" rather than crashing.
 */
@DisplayName("the FTB-aware button default")
class FtbCompatTest {

    private static final Predicate<String> ABSENT = mod -> false;
    private static final Predicate<String> PRESENT = FtbCompat.MOD_ID::equals;

    @Test
    @DisplayName("without that sidebar everything ships where the table says")
    void absentIsTheTable() {
        for (HudElement element : HudElement.values()) {
            assertEquals(element.defaultX(), FtbCompat.effectiveDefaultX(element, ABSENT),
                    element + " keeps its table position without that mod");
            assertEquals(element.defaultY(), FtbCompat.effectiveDefaultY(element, ABSENT),
                    element + " keeps its table position without that mod");
        }
        assertFalse(FtbCompat.present(ABSENT));
    }

    @Test
    @DisplayName("with that sidebar only the book's button moves, one cell right")
    void presentMovesOnlyTheButton() {
        assertTrue(FtbCompat.present(PRESENT));

        assertEquals(FtbCompat.SHIFTED_X,
                FtbCompat.effectiveDefaultX(HudElement.INVENTORY_BUTTON, PRESENT),
                "right of a 17-wide column from a 2-pixel inset with a 4-pixel gap");
        assertEquals(FtbCompat.SHIFTED_Y,
                FtbCompat.effectiveDefaultY(HudElement.INVENTORY_BUTTON, PRESENT),
                "the column grows downward, so the height does not move");

        for (HudElement element : HudElement.values()) {
            if (element == HudElement.INVENTORY_BUTTON) {
                continue;
            }
            assertEquals(element.defaultX(), FtbCompat.effectiveDefaultX(element, PRESENT),
                    element + " has no corner to clear");
            assertEquals(element.defaultY(), FtbCompat.effectiveDefaultY(element, PRESENT),
                    element + " has no corner to clear");
        }
    }

    @Test
    @DisplayName("the shifted spot clears the column's first cell")
    void shiftedClearsTheColumn() {
        int tableRight = HudElement.INVENTORY_BUTTON.defaultX() + HudElement.INVENTORY_BUTTON.width();
        assertTrue(FtbCompat.SHIFTED_X >= tableRight,
                "the shipped button starts where the unshifted one ends");
        assertEquals(22, FtbCompat.SHIFTED_X, "2 + 16 + 4: inset, button width, gap");
        assertEquals(2, FtbCompat.SHIFTED_Y, "same height it always shipped at");
    }

    @Test
    @DisplayName("a loader that throws reads as absent")
    void throwingReadsAsAbsent() {
        Predicate<String> failing = mod -> {
            throw new IllegalStateException("platform not ready");
        };
        assertFalse(FtbCompat.present(failing),
                "a caller ordering fault is a default, never a crash");
        assertEquals(HudElement.INVENTORY_BUTTON.defaultX(),
                FtbCompat.effectiveDefaultX(HudElement.INVENTORY_BUTTON, failing));
    }
}
