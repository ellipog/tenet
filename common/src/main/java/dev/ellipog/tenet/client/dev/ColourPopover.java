package dev.ellipog.tenet.client.dev;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.tenet.client.BookGeometry;

import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The floating colour picker: a saturation/value box, two tracks, a hex field and three channel fields.
 *
 * <h2>An overlay, not a band</h2>
 *
 * <p>It replaces the docked colour footer -- a swatch line, four channel steppers and a permanent hex
 * field, all of which were spent on one colour at a time whether or not anybody was editing one. This
 * opens from the chip on the row the colour belongs to, sits to the left of the drawer (over the canvas,
 * where nothing can be underneath it to steal its presses), and closes when the edit is done.
 *
 * <h2>One commit per gesture, on the same terms as every other control</h2>
 *
 * <p>{@link #preview} is called as the pointer moves -- the canvas follows the drag before anything is
 * written -- and {@link #commit} once per released gesture, and once more on close if the last change was
 * not written yet. Both are the screen's: the popover does not know whether the target is a chapter's
 * patch or the player's own theme, and it must not.
 *
 * <h2>The fields are widgets; the picker is not</h2>
 *
 * <p>The hex box and the three channel fields are {@code ArmatureTextField} and {@code ScrubField}, because
 * typing and scrubbing are what those already do; the screen adds them while the popover is open and
 * removes them when it closes ({@link #widgets()}). The picker's own surface, tracks, swatches and markers
 * are drawn here, and their presses are hit-tested here -- one rectangle per part, derived once.
 */
public final class ColourPopover {

    public static final int WIDTH = 200;
    /** The saturation/value box: the design's 160x120, with the panel's own padding either side. */
    public static final int SV_WIDTH = 160;
    public static final int SV_HEIGHT = 120;
    /** The two tracks: hue, then alpha over a checkerboard. */
    public static final int TRACK_HEIGHT = 12;
    private static final int PAD = 8;
    private static final int GAP = 6;
    private static final int SWATCH = 18;
    private static final int SWATCH_GAP = 4;
    private static final int RESET_WIDTH = 44;
    private static final int BUTTON_HEIGHT = 16;

    /**
     * The colours offered as quick picks, seeded when the picker opens.
     *
     * <h2>Why this is not static, which it was</h2>
     *
     * <p>It was one list for the whole client: shared across chapters, targets and pickers, grown forever
     * by `+`, never saved — and once it passed the slots that fit, {@code presetRect} answered null for the
     * overflow while `+` went on appending, so a press did nothing and said nothing. A quick pick is a
     * convenience for the colour being edited, not a preference, so it is seeded per open from the theme's
     * own surfaces and dies with the picker.
     */
    private final List<Integer> presets = new ArrayList<>();

    private boolean open;
    private BookGeometry.Rect panel = BookGeometry.Rect.at(0, 0, 0, 0);
    private BookGeometry.Rect sv = BookGeometry.Rect.at(0, 0, 0, 0);
    private BookGeometry.Rect hue = BookGeometry.Rect.at(0, 0, 0, 0);
    private BookGeometry.Rect alpha = BookGeometry.Rect.at(0, 0, 0, 0);
    private BookGeometry.Rect swatches = BookGeometry.Rect.at(0, 0, 0, 0);
    private BookGeometry.Rect plus = BookGeometry.Rect.at(0, 0, 0, 0);
    private BookGeometry.Rect reset = BookGeometry.Rect.at(0, 0, 0, 0);

    private String label = "";
    private int argb = 0xFFFFFFFF;
    /** What was in force when the picker opened: what Reset returns to and what "changed" means. */
    private int openedWith = 0xFFFFFFFF;
    /** The last value handed to the target, so a release that changed nothing writes nothing. */
    private int committed = 0xFFFFFFFF;

    private IntConsumer preview = value -> {
    };
    private IntConsumer commit = value -> {
    };

    /** Which part a press claimed, so the drag and the release go to the same one. */
    private Part dragging = Part.NONE;

    /** Where the last frame drew it: the part the arrow keys nudge. See {@link #hoverPart}. */
    private double pointerX;
    private double pointerY;

    private enum Part {
        NONE,
        SV,
        HUE,
        ALPHA
    }

    private final dev.ellipog.armature.client.ArmatureTextField hex;
    private final ScrubField red;
    private final ScrubField green;
    private final ScrubField blue;

    public ColourPopover() {
        hex = new dev.ellipog.armature.client.ArmatureTextField(0, 0, 0, 0, "#FFFFFF");
        hex.onSubmit(this::typeHex);
        hex.colours(ArmatureTheme.title(), ArmatureTheme.recessed(), ArmatureTheme.panelEdge());
        red = channelField();
        green = channelField();
        blue = channelField();
        // The channel fields write the moment they commit, which is once per gesture -- the same contract
        // the tracks have, so a channel drag and a track drag cannot differ in how many undo steps they cost.
        red.onCommit(value -> set(ColourMath.withChannel(argb, 0, (int) Math.round(value))));
        green.onCommit(value -> set(ColourMath.withChannel(argb, 1, (int) Math.round(value))));
        blue.onCommit(value -> set(ColourMath.withChannel(argb, 2, (int) Math.round(value))));
    }

    private ScrubField channelField() {
        return new ScrubField(0, 0, 255, 1, "");
    }

    /** Whether the picker is showing. */
    public boolean isOpen() {
        return open;
    }

    /** The whole panel, for the caller's own hit test ("was this press outside?"). */
    public BookGeometry.Rect panel() {
        return panel;
    }

    /**
     * The fields the screen has to add to itself while the picker is open.
     *
     * <p>They are ordinary widgets: the screen adds them when it opens the picker and removes them when it
     * closes, exactly as it does with the drawer's own fields. Their positions are set by {@link #place},
     * which runs on open and whenever the panel is sized.
     */
    public List<AbstractWidget> widgets() {
        return List.of(hex, red, green, blue);
    }

    /**
     * Opens the picker beside a chip.
     *
     * @param anchor  the chip's rectangle, in screen coordinates: the panel is placed at its left
     * @param bounds  the canvas, which the panel is kept inside -- so it can never cover the drawer it
     *                belongs to, and its presses are never shadowed by a row's own field
     * @param label   the property's name, drawn at the panel's top
     * @param argb    the colour in force
     * @param presets the quick picks; the caller passes what it wants offered, and `+` appends to this list
     */
    public void open(BookGeometry.Rect anchor, BookGeometry.Rect bounds, String label, int argb,
                     List<Integer> presets, IntConsumer preview, IntConsumer commit) {
        this.open = true;
        this.label = label == null ? "" : label;
        this.argb = argb;
        this.openedWith = argb;
        this.committed = argb;
        this.preview = preview == null ? value -> {
        } : preview;
        this.commit = commit == null ? value -> {
        } : commit;
        // Seeded per open, the value in force included: "what this is now" is the quick pick an author
        // reaches for most, and the list dies with the picker rather than growing for the whole session.
        this.presets.clear();
        for (int preset : presets) {
            addPreset(preset);
        }
        addPreset(argb);
        place(anchor, bounds);
        syncFields();
    }

    /** One quick pick, without the alpha (a swatch is a colour, not a strength) and without duplicates. */
    private void addPreset(int colour) {
        int rgb = colour & 0xFFFFFF;
        if (!presets.contains(rgb)) {
            presets.add(rgb);
        }
    }

    /** How many quick picks the row can show: the slots that fit, less the one `+` keeps. */
    private int presetSlots() {
        int fits = Math.max(1, (sv.width() + SWATCH_GAP) / (SWATCH + SWATCH_GAP));
        return Math.max(0, fits - 1);
    }

    /** Closes, writing anything the last gesture left unwritten -- one op, on the same terms as a release. */
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        dragging = Part.NONE;
        if (argb != committed) {
            committed = argb;
            commit.accept(argb);
        }
    }

    /** The geometry, from the anchor and the bounds. */
    private void place(BookGeometry.Rect anchor, BookGeometry.Rect bounds) {
        int height = PAD + 10 + GAP
                + SV_HEIGHT + GAP
                + TRACK_HEIGHT + GAP
                + TRACK_HEIGHT + GAP
                + BUTTON_HEIGHT + GAP
                + BUTTON_HEIGHT + GAP
                + SWATCH + PAD;
        int x = anchor.x() - WIDTH - GAP;
        // Beside the chip when there is room to the left, and clamped inside the canvas otherwise: a
        // picker half off the canvas is a picker whose drags leave the window.
        x = Math.max(bounds.x() + 2, Math.min(x, bounds.right() - WIDTH - 2));
        int y = Math.max(bounds.y() + 2, Math.min(anchor.y(), bounds.bottom() - height - 2));
        panel = BookGeometry.Rect.at(x, y, WIDTH, height);

        int cursor = panel.y() + PAD + 10 + GAP;
        int inner = WIDTH - PAD * 2;
        sv = BookGeometry.Rect.at(panel.x() + PAD, cursor, Math.min(SV_WIDTH, inner), SV_HEIGHT);
        cursor = sv.bottom() + GAP;
        hue = BookGeometry.Rect.at(sv.x(), cursor, sv.width(), TRACK_HEIGHT);
        cursor = hue.bottom() + GAP;
        alpha = BookGeometry.Rect.at(sv.x(), cursor, sv.width(), TRACK_HEIGHT);
        cursor = alpha.bottom() + GAP;
        // The hex field keeps the left; Reset the right.
        hex.setX(sv.x());
        hex.setY(cursor);
        hex.setWidth(Math.max(0, sv.width() - RESET_WIDTH - GAP));
        hex.setHeight(BUTTON_HEIGHT);
        reset = BookGeometry.Rect.at(sv.right() - RESET_WIDTH, cursor, RESET_WIDTH, BUTTON_HEIGHT);
        cursor += BUTTON_HEIGHT + GAP;

        // Three channels abreast under the hex, each a ScrubField's own width.
        int channelWidth = Math.max(0, (sv.width() - GAP * 2) / 3);
        placeChannel(red, sv.x(), cursor, channelWidth);
        placeChannel(green, sv.x() + channelWidth + GAP, cursor, channelWidth);
        placeChannel(blue, sv.x() + (channelWidth + GAP) * 2, cursor, channelWidth);
        cursor += BUTTON_HEIGHT + GAP;

        swatches = BookGeometry.Rect.at(sv.x(), cursor, sv.width(), SWATCH);
        plus = BookGeometry.Rect.at(swatches.right() - SWATCH, cursor, SWATCH, SWATCH);
        // One slot fewer than fits is reserved for the `+`, which is drawn last and always present -- and
        // the row is as wide as the picks that will actually be shown, so a full row cannot push a swatch
        // under the `+` and leave a press that does nothing.
        swatches = BookGeometry.Rect.at(swatches.x(), cursor,
                Math.max(0, Math.min(presetSlots(), presets.size()) * (SWATCH + SWATCH_GAP)), SWATCH);
    }

    /** The hex field's text: eight digits while the alpha matters, six while it does not. */
    private String hexText() {
        int alphaChannel = ColourMath.channel(argb, 3);
        return alphaChannel == 0xFF
                ? String.format("#%06X", argb & 0xFFFFFF)
                : String.format("#%08X", argb);
    }

    private static void placeChannel(ScrubField field, int x, int y, int width) {
        field.setX(x);
        field.setY(y);
        field.setWidth(width);
        field.setHeight(BUTTON_HEIGHT);
        field.value(field.value());
    }

    /** Pushes the current colour into the fields, without touching the target. */
    private void syncFields() {
        hex.setValue(hexText());
        red.value(ColourMath.channel(argb, 0));
        green.value(ColourMath.channel(argb, 1));
        blue.value(ColourMath.channel(argb, 2));
        hsvCache = ColourMath.toHsv(argb);
    }

    /** The hue the tracks are drawn with, kept between frames so a grey's hue survives a drag. */
    private ColourMath.Hsv hsvCache = new ColourMath.Hsv(0F, 0F, 1F);

    // ------------------------------------------------------------------
    // The edit
    // ------------------------------------------------------------------

    /** Sets the colour from a gesture: previews it, and lets the release commit it. */
    private void set(int next) {
        if (next == argb) {
            return;
        }
        argb = next;
        hsvCache = ColourMath.toHsv(argb);
        syncFieldsWithoutRecursion();
        preview.accept(argb);
    }

    /**
     * Re-syncs the hex and channel texts after a track drag.
     *
     * <p>The channel fields must not commit what this writes: setting a widget's value is the caller's
     * state, and a commit here would be a write per frame of a drag -- the thing the whole design avoids.
     * So their model is updated through the same silent setter the screen would use.
     */
    private void syncFieldsWithoutRecursion() {
        hex.setValue(hexText());
        red.value(ColourMath.channel(argb, 0));
        green.value(ColourMath.channel(argb, 1));
        blue.value(ColourMath.channel(argb, 2));
    }

    private void typeHex(String text) {
        Integer parsed = dev.ellipog.tenet.client.dev.HexColour.parse(text, argb);
        if (parsed == null) {
            hex.setValue(hexText());
            return;
        }
        set(parsed);
        commit.accept(argb);
        committed = argb;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event) {
        if (!open) {
            return false;
        }
        // Left is 1 on SDL, where it was 0 on GLFW: asked as a comparison, because
        // `isValidClickButton` lives on AbstractWidget and this is not one.
        boolean left = event.button()
                == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;
        double mouseX = event.x();
        double mouseY = event.y();
        if (left && reset.contains(mouseX, mouseY)) {
            set(openedWith);
            commit.accept(argb);
            committed = argb;
            return true;
        }
        if (left && plus.contains(mouseX, mouseY)) {
            // Nothing to add once the row is full, and nothing pretended either: the `+` is drawn dimmed
            // when there is no room, which is the half the old list was missing -- it kept appending past
            // the slots that fit, so a press did nothing and said nothing.
            if (presets.size() < presetSlots()) {
                addPreset(argb);
            }
            return true;
        }
        int preset = presetAt(mouseX, mouseY);
        if (preset >= 0) {
            set(preset | 0xFF000000);
            commit.accept(argb);
            committed = argb;
            return true;
        }
        if (sv.contains(mouseX, mouseY)) {
            dragging = Part.SV;
            dragTo(mouseX, mouseY);
            return true;
        }
        if (hue.contains(mouseX, mouseY)) {
            dragging = Part.HUE;
            dragTo(mouseX, mouseY);
            return true;
        }
        if (alpha.contains(mouseX, mouseY)) {
            dragging = Part.ALPHA;
            dragTo(mouseX, mouseY);
            return true;
        }
        return panel.contains(mouseX, mouseY);
    }

    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event) {
        if (!open || dragging == Part.NONE
                || event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        dragTo(event.x(), event.y());
        return true;
    }

    /** The release writes once, and only when the gesture changed the colour. */
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (!open
                || event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        if (dragging != Part.NONE) {
            dragging = Part.NONE;
            if (argb != committed) {
                committed = argb;
                commit.accept(argb);
            }
            return true;
        }
        return false;
    }

    /** A press on a track goes straight to the value under the pointer, and then drags it. */
    private void dragTo(double mouseX, double mouseY) {
        switch (dragging) {
            case SV -> {
                float saturation = fraction(mouseX, sv.x(), sv.width());
                float value = 1F - fraction(mouseY, sv.y(), sv.height());
                set(ColourMath.fromHsv(hsvCache.hue(), saturation, value,
                        ColourMath.channel(argb, 3)));
            }
            case HUE -> set(ColourMath.fromHsv(fraction(mouseX, hue.x(), hue.width()),
                    hsvCache.saturation(), hsvCache.value(), ColourMath.channel(argb, 3)));
            case ALPHA -> set(ColourMath.fromHsv(hsvCache.hue(), hsvCache.saturation(), hsvCache.value(),
                    Math.round(fraction(mouseX, alpha.x(), alpha.width()) * 255F)));
            default -> {
            }
        }
    }

    private static float fraction(double point, int from, int length) {
        if (length <= 1) {
            return 0F;
        }
        return Math.max(0F, Math.min(1F, (float) ((point - from) / (length - 1))));
    }

    private int presetAt(double mouseX, double mouseY) {
        for (int i = 0; i < presets.size(); i++) {
            BookGeometry.Rect slot = presetRect(i);
            if (slot != null && slot.contains(mouseX, mouseY)) {
                return presets.get(i);
            }
        }
        return -1;
    }

    private BookGeometry.Rect presetRect(int index) {
        int x = swatches.x() + index * (SWATCH + SWATCH_GAP);
        if (x + SWATCH > swatches.right()) {
            return null;
        }
        return BookGeometry.Rect.at(x, swatches.y(), SWATCH, SWATCH);
    }

    /**
     * The picker's keys: the arrows nudge the part the pointer is over, and Shift takes a ten-step.
     *
     * <h2>Why there is a keyboard at all now</h2>
     *
     * <p>Because there was not, and it showed: the only key this ever answered was Escape — which the
     * screen handles itself, so this method was not called at all — and the fields that <i>can</i> be typed
     * into are the hex and the three channels, which are the wrong shape for "one step to the left".
     *
     * <h2>Where "the part" comes from</h2>
     *
     * <p>From the pointer the last {@link #render} saw, not from a second parameter: the picker is drawn
     * with the frame's own pointer, so the part a key nudges is the part the crosshair is nearest, and the
     * two cannot disagree. Escape is deliberately not here — it needs the screen's rebuild, which removes
     * these fields, and a second way to close that never ran is what this method used to be.
     *
     * @param shift whether Shift is down, which is the coarse step
     * @return whether the key was the picker's
     */
    public boolean keyPressed(int keyCode, boolean shift) {
        if (!open) {
            return false;
        }
        int step = shift ? 10 : 1;
        // Arrow scancodes: the constants changed values with the SDL move, so these are the
        // `InputConstants` ones for the same physical keys.
        if (keyCode == com.mojang.blaze3d.platform.InputConstants.KEY_LEFT) {
            nudge(-step, 0);
            return true;
        }
        if (keyCode == com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT) {
            nudge(step, 0);
            return true;
        }
        if (keyCode == com.mojang.blaze3d.platform.InputConstants.KEY_UP) {
            nudge(0, -step);
            return true;
        }
        if (keyCode == com.mojang.blaze3d.platform.InputConstants.KEY_DOWN) {
            nudge(0, step);
            return true;
        }
        return false;
    }

    /**
     * Which part the keys act on: the one under the pointer, or the saturation/value box anywhere else on
     * the panel — a key that did nothing over the hex field would read as a broken key.
     */
    private Part hoverPart() {
        if (sv.contains(pointerX, pointerY)) {
            return Part.SV;
        }
        if (hue.contains(pointerX, pointerY)) {
            return Part.HUE;
        }
        if (alpha.contains(pointerX, pointerY)) {
            return Part.ALPHA;
        }
        return panel.contains(pointerX, pointerY) ? Part.SV : Part.NONE;
    }

    /** One step of the arrows, on the part under the pointer. */
    private void nudge(int dx, int dy) {
        int alphaChannel = ColourMath.channel(argb, 3);
        switch (hoverPart()) {
            case SV -> {
                float saturation = clampUnit(hsvCache.saturation()
                        + dx / (float) Math.max(1, sv.width() - 1));
                float value = clampUnit(hsvCache.value()
                        - dy / (float) Math.max(1, sv.height() - 1));
                set(ColourMath.fromHsv(hsvCache.hue(), saturation, value, alphaChannel));
            }
            case HUE -> set(ColourMath.fromHsv(
                    wrapUnit(hsvCache.hue() + dx / (float) Math.max(1, hue.width() - 1)),
                    hsvCache.saturation(), hsvCache.value(), alphaChannel));
            case ALPHA -> set(ColourMath.withChannel(argb, 3, Math.max(0,
                    Math.min(255, alphaChannel + dx * 255 / Math.max(1, alpha.width() - 1)))));
            case NONE -> {
            }
        }
    }

    private static float clampUnit(float value) {
        return Math.max(0F, Math.min(1F, value));
    }

    /** A hue that wraps rather than sticking at the end: red is next to red. */
    private static float wrapUnit(float value) {
        float wrapped = value % 1F;
        return wrapped < 0F ? wrapped + 1F : wrapped;
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    public void render(GuiRenderer r, int mouseX, int mouseY) {
        if (!open) {
            return;
        }
        // Kept, because the arrow keys act on the part the pointer is over and the pointer arrives here:
        // see `keyPressed` for why this is the frame's own position rather than a second parameter.
        pointerX = mouseX;
        pointerY = mouseY;
        ArmatureTheme.panel(r, panel.x() - 2, panel.y() - 3, panel.width() + 4, panel.height() + 6,
                ArmatureTheme.raised(), ArmatureTheme.panelEdge());
        r.text(label, panel.x() + PAD, panel.y() + PAD, ArmatureTheme.title());

        drawSv(r);
        drawHue(r);
        drawAlpha(r);
        drawReset(r, mouseX, mouseY);
        drawSwatches(r, mouseX, mouseY);
    }

    /** The saturation/value box: one vertical line per column, then a black veil that deepens downward. */
    private void drawSv(GuiRenderer r) {
        float hue = hsvCache.hue();
        for (int x = 0; x < sv.width(); x++) {
            float saturation = sv.width() <= 1 ? 0F : x / (float) (sv.width() - 1);
            r.fill(sv.x() + x, sv.y(), sv.x() + x + 1, sv.bottom(),
                    ColourMath.fromHsv(hue, saturation, 1F, 0xFF));
        }
        // **One veil row per row of the box**, not 48 of them: the step count used to be a constant
        // unrelated to the height, so a 120-pixel box drew 2.5-pixel bands and the gradient read as a
        // ramp of stripes. The same bounded cost, one fill per row.
        for (int y = 0; y < sv.height(); y++) {
            int alpha = Math.round(y / (float) Math.max(1, sv.height() - 1) * 255F);
            r.fill(sv.x(), sv.y() + y, sv.right(), sv.y() + y + 1, alpha << 24);
        }
        // The marker: a crosshair, and two-tone on purpose. A single ring is invisible at one end of the
        // gradient or the other -- white on the white corner, black on the black one -- so it is a black
        // outline, a white inner ring and four arms, which reads on both.
        int markerX = sv.x() + Math.round(hsvCache.saturation() * (sv.width() - 1));
        int markerY = sv.y() + Math.round((1F - hsvCache.value()) * (sv.height() - 1));
        crosshair(r, markerX, markerY, 3);
    }

    /**
     * A two-tone crosshair at a point: a black ring with a white one inside it, and four short arms.
     *
     * <p>Four fills for the rings and eight for the arms, which is what makes it legible on a white
     * corner and on a black one; a single white square vanishes on one and a single black square on the
     * other, and the first version of this picker was the second of those.
     */
    private static void crosshair(GuiRenderer r, int x, int y, int arm) {
        r.fill(x - 3, y - 3, x + 4, y + 4, 0xFF000000);
        r.fill(x - 2, y - 2, x + 3, y + 3, 0xFFFFFFFF);
        r.fill(x - 2, y - 1, x + 3, y + 2, 0xFF000000);
        r.fill(x - 1, y - 2, x + 2, y + 3, 0xFFFFFFFF);
        for (int i = 1; i <= arm; i++) {
            r.fill(x - i - 1, y, x - i, y + 1, 0xFF000000);
            r.fill(x + i, y, x + i + 1, y + 1, 0xFF000000);
            r.fill(x, y - i - 1, x + 1, y - i, 0xFF000000);
            r.fill(x, y + i, x + 1, y + i + 1, 0xFF000000);
        }
    }

    private void drawHue(GuiRenderer r) {
        // One fill per pixel of width: 60 steps over 160 pixels meant the marker could point at a colour
        // that was not drawn, which is the whole of what a hue track is for.
        int steps = Math.max(1, hue.width());
        for (int i = 0; i < steps; i++) {
            r.fill(hue.x() + i, hue.y(), hue.x() + i + 1, hue.bottom(),
                    ColourMath.fromHsv(i / (float) Math.max(1, steps - 1), 1F, 1F, 0xFF));
        }
        marker(r, hue, Math.round(hsvCache.hue() * (hue.width() - 1)) + hue.x());
    }

    private void drawAlpha(GuiRenderer r) {
        int steps = Math.max(1, alpha.width());
        // The checkerboard first, anchored to the track's own origin rather than to the screen: it is the
        // track's transparency that is being shown, so it must not shift under the panel when the panel
        // moves. Four-pixel cells, the size every picker in every program uses.
        for (int x = 0; x < alpha.width(); x += 4) {
            for (int y = 0; y < alpha.height(); y += 4) {
                boolean dark = ((x / 4) + (y / 4)) % 2 == 0;
                r.fill(alpha.x() + x, alpha.y() + y, Math.min(alpha.x() + x + 4, alpha.right()),
                        Math.min(alpha.y() + y + 4, alpha.bottom()),
                        dark ? 0xFF606060 : 0xFF9A9A9A);
            }
        }
        for (int i = 0; i < steps; i++) {
            int scale = Math.round(i / (float) Math.max(1, steps - 1) * 255F);
            r.fill(alpha.x() + i, alpha.y(), alpha.x() + i + 1, alpha.bottom(),
                    (scale << 24) | (argb & 0xFFFFFF));
        }
        marker(r, alpha, Math.round(ColourMath.channel(argb, 3) / 255F * (alpha.width() - 1)) + alpha.x());
    }

    private void marker(GuiRenderer r, BookGeometry.Rect track, int x) {
        r.fill(x - 2, track.y() - 2, x + 3, track.bottom() + 2, 0xFF000000);
        r.fill(x - 1, track.y() - 1, x + 2, track.bottom() + 1, 0xFFFFFFFF);
    }

    private void drawReset(GuiRenderer r, int mouseX, int mouseY) {
        boolean hot = reset.contains(mouseX, mouseY);
        r.fill(reset.x(), reset.y(), reset.right(), reset.bottom(),
                hot ? ArmatureTheme.rowHover() : ArmatureTheme.recessed());
        String word = "Reset";
        r.text(word, reset.x() + (reset.width() - r.textWidth(word)) / 2,
                reset.y() + (reset.height() - r.lineHeight()) / 2,
                hot ? ArmatureTheme.title() : ArmatureTheme.faint());
        // What it resets *to*, on hover, beside the title: "Reset" alone is a word whose meaning has to be
        // guessed, and the answer -- the value this picker opened with -- is one line that was not written.
        if (hot) {
            String back = "back to " + (ColourMath.channel(openedWith, 3) == 0xFF
                    ? String.format("#%06X", openedWith & 0xFFFFFF)
                    : String.format("#%08X", openedWith));
            int room = panel.right() - 4 - (panel.x() + PAD + r.textWidth(label) + 8);
            r.text(dev.ellipog.armature.client.ui.kit.Measure.truncate(back, Math.max(0, room),
                            dev.ellipog.armature.client.ui.kit.Measure.monospace(6, 9)),
                    panel.x() + PAD + r.textWidth(label) + 8, panel.y() + PAD, ArmatureTheme.faint());
        }
    }

    private void drawSwatches(GuiRenderer r, int mouseX, int mouseY) {
        for (int i = 0; i < presets.size(); i++) {
            BookGeometry.Rect slot = presetRect(i);
            if (slot == null) {
                break;
            }
            boolean hot = slot.contains(mouseX, mouseY);
            r.fill(slot.x() - 1, slot.y() - 1, slot.right() + 1, slot.bottom() + 1,
                    hot ? ArmatureTheme.title() : ArmatureTheme.panelEdge());
            r.fill(slot.x(), slot.y(), slot.right(), slot.bottom(), 0xFF000000 | presets.get(i));
        }
        boolean hot = plus.contains(mouseX, mouseY);
        r.fill(plus.x() - 1, plus.y() - 1, plus.right() + 1, plus.bottom() + 1,
                hot ? ArmatureTheme.title() : ArmatureTheme.panelEdge());
        r.fill(plus.x(), plus.y(), plus.right(), plus.bottom(), ArmatureTheme.recessed());
        String glyph = "+";
        // Dimmed when the row is full, because a control that cannot do anything must not look as though
        // it can: this is the same treatment the table toolbar's Undo gets with nothing to undo.
        boolean room = presets.size() < presetSlots();
        r.text(glyph, plus.x() + (plus.width() - r.textWidth(glyph)) / 2,
                plus.y() + (plus.height() - r.lineHeight()) / 2,
                hot && room ? ArmatureTheme.title()
                        : room ? ArmatureTheme.body() : ArmatureTheme.blocked());
    }
}
