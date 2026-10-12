package dev.ellipog.tenet.client;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The settings card's rows, composed without a client.
 *
 * <h2>What is on this card, and what deliberately is not</h2>
 *
 * <p>The card holds the rows about <b>this player's own view</b>: how large the text draws, and where their
 * HUD things sit. The look is the pack's — a theme list, a radius and a follow-the-pack row sat here once and
 * moved to the tools panel, where an author edits colours — and Motion is a switch in that same panel,
 * beside the other mode switches. What a player is owed on this card is what is about their own eyes and
 * their own screen rather than about the book's appearance, and a placed button is the second of those.
 *
 * <h2>Why this is a class</h2>
 *
 * <p>The composition is arithmetic over the values {@code Look} reports, so it lives here where it can be
 * asserted without a screen, and the screen turns an entry into a widget. It was written with one row and its
 * note said the shape was kept because "the row list is what a test can hold and what a second row would
 * join"; the HUD row is that second row, and it joined without this class changing shape.
 *
 * <h2>Why the label is a {@code Component}</h2>
 *
 * <p>Because it was a bare string and only English ever saw it: the first row read {@code "Text size"} in
 * every language, and the translated text a player did see was the slider's own message beside it. A
 * component is what the screen draws and what a translator can reach, and the one existing row is unchanged
 * from the player's side by being wrapped rather than rewritten.
 */
public final class SettingsLayout {

    /** The text-size row. */
    public static final String TEXT_KEY = "text";

    /** The HUD row: the way into the editor for a player who never opens their Controls list. */
    public static final String HUD_KEY = "hud";

    /**
     * The chapter-list row: docked down the panel's left, or floating as a box on the canvas.
     *
     * <p>A toggle rather than an action: the row names the placement and the strip carries its
     * switch, so the state reads at a glance instead of needing the word found every time. The
     * third thing on this card that is about the player's own screen rather than the book's
     * appearance: where the navigation sits is a placement, like the HUD row's, and the header
     * is the book's busiest row — a choice made once lives here rather than in it.
     */
    public static final String SIDEBAR_KEY = "sidebar";

    /**
     * One row.
     *
     * @param key   the widget key the screen registers its control under
     * @param label what it reads as — the value is the slider's own message, so it can follow the thumb
     *              without a rebuild
     */
    public record Entry(String key, Component label) {
    }

    private SettingsLayout() {
    }

    /**
     * The card's rows, in order: the text size, the chapter list's placement, then the HUD editor.
     *
     * @param textScale the player's text size, as a factor; the slider reads it, the row does not
     */
    public static List<Entry> entries(double textScale) {
        return List.of(
                new Entry(TEXT_KEY, Component.translatable("tenet.screen.accessibility_settings")),
                new Entry(SIDEBAR_KEY, Component.translatable("tenet.screen.sidebar_floating_list")),
                new Entry(HUD_KEY, Component.translatable("tenet.screen.hud_layout")));
    }
}
