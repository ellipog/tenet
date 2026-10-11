package dev.ellipog.tenet.client;

/**
 * How the book's chapter list is shown: its width, where it lives, and whether it is out.
 *
 * <h2>Three answers, and what each decides</h2>
 *
 * <p>{@code width} is the player's for the expanded list, in GUI pixels. {@code floating} says
 * whether the list is docked down the panel's left (the old column, exactly as it was) or sits
 * as a box on the canvas. {@code pinned} says whether a floating box stays open or slides away
 * when the pointer leaves it; docked, there is nothing to pin and it decides nothing.
 * {@code collapsed} says whether the list has slid away to its sticking-out sliver. Docked, the
 * list is always expanded either way — the old mode cannot collapse.
 *
 * <p>All four default to a floating pinned box, expanded, 156 wide — so a file that never
 * heard of any of them draws the box rather than the old docked column. They are the player's,
 * read from {@code config/tenet/client.json} through {@code DevMode}. Docking is still one
 * switch away in the settings card, and a docked list is always expanded either way.
 *
 * <p>What is <b>not</b> here is the peek: hovering the sliver shows the full list without
 * changing any of these. The screen resolves that before asking for a rectangle (a collapsed
 * state that is being peeked draws as a floating expanded one), so the geometry never sees a
 * state that is half held by the pointer. A rectangle derived from a hover would be a layout
 * that no file describes and no test can sweep.
 *
 * <p>Game-free, like everything it feeds: integers and booleans in, answers out. No Minecraft.
 */
public record SidebarState(int width, boolean floating, boolean pinned, boolean collapsed) {

    /** A floating pinned box, expanded, 156 wide. What a missing file reads as. */
    public static final SidebarState DEFAULT = new SidebarState(BookGeometry.SIDEBAR_WIDTH, true, true,
            false);

    /** Whether the list is drawn at all beyond its sliver. False while slid away. */
    public boolean expanded() {
        return !collapsed;
    }

    /** Whether the list is docked down the panel's left rather than floating over the map. */
    public boolean docked() {
        return !floating;
    }
}
