package dev.ellipog.tenet.client;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.armature.client.Look;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.ui.art.CanvasBackgroundArt;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Hover;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Motion;
import dev.ellipog.armature.client.ui.kit.ScrollView;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tenet.client.viewer.RecipeLookups;
import dev.ellipog.tenet.quest.CanvasElement;
import dev.ellipog.tenet.quest.ClickAction;
import dev.ellipog.tenet.quest.QuestLink;
import dev.ellipog.tenet.client.dev.CanvasReveal;
import dev.ellipog.tenet.client.dev.LineArt;
import dev.ellipog.tenet.client.dev.MenuFlyoutArt;
import dev.ellipog.tenet.quest.DependencyStyle;
import dev.ellipog.tenet.quest.QuestShape;
import dev.ellipog.tenet.client.dev.ToolsLayout;
import dev.ellipog.tenet.editor.EditorSession;
import dev.ellipog.tenet.progress.QuestState;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The quest book's canvas: where a point on screen lands on the chapter and back, and the drawing
 * of the canvas itself, its nodes, lines, labels and elements.
 *
 * <p>Moved out of {@link QuestBookScreen} as it stood, with the state only it reads; the screen
 * keeps the state it shares and reaches this through its {@code bookCanvas} field.
 */
final class BookCanvas {

    private final QuestBookScreen book;

    BookCanvas(QuestBookScreen book) {
        this.book = book;
    }

    /**
     * Node labels, and the room they are allowed to take.
     *
     * <h2>The garbled-text bug this replaces</h2>
     *
     * <p>Node titles were truncated to {@code (int) (size / zoom / 5)} <b>characters</b> — a number with
     * no relationship to the font, to the title, or to the space between nodes. And the space between
     * nodes was never consulted at all, so with a questline authored 64px apart, three labels of about
     * 90px were drawn centred on nodes 64px apart and ran straight into each other. In the screenshot
     * that read as <em>"Punch a SomewherStone To…"</em>, which is three quest titles interleaved and
     * looks like a corrupt string rather than like three labels that are too wide.
     *
     * <p>So the room is <b>measured</b> — each node's own nearest columns, in screen pixels — and every
     * label is truncated to it by <b>width</b> ({@code Font.plainSubstrByWidth}), not by character
     * count. A label with not enough room of its own is not drawn at all and the hover caption
     * carries the name, while its neighbours keep theirs. One measured rule per node, instead of a
     * zoom threshold that was a guess.
     */
    // Aliases again, for the same reason as the layout constants above: the number lives in
    // BookGeometry, where BookGeometryTest can assert the invariant that depends on it -- that a
    // label's half-width is always strictly less than its nearest gap.
    private static final int MAX_LABEL_WIDTH = BookGeometry.MAX_LABEL_WIDTH;

    /** Below this there is no room for a readable label, so none are drawn. */
    private static final int MIN_LABEL_WIDTH = BookGeometry.MIN_LABEL_WIDTH;

    /** The gap kept between a label and the node beside it. */
    private static final int LABEL_GAP = BookGeometry.LABEL_GAP;

    // --- view state, kept between openings -----------------------------------

    /**
     * How hovered each node looks, and each row of the overlay's body.
     *
     * <h2>Two trackers rather than one, because two things are never hovered at once</h2>
     *
     * <p>A pointer is either over the canvas or over the overlay — the overlay covers the canvas
     * entirely — so one tracker would work. Two are used anyway because the keys would otherwise share
     * a namespace: a quest id and a row key like {@code "task:0"} can never collide today, and nothing
     * would notice if a future key scheme made them collide, because a hover that lit the wrong thing
     * for one frame is not a thing anyone reports.
     *
     * <p>Instances rather than static, unlike the selection above: a hover is <b>per screen</b>, and a
     * screen that reopened with the last one's hovered row still lit would point at whatever now sits
     * at that index. {@code clear()} is called when the overlay opens and closes for the same reason.
     */
    private final Hover nodeHover = new Hover();
    /** The dependency line under the pointer, keyed by its two ids: the brightening before a right-click. */
    private final Hover edgeHover = new Hover();

    /**
     * The quest whose lines glow steadily: the one being read, else a lone selection.
     *
     * <p>Its own tracker rather than a second key in {@link #nodeHover}, because a pointer glow and
     * a reading glow overlap -- the quest under the pointer is often the quest being read -- and one
     * tracker holds one thing. It eases like every hover, so opening a quest fades its lines up and
     * closing one fades them down instead of snapping.
     */
    private final Hover selectionGlow = new Hover();

    /**
     * The last line-hover answer, and the pointer/view state it was computed under.
     *
     * <p>The nearest-line walk reads every point of every route, and it used to run every frame in edit
     * mode. A still pointer over a still canvas cannot change the answer, so it is kept until the
     * pointer, the view or the chapter's geometry moves -- see {@link #lineHover}.
     */
    private double lastLineHoverX = Double.NaN;
    private double lastLineHoverY = Double.NaN;
    private float lastLineHoverScale = -1F;
    private int lastLineHoverPanX;
    private int lastLineHoverPanY;
    private int lastLineHoverEdges = -1;
    private long lastLineHoverRevision = -1L;

    /**
     * The two revisions the routes also depend on, and their absence was a stale answer rather than a
     * missed saving.
     *
     * <p>A line's geometry is a function of its endpoints, and those move for reasons the tree revision
     * does not record: a **draft** write — a stepper press, a size nudge, a scrub's release — changes a
     * node's position or its size with the tree untouched, and {@code EditorSession}'s pending moves change
     * one without moving either. So with only the revision and the edge *count* in the key, a nudge that
     * moved a node left the hover answering for the route the line used to be: the pointer sits still, the
     * count is the same, and the highlight stays on a line that is no longer under it.
     *
     * <p>The count was never enough on its own for the same reason: it catches a line being added or
     * removed, and says nothing about one having moved. {@code CanvasState} already carries both of these
     * for the edges themselves — this is the same key, one layer up.
     */
    private long lastLineHoverDraft = Long.MIN_VALUE;
    private long lastLineHoverEditors = Long.MIN_VALUE;
    private boolean lastLineHoverMoving;
    private String[] lastLineHover;

    /**
     * The tree revision the selection was last checked against; see {@link #pruneSelection}.
     *
     * <p>Static with the selection it guards, because the selection outlives the screen and so must the
     * memory of which tree it was checked against -- an instance field would re-prune (harmlessly) on
     * every reopen and, worse, would not notice a reload that happened while the book was closed.
     */
    private static long selectionRevision = -1L;

    // ------------------------------------------------------------------
    // Canvas coordinates
    // ------------------------------------------------------------------

    /**
     * A click that opens a card, without discarding the set the node is already part of.
     *
     * <p>A plain click normally means "this node and nothing else" -- clearing the multi-selection is
     * what says so. The exception is a click on a node that is <b>already selected</b>: the author has
     * collected a set with ctrl-click or the marquee, is clicking one of its members to edit it, and
     * clearing would throw away the very set they are about to say "Add selected" over. So the clicked
     * node becomes the primary and the rest of the set stays.
     *
     * <p>The old behaviour is kept for everything else, including a second click on a lone primary,
     * which still deselects: a set of one is not a set, and "click it again to let go" is a gesture
     * people already have.
     */
    static void selectForCard(String id) {
        if (QuestBookScreen.multiSelection.contains(id)) {
            QuestBookScreen.multiSelection.remove(id);
            QuestBookScreen.selectedQuest = id;
            return;
        }
        if (id.equals(QuestBookScreen.selectedQuest)) {
            if (QuestBookScreen.multiSelection.isEmpty()) {
                QuestBookScreen.selectedQuest = null;
            }
            return;
        }
        QuestBookScreen.selectedQuest = id;
        QuestBookScreen.multiSelection.clear();
    }

    /**
     * Drops selected ids the tree no longer holds.
     *
     * <p>The selection is static, and a tree arrives after every edit -- including the edit that deletes
     * a selected quest, an undo of its creation, or a file hand-edited and reloaded. An id the cache no
     * longer knows would otherwise stay in the set and be acted on by the next gesture: the same failure
     * as a selection carried across a chapter switch, arriving by a different road. So the revision that
     * expires the editor's remembered positions expires the selection too -- once per tree, by a guard
     * that lives with the selection it protects, and nothing that still exists is touched.
     */
    static void pruneSelection(long revision) {
        if (revision == selectionRevision) {
            return;
        }
        selectionRevision = revision;
        Set<String> live = new java.util.HashSet<>();
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            live.add(entry.id());
        }
        QuestBookScreen.multiSelection.removeIf(id -> !live.contains(id));
        if (QuestBookScreen.selectedQuest != null && !live.contains(QuestBookScreen.selectedQuest)) {
            QuestBookScreen.selectedQuest = null;
        }
    }

    /** Re-reads {@link QuestBookScreen#editingView}. See its note for who calls it and why. */
    void refreshEditingView() {
        if (!book.mayEdit()) {
            book.previewAsPlayer = false;
            book.previewFromCommand = false;
        }
        QuestBookScreen.editingView = book.mayEditNow();
    }

    /**
     * Centres the content the first time a chapter is shown, and on a reset.
     *
     * <p>Computed from the content's own bounding box rather than from zero, so a questline authored
     * at negative coordinates — which the editor will produce, since it grows in every direction —
     * still lands in the middle of the canvas instead of off the edge.
     */
    void centreCanvas() {
        String chapter = QuestBookScreen.effectiveChapter();
        if (chapter == null) {
            return;
        }
        if (QuestBookScreen.centred && chapter.equals(QuestBookScreen.pannedChapter)) {
            return;
        }

        List<ClientQuestCache.Entry> quests = book.questsIn(chapter);
        // A chapter that names a quest centres on it rather than on the whole canvas — the legacy
        // `autofocus_id`. Resolved by id or alias within this chapter; a name that resolves nowhere
        // (or elsewhere, which the loader already refused) falls through to the bounding box, which
        // is the honest fallback: the chapter still lands in the middle rather than off the edge.
        for (ClientQuestCache.ChapterEntry candidate : ClientQuestCache.chapters()) {
            if (candidate.id().equals(chapter) && candidate.hasAutofocus()) {
                for (ClientQuestCache.Entry entry : quests) {
                    if (entry.matches(candidate.autofocus())) {
                        book.viewport().centreOn(entry.x(), entry.y(),
                                entry.x() + entry.size(), entry.y() + entry.size());
                        QuestBookScreen.pannedChapter = chapter;
                        QuestBookScreen.centred = true;
                        return;
                    }
                }
                break;
            }
        }
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (ClientQuestCache.Entry entry : quests) {
            minX = Math.min(minX, entry.x());
            maxX = Math.max(maxX, entry.x() + 48);
            minY = Math.min(minY, entry.y());
            maxY = Math.max(maxY, entry.y() + 48);
        }
        if (quests.isEmpty()) {
            minX = maxX = minY = maxY = 0;
        }

        // Centred in the view port rather than in the canvas: the canvas minus the floating strip. The
        // strip covers nothing, at the cost of a chapter looking slightly high when nothing is selected
        // -- which is the right trade against the alternative, where the bottom row of a chapter slides
        // under the bar the instant a player clicks a node, because clicking is what makes it appear.
        // Centred in the canvas, which is the whole area below the header. There used to be a summary
        // strip floating over its bottom, and content was centred in the canvas minus that strip so the
        // bar could not cover the last row of a chapter -- but the strip is gone (clicking a node opens
        // the quest), so there is nothing on the canvas for content to be kept clear of.
        // Given the bounding box rather than a size and an origin, which is what makes the
        // negative-coordinate case above fall out instead of needing the handling it used to get. No
        // clamp is applied, deliberately: this is the call that defines where a canvas starts, so
        // applying one would move content the caller had just positioned.
        book.viewport().centreOn(minX, minY, maxX, maxY);
        QuestBookScreen.pannedChapter = chapter;
        QuestBookScreen.centred = true;
    }

    /**
     * Applies a new zoom while keeping the world point under {@code (mouseX, mouseY)} fixed.
     *
     * <p>The arithmetic is the kit's, and it is asserted there rather than here: the content point
     * under the pointer is the same point after the zoom, the limits clamp, and a zoom already at a
     * limit does not drift. This method exists only to name the canvas for it — which is the whole
     * argument for the transform living in {@link Viewport}, because in a {@code Screen} that
     * calculation could only ever be judged by eye.
     */
    void zoomAt(double mouseX, double mouseY, float factor) {
        // A zoom is the hand taking the camera: a glide still in flight must not fight it.
        book.glideQuest = null;
        zoomGlideStart = -1L;
        book.viewport().zoomAt(mouseX, mouseY, factor);
    }

    /**
     * Zooms about the view port's centre, for the buttons, which have no pointer position.
     *
     * <p>Gliding rather than jumping: the wheel above stays direct — the hand is the animation — but a
     * button is a 25% step with no gesture behind it, and a step that size reads as a cut. Retargeted
     * from wherever the scale stands, so hammering the button walks up smoothly instead of queueing
     * jumps; the hand cancels it like any other glide, and the locate glide goes with it, because two
     * cameras moving at once is not a thing the view can do.
     */
    void zoomCentre(float factor) {
        book.glideQuest = null;
        zoomGlideFrom = book.viewport().scale();
        zoomGlideTo = zoomGlideFrom * factor;
        zoomGlideStart = net.minecraft.util.Util.getMillis();
    }

    /** How long a zoom button's glide takes: felt as a settle rather than watched as a move. */
    private static final long ZOOM_GLIDE_MILLIS = 140L;

    /** A zoom glide in flight: where it started, where it is headed, and when — or -1 for none. */
    private float zoomGlideFrom;
    private float zoomGlideTo;
    long zoomGlideStart = -1L;

    /**
     * Advances the zoom buttons' glide, if one is in flight.
     *
     * <p>Beside {@link #advanceGlide} and on its terms: stepped through {@code zoomAboutCentre} because
     * the limits live in the viewport and the button has no right to jump past them — each frame asks
     * for the eased scale and the viewport clamps. A frame that moved nothing hit a limit, so the glide
     * ends there rather than pushing against it for the rest of its time; motion off lands at once,
     * because the eased answer is the target on the first frame.
     */
    private void advanceZoomGlide(long now) {
        if (zoomGlideStart < 0) {
            return;
        }
        long elapsed = now - zoomGlideStart;
        float current = book.viewport().scale();
        if (elapsed >= ZOOM_GLIDE_MILLIS) {
            if (zoomGlideTo != current) {
                book.viewport().zoomAboutCentre(zoomGlideTo / current);
            }
            zoomGlideStart = -1L;
            return;
        }
        float t = Math.max(0F, Math.min(1F, (float) elapsed / ZOOM_GLIDE_MILLIS));
        float eased = Motion.defaultEasing().between(zoomGlideFrom, zoomGlideTo, t);
        if (eased != current && CanvasDetail.of(eased) != CanvasDetail.of(current)
                && !book.viewport().zoomAboutCentre(eased / current)) {
            zoomGlideStart = -1L;
        }
    }

    /** The shape the canvas should draw, with a pending settings-page change winning over the tree. */
    private static QuestShape drawnShape(ClientQuestCache.Entry entry) {
        String name = QuestBookScreen.fieldDraft.text(entry.chapterId(), entry.id(), "shape", "");
        if (!name.isEmpty()) {
            for (QuestShape shape : QuestShape.values()) {
                if (shape.name().equalsIgnoreCase(name)) {
                    return shape;
                }
            }
        }
        return entry.shape();
    }

    /**
     * The grips a selected element wears: a small square per handle, and a stem up to the rotate grip.
     *
     * <h2>Why the drawn grip is smaller than the grab</h2>
     *
     * <p>The rule every control in this screen follows: the ink says where the control is and the grab is
     * generous around it, so a press a pixel or two off still lands. A grip drawn at the grab's own size would
     * be a five-pixel-square dot scaled up to sixteen, sitting over the very edge an author is trying to see.
     *
     * <p>The stem is drawn only for a picture, because only a picture has a rotate grip to reach — a square
     * floating above a box would read as a stray mark rather than as a control, which is the same reason every
     * editor draws the line.
     */
    private void drawElementGrips(GuiRenderer r, QuestBookScreen.ElementSlot slot) {
        int ink = ArmatureTheme.selectedRing();
        int half = CanvasElementArt.GRIP_SIZE / 2;
        CanvasElement element = slot.element();
        // **The frame turns with the picture.** The grips are kept in the picture's own frame — see
        // `CanvasElementArt.handles` — so the whole frame is drawn inside one turn about the same pivot the
        // picture itself is drawn about, and a rotated picture wears a rotated frame. Without this the grips
        // sat square to the screen around a turned picture, which is a frame that does not belong to what is
        // inside it.
        GuiRenderer.Scoped turn = CanvasElementArt.degreesOf(element) == 0 ? null
                : r.turned(CanvasElementArt.pivotX(element, slot.box()),
                        CanvasElementArt.pivotY(element, slot.box()),
                        CanvasElementArt.degreesOf(element));
        try {
            for (CanvasElementArt.Grip grip : CanvasElementArt.handles(element, slot.box())) {
                r.fill(grip.x() - half, grip.y() - half, grip.x() + half + 1, grip.y() + half + 1, ink);
            }
            if (element instanceof CanvasElement.Image) {
                int x = slot.box().left() + slot.box().width() / 2;
                r.fill(x, slot.box().top() - CanvasElementArt.ROTATE_OFFSET, x + 1, slot.box().top(), ink);
            }
        }
        finally {
            if (turn != null) {
                turn.close();
            }
        }
    }

    /**
     * Advances the camera's glide, if one is in flight.
     *
     * <p>Called once a frame from the canvas drawing, which is the only place the move can be seen: the
     * canvas is not drawn while a card is up, so a glide cannot run on behind one.
     */
    private void advanceGlide(long now) {
        if (book.glideQuest == null) {
            return;
        }
        ClientQuestCache.Entry target = book.entryFor(book.glideQuest);
        if (target == null || !target.chapterId().equals(QuestBookScreen.effectiveChapter())) {
            // The chapter changed under the glide (a sidebar press mid-flight): the view belongs to the
            // chapter now, and dragging it to a node that is not on screen is the wrong kind of help.
            book.glideQuest = null;
            return;
        }
        long elapsed = now - book.glideStart;
        Viewport view = book.viewport();
        if (elapsed >= CanvasReveal.GLIDE_MILLIS) {
            view.setOffset(book.glideToX, book.glideToY);
            book.glideQuest = null;
            return;
        }
        view.setOffset(CanvasReveal.glide(book.glideFromX, book.glideToX, elapsed, CanvasReveal.GLIDE_MILLIS),
                CanvasReveal.glide(book.glideFromY, book.glideToY, elapsed, CanvasReveal.GLIDE_MILLIS));
    }

    // ------------------------------------------------------------------
    // The canvas
    // ------------------------------------------------------------------

    /**
     * What the canvas asks to have captioned: the hovered quest, the hovered link's slot, and the
     * hovered dependency line's caption, or nulls when the pointer names none of them.
     *
     * <p>Returned rather than drawn because captions belong over everything, at {@code TOOLTIP_Z}
     * in {@code render}: a caption drawn here, at canvas Z, loses the depth test wherever a
     * depth-writing picture sits — which is how node textures ended up covering the title they
     * name. The values are this frame's, so there is no stale caption when the canvas is hidden.
     */
    record CanvasCaption(ClientQuestCache.Entry quest, LinkSlot link, String line) {
    }

    CanvasCaption drawCanvas(GuiRenderer r, int mouseX, int mouseY,
                                     List<ClientQuestCache.Entry> quests, long now) {
        ClientQuestCache.Entry hovered;

        // Clipped to the canvas, so a node panned past the edge is cut off at the edge rather than
        // drawn over the sidebar. A scoped clip rather than a raw enable/disable pair: the pop is
        // placed by the compiler on every exit path, so the two calls cannot come apart -- and a
        // missing pop does not fail loudly, it leaves every later draw in the frame clipped to a
        // rectangle nobody chose.
        //
        // The scope is the seam's own type, so this file no longer knows what a scissor is. What that
        // bought beyond the port: `RecordingRenderer` can now assert that the clip was opened and
        // closed exactly once, which a screen cannot be asked without one.
        // Two scopes, and they are different kinds of thing. The clip is where the drawing is allowed
        // to land; the theme is what it is drawn in. Both are closed by the compiler on every exit path,
        // which is the property that matters for each — a clip left open cuts the rest of the frame off,
        // and a theme left open paints every later screen in one chapter's colours.
        //
        // The chapter's palette covers the canvas and everything on it: the backdrop, the connector
        // lines, the nodes, their rings, their washes. The sidebar beside it is chrome and keeps the
        // player's own theme — that is what lets a themed canvas and an ordinary sidebar be visible in
        // the same frame with no precedence rule between them.
        //
        // The node caption is deliberately outside all of this (see CanvasCaption): it is a label
        // floating over the canvas in the same family as a tooltip, and a caption that changed
        // colour with the chapter would read as part of the node it names rather than as a label
        // about it.
        try (GuiRenderer.Scoped clip = r.clip(book.canvasLeft(), book.canvasTop(), book.canvasRight(), book.canvasBottom());
             ArmatureTheme.Scope theme = ArmatureTheme.scope(QuestBookScreen.viewportTheme())) {
            // Everything on the canvas in **one batch**, and the placement is the whole correctness
            // argument: the clip is open, the batch flushes before the clip closes, so every queued fill
            // is submitted under the canvas's own scissor. See GuiRenderer#batched.
            //
            // Without it this is one GPU batch submission per fill -- the context a screen draws into is
            // unmanaged, so every fill ends in its own flush -- and a chapter of curved lines is one fill
            // per screen pixel of ink. The count is what the player feels as zoom lag: the same chapter
            // issues tens of thousands of submissions at 2.2x and a handful with this wrapper.
            hovered = r.batched(() -> drawCanvasContents(r, mouseX, mouseY, quests, now));
        }

        // No caption here: see CanvasCaption. The hovered link's slot is resolved now, while the
        // frame that placed it is current, so the render pass has nothing to look up.
        LinkSlot link = hoveredQuestLink == null ? null : linkSlot(hoveredQuestLink);
        return new CanvasCaption(hovered, link, hoveredEdgeCaption);
    }

    /** The canvas's own drawing, inside the clip. Returns the hovered node, for the caption above. */
    private ClientQuestCache.Entry drawCanvasContents(GuiRenderer r, int mouseX, int mouseY,
                                                      List<ClientQuestCache.Entry> quests, long now) {
        // The locate glide first, before anything reads a screen position: a frame is the unit the
        // camera moves in, and this is the only drawing the move can be seen in -- the canvas is not
        // drawn while a card is up.
        advanceGlide(now);
        // And the zoom buttons' glide beside it: same frame, same camera, no fighting.
        advanceZoomGlide(now);
        // The frame's canvas — its edges and its on-canvas nodes — rebuilt only if something it is drawn
        // from has moved. Stamped here rather than earlier because the glide above moves the camera, and
        // this is the first point at which the viewport is the one the frame will draw with.
        stampCanvas(r, quests);
        int dx = panDx();
        int dy = panDy();
        int cLeft = book.canvasLeft();
        int cTop = book.canvasTop();
        int cRight = book.canvasRight();
        int cBottom = book.canvasBottom();
        r.fill(cLeft, cTop, cRight, cBottom, ArmatureTheme.canvas());
        // The chapter's surface over its colour, inside the same batch and the same theme scope as
        // everything else on the canvas -- so a chapter that names a patterned theme gets it here,
        // and CanvasBackgroundArt's own bounds keep it decorative. See that class for the fades.
        CanvasBackgroundArt.draw(r, book.viewport(), ArmatureTheme.background(), ArmatureTheme.canvasPattern());

        // The node under the pointer first, because a node owns the right-click and so owns the hover
        // cue too -- a line brightening under a node would promise a menu the press will not open. The
        // line test mirrors `edgeAt` exactly (the same candidates and the same reach), so the ink lights
        // precisely where the menu opens; and it is edit-gated with the menu, so it never offers an
        // action this client cannot take.
        ClientQuestCache.Entry hovered = book.nodeAt(mouseX, mouseY, quests);

        // Every line the chapter draws, this frame: endpoints, style and route computed **once**. The
        // drawing, the hover and the handle layer all read this list -- they used to each rebuild every
        // path, which is up to three full walks of every curve per frame, the CPU half of the zoom cost.
        // And "once" is now once per *canvas*, not once per frame: see stampCanvas.
        List<QuestBookScreen.FrameEdge> edges = drawnEdges();

        // The line hover, recomputed only when the pointer or the view moved: a still pointer over a
        // still canvas cannot change which line is nearest, and the walk it would redo is every pixel
        // of every route.
        String[] hoveredEdge = book.mayEditNow() && hovered == null ? lineHover(edges, mouseX, mouseY, dx, dy) : null;
        // A value-equal key -- a List, not the candidate's own array -- because Hover compares by
        // equals, and a fresh array every frame would restart the fade every frame and never arrive.
        edgeHover.update(hoveredEdge == null ? null : List.of(hoveredEdge[0], hoveredEdge[1]), now);
        // The caption the tooltip pass reads: what the line menu would act on, in the titles the
        // canvas draws rather than the ids the files hold. Published like hoveredQuestLink, read
        // with the captions at TOOLTIP_Z so a depth-writing icon cannot cover it.
        hoveredEdgeCaption = hoveredEdge == null ? null : lineCaption(hoveredEdge[0], hoveredEdge[1]);

        // The canvas's decoration, under everything else on it: a box behind a cluster of quests, a label over
        // the backdrop, a rule between two tiers. After the chapter's own background and **before** the
        // dependency lines, which is the legacy format's own arrangement and the reason a box is a container rather than a
        // lid over what it names. Culled per frame against the pan-shifted bounds; the look is read per frame,
        // because hover eases and a selection changes on a press.
        CanvasElementArt.Frame elementFrame = elementFrame(r);
        for (QuestBookScreen.ElementSlot slot : canvasElements) {
            if (!slot.overlaps(dx, dy, cLeft, cTop, cRight, cBottom)) {
                continue;
            }
            CanvasElementArt.draw(elementFrame, slot.element(), elementLook(slot));
        }
        // And the grips, over the elements and under the lines and the nodes. That order is the press order
        // read backwards: a node keeps the press over a grip, so a grip must not draw over a node -- an
        // invisible control that acted would be worse than one that cannot be reached. Only the selected
        // element wears them, and only while editing: a reader has no gestures for them at all.
        QuestBookScreen.ElementSlot gripped = book.mayEditNow() ? book.elementSlot(QuestBookScreen.selectedElement) : null;
        if (gripped != null) {
            drawElementGrips(r, gripped);
        }

        // The element under the pointer, and only when no node is: a node is drawn over an element, so a press
        // that lands on both belongs to the node. Asked here rather than in the element pass below because the
        // tooltip pass reads it after the canvas is done.
        hoveredQuestLink = hovered == null ? linkAtId(mouseX, mouseY) : null;
        hoveredElement = hovered == null && hoveredQuestLink == null ? book.elementAt(mouseX, mouseY) : null;

        // Told what the pointer is over and what is being read, once, before the lines and the nodes
        // are drawn. Then every node asks how hovered it is -- which is what makes the ring ease in
        // as the pointer arrives and ease out as it leaves -- and every line asks how much glow
        // reaches it, so a sweep across a chapter hands the lines over instead of snapping them.
        // See Hover for why both halves have to ease.
        //
        // A hovered link answers here too, under its own id: link ids can never equal quest ids --
        // the index refuses the file -- so one map holds both without either reaching through the
        // other. The steady glow is told separately, for the reason its field gives.
        nodeHover.update(hoveredQuestLink != null ? hoveredQuestLink : hovered == null ? null : hovered.id(),
                now);
        selectionGlow.update(glowSelection(), now);

        // How far out this frame is, once, for everything below it. Zooming out is what puts the most
        // nodes on screen at once, and it is also where the fine detail stops being readable -- so the
        // tier is read here rather than decided per drawing. See CanvasDetail for the tiers and for why
        // both thresholds sit below the zoom the project's pictures are taken at.
        //
        // A chapter or book that disabled the LOD skips the tiers outright: FULL at every zoom,
        // so rings, titles and badges draw where a tiered chapter would have dropped them.
        // The icon half of the same promise is per node, in `drawNode`; this is the rest.
        CanvasDetail detail = ClientQuestCache.lodDisabledFor(QuestBookScreen.effectiveChapter())
                ? CanvasDetail.FULL
                : CanvasDetail.of(book.viewport().scale());

        // Dependency lines first, so nodes draw over them.
        //
        // Through the edge's own geometry rather than through `drawStyledPath(path, style, ...)`: the
        // rectangles and the arrowheads were worked out when this edge was built, so a frame re-issues
        // them and measures nothing. The pan-shifted origin is added per fill (see `drawStyledPath`'s overload).
        for (QuestBookScreen.FrameEdge edge : edges) {
            if (!edge.reaches(dx, dy, cLeft, cTop, cRight, cBottom)) {
                continue;
            }
            drawStyledPath(r, edge.geometry(), edge.origin(dx, dy),
                    lineInk(edge.baseColour(), edge.fromId(), edge.toId(), now));
            drawEdgePulse(r, edge, now, dx, dy);
        }

        // The chapter's markers, in the node layer but beneath the nodes: a shortcut beside the quests
        // it points at, wearing each target's state. Before the nodes so a node keeps the press over a
        // marker -- the same rule that puts the elements behind both, and for the same reason: the
        // thing drawn on top is the thing a press belongs to, and a press that landed elsewhere than
        // what it opened would be a fault with no visible cause.
        for (LinkSlot slot : canvasLinks) {
            if (!slot.overlaps(dx, dy, cLeft, cTop, cRight, cBottom)) {
                continue;
            }
            drawLink(r, slot.shifted(dx, dy), nodeHover.amount(slot.slot().link().id(), now), detail);
        }

        // Only the nodes the canvas can show. The scissor already hides the rest, but a clipped fill is
        // still a fill that was built, transformed and submitted -- and at high zoom most of a chapter is
        // off-canvas, which is why this is the node half of the zoom fix. Culled against the pan-shifted
        // screen box so panning does not re-stamp.
        // The focused id unwrapped once for the loop rather than per node: `focused()` answers an
        // Optional, and mapping it per node allocates two throwaways per visible node per frame.
        String focusedId = book.canvasFocus.focused().orElse(null);
        List<QuestBookScreen.FrameNode> nodes = frameNodes;
        for (QuestBookScreen.FrameNode node : nodes) {
            int nx = node.x() + dx;
            int ny = node.y() + dy;
            int nSize = node.size();
            if (nx >= cRight || nx + nSize <= cLeft || ny >= cBottom || ny + nSize <= cTop) {
                continue;
            }
            float flash = node.id().equals(book.flashQuest)
                    ? CanvasReveal.flash(now - book.flashStart, CanvasReveal.FLASH_MILLIS) : 0F;
            drawNode(r, node, nx, ny, nodeHover.amount(node.id(), now), flash, detail, focusedId);
        }
        // Titles in their own pass, after every node, so a label can see the other nodes -- see the
        // stamp for what happened when it could not. Placed and cut there; the frame applies the pan
        // shift and re-issues fills and runs.
        drawLabels(r);
        // The reward badges last of the node furniture: a title's backdrop is opaque and reaches the
        // corner on a long name, so a badge drawn with the nodes would vanish exactly when the chapter
        // is busiest.
        book.bookSidebar.drawRewardBadges(r, nodes, dx, dy, detail);
        // The padlocks after everything node-shaped, straddling their node's top-right corner:
        // a large icon reaches the corner the lock would sit inside, and vanilla's item pipeline
        // draws over fills whatever order they are issued in — a lock drawn with its node ends up
        // under the item. Half outside the panel the icon cannot reach it, and the half that stays
        // reads as pinned to the node rather than floating beside it. the legacy `show_lock_icons`
        // with the quest's own `hide_lock_icon`, either silence winning; a locked node without one
        // still reads locked — the blocked edge and the dim wash stay — because the mark is the
        // announcement, not the state.
        book.bookSidebar.drawLockMarks(r, nodes, dx, dy, detail);

        // The handle layer **after the nodes**, deliberately: a dot that overlaps a node -- an anchor
        // dragged round to its far side -- has to be on top of it, or the thing in your hand disappears.
        // The frame's own edge list, so the paths it grabs against are the ones that were drawn.
        book.bookRewards.drawEdgeHandles(r, mouseX, mouseY, edges, dx, dy);

        // The editor's in-progress gestures, drawn over everything they touch: the marquee is what the
        // author is reaching for, and the rubber line is the edge they are about to make.
        if (book.marqueeActive) {
            drawMarquee(r);
        }
        if (book.edgeDragLive && book.edgeDragFrom != null) {
            ClientQuestCache.Entry from = book.entryFor(book.edgeDragFrom);
            if (from != null) {
                // The chapter's default, because the landing quest -- whose style the new edge will wear --
                // is not known until the pointer lets go. It is the same style the line gets unless that
                // quest overrides it, which is the closest the drag can honestly promise.
                drawStyled(r, from.chapterDependencyStyle(),
                        new LineArt.Point(book.nodeScreenX(from) + book.nodeSize(from) / 2,
                                book.nodeScreenY(from) + book.nodeSize(from) / 2),
                        new LineArt.Point((int) book.edgeDragX, (int) book.edgeDragY),
                        // The pointer end has no node, so its half is zero and the head rides at the
                        // pointer exactly; the source's own half keeps its end clear of its rim.
                        book.nodeSize(from) / 2, 0, ArmatureTheme.hoverRing());
            }
        }
        if (book.pickingDependency) {
            // Named, because the pick survives a chapter switch: the author may be looking at another
            // chapter's nodes and needs to know which quest is gaining the prerequisite.
            String hint = book.pendingPick == null
                    ? "click the quest to depend on \u00b7 escape cancels"
                    : "click the quest to depend on \u00b7 for " + book.pendingPick.quest()
                            + " \u00b7 escape cancels";
            int hintX = book.canvasLeft() + 10;
            int hintY = book.canvasTop() + 10;
            // A tooltip's tokens rather than a label's: this is a floating sentence over the canvas,
            // not a node label, and only the tooltip pair stays readable on a light theme — a label
            // backdrop is dark on every shipped theme (it carries white node ink) while body ink
            // is dark on paper, so that pairing is dark-on-dark there.
            r.fill(hintX - 3, hintY - 2, hintX + r.textWidth(hint) + 3, hintY + 10,
                    ArmatureTheme.tooltipFill());
            r.text(hint, hintX, hintY, ArmatureTheme.tooltipText());
        }

        if (quests.isEmpty()) {
            r.text("No quests in this chapter", book.canvasLeft() + 10, book.canvasTop() + 10,
                    ArmatureTheme.faint());
        }

        return hovered;
    }

    /**
     * The marquee, as a one-pixel ring in the selection's own colour: the rectangle the release will
     * select is the rectangle being drawn, which is the whole honesty a rubber band has.
     */
    private void drawMarquee(GuiRenderer r) {
        int left = Math.min(book.marqueeX0, book.marqueeX1);
        int right = Math.max(book.marqueeX0, book.marqueeX1);
        int top = Math.min(book.marqueeY0, book.marqueeY1);
        int bottom = Math.max(book.marqueeY0, book.marqueeY1);
        r.fill(left, top, right + 1, top + 1, ArmatureTheme.selectedRing());
        r.fill(left, bottom, right + 1, bottom + 1, ArmatureTheme.selectedRing());
        r.fill(left, top, left + 1, bottom + 1, ArmatureTheme.selectedRing());
        r.fill(right, top, right + 1, bottom + 1, ArmatureTheme.selectedRing());
    }

    /** The hovered node's title, under the pointer. */
    void drawNodeCaption(GuiRenderer r, ClientQuestCache.Entry entry) {
        QuestNodeArt.caption(r, book.nodeScreenX(entry), book.nodeScreenY(entry), book.nodeSize(entry),
                QuestBookScreen.titleOf(entry), book.canvasLeft(), book.visibleCanvasRight(), book.canvasBottom());
    }

    /** The hovered link's target title, under the pointer: what the press will open. */
    void drawLinkCaption(GuiRenderer r, LinkSlot slot) {
        QuestNodeArt.caption(r, slot.slot().box().left(), slot.slot().box().top(),
                slot.slot().box().width(), QuestBookScreen.titleOf(slot.target()), book.canvasLeft(), book.visibleCanvasRight(),
                book.canvasBottom());
    }

    /** A dependency line's caption: the two quests it joins, in the titles the canvas draws. */
    private String lineCaption(String fromId, String toId) {
        ClientQuestCache.Entry from = book.entryFor(fromId);
        ClientQuestCache.Entry to = book.entryFor(toId);
        return (from == null ? fromId : QuestBookScreen.titleOf(from)) + " \u2192 " + (to == null ? toId : QuestBookScreen.titleOf(to));
    }

    /**
     * The quest whose lines glow steadily, or null: the quest being read, else a lone selection.
     *
     * <p>The overlay first, because reading is what the glow is for -- and a lone selection second,
     * like FTB's single-selection highlight, because a marquee of six quests has no one set of lines
     * to name. A stale id glows nothing: an edge that no longer exists cannot touch it, so a deleted
     * quest leaves no light behind.
     */
    private String glowSelection() {
        if (book.overlay == PanelKind.QUEST && book.overlayQuest != null) {
            return book.overlayQuest;
        }
        List<String> sel = QuestBookScreen.selection();
        return sel.size() == 1 ? sel.get(0) : null;
    }

    // drawIcon(GuiGraphics, ...) used to be here, delegating to ArmatureTheme's copy. Both are gone:
    // the operation is `GuiRenderer.icon` now, so the screen calls it on the renderer it was handed
    // rather than on a helper, and there is no static method on either side to pass the wrong thing to.
    //
    // Worth keeping as a note because of what the indirection cost. This method existed only to forward
    // to another class's static helper, and that helper existed only because a colour table had been
    // asked to solve a rendering problem. Two layers of forwarding around one pose-stack manipulation,
    // and the manipulation is the only part that had anything to say.

    private void drawNode(GuiRenderer r, QuestBookScreen.FrameNode node, int x, int y, float hover, float flash,
                          CanvasDetail detail, String focusedId) {
        int size = node.size();
        int edge = node.edge();

        boolean isSelected = QuestBookScreen.isSelected(node.id());

        // The hover ring's alpha is scaled by the eased hover, so it fades in and out rather than
        // appearing. `translucent` rather than `alphaOf`: HOVER_RING is already 0x80 alpha, and
        // `alphaOf` would discard that and make a fully-hovered ring twice as bright as it has always
        // been. Selection is not animated at all -- the row you are on is a state, not a transition.
        //
        // The locate flash outranks both while it lasts: it is the answer to "where did that quest
        // go", and the one moment the node must not blend in with its neighbours.
        // The keyboard's focus rings like a selection, and that is deliberate: it is the pointer for
        // somebody who has no pointer, so it has to be as loud as the mouse's own mark. See CanvasFocus.
        // Unwrapped by the caller: `focused()` answers an Optional, and mapping it here would
        // allocate two throwaways per node per frame for an answer that cannot move mid-loop.
        boolean isFocused = node.id().equals(focusedId);
        int ring = 0;
        if (flash > 0F) {
            ring = Colour.translucent(ArmatureTheme.selectedRing(), flash);
        }
        else if (hover > 0F || isSelected || isFocused) {
            ring = isSelected || isFocused
                    ? ArmatureTheme.selectedRing()
                    : Colour.translucent(ArmatureTheme.hoverRing(), hover);
        }
        if (!detail.rings()) {
            // A one-pixel ring round a twelve-pixel node is a thicker border rather than a cue, and the
            // selection is still legible as the node's own ink. The flash goes with it: it answers
            // "where did that quest go", and at this zoom every node looks the same anyway.
            ring = 0;
        }

        // The wash FOLLOWS THE SHAPE, and that is the whole point of drawing it here rather than with a
        // `fill` rectangle over the icon's box -- see `QuestNodeArt` for the drawing and for why it is
        // shared with the settings preview rather than written twice.
        int wash = node.wash();

        QuestNodeArt.draw(r, x, y, new QuestNodeArt.Look(size, node.shape(), node.geometry(),
                // The icon was offered at stamp time, and the node's own box decided whether it
                // is drawn. See CanvasSettings and QuestNodeArt for why the zoom never did.
                node.icon(), node.texture(), node.sprite(), node.iconScale(),
                // A chapter or book that disabled the LOD draws every icon at every zoom — legacy
                // parity, resolved per chapter so one tierless chapter does not tierless the rest.
                // One map lookup per node per frame, which is what the chapter index is for.
                ClientQuestCache.lodDisabledFor(node.chapterId()),
                edge, ring, wash));

        if (book.mayEditNow() && !QuestBookScreen.questVisible(node.id())) {
            // Marked, because the author is looking at a node the reader's book does not draw -- and
            // without a mark the only way to find out would be to open the book as a player and notice
            // something missing. A dashed square, deliberately unlike every other ring on this canvas:
            // those mean selection or hover, and this means "you are seeing this and nobody else is".
            drawHiddenMark(r, x, y, size);
        }
    }

    /**
     * One marker, wearing its target's state.
     *
     * <p>The icon is the target's own, read through the drafts: a shape or a target the tree has not
     * carried yet is drawn as the hand has it, which is what keeps a drag from snapping the marker
     * back under the pointer. The ring is hover plus selection, for the reason a node's is: the row
     * the author is on is a state, not a transition.
     */
    private void drawLink(GuiRenderer r, LinkSlot slot, float hover, CanvasDetail detail) {
        QuestState state = ClientQuestCache.stateOf(slot.target().id());
        int ring = hover > 0F ? Colour.translucent(ArmatureTheme.hoverRing(), hover) : 0;
        if (!detail.rings()) {
            // The same mercy the nodes get at this zoom: a one-pixel ring round a twelve-pixel node
            // is a thicker border rather than a cue.
            ring = 0;
        }
        // The selection ring, unanimated like a node's: the row the author is on is a state, not a
        // transition. Outranks the hover, because a selected link under the pointer is still the
        // thing the tab is editing.
        if (slot.slot().link().id().equals(selectedLink)) {
            ring = ArmatureTheme.selectedRing();
        }
        QuestLinkArt.draw(new QuestLinkArt.Frame(r, book.viewport()), slot.slot(), slot.target().icon(),
                slot.target().textureIcon(), slot.target().spriteIcon(), state, ring,
                ClientQuestCache.lodDisabledFor(slot.target().chapterId()));
    }

    /** A dashed one-pixel square around a node that is hidden from players. */
    private static void drawHiddenMark(GuiRenderer r, int x, int y, int size) {
        int colour = ArmatureTheme.faint();
        for (int i = 0; i < size; i += 4) {
            int length = Math.min(2, size - i);
            r.fill(x + i, y, x + i + length, y + 1, colour);
            r.fill(x + i, y + size - 1, x + i + length, y + size, colour);
            r.fill(x, y + i, x + 1, y + i + length, colour);
            r.fill(x + size - 1, y + i, x + size, y + i + length, colour);
        }
    }

    /**
     * Node titles, drawn once after every node.
     *
     * <p>A separate pass rather than part of {@link #drawNode}, because a label needs to know about the
     * <i>other</i> nodes: how much room there is beside it, and whether the space under it is occupied.
     * Drawing labels inside the node loop is what produced the garbled text in the screenshot — each
     * label knew only about its own node, so three of them were drawn straight through each other.
     */
    private void drawLabels(GuiRenderer r) {
        // Built by the stamp; the frame applies the pan shift, clamps against the dock-aware
        // canvas edge, checks whether a clamped edge label now overlaps a neighbouring node,
        // and re-issues fills and runs. The colour stays here because selection is a press
        // rather than a revision, and a selected label must answer it on the press rather than
        // on the next stamp.
        int dx = panDx();
        int dy = panDy();
        int cLeft = book.canvasLeft();
        int cTop = book.canvasTop();
        int cBottom = book.canvasBottom();
        int vRight = book.visibleCanvasRight();
        for (FrameLabel label : frameLabels) {
            int textY = label.y() + dy;
            if (textY + 9 > cBottom || textY + 9 <= cTop) {
                continue;
            }
            int width = label.cut().advance();
            int textX = Mth.clamp(label.x() + dx, cLeft + 2, vRight - width - 2);
            if (textX >= vRight || textX + width <= cLeft) {
                continue;
            }
            if (stampOverlap != null && stampOverlap.over(label.owner(), textX - dx, label.y(), width)) {
                continue;
            }
            QuestState state = label.state();
            int textColour = switch (state) {
                case LOCKED -> ArmatureTheme.blocked();
                case COMPLETED -> ArmatureTheme.complete();
                default -> QuestBookScreen.isSelected(label.id()) ? ArmatureTheme.title() : ArmatureTheme.body();
            };

            // A backdrop, so a label sitting over a connector line is still readable. Opaque rather
            // than shadowed: a shadow does not help against a line of similar brightness.
            r.fill(textX - 2, textY - 1, textX + width + 2, textY + 9,
                    ArmatureTheme.labelBackdrop());
            RichText.draw(r, label.cut(), textX, textY, textColour);
        }
    }

    /**
     * The chapter's edges and its nodes, as they were last built, and the frame state they were
     * built from.
     *
     * <h2>Why this exists at all, when the individual reads are cheap</h2>
     *
     * <p>Because there are so many of them and they are re-done every frame. Building the edges alone costs
     * a hash map of every quest in the chapter, a dependency read per quest, two rim searches per edge and a
     * list per edge; culling the nodes costs a lookup and a viewport mapping each. None of that scales with
     * what is on screen — only with how big the chapter is — which is exactly the shape of the report that
     * prompted this: a nine-hundred-node chapter at 20 fps while an empty one ran at the refresh rate.
     *
     * <h2>The key, and why every field is in it</h2>
     *
     * <p>A node's screen position depends on the tree (its own coordinates), the draft (a position or size
     * being edited), the editor session's pending moves, the node being dragged right now, and the viewport
     * scale; an edge's colour depends on progress and on the theme; and the chapter decides which quests are
     * built. Pan offsets ({@code offsetX}, {@code offsetY}) are deliberately left out of the key: panning
     * shifts every screen position by {@code (panDx(), panDy())} at draw and hit-test time without
     * re-stamping.
     *
     * <p><b>Including whether this client may edit</b>, which is not a detail: an author sees every quest,
     * hidden flags and all, and a reader sees only the visible ones — that filter is what
     * {@code questsIn(chapter)} applies. Leaving it out of the key would leave the reader's node set on
     * screen after switching into edit mode, until something else happened to move the canvas. It was left
     * out of the first version of this record, and an outside review found it.
     *
     * <p>Stamped **after** the locate glide has advanced the camera, because that moves the viewport: a key
     * taken before it would describe the position the frame started at rather than the one it draws.
     */
    private record CanvasState(long tree, long progress, long draft, long editors, String dragging,
                               float dragX, float dragY, float scale,
                               int left, int top, int right, int bottom, int visibleRight, String chapter,
                               Object theme, boolean authoring, long text, CanvasElement elementPreview,
                               long elementShape, String selectedElement, long linkShape,
                               QuestLink linkPreview, String selectedLink) {
    }

    CanvasState canvasState;
    private int stampOriginX;
    private int stampOriginY;
    private LabelOverlap stampOverlap;
    private List<QuestBookScreen.FrameEdge> canvasEdges;

    int panDx() {
        return canvasState == null ? 0 : book.viewport().originX() + book.viewport().offsetX() - stampOriginX;
    }

    int panDy() {
        return canvasState == null ? 0 : book.viewport().originY() + book.viewport().offsetY() - stampOriginY;
    }

    /**
     * One label ready to draw: placed, cut and collision-tested per stamp, re-issued
     * per frame.
     *
     * <p>The cut carries the runs the draw would have decided on, so the frame draws
     * fills and runs and measures nothing — not the title's locale resolution, not the
     * truncation walk, not the legacy token parse. The colour stays per frame: selection
     * is a press rather than a revision, and a selected label must answer it on the
     * press rather than on the next stamp.
     */
    private record FrameLabel(String id, QuestState state, int owner, int x, int y, RichText.Cut cut) {
    }

    /** The chapter's nodes as the frame draws them, in draw order. Rebuilt by the stamp. */
    List<QuestBookScreen.FrameNode> frameNodes = List.of();

    /** The chapter's labels as the frame draws them, placed and cut. Rebuilt by the stamp. */
    private List<FrameLabel> frameLabels = List.of();

    /**
     * The label pass's working buffers, reused across stamps rather than rebuilt.
     *
     * <p>Cleared and refilled on every pass that uses them; the published labels leave as a copy,
     * so reuse can never show a previous stamp's rows. See the label pass for why the pass cannot
     * be skipped on a drag (positions move) but its containers can survive one.
     */
    private final List<ClientQuestCache.Entry> stampNamed = new ArrayList<>();
    private final List<Integer> stampColumns = new ArrayList<>();
    private final List<LabelOverlap.Box> stampBoxes = new ArrayList<>();
    private final Map<String, Integer> stampBoxOf = new HashMap<>();
    private final List<FrameLabel> stampLabels = new ArrayList<>();

    /**
     * Whether any drawn node wears a cycling adopted icon, and the second it was last
     * drawn for. A cycling icon advances once a second, so a stamp that could not see
     * the clock would freeze it — the stamp re-runs when the second turns, but only
     * while one is actually on screen. See {@link ClientQuestCache#adoptionCycles}.
     */
    private boolean canvasCycling;
    private long canvasAnimSecond;

    List<QuestBookScreen.ElementSlot> canvasElements = List.of();

    /**
     * One link ready to draw: the marker, its box on screen, and the target it mirrors.
     *
     * <p>The target is resolved at stamp time rather than per frame, because the draw needs its
     * icon and the click needs its chapter — and both are answers the tree already gave. A link
     * whose target the tree does not know never becomes a slot: the validator refuses the file,
     * and this is the second line of defence for a pack edited under a client that has not
     * resynced.
     */
    record LinkSlot(QuestLinkArt.Slot slot, ClientQuestCache.Entry target) {

        LinkSlot shifted(int dx, int dy) {
            if (dx == 0 && dy == 0) {
                return this;
            }
            CanvasElementArt.Box b = slot.box();
            CanvasElementArt.Box shiftedBox = new CanvasElementArt.Box(
                    b.left() + dx, b.top() + dy, b.right() + dx, b.bottom() + dy);
            return new LinkSlot(new QuestLinkArt.Slot(slot.link(), shiftedBox, slot.geometry()), target);
        }

        boolean overlaps(int dx, int dy, int left, int top, int right, int bottom) {
            CanvasElementArt.Box b = slot.box();
            return b.left() + dx < right && b.right() + dx > left
                    && b.top() + dy < bottom && b.bottom() + dy > top;
        }
    }

    /** The chapter's markers, in declaration order. Rebuilt by the stamp, like everything drawn. */
    private List<LinkSlot> canvasLinks = List.of();

    /** The link under the pointer, by id, or null. Published by the draw, read by the press. */
    private String hoveredQuestLink;

    /** The hovered dependency line's caption, or null. Published by the draw, read with the captions. */
    private String hoveredEdgeCaption;

    /** The link a press took hold of, by id, or null. Decided on release, like a node's. */
    String pressedQuestLink;

    /**
     * The link the author has selected, or empty.
     *
     * <p>Deliberately <b>not</b> folded into the quest selection or the element's, for the reason both
     * give: three namespaces, three selections, and every bulk operation would otherwise have to ask
     * which kind each id is. One link at a time is the honest shape of the gesture — an author moves
     * one marker, and its fields are the only thing the selection opens.
     */
    static String selectedLink;

    /** The link whose delete button is asking its question, or null. Asked twice, like every delete. */
    String linkConfirmingDelete;

    /** A link-target pick in flight, or null. The next node click names the target; Escape cancels. */
    dev.ellipog.tenet.client.dev.LinkPick pendingLinkPick;

    /** The element under the pointer, or empty. Published by the draw, read by the tooltip pass. */
    private String hoveredElement;

    /**
     * The element a gesture is carrying, the element it started from, and the version being previewed.
     *
     * <h2>Why a previewed element rather than a set of drag offsets</h2>
     *
     * <p>Because there are three gestures now — a move, a resize and a rotate — and each produces a
     * <i>different element</i> rather than a different offset: a resize changes the box, a rotate changes one
     * number, and a line's grip changes an endpoint. Holding the result means the drawing has one thing to
     * substitute, the commit has one thing to diff against {@link #elementDragBase}, and a fourth gesture
     * would need no new field at all.
     *
     * <p>{@code elementDragBase} is the element as this client believes it to be when the press takes hold —
     * the tree plus the drafts, not the canvas's cached copy — captured once: the diff is against where the
     * gesture started, so the file is written with what changed rather than with everything, and a gesture that
     * started from the cached copy would compound its own last frame.
     */
    String elementDragging;
    boolean elementDragLive;
    CanvasElement elementDragBase;
    CanvasElement elementPreview;

    /**
     * The link a gesture is carrying, the link it started from, and the version being previewed.
     *
     * <p>One gesture rather than three: a link has no grips, so there is nothing to resize or rotate
     * and the body is the whole of it. Otherwise the same arrangement as an element's drag — the base
     * is captured once at the press from the settled belief, every frame is derived from it, and the
     * release diffs the two into field writes — because a drag that compounded its own last frame
     * would run away from the pointer the same way.
     */
    String linkDragging;
    boolean linkDragLive;
    QuestLink linkDragBase;
    QuestLink linkPreview;

    /**
     * The grip the gesture took hold of, or null when it is moving the element's body.
     *
     * <p>Null is the ordinary case and the one a press on the middle of a box is. A grip is claimed only on the
     * <b>selected</b> element, because that is the only one wearing any — see {@code CanvasElementArt.handles}.
     */
    CanvasElementArt.Handle elementHandle;

    /**
     * The element panel's own rows, scroll view and layout: the same pair the Chapter tab keeps, for the
     * same kind of content. The element it is about is {@link #elementPanelId}, because a panel's subject is
     * per-kind state rather than part of the arrangement -- see {@code PanelStack}'s own note on that.
     */
    final dev.ellipog.armature.client.ui.kit.ScrollView elementView =
            dev.ellipog.armature.client.ui.kit.ScrollView.of(
                    dev.ellipog.armature.client.ui.kit.Viewport.fixed());
    List<ToolsLayout.Action> elementRows = List.of();
    Layout elementLayout;

    /**
     * Which of the element panel's sections are put away, by heading key.
     *
     * <p>The screen owns it the way it owns {@code questFolded}: the layout only reads which blocks to
     * skip. Keys carry the element's id, so folds belong to the element they were made on and never leak
     * onto another.
     */
    final Set<String> elementFolded = new LinkedHashSet<>();

    /**
     * The element whose Delete is asking its question, or null when none is.
     *
     * <p>The two-press arm every destructive control in this editor has: the first press says "Really
     * delete?", the second deletes, and anything that changes the subject disarms it. Stored as the id
     * rather than a boolean because the panel can move between elements without closing.
     */
    String elementConfirmingDelete;

    /**
     * Which canvas element the panel is about, or null when it is not open.
     *
     * <p>Its own field rather than a read of {@code selectedElement}, because the two are not the same
     * question: a selection survives the panel being closed (Escape puts the panel away and leaves the ring),
     * and the panel is what knows which element its rows were built for — so a row press can never land on a
     * different element from the one it was drawn for.
     */
    String elementPanelId;

    /**
     * The element panel's drawn targets: what the chrome registers, and what its own press walks.
     *
     * <p>Its own list rather than the editor's, for the reason the pack's panel has one: the editor's list
     * still holds the marks the panel behind it drew, and a press inside this panel must not be able to land
     * on one of those.
     */
    final List<QuestBookScreen.EditTarget> elementTargets = new ArrayList<>();

    /** Rebuilds the frame's canvas if anything it is drawn from has moved. See {@link CanvasState}. */
    private void stampCanvas(GuiRenderer r, List<ClientQuestCache.Entry> quests) {
        CanvasState key = new CanvasState(ClientQuestCache.treeRevision(),
                ClientQuestCache.progressRevision(), QuestBookScreen.fieldDraft.version(), book.editors.epoch(), book.draggedNode,
                book.dragNodeX, book.dragNodeY, book.viewport().scale(),
                book.canvasLeft(), book.canvasTop(), book.canvasRight(), book.canvasBottom(), book.visibleCanvasRight(),
                QuestBookScreen.effectiveChapter(),
                ArmatureTheme.current(), book.mayEditNow(), ClientQuestCache.textRevision(),
                // A gesture in flight re-stamps, exactly as a node's drag does: the canvas draws the element as
                // the hand has it, and the previewed element is the whole of what the frame draws differently.
                // It is one component rather than a set of offsets because the three gestures produce three
                // different elements -- see the fields above.
                elementPreview,
                // The shape draft, because an element added or deleted re-stamps: the canvas has to draw the
                // element the author has just asked for, and stop drawing the one they have just asked away.
                // The *values* need no component here -- `draft` above is `fieldDraft`'s own version, which
                // moves whenever an element's field is drafted.
                QuestBookScreen.elementDraft.version(),
                // The element the Chapter tab's list has chosen, so the ring on the canvas follows the
                // panel's own selection: two views of one choice, and a ring that arrived a frame late
                // because the stamp could not see it would be the one place they disagreed.
                QuestBookScreen.selectedElement,
                // And the link draft's version, for the same reason as the element's: a marker added or
                // deleted re-stamps. The *values* need no component here either -- `draft` above is
                // `fieldDraft`'s own version, which moves whenever a link's field is drafted.
                QuestBookScreen.linkDraft.version(),
                // A gesture in flight re-stamps, exactly as an element's drag does: the canvas draws the
                // link as the hand has it. One component, because a link has one gesture -- a move.
                linkPreview,
                // The link the Chapter tab's list has chosen, so its ring follows the panel's selection
                // the way an element's does.
                selectedLink);
        // A still canvas pays nothing here. The one exception is a cycling adopted icon,
        // which advances once a second with no revision behind it: while one is drawn the
        // second is part of the key's world, and the stamp re-runs when it turns.
        long nowSecond = Util.getMillis() / 1000L;
        if (key.equals(canvasState) && (!canvasCycling || canvasAnimSecond == nowSecond)) {
            return;
        }
        if (key.equals(canvasState)) {
            // Only the second turned: a cycling adopted icon advances, and nothing else moves.
            // Refresh the icons in place rather than rebuilding the canvas. Edges, labels,
            // elements and links all ride state the key already names — re-running them is a
            // line re-walk and a label re-place every second for answers that cannot have
            // changed, which is exactly the periodic spike the frame overlay showed. States,
            // sizes and boxes are untouched: a progress or tree change moves the key and takes
            // the full path below, so by the time this runs the only thing stale is icon
            // frames. Not published as a rebuild, because nothing was rebuilt.
            java.util.Map<String, ItemStack> fresh = new java.util.HashMap<>();
            for (ClientQuestCache.Entry entry : quests) {
                ItemStack icon = entry.icon();
                if (icon.isEmpty()) {
                    ItemStack adopted = ClientQuestCache.adoptedQuestIcon(entry);
                    if (!adopted.isEmpty()) {
                        icon = adopted;
                    }
                }
                fresh.put(entry.id(), icon);
            }
            List<QuestBookScreen.FrameNode> refreshed = new ArrayList<>(frameNodes.size());
            for (QuestBookScreen.FrameNode node : frameNodes) {
                refreshed.add(node.withIcon(fresh.getOrDefault(node.id(), node.icon())));
            }
            frameNodes = List.copyOf(refreshed);
            canvasAnimSecond = nowSecond;
            return;
        }
        stampOriginX = book.viewport().originX() + book.viewport().offsetX();
        stampOriginY = book.viewport().originY() + book.viewport().offsetY();
        canvasEdges = frameEdges(quests);

        // One node ready to draw, per quest in the chapter: the position and size at the stamp
        // origin, the shown state, the drafted shape and rotation resolved to geometry, the
        // adopted icon, the icon scale, the edge and the wash. Sixty frames a second of draft
        // lookups, dependency walks and task walks become one stamp's worth, because every answer
        // rides state the key above already names. Off-canvas culling happens at draw time against
        // the pan-shifted screen coordinates so panning never re-stamps.
        List<QuestBookScreen.FrameNode> nodes = new ArrayList<>(quests.size());
        boolean cycling = false;
        int visibleNodes = 0;
        int cLeft = book.canvasLeft();
        int cTop = book.canvasTop();
        int cRight = book.canvasRight();
        int cBottom = book.canvasBottom();
        for (ClientQuestCache.Entry entry : quests) {
            QuestState state = QuestBookScreen.shownState(entry);
            int size = book.nodeSize(entry);
            int x = book.nodeScreenX(entry);
            int y = book.nodeScreenY(entry);
            if (x < cRight && x + size > cLeft && y < cBottom && y + size > cTop) {
                visibleNodes++;
            }
            // The border reads the node-edge tokens rather than the state inks, and that is the
            // whole of what those four tokens are for — see QuestNodeArt.edgeFor, which owns the
            // switch so the canvas's nodes and links cannot disagree about it.
            // The geometry is the outline with its rotation applied: a pending shape or rotation
            // — a settings-page change the tree has not carried yet — resolves through the same
            // cached outline table, so the canvas follows the click. A quest with no icon of its
            // own wears its first task's, cycling for a filter — see adoptedQuestIcon.
            QuestShape drawnShape = drawnShape(entry);
            int drawnRotation = QuestBookScreen.fieldDraft.number(entry.chapterId(), entry.id(), "rotation",
                    entry.rotation());
            boolean draftedLook = drawnShape != entry.shape() || drawnRotation != entry.rotation();
            ItemStack nodeIcon = entry.icon();
            boolean adopted = false;
            if (nodeIcon.isEmpty()) {
                nodeIcon = ClientQuestCache.adoptedQuestIcon(entry);
                adopted = !nodeIcon.isEmpty();
            }
            if (adopted && ClientQuestCache.adoptionCycles(entry)) {
                cycling = true;
            }
            nodes.add(new QuestBookScreen.FrameNode(entry, entry.id(), entry.chapterId(), x, y, size, state, drawnShape,
                    draftedLook ? ClientQuestCache.geometry(drawnShape, drawnRotation)
                            : entry.geometry(),
                    nodeIcon, entry.textureIcon(), entry.spriteIcon(),
                    QuestBookScreen.fieldDraft.decimal(entry.chapterId(), entry.id(), "iconScale", entry.iconScale()),
                    QuestNodeArt.edgeFor(state), QuestNodeArt.washFor(state), entry.hideLockIcon()));
        }
        frameNodes = List.copyOf(nodes);
        canvasCycling = cycling;
        canvasAnimSecond = nowSecond;

        // The canvas's decoration: the chapter's own elements, gated and measured, with the boxes the press
        // path will use. Rebuilt with everything else, because an element edit re-sends the tree -- which is
        // why `tree` is already in the key -- and because a label's box moves when the language does, which
        // is what `text` is in the key for. Culled against the canvas bounds per frame using the pan shift.
        CanvasElementArt.Frame frame = elementFrame(r);
        // A gesture in flight changes the element it is carrying, and it changes it *here* -- in the stamp
        // rather than in the model, so the file is untouched until the release and the drawing cannot disagree
        // with what will be written. The substitution is by id and it is the whole of the preview: the gesture
        // already produced the element it wants drawn, so there is no offset arithmetic to get wrong and no
        // second definition of what a move is.
        List<QuestBookScreen.ElementSlot> elements = new ArrayList<>();
        for (CanvasElement model : book.elementsNow()) {
            CanvasElement element = elementPreview != null && model.id().equals(elementPreview.id())
                    ? elementPreview : model;
            CanvasElementArt.Box box = CanvasElementArt.boxOf(element, frame);
            // An element of a type this build cannot read has no box at all and is skipped here -- the
            // chapter tab's list is where one is reached, since the canvas has nothing to aim at.
            if (box.width() <= 0 || box.height() <= 0) {
                continue;
            }
            boolean shown = CanvasElementArt.shown(element, frame);
            if (!shown && !book.mayEditNow()) {
                continue;
            }
            elements.add(new QuestBookScreen.ElementSlot(element, box, !shown));
        }
        canvasElements = List.copyOf(elements);

        // The chapter's markers: the links it draws among its quests, each with the target it
        // mirrors resolved once rather than per frame. Rebuilt with everything else, because a link
        // arrives on the tree — which is why `tree` is already in the key — and because what it
        // wears is its target's state, which is why `progress` is.
        //
        // A link whose target the tree does not know is skipped rather than taking the chapter with
        // it, for the same reason an unreadable element is: one marker missing from a canvas rather
        // than a chapter that will not draw. It should not happen — the validator refuses the file —
        // and if it does, the honest outcome is a canvas without the shortcut.
        //
        // A target the reader may not see hides its marker too: the tooltip names the quest, so
        // drawing the node would leak a title the book withholds. An author sees every marker,
        // marked by nothing — a link is content, and content is what edit mode is for.
        List<LinkSlot> links = new ArrayList<>();
        String canvasChapter = QuestBookScreen.effectiveChapter();
        if (canvasChapter != null) {
            // From the drafts rather than the tree, so a link being added, moved or deleted is drawn
            // as the hand has it: the canvas follows the gesture, not the round trip.
            List<QuestLink> models = new ArrayList<>(book.linksNow());
            // A gesture in flight changes the link it is carrying, and it changes it *here* -- in the stamp
            // rather than in the model, so the file is untouched until the release and the drawing cannot
            // disagree with what will be written. The substitution is by id and it is the whole of the
            // preview: the gesture already produced the link it wants drawn, so there is no offset
            // arithmetic to get wrong. The element path does the same; see the stamp above it.
            if (linkPreview != null) {
                for (int i = 0; i < models.size(); i++) {
                    if (models.get(i).id().equals(linkPreview.id())) {
                        models.set(i, linkPreview);
                    }
                }
            }
            for (QuestLink link : models) {
                ClientQuestCache.Entry target = book.entryFor(link.quest().id());
                if (target == null) {
                    continue;
                }
                if (!book.mayEditNow() && !QuestBookScreen.questVisible(target.id())) {
                    continue;
                }
                CanvasElementArt.Box box = QuestLinkArt.boxOf(link, book.viewport());
                links.add(new LinkSlot(new QuestLinkArt.Slot(link, box,
                        ClientQuestCache.geometry(link.shape(), 0)), target));
            }
        }
        canvasLinks = List.copyOf(links);

        // One label ready to draw, per named node: placed against its node's box and cut to the
        // room. The collision test against neighbouring nodes runs both here (for the unclamped
        // position) and in `drawLabels` (in case the canvas-edge clamp pushed an edge label into
        // a neighbour). All of it from state the key already names, so a panned or still canvas
        // pays for text measurement and truncation once instead of sixty times a second; a tierless
        // chapter builds them at every zoom, for the same reason the draw forces FULL.
        //
        // The working buffers are reused across stamps rather than rebuilt: a drag re-stamps
        // every frame, and the containers — five lists and maps sized by the quest count — were
        // a five-figure byte churn per frame before a single label was placed. Clearing and
        // refilling is sound because every buffer is fully rewritten before it is read; the
        // published labels leave as a copy. An untitled chapter skips the pass whole: with no
        // named node there is nothing to place, and the boxes built for the collision test
        // would be work for nothing.
        List<FrameLabel> labels = stampLabels;
        labels.clear();
        stampOverlap = null;
        if (ClientQuestCache.lodDisabledFor(QuestBookScreen.effectiveChapter())
                || CanvasDetail.of(book.viewport().scale()).labels()) {
            List<ClientQuestCache.Entry> named = stampNamed;
            named.clear();
            for (ClientQuestCache.Entry entry : quests) {
                if (QuestBookScreen.fieldDraft.flag(entry.chapterId(), entry.id(), "showTitle", entry.showTitle())) {
                    named.add(entry);
                }
            }
            if (!named.isEmpty()) {
                // One room per node, from its own neighbours: a single crowded pair used to decide
                // every label in the chapter, so the airy majority truncated to a gap they were nowhere
                // near. A label with no room of its own is not built at all -- the hover caption carries
                // the name -- while its neighbours keep theirs. Skipping it here rather than drawing it
                // is what keeps one tight cluster from reading as a corrupt string.
                List<Integer> columns = stampColumns;
                columns.clear();
                for (ClientQuestCache.Entry entry : named) {
                    columns.add(book.nodeScreenX(entry));
                }
                columns.sort(Integer::compareTo);
                // One box per node on the canvas, and the index that finds a node's own box:
                // the placement and the collision test read the same boxes, so the two cannot
                // disagree about where a node is.
                List<LabelOverlap.Box> boxes = stampBoxes;
                boxes.clear();
                Map<String, Integer> boxOf = stampBoxOf;
                boxOf.clear();
                for (ClientQuestCache.Entry entry : quests) {
                    boxOf.put(entry.id(), boxes.size());
                    boxes.add(new LabelOverlap.Box(book.nodeScreenX(entry), book.nodeScreenY(entry),
                            book.nodeSize(entry)));
                }
                LabelOverlap overlap = new LabelOverlap(boxes);
                stampOverlap = overlap;
                dev.ellipog.armature.client.ui.kit.Measure measure = book.textMeasure(r);
                for (ClientQuestCache.Entry entry : named) {
                    // Sorted once above rather than per label: the columns cannot move between labels,
                    // so each call was copying and sorting the same list.
                    int room = BookGeometry.labelRoomForSorted(columns, book.nodeScreenX(entry), LABEL_GAP,
                            MAX_LABEL_WIDTH);
                    if (room < MIN_LABEL_WIDTH) {
                        continue;
                    }
                    int owner = boxOf.get(entry.id());
                    LabelOverlap.Box box = boxes.get(owner);
                    int size = box.size();
                    RichText.Cut cut = RichText.truncate(QuestBookScreen.titleOfRaw(entry), room, measure);
                    int width = cut.advance();
                    int textX = box.x() + size / 2 - width / 2;
                    int textY = box.y() + size + LABEL_GAP;
                    labels.add(new FrameLabel(entry.id(), QuestBookScreen.shownState(entry), owner, textX, textY, cut));
                }
            }
        }
        // Zoomed far enough out that no title is drawn, the labels stay empty: the boxes
        // above would be work for nothing.
        frameLabels = List.copyOf(labels);
        canvasState = key;
        // What this rebuild produced, for the overlay. Published here rather than per frame because these
        // describe the canvas's *contents*, which only move when it is rebuilt -- and because `rebuilds` is
        // the number that says a drag is re-stamping, which no frame counter can show. See `CanvasStats`.
        dev.ellipog.tenet.client.dev.CanvasStats.published(
                quests.size(), canvasEdges.size(), frameLabels.size(), visibleNodes);
    }

    /**
     * What the element art cannot answer for itself: the renderer, the view, the words, the states, the mode.
     *
     * <p>Built per stamp rather than per frame, and per frame for the drawing — it is five references and a
     * record, and the alternative (the screen holding one) would be a renderer remembered across frames, which
     * is exactly the thing this codebase refuses to cache anywhere.
     */
    private CanvasElementArt.Frame elementFrame(GuiRenderer r) {
        return new CanvasElementArt.Frame(r, book.viewport(), ClientQuestCache::elementWords,
                ClientQuestCache::stateOf, book.mayEditNow());
    }

    /**
     * What pressing a pressable element does, in play mode.
     *
     * <h2>Why this is a switch over a closed set rather than a lookup</h2>
     *
     * <p>Because the set is the legacy seven names and this build runs all seven: the two the client cannot
     * run itself cross the wire as identity, and the server reads what the press does from its own
     * index. The default arm is a no-op rather than a message because a message here could only be
     * reached by a client that had resynced against a server whose validator said nothing — which is
     * not a state this build produces.
     *
     * <h2>What a press is not allowed to do silently</h2>
     *
     * <p>A press that does nothing reads as a broken control rather than as a gap in the mod, so an
     * {@code open_quest} whose target cannot be resolved says so. The validator reports the same thing at load,
     * and this is the second line of defence: a pack edited under a client that has not resynced.
     */
    void pressElement(CanvasElement element) {
        if (!(element instanceof CanvasElement.Image image) || image.click().isNone()) {
            return;
        }
        ClickAction click = image.click();
        switch (click.type()) {
            case OPEN_QUEST -> openQuest(click.data());
            // The description links' own opener, so a picture's URL and a sentence's URL are refused and
            // opened by one rule: http and https only, and a bad address says so.
            case OPEN_URI -> book.openLink(click.data());
            case SHOW_RECIPE -> showRecipe(click.data());
            case SHOW_DOCS -> showDocs(click.data());
            // The two the client cannot run itself: the chapter and the element travel, and the server
            // reads the authoritative click from its own index -- never the command, never the event id.
            // A press naming either is a request, and the request carries identity and nothing else.
            case RUN_COMMAND, CUSTOM_EVENT -> {
                String chapter = QuestBookScreen.effectiveChapter();
                if (chapter != null) {
                    ArmatureNetwork.sendToServer(
                            new dev.ellipog.tenet.net.ClickPayload(chapter, image.id()));
                }
            }
            default -> {
            }
        }
    }

    /**
     * A press naming a quest, by id or alias in any letter case: the book opens it, or says so.
     *
     * <p>One rule for a picture's {@code open_quest} click and a sentence's {@code change_page}:
     * the legacy own reader treats the latter as the former, so this build does too. A press naming
     * nothing is the validator's miss reaching the reader — a stale client, or a pack edited
     * under one — and it is answered rather than silent, the second line of defence.
     */
    void openQuest(String idOrAlias) {
        String quest = idOrAlias == null ? "" : idOrAlias;
        int page = quest.indexOf('/');
        if (page >= 0) {
            quest = quest.substring(0, page);
        }
        ClientQuestCache.Entry target = book.entryFor(quest);
        if (target == null) {
            book.status("No quest with id or alias \"" + idOrAlias + "\" to open", true);
            return;
        }
        // Through the entry's own id rather than the string in the file: the press may name an
        // alias, and opening by an alias works only as long as every lookup resolves one.
        // A `change_page` value may carry the legacy `/page` suffix after its quest id; the card
        // scrolls rather than pages, so the suffix is read and left behind.
        QuestBookScreen.selectedQuest = target.id();
        QuestBookScreen.selectedElement = null;
        QuestBookScreen.multiSelection.clear();
        book.bookRewards.openOverlay(target.id());
    }

    /**
     * A press asking for a recipe: the chosen viewer opens, and the press is answered.
     *
     * <p>Answered rather than silent in every direction: an id with nothing behind it says which id,
     * and a press with no viewer installed says that there is none — because a viewer is a soft
     * dependency, and a book that needs one hard would be a book that breaks pack by pack. The
     * target is an item alone, never a tag: a click carries one id, and tags are the task rows'
     * vocabulary rather than a press's.
     */
    private void showRecipe(String data) {
        net.minecraft.world.item.ItemStack stack = ClientQuestCache.iconOf(data, null);
        if (stack.isEmpty()) {
            book.status("No item \"" + data + "\" to look up", true);
            return;
        }
        if (!RecipeLookups.open(RecipeLookups.Target.of(stack))) {
            book.status("No recipe viewer is installed to show \"" + data + "\"", true);
        }
    }

    /**
     * A press asking for a guide page: a message naming where it would have gone.
     *
     * <p>A message rather than an opener, because there is no docs integration to open with — and a
     * recorded loss rather than a silent one, because a click that does nothing reads as a bug in
     * this mod rather than as a gap in it. The validator checks the address's shape at load, so by
     * the time this runs the address is well-formed and only the shelf is missing.
     */
    private void showDocs(String data) {
        book.status("Guide page \"" + data + "\" is not installed: guide books have no integration here",
                true);
    }

    /**
     * How one element is being looked at: selected if it is the author's, hovered if the pointer is on it.
     *
     * <p>The hover ring is shown in edit mode <b>and</b> for a pressable element in play mode, and the second
     * half is the point of it: a picture that does something when pressed has to say so before it is pressed,
     * or the only way to find out is to press it. A decorative element gets no ring in play mode, because a
     * ring on a box that does nothing promises something.
     */
    private CanvasElementArt.Look elementLook(QuestBookScreen.ElementSlot slot) {
        String id = slot.element().id();
        boolean selected = book.mayEditNow() && id.equals(QuestBookScreen.selectedElement);
        boolean hovered = id.equals(hoveredElement)
                && (book.mayEditNow() || pressable(slot.element()));
        return new CanvasElementArt.Look(selected, hovered, slot.marked());
    }

    /** Whether pressing this element would do something, which is what a play-mode hover cue promises. */
    private static boolean pressable(CanvasElement element) {
        return element instanceof CanvasElement.Image image && !image.click().isNone();
    }

    /**
     * The title of the hovered element, or null when it has none to show.
     *
     * <p>A title painted into a picture is not a tooltip: the author has already said where those words go, and
     * a second copy floating under the pointer would be the same sentence twice.
     */
    String hoveredElementTitle() {
        QuestBookScreen.ElementSlot slot = book.elementSlot(hoveredElement);
        if (slot == null || !(slot.element() instanceof CanvasElement.Image image)) {
            return null;
        }
        if (image.label().filter(dev.ellipog.tenet.quest.ElementLabel::onImage).isPresent()) {
            return null;
        }
        return image.title()
                .map(title -> ClientQuestCache.elementWords(image, "title", title))
                .filter(words -> !words.isBlank())
                .orElse(null);
    }

    private List<QuestBookScreen.FrameEdge> frameEdges(List<ClientQuestCache.Entry> quests) {
        // An id-keyed map for the lookups, so the loops below are linear rather than a scan of every
        // quest per dependency -- the same O(E*N) scan the drawing used to do per frame.
        java.util.Map<String, ClientQuestCache.Entry> byId = new java.util.HashMap<>();
        for (ClientQuestCache.Entry quest : quests) {
            byId.put(quest.id(), quest);
        }

        List<QuestBookScreen.FrameEdge> edges = new ArrayList<>();
        for (ClientQuestCache.Entry quest : quests) {
            if (!dev.ellipog.tenet.client.dev.QuestVisibility
                    .drawsDependencyLines(quest.hideDependencyLines())) {
                // The author asked for the hub without the spokes: the quest is drawn and the lines
                // arriving at it are not. Only the incoming side, so a quest that hides its own lines
                // still appears as a prerequisite of everything that depends on it.
                continue;
            }
            for (String dependencyId : QuestBookScreen.dependenciesOf(quest)) {
                ClientQuestCache.Entry dependency = byId.get(dependencyId);
                if (dependency == null) {
                    // A dependency in another chapter, or one filtered out. Not drawn: a line to
                    // nowhere reads as a rendering fault, and the overlay names the dependency.
                    continue;
                }
                // The outgoing half: the source quest asked for the fan-out without the spokes. Read
                // on the dependency (the line's source), beside the incoming read on the dependent
                // above — either silence wins, and a quest that hides its own outgoing lines still
                // appears as a prerequisite everywhere its name is read.
                if (!dev.ellipog.tenet.client.dev.QuestVisibility
                        .drawsDependentLines(dependency.hideDependentLines())) {
                    continue;
                }
                // Coloured by the *dependent's* rule rather than by completion: under the two
                // started-based modes a prerequisite with any task progress already satisfies, and a
                // line drawn dark for it was the canvas telling a player they were stuck when they
                // were not.
                boolean done = QuestBookScreen.dependencyProgressOf(quest)
                        .satisfies(dependencyId, ClientQuestCache::stateOf);
                QuestBookScreen.FrameEdge edge = frameEdge(dependency, quest, dependencyId,
                        done ? ArmatureTheme.lineDone() : ArmatureTheme.line());
                if (edge != null) {
                    edges.add(edge);
                }
            }
        }

        // A linear chapter's road. Drawn from the list order rather than from dependencies, because a
        // linear chapter declares none -- that is what makes it linear. Without this the client would
        // draw five unconnected boxes for a chapter that is unmistakably a sequence, which reads as a
        // missing feature rather than as a missing line.
        //
        // Consecutive pairs only, so the road has no shortcuts across it.
        if (!quests.isEmpty() && quests.get(0).chapterLinear()) {
            List<ClientQuestCache.Entry> ordered = quests.stream()
                    .sorted(java.util.Comparator.comparingInt(ClientQuestCache.Entry::orderInChapter))
                    .toList();
            for (int i = 1; i < ordered.size(); i++) {
                ClientQuestCache.Entry previous = ordered.get(i - 1);
                ClientQuestCache.Entry dependent = ordered.get(i);
                if (!dev.ellipog.tenet.client.dev.QuestVisibility
                        .drawsDependencyLines(dependent.hideDependencyLines())) {
                    continue;
                }
                // The road's outgoing half, for the same reason as the edge above: a quest that hides
                // its outgoing lines breaks the road after itself, and the chapters after it read as
                // unconnected rather than as hidden.
                if (!dev.ellipog.tenet.client.dev.QuestVisibility
                        .drawsDependentLines(previous.hideDependentLines())) {
                    continue;
                }
                boolean done = ClientQuestCache.stateOf(previous.id()) == QuestState.COMPLETED;
                QuestBookScreen.FrameEdge edge = frameEdge(previous, dependent, previous.id(),
                        done ? ArmatureTheme.lineDone() : ArmatureTheme.line());
                if (edge != null) {
                    edges.add(edge);
                }
            }
        }
        return edges;
    }

    /**
     * One line, ready to draw.
     *
     * <p>{@code dependencyId} is the quest the dependent waits on — the key its override is stored
     * under — and the pairing matters: the style belongs to the edge, and the edge is owned by the
     * quest that depends, not by the quest that is depended upon.
     *
     * <p>Conservative reach bounds are stored on the edge so draw-time and hover-time passes can skip
     * off-canvas lines after applying the pan delta, without rebuilding the edge list on a pan.
     */
    private QuestBookScreen.FrameEdge frameEdge(ClientQuestCache.Entry from, ClientQuestCache.Entry to, String dependencyId,
                                int baseColour) {
        return frameEdge(from, to, dependencyId, baseColour, true);
    }

    private QuestBookScreen.FrameEdge frameEdge(ClientQuestCache.Entry from, ClientQuestCache.Entry to, String dependencyId,
                                int baseColour, boolean stamped) {
        DependencyStyle style = book.styleFor(to, dependencyId);
        LineArt.Point[] ends = book.lineEnds(from, to, style);
        double chord = Math.hypot(ends[1].x() - ends[0].x(), ends[1].y() - ends[0].y());
        int slack = (int) Math.ceil(chord) + book.nodeSize(from) + book.nodeSize(to) + 32;
        int reachLeft = Math.min(ends[0].x(), ends[1].x()) - slack;
        int reachTop = Math.min(ends[0].y(), ends[1].y()) - slack;
        int reachRight = Math.max(ends[0].x(), ends[1].x()) + slack;
        int reachBottom = Math.max(ends[0].y(), ends[1].y()) + slack;
        return new QuestBookScreen.FrameEdge(from.id(), to.id(), from, to, style, ends[0], ends[1],
                reachLeft, reachTop, reachRight, reachBottom, stamped, baseColour);
    }

    /** The frame's edges as hover candidates — the paths already computed, so a walk costs only distance. */
    private List<LineArt.Candidate<String[]>> frameCandidates(List<QuestBookScreen.FrameEdge> edges, int panDx, int panDy) {
        int cLeft = book.canvasLeft();
        int cTop = book.canvasTop();
        int cRight = book.canvasRight();
        int cBottom = book.canvasBottom();
        List<LineArt.Candidate<String[]>> candidates = new ArrayList<>();
        for (QuestBookScreen.FrameEdge edge : edges) {
            if (!edge.reaches(panDx, panDy, cLeft, cTop, cRight, cBottom)) {
                continue;
            }
            candidates.add(new LineArt.Candidate<>(edge.key(), edge.path(panDx, panDy)));
        }
        return candidates;
    }

    /** One styled line along a path that is already computed, with the arrowhead gaps the caller wants. */
    private static void drawStyledPath(GuiRenderer r, List<LineArt.Point> path, DependencyStyle style,
                                       int fromHalf, int toHalf, int colour) {
        // Asks for the route **measured from its own first point**, and adds that point back as it draws.
        // A pan moves every edge's endpoints, so the absolute route is a new cache key for every edge on
        // every frame of the gesture; a line's shape is the differences between its points, and those do not
        // move. So panning now costs the path construction and the fills, and none of the walking, merging
        // or allocating that used to happen per edge per frame while the view moved. See LineArt.
        LineArt.Point origin = path.get(0);
        DependencyStyle.Weight weight = style.weightOr(DependencyStyle.Weight.THIN);
        for (LineArt.Fill fill : LineArt.fillsRelative(path, weight,
                style.dashOr(DependencyStyle.Dash.SOLID))) {
            r.fill(origin.x() + fill.x1(), origin.y() + fill.y1(),
                    origin.x() + fill.x2(), origin.y() + fill.y2(),
                    MenuFlyoutArt.ink(fill.tone(), colour));
        }
        // The head is drawn at the line's own weight: a six-pixel conduit ending in a hairline arrow was
        // the report, and the head's standoff and reach grow with the weight so it still clears its node.
        for (LineArt.Fill fill : LineArt.arrowsRelative(path, style.headOr(DependencyStyle.ArrowHead.CHEVRON),
                style.placeOr(DependencyStyle.ArrowPlace.TARGET), style.arrowSpacing(),
                fromHalf, toHalf, weight)) {
            r.fill(origin.x() + fill.x1(), origin.y() + fill.y1(),
                    origin.x() + fill.x2(), origin.y() + fill.y2(), colour);
        }
    }

    /**
     * The same, for an edge that has already worked its geometry out.
     *
     * <p>The whole of {@link FrameEdge}'s reason for existing: the rectangles are re-issued here and nothing
     * about the route is measured, copied or hashed. The origin is still added per fill, which is the one
     * thing that cannot be precomputed — it is what makes a panned line the same memo entry.
     */
    private static void drawStyledPath(GuiRenderer r, LineArt.EdgeGeometry geometry,
                                       LineArt.Point origin, int colour) {
        for (LineArt.Fill fill : geometry.fills()) {
            r.fill(origin.x() + fill.x1(), origin.y() + fill.y1(),
                    origin.x() + fill.x2(), origin.y() + fill.y2(),
                    MenuFlyoutArt.ink(fill.tone(), colour));
        }
        for (LineArt.Fill fill : geometry.arrows()) {
            r.fill(origin.x() + fill.x1(), origin.y() + fill.y1(),
                    origin.x() + fill.x2(), origin.y() + fill.y2(), colour);
        }
    }

    /** The same, for a drawn line whose path is already computed and whose ends sit on the rims. */
    private static void drawStyledPath(GuiRenderer r, List<LineArt.Point> path, DependencyStyle style,
                                       int colour) {
        drawStyledPath(r, path, style, 0, 0, colour);
    }

    /** One end: the anchor angle when the line names one, else the node's own rim. */
    LineArt.Point endPoint(ClientQuestCache.Entry entry, LineArt.Point centre,
                                   LineArt.Point towards, Double anchor) {
        int size = book.nodeSize(entry);
        int screenX = book.nodeScreenX(entry);
        int screenY = book.nodeScreenY(entry);
        // A point along the anchor's direction, then the shape's real rim along it: an anchor on a square
        // or a diamond used to land at half the size, which is inside the shape for any off-axis angle.
        LineArt.Point aim = anchor == null ? towards : LineArt.anchorPoint(centre, size * 2, anchor);
        return LineArt.rimPoint(centre, aim, size,
                (x, y) -> entry.geometry().contains(x, y, screenX, screenY, size));
    }

    /**
     * One styled line between two screen points — the shapes the dependency lines and the edge gesture's
     * rubber line both draw, because a rubber line that bent by different rules than the real ones would
     * promise a route the real line would not take.
     *
     * <p>The path comes from {@link LineArt}, which is the same function the hover and the right-click
     * hit test call: that is what makes a line you can see a line you can click, whatever its form.
     */
    private static void drawStyled(GuiRenderer r, DependencyStyle style, LineArt.Point from,
                                   LineArt.Point to, int fromHalf, int toHalf, int colour) {
        drawStyledPath(r, QuestBookScreen.stylePath(style, from, to), style, fromHalf, toHalf, colour);
    }

    /**
     * A line's ink with every brightening on it: the direct hover's, the hovered node's, and the
     * steady glow's.
     *
     * <p>The direct hover is the cue that a right-click lands here, and it stays the loudest. The
     * other two name the line's place in the graph instead: the lines joined to the quest under the
     * pointer, and the lines joined to the quest being read or solely selected. All three ease --
     * the trackers crossfade, so a sweep hands the glow over and opening a quest fades it up --
     * and {@code shade} lightens towards white without picking a hue, so a completed line stays
     * recognisably completed underneath.
     */
    private int lineInk(int base, String from, String to, long now) {
        float glow = lineGlow(from, to, now);
        return glow <= 0F ? base : Colour.shade(base, glow);
    }

    /**
     * How much glow reaches one line, before it becomes ink: the direct hover's share or the
     * brightest neighbour's, whichever is louder.
     *
     * <p>Beside {@link #lineInk} because the pulse needs the amount rather than the colour: its
     * packets fade in and out with the same crossfade the ink does, so a sweep hands the flow over
     * instead of blinking it.
     */
    private float lineGlow(String from, String to, long now) {
        float direct = edgeHover.amount(List.of(from, to), now) * BookRewards.LINE_HOVER_BRIGHTEN;
        float beside = Math.max(adjacency(nodeHover, from, to, now),
                adjacency(selectionGlow, from, to, now)) * BookRewards.ADJACENT_LINE_BRIGHTEN;
        return Math.max(direct, beside);
    }

    /**
     * The direction cue on a glowing line: bright comets travelling prerequisite to dependent.
     *
     * <p>An overlay over the stamped geometry, never a rebuild of it: the route's rectangles stay as
     * the frame built them and a few dots ride above, only while the line glows, so quiet chapters
     * pay nothing. The packets ease in with the hover (their ink carries the same glow), stop short
     * of the arrowhead (which already says the direction, statically), and read the same at every
     * zoom: routes are measured in screen pixels and short ones carry short comets rather than
     * nothing, so pulling back keeps the flow instead of losing it. Motion off still stills
     * everything, which is the reduced-motion promise.
     */
    private void drawEdgePulse(GuiRenderer r, QuestBookScreen.FrameEdge edge, long now, int panDx, int panDy) {
        float glow = lineGlow(edge.fromId(), edge.toId(), now);
        if (glow < 0.05F || !ClientAppearance.LOOK.motion()) {
            return;
        }
        List<LineArt.Point> path = edge.path(panDx, panDy);
        double length = LineArt.length(path);
        double travel = length - EdgePulse.ARROW_KEEP;
        double len = EdgePulse.packetLength(length);
        List<Double> centers = EdgePulse.centers(length, now, edgeSeed(edge));
        if (centers.isEmpty() || len <= 0) {
            return;
        }
        int hue = Colour.shade(edge.baseColour(), Math.min(1F, glow + 0.35F));
        for (double center : centers) {
            for (double s = center - len; s <= center; s += 2) {
                if (s < 0 || s > travel) {
                    continue;
                }
                // 0 at the tail, 1 at the head: the packet brightens towards arrival.
                double t = (s - (center - len)) / len;
                LineArt.Point p = LineArt.pointAt(path, s);
                r.fill(p.x() - 1, p.y() - 1, p.x() + 1, p.y() + 1,
                        Colour.translucent(hue, EdgePulse.packetAlpha(glow, t)));
            }
        }
    }

    /** Per-edge variety for the pulse loops, so neighbouring lines do not march in lockstep. */
    private static int edgeSeed(QuestBookScreen.FrameEdge edge) {
        return (edge.fromId() + '>' + edge.toId()).hashCode();
    }

    /**
     * How much of a tracker's glow reaches one line: the eased amount when the tracker names an end
     * of it, the fade-out included.
     *
     * <p>Both ends of the crossfade, because a line the pointer just left should dim as the one
     * arrived at lights -- a glow that snapped off would stutter where the ring glides. A link id
     * never matches an end, so a marker's hover reaches no line without being asked not to.
     */
    private static float adjacency(Hover tracker, String from, String to, long now) {
        float glow = 0F;
        Object current = tracker.current();
        if (current instanceof String id && LineArt.touches(from, to, id)) {
            glow = Math.max(glow, tracker.amount(id, now));
        }
        Object previous = tracker.previous();
        if (previous instanceof String id && LineArt.touches(from, to, id)) {
            glow = Math.max(glow, tracker.amount(id, now));
        }
        return glow;
    }

    /**
     * The line nearest the pointer — recomputed only when the pointer, the view or the geometry moved.
     *
     * <p>{@link LineArt#nearest} reads every point of every candidate route, and in edit mode it ran on
     * every frame whether anything had changed or not. A still pointer over a still canvas has the same
     * nearest line as it did a frame ago, so the answer is kept until one of the things that can change
     * it does: the pointer, the pan, the zoom, the tree revision (a reload or a chapter switch) or a live
     * gesture (a node or handle being dragged moves the geometry without moving the view).
     *
     * <p>The cached value is the edge key only. The hover's brightening is applied where the ink is
     * chosen, so the fade still animates while the answer stands.
     */
    private String[] lineHover(List<QuestBookScreen.FrameEdge> edges, double mouseX, double mouseY, int panDx, int panDy) {
        boolean moving = book.draggedNode != null || book.bendDragFrom != null || book.edgeDragLive;
        float scale = book.viewport().scale();
        int panX = book.viewport().offsetX();
        int panY = book.viewport().offsetY();
        long revision = ClientQuestCache.treeRevision();
        // The two the geometry also depends on, and they are not optional -- see the note below.
        long draft = QuestBookScreen.fieldDraft.version();
        long editors = book.editors.epoch();
        boolean same = !moving && !lastLineHoverMoving
                && mouseX == lastLineHoverX && mouseY == lastLineHoverY
                && scale == lastLineHoverScale && panX == lastLineHoverPanX && panY == lastLineHoverPanY
                && edges.size() == lastLineHoverEdges && revision == lastLineHoverRevision
                && draft == lastLineHoverDraft && editors == lastLineHoverEditors;
        if (same) {
            return lastLineHover;
        }
        lastLineHoverX = mouseX;
        lastLineHoverY = mouseY;
        lastLineHoverScale = scale;
        lastLineHoverPanX = panX;
        lastLineHoverPanY = panY;
        lastLineHoverEdges = edges.size();
        lastLineHoverRevision = revision;
        lastLineHoverDraft = draft;
        lastLineHoverEditors = editors;
        lastLineHoverMoving = moving;
        lastLineHover = LineArt.nearest(frameCandidates(edges, panDx, panDy), mouseX, mouseY, BookRewards.LINE_HIT);
        return lastLineHover;
    }

    /**
     * The line a hand is bending right now, rebuilt from the live preview — or null when none is, or when
     * the drag has taken it somewhere it cannot be drawn from.
     *
     * <h2>Why this exists, and the assumption it corrects</h2>
     *
     * <p>{@code frameEdge} is the only thing that reads {@link QuestBookScreen#styleFor}, and it runs inside
     * {@code frameEdges}, which runs inside {@code stampCanvas} — which returns early unless its key moved.
     * The preview is not in that key, so during a bend drag the line kept the route it was stamped with and
     * moved only when the release changed something the key does name: the draft, or the tree. That is the
     * whole of "it only renders when I let go", and it is why the hit test was live while the drawing was
     * not — {@code handleAt} and {@code edgeAt} call {@code styleFor} directly.
     *
     * <p><b>One edge, not a wider key.</b> Putting the preview into {@code CanvasState} would re-stamp every
     * edge's route, the visible-quest filter and the whole label pass — a box per node and a collision sort
     * — on every mouse move, which is the cost that cache exists to avoid. A bend moves one line, so one
     * line is what gets rebuilt.
     */
    private QuestBookScreen.FrameEdge liveBendEdge() {
        if (book.bendDragFrom == null || book.bendDragTo == null) {
            return null;
        }
        ClientQuestCache.Entry dependency = book.entryFor(book.bendDragFrom);
        ClientQuestCache.Entry dependent = book.entryFor(book.bendDragTo);
        if (dependency == null || dependent == null) {
            return null;
        }
        // The completion colour is the committed edge's, so a preview cannot change what a line *means* --
        // only where it goes. Falling back to the undimmed ink is for the case where the stamped edge is
        // not in the list at all, which `frameEdge` then re-decides anyway.
        int baseColour = ArmatureTheme.line();
        for (QuestBookScreen.FrameEdge edge : canvasEdges) {
            if (edge.matches(book.bendDragFrom, book.bendDragTo)) {
                baseColour = edge.baseColour();
                break;
            }
        }
        return frameEdge(dependency, dependent, book.bendDragFrom, baseColour, false);
    }

    /**
     * The lines to draw this frame: the stamped ones, with the bent line rebuilt from the live preview.
     *
     * <p>One seam, because the ink, the hover's candidate walk and the handle layer all read the same list
     * and all three have to agree — a handle drawn on the committed route while the line follows the
     * pointer is a handle in the wrong place. The copy is references and only happens while a bend drag is
     * live, so the common frame pays nothing.
     */
    private List<QuestBookScreen.FrameEdge> drawnEdges() {
        if (book.bendDragFrom == null || book.bendDragTo == null) {
            return canvasEdges;
        }
        QuestBookScreen.FrameEdge bent = liveBendEdge();
        List<QuestBookScreen.FrameEdge> live = new ArrayList<>(canvasEdges);
        for (int i = 0; i < live.size(); i++) {
            if (!live.get(i).matches(book.bendDragFrom, book.bendDragTo)) {
                continue;
            }
            if (bent == null) {
                // The drag has taken the line off the canvas, or made it unreachable: it goes, rather than
                // staying at the route it was stamped with. `frameEdge` is what decided that.
                live.remove(i);
            }
            else {
                live.set(i, bent);
            }
            break;
        }
        return live;
    }

    /**
     * The link under the pointer, by id, or null.
     *
     * <p>After the nodes and before the elements: a node owns the press over a marker, and a marker
     * owns it over a decoration — the pick order is the draw order read backwards, so what a click
     * opens is what was on top. The shape test is the art's, because a link is a node where the
     * pointer is concerned.
     */
    String linkAtId(double mouseX, double mouseY) {
        int dx = panDx();
        int dy = panDy();
        List<QuestLinkArt.Slot> slots = new ArrayList<>(canvasLinks.size());
        for (LinkSlot slot : canvasLinks) {
            slots.add(slot.shifted(dx, dy).slot());
        }
        QuestLinkArt.Slot hit = QuestLinkArt.at(slots, mouseX, mouseY);
        return hit == null ? null : hit.link().id();
    }

    /** One stamped marker, by link id, or null when the stamp holds none by that name. */
    LinkSlot linkSlot(String id) {
        if (id == null) {
            return null;
        }
        int dx = panDx();
        int dy = panDy();
        for (LinkSlot slot : canvasLinks) {
            if (id.equals(slot.slot().link().id())) {
                return slot.shifted(dx, dy);
            }
        }
        return null;
    }
}
