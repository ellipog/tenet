package dev.ellipog.tenet.client.dev;

import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.tenet.Constants;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.UUID;

/**
 * A renderer that counts what it forwards — the instrument the canvas's cost is read from.
 *
 * <h2>Why a counter rather than an argument</h2>
 *
 * <p>The canvas's slowness was one fact: every {@code fill} is its own GPU batch submission, and a
 * chapter of curved lines issues one fill per screen pixel of ink. That is a claim about a <b>number</b>,
 * and the honest way to check a claim about a number is to read the number — not to argue it from the
 * code and then ask a player whether the game feels better.
 *
 * <p>So this wraps the real renderer when the dev mode is on, counts the frame, and logs a line a
 * second: fills, labels, icons, textures and batched regions. Before the batching work a chapter of curves reads
 * in the tens of thousands of fills; with it, the same fills arrive in one batched region per frame,
 * which is the number that matters — the fills still exist, the <i>submissions</i> do not.
 *
 * <h2>Widths, and why they are counted</h2>
 *
 * <p>Because measuring text is the other half of what a frame costs, and the fill count cannot see it:
 * every truncated label measures its own string once per character, and a card that re-wraps its prose
 * measures every line of it again. {@code widths} is that number — a proxy, and the honest one this
 * seam can offer, because it can see the ask and not the font call behind it. It is what says whether a
 * measurement cache would have anything to hit.
 *
 * <h2>Flushes, and the submission count that used not to be visible from here</h2>
 *
 * <p>{@code flushes} counts the calls this seam makes to {@link GuiRenderer#flush} — the canvas's own
 * drain, and the blur when a modal is open. <b>That is all it counts, and the number is small: one or
 * two on an ordinary frame.</b> It is not the submission count and must not be read as one. Every
 * {@code fill}'s own flush happens inside {@code GuiGraphics}, below this interface, and so does every
 * render-type change — a label, an icon, a texture — which ends a batch just as surely.
 *
 * <p><b>{@code switches} is that submission count, and it is the reason this class exists in its current
 * shape.</b> It counts the points at which the drawing has to stop being one batch and become another:
 * a change of render type (a fill after a label, a label after an icon), a clip, a flush, the blur, and the
 * shader-colour set that tints a texture draw — the last of which was happening and going uncounted until a
 * picture element made it worth counting. That is
 * what a frame costs on the CPU side, because each of those ends a {@code endBatch} and starts a new
 * one with its own state setup — and it is the number that a batching change has to move. It counts
 * every drawing call in the frame, batched or not, which is why it is meaningful before and after: the
 * <i>fills</i> are the same either way, and the <i>boundaries</i> are not.
 *
 * <p>The one state change deliberately <b>not</b> counted is a turn, and that is a fact rather than an
 * omission: a transform is applied to the vertices as they are queued, so it ends no batch. See
 * {@link #turned}.
 *
 * <p>It undercounts two things, both deliberately: the extra passes inside {@code renderItem} (an item
 * with a glint or a translucent layer submits more than once, and that is below this interface), and
 * the second scissor change a clip makes when it closes. Both mean the real figure is a little higher
 * than this — which is the honest direction for a proxy to be wrong in, since the claim it supports is
 * that the frame submits far too many times.
 *
 * <p>It is a decorator rather than a second implementation, and it counts in the same call that draws,
 * so nothing can be drawn without being counted. Off, it costs one branch at the call site that builds
 * the renderer.
 */
public final class CountingRenderer implements GuiRenderer {

    private static long lastReportNanos;

    /**
     * The last report, as short lines for the dev HUD. Empty until a frame has been reported.
     *
     * <p>Published as one array rather than as six numbers a reader assembles, so nobody can see half
     * of one frame's totals beside half of another's. Written and read on the client thread where the
     * drawing happens; the volatile is there for the same reason every other one in this mod is — it
     * costs nothing and it stops the question being asked twice.
     */
    private static volatile String[] summary = new String[0];

    private final GuiRenderer delegate;
    private int fills;
    private int texts;
    private int widths;
    private int icons;
    private int textures;
    private int flushes;
    private int batches;
    private int switches;
    /**
     * Nanoseconds per kind, accumulated around the delegate call. Counts say <i>how many</i> draws
     * a frame issued; these say <i>how long</i> they took — and on 26.x the two disagree badly
     * enough to matter: one full-3D item icon costs what hundreds of fills do, so a frame with
     * forty icons and four thousand fills can spend its time in either place. Read with the
     * counts, never instead of them.
     *
     * <p>Two nanoTime calls per draw, ~100ns together against micro-to-milliseconds of drawing,
     * and this renderer only exists while `/tenet vitals` is up — off, the cost is the one
     * branch that builds the plain renderer instead.
     */
    private long fillNanos;
    private long textNanos;
    private long widthNanos;
    private long iconNanos;
    private long textureNanos;

    /** Nothing drawn yet, or a boundary has just ended whatever was. See {@link #drew}. */
    private static final int NONE = 0;
    private static final int FILL = 1;
    private static final int TEXT = 2;
    private static final int ICON = 3;
    private static final int TEXTURE = 4;

    /** The kind of the last thing drawn, so a change of kind can be counted as the boundary it is. */
    private int lastKind;

    /**
     * Records that something of this kind is being drawn, counting a submission when the kind changes.
     *
     * <p>A change of render type is a boundary below this interface — text is `RenderType.text` where a
     * fill is `RenderType.gui`, so one cannot continue the other's batch — and so is the first drawing
     * call of a frame, which is why {@link #NONE} is a kind like any other here.
     */
    private void drew(int kind) {
        if (lastKind != kind) {
            switches++;
            lastKind = kind;
        }
    }

    /**
     * Records a boundary that is not itself a drawing call: a clip, a flush, or the blur.
     *
     * <p>Each of those ends whatever batch was open, so the next thing drawn starts a new submission.
     * Setting the kind back to {@link #NONE} is what makes that next call count.
     */
    private void boundary() {
        if (lastKind != NONE) {
            switches++;
            lastKind = NONE;
        }
    }

    public CountingRenderer(GuiRenderer delegate) {
        this.delegate = delegate;
    }

    /**
     * The frame is over: log this frame's totals, at most once a second.
     *
     * <p>Called by the screen after its last draw rather than from a constructor, because a frame has an
     * end and only the screen knows where it is — a renderer built at the top of the next frame would be
     * reporting a frame it never saw finish.
     */
    public static void endFrame(GuiRenderer renderer) {
        if (renderer instanceof CountingRenderer counting) {
            counting.report();
        }
    }

    private void report() {
        long now = System.nanoTime();
        if (now - lastReportNanos < 1_000_000_000L) {
            return;
        }
        lastReportNanos = now;
        // The line layer's own number, and the one that says whether a still canvas is still paying for
        // its edges: a route that has not moved is remembered, so this is near zero while the canvas is
        // being read and spikes only while it is being panned, zoomed or edited.
        // (An icon split used to be reported here -- flat versus live icon paths, drained from
        // Armature's `IconPlan`. That experiment went away with the 1.21.1 model pipeline it measured,
        // so there is nothing left to count on this line.)
        long walkPoints = LineArt.drainWalked();
        // The line keeps the shape TESTING.md greps for -- `book frame -- N fill(s)` -- so everything
        // newer than that is appended rather than inserted among the older counts.
        Constants.LOG.info("book frame -- {} fill(s), {} text(s), {} icon(s), {} texture(s),"
                        + " {} batched region(s), {} width(s), {} flush(es),"
                        + " {} submission(s), {} line walk point(s)"
                        + " -- times icon {} ms, fill {} ms, text {} ms, texture {} ms, width {} ms",
                fills, texts, icons, textures, batches, widths, flushes,
                switches, walkPoints,
                ms(iconNanos), ms(fillNanos), ms(textNanos), ms(textureNanos), ms(widthNanos));
        summary = new String[] {
                "fills " + fills + "  " + ms(fillNanos) + " ms",
                "texts " + texts + "  " + ms(textNanos) + " ms",
                "widths " + widths + "  " + ms(widthNanos) + " ms",
                "icons " + icons + "  " + ms(iconNanos) + " ms",
                "flushes " + flushes,
                "batches " + batches,
                "submits " + switches,
                "linewalk " + walkPoints,
                // What was *there*, which no count above says. A reading of `fills 4034` cannot be compared
                // with another until the node count is known, and the baseline table exists to compare
                // readings. `visible` is the cull's own answer and `named` the label pass's input, so the
                // three together say what the frame was actually drawing through.
                "nodes " + dev.ellipog.tenet.client.dev.CanvasStats.nodes()
                        + "  visible " + dev.ellipog.tenet.client.dev.CanvasStats.visible()
                        + "  lines " + dev.ellipog.tenet.client.dev.CanvasStats.edges()
                        + "  named " + dev.ellipog.tenet.client.dev.CanvasStats.named(),
                // Cumulative, and the one number that shows a drag re-stamping the canvas every frame: the
                // frame counters look much the same whether it was built once or sixty times.
                "rebuilds " + dev.ellipog.tenet.client.dev.CanvasStats.rebuilds()};
        reportCacheHits();
    }

    /**
     * The toolkit's cache ratios, as their own line rather than appended to the frame's.
     *
     * <h2>Why a second line, when every other counter was appended</h2>
     *
     * <p>Because the frame line's shape is a contract — {@code TESTING.md} greps for
     * {@code book frame -- N fill(s)} — and because these are not frame counters. They are <b>ratios</b>,
     * and a ratio answers a different question from a count: `fills 119` says what the frame drew, while
     * `plans 1940/60 (97%)` says whether the table that frame drew <i>through</i> is being consulted or
     * merely built. Appending a ratio to a list of counts invites it to be read as one.
     *
     * <p>And it is the number that would have caught the fault this project already had once: a plan table
     * keyed on a shape's identity, with {@code inner()} returning a fresh object per call, so every entry
     * was built and then unreachable. Every count on the frame line was <i>correct</i> while that was true,
     * and so were the pixels. A ratio near zero is the only thing that says it.
     *
     * <p>Logged only when something was asked: a client whose screen draws no shapes in a second would
     * otherwise get a line saying nothing, which reads as a measurement of zero rather than as an absence.
     */
    private static void reportCacheHits() {
        java.util.Map<String, int[]> ratios = dev.ellipog.armature.client.ui.CacheHits.drain();
        String line = dev.ellipog.armature.client.ui.CacheHits.describe(ratios);
        if (!line.isEmpty()) {
            Constants.LOG.info("cache hits -- {}", line);
        }
    }

    /** Milliseconds with one decimal, for the overlay and the log. Never negative. */
    private static String ms(long nanos) {
        return Double.toString(Math.max(0L, nanos + 50_000L) / 100_000L / 10.0);
    }

    /** The last reported frame, as short lines for the dev HUD. Empty before the first report. */
    public static String[] summary() {
        return summary;
    }

    /**
     * How many submissions this frame's drawing needs, as counted so far.
     *
     * <p>Exposed because the rules that produce it are arithmetic with three ways to be wrong — the first
     * call of a frame, a change of kind, and a boundary that is not a drawing call — and a report that can
     * only be read once a second cannot be asserted in a test. See {@link #drew} and {@link #boundary}.
     */
    public int submissions() {
        return switches;
    }

    @Override
    public void fill(int left, int top, int right, int bottom, int argb) {
        fills++;
        drew(FILL);
        long start = System.nanoTime();
        delegate.fill(left, top, right, bottom, argb);
        fillNanos += System.nanoTime() - start;
    }

    @Override
    public void text(String text, int x, int y, int argb) {
        texts++;
        drew(TEXT);
        long start = System.nanoTime();
        delegate.text(text, x, y, argb);
        textNanos += System.nanoTime() - start;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Counted with {@link #text}, because it is a label and costs what a label costs — the shadow is one
     * more glyph per character inside the font, below this interface, and the kind of drawing is the same
     * {@code RenderType.text} a plain line uses. A separate counter here would report two numbers that no
     * batching decision can act on differently.
     */
    @Override
    public void shadowedText(String text, int x, int y, int argb, float scale) {
        texts++;
        drew(TEXT);
        long start = System.nanoTime();
        delegate.shadowedText(text, x, y, argb, scale);
        textNanos += System.nanoTime() - start;
    }

    @Override
    public void styledText(java.util.List<StyledRun> runs, int x, int y, int argb) {
        texts++;
        drew(TEXT);
        long start = System.nanoTime();
        delegate.styledText(runs, x, y, argb);
        textNanos += System.nanoTime() - start;
    }

    @Override
    public int styledWidth(String text, boolean bold, boolean italic, float scale) {
        widths++;
        long start = System.nanoTime();
        try {
            return delegate.styledWidth(text, bold, italic, scale);
        }
        finally {
            widthNanos += System.nanoTime() - start;
        }
    }

    @Override
    public int textWidth(String text) {
        widths++;
        long start = System.nanoTime();
        try {
            return delegate.textWidth(text);
        }
        finally {
            widthNanos += System.nanoTime() - start;
        }
    }

    @Override
    public int lineHeight() {
        return delegate.lineHeight();
    }

    @Override
    public boolean icon(ItemStack stack, int boxX, int boxY, int box) {
        icons++;
        drew(ICON);
        long start = System.nanoTime();
        try {
            return delegate.icon(stack, boxX, boxY, box);
        }
        finally {
            iconNanos += System.nanoTime() - start;
        }
    }

    @Override
    public boolean face(UUID player, int boxX, int boxY, int box) {
        icons++;
        drew(ICON);
        long start = System.nanoTime();
        try {
            return delegate.face(player, boxX, boxY, box);
        }
        finally {
            iconNanos += System.nanoTime() - start;
        }
    }

    @Override
    public void texture(Identifier texture, int x, int y, int width, int height) {
        textures++;
        drew(TEXTURE);
        long start = System.nanoTime();
        delegate.texture(texture, x, y, width, height);
        textureNanos += System.nanoTime() - start;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Counted and bounded exactly as {@link #scaled} is, because the two share the one mechanism that
     * makes them interesting to this counter: both set a shader colour before the blit, and setting it flushes
     * a managed batch. A sprite is drawn through the block atlas' own render type besides, which is a second
     * real boundary the kinds here cannot express — the same coarseness that lumps an icon in with a texture.
     */
    @Override
    public void sprite(Identifier atlasSprite, int x, int y, int width, int height, int argb) {
        boundary();
        textures++;
        drew(TEXTURE);
        long start = System.nanoTime();
        delegate.sprite(atlasSprite, x, y, width, height, argb);
        textureNanos += System.nanoTime() - start;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Counted with {@link #texture} rather than with {@link #sprite}: a GUI sprite is drawn as
     * authored with no tint, so unlike a scaled blit or an atlas sprite it sets no shader colour
     * and forces no batch boundary of its own. The kind change still counts the render-type switch
     * when the last thing drawn was something else.
     */
    @Override
    public void guiSprite(Identifier guiSprite, int x, int y, int width, int height) {
        textures++;
        drew(TEXTURE);
        long start = System.nanoTime();
        delegate.guiSprite(guiSprite, x, y, width, height);
        textureNanos += System.nanoTime() - start;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Forwarded because the delegate is the only thing here that can read a file's header — the counter
     * exists to watch a frame, not to answer for the textures in it — and a missing delegate has no answer,
     * which is the empty the seam promises for an asset it cannot read.
     */
    @Override
    public Optional<TextureSize> textureSize(Identifier texture) {
        return delegate == null ? Optional.empty() : delegate.textureSize(texture);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Counted with {@link #texture}, because a scaled blit is a texture draw like any other and the
     * image canvas background is exactly what this counter was extended for. See {@link #textureSize}.
     *
     * <p>{@link #boundary} first, and it is a correction rather than a new opinion: this method tints its
     * blit, tinting sets a shader colour, and setting a shader colour <b>flushes a managed batch</b> — so it
     * was already a boundary below this interface and was simply not being counted as one. Written down here
     * because the omission is invisible: the number was a little low for every frame that drew a texture, in
     * the direction the class note says it is fine to be wrong in, which is exactly why nobody would notice.
     */
    @Override
    public void scaled(Identifier texture, int x, int y, int width, int height,
                       float u, float v, int sourceWidth, int sourceHeight,
                       int textureWidth, int textureHeight, int argb) {
        boundary();
        textures++;
        drew(TEXTURE);
        long start = System.nanoTime();
        if (delegate != null) {
            delegate.scaled(texture, x, y, width, height, u, v, sourceWidth, sourceHeight,
                    textureWidth, textureHeight, argb);
        }
        textureNanos += System.nanoTime() - start;
    }

    @Override
    public boolean blur(float partialTick) {
        // A post-process, so a boundary and not a drawing call: it drains everything drawn so far and
        // leaves the pipeline restored, which means whatever is drawn next is a new submission.
        boundary();
        return delegate.blur(partialTick);
    }

    @Override
    public void flush() {
        flushes++;
        boundary();
        delegate.flush();
    }

    @Override
    public <T> T batched(java.util.function.Supplier<T> draw) {
        batches++;
        return delegate.batched(draw);
    }

    @Override
    public Scoped clip(int left, int top, int right, int bottom) {
        // A scissor change ends the batch whether the context is managed or not -- `applyScissor` calls
        // `flushIfManaged` -- so it is a boundary here for the same reason a render-type change is. The
        // `close` is not counted: it is the same change in the other direction, and one clip is one
        // interruption.
        boundary();
        return delegate.clip(left, top, right, bottom);
    }

    /**
     * {@inheritDoc}
     *
     * <h2>Why a turn is <b>not</b> a boundary, which is the opposite of what it looks like</h2>
     *
     * <p>Every other state change on this seam ends a batch and is counted as one. A turn does not, and the
     * reason is worth the paragraph because the obvious reading is wrong: the transform is applied to the
     * <b>vertices</b>, as they are queued, rather than to the batch as it is submitted. So a turn changes what
     * the next vertices say and nothing at all about the submission the previous ones are already in — which is
     * also why a tint change <i>inside</i> a turn is safe, and why counting this as a boundary would inflate
     * the one number this class exists to report honestly.
     */
    @Override
    public Scoped turned(int pivotX, int pivotY, float degrees) {
        return delegate.turned(pivotX, pivotY, degrees);
    }
}
