package dev.ellipog.tenet.client.render;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link GuiRenderer} that records what it was asked to draw instead of drawing it.
 *
 * <h2>Why this is the second implementation, and why that matters more than it looks</h2>
 *
 * <p>The plan's reason for scheduling the seam last was fair and worth answering directly: <i>"an
 * interface wrapping one implementation is an indirection nobody can evaluate"</i>. A seam whose only
 * implementation forwards to the thing it abstracts is a layer of indirection with nothing on the
 * other side, and nothing about it can be tested — you cannot tell a correct forwarding from a wrong
 * one without a client, which is the situation the seam was supposed to improve.
 *
 * <p>This is the answer. It is not a renderer; it is a <b>reader</b>. Every call it receives is
 * recorded as a value, so a test can ask what would have been drawn without a window, a font or an
 * item registry — and the questions that become askable are the ones that were previously only
 * answerable by looking at a screenshot:
 *
 * <ul>
 *   <li>Does a hovered node draw an outline that follows its shape rather than a box around it?</li>
 *   <li>Is a label's backdrop behind the label, and is the label inside the node it names?</li>
 *   <li>Does an outline's four fills actually enclose the rectangle they claim to?</li>
 *   <li>Is a clip opened and closed exactly once, even on the path that returns early?</li>
 * </ul>
 *
 * <p>That is what turns this from a deferral into work the tests hold. It also makes the seam
 * itself evaluable: a forwarding bug — {@code fill} passing its arguments in the wrong order, say —
 * is a failing assertion rather than a picture somebody has to notice.
 *
 * <h2>What it deliberately does not simulate</h2>
 *
 * <p>No font metrics and no item rendering. {@link #textWidth} returns a fixed width per character and
 * {@link #icon} always reports success, because a test that needs real metrics is a test that needs a
 * client and there is no point pretending otherwise. What it records is the <i>sequence and geometry
 * of calls</i>, which is the part that is about this code rather than about Minecraft's renderer.
 *
 * <p>{@link #withCharWidth} exists so a caller can reason about wrapping and truncation with
 * arithmetic it chose, the same way {@code Measure.monospace} works for the layout tests.
 */
public final class RecordingRenderer implements GuiRenderer {

    /**
     * One recorded call.
     *
     * <p>A record with an operation name rather than a class per operation: the assertions read
     * {@code fills()} and {@code texts()}, and a test that wants "was this label drawn over that
     * rectangle" compares numbers from two lists. Six types would be six visitors.
     */
    public record Call(Op op, int x, int y, int x2, int y2, int argb, String text,
                       java.util.List<GuiRenderer.StyledRun> runs, float amount) {

        /** A call with runs but no number: every op but styled text, a turn and a shadowed label. */
        public Call(Op op, int x, int y, int x2, int y2, int argb, String text) {
            this(op, x, y, x2, y2, argb, text, java.util.List.of(), 0F);
        }

        /**
         * A turn, whose pivot is the coordinates and whose angle is its own number.
         *
         * <p>The number is a field of its own rather than a rounded integer in a spare slot, for the reason the
         * armature recorder gives at length: a turn can be fractional, and rounding it would make "ninety
         * degrees" and "ninety and a half" the same recorded call — which is precisely the difference a test
         * about a rotated picture would be asserting. A shadowed label's size shares the field, because no
         * call is ever both; see {@link #shadowed}.
         */
        public static Call turn(Op op, int pivotX, int pivotY, float degrees) {
            return new Call(op, pivotX, pivotY, 0, 0, 0, "", java.util.List.of(), degrees);
        }

        /**
         * A shadowed line: the position, the colour and the text are the call, and the size is its number.
         *
         * <p>The size rides here rather than on a styled run because a shadow is a <b>baked glyph</b> — the
         * same shape in a darker sprite, drawn one pixel down and right before the line — so a bigger shadowed
         * line cannot be made by drawing a plain line twice at any colour. That is why the seam's shadowed
         * call takes a size at all, and why a test wants to see that it arrived.
         */
        public static Call shadowed(int x, int y, int argb, String text, float scale) {
            return new Call(Op.SHADOWED_TEXT, x, y, 0, 0, argb, text, java.util.List.of(), scale);
        }

        /** Whether this call is a filled rectangle covering the given point. */
        public boolean covers(int px, int py) {
            return op == Op.FILL && px >= x && px < x2 && py >= y && py < y2;
        }

        @Override
        public String toString() {
            return switch (op) {
                case FILL -> "fill(" + x + "," + y + " -> " + x2 + "," + y2 + ", " + hex(argb) + ")";
                case TEXT -> "text(\"" + text + "\" at " + x + "," + y + ", " + hex(argb) + ")";
                case SHADOWED_TEXT -> "shadowedText(\"" + text + "\" at " + x + "," + y + " x"
                        + amount + ")";
                case STYLED_TEXT -> "styledText(\"" + text + "\" in " + runs.size() + " run(s) at "
                        + x + "," + y + ")";
                case ICON -> "icon(" + x + "," + y + " " + x2 + "px)";
                case FACE -> "face(" + text + " at " + x + "," + y + " " + x2 + "px)";
                case TEXTURE -> "texture(" + text + " at " + x + "," + y + " -> " + x2 + "," + y2 + ")";
                case SPRITE -> "sprite(" + text + " at " + x + "," + y + " -> " + x2 + "," + y2 + ")";
                case GUI_SPRITE -> "guiSprite(" + text + " at " + x + "," + y + " -> " + x2 + "," + y2 + ")";
                case TURN -> "turn(about " + x + "," + y + " by " + amount + "\u00b0)";
                case UNTURN -> "unturn";
                case BLUR -> "blur(yes)";
                case CLIP -> "clip(" + x + "," + y + " -> " + x2 + "," + y2 + ")";
                case UNCLIP -> "unclip";
                case FLUSH -> "flush";
                case BATCH -> "batch";
                case END_BATCH -> "endBatch";
            };
        }

        private static String hex(int argb) {
            return String.format("#%08X", argb);
        }
    }

    /** What a recorded call was. */
    public enum Op {
        FILL, TEXT, SHADOWED_TEXT, STYLED_TEXT, ICON, FACE, TEXTURE, SPRITE, GUI_SPRITE,
        TURN, UNTURN, BLUR, CLIP, UNCLIP, FLUSH, BATCH, END_BATCH
    }

    private final List<Call> calls = new ArrayList<>();
    private final int charWidth;
    private final int lineHeight;
    private final boolean iconsDraw;

    private int openClips;
    private int deepestClip;
    private int clippedAfterStop;
    private int batches;

    /** Open turns, counted apart from clips because a leaked turn is a different fault. See {@link #turnsBalanced}. */
    private int openTurns;
    private int strayTurnPops;

    private RecordingRenderer(int charWidth, int lineHeight, boolean iconsDraw) {
        this.charWidth = charWidth;
        this.lineHeight = lineHeight;
        this.iconsDraw = iconsDraw;
    }

    /**
     * A recorder with the given metrics. Six pixels a character, ten a line, icons drawing — the same
     * stand-in the kit's own layout tests use, so arithmetic in a test is checkable by hand.
     */
    public static RecordingRenderer create() {
        return new RecordingRenderer(6, 10, true);
    }

    /** A recorder with a chosen character width, for a test that needs a label to fit or not. */
    public static RecordingRenderer withCharWidth(int charWidth) {
        return new RecordingRenderer(charWidth, 10, true);
    }

    /** A recorder whose {@link #icon} reports that it drew nothing, for the fallback path. */
    public static RecordingRenderer withoutIcons() {
        return new RecordingRenderer(6, 10, false);
    }

    // ------------------------------------------------------------------
    // GuiRenderer
    // ------------------------------------------------------------------

    @Override
    public void fill(int left, int top, int right, int bottom, int argb) {
        calls.add(new Call(Op.FILL, left, top, right, bottom, argb, ""));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Recorded with the player's id in the text field and the box in {@code argb}, so a test can ask
     * <i>whose</i> face was drawn where. The id is the only thing a caller chooses about a face, and a
     * recording that dropped it could not tell two members' rows apart.
     */
    @Override
    public boolean face(UUID player, int boxX, int boxY, int box) {
        calls.add(new Call(Op.FACE, boxX, boxY, box, box, 0, player == null ? "" : player.toString()));
        return iconsDraw;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>Recorded rather than ignored</b>, and that is the whole value of it being here. A flush is
     * where a caller declares a layering boundary — "everything up to here is behind everything after
     * it" — so a test can assert the boundary exists by finding the marker between two draws. An
     * implementation that silently did nothing would make a screen's z-order fix
     * unassertable, which is exactly the class of defect it was written to fix: an item icon landing on
     * top of a button, ordered by batching rather than by the order the code drew them in.
     */
    @Override
    public void flush() {
        calls.add(new Call(Op.FLUSH, 0, 0, 0, 0, 0, ""));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Recorded as an opening and a closing marker around whatever the supplier draws, so a test can assert
     * both halves: that a region was batched, and that its drawing happened <i>inside</i> the markers rather
     * than after them. The supplier runs for real — a recorder that skipped it would record an empty region
     * and could not answer "is this region one batch" about anything.
     */
    @Override
    public <T> T batched(java.util.function.Supplier<T> draw) {
        calls.add(new Call(Op.BATCH, 0, 0, 0, 0, 0, ""));
        batches++;
        try {
            return draw.get();
        }
        finally {
            calls.add(new Call(Op.END_BATCH, 0, 0, 0, 0, 0, ""));
        }
    }

    @Override
    public void text(String text, int x, int y, int argb) {
        calls.add(new Call(Op.TEXT, x, y, 0, 0, argb, text));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Its own op rather than a flag on {@link #text}, because the difference is the picture: a canvas label
     * that floats over a chapter has asked for the shadow, and every other label in either mod has not. A
     * recorder that folded the two could not tell a label that asked for one from a label that stopped.
     */
    @Override
    public void shadowedText(String text, int x, int y, int argb, float scale) {
        calls.add(Call.shadowed(x, y, argb, text, scale));
    }

    @Override
    public void styledText(java.util.List<StyledRun> runs, int x, int y, int argb) {
        StringBuilder whole = new StringBuilder();
        for (StyledRun run : runs) {
            whole.append(run.text());
        }
        calls.add(new Call(Op.STYLED_TEXT, x, y, 0, 0, argb, whole.toString(),
                java.util.List.copyOf(runs), 0F));
    }

    @Override
    public int styledWidth(String text, boolean bold, boolean italic, float scale) {
        if (text == null) {
            return 0;
        }
        // Bold is one pixel wider per glyph, which is what the game's font does, and a scaled run is that
        // much larger throughout -- so a fake that ignored either could not tell a correct layout from a
        // wrong one.
        return Math.round(text.length() * (charWidth + (bold ? 1 : 0)) * scale);
    }

    @Override
    public int textWidth(String text) {
        return text == null ? 0 : text.length() * charWidth;
    }

    @Override
    public int lineHeight() {
        return lineHeight;
    }

    @Override
    public boolean icon(ItemStack stack, int boxX, int boxY, int box) {
        calls.add(new Call(Op.ICON, boxX, boxY, box, box, 0, ""));
        return iconsDraw;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Recorded with the texture's path in the text field and the box in the coordinates, so a test
     * can ask <i>which</i> image was drawn where — the path is the only thing a caller chooses, and a
     * recording that dropped it could not tell the pinned star from the empty one.
     */
    @Override
    public void texture(ResourceLocation texture, int x, int y, int width, int height) {
        calls.add(new Call(Op.TEXTURE, x, y, x + width, y + height, 0, texture.toString()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Empty, because there is no resource manager behind this recorder: the game reads a PNG's header,
     * and a test that needs a size stands in for the answer itself.
     */
    @Override
    public Optional<TextureSize> textureSize(ResourceLocation texture) {
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Recorded as a texture call, because that is what it is: the destination box and the file's path
     * are the parts a caller chose, and a test asking "was the background image drawn there" reads them
     * exactly as it reads a plain {@link #texture}.
     */
    @Override
    public void scaled(ResourceLocation texture, int x, int y, int width, int height,
                       float u, float v, int sourceWidth, int sourceHeight,
                       int textureWidth, int textureHeight, int argb) {
        calls.add(new Call(Op.TEXTURE, x, y, x + width, y + height, 0, texture.toString()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Its own op, carrying the sprite's id and the tint — which is everything a caller chooses, and so
     * everything a canvas test could be wrong about. Kept apart from {@link Op#TEXTURE} because the two
     * lookups fail differently and a test that could not say which one a picture asked for could not hold
     * either behaviour: an absent file draws nothing, where an unknown sprite draws the game's own marker.
     */
    @Override
    public void sprite(ResourceLocation atlasSprite, int x, int y, int width, int height, int argb) {
        calls.add(new Call(Op.SPRITE, x, y, x + width, y + height, argb,
                atlasSprite == null ? "" : atlasSprite.toString()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Its own op beside {@link Op#SPRITE}: the GUI atlas is a different sheet resolved a
     * different way, so a test that could not tell which atlas a picture asked for could not hold
     * either lookup's behaviour. No tint to carry: a GUI sprite draws as authored.
     */
    @Override
    public void guiSprite(ResourceLocation guiSprite, int x, int y, int width, int height) {
        calls.add(new Call(Op.GUI_SPRITE, x, y, x + width, y + height, 0,
                guiSprite == null ? "" : guiSprite.toString()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Recorded, and answers like {@link #icon}: a screen that falls back to a scrim when there is no
     * blur is a path worth being able to drive, and a recorder that always said yes could not drive it.
     */
    @Override
    public boolean blur(float partialTick) {
        calls.add(new Call(Op.BLUR, 0, 0, 0, 0, 0, ""));
        return iconsDraw;
    }

    @Override
    public Scoped clip(int left, int top, int right, int bottom) {
        calls.add(new Call(Op.CLIP, left, top, right, bottom, 0, ""));
        openClips++;
        deepestClip = Math.max(deepestClip, openClips);

        // An anonymous class rather than a lambda, and the reason is a contract rather than a style:
        // the seam says closing twice is a no-op, and a lambda cannot remember that it has been
        // called. My first version returned a lambda, and the assertion below failed -- correctly,
        // because the recorder was not modelling the thing it exists to model.
        //
        // That is worth keeping as a note: a test double that does not implement the contract being
        // tested does not report a wrong contract, it reports the double. The failure pointed at the
        // recorder, which is where the bug was.
        return new Scoped() {
            private boolean closed;

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                calls.add(new Call(Op.UNCLIP, 0, 0, 0, 0, 0, ""));
                openClips--;
                if (openClips < 0) {
                    // A pop with nothing pushed. Counted rather than thrown, so a test can assert that
                    // it did not happen without the failure arriving as an exception from inside a
                    // recorder.
                    clippedAfterStop++;
                    openClips = 0;
                }
            }
        };
    }

    /**
     * {@inheritDoc}
     *
     * <p>Recorded as an opening and a closing marker around whatever the supplier draws, exactly as a clip is,
     * and for the same reason: what a test needs to ask is not "was there a turn" but "did the picture land
     * <i>inside</i> it". An element that computed its own rotated corner and drew an upright blit would pass a
     * test that only counted turns.
     *
     * <p>The same double-close guard {@link #clip} has, and here it is load-bearing rather than polite: a
     * second pop would remove a frame the caller pushed.
     */
    @Override
    public Scoped turned(int pivotX, int pivotY, float degrees) {
        calls.add(Call.turn(Op.TURN, pivotX, pivotY, degrees));
        openTurns++;
        return new Scoped() {
            private boolean closed;

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                calls.add(new Call(Op.UNTURN, 0, 0, 0, 0, 0, ""));
                openTurns--;
                if (openTurns < 0) {
                    strayTurnPops++;
                    openTurns = 0;
                }
            }
        };
    }

    // ------------------------------------------------------------------
    // Reading it back
    // ------------------------------------------------------------------

    /** Every call, in order. */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    /** The filled rectangles, in order. */
    public List<Call> fills() {
        return calls.stream().filter(call -> call.op() == Op.FILL).toList();
    }

    /** The text, in order. */
    public List<Call> texts() {
        return calls.stream().filter(call -> call.op() == Op.TEXT).toList();
    }

    /** The shadowed lines, in order. The other half of the distinction {@link Op#SHADOWED_TEXT} keeps. */
    public List<Call> shadowedTexts() {
        return calls.stream().filter(call -> call.op() == Op.SHADOWED_TEXT).toList();
    }

    /** The sprites, in order, each carrying the sprite's own id. */
    public List<Call> sprites() {
        return calls.stream().filter(call -> call.op() == Op.SPRITE).toList();
    }

    /** The vanilla GUI sprites, in order, each carrying the sprite's own id. */
    public List<Call> guiSprites() {
        return calls.stream().filter(call -> call.op() == Op.GUI_SPRITE).toList();
    }

    /** The turns, in order, each carrying its pivot and its angle. */
    public List<Call> turns() {
        return calls.stream().filter(call -> call.op() == Op.TURN).toList();
    }

    /** The styled lines, in order, with the runs each carried. */
    public List<Call> styled() {
        return calls.stream().filter(call -> call.op() == Op.STYLED_TEXT).toList();
    }

    /** The icons, in order. */
    public List<Call> icons() {
        return calls.stream().filter(call -> call.op() == Op.ICON).toList();
    }

    /** The textures, in order, each carrying its resource path. */
    public List<Call> textures() {
        return calls.stream().filter(call -> call.op() == Op.TEXTURE).toList();
    }

    /** The clips, in order. */
    public List<Call> clips() {
        return calls.stream().filter(call -> call.op() == Op.CLIP).toList();
    }

    /**
     * Where the layering boundaries were, as indices into {@link #calls()}.
     *
     * <p>Indices rather than the calls themselves, because the question a test asks is <i>relative</i>
     * order — "was the flush between the icons and the panel" — and an index is what a comparison
     * against another call's position needs. Returning the calls would make every such assertion
     * re-derive that.
     */
    public List<Integer> flushes() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).op() == Op.FLUSH) {
                out.add(i);
            }
        }
        return out;
    }

    /** The index of the first call with this op, or -1. For asserting relative order. */
    public int firstIndex(Op op) {
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).op() == op) {
                return i;
            }
        }
        return -1;
    }

    /** The index of the last call with this op, or -1. */
    public int lastIndex(Op op) {
        for (int i = calls.size() - 1; i >= 0; i--) {
            if (calls.get(i).op() == op) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether every clip was closed exactly once.
     *
     * <p>The assertion worth having on this class, and the reason a scoped clip is the seam's shape
     * rather than a push/pop pair: a leaked clip does not fail loudly, it leaves every later draw in
     * the frame clipped to a rectangle nobody chose. Reading it back off a recorder is the only way to
     * check it without a client.
     */
    public boolean clipsBalanced() {
        return openClips == 0 && clippedAfterStop == 0;
    }

    /** How many clips were still open when this recorder was last read. */
    public int unclosedClips() {
        return openClips;
    }

    /** How many pops happened with nothing pushed. */
    public int strayPops() {
        return clippedAfterStop;
    }

    /**
     * How many batched regions were opened.
     *
     * <p>The count a canvas test asserts on: "everything on the canvas is drawn in one batch" is
     * {@code batches() == 1}, and the number is also what makes a regression — a batch quietly dropped from
     * the draw path — a failing assertion rather than a frame-rate report from a player.
     */
    public int batches() {
        return batches;
    }

    /** How deep the clip nesting went, so a test can tell nesting from replacing. */
    public int deepestClip() {
        return deepestClip;
    }

    /**
     * Whether every turn was closed exactly once.
     *
     * <p>The same question {@link #clipsBalanced} asks, and a canvas test needs it because a picture element's
     * turn is the one scope on the canvas that is opened per element rather than once per frame: a leak there
     * turns every later node, label and handle in the frame, which reads as a broken canvas rather than as a
     * broken element.
     */
    public boolean turnsBalanced() {
        return openTurns == 0 && strayTurnPops == 0;
    }

    /** How many turns were still open when this recorder was last read. */
    public int unclosedTurns() {
        return openTurns;
    }

    /** Whether any text was drawn with the given string. */
    public boolean drewText(String text) {
        return calls.stream().anyMatch(call -> call.op() == Op.TEXT && text.equals(call.text()));
    }

    /** Whether a filled rectangle covers this point. */
    public boolean covered(int x, int y) {
        return calls.stream().anyMatch(call -> call.covers(x, y));
    }

    /** The text drawn with the given string, or null. The first, if it was drawn more than once. */
    public Call callFor(String text) {
        return calls.stream().filter(call -> call.op() == Op.TEXT && text.equals(call.text()))
                .findFirst().orElse(null);
    }

    /** Forgets everything recorded, so one recorder can serve several assertions. */
    public void reset() {
        calls.clear();
        openClips = 0;
        deepestClip = 0;
        clippedAfterStop = 0;
        batches = 0;
        openTurns = 0;
        strayTurnPops = 0;
    }

    @Override
    public String toString() {
        return "RecordingRenderer(" + calls.size() + " call(s))";
    }
}
