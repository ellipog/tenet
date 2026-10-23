package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tenet.client.BookGeometry;
import dev.ellipog.tenet.client.DevMode;
import dev.ellipog.tenet.quest.QuestShape;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the settings page draws, from the recorded calls.
 *
 * <h2>What a test can see here, and what it cannot</h2>
 *
 * <p>It can see that every shape the format names is offered as a swatch, that the page names the shape
 * and size it is previewing, that the node in the preview is drawn inside the preview's own pane, that
 * a hovered row's explanation is on the help line, and that a switch says which way it is. It cannot see
 * whether the page reads well — that is what the stills and the game are for.
 *
 * <h2>And it can see where a control landed, which is the thing that shipped wrong</h2>
 *
 * <p>The first version of this page drew every row at its <b>layout</b> coordinates while clipping to
 * the controls column, which is a screen rectangle a column's width to the right — so every control was
 * discarded by the clip and the column drew nothing at all. The preview, the caption and the help line
 * are drawn from the frame's own screen rectangles, so the page looked half-alive rather than broken,
 * and the stills agreed with it because the preview script placed the slots itself. So these tests draw
 * through a viewport placed exactly as the screen places it, and assert that the controls are <b>in the
 * column</b> — the one assertion that makes that class of mistake impossible to ship again.
 *
 * <p>The recording renderer is package-private to this package, which is why this test lives here
 * rather than beside the panel.
 */
@DisplayName("The settings page's drawing")
class QuestSettingsPanelTest {

    /**
     * Full depth, because this page's rows are asserted as a whole.
     *
     * <p>Normal mode gates away the visibility, rules and identity sections and two rows inside the ones
     * that stay, so the page it draws is a shorter one -- and the switches this file is about are mostly in
     * those sections. What Normal hides is {@code AdvancedTest}'s subject; this file is about the drawing.
     */
    @BeforeEach
    void atFullDepth() {
        DevMode.setAdvanced(true);
    }

    @AfterEach
    void forgetTheDepth() {
        // A static flag: a test that leaves it set changes the next class to run.
        DevMode.reset();
    }

    private static final BookGeometry.Rect BODY = BookGeometry.Rect.at(0, 0, 496, 284);


    private static JsonObject quest() {
        JsonObject quest = new JsonObject();
        quest.add("id", new JsonPrimitive("smelt_iron"));
        quest.add("size", new JsonPrimitive(96));
        quest.add("iconScale", new JsonPrimitive(0.5));
        quest.add("showTitle", new JsonPrimitive(true));
        quest.add("repeatable", new JsonPrimitive(false));
        return quest;
    }

    /** The viewport the screen hands the panel: the column's own rectangle. */
    private static dev.ellipog.armature.client.ui.kit.Viewport columnOf(QuestSettingsLayout.Frame frame) {
        var column = dev.ellipog.armature.client.ui.kit.Viewport.fixed();
        column.bounds(frame.controls().x(), frame.controls().y(), frame.controls().width(),
                frame.controls().height());
        return column;
    }

    private static RecordingRenderer draw(QuestSettingsPanel.View view) {
        QuestSettingsLayout.Frame frame = QuestSettingsLayout.Frame.of(BODY);
        List<QuestSettingsLayout.Row> rows = QuestSettingsLayout.rows();
        Layout layout = QuestSettingsLayout.build(rows, frame.controls().width(),
                Measure.monospace(6, 9));
        RecordingRenderer r = new RecordingRenderer();
        QuestSettingsPanel.draw(r, frame, layout, rows, quest(), view, columnOf(frame), 0, 0);
        return r;
    }

    private static QuestSettingsPanel.View view(QuestShape shape, String hovered) {
        return new QuestSettingsPanel.View("Smelt Iron", null, "", "", shape, shape.geometry(), 0, 96, 0.5,
                true, -1, hovered, "all_completed", false, false);
    }

    /** The same, turned: what the preview does with an angle. */
    private static QuestSettingsPanel.View turned(QuestShape shape, int rotation, String hovered) {
        return new QuestSettingsPanel.View("Smelt Iron", null, "", "", shape,
                dev.ellipog.armature.client.ui.shape.Shapes.rotated(shape.geometry(), rotation),
                rotation, 96, 0.5, true, -1, hovered, "all_completed", false, false);
    }

    @Test
    @DisplayName("every shape the format names is offered, by name")
    void everyShapeIsOffered() {
        RecordingRenderer r = draw(view(QuestShape.ROUNDED, null));
        for (QuestShape shape : QuestShape.values()) {
            String name = shape.name().toLowerCase(java.util.Locale.ROOT);
            assertTrue(r.wrote(name), "the page does not offer the " + name + " shape");
        }
    }

    @Test
    @DisplayName("the caption says whether the preview is the size the canvas draws")
    void theCaptionSaysActualSize() {
        // The controls beside it already name the shape and the size; what only the caption can say is
        // whether the picture is to scale.
        RecordingRenderer r = draw(view(QuestShape.HEART, null));
        assertTrue(r.wrote("Actual size"),
                "a node that fits is drawn 1:1, and the caption should say so: " + r.describe());
    }

    @Test
    @DisplayName("the node is drawn inside the preview pane, not over the controls")
    void thePreviewStaysInItsPane() {
        QuestSettingsLayout.Frame frame = QuestSettingsLayout.Frame.of(BODY);
        RecordingRenderer r = draw(view(QuestShape.CIRCLE, null));
        boolean nodeInPane = r.fills().stream().anyMatch(fill ->
                fill.left() >= frame.preview().x() && fill.right() <= frame.preview().right()
                        && fill.top() >= frame.preview().y() && fill.bottom() <= frame.preview().bottom());
        assertTrue(nodeInPane, "the preview drew nothing inside its own pane: " + r.describe());
    }

    @Test
    @DisplayName("the hovered row's explanation is on the help line")
    void theHelpLineExplainsWhatIsHovered() {
        RecordingRenderer r = draw(view(QuestShape.ROUNDED, "iconScale"));
        // The help line says what the field means now: a share of the node that the outline caps, rather
        // than a share of whatever square the shape happens to hold.
        assertTrue(r.texts().stream().anyMatch(drawn -> drawn.text().contains("of the node")
                        && drawn.text().contains("caps")),
                "the help line did not explain the icon scale: " + r.describe());

        RecordingRenderer quiet = draw(view(QuestShape.ROUNDED, null));
        assertTrue(quiet.texts().stream().anyMatch(drawn -> drawn.text().contains("Shape, size")),
                "with nothing hovered the page should say what it is");
    }

    @Test
    @DisplayName("a switch says which way it is with its knob, not with a word beside it")
    void switchesSayTheirState() {
        // The state used to be a suffix on the label — "Show title · on" — and the assertion was on the
        // text. That suffix is gone: the knob is a switch drawn at one end or the other, and the words cost
        // the label the room it needed for its own name. So what is asserted moved with the state: the two
        // knobs are on opposite ends of their tracks, read from the tree rather than from anything cached.
        RecordingRenderer r = draw(view(QuestShape.ROUNDED, null));
        QuestSettingsLayout.Frame frame = QuestSettingsLayout.Frame.of(BODY);
        List<QuestSettingsLayout.Row> rows = QuestSettingsLayout.rows();
        Layout layout = QuestSettingsLayout.build(rows, frame.controls().width(),
                Measure.monospace(6, 9));
        Viewport column = columnOf(frame);

        assertTrue(r.wrote("Show title"), "the switch's own label is not drawn: " + r.describe());
        assertFalse(r.wrote("Show title \u00b7 on"),
                "the state is still spelled out beside the knob: " + r.describe());

        BookGeometry.Rect on = track(layout, column, "showTitle");
        BookGeometry.Rect off = track(layout, column, "repeatable");
        // The knob is the one fill in the `title` ink, one pixel inside whichever end it sits at.
        assertTrue(knobAt(r, on, true), "the showTitle switch (true) is not drawn on: " + r.describe());
        assertTrue(knobAt(r, off, false), "the repeatable switch (false) is not drawn off: "
                + r.describe());
    }

    /** A switch's track on screen, through the same viewport and strip the drawing uses. */
    private static BookGeometry.Rect track(Layout layout, Viewport column, String key) {
        Slot strip = QuestSettingsLayout.strip(QuestSettingsLayout.Row.Kind.SWITCH,
                dev.ellipog.armature.client.ui.inspect.InspectLayout.onScreen(column, layout.slot(key)));
        return QuestSettingsLayout.switchTrack(strip);
    }

    /** Whether a knob in the switch's own ink sits in the half of the track the state names. */
    private static boolean knobAt(RecordingRenderer r, BookGeometry.Rect track, boolean on) {
        int middle = track.x() + track.width() / 2;
        return r.fills().stream().anyMatch(fill -> fill.argb() == ArmatureTheme.title()
                && fill.top() >= track.y() && fill.bottom() <= track.bottom()
                && (on ? fill.left() >= middle : fill.right() <= middle));
    }

    @Test
    @DisplayName("the preview follows the draft, so a slider moves the node before the server answers")
    void thePreviewReadsTheDraft() {
        QuestSettingsLayout.Frame frame = QuestSettingsLayout.Frame.of(BODY);
        List<QuestSettingsLayout.Row> rows = QuestSettingsLayout.rows();
        Layout layout = QuestSettingsLayout.build(rows, frame.controls().width(),
                Measure.monospace(6, 9));
        RecordingRenderer r = new RecordingRenderer();
        QuestSettingsPanel.View drafted = new QuestSettingsPanel.View("Smelt Iron", null, "", "",
                QuestShape.GEAR, dev.ellipog.armature.client.ui.shape.Shapes.rotated(
                        QuestShape.GEAR.geometry(), 0),
                0, 320, 0.5, true, -1, null, "all_completed", false, false);
        QuestSettingsPanel.draw(r, frame, layout, rows, quest(), drafted, columnOf(frame), 0, 0);
        // A 320-pixel node does not fit the column, so the drafted size is what scales it -- the
        // server's answer would have drawn it 1:1.
        assertTrue(r.texts().stream().anyMatch(drawn -> drawn.text().startsWith("Shown at ")),
                "the caption ignored the draft: " + r.describe());
    }

    @Test
    @DisplayName("the size slider's knob is drawn at the value's own place on the track")
    void theKnobSitsWhereTheValueSays() {
        QuestSettingsLayout.Frame frame = QuestSettingsLayout.Frame.of(BODY);
        List<QuestSettingsLayout.Row> rows = QuestSettingsLayout.rows();
        Layout layout = QuestSettingsLayout.build(rows, frame.controls().width(),
                Measure.monospace(6, 9));
        RecordingRenderer r = new RecordingRenderer();
        QuestSettingsPanel.draw(r, frame, layout, rows, quest(),
                view(QuestShape.ROUNDED, null), columnOf(frame), 0, 0);

        // The knob's x, from the layout's own arithmetic through the same viewport the drawing uses --
        // the layout's slot is the column's coordinates, so the expected x has to be placed too.
        var slot = dev.ellipog.armature.client.ui.inspect.InspectLayout.onScreen(columnOf(frame),
                layout.slot("size"));
        var strip = QuestSettingsLayout.strip(QuestSettingsLayout.Row.Kind.SLIDER, slot);
        var track = QuestSettingsLayout.track(strip);
        int knobX = QuestSettingsLayout.knobX(track, 96, QuestSettingsLayout.MIN_SIZE,
                QuestSettingsLayout.MAX_SIZE, true);
        // The knob is the tallest thing on the track's row: a fill that starts on the track and is
        // taller than the track itself, centred on the value's x.
        boolean knobThere = r.fills().stream().anyMatch(fill ->
                fill.bottom() - fill.top() >= QuestSettingsLayout.KNOB_HEIGHT
                        && Math.abs((fill.left() + fill.right()) / 2 - knobX) <= 1);
        assertTrue(knobThere, "no knob was drawn at x=" + knobX + ": " + r.describe());
    }

    @Test
    @DisplayName("every control is drawn inside the column, and none of them over the preview")
    void theControlsLandInTheColumn() {
        // The assertion the first version of this page would have failed. Its rows were drawn at the
        // layout's own coordinates -- x from zero -- while the clip was the column, which starts a
        // column's width to the right, so every control was discarded and the column drew nothing. The
        // preview, the caption and the help line come from the frame's screen rectangles, so the page
        // still looked half-alive; and the preview stills agreed with it, because the preview script
        // placed the slots itself. A test that says "the controls are where the column is" is what
        // closes that gap.
        QuestSettingsLayout.Frame frame = QuestSettingsLayout.Frame.of(BODY);
        RecordingRenderer r = draw(view(QuestShape.ROUNDED, null));

        List<String> labels = List.of("Shape", "Size and icon", "Size", "Icon scale", "Icon",
                // The switch row's label and nothing else: the knob is the state, so "· on" is not drawn.
                "Show title", "Placement", "Rules", "Identity extras",
                "rounded", "square", "circle", "diamond", "heart");
        for (String label : labels) {
            assertTrue(r.texts().stream().anyMatch(drawn -> drawn.text().equals(label)
                            && drawn.x() >= frame.controls().x()
                            && drawn.x() < frame.controls().right()),
                    "'" + label + "' was not drawn inside the controls column: " + r.describe());
        }

        // And the other direction: nothing the controls draw may land over the preview pane, which is
        // drawn first and would be painted over rather than hidden.
        for (RecordingRenderer.Drawn drawn : r.texts()) {
            boolean previewText = drawn.x() < frame.controls().x() && drawn.y() < frame.caption().y();
            assertTrue(!previewText || drawn.text().equals("Smelt Iron"),
                    "the controls drew '" + drawn.text() + "' over the preview: " + r.describe());
        }
    }
}
