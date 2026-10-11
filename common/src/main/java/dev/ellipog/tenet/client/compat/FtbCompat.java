package dev.ellipog.tenet.client.compat;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.client.hud.HudElement;

import java.util.function.Predicate;

/**
 * Where the quest book's button ships when FTB Library's sidebar is around.
 *
 * <h2>Why this exists</h2>
 *
 * <p>FTB Library draws a dynamic button column from the window's top-left corner: 16-pixel icons on a
 * 17-pixel grid starting at {@code (1, 1)}, so its first icon covers {@code 1..17} in both axes. The
 * book's button ships at window {@code (2, 2)} and is 16 wide, so the two sit on top of each other
 * whenever that sidebar is present -- see tenet#25. Shifting right rather than down clears the whole
 * column however many rows it holds: a downward nudge only clears a fixed count, while a column is
 * always 17 wide no matter how tall it grows.
 *
 * <h2>Why a mod id and never an import</h2>
 *
 * <p>Probed by mod id through {@code ArmatureApi.platform().isModLoaded}, the same seam the recipe
 * viewers use -- see {@code Viewers}. No class from that mod is named here, so a client without it
 * loses nothing and {@code check_integration.py} needs no new entry: its markers are Java packages,
 * and a bare mod id cannot match one.
 *
 * <h2>What this does not know</h2>
 *
 * <p>Only presence. That sidebar can be moved to any corner, given extra columns, or switched off in
 * its own config, and none of that is readable from here without naming its packages. Presence
 * therefore assumes the default top-left column; a player who moved or hid that sidebar drags this
 * button back once with {@code H} and the choice persists in {@code hud.json} like any other move.
 * An inventory-anchored button is panel-relative and never shifted: a window corner means nothing to
 * it.
 */
public final class FtbCompat {

    /** The mod id whose presence moves the shipped position. A constant on purpose: callers probe with it. */
    public static final String MOD_ID = "ftblibrary";

    /**
     * Where the button ships with that sidebar around: two pixels in, one sidebar cell (17) plus a
     * four-pixel gap across, at the same height. {@code 2 + 16 + 4}: the button's own left inset, its
     * width, and the gap {@code InventoryPanel.UNDER_GAP} already uses elsewhere.
     */
    public static final int SHIFTED_X = 22;

    /** Unmoved vertically: the column grows downward, so sideways is what clears it. */
    public static final int SHIFTED_Y = 2;

    private FtbCompat() {
    }

    /**
     * Whether that mod is loaded, asked through the platform.
     *
     * <p>False when the platform is not there yet -- a test JVM is exactly that -- which is also the
     * honest answer before a loader has reached it: nothing can be loaded if the loader is not there.
     */
    public static boolean present() {
        return present(FtbCompat::loaded);
    }

    /** The same question over a stub, so the arithmetic is testable with nothing installed. */
    public static boolean present(Predicate<String> loaded) {
        try {
            return loaded.test(MOD_ID);
        }
        catch (RuntimeException e) {
            Constants.LOG.debug("tenet: cannot ask whether '{}' is loaded ({}); assuming not",
                    MOD_ID, e.toString());
            return false;
        }
    }

    /**
     * Where an element ships: its table default, except the book's button with that sidebar around,
     * which ships shifted right of the column.
     *
     * <p>Only the button, and only its window position: every other element has no corner to clear,
     * and a panel-anchored button is measured from the inventory rather than the window.
     */
    public static int effectiveDefaultX(HudElement element, Predicate<String> loaded) {
        if (element == HudElement.INVENTORY_BUTTON && present(loaded)) {
            return SHIFTED_X;
        }
        return element.defaultX();
    }

    /** The same, vertically. */
    public static int effectiveDefaultY(HudElement element, Predicate<String> loaded) {
        if (element == HudElement.INVENTORY_BUTTON && present(loaded)) {
            return SHIFTED_Y;
        }
        return element.defaultY();
    }

    /** Where an element ships on this client. */
    public static int effectiveDefaultX(HudElement element) {
        return effectiveDefaultX(element, FtbCompat::loaded);
    }

    /** The same, vertically. */
    public static int effectiveDefaultY(HudElement element) {
        return effectiveDefaultY(element, FtbCompat::loaded);
    }

    private static boolean loaded(String modId) {
        try {
            return ArmatureApi.platform().isModLoaded(modId);
        }
        catch (RuntimeException e) {
            Constants.LOG.debug("tenet: cannot ask whether '{}' is loaded ({}); assuming not",
                    modId, e.toString());
            return false;
        }
    }
}
