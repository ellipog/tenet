package dev.ellipog.tenet.client.dev;

import dev.ellipog.armature.client.ui.CanvasBackground;
import dev.ellipog.armature.client.ui.inspect.InspectLayout;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tenet.client.BookGeometry;
import dev.ellipog.tenet.client.DevMode;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The panel's drawing, read back from a renderer that keeps its calls.
 *
 * <h2>The one question this answers</h2>
 *
 * <p>Not "is the arithmetic right" — {@code ToolsLayoutTest} has that — but <i>"did anything get drawn
 * here"</i>. Every fault the tools panel has had was of that kind, and none of them was visible to
 * arithmetic: the radius row's arrows were built as widgets at the list's coordinates and landed outside the
 * panel; then they were drawn by nobody; then the row fell through the dispatch into the switch-label branch
 * and drew as a bare label while the hit test answered presses perfectly. Three reports from play, three
 * screenshots, and one question that would have caught each of them on the way in.
 *
 * <h2>And why every test here runs at full depth</h2>
 *
 * <p>Because the rows it draws are the appearance ones -- a palette swatch row, a foldable section's rule --
 * and Normal mode leaves the Book tab with its three switches. See {@code AdvancedTest} for what that gate
 * hides.
 */
@DisplayName("the tools panel's drawing")
class ToolsPanelTest {

    @BeforeEach
    void atFullDepth() {
        DevMode.setAdvanced(true);
    }

    @AfterEach
    void forgetTheDepth() {
        // A static flag: a test that leaves it set changes the next class to run.
        DevMode.reset();
    }

    private static final Measure MEASURE = Measure.monospace(6, 9);


    @Test
    @DisplayName("stacked, a colour chip is drawn in the control band and its name in the label's")
    void aStackedChipKeepsItsOwnBand() {
        // The report was a screenshot: a colour row three times the height of its neighbours, its swatch and
        // its hex stretched down the whole of it. What that was is the chip asked for the stacked *row* rather
        // than for the band under the label -- so the two assertions here are the pair that says which band
        // each half belongs in, and the height, which is the thing a reader saw.
        List<ToolsLayout.Action> rows = List.of(
                ToolsLayout.Action.chip("element.logo.tint", "Tint", "#FF336699"));
        BookGeometry.Rect listRect = BookGeometry.Rect.at(10, 20, 300, 200);
        Viewport list = Viewport.fixed().bounds(listRect.x(), listRect.y(), listRect.width(),
                listRect.height());
        Layout layout = ToolsLayout.build(rows, listRect.width(), MEASURE, InspectLayout.Mode.STACKED);
        RecordingRenderer r = new RecordingRenderer();

        ToolsPanel.drawRows(r, listRect, list, layout, rows, state(CanvasBackground.NONE, 51), 0, 0,
                InspectLayout.Mode.STACKED);

        Slot slot = layout.slot("element.logo.tint");
        Slot band = InspectLayout.controlBand(slot);
        Slot label = InspectLayout.labelBand(slot);
        Slot bandOnScreen = onScreen(listRect, band);
        Slot labelOnScreen = onScreen(listRect, label);

        // The swatch is the row's own colour, drawn inside the control's band and no taller than it.
        assertTrue(r.fills().stream().anyMatch(fill -> fill.argb() == 0xFF336699
                        && fill.left() >= bandOnScreen.x() && fill.right() <= bandOnScreen.right()
                        && fill.top() >= bandOnScreen.y() && fill.bottom() <= bandOnScreen.bottom()),
                () -> "the chip's swatch is not drawn inside its band: " + r.describe());
        assertTrue(r.wroteWithin("#336699", bandOnScreen.x(), bandOnScreen.y(), bandOnScreen.right(),
                        bandOnScreen.bottom()),
                () -> "the hex is not in the control band: " + r.describe());
        // **And nothing of the chip reaches the label's band.** A chip that still took the whole row would
        // draw its swatch up there, which is exactly what the screenshot showed.
        assertFalse(r.fills().stream().anyMatch(fill -> fill.argb() == 0xFF336699
                        && fill.top() < bandOnScreen.y()),
                () -> "the chip is drawn up into the label's band: " + r.describe());
        // The name is the label band's, whole, rather than centred down a three-band row.
        assertTrue(r.wroteWithin("Tint", labelOnScreen.x(), labelOnScreen.y(), labelOnScreen.right(),
                        labelOnScreen.bottom()),
                () -> "the chip's name is not in the label band: " + r.describe());
        // Read from the lines directly rather than through `wroteWithin`, whose top edge carries eight pixels
        // of slack for a baseline: that slack would reach up into the label's band and make this assertion
        // pass or fail on where the two bands meet rather than on which one the name was drawn in.
        assertTrue(r.texts().stream().noneMatch(drawn -> drawn.text().equals("Tint")
                        && drawn.y() >= bandOnScreen.y()),
                () -> "and it is not drawn again down in the control band: " + r.describe());
    }

    /** A content slot as the drawing maps it: the list's own rectangle through the viewport it is drawn in. */
    private static Slot onScreen(BookGeometry.Rect listRect, Slot slot) {
        return new Slot(slot.key(), listRect.x() + slot.x(), listRect.y() + slot.y(), slot.width(),
                slot.height());
    }

    @Test
    @DisplayName("every row of the list is drawn as something, whatever its kind")
    void noRowIsDrawnAsNothing() {
        // The general shape of the fault, rather than the one row it happened to: a kind with no branch, or a
        // branch that draws nothing, leaves a row that is invisible while its widget still takes presses. Each
        // kind is asked for what the *panel* owes it -- a colour row's name is its widget's business, and what
        // the panel draws for it is its code and its swatch.
        BookGeometry.Rect canvas = new BookGeometry(854, 480, true).canvas();
        ToolsLayout.Frame frame = ToolsLayout.frame(canvas);
        List<ToolsLayout.Action> rows = ToolsLayout.rows(true, true, true, true);
        Layout layout = ToolsLayout.build(rows, frame.list().width(), MEASURE);
        Viewport list = Viewport.fixed().bounds(frame.list().x(), frame.list().y(),
                frame.list().width(), frame.list().height());
        RecordingRenderer r = new RecordingRenderer();

        ToolsPanel.draw(r, frame, list, layout, rows, state(
                dev.ellipog.armature.client.ui.CanvasBackground.NONE, 51), 0, 0);

        for (ToolsLayout.Action row : rows) {
            Slot slot = layout.slot(row.key());
            Slot onScreen = new Slot(row.key(), frame.list().x() + slot.x(), frame.list().y() + slot.y(),
                    slot.width(), slot.height());
            // What the *panel* owes each kind: its name, for the rows whose name no widget draws; and its
            // code and swatch, for a colour chip, whose press the screen hit-tests but whose picture is
            // the panel's. A field, a pair and a choice are widgets: the panel draws their labels and the
            // widget draws everything else.
            boolean drawn;
            if (row.kind() == ToolsLayout.Action.Kind.CHIP) {
                // A colour row: the panel owes it both its name and its hex. A chip that drew neither
                // would be a row that is invisible while its rectangle still takes the press.
                drawn = r.wroteWithin(Labels.of(row.label()), onScreen.x(), onScreen.y(),
                                onScreen.right(), onScreen.bottom())
                        && r.texts().stream().anyMatch(text -> text.text().startsWith("#")
                                && text.x() >= onScreen.x() && text.x() <= onScreen.right()
                                && text.y() >= onScreen.y() - 8 && text.y() <= onScreen.bottom());
            }
            else if (ToolsLayout.CANVAS_COPY.equals(row.key())) {
                // A plain row whose label its widget draws, the same deal a switch's button has one
                // kind over: the panel itself owes this row nothing.
                drawn = true;
            }
            else if (row.kind() == ToolsLayout.Action.Kind.ROW) {
                drawn = r.texts().stream().anyMatch(text -> text.text().startsWith("#")
                        && text.x() >= onScreen.x() && text.x() <= onScreen.right()
                        && text.y() >= onScreen.y() - 8 && text.y() <= onScreen.bottom());
            }
            else {
                // The label is a key in the record and the panel resolves it, so the expected text is
                // the resolved one -- the same call the panel makes, with the mod's language installed.
                drawn = r.wroteWithin(Labels.of(row.label()), onScreen.x(), onScreen.y(),
                        onScreen.right(), onScreen.bottom());
            }
            assertTrue(drawn, () -> "a row drew nothing: " + row.key() + " (" + row.kind() + ")");
        }
    }

    @Test
    @DisplayName("a palette row draws its four swatches, and no hex")
    void paletteRowsDrawTheirColours() {
        BookGeometry.Rect canvas = new BookGeometry(854, 480, true).canvas();
        ToolsLayout.Frame frame = ToolsLayout.frame(canvas);
        List<ToolsLayout.Action> rows = ToolsLayout.rows(true, true, true, true,
                List.of(new ToolsLayout.Palette("obsidian", "Obsidian")), true);
        Layout layout = ToolsLayout.build(rows, frame.list().width(), MEASURE);
        Viewport list = Viewport.fixed().bounds(frame.list().x(), frame.list().y(),
                frame.list().width(), frame.list().height());
        RecordingRenderer r = new RecordingRenderer();

        ToolsPanel.draw(r, frame, list, layout, rows,
                state(dev.ellipog.armature.client.ui.CanvasBackground.NONE, 51),
                0, 0);

        String key = ToolsLayout.paletteKey("obsidian");
        Slot slot = layout.slot(key);
        Slot onScreen = new Slot(key, frame.list().x() + slot.x(), frame.list().y() + slot.y(),
                slot.width(), slot.height());
        dev.ellipog.armature.client.ui.Theme obsidian =
                dev.ellipog.armature.client.ui.Themes.any("obsidian");

        for (String token : List.of("panel", "raised", "title", "accent")) {
            int argb = obsidian.colour(token);
            assertTrue(r.fills().stream().anyMatch(fill -> fill.argb() == argb
                            && fill.left() >= onScreen.x() && fill.right() <= onScreen.right()
                            && fill.top() >= onScreen.y() && fill.bottom() <= onScreen.bottom()),
                    () -> "the palette row does not draw its " + token + " swatch: " + r.describe());
        }
        assertTrue(r.texts().stream().noneMatch(text -> text.text().startsWith("#")
                        && text.x() >= onScreen.x() && text.x() <= onScreen.right()
                        && text.y() >= onScreen.y() - 8 && text.y() <= onScreen.bottom()),
                () -> "a palette row drew a hex string, and the boxes are the information: "
                        + r.describe());
    }

    @Test
    @DisplayName("the current palette keeps its swatches over its own selection")
    void selectedPaletteKeepsItsSwatches() {
        // The layering bug, pinned as an order: the selected row's widget used to draw its opaque
        // box in the later widget pass, over the swatches this pass drew — so the current palette
        // lost its preview in every theme. The selection box lives in this pass now, under the
        // swatches, and the widget draws none; assert the order, which is the whole of the fix.
        BookGeometry.Rect canvas = new BookGeometry(854, 480, true).canvas();
        ToolsLayout.Frame frame = ToolsLayout.frame(canvas);
        List<ToolsLayout.Action> rows = ToolsLayout.rows(true, true, true, true,
                List.of(new ToolsLayout.Palette("obsidian", "Obsidian")), true);
        Layout layout = ToolsLayout.build(rows, frame.list().width(), MEASURE);
        Viewport list = Viewport.fixed().bounds(frame.list().x(), frame.list().y(),
                frame.list().width(), frame.list().height());
        RecordingRenderer r = new RecordingRenderer();

        ToolsPanel.draw(r, frame, list, layout, rows,
                state(dev.ellipog.armature.client.ui.CanvasBackground.NONE, 51, "obsidian"),
                0, 0);

        String key = ToolsLayout.paletteKey("obsidian");
        Slot slot = layout.slot(key);
        Slot onScreen = new Slot(key, frame.list().x() + slot.x(), frame.list().y() + slot.y(),
                slot.width(), slot.height());

        int selectedEdge = dev.ellipog.armature.client.ArmatureControlStyle.edge(
                dev.ellipog.armature.client.ArmatureControlStyle.Variant.SELECTED, true, false, false);
        var fills = r.fills();
        // The footprint carries the border colour and the inset the fill: a panel is two fills,
        // so the box is found by its border spanning the row, and the swatches by their tokens.
        int box = -1;
        for (int i = 0; i < fills.size(); i++) {
            var fill = fills.get(i);
            if (fill.argb() == selectedEdge && fill.left() <= onScreen.x() && fill.right() >= onScreen.right()) {
                box = i;
                break;
            }
        }
        assertTrue(box >= 0, () -> "the current palette marks its row: " + r.describe());

        dev.ellipog.armature.client.ui.Theme obsidian =
                dev.ellipog.armature.client.ui.Themes.any("obsidian");
        for (String token : List.of("panel", "raised", "title", "accent")) {
            int argb = obsidian.colour(token);
            int swatch = -1;
            for (int i = 0; i < fills.size(); i++) {
                var fill = fills.get(i);
                if (fill.argb() == argb && fill.left() >= onScreen.x() && fill.right() <= onScreen.right()
                        && fill.top() >= onScreen.y() && fill.bottom() <= onScreen.bottom()) {
                    swatch = i;
                    break;
                }
            }
            final int found = swatch;
            assertTrue(found > box,
                    () -> "the " + token + " swatch paints over the selection, not under it: "
                            + r.describe());
        }
    }

    // ------------------------------------------------------------------
    // The canvas strip and its steppers
    // ------------------------------------------------------------------

    /** The panel's state for a background and an ink alpha: one helper so a case states only what it tests. */
    private static ToolsPanel.State state(CanvasBackground background, int patternOpacity) {
        return state(background, patternOpacity, null);
    }

    /** The same, with a palette marked current: the selection box is the caller's shape. */
    private static ToolsPanel.State state(CanvasBackground background, int patternOpacity,
                                          String selectedPalette) {
        // The record is (theme, background, selectedPalette): the status line and the stepper values
        // it used to carry are not read by the drawing any more, and the alpha parameter stays as
        // the caller's shape.
        return new ToolsPanel.State(dev.ellipog.armature.client.ui.Themes.MODERN, background,
                selectedPalette);
    }

    /** An image background at a tile size the tile case can recognize. */
    private static CanvasBackground image(CanvasBackground.Fit fit) {
        return new CanvasBackground(CanvasBackground.Kind.IMAGE, CanvasBackground.Space.SCREEN, 24,
                CanvasBackground.Tuning.DEFAULT,
                new CanvasBackground.Image("minecraft:textures/gui/bg.png", fit, 48));
    }

    /**
     * The strip: its own well, and the painter's marks over it.
     *
     * <p>What this sees that arithmetic cannot: the strip is the one row whose content is a drawing,
     * and a row that filled its well and never called the painter would look, to any assertion about
     * the fill, exactly like one that worked. So the ink's own colour is looked up among the fills --
     * a one-pixel dot of it inside the well -- and then the well has to come before that mark in the
     * call order, which is what "over" means when both are fills.
     */

    /**
     * Every canvas stepper's number, in the row its kind gives it.
     *
     * <p>The values are the state's, not the theme's defaults, so the test also pins that the panel
     * reads the background it was handed: a size of 3 where the default is 1, a density of 7 where the
     * default is 5, and an opacity per cent that only the carried alpha can produce.
     */

    // ------------------------------------------------------------------
    // The sections, the texture row, and the words
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a foldable heading draws the rule that says it is a section")
    void foldableHeadingsDrawTheirRule() {
        List<ToolsLayout.Action> rows = ToolsLayout.rows(true, true, true, true);
        BookGeometry.Rect listRect = BookGeometry.Rect.at(10, 20, 288, 400);
        Viewport list = Viewport.fixed().bounds(listRect.x(), listRect.y(), listRect.width(),
                listRect.height());
        Layout layout = ToolsLayout.build(rows, listRect.width(), MEASURE);
        RecordingRenderer r = new RecordingRenderer();

        ToolsPanel.drawRows(r, listRect, list, layout, rows, state(CanvasBackground.NONE, 51), 0, 0);

        Slot heading = layout.slot(ToolsLayout.CANVAS_SECTION);
        assertNotNull(heading, "the Canvas heading is in the list");
        Slot onScreen = new Slot(heading.key(), listRect.x() + heading.x(), listRect.y() + heading.y(),
                heading.width(), heading.height());
        // The rule is one pixel at the heading's bottom, exactly where the layout says the heading
        // ends: it is what makes a section read as a section rather than as a word in a list, and the
        // Canvas heading is the one that was drawn promising a fold with no widget behind it.
        assertTrue(r.covered(onScreen.x(), onScreen.bottom() - 1, onScreen.right(), onScreen.bottom()),
                () -> "the foldable heading draws no section rule: " + r.describe());
        assertTrue(r.wroteWithin(Labels.of(rows.stream()
                                .filter(row -> row.key().equals(ToolsLayout.CANVAS_SECTION))
                                .findFirst().orElseThrow().label()),
                        onScreen.x(), onScreen.y(), onScreen.right(), onScreen.bottom()),
                () -> "the heading's own name is missing: " + r.describe());
    }

    @Test
    @DisplayName("the texture row draws the file's picture and the browse button's mark")
    void theTextureRowIsDrawn() {
        CanvasBackground background = image(CanvasBackground.Fit.COVER);
        List<ToolsLayout.Action> rows = ToolsLayout.canvasRows(background, true, null);
        BookGeometry.Rect listRect = BookGeometry.Rect.at(10, 20, 288, 400);
        Viewport list = Viewport.fixed().bounds(listRect.x(), listRect.y(), listRect.width(),
                listRect.height());
        Layout layout = ToolsLayout.build(rows, listRect.width(), MEASURE);
        RecordingRenderer r = new RecordingRenderer();
        // The size the row's picture is drawn at. Without it the recorder answers empty and the
        // thumbnail falls back to its `?`, which is the correct behaviour and not what this test is
        // about -- so the file is taught one, and the drawing is asked to use it.
        ResourceLocation file = ResourceLocation.parse("minecraft:textures/gui/bg.png");
        r.putTextureSize(file, 32, 16);

        ToolsPanel.drawRows(r, listRect, list, layout, rows, state(background, 51), 0, 0);

        Slot row = layout.slot(ToolsLayout.CANVAS_TEXTURE);
        assertNotNull(row, "the image's Texture row is in the list");
        Slot onScreen = new Slot(row.key(), listRect.x() + row.x(), listRect.y() + row.y(),
                row.width(), row.height());
        Slot thumb = ToolsLayout.textureThumb(onScreen);
        assertTrue(r.scaled().stream().anyMatch(drawn -> drawn.texture().equals(file)
                        && drawn.x() >= thumb.x() && drawn.y() >= thumb.y()
                        && drawn.x() + drawn.width() <= thumb.right()
                        && drawn.y() + drawn.height() <= thumb.bottom()),
                () -> "the file's picture is not drawn in the row's box: " + r.describe());

        Slot browse = ToolsLayout.textureBrowse(onScreen);
        assertTrue(r.wroteWithin("\u2026", browse.x(), browse.y(), browse.right(), browse.bottom()),
                () -> "the browse button has no mark: " + r.describe());
    }

    @Test
    @DisplayName("an image background's pattern reads Image, not Texture")
    void theImagePatternIsCalledImage() {
        // The row below the pattern names the file, so the two must not say the same word: the value
        // is the kind and the row is the file, and a reader looking for the difference finds it here.
        assertEquals("Image", ToolsPanel.patternLabel(CanvasBackground.Kind.IMAGE));
        assertEquals(Labels.of("tenet.dev.canvas.pattern_image"),
                ToolsPanel.patternLabel(CanvasBackground.Kind.IMAGE));
    }

    @Test
    @DisplayName("a read-only row draws its label and its value, because nothing else can")
    void valueRowsAreDrawn() {
        // The chapter tab's own read-only rows: a description, and the quests the chapter lists. Nothing
        // is placed for them -- they are the one kind with no control -- so if the panel does not draw
        // them, nobody does, and the row is a rectangle that shows nothing while still taking the height
        // the layout gave it.
        List<ToolsLayout.Action> rows = List.of(
                ToolsLayout.Action.value("v:description", "Description", "A chapter's own words"));
        BookGeometry.Rect listRect = BookGeometry.Rect.at(10, 20, 300, 200);
        Viewport list = Viewport.fixed().bounds(listRect.x(), listRect.y(), listRect.width(),
                listRect.height());
        Layout layout = ToolsLayout.build(rows, listRect.width(), MEASURE);
        RecordingRenderer r = new RecordingRenderer();

        ToolsPanel.drawRows(r, listRect, list, layout, rows, state(CanvasBackground.NONE, 51), 0, 0);

        Slot slot = layout.slot("v:description");
        Slot onScreen = new Slot("v:description", listRect.x() + slot.x(), listRect.y() + slot.y(),
                slot.width(), slot.height());
        assertTrue(r.wroteWithin("Description", onScreen.x(), onScreen.y(), onScreen.right(),
                onScreen.bottom()), () -> "the read-only row's label is missing: " + r.describe());
        assertTrue(r.wroteWithin("A chapter's own words", onScreen.x(), onScreen.y(), onScreen.right(),
                onScreen.bottom()), () -> "the read-only row's value is missing: " + r.describe());
    }

    @Test
    @DisplayName("stacked, a field's label is drawn whole in its own band instead of cut to a strip")
    void stackedRowsDrawTheirLabelInTheLabelBand() {
        // The composition exists for exactly one row: a label longer than the room a control's box leaves
        // in a narrow dock. So the assertion is the pair -- side by side the same label is truncated,
        // stacked it is drawn whole -- because either half alone would pass on a drawing that ignored the
        // mode, and the pair is also the statement of what stacking buys.
        String wordy = "Default Prerequisite Mode";
        List<ToolsLayout.Action> rows = List.of(ToolsLayout.Action.choice("progression", wordy));
        // 180, which is the narrow dock this composition was chosen for: wide enough for the stacked label
        // and not for the side-by-side one beside a control's box.
        BookGeometry.Rect listRect = BookGeometry.Rect.at(10, 20, 180, 200);
        Viewport list = Viewport.fixed().bounds(listRect.x(), listRect.y(), listRect.width(),
                listRect.height());

        Layout stacked = ToolsLayout.build(rows, listRect.width(), MEASURE, InspectLayout.Mode.STACKED);
        RecordingRenderer r = new RecordingRenderer();
        ToolsPanel.drawRows(r, listRect, list, stacked, rows, state(CanvasBackground.NONE, 51), 0, 0,
                InspectLayout.Mode.STACKED);

        Slot slot = stacked.slot("progression");
        Slot band = InspectLayout.labelBand(slot);
        Slot onScreen = new Slot("progression", listRect.x() + band.x(), listRect.y() + band.y(),
                band.width(), band.height());
        assertTrue(r.wroteWithin(wordy, onScreen.x(), onScreen.y(), onScreen.right(), onScreen.bottom()),
                () -> "the stacked label is not drawn whole in its own band: " + r.describe());

        Layout sideBySide = ToolsLayout.build(rows, listRect.width(), MEASURE);
        RecordingRenderer flat = new RecordingRenderer();
        ToolsPanel.drawRows(flat, listRect, list, sideBySide, rows, state(CanvasBackground.NONE, 51),
                0, 0);
        Slot flatSlot = sideBySide.slot("progression");
        Slot flatOnScreen = new Slot("progression", listRect.x() + flatSlot.x(),
                listRect.y() + flatSlot.y(), flatSlot.width(), flatSlot.height());
        assertFalse(flat.wroteWithin(wordy, flatOnScreen.x(), flatOnScreen.y(), flatOnScreen.right(),
                        flatOnScreen.bottom()),
                () -> "side by side this label does not fit the room left of the control, which is the "
                        + "fault the stacked composition answers: " + flat.describe());
    }
}
