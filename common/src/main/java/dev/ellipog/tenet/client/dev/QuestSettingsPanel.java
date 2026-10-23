package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.inspect.InspectLayout;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.armature.client.ui.shape.Shape;
import dev.ellipog.tenet.client.BookGeometry;
import dev.ellipog.tenet.client.QuestNodeArt;
import dev.ellipog.tenet.quest.QuestShape;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * What the quest settings page looks like: a live node on the left, the controls on the right.
 *
 * <h2>Why the drawing is its own class</h2>
 *
 * <p>The same reason {@code QuestPanel}'s and {@code ToolsPanel}'s are: the screen cannot be
 * instantiated by a test, and everything here takes a {@link GuiRenderer} and plain numbers, so the
 * page's appearance is one file that can be read at once — and the arithmetic it reads is
 * {@link QuestSettingsLayout}'s, which is asserted.
 *
 * <h2>The preview is the point</h2>
 *
 * <p>A settings page of labelled controls is a form, and a form asks an author to imagine the result of
 * every change. This one draws the result: the node is rendered by {@link QuestNodeArt} — the same code
 * the canvas uses, not a copy — at the shape, size and icon scale the page is currently asking for. A
 * shape being hovered in the grid is previewed before it is chosen, so the grid is a set of answers
 * rather than a list of names.
 *
 * <h2>What the widgets do and what this draws</h2>
 *
 * <p>A field's text box is a widget, placed by the screen through {@link QuestSettingsLayout#strip};
 * this draws everything else — the chrome, the preview, every row's label, the swatches, the sliders,
 * the switches, the steppers and the help line. A row's control is drawn from the same rectangle the
 * press reads, so what is drawn is what is pressed.
 */
public final class QuestSettingsPanel {

    private QuestSettingsPanel() {
    }

    /**
     * Everything the page draws from, gathered once.
     *
     * @param title       the quest's name, for the preview's label and the caption
     * @param icon        the item in the node
     * @param shape       the shape to preview — the draft's, or the server's
     * @param geometry    that shape's outline <b>with the rotation applied</b>, resolved by the caller
     *                    for the same reason the canvas resolves it: a rotation is applied by sampling
     *                    the shape again, and the page must not rebuild a table every frame
     * @param rotation    the angle to name in the caption, in degrees
     * @param size        the size to preview
     * @param iconScale   the icon scale to preview
     * @param showTitle   whether the name is drawn under the node
     * @param hoveredCell the shape swatch under the pointer, or -1
     * @param hoveredKey  the row under the pointer, or null — which drives the help line
     * @param chapterRequirement the chapter's default requirement, named inside the picker's "Chapter
     *     default (...)" value — because "Default" alone makes an author open the chapter file to find
     *     out what it defaults <i>to</i>
     */
    public record View(String title, ItemStack icon, String textureIcon, String spriteIcon,
                        QuestShape shape,
                        Shape geometry, int rotation,
                        int size, double iconScale, boolean showTitle, int hoveredCell,
                       String hoveredKey, String chapterRequirement,
                       /**
                        * The chapter's default for its quests' own {@code hideUntilDependenciesComplete},
                        * named in the picker's "Chapter default (...)" value like the requirement above.
                        * Read from the chapter's own copy rather than the wire: it is an authoring field,
                        * and this page is an authoring surface.
                        */
                       boolean chapterHideUntilDepsComplete,
                       /** The same, for {@code hideUntilDependenciesVisible}. */
                       boolean chapterHideUntilDepsVisible) {
    }

    /**
     * One line per control, said where the pointer is.
     *
     * <p>A page of switches and sliders with no words is a page an author has to guess at, and the
     * alternative — a paragraph under every row — doubles the height of every row and turns the page
     * into a manual. One line at the bottom, describing whatever is under the pointer, is the whole of
     * what a control has to say about itself.
     */
    private static final Map<String, String> HELP = Map.ofEntries(
            Map.entry("shape", "tenet.dev.help.shape"),
            Map.entry("rotation", "tenet.dev.help.rotation"),
            Map.entry("size", "tenet.dev.help.size"),
            Map.entry("iconScale", "tenet.dev.help.icon_scale"),
            Map.entry("icon", "tenet.dev.help.icon"),
            Map.entry("showTitle", "tenet.dev.help.show_title"),
            Map.entry("x", "tenet.dev.help.position"),
            Map.entry("y", "tenet.dev.help.position"),
            Map.entry("repeatable", "tenet.dev.help.repeatable"),
            Map.entry("repeatCooldownTicks", "tenet.dev.help.repeat_cooldown"),
            Map.entry("sequentialTasks", "tenet.dev.help.sequential_tasks"),
            Map.entry("invisible", "tenet.dev.help.invisible"),
            Map.entry("exclusiveGroup", "tenet.dev.help.exclusive_group"),
            Map.entry("prerequisiteMode", "tenet.dev.help.prerequisite_mode"),
            Map.entry("minRequired", "tenet.dev.help.min_required"),
            Map.entry("maxCompletableDependents", "tenet.dev.help.max_dependents"),
            Map.entry("optional", "tenet.dev.help.optional"),
            Map.entry("flexibleProgress", "tenet.dev.help.flexible_progress"),
            Map.entry(QuestSettingsLayout.DEPENDENCY_SELECTED, "tenet.dev.help.add_selected"),
            Map.entry(QuestPanelLayout.DEPENDENCY_PICK, "tenet.dev.help.pick_on_canvas"),
            Map.entry("invisibleUntilTasks", "tenet.dev.help.visible_after_tasks"),
            Map.entry("hideUntilDependenciesComplete", "tenet.dev.help.hide_until_deps_done"),
            Map.entry("hideUntilDependenciesVisible", "tenet.dev.help.hide_until_deps_shown"),
            Map.entry("hideDependencyLines", "tenet.dev.help.hide_dependency_lines"),
            Map.entry("hideDependentLines", "tenet.dev.help.hide_dependent_lines"),
            Map.entry("minWidth", "tenet.dev.help.min_width"),
            Map.entry("disableToast", "tenet.dev.help.disable_toast"),
            Map.entry("ignoreRewardBlocking", "tenet.dev.help.ignore_reward_blocking"),
            Map.entry("disableRecipeMod", "tenet.dev.help.disable_recipe_mod"),
            Map.entry("hideLockIcon", "tenet.dev.help.hide_lock_icon"),
            Map.entry("hideTextUntilComplete", "tenet.dev.help.hide_text_until_done"),
            Map.entry("hideDetailsUntilStartable", "tenet.dev.help.hide_details_until_startable"),
            Map.entry("id", "tenet.dev.help.id"),
            Map.entry("aliases", "tenet.dev.help.aliases"),
            Map.entry("tags", "tenet.dev.help.tags"),
            Map.entry("guidePage", "tenet.dev.help.guide_page"));

    /**
     * Draws the whole page: the preview, the controls, and the help line under them.
     *
     * @param column the viewport the controls are drawn through — the same one the screen places the
     *     text fields with and the same one the hover test reads, because a row's slot is in the
     *     <b>layout's</b> coordinates (its x is a distance across the column, not across the card) and
     *     only this mapping turns it into somewhere on screen. Drawing a row without it puts every
     *     control to the left of the column's clip, where it is discarded — which is exactly what the
     *     first version of this page did, with a preview and a help line drawn from the frame's own
     *     screen rectangles and a column that stayed empty.
     */
    public static void draw(GuiRenderer r, QuestSettingsLayout.Frame frame, Layout layout,
                            List<QuestSettingsLayout.Row> rows, JsonObject quest, View view,
                            Viewport column, double mouseX, double mouseY) {
        drawPreview(r, frame, view);
        drawControls(r, frame, layout, rows, quest, view, column, mouseX, mouseY);
        drawHelp(r, frame, view);
    }

    // ------------------------------------------------------------------
    // The preview
    // ------------------------------------------------------------------

    /**
     * The node, at the values the page is currently asking for.
     *
     * <p>Drawn 1:1 unless it does not fit, because 1:1 is what the canvas shows and a preview that
     * silently shrank a node would be lying about the one thing the size control is for. When it does
     * not fit — a 512-pixel node in a 176-pixel column — it is scaled down and the caption says so.
     *
     * <p>The values come through the draft, so a slider being dragged moves the node before the server
     * has answered; the view is the server's own answer and is what the draft falls back to.
     */
    private static void drawPreview(GuiRenderer r, QuestSettingsLayout.Frame frame, View view) {
        BookGeometry.Rect pane = frame.preview();
        if (pane.width() <= 0 || pane.height() <= 0) {
            return;
        }
        ArmatureTheme.panel(r, pane.x(), pane.y(), pane.width(), pane.height(), ArmatureTheme.canvas(),
                ArmatureTheme.panelEdge());

        // Everything here comes from the view, which the screen built from the draft: one answer to
        // "what is the page showing", rather than a panel that re-applies pending values the caller
        // has already applied.
        QuestShape shape = view.shape();
        int size = Math.max(1, view.size());
        double iconScale = view.iconScale();
        int room = Math.max(8, Math.min(pane.width(), pane.height()) - QuestSettingsLayout.PREVIEW_PAD * 2);
        double zoom = Math.min(1.0, room / (double) size);
        int drawn = Math.max(1, (int) Math.round(size * zoom));
        int x = pane.x() + (pane.width() - drawn) / 2;
        int y = pane.y() + (pane.height() - drawn) / 2;

        // The border is `nodeEdgeAvailable`, not `available`: the canvas's node borders read the four
        // node-edge tokens, and a preview drawn with the state ink would show a border the canvas will
        // not draw -- the exact kind of preview that drifts from the thing it previews.
        QuestNodeArt.draw(r, x, y, new QuestNodeArt.Look(drawn, shape, view.geometry(), view.icon(),
                view.textureIcon(), view.spriteIcon() == null ? "" : view.spriteIcon(), iconScale,
                false, ArmatureTheme.nodeEdgeAvailable(), 0, 0));

        if (view.showTitle() && !view.title().isEmpty()) {
            QuestNodeArt.caption(r, x, y, drawn, view.title(), pane.x(), pane.right(),
                    Math.min(pane.bottom(), frame.caption().y()));
        }

        // The one thing the picture cannot say for itself: whether it is the size the canvas will draw.
        // The caption used to spell out the shape, size, turn and icon scale too, every one of which
        // the control beside it already shows, and in a column this narrow it never got past the second.
        String caption = zoom < 0.999
                ? Labels.of("tenet.dev.preview.shown_at", Math.round(zoom * 100))
                : Labels.of("tenet.dev.preview.actual");
        r.text(Measure.truncate(caption, frame.caption().width(),
                        Measure.of(r::textWidth, r.lineHeight())),
                frame.caption().x(), frame.caption().y(), ArmatureTheme.faint());
    }

    // ------------------------------------------------------------------
    // The controls
    // ------------------------------------------------------------------

    private static void drawControls(GuiRenderer r, QuestSettingsLayout.Frame frame, Layout layout,
                                     List<QuestSettingsLayout.Row> rows, JsonObject quest, View view,
                                     Viewport column, double mouseX, double mouseY) {
        BookGeometry.Rect clip = frame.controls();
        if (clip.width() <= 0 || clip.height() <= 0) {
            return;
        }
        Measure measure = Measure.of(r::textWidth, r.lineHeight());
        try (GuiRenderer.Scoped scoped = r.clip(clip.x(), clip.y(), clip.right(), clip.bottom())) {
            for (QuestSettingsLayout.Row row : rows) {
                Slot slot = layout.slot(row.key());
                if (slot == null) {
                    continue;
                }
                // Where the row is *now*: the layout's slot through the viewport, which is where the
                // scroll and the column's origin are applied. Every rectangle the helpers below derive
                // -- a strip, a swatch cell, a slider track, an arrow -- is measured from this slot, and
                // each of those derivations is a difference of coordinates, so none of them cares which
                // space it is given as long as the drawing and the pointer agree on it. They agree on
                // the screen's.
                Slot onScreen = InspectLayout.onScreen(column, slot);
                boolean hovered = row.key().equals(view.hoveredKey());
                switch (row.kind()) {
                    case HEADING -> r.text(Labels.of(row.label()), onScreen.x(),
                            onScreen.y() + (onScreen.height() - 8) / 2, ArmatureTheme.title());
                    case SHAPE_GRID -> drawShapeGrid(r, onScreen, view, measure);
                    case SLIDER -> drawSlider(r, onScreen, row, view, hovered, mouseX, mouseY);
                    case NUMBER -> drawNumberLabel(r, onScreen, row, measure);
                    case SWITCH -> drawSwitch(r, onScreen, row, QuestPanelLayout.flag(quest, row.key()),
                            hovered);
                    case FIELD -> drawFieldLabel(r, onScreen, row, quest, measure);
                    case VALUE -> drawValue(r, onScreen, row, quest, measure);
                    case ICON -> drawIconRow(r, onScreen, view, hovered);
                    case CHOICE -> drawChoice(r, onScreen, row, quest, view, hovered);
                    case DEPENDENCY -> drawDependency(r, onScreen, row, measure);
                    case ACTION -> drawAction(r, onScreen, row, hovered);
                }
            }
        }
    }

    /**
     * The swatch grid: every shape the format names, drawn in its own geometry.
     *
     * <p>A swatch is the shape itself, not a picture of it and not a word for it — drawn with the same
     * spans the canvas draws, so the choice is made by looking at the thing rather than by reading its
     * name and hoping. The name is under it anyway, because a grid of twelve outlines is a puzzle
     * without one.
     */
    private static void drawShapeGrid(GuiRenderer r, Slot grid, View view, Measure measure) {
        QuestShape[] shapes = QuestShape.values();
        for (int index = 0; index < shapes.length; index++) {
            QuestShape shape = shapes[index];
            BookGeometry.Rect cell = QuestSettingsLayout.cellRect(grid, index, shapes.length);
            if (cell.width() <= 0 || cell.height() <= 0) {
                continue;
            }
            boolean selected = shape == view.shape();
            boolean hovered = index == view.hoveredCell();

            int swatch = Math.max(8, Math.min(cell.width() - 14,
                    QuestSettingsLayout.SWATCH_HEIGHT - 18));
            int x = cell.x() + (cell.width() - swatch) / 2;
            int y = cell.y() + 2;
            if (hovered || selected) {
                // The swatch's own backdrop, so the pointer and the choice are visible before the shape
                // is looked at -- a ring around a shape that is itself an outline reads as part of it.
                // Around the swatch and not the name: boxed in with it, the name had the cell's width
                // less the ring's, which is what cut "pentagon" short, and the ring ran through its
                // first and last letters besides.
                ArmatureTheme.panel(r, cell.x() + 1, cell.y(), Math.max(0, cell.width() - 2),
                        Math.min(swatch + 4, Math.max(0, cell.height() - 1)),
                        hovered ? Colour.lerp(ArmatureTheme.panel(), ArmatureTheme.raised(), 0.7F)
                                : ArmatureTheme.raised(),
                        selected ? ArmatureTheme.selectedRing() : ArmatureTheme.panelEdge());
            }
            if (shape == QuestShape.NONE) {
                // No panel to draw, so the swatch says so with a dashed outline: an empty cell would
                // read as a shape that failed to render.
                dashedBox(r, x, y, swatch, swatch, ArmatureTheme.controlEdge());
            }
            else {
                ArmatureTheme.shapePanel(r, x, y, swatch, ArmatureTheme.nodeFill(),
                        selected ? ArmatureTheme.available() : ArmatureTheme.controlEdge(),
                        shape.geometry());
            }

            String name = shape.name().toLowerCase(java.util.Locale.ROOT);
            String shown = Measure.truncate(name, cell.width(), measure);
            r.text(shown, cell.x() + (cell.width() - r.textWidth(shown)) / 2,
                    cell.y() + cell.height() - 12,
                    selected ? ArmatureTheme.title() : ArmatureTheme.body());
        }
    }

    /** A slider: label, the track with its knob, the value, and the exact-value steppers. */
    private static void drawSlider(GuiRenderer r, Slot slot, QuestSettingsLayout.Row row, View view,
                                   boolean hovered, double mouseX, double mouseY) {
        Slot strip = QuestSettingsLayout.strip(row, slot);
        drawRowLabel(r, slot, strip, row.label());

        boolean size = row.key().equals("size");
        boolean rotation = row.key().equals("rotation");
        int min = size ? QuestSettingsLayout.MIN_SIZE : QuestSettingsLayout.MIN_ROTATION;
        int max = size ? QuestSettingsLayout.MAX_SIZE : QuestSettingsLayout.MAX_ROTATION;
        double value = size ? view.size() : rotation ? view.rotation() : view.iconScale();
        // Logarithmic for the size only: a doubling is the same distance everywhere on that track, and
        // a rotation is a plain count of degrees.
        boolean logarithmic = size;

        BookGeometry.Rect track = QuestSettingsLayout.track(strip);
        if (track.width() > 0) {
            ArmatureTheme.panel(r, track.x(), track.y(), track.width(), track.height(),
                    ArmatureTheme.recessed(), ArmatureTheme.panelEdge());
            double fraction = size || rotation
                    ? QuestSettingsLayout.fractionOf(value, min, max, logarithmic)
                    : QuestSettingsLayout.fractionOf(value, QuestSettingsLayout.MIN_ICON_SCALE,
                            QuestSettingsLayout.MAX_ICON_SCALE, false);
            int filled = (int) Math.round(fraction * Math.max(0, track.width() - 1));
            if (filled > 0) {
                r.fill(track.x(), track.y(), track.x() + filled, track.bottom(),
                        ArmatureTheme.available());
            }
            BookGeometry.Rect knob = size || rotation
                    ? QuestSettingsLayout.knob(track, value, min, max, logarithmic)
                    : QuestSettingsLayout.knob(track, value, QuestSettingsLayout.MIN_ICON_SCALE,
                            QuestSettingsLayout.MAX_ICON_SCALE, false);
            boolean hot = knob.contains(mouseX, mouseY);
            ArmatureTheme.panel(r, knob.x(), knob.y(), knob.width(), knob.height(),
                    hot ? ArmatureTheme.title() : ArmatureTheme.body(), ArmatureTheme.panelEdge());
        }

        String shown = size || rotation
                ? (rotation ? ((int) value) + "\u00b0" : String.valueOf((int) value))
                : trim(value);
        r.text(shown, strip.right() - QuestSettingsLayout.ARROW_WIDTH - r.textWidth(shown) - 3,
                slot.y() + (slot.height() - 8) / 2, ArmatureTheme.title());

        drawStepperArrows(r, strip, hovered, mouseX, mouseY);
    }


    /** A numeric row: the label only; the field beside it is a widget and draws its own box. */
    private static void drawNumberLabel(GuiRenderer r, Slot slot, QuestSettingsLayout.Row row,
                                        Measure measure) {
        int room = Math.max(0, slot.width() - ScrubField.BOX_WIDTH - 6);
        r.text(Measure.truncate(Labels.of(row.label()), room, measure), slot.x() + 2,
                slot.y() + (slot.height() - 8) / 2, ArmatureTheme.body());
    }

    /** A switch: the label, and a track whose knob is at one end or the other. */
    private static void drawSwitch(GuiRenderer r, Slot slot, QuestSettingsLayout.Row row, boolean on,
                                   boolean hovered) {
        Slot strip = QuestSettingsLayout.strip(row, slot);
        // **The knob is the state, so the label does not repeat it.** It used to read "Hide text until
        // done · off" beside a knob that said exactly that — the same fact twice, and the repeat was
        // charged to the label's room: at a docked column's width that suffix was the difference between
        // "Hide details until startable" fitting and being cut to "Hide det…". The knob is a switch drawn
        // at one end or the other; a word beside it says nothing a reader cannot see, and takes the room
        // of the name they cannot.
        //
        // The state is still read from the live flag rather than baked into the row — the row is built once
        // and the tree can change under it, a press writing a draft the server has not answered — which is
        // why the *track* below is composed here and `QuestSettingsLayout.rows()` says nothing about it.
        drawRowLabel(r, slot, strip, Labels.of(row.label()));
        BookGeometry.Rect track = QuestSettingsLayout.switchTrack(strip);
        ArmatureTheme.panel(r, track.x(), track.y(), track.width(), track.height(),
                on ? ArmatureTheme.available()
                        : (hovered ? Colour.lerp(ArmatureTheme.recessed(), ArmatureTheme.title(), 0.10F)
                                : ArmatureTheme.recessed()),
                ArmatureTheme.panelEdge());
        int knobWidth = track.height() - 2;
        int knobX = on ? track.right() - knobWidth - 1 : track.x() + 1;
        r.fill(knobX, track.y() + 1, knobX + knobWidth, track.bottom() - 1, ArmatureTheme.title());
    }

    /** A field's label: the box itself is a widget, and it draws in the strip. */
    private static void drawFieldLabel(GuiRenderer r, Slot slot, QuestSettingsLayout.Row row,
                                       JsonObject quest, Measure measure) {
        Slot strip = QuestSettingsLayout.strip(row, slot);
        drawRowLabel(r, slot, strip, row.label());
        if (row.key().equals("exclusiveGroup") || row.key().equals("prerequisiteMode")) {
            // Shown under the field as a faint hint of what is stored, because both are optional and an
            // empty box says nothing about whether the field is absent or blank.
            String value = displayValue(quest, row.key());
            if (!value.isEmpty()) {
                r.text(Measure.truncate(value, strip.width(), measure), strip.x(),
                        slot.y() + slot.height() - 8, ArmatureTheme.faint());
            }
        }
    }

    /** A read-only row: the label, and the value after it. */
    private static void drawValue(GuiRenderer r, Slot slot, QuestSettingsLayout.Row row,
                                  JsonObject quest, Measure measure) {
        Slot strip = QuestSettingsLayout.strip(row, slot);
        drawRowLabel(r, slot, strip, row.label());
        String value = displayValue(quest, row.key());
        r.text(Measure.truncate(value, strip.width(), measure), strip.x(),
                slot.y() + (slot.height() - 8) / 2, ArmatureTheme.faint());
    }

    /**
     * The requirement picker: a closed set of values with an arrow at each end.
     *
     * <p>The same two arrows a stepper has, in the same boxes, because it is the same gesture -- step
     * through the values -- and the only difference is that the values wrap and are named. It replaced a
     * free-text box, where a typo was a value the client drew as written and the server refused, which
     * looked like the edit silently failing.
     */
    private static void drawChoice(GuiRenderer r, Slot slot, QuestSettingsLayout.Row row,
                                   JsonObject quest, View view, boolean hovered) {
        Slot strip = QuestSettingsLayout.strip(row, slot);
        drawRowLabel(r, slot, strip, row.label());
        String value = displayValue(quest, row.key());
        // The auto-claim row defers to the chapter rather than to a mode, so its unset label is not the
        // requirement row's "Chapter default (all_completed)" shape.
        String shown = switch (row.key()) {
            case "autoClaim" -> QuestSettingsLayout.autoClaimLabel(value);
            case "hideUntilDependenciesComplete" ->
                    QuestSettingsLayout.triStateLabel(value, view.chapterHideUntilDepsComplete());
            case "hideUntilDependenciesVisible" ->
                    QuestSettingsLayout.triStateLabel(value, view.chapterHideUntilDepsVisible());
            // The viewer flag defers to the file's settings rather than to the chapter, and the
            // file's settings are not synced to this client — so its unset state names no value.
            // See `QuestSettingsLayout.fileDefaultLabel`.
            case "disableRecipeMod" -> QuestSettingsLayout.fileDefaultLabel(value);
            default -> QuestSettingsLayout.requirementLabel(value, view.chapterRequirement());
        };
        r.text(Measure.truncate(shown, Math.max(0, strip.width() - 40),
                        Measure.of(r::textWidth, r.lineHeight())),
                strip.x() + QuestSettingsLayout.ARROW_WIDTH + 4,
                slot.y() + (slot.height() - 8) / 2,
                value.isEmpty() ? ArmatureTheme.faint() : ArmatureTheme.title());
        drawStepperArrows(r, strip, hovered, -1, -1);
    }

    /**
     * One prerequisite: its title, its id under it when the two differ, and an x at the right.
     *
     * <p>The id is shown because it is what the file holds and what an "Add by id" row takes, so an
     * author who removes the title's row can type the id back; and it is faint because the title is what
     * they read. A row whose target the client has never heard of shows the id as its title, which is
     * also the honest answer -- the id is all there is.
     */
    private static void drawDependency(GuiRenderer r, Slot slot, QuestSettingsLayout.Row row,
                                       Measure measure) {
        Slot strip = QuestSettingsLayout.strip(row, slot);
        int room = Math.max(0, strip.x() - slot.x() - 6);
        // The label is a quest's own title, or its id when the client has never heard of it -- content
        // rather than prose, so `Labels.of` passes it through; the equality checks below read the raw
        // label, which is what the two-line layout is decided by.
        r.text(Measure.truncate(Labels.of(row.label()), room, measure), slot.x(),
                slot.y() + (slot.height() - 8) / 2 - (row.label().equals(row.key().substring(
                        QuestSettingsLayout.DEPENDENCY_PREFIX.length())) ? 0 : 3),
                ArmatureTheme.title());
        String id = row.key().substring(QuestSettingsLayout.DEPENDENCY_PREFIX.length());
        if (!row.label().equals(id)) {
            r.text(Measure.truncate(id, room, measure), slot.x(), slot.y() + slot.height() / 2 + 3,
                    ArmatureTheme.faint());
        }
        BookGeometry.Rect remove = QuestSettingsLayout.removeBox(strip);
        ArmatureTheme.panel(r, remove.x(), remove.y(), remove.width(), remove.height(),
                ArmatureTheme.recessed(), ArmatureTheme.panelEdge());
        String glyph = "×";
        r.text(glyph, remove.x() + (remove.width() - r.textWidth(glyph)) / 2,
                remove.y() + (remove.height() - 8) / 2, ArmatureTheme.body());
    }

    /** A row that is a button: the strip is the button, and its label is centred in it. */
    private static void drawAction(GuiRenderer r, Slot slot, QuestSettingsLayout.Row row,
                                   boolean hovered) {
        Slot strip = QuestSettingsLayout.strip(row, slot);
        BookGeometry.Rect button = QuestSettingsLayout.actionBox(strip);
        // "Add selected" with no selection is drawn dimmed, and its press is refused: a button that
        // looks live and does nothing is the thing this page keeps being fixed for. Which of the two
        // keys the row carries is the answer -- the counted one is only chosen when something is
        // selected -- so this reads the key rather than sniffing the label for a bracket.
        boolean live = !row.key().equals(QuestSettingsLayout.DEPENDENCY_SELECTED)
                || row.label().equals(QuestSettingsLayout.ADD_SELECTED_COUNT);
        ArmatureTheme.panel(r, button.x(), button.y(), button.width(), button.height(),
                hovered && live ? Colour.lerp(ArmatureTheme.raised(), ArmatureTheme.title(), 0.12F)
                        : ArmatureTheme.raised(),
                ArmatureTheme.panelEdge());
        String label = Labels.of(row.label());
        r.text(Measure.truncate(label, Math.max(0, button.width() - 8),
                        Measure.of(r::textWidth, r.lineHeight())),
                button.x() + (button.width() - r.textWidth(label)) / 2,
                button.y() + (button.height() - 8) / 2,
                live ? ArmatureTheme.body() : ArmatureTheme.faint());
    }

    /** The icon row: the item itself, and a button that opens the picker. */
    private static void drawIconRow(GuiRenderer r, Slot slot, View view, boolean hovered) {
        Slot strip = QuestSettingsLayout.strip(QuestSettingsLayout.Row.Kind.ICON, slot);
        drawRowLabel(r, slot, strip, "tenet.dev.quest.icon");
        BookGeometry.Rect button = iconButton(strip);
        ArmatureTheme.panel(r, button.x(), button.y(), button.width(), button.height(),
                hovered ? Colour.lerp(ArmatureTheme.raised(), ArmatureTheme.title(), 0.12F)
                        : ArmatureTheme.raised(),
                ArmatureTheme.panelEdge());
        String label = Labels.of("tenet.dev.change");
        r.text(label, button.x() + (button.width() - r.textWidth(label)) / 2,
                button.y() + (button.height() - 8) / 2, ArmatureTheme.body());
        if (view.icon() != null && !view.icon().isEmpty()) {
            r.icon(view.icon(), strip.x(), strip.y() + (strip.height() - 16) / 2, 16);
        }
        else if (view.textureIcon() != null && !view.textureIcon().isEmpty()) {
            net.minecraft.resources.Identifier texture =
                    net.minecraft.resources.Identifier.tryParse(view.textureIcon());
            if (texture != null) {
                r.texture(texture, strip.x(), strip.y() + (strip.height() - 16) / 2, 16, 16);
            }
        }
        else if (view.spriteIcon() != null && !view.spriteIcon().isEmpty()) {
            // An atlas region, drawn from the atlas like the node's own: the stack is empty for a
            // sprite for the same reason it is for a texture.
            net.minecraft.resources.Identifier sprite =
                    net.minecraft.resources.Identifier.tryParse(view.spriteIcon());
            if (sprite != null) {
                r.sprite(sprite, strip.x(), strip.y() + (strip.height() - 16) / 2, 16, 16, 0xFFFFFFFF);
            }
        }
    }

    /** Where the icon row's Change button sits. */
    public static BookGeometry.Rect iconButton(Slot strip) {
        return BookGeometry.Rect.at(strip.x() + 20, strip.y() + (strip.height() - 14) / 2, 78, 14);
    }

    // ------------------------------------------------------------------
    // The pieces
    // ------------------------------------------------------------------

    /** A row's label, truncated to the room left of its strip. The label is a key; resolved here. */
    private static void drawRowLabel(GuiRenderer r, Slot slot, Slot strip, String label) {
        int room = Math.max(0, strip.x() - slot.x() - 6);
        r.text(Measure.truncate(Labels.of(label), room, Measure.of(r::textWidth, r.lineHeight())),
                slot.x(), slot.y() + (slot.height() - 8) / 2, ArmatureTheme.body());
    }

    /** A stepper's two arrows, drawn from the boxes the press reads. */
    private static void drawStepperArrows(GuiRenderer r, Slot strip, boolean rowHovered,
                                          double mouseX, double mouseY) {
        for (String way : List.of("down", "up")) {
            BookGeometry.Rect box = QuestSettingsLayout.arrowBox(strip, way);
            boolean hot = rowHovered && box.contains(mouseX, mouseY);
            ArmatureTheme.panel(r, box.x(), box.y(), box.width(), box.height(),
                    hot ? Colour.lerp(ArmatureTheme.raised(), ArmatureTheme.title(), 0.12F)
                            : ArmatureTheme.raised(),
                    ArmatureTheme.panelEdge());
            String glyph = way.equals("down") ? "\u2212" : "+";
            r.text(glyph, box.x() + (box.width() - r.textWidth(glyph)) / 2 + 1,
                    box.y() + (box.height() - 8) / 2, hot ? ArmatureTheme.title() : ArmatureTheme.body());
        }
    }

    /** The help line: what the control under the pointer is for. */
    private static void drawHelp(GuiRenderer r, QuestSettingsLayout.Frame frame, View view) {
        BookGeometry.Rect help = frame.help();
        if (help.width() <= 0 || help.height() <= 0) {
            return;
        }
        // `Map.of` refuses a null key, and "nothing is hovered" is a real state rather than a lookup
        // that should have been avoided -- so the null is checked here, once.
        String text = view.hoveredKey() == null ? null : HELP.get(view.hoveredKey());
        if (text == null) {
            text = "tenet.dev.quest.help.default";
        }
        r.text(Measure.truncate(Labels.of(text), help.width(),
                        Measure.of(r::textWidth, r.lineHeight())),
                help.x(), help.y() + (help.height() - 8) / 2, ArmatureTheme.faint());
    }

    /** A dashed one-pixel box: what a shape with no panel looks like as a swatch. */
    private static void dashedBox(GuiRenderer r, int x, int y, int width, int height, int colour) {
        for (int i = 0; i < width; i += 4) {
            int length = Math.min(2, width - i);
            r.fill(x + i, y, x + i + length, y + 1, colour);
            r.fill(x + i, y + height - 1, x + i + length, y + height, colour);
        }
        for (int i = 0; i < height; i += 4) {
            int length = Math.min(2, height - i);
            r.fill(x, y + i, x + 1, y + i + length, colour);
            r.fill(x + width - 1, y + i, x + width, y + i + length, colour);
        }
    }

    /** A double as a field shows it: no trailing zeros, at most two decimals. */
    private static String trim(double value) {
        String shown = String.format(java.util.Locale.ROOT, "%.2f", value);
        while (shown.endsWith("0")) {
            shown = shown.substring(0, shown.length() - 1);
        }
        if (shown.endsWith(".")) {
            shown = shown.substring(0, shown.length() - 1);
        }
        return shown;
    }

    private static int intValue(JsonObject quest, String key, int fallback) {
        JsonElement value = quest == null ? null : QuestPanelLayout.get(quest, key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? value.getAsInt() : fallback;
    }

    private static double doubleValue(JsonObject quest, String key, double fallback) {
        JsonElement value = quest == null ? null : QuestPanelLayout.get(quest, key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? value.getAsDouble() : fallback;
    }

    private static String displayValue(JsonObject quest, String key) {
        JsonElement value = quest == null ? null : QuestPanelLayout.get(quest, key);
        if (value == null) {
            return "";
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }
}
