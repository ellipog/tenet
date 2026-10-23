package dev.ellipog.tenet.client.dev;

import dev.ellipog.armature.client.Look;
import dev.ellipog.tenet.client.ClientAppearance;
import dev.ellipog.armature.client.ArmatureControlStyle;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.Theme;
import dev.ellipog.armature.client.ui.ThemeToken;
import dev.ellipog.armature.client.ui.Themes;
import dev.ellipog.armature.client.ui.art.CanvasBackgroundArt;
import dev.ellipog.armature.client.ui.inspect.InspectLayout;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.armature.client.ui.shape.Shape;
import dev.ellipog.tenet.client.BookGeometry;
import dev.ellipog.tenet.quest.QuestShape;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the tools panel looks like: the preview, the list, and the band that edits one colour.
 *
 * <h2>Why the drawing is its own class</h2>
 *
 * <p>Because {@code QuestBookScreen} is a screen: nothing in it can be instantiated by a test, and it is
 * already the largest file in either mod. Everything here takes a {@link GuiRenderer} and plain numbers,
 * so the panel's appearance is one file that can be read at once — and the arithmetic it reads is
 * {@link ToolsLayout}'s, which is asserted.
 *
 * <h2>The preview, and what it is honest about</h2>
 *
 * <p>It draws one sample per tab: the graph's surfaces (a canvas, nodes, a card, a button, a tooltip, a
 * scrollbar mark) on the chapter tab, and the book's own chrome (panel, header, sidebar row, control,
 * scrollbar) on the book tab — see {@link ToolsLayout.Sample}. Selecting a colour rings the region its
 * <b>group</b> paints — eight regions for eight groups — because that is the question the list raises:
 * not "what is {@code rowHover}" but "which part of this screen does it change".
 *
 * <p>It is a <b>sample, not the book</b>: the proportions are its own, the text is literal, and the node is
 * always the rounded shape. A preview that claimed to be the real screen would be a second description of
 * it, and this one says what it is.
 */
public final class ToolsPanel {

    /**
     * The four colours a palette row shows: the surface it paints, the strip above it, its text and its
     * accent. Enough to tell two palettes apart at a glance, and few enough to fit beside a name.
     */
    private static final List<String> PALETTE_TOKENS = List.of("panel", "raised", "title", "accent");

    /** Between two of a palette row's swatches. Their size is the row's height less its inset. */
    private static final int PALETTE_GAP = 3;

    private ToolsPanel() {
    }

    /**
     * What the panel needs to draw: the palette in force, the canvas background it samples, and
     * which palette row is the current one.
     *
     * <p>Three things, because three things are read. The record used to carry the selected token, the
     * status line and the radius, all of which the drawing stopped asking for when the band and the
     * steppers left -- a record that carries what nobody reads is a list of fields to keep passing.
     *
     * @param theme the palette the chips and the texture row read from: the player's own theme, or the
     *     open chapter's composed one while the chapter target is on. Passed in rather than read from
     *     {@code ClientAppearance} here, so this panel has one source of truth and the chapter target is
     *     a choice made by the screen rather than a branch in every drawing method.
     * @param background the canvas background in force for that target, for the texture row's thumbnail
     * @param selectedPalette the palette id the list marks, or null when none is marked. The screen's
     *     answer, not the theme's name: a chapter with no theme still marks {@code default} while its
     *     composed palette wears the fallback's name.
     */
    public record State(dev.ellipog.armature.client.ui.Theme theme,
                        dev.ellipog.armature.client.ui.CanvasBackground background,
                        String selectedPalette) {
    }

    /**
     * Draws everything that is not a widget.
     *
     * <p>The drawer's title, the rows' labels, the palette strips and the colour chips — the panel they
     * sit on, and the ink a widget cannot draw. Every field, choice and menu button is a widget drawn by
     * the widget pass; what is left here is what has no widget to belong to.
     */
    public static void draw(GuiRenderer r, ToolsLayout.Frame frame, Viewport list, Layout layout,
                            List<ToolsLayout.Action> rows, State state, int mouseX, int mouseY) {
        drawChrome(r, frame, state);
        drawRows(r, frame.list(), list, layout, rows, state, mouseX, mouseY);
    }

    /**
     * The panel's own surface: the thing every tab draws the same way.
     *
     * <p>One method because both tabs paint it. It used to draw the one-line status too; that line is
     * gone -- every message it carried is toasted as it happens, so it was a second copy in a band of
     * its own.
     */
    public static void drawChrome(GuiRenderer r, ToolsLayout.Frame frame, State state) {
        ArmatureTheme.panel(r, frame.panel().x(), frame.panel().y(), frame.panel().width(),
                frame.panel().height(), ArmatureTheme.panel(), ArmatureTheme.panelEdge());
    }

    /**
     * The list's rows, drawn from any layout that carries them.
     *
     * <p>Public because the chapter tab shows the same appearance rows under a different layout: it
     * composes the book tab's sections above the chapter's content, and this is the one place those
     * sections are drawn, so the two tabs cannot disagree about what a row looks like.
     */
    public static void drawRows(GuiRenderer r, BookGeometry.Rect listRect, Viewport list, Layout layout,
                                List<ToolsLayout.Action> rows, State state, int mouseX, int mouseY) {
        drawRows(r, listRect, list, layout, rows, state, mouseX, mouseY, InspectLayout.Mode.SIDE_BY_SIDE);
    }

    /**
     * The same rows, composed the way the tab asked for it.
     *
     * <p>See {@link ToolsLayout#stack(List, InspectLayout.Mode)} for why the mode reaches the drawing at
     * all: a stacked row's label sits in its own band, above the control's, and the band comes from the
     * same {@code InspectLayout} call whoever places the widget makes.
     */
    public static void drawRows(GuiRenderer r, BookGeometry.Rect listRect, Viewport list, Layout layout,
                                List<ToolsLayout.Action> rows, State state, int mouseX, int mouseY,
                                InspectLayout.Mode mode) {
        Measure measure = textMeasure(r);
        try (GuiRenderer.Scoped clip = r.clip(listRect.x(), listRect.y(), listRect.right(),
                listRect.bottom())) {
            for (ToolsLayout.Action row : rows) {
                Slot slot = layout.slot(row.key());
                if (slot == null) {
                    continue;
                }
                Slot onScreen = ToolsLayout.onScreen(list, slot);
                if (ToolsLayout.CANVAS_TEXTURE.equals(row.key())) {
                    // Three controls in one row: the label, the file's own picture, and the browse
                    // button. The id's field between them is a widget, placed by the layout's own
                    // `textureField` -- so what is drawn here is exactly what a widget cannot say.
                    drawTextureRow(r, row, slot, onScreen, measure, state);
                    continue;
                }
                if (ToolsLayout.BOOK_TITLE.equals(row.key()) || ToolsLayout.BOOK_ICON.equals(row.key())) {
                    // A field row: the label is the panel's, the control is a widget placed by the
                    // layout's own `valueField`, and neither may draw over the other.
                    r.text(Measure.truncate(Labels.of(row.label()), ToolsLayout.LABEL_ROOM - 4, measure),
                            onScreen.x() + 2, textY(slot, onScreen, r), ArmatureTheme.body());
                    continue;
                }
                // A switch on the kind rather than a chain of `isControl()` tests, and exhaustive on purpose:
                // the radius row used to fall through this to the *switch label* branch below, because its
                // kind is STEPPER and the middle test asked `kind == ROW`. The row then drew its label and
                // nothing else -- no arrows, no number -- while the click path, which reads the kind not at
                // all, worked fine: *"i can click them but not see them"*. A switch over the enum means the
                // next kind added cannot quietly become a label.
                switch (row.kind()) {
                    case HEADING -> drawHeading(r, row, slot, onScreen, measure);
                    case ROW -> {
                        // A row that is itself the control. Its *name* is the widget's in both
                        // compositions -- see the note on `drawRow` -- so what is here is what the name
                        // cannot say (a palette's four swatches, a colour's hex). Stacked, the widget simply
                        // gets the whole row rather than a band beneath a label, which is why this needs no
                        // stacked arm: there is nothing extra to draw.
                        if (mode != InspectLayout.Mode.STACKED) {
                            drawRow(r, row, slot, onScreen, measure, state, mouseX, mouseY);
                        }
                    }
                    case SWITCH -> {
                        if (mode == InspectLayout.Mode.STACKED) {
                            drawStackedLabel(r, row, slot, list, measure);
                        }
                        else {
                            drawSwitchLabel(r, row, slot, onScreen, measure);
                        }
                    }
                    // A field's label, and a choice's: the control beside it is a widget, so this is
                    // the label and nothing else -- and stacked, the label has the whole band, because
                    // there is no box beside it to leave room for. A text row and a row whose button opens
                    // something are the same shape: a label, and a control the screen places.
                    case FIELD, CHOICE, TEXT, BUTTON -> {
                        if (row.labelOnButton()) {
                            // The button says it; see `Action.action`.
                            break;
                        }
                        if (mode == InspectLayout.Mode.STACKED) {
                            drawStackedLabel(r, row, slot, list, measure);
                        }
                        else {
                            drawRowLabel(r, row, slot, onScreen, measure);
                        }
                    }
                    // The one kind with no control: both lines are the panel's, in either composition.
                    case VALUE -> drawValue(r, row, slot, onScreen, measure);
                    case PAIR -> {
                        // Two labels, one per half, from the same split the pair widget uses -- see
                        // `ToolsLayout.pairLeft`. Stacked, the labels share the label band above the
                        // control's, the way every other stacked label takes its own band: splitting the
                        // whole row would centre them over the boundary between the label and the boxes.
                        if (mode == InspectLayout.Mode.STACKED) {
                            Slot label = InspectLayout.labelBand(slot);
                            Slot seen = InspectLayout.labelBand(onScreen);
                            drawRowLabel(r, row, ToolsLayout.pairLeft(label),
                                    ToolsLayout.pairLeft(seen), measure);
                            drawRowLabel(r, row.right(), ToolsLayout.pairRight(label, row.right().key()),
                                    ToolsLayout.pairRight(seen, row.right().key()), measure);
                        }
                        else {
                            drawRowLabel(r, row, ToolsLayout.pairLeft(slot),
                                    ToolsLayout.pairLeft(onScreen), measure);
                            drawRowLabel(r, row.right(), ToolsLayout.pairRight(slot, row.right().key()),
                                    ToolsLayout.pairRight(onScreen, row.right().key()), measure);
                        }
                    }
                    case CHIP -> {
                        // A colour row's label goes where every other row's does, and the chip goes in the
                        // band the row gives it: the row itself side by side, the control's band under a
                        // stacked label. `chipOf` is that one answer, so what is painted here and what the
                        // screen's press tests are the same rectangle -- see its own note for the tall-chip
                        // fault that made the distinction necessary.
                        drawChip(r, row, ToolsLayout.chipOf(onScreen, mode), state, mouseX, mouseY);
                        if (mode == InspectLayout.Mode.STACKED) {
                            drawStackedLabel(r, row, slot, list, measure);
                        }
                        else {
                            drawChipLabel(r, row, slot, onScreen, measure);
                        }
                    }
                    // Loud rather than quiet, because quiet is what happened: the radius row fell through
                    // this dispatch into the label branch and drew as a label with no controls, and nothing
                    // anywhere said so. A new kind now fails the first time it is drawn instead.
                    default -> throw new IllegalStateException("no drawing for row kind " + row.kind());
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // The list
    // ------------------------------------------------------------------

    /** A section's name, a rule under it, and a marker for a group inside a section. */
    private static void drawHeading(GuiRenderer r, ToolsLayout.Action row, Slot slot, Slot onScreen,
                                    Measure measure) {
        // Every foldable heading draws as a section, not just the colours: the rule is what says "this
        // is a section and its name is a control", and the marker alone was too quiet to read as one --
        // which is how the Canvas heading came to look like plain text that did nothing, and did.
        boolean section = ToolsLayout.folds(row.key());
        r.text(Measure.truncate(Labels.of(row.label()), slot.width(), measure), onScreen.x(),
                onScreen.y() + (slot.height() - r.lineHeight()) / 2,
                section ? ArmatureTheme.title() : ArmatureTheme.heading());
        if (section) {
            // A rule under a section's name, which is what makes it read as a section rather than as a
            // heading that happens to be in a list.
            r.fill(onScreen.x(), onScreen.bottom() - 1, onScreen.right(), onScreen.bottom(),
                    ArmatureTheme.panelEdge());
        }
    }

    /**
     * A row that is itself the control: a theme, a group's name, or a colour.
     *
     * <p>The row's <i>name</i> is not drawn here. Every one of these rows is a widget -- flat, so it
     * paints nothing but its own label -- and the first version drew the name here as well, so every row
     * carried two copies of it a few pixels apart. The screenshot read as a font fault and was a
     * duplicated string.
     */
    private static void drawRow(GuiRenderer r, ToolsLayout.Action row, Slot slot, Slot onScreen,
                                Measure measure, State state, int mouseX, int mouseY) {
        String palette = ToolsLayout.paletteId(row.key());
        boolean hovered = onScreen.contains(mouseX, mouseY);

        if (palette != null) {
            // A palette row: the row is the widget and the name is its label, so what this adds is what
            // the name cannot say -- what the palette looks like. Four boxes and no hex: the hex is the
            // colour rows' business, and four strings would not fit beside a name.
            //
            // Resolved by name from the catalogue rather than read from the state's theme: the state is
            // what is *in force* (the player's, or the chapter's while the chapter target is on), and a
            // row that previewed the current theme under every name would show sixteen identical strips.
            Theme theme = Themes.any(palette);
            if (state.selectedPalette() != null && state.selectedPalette().equals(palette)) {
                // The selection box lives in this content pass, under the swatches, rather than on
                // the row's widget: a selected button draws its opaque box in the later widget pass,
                // over whatever this drew — which is how the current palette lost its preview in
                // every theme. Same values the widget used, so flat looks unchanged.
                ArmatureTheme.panel(r, onScreen.x(), onScreen.y(), onScreen.width(), onScreen.height(),
                        ArmatureControlStyle.fill(ArmatureControlStyle.Variant.SELECTED, true, false, false),
                        ArmatureControlStyle.edge(ArmatureControlStyle.Variant.SELECTED, true, false, false));
            }
            if (hovered) {
                r.fill(onScreen.x(), onScreen.y(), onScreen.right(), onScreen.bottom(),
                        Colour.translucent(ArmatureTheme.rowHover(), 0.22F));
            }
            if (theme == null) {
                return;   // a name this build does not have: the label still says which one
            }
            int size = slot.height() - 4;
            int x = onScreen.right() - 4 - (size * PALETTE_TOKENS.size() + PALETTE_GAP * 3);
            int y = onScreen.y() + 2;
            for (String id : PALETTE_TOKENS) {
                int argb = theme.colour(id);
                r.fill(x - 1, y - 1, x + size + 1, y + size + 1, ArmatureTheme.panelEdge());
                r.fill(x, y, x + size, y + size, argb);
                x += size + PALETTE_GAP;
            }
            return;
        }
    }

    // ------------------------------------------------------------------
    // The band
    // ------------------------------------------------------------------


    /** A switch's label. Its button is a widget, in the strip the row reserved. */
    private static void drawSwitchLabel(GuiRenderer r, ToolsLayout.Action row, Slot slot, Slot onScreen,
                                        Measure measure) {
        r.text(Measure.truncate(Labels.of(row.label()),
                        Math.max(0, slot.width() - ToolsLayout.STRIP_WIDTH - 8), measure),
                onScreen.x() + 2, textY(slot, onScreen, r), ArmatureTheme.body());
    }

    /**
     * A row's label, cut to the room its control leaves.
     *
     * <p>One rule for three kinds, because the control's room is the same question in each: a field's box
     * is right-aligned inside its row ({@code ScrubField.BOX_WIDTH}), a choice's is the row's right side
     * after {@link ToolsLayout#LABEL_ROOM}, and a pair half is half a row with the same right-aligned box.
     * The label keeps what is left, less a small gap, so a long name is truncated rather than drawn
     * under the control beside it.
     */
    private static void drawRowLabel(GuiRenderer r, ToolsLayout.Action row, Slot slot, Slot onScreen,
                                     Measure measure) {
        // The room the control leaves. A chooser, a text box and a picker button are all placed by
        // `ToolsLayout.valueField(slot, LABEL_ROOM)` -- the same number the layout reserved -- while a
        // numeric field's box is right-aligned at its own width (`ScrubField.BOX_WIDTH`).
        int room = switch (row.kind()) {
            case CHOICE, TEXT, BUTTON -> ToolsLayout.LABEL_ROOM;
            default -> Math.min(ScrubField.BOX_WIDTH, Math.max(0, slot.width() / 2));
        };
        int width = Math.max(0, slot.width() - room - 6);
        r.text(Measure.truncate(Labels.of(row.label()), width, measure), onScreen.x() + 2,
                textY(slot, onScreen, r), ArmatureTheme.body());
    }

    /**
     * A row's label in the stacked composition: its own band, and the whole width of it.
     *
     * <p>There is no strip to stop short of, which is the whole of what stacking buys a narrow dock -- the
     * label gets the row's width instead of what a control's box leaves, so a long field name
     * ("Default Prerequisite Mode") is drawn rather than truncated. The band is
     * {@link InspectLayout#labelBand}, the same call whoever places the widget makes, so the label and the
     * control cannot disagree about where the line between them is.
     */
    private static void drawStackedLabel(GuiRenderer r, ToolsLayout.Action row, Slot slot, Viewport list,
                                         Measure measure) {
        Slot band = InspectLayout.onScreen(list, InspectLayout.labelBand(slot));
        r.text(Measure.truncate(Labels.of(row.label()), band.width(), measure), band.x(),
                textY(band, band, r), ArmatureTheme.body());
    }

    /**
     * A read-only row: its label at the left, and the fact it carries from the middle.
     *
     * <p>The one kind with no control and no widget to place. The value is drawn from the row's middle to
     * its right edge and in the faint ink a secondary fact takes -- the arrangement the inspector's
     * read-only rows already use, kept because these are the same rows: a chapter's description, and the
     * quests it lists. Both halves are truncated, since both are a file's own text.
     */
    private static void drawValue(GuiRenderer r, ToolsLayout.Action row, Slot slot, Slot onScreen,
                                  Measure measure) {
        int half = Math.max(0, slot.width() / 2);
        if (row.label().isEmpty() && row.value() != null && !row.value().isEmpty()) {
            // A row with no name is a line to read -- a pack problem, mostly -- and gets the whole width.
            // In the value column it was cut to half the dock, which for a `file:line: message` left
            // the file's path and nothing of what was wrong with it.
            r.text(Measure.truncate(row.value(), Math.max(0, onScreen.width()), measure), onScreen.x(),
                    textY(slot, onScreen, r), ArmatureTheme.faint());
            return;
        }
        r.text(Measure.truncate(Labels.of(row.label()), Math.max(0, half - 4), measure), onScreen.x(),
                textY(slot, onScreen, r), ArmatureTheme.body());
        String value = row.value();
        if (value == null || value.isEmpty()) {
            return;
        }
        int valueX = onScreen.x() + half;
        r.text(Measure.truncate(value, Math.max(0, onScreen.right() - valueX), measure), valueX,
                textY(slot, onScreen, r), ArmatureTheme.faint());
    }

    /** The colour a row carries, or null when it carries none. See {@link #drawChip}. */
    private static Integer ownColour(String value) {
        if (value == null || value.isBlank() || !value.startsWith("#")) {
            return null;
        }
        return dev.ellipog.tenet.quest.Argb.parseHex(value).isPresent()
                ? dev.ellipog.tenet.quest.Argb.parseHex(value).getAsInt() : null;
    }

    /**
     * A colour row's chip: swatch, hex, alpha -- which is the whole control.
     *
     * <p>The redesign's replacement for the docked band: a colour is edited where it is named, and the
     * chip's press opens the picker (the screen hit-tests the same rectangle this draws, from
     * {@link ToolsLayout#chipOf}). The alpha travels in the chip because a pattern's strength is the ink's
     * alpha, and leaving it out would make the chip a two-thirds description of its value.
     *
     * <p><b>The rectangle is the caller's and the label is not this method's.</b> A chip's box depends on
     * the composition its row was built in -- the row itself side by side, the control's band under a
     * stacked label -- so deriving it here would be a second place that knows, and the label belongs to
     * whichever band the row gave it: {@code drawChipLabel} side by side, {@code drawStackedLabel} under a
     * stacked name.
     */
    private static void drawChip(GuiRenderer r, ToolsLayout.Action row, BookGeometry.Rect chip,
                                 State state, int mouseX, int mouseY) {
        String token = ToolsLayout.tokenId(row.key());
        // A chip's colour is the theme token it names, or -- when the row carries one -- the colour in the
        // row's own value. The second case is what lets a *field* be a chip: an element's fill is a colour in a
        // file rather than a token in a theme, and the control an author already knows for a colour is this one.
        Integer own = ownColour(row.value());
        if (own == null && token == null) {
            return;
        }
        int argb = own != null ? own : state.theme().colour(token);
        boolean hovered = chip.contains(mouseX, mouseY);
        if (hovered) {
            r.fill(chip.x(), chip.y(), chip.right(), chip.bottom(), ArmatureTheme.rowHover());
        }
        int size = Math.max(8, chip.height() - 4);
        int swatchY = chip.y() + (chip.height() - size) / 2;
        r.fill(chip.x() + 2, swatchY, chip.x() + 2 + size, swatchY + size, argb);
        String hex = String.format("#%06X", argb & 0xFFFFFF);
        String alpha = Math.round((argb >>> 24) / 255F * 100F) + "%";
        int line = chip.y() + (chip.height() - r.lineHeight()) / 2;
        r.text(hex, chip.x() + size + 6, line, ArmatureTheme.body());
        r.text(alpha, chip.right() - 2 - r.textWidth(alpha), line, ArmatureTheme.faint());
    }

    /**
     * A colour row's name, to the left of the chip it is the control of.
     *
     * <p>Side by side only. Stacked, the name has a band of its own and gets the row's whole width, which is
     * what {@code drawStackedLabel} is for -- and it is the same split every other labelled kind in this
     * dispatch makes.
     */
    private static void drawChipLabel(GuiRenderer r, ToolsLayout.Action row, Slot slot, Slot onScreen,
                                      Measure measure) {
        int room = Math.max(0, ToolsLayout.chip(onScreen).x() - onScreen.x() - 6);
        r.text(Measure.truncate(Labels.of(row.label()), room, measure), onScreen.x() + 2,
                textY(slot, onScreen, r), ArmatureTheme.body());
    }



    /**
     * The texture row: the label, the file's own picture, and the browse button.
     *
     * <p>Three controls because an id is two things at once — a string only readable when typed, and a
     * picture only judgeable when seen — so the row carries the paste and the picture both. The field
     * between them is a widget, placed by {@link ToolsLayout#textureField}; everything here is what a
     * widget cannot draw.
     */
    private static void drawTextureRow(GuiRenderer r, ToolsLayout.Action row, Slot slot, Slot onScreen,
                                       Measure measure, State state) {
        r.text(Measure.truncate(Labels.of(row.label()), ToolsLayout.LABEL_ROOM - 4, measure),
                onScreen.x() + 2, textY(slot, onScreen, r), ArmatureTheme.body());
        drawTextureThumb(r, onScreen, state);
        Slot browse = ToolsLayout.textureBrowse(onScreen);
        r.text("\u2026", browse.x() + (browse.width() - r.textWidth("\u2026")) / 2,
                textY(browse, browse, r), ArmatureTheme.body());
    }

    /** The row's own picture box: the panel asks the renderer directly, once a frame. */
    private static void drawTextureThumb(GuiRenderer r, Slot onScreen, State state) {
        drawTextureThumb(r, ToolsLayout.textureThumb(onScreen),
                state.background().image().texture(), null);
    }

    /**
     * A texture drawn at its own aspect inside a box, shared by the row and the picker's list.
     *
     * <p>The cache is the caller's and may be null: the row asks once a frame and the picker asks once
     * per visible row, and both would rather not read a file header per frame -- but a cache the
     * drawing owned would be a second place the screen's state lives, which is the split this codebase
     * keeps making. Null means "ask the renderer every time", which is correct and merely slower.
     */
    public static void drawTextureThumb(GuiRenderer r, Slot box, String texture,
                                        Map<Identifier, Optional<GuiRenderer.TextureSize>> cache) {
        r.fill(box.x(), box.y(), box.right(), box.bottom(), ArmatureTheme.recessed());
        if (texture == null || texture.isEmpty()) {
            return;
        }
        Identifier id = Identifier.tryParse(texture);
        if (id == null) {
            return;
        }
        Optional<GuiRenderer.TextureSize> size =
                cache == null ? r.textureSize(id) : cache.computeIfAbsent(id, r::textureSize);
        if (size.isEmpty()) {
            r.text("?", box.x() + (box.width() - r.textWidth("?")) / 2,
                    box.y() + (box.height() - r.lineHeight()) / 2, ArmatureTheme.faint());
            return;
        }
        GuiRenderer.TextureSize dims = size.get();
        float scale = Math.min(box.width() / (float) Math.max(1, dims.width()),
                box.height() / (float) Math.max(1, dims.height()));
        int width = Math.max(1, Math.round(dims.width() * scale));
        int height = Math.max(1, Math.round(dims.height() * scale));
        r.scaled(id, box.x() + (box.width() - width) / 2, box.y() + (box.height() - height) / 2,
                width, height, 0F, 0F, dims.width(), dims.height(), dims.width(), dims.height(),
                0xFFFFFFFF);
    }


    /**
     * The word an image fit is shown as.
     *
     * <p>Here beside {@link #patternLabel} because it is the same kind of answer: a value the panel
     * draws, resolved from a key so a language is free to say it differently.
     */
    public static String fitLabel(dev.ellipog.armature.client.ui.CanvasBackground.Fit fit) {
        return Labels.of(switch (fit) {
            case TILE -> "tenet.dev.canvas.fit_tile";
            case COVER -> "tenet.dev.canvas.fit_cover";
        });
    }

    /**
     * The word a pattern kind is shown as, and the one a space is.
     *
     * <p>Switches over literal keys rather than string concatenation, and that is a test's shape as
     * much as a style: {@code LangSweepTest} reads keys whole, so a key built by adding an id to a
     * prefix is invisible to it and reads as an orphan. Every key here is also referenced by the
     * status line the press writes.
     */
    public static String patternLabel(dev.ellipog.armature.client.ui.CanvasBackground.Kind kind) {
        return Labels.of(switch (kind) {
            case NONE -> "tenet.dev.canvas.pattern_none";
            case DOTS -> "tenet.dev.canvas.pattern_dot_grid";
            case GRID_LINES -> "tenet.dev.canvas.pattern_grid_lines";
            case SPECKLE -> "tenet.dev.canvas.pattern_speckle";
            case HATCH -> "tenet.dev.canvas.pattern_hatch";
            // "Image", not "Texture": the row below this one is the Texture row and names the file, and
            // two rows saying the same word left a reader looking for the difference between them. This
            // value is the kind; that row is the file.
            case IMAGE -> "tenet.dev.canvas.pattern_image";
        });
    }

    public static String spaceLabel(dev.ellipog.armature.client.ui.CanvasBackground.Space space) {
        return Labels.of(switch (space) {
            case GRAPH -> "tenet.dev.canvas.space_graph";
            case SCREEN -> "tenet.dev.canvas.space_screen";
        });
    }



    /** One channel's value out of a packed colour. */
    public static int channelValue(int argb, String channel) {
        int shift = switch (channel) {
            case "R" -> 16;
            case "G" -> 8;
            case "B" -> 0;
            default -> 24;
        };
        return (argb >>> shift) & 0xFF;
    }

    /** A token's name, from the catalogue rather than from its id. */
    private static String labelOf(String tokenId) {
        ThemeToken token = ThemeToken.byId(tokenId);
        return token == null ? tokenId : token.label();
    }

    // ------------------------------------------------------------------
    // The preview
    // ------------------------------------------------------------------




    /** A row's icon slot: a recessed square with a brighter top edge, as the chapter sample draws one. */
    private static void itemSlot(GuiRenderer r, BookGeometry.Rect item) {
        r.fill(item.x(), item.y(), item.right(), item.bottom(), ArmatureTheme.recessed());
        r.fill(item.x(), item.y(), item.right(), item.y() + 1, ArmatureTheme.panelEdge());
    }

    /** A rounded control: its edge colour fills the whole shape, the face sits one pixel inside it. */
    private static void control(GuiRenderer r, BookGeometry.Rect button) {
        ArmatureTheme.fillSurface(r, button.x(), button.y(), button.width(), button.height(),
                ArmatureTheme.controlEdge(), ArmatureTheme.CORNERS_ALL);
        ArmatureTheme.fillSurface(r, button.x() + 1, button.y() + 1, Math.max(0, button.width() - 2),
                Math.max(0, button.height() - 2), ArmatureTheme.raised(), ArmatureTheme.CORNERS_ALL);
    }

    /** A floating panel: its border colour fills the shape, its fill one pixel inside. */
    private static void tooltip(GuiRenderer r, BookGeometry.Rect tooltip) {
        ArmatureTheme.fillSurface(r, tooltip.x(), tooltip.y(), tooltip.width(), tooltip.height(),
                ArmatureTheme.tooltipEdge(), ArmatureTheme.CORNERS_ALL);
        ArmatureTheme.fillSurface(r, tooltip.x() + 1, tooltip.y() + 1, Math.max(0, tooltip.width() - 2),
                Math.max(0, tooltip.height() - 2), ArmatureTheme.tooltipFill(), ArmatureTheme.CORNERS_ALL);
    }

    /** One node: fill, an edge in the state's colour, and a ring when it is the hovered one. */
    private static void node(GuiRenderer r, BookGeometry.Rect rect, int edge, int ringColour) {
        // The shape rather than its span lookup, so the panel's own layer is the one the shape keeps:
        // see ArmatureTheme.shapePanel. `QuestShape.ROUNDED.geometry()` is a field, not a construction.
        Shape rounded = QuestShape.ROUNDED.geometry();
        if (ringColour != 0) {
            ArmatureTheme.shapePanel(r, rect.x() - 1, rect.y() - 1, rect.width() + 2,
                    ArmatureTheme.nodeFill(), ringColour, rounded);
        }
        ArmatureTheme.shapePanel(r, rect.x(), rect.y(), rect.width(), ArmatureTheme.nodeFill(), edge,
                rounded);
    }


    private static BookGeometry.Rect expand(BookGeometry.Rect rect, int by) {
        return BookGeometry.Rect.at(rect.x() - by, rect.y() - by, rect.width() + by * 2,
                rect.height() + by * 2);
    }

    /** A one-pixel ring that follows a rectangle, drawn just outside it. */
    private static void ring(GuiRenderer r, BookGeometry.Rect rect, int colour) {
        r.fill(rect.x() - 1, rect.y() - 1, rect.right() + 1, rect.y(), colour);
        r.fill(rect.x() - 1, rect.bottom(), rect.right() + 1, rect.bottom() + 1, colour);
        r.fill(rect.x() - 1, rect.y(), rect.x(), rect.bottom(), colour);
        r.fill(rect.right(), rect.y(), rect.right() + 1, rect.bottom(), colour);
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static int textY(Slot slot, Slot onScreen, GuiRenderer r) {
        return onScreen.y() + (slot.height() - r.lineHeight()) / 2;
    }

    private static Measure textMeasure(GuiRenderer r) {
        return Measure.of(r::textWidth, r.lineHeight());
    }
}
