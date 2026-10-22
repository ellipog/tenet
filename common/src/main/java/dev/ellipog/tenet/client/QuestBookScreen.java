package dev.ellipog.tenet.client;

import dev.ellipog.armature.api.client.ArmatureClient;
import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.armature.client.ArmatureButton;
import dev.ellipog.armature.client.ArmatureTextArea;
import dev.ellipog.armature.client.ArmatureTextField;
import dev.ellipog.armature.client.ArmatureScrollStyle;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.Tooltips;
import dev.ellipog.armature.client.ui.ArmatureLive;
import dev.ellipog.armature.client.ui.ArmatureScreen;
import dev.ellipog.armature.client.ui.Theme;
import dev.ellipog.armature.client.ui.Themes;
import dev.ellipog.armature.client.ui.CanvasBackground;
import dev.ellipog.armature.client.render.GuiGraphicsRenderer;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.render.TextEpoch;
import dev.ellipog.armature.client.ui.inspect.InspectLayout;
import dev.ellipog.armature.client.ui.inspect.InspectRow;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Hover;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Motion;
import dev.ellipog.armature.client.ui.kit.ScrollBar;
import dev.ellipog.armature.client.ui.kit.ScrollView;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Stack;
import dev.ellipog.armature.client.ui.kit.TextWrap;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.armature.client.ui.party.PartyRoster;
import dev.ellipog.tenet.QuestAuthority;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.editor.EditorOp;
import dev.ellipog.tenet.net.EditorReplyPayload;
import dev.ellipog.tenet.net.TenetNetworking;
import dev.ellipog.tenet.client.ClientChapterReplica;
import dev.ellipog.tenet.client.ClientEditReplies;
import dev.ellipog.tenet.client.viewer.QuestBookFocus;
import dev.ellipog.tenet.client.viewer.RecipeLookups;
import dev.ellipog.tenet.quest.CanvasElement;
import dev.ellipog.tenet.quest.QuestLink;
import dev.ellipog.tenet.client.dev.ChapterPanelLayout;
import dev.ellipog.tenet.client.dev.ChapterTheme;
import dev.ellipog.tenet.client.dev.CanvasReveal;
import dev.ellipog.tenet.client.dev.ClientEditorClipboard;
import dev.ellipog.tenet.client.dev.EntryFormLayout;
import dev.ellipog.tenet.quest.EditorField;
import dev.ellipog.tenet.quest.EditorSources;
import dev.ellipog.tenet.client.dev.ItemPicker;
import dev.ellipog.tenet.client.dev.ItemPickerLayout;
import dev.ellipog.tenet.client.dev.Labels;
import dev.ellipog.tenet.client.dev.LineArt;
import dev.ellipog.tenet.client.dev.QuestSettingsLayout;
import dev.ellipog.tenet.client.dev.SettingsDraft;
import dev.ellipog.tenet.quest.DependencyStyle;
import dev.ellipog.tenet.quest.QuestShape;
import dev.ellipog.tenet.client.dev.QuestPanelLayout;
import dev.ellipog.tenet.client.dev.SidebarDrag;
import dev.ellipog.tenet.client.dev.ToolsLayout;
import dev.ellipog.tenet.client.dev.ToolsPanel;
import dev.ellipog.tenet.editor.EditorSession;
import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.client.ClientPartyCache;
import dev.ellipog.tenet.net.ClaimRewardPayload;
import dev.ellipog.tenet.net.SubmitTaskPayload;
import dev.ellipog.tenet.progress.QuestState;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;

import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The quest book: a chapter list, a canvas of quests, and a full-screen reading view.
 *
 * <h2>The blur, and how it was caused</h2>
 *
 * <p>The first version of this screen was blurred, along with the world behind it, on both loaders.
 * The cause is not guessable from the symptom, so it is written down. From Minecraft 1.21.1's own
 * source:
 *
 * <pre>
 * Screen.render(g, mx, my, pt) {
 *     renderBackground(g, mx, my, pt);      // &lt;-- this
 *     for (Renderable r : renderables) r.render(...);
 * }
 *
 * Screen.renderBackground(g, mx, my, pt) {
 *     if (minecraft.level == null) renderPanorama(g, pt);
 *     renderBlurredBackground(pt);          // &lt;-- gameRenderer.processBlurEffect(pt)
 *     renderMenuBackground(g);              // &lt;-- MENU_BACKGROUND over everything
 * }
 * </pre>
 *
 * <p>So {@code super.render()} calls {@code renderBackground} whether the subclass wants it or not,
 * and that is where vanilla's menu blur comes from. This screen called {@code super.render()}
 * <b>last</b>, so the blur post-effect processed a framebuffer that already contained this screen's
 * text — which is why the text was soft as well as the world. Overriding {@code renderBackground} to
 * do nothing is the whole fix, and it is one method — which now lives on {@code ArmatureScreen}, so
 * that the next panel cannot forget it: the developer screen did, and shipped washed out.
 *
 * <p><b>And the blur is not drawn on an ordinary frame at all.</b> With vanilla's suppressed, the only
 * blur left was this screen's own, once a frame, and it was the largest single cost in the frame: six
 * full-screen post-process passes at the window's resolution, whether the canvas held one node or a
 * thousand — which is why a blank canvas was not fast. The idle case now draws the theme's dim wash
 * instead, which is one quad, and the blur is kept for an open modal, where it is what makes the card
 * readable. See the two call sites in {@code render} for the measurements behind that.
 *
 * <h2>Three ways to look around</h2>
 *
 * <ul>
 *   <li><b>Scroll</b> zooms, about the pointer — so the thing under the cursor stays under it.</li>
 *   <li><b>Hold left and drag</b> pans. A press that does not move is a click, and a click on a node
 *       selects it — so selecting and panning are the same gesture, told apart by whether the pointer
 *       moved. Nothing is selected on press, only on release.</li>
 *   <li><b>Hold middle and drag</b> pans from anywhere, including over a node, so it can never be
 *       read as a click.</li>
 * </ul>
 *
 * <h2>The editor's drag, in four phases</h2>
 *
 * <p>In developer mode a left press on a node picks it up instead of panning, and the same gesture
 * runs <b>press, threshold, follow, release</b>: the press reads the node's position through the one
 * reader ({@code nodeX}/{@code nodeY}) before claiming it; the threshold (four screen pixels, the pan's
 * own) separates a click from a drag, so a click never twitches the node; the follow puts the grabbed
 * point under the pointer and lands it on {@link BookGeometry#SNAP_GRID} while the Snap switch is on —
 * Alt held asks for free placement, per event; the release commits exactly the position the canvas was
 * last drawn showing. One edit per drag, one write per drag.
 *
 * <p><b>The modifiers are the selection's, and every button has one meaning.</b> Left is select,
 * drag-move and pan; right is the dependency edge; middle pans. Shift-click adds a node to the
 * selection (and the press still picks it up, so shift-drag moves the whole selection); Ctrl-click
 * toggles one in or out; a click on the empty canvas lets go of everything; a shift-drag on the empty
 * canvas stretches the additive marquee. Modifiers are read at press and remembered, so letting go of
 * a key a moment before the mouse comes up cannot turn one gesture into another.
 *
 * <h2>Layout, and the bug that made this a separate concern</h2>
 *
 * <h2>The selected chapter is a fill, and getting that wrong is worth knowing about</h2>
 *
 * <p>The chapter list marked the current chapter with {@code .flat(true)} — no fill, no border — on the
 * reasoning that a control with nothing behind it stands out among controls that have something. It does
 * stand out, and as the wrong thing: in a list of filled rows, the one with no box reads as the
 * <i>disabled</i> or missing entry. A selection has to be something that is there, not something that is
 * absent. It is {@code .selected(true)} now, and {@code ArmatureControlStyle} decides what that looks
 * like so the preview draws the same thing this screen does.
 *
 * <p>The first working version drew two controls on top of each other in the bottom-right corner. The
 * cause was writing "near the bottom right" twice, as two different expressions that were not equal:
 *
 * <pre>
 * Open: canvasRight() - 78,  canvasBottom() + 11
 * Done: panelLeft() + panelWidth() - 68,  panelTop() + panelHeight() - 24
 * </pre>
 *
 * <p>The two x values were 10px apart and the two y values ~15px apart — a collision, and the reason
 * every position that the drawing and the controls both need now comes from a helper method:
 * {@link #footerRow1Y()}, {@link #footerRow2Y()},
 * {@link #stripButtonX()}, {@link #stripButtonY()}, {@link #sidebarViewport()}. One expression, used twice,
 * rather than two expressions that happen to agree until someone changes one.
 *
 * <h2>Every colour is eight digits</h2>
 *
 * <p>{@code 0xAARRGGBB}, alpha included, and all of them live in {@link ArmatureTheme}. On 1.21.1 a
 * colour written without alpha happens to come out opaque; from 1.21.6 it does not.
 */
public final class QuestBookScreen extends ArmatureScreen
        implements dev.ellipog.tenet.client.viewer.ItemDropTarget {

    // ------------------------------------------------------------------
    // Layout constants. See the class comment for why these are shared.
    // ------------------------------------------------------------------

    // Every one of these is an alias, not a number. The number lives in BookGeometry, and this class
    // reads it from there -- because a second copy of "the sidebar is 132 wide" is a copy that can
    // disagree with the one the overlap test checks. That disagreement is precisely how two buttons
    // ended up drawn on top of each other: "near the bottom right" written twice.
    //
    // There is deliberately no alias for the sidebar's width any more: the list is the player's
    // width now, read from the geometry that resolved it, and an alias would be a second copy of a
    // number that moves.
    private static final int HEADER_HEIGHT = BookGeometry.HEADER_HEIGHT;
    private static final int OVERLAY_MARGIN = BookGeometry.OVERLAY_MARGIN;

    /** The sidebar's footer, as two rows of its own. Four controls do not fit across one. */
    private static final int ROW_HEIGHT = BookGeometry.ROW_HEIGHT;
    private static final int ROW_GAP = BookGeometry.ROW_GAP;

    /** The quest book's overlay and choice offer. See {@link BookOverlay}. */
    final BookOverlay bookOverlay = new BookOverlay(this);

    /** The quest book's settings card. See {@link BookSettings}. */
    final BookSettings bookSettings = new BookSettings(this);

    /** The quest book's first-run intro. See {@link BookIntro}. */
    final BookIntro bookIntro = new BookIntro(this);

    /** The quest book's pins. See {@link BookPins}. */
    final BookPins bookPins = new BookPins(this);

    /** The quest book's Assets panel. See {@link BookAssets}. */
    final BookAssets bookAssets = new BookAssets(this);

    /** The quest book's task and reward rows. See {@link BookQuestRows}. */
    final BookQuestRows bookQuestRows = new BookQuestRows(this);

    /** The quest book's editor controls. See {@link BookEditorControls}. */
    final BookEditorControls editorControls = new BookEditorControls(this);

    /** The quest book's quest panel edits. See {@link BookQuestEditor}. */
    final BookQuestEditor questEditor = new BookQuestEditor(this);

    /** The quest book's item picker. See {@link BookItemPicker}. */
    final BookItemPicker itemPicker = new BookItemPicker(this);

    /** The quest book's canvas. See {@link BookCanvas}. */
    final BookCanvas bookCanvas = new BookCanvas(this);

    /** The quest book's input. See {@link BookInput}. */
    final BookInput bookInput = new BookInput(this);

    /** The quest book's sidebar. See {@link BookSidebar}. */
    final BookSidebar bookSidebar = new BookSidebar(this);

    /** The quest book's right-click menus. See {@link BookMenu}. */
    final BookMenu bookMenu = new BookMenu(this);

    /** The quest book's rewards. See {@link BookRewards}. */
    final BookRewards bookRewards = new BookRewards(this);

    /** The quest book's texture picker. See {@link BookTexturePicker}. */
    final BookTexturePicker texturePicker = new BookTexturePicker(this);

    /** The quest book's row drag. See {@link BookRowDrag}. */
    final BookRowDrag rowDrag = new BookRowDrag(this);

    /**
     * The layout, rebuilt when the window changes size.
     *
     * <h2>Why the screen does not compute its own coordinates any more</h2>
     *
     * <p>Because it did, and the arithmetic was untestable. {@code QuestBookScreen} extends
     * {@code Screen}, so it needs a running Minecraft to instantiate — which means a test can never ask
     * it where its controls are, and "these two do not overlap" cannot be asserted at all. That is not
     * a gap in the tests, it is the reason the bug got as far as a screenshot.
     *
     * <p>So the positions come from {@link BookGeometry}, which has no Minecraft in it and can be
     * tested (see {@code BookGeometryTest}). Rebuilt on demand rather than in {@code init()} because
     * {@code render} and {@code mouseClicked} both need it, and neither is guaranteed to run after the
     * other — a cache keyed on the size is correct however the call order falls.
     */
    private BookGeometry geometry;
    private int geometryWidth = -1;
    private int geometryHeight = -1;
    /** Whether the cached geometry was built for an author. See {@link #geometry}. */
    private boolean geometryAuthor;
    /**
     * The sidebar state the cached geometry was built from. See {@link #sidebarEffective}: a
     * toggle that left the geometry cached would draw yesterday's rectangles under today's
     * widgets.
     */
    private SidebarState geometrySidebar = SidebarState.DEFAULT;

    /**
     * Per-frame snapshot of the geometry, canvas viewport, permission check, and active chapter.
     *
     * <p>Captured once at the top of {@link #extractRenderState} (and {@link #renderWith} when driven
     * directly by a test), refreshed if the frame triggers a widget rebuild, and cleared when the frame
     * finishes or {@link #init} runs. Without this, {@link #viewport} and {@link #geometry} re-ran
     * permission checks and allocated {@link SidebarState} records hundreds of times per frame.
     */
    private boolean frameActive;
    private BookGeometry frameGeometry;
    private Viewport frameViewport;
    private boolean frameMayEdit;
    static boolean frameChapterActive;
    private static String frameChapter;

    private void snapshotFrame() {
        frameActive = false;
        frameChapterActive = false;
        frameMayEdit = dev.ellipog.tenet.QuestAuthority.mayEditClient(minecraft);
        boolean author = frameMayEdit && !previewAsPlayer;
        SidebarState sidebar = sidebarEffective();
        if (geometry == null || geometryWidth != width || geometryHeight != height
                || geometryAuthor != author || !geometrySidebar.equals(sidebar)) {
            geometry = new BookGeometry(width, height, author, author, sidebar);
            geometryWidth = width;
            geometryHeight = height;
            geometryAuthor = author;
            geometrySidebar = sidebar;
        }
        frameGeometry = geometry;
        BookGeometry.Rect canvas = frameGeometry.canvas();
        frameViewport = VIEW.bounds(canvas.x(), canvas.y(), canvas.width(), canvas.height());
        frameChapter = computeEffectiveChapter();
        frameActive = true;
        frameChapterActive = true;
    }

    private void clearFrameSnapshot() {
        frameActive = false;
        frameChapterActive = false;
        frameGeometry = null;
        frameViewport = null;
    }

    /**
     * The chapter list's effective state: the file's answers, with a held drag's live width over
     * the stored one, docked forced expanded, and a hover-peek resolved to what it draws as.
     *
     * <p>One resolver for the geometry, the widget build and the press dispatch, so the three
     * cannot disagree about whether the list is docked, floating, or a sliver being hovered. The
     * file is read live rather than cached: {@code DevMode} is the owner, and a second copy of
     * its answers here is a copy that can disagree with it.
     */
    private SidebarState sidebarEffective() {
        SidebarState stored = DevMode.sidebarState();
        int width = sidebarLiveWidth != null ? sidebarLiveWidth : stored.width();
        if (!stored.floating()) {
            // Docked is the old column, always expanded: the old mode cannot collapse, whatever
            // the file's other answers say.
            return new SidebarState(width, false, true, false);
        }
        if (stored.collapsed() && sidebarPeeking) {
            // A peek draws as a floating expanded box: the full rows over the canvas, with
            // nothing written. Lifting the pointer resolves back to the sliver on its own.
            return new SidebarState(width, true, stored.pinned(), false);
        }
        return new SidebarState(width, true, stored.pinned(), stored.collapsed());
    }

    /**
     * The two things about the geometry that are not the window: how much of it the book takes, and whether
     * the panel keeps a strip for the author's band.
     *
     * <h2>Why these are remembered at all</h2>
     *
     * <p>Because both are read live from the player's permission and the size guard above cannot see them:
     * the first version of this cache compared the window alone, so a player de-opped with the book open
     * kept the full-bleed geometry they had as an author -- and the band's controls with it, drawn over a
     * panel that no longer reserved their strip. The two flags are what the cache key was missing, and they
     * are the reason the key is three comparisons rather than one.
     */
    BookGeometry geometry() {
        if (frameActive && frameGeometry != null) {
            return frameGeometry;
        }
        // A previewing operator gets the reader's geometry: the card, no band, no full-bleed. That is
        // the whole point of the preview — the panel shrinks to exactly what a player without the
        // permission sees — and the way back out is the floating pill `buildViewCluster` puts over the
        // canvas, not the band, which this geometry does not have.
        boolean author = mayEdit() && !previewAsPlayer;
        SidebarState sidebar = sidebarEffective();
        if (geometry == null || geometryWidth != width || geometryHeight != height
                || geometryAuthor != author || !geometrySidebar.equals(sidebar)) {
            // Full-bleed for an author, a card for a reader, and a band for the same author. See
            // `BookGeometry`'s constructor: the margin and the maximum size exist so a reading panel does
            // not read as a wall, and an author is not reading -- every spare pixel is canvas or colour
            // list.
            geometry = new BookGeometry(width, height, author, author, sidebar);
            geometryWidth = width;
            geometryHeight = height;
            geometryAuthor = author;
            geometrySidebar = sidebar;
        }
        return geometry;
    }

    /**
     * Item boxes, and the row pitch derived from them.
     *
     * <h2>Why these are constants and not numbers at the call sites</h2>
     *
     * <p>Because the boxes and the item inside them were sized independently and disagreed. A node's
     * box scales with the zoom, but the item in it was pinned at vanilla's 16px and centred in
     * whatever space was left — so at 100% a 26px node held a 16px item, and at 220% a 105px node held
     * the same 16px item, which reads as a rendering fault rather than as a small picture. And a row's
     * pitch was 13px while the icon in it was 16px tall, so consecutive icons overlapped.
     *
     * <p>So one number per box, and everything around it derived: the row pitch, the gap under an icon
     * and the box an icon fills are {@link OverlayLayout}'s, and this screen reads them from there —
     * because those same numbers are what the scrollbar's range is computed from, and a copy here is a
     * copy that can disagree with the height the layout reports.
     *
     * <p>{@link #NODE_INSET} stays: it is about a node on the canvas, which no layout knows about.
     */
    private static final int NODE_INSET = 3;

    /** The item box in an overlay row. The layout reserved this box; the icon fills it. */
    static final int ROW_ICON = OverlayLayout.ROW_ICON;

    static final int HEADER_ICON = 20;

    /**
     * Where the overlay's body sits inside its surface: inset from the sides, and the stops above and
     * below it.
     *
     * <p>Above is the header's height plus a gap; below is the footer's rule plus its own. The drawing
     * and the clip used to write these numbers out separately — {@code left + 18} and {@code top + 54}
     * in two places each — which is two expressions for one rectangle, and the same class of mistake as
     * the two controls that were once drawn on top of each other, one surface up.
     *
     * <p><b>The values live in {@link PanelLayout} now, and these are aliases.</b> A centred card and a
     * docked column present the same content, so they have to measure the same three stops or the content
     * would shift as the mode is switched — which is the one promise the second presentation makes.
     * {@code PanelLayout} owns them because it is the layout a test can reach; this class keeps the names
     * its own drawing reads, so the drawing did not change when the numbers moved.
     */
    private static final int BODY_INSET = PanelLayout.BODY_INSET;
    static final int BODY_TOP = PanelLayout.BODY_TOP;

    /** The settings card's width. Narrower than the modal: two rows do not want a wide card. */
    private static final int SETTINGS_CARD_WIDTH = 340;
    private static final int BODY_BOTTOM = PanelLayout.BODY_BOTTOM;

    /**
     * The claim menu's card: the widest a card gets, and a body that starts below a taller band.
     *
     * <p>The band is taller than {@link #BODY_TOP} because it carries the state toggles as well as the
     * two lines of title — the title, the subtitle, and the toggle row under them. Named here rather
     * than written at the three call sites that need it, which is exactly what {@link #BODY_TOP}'s own
     * note is about: one rectangle described twice is one rectangle that will disagree with itself.
     */
    private static final int REWARDS_CARD_WIDTH = BookGeometry.MAX_WIDE_MODAL_WIDTH;

    /**
     * How much body an empty view reserves for its one line of explanation.
     *
     * <p>Named because the card's floor is {@code MIN_MODAL_HEIGHT} (120) and the band plus the footer's
     * chrome is 118 — so an empty card that asked for no body at all got <b>two pixels</b> of one, and
     * the hint was drawn outside it. A line of text is eight pixels tall; ten leaves it a pixel clear of
     * the footer band above and below.
     */
    private static final int EMPTY_HINT_HEIGHT = 10;

    /**
     * The zoom range: how far the view may be pulled back, and how far pushed in.
     *
     * <p>The floor is 0.2 rather than the 0.35 it was first, so a sprawling chapter can be taken in at
     * once. Nothing else has to agree with it: labels are dropped by measured room rather than by a zoom
     * threshold -- see the note above {@link BookCanvas#MAX_LABEL_WIDTH} -- so a further-out view reads as fewer
     * names, not as a broken one.
     */
    private static final float MIN_ZOOM = 0.2F;
    private static final float MAX_ZOOM = 2.2F;

    /**
     * How far the pointer must move before a press counts as a drag rather than a click.
     */
    static final double DRAG_THRESHOLD = 4.0;

    /**
     * The longest the panel's arrival may take, whatever the theme's motion asks for.
     *
     * <p>A cap rather than a duration: the theme's own motion decides, and this only stops a long one
     * from being felt as a wait. It matters because a press landing mid-slide is answered by a control
     * that is still travelling to its spot — so the window in which every panel widget is inert is
     * exactly this long, and with motion off the window is not there at all.
     */
    private static final int PANEL_REVEAL_MAX = 180;
    final Hover rowHover = new Hover();

    static String selectedChapter;
    static String selectedQuest;

    /**
     * The quests selected besides the primary one, which is {@link #selectedQuest}.
     *
     * <p>Static with the selection it extends, for the same reason: a marquee that survives reopening
     * the book is the selection an author put down and walked away from. The panel edits the primary
     * alone -- a property panel over six quests at once is six panels pretending to be one -- so the
     * multi-selection exists for the canvas's whole-hand gestures: drag them together, delete them
     * together, copy them together.
     */
    static final Set<String> multiSelection = new LinkedHashSet<>();

    /**
     * The marquee: where the rectangle started, and where the pointer is now, in screen coordinates.
     *
     * <p>Screen coordinates because the rectangle is drawn as it is dragged and a zoom mid-drag should
     * not move what the author is holding; the selection test at release maps each node into the same
     * space, so the rectangle and the test it feeds agree about what "inside" means.
     */
    boolean marqueeActive;
    int marqueeX0;
    int marqueeY0;
    int marqueeX1;
    int marqueeY1;

    /**
     * The quest an edge drag is carrying from, once the drag is live.
     *
     * <p>Right is the edge's button: a right-press on a node that never moves does nothing, and the
     * same threshold that tells a click from a node drag tells this one from that. A live edge drag
     * draws the line to the pointer and lands on whatever node is under the release -- the arrow
     * points the way the drag went: from the prerequisite to the quest that depends on it.
     */
    String edgeDragFrom;
    boolean edgeDragLive;
    double edgeDragX;
    double edgeDragY;

    /** Whether the next canvas click adds a dependency instead of selecting. Escape cancels. */
    boolean pickingDependency;

    /** What the pick is for, and the rules a click follows: see {@code DependencyPick}. */
    dev.ellipog.tenet.client.dev.DependencyPick pendingPick;

    /**
     * Whether the drag in progress began on the open field's own frame, so it extends the field's mark
     * rather than panning the canvas or moving a node. Set by the press the field took -- the same test
     * that places the caret -- and cleared when it is let go.
     */
    private boolean fieldDrag;

    /** One block of the reader's description: what it is, the lines it was wrapped to, and the token look over it. */
    record ProseBlock(dev.ellipog.armature.client.ui.kit.RichText.Paragraph paragraph, List<dev.ellipog.armature.client.ui.kit.RichText.Line> lines,
                              List<RichText.Span> spans, RichText.PlacedImage image) {
    }

    /**
     * A link as it was drawn: its rectangle on screen, and where it goes — a URL, a quest id,
     * never both. A quest id is the legacy {@code change_page} repurposing, resolved by id or alias
     * when it is pressed rather than when it is drawn.
     */
    record LinkRect(BookGeometry.Rect box, String url, String quest) {
    }

    /**
     * The reader's description, parsed and wrapped for this frame.
     *
     * <p>One parse serves three things -- the layout takes the lines and widths, the drawing takes the runs,
     * and the link rectangles are cut from what the drawing did -- so they cannot disagree about what the
     * prose says or where it is. Rebuilt where the reader's card is drawn, which is once a frame and the
     * same cost class as the wrap it replaces.
     */
    final List<ProseBlock> readerProse = new ArrayList<>();

    /** What the reader's card drew this frame: the targets a hover and a click are matched against. */
    final List<LinkRect> linkRects = new ArrayList<>();

    /** The link the press landed on, opened when it is released on the same rectangle. */
    private LinkRect pressedLink;

    /**
     * Where every node of a dragged selection started, in content coordinates.
     *
     * <p>Captured when the drag goes live, because a drag moves the selection <i>together</i> and a
     * delta can only be taken against where things were. The pressed node's own start is
     * {@link #dragStartX}; this carries the rest.
     */
    private final Map<String, float[]> dragStarts = new LinkedHashMap<>();
    private float dragStartX;
    private float dragStartY;

    /**
     * The chapter whose missing theme was last reported, so the warning is not repeated every frame.
     *
     * <h2>What this guards, and what it used to</h2>
     *
     * <p>It used to guard the <i>application</i> of a chapter's theme, because applying one was a global
     * side effect that {@link #init} would otherwise repeat on every resize and every widget rebuild.
     * A chapter's theme is now applied by opening a scope around the canvas and the overlay, which costs
     * nothing to re-open and resets no tween — so there is nothing left to guard except the message.
     *
     * <p>The message does still need guarding: it is emitted from inside a per-frame drawing path, and a
     * chapter with a misspelled theme name would otherwise write a line of log per frame, which buries
     * everything else in it. Once per chapter, cleared on disconnect, is the useful amount.
     */
    private static String warnedThemeFor;

    /**
     * The canvas transform: pan, zoom, the mapping from a quest's own coordinates to the screen, and
     * the rectangle currently visible. Shared across openings, like the selection above it.
     *
     * <h2>Where the four things that used to be here went</h2>
     *
     * <p>This was {@code float zoom}, {@code int panX}, {@code int panY} and the bound arithmetic
     * beside them — and every place that needed a coordinate wrote the conversion out itself: the two
     * methods that turn a quest into a screen position, the hit test, the pan on drag, the zoom about
     * the pointer, and {@link BookCanvas#centreCanvas}, which solved the same equation a second time from the
     * content's bounding box. Five copies of one transform, and the copy that decided what a click
     * selected was the one nothing could check.
     *
     * <p>It is {@link Viewport}'s now — the same object a scrolling list uses at a smaller range, which
     * is why it is in the kit rather than in {@code ui.graph}. Two things follow, and both are the
     * reason rather than a side effect: the zoom-about-the-pointer arithmetic is asserted against a
     * known transform in Armature's own tests instead of being judged in a screenshot, and there is
     * exactly one place in this repo that knows how to map content space onto screen space.
     *
     * <p><b>No content size is ever set on it, deliberately.</b> A canvas may be dragged off into
     * empty space: a player exploring a large questline should not be stopped at an invisible edge, and
     * a chapter's bounding box is not a wall. A view that wants a clamp asks for one by saying how much
     * content it has; this one never does, so the offset is whatever the drag put there.
     */
    private static final Viewport VIEW = Viewport.of(MIN_ZOOM, MAX_ZOOM);

    static String pannedChapter;
    static boolean centred;

    // --- per-open state ------------------------------------------------------

    PanelKind overlay = PanelKind.NONE;

    /**
     * The second column: what the thing in {@link #overlay} opened, or {@link PanelKind#NONE}.
     *
     * <p>A field beside {@code overlay} rather than a stack object replacing it, and the reason is the
     * hundred branches that read {@code overlay == PanelKind.X}: rewriting them all into accessor calls
     * would be churn in the one file where churn is expensive, and none of them changed meaning. So the
     * two fields carry the arrangement and {@code PanelStack} owns every rule about it —
     * {@link #columns()} and {@link #applyColumns} are the whole of the bridge.
     */
    PanelKind overlay2 = PanelKind.NONE;

    /**
     * Whether the author has the Book/Chapter dock latched.
     *
     * <h2>Why this is a field, and what it stopped being</h2>
     *
     * <p>The dock used to <i>be</i> edit mode: it was derived from it — {@code drawerOpen()} answered
     * {@code mayEditNow()} — so it appeared when the Edit pill latched and went away with it. That gave the
     * Edit pill a second meaning, "close the panel so the dock comes back", because the dock and a panel
     * shared column 1 and the dock was not drawn while a panel was in it. An author pressing Edit to leave
     * edit mode got the panel closed instead, and had to press again to do what they asked for.
     *
     * <h2>And what it stopped being after that</h2>
     *
     * <p>It is the screen's mirror of {@link PanelStack.Columns#dock()}, written by {@link #applyColumns}
     * and by nothing else — {@link #pressAuthorPill} goes through the rules like every other opening, so
     * there is one writer of the arrangement rather than one writer and a latch beside it. That matters
     * because the dock is no longer the fallback occupant of column 1: it owns its own rail, so it is
     * neither hidden behind a panel nor revealed by closing one, and the only thing that may take it away
     * is a press that names it — the pill, the X or Escape.
     *
     * <p>Session-only, like {@link #overlay} — nothing about the arrangement reaches disk.
     */
    boolean dockOpen;

    /**
     * The kind whose surface the four accessors below answer for: what is being drawn, built, or pressed.
     *
     * <h2>Why a field rather than a parameter through thirty methods</h2>
     *
     * <p>Every position inside a panel — its body's clip, its footer's controls, its rows' rectangles — comes
     * from {@code overlayLeft/Top/Width/Height}, which used to read {@code overlay}. With one surface that
     * was the same question; with two it is not, and a kind in the second column would have measured itself
     * against the first column's rectangle. Threading a rectangle through the twenty-odd methods that ask
     * would be the tidier answer and a much larger change; this is one assignment per surface, written by the
     * three places that have one — {@link BookOverlay#drawSurface}, {@link BookQuestEditor#buildOverlayWidgets} and the press dispatch
     * — and each of them sets it immediately before the code that reads it, so it cannot be stale: there is
     * no path that reads these accessors between two surfaces.
     *
     * <p>Defaults to {@link PanelKind#NONE}, which {@code surfaceCard} answers with the modal rectangle —
     * the shape every kind had before this mode existed, so a path that somehow forgot to set it draws what
     * it always drew rather than nothing.
     */
    PanelKind surfaceKind = PanelKind.NONE;

    /** What the player asked the second column to do. Read at startup, written when the fold is pressed. */
    PanelStack.Fold foldChoice = PanelStack.Fold.AUTO;

    /**
     * When each rail started arriving, in {@code Util.getMillis()} terms; no entry means settled.
     *
     * <h2>Per kind, because the dock must arrive once and then stay</h2>
     *
     * <p>There was one timestamp for the whole arrangement, and it was harmless while opening a panel also
     * took the dock's rail: the dock appeared with the panel, so one wipe covered both. The dock now keeps
     * its own rail, so one timestamp means <b>every panel opening re-wipes the dock</b> — the author's tools
     * sliding in again each time they click a node, which reads as the dock closing and reopening, and is
     * the very thing this round exists to stop. A map is what makes "this rail is arriving; that one is
     * already there" sayable.
     *
     * <p>Captured where the rail is opened — <b>at the action</b> — rather than on the next tick.
     * {@code tick} runs at twenty hertz, so a start timestamp read there would eat up to forty-nine
     * milliseconds of the reveal, which is a quarter of it. {@code startGlide} and the
     * locate flash already do it this way for the same reason.
     *
     * <p>{@link #applyColumns} drops the entries of anything that left the arrangement, so a kind closed
     * mid-wipe and opened again later starts its second arrival at the beginning rather than part-way
     * through the first.
     */
    final java.util.Map<PanelKind, Long> panelRevealStarts =
            new java.util.EnumMap<>(PanelKind.class);

    /**
     * Whether a rebuild is owed because a rail arrived while its controls were travelling.
     *
     * <p>Set by the per-frame slide while any rail is still moving: the settle frame leaves every
     * widget at its built position but inert, and the rebuild in {@code tick} is what hands back
     * their live state. A widget list changed inside a render is the one thing this screen never
     * does, so the flag is the whole of the handover between the two.
     */
    boolean panelBuildPending;

    /**
     * Which rail is being built, while it is being built — or null anywhere else.
     *
     * <p>The bucket the widget overrides below file into: a control built by {@link BookQuestEditor#buildColumn} (or
     * the fold control's, or the choice card's) belongs to the rail it was placed against, and the
     * per-frame slide moves exactly those widgets. The header, the sidebar and the view cluster build
     * with this null, so their controls never travel — they are not on a rail that arrives.
     */
    PanelKind slideBucket;

    /**
     * Every sliding widget by rail, and the X it was placed at.
     *
     * <p>Cleared with everything else in {@code init} and filled as the columns build; the bases are
     * read on the first slide frame rather than at filing (see {@link #slidePanelWidgets}). The slide
     * never writes a placed position — it writes {@code base + offset} every frame — so a rebuild for
     * any other reason (a replica arriving mid-slide, a press) cannot strand a widget off its rail: the
     * next frame re-derives the same answer from the same base.
     */
    final java.util.Map<PanelKind, java.util.List<AbstractWidget>> slideWidgets =
            new java.util.EnumMap<>(PanelKind.class);
    final java.util.Map<AbstractWidget, Integer> slideBaseX =
            new java.util.IdentityHashMap<>();

    /**
     * Files a widget under the rail being built, when one is.
     *
     * <p>Called by both widget overrides: every panel widget is an {@code AbstractWidget} with a
     * position to travel from, and anything else (there is nothing else today) is simply not slid.
     * Only the filing happens here — the base X is read on the first slide frame (see
     * {@link #slidePanelWidgets}), because a field built for a scrolling list is constructed at
     * 0,0 and placed by its view afterwards. Filing the construction position would pin every one
     * of those fields at the window's edge for as long as the panel is open.
     */
    private void noteSlideWidget(GuiEventListener added) {
        if (slideBucket == null || !(added instanceof AbstractWidget widget)) {
            return;
        }
        slideWidgets.computeIfAbsent(slideBucket, kind -> new java.util.ArrayList<>()).add(widget);
    }

    /**
     * The travelling widgets filed under a rail, filing an empty list when the rail has none.
     *
     * <p>Filed rather than defaulted: a full-screen rebuild clears this map without touching
     * cards whose controls rebuild lazily, so the first claim press after a rebuild found no
     * entry and died removing from {@code List.of()}. The returned list is always mutable and
     * always the map's own, so a removal below cleans up either way. Pure in everything but
     * the map it is handed, so a test can hold the crash shape headlessly.
     */
    static java.util.List<AbstractWidget> slideList(
            java.util.Map<PanelKind, java.util.List<AbstractWidget>> filed, PanelKind kind) {
        return filed.computeIfAbsent(kind, missing -> new java.util.ArrayList<>());
    }

    @Override
    protected <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(
            T widget) {
        T added = super.addRenderableWidget(widget);
        noteSlideWidget(added);
        return added;
    }

    @Override
    protected <T extends GuiEventListener & NarratableEntry> T addWidget(T widget) {
        T added = super.addWidget(widget);
        noteSlideWidget(added);
        return added;
    }

    /**
     * When each departed rail started leaving, in {@code Util.getMillis()} terms.
     *
     * <p>The exit's answer to {@link #panelRevealStarts}: a rail that left the arrangement keeps
     * drawing — sinking into the edge it arrived from — until its duration is spent. Recorded in
     * {@link #applyColumns}, the one writer of the arrangement, beside the arrival stamps; forgotten
     * in {@code tick} once settled, in {@code init} on any rebuild, and in {@code removed} with the
     * screen, so a stored rectangle can never outlive the window it was measured in.
     */
    final java.util.Map<PanelKind, Long> panelDepartStarts =
            new java.util.EnumMap<>(PanelKind.class);

    /**
     * The rectangle each departed rail is still drawn in.
     *
     * <p>Snapshotted at close time — and it is the <i>shown</i> rectangle, not the full rail, so a rail
     * closed mid-enter leaves from where it stood rather than jumping to whole and sinking. With it,
     * the exit needs nothing else from the arrangement it just left.
     */
    final java.util.Map<PanelKind, BookGeometry.Rect> departRails =
            new java.util.EnumMap<>(PanelKind.class);

    /**
     * The content cleanup each departed rail is still owed, by rail.
     *
     * <p>Closing nulls the state a panel was drawn from — its quest, its rows, its armed deletes — and
     * the exiting surface still reads that state for the length of its slide. So a close schedules its
     * nullings here instead of running them, stamped with the arrangement they closed under. They run
     * when the rail has sunk <i>and nothing has moved since</i> — in {@code tick} on settle — or eagerly
     * in {@link #applyColumns} when the same kind reopens first (teardown before setup, the order a
     * close-then-open has always had). A settle that finds a newer arrangement leaves them parked: the
     * reopen they belong to has not happened yet, and running them under it would null state a new
     * panel just set. {@code init} runs none of them — a rebuild is not a settle — but it keeps its
     * hands off the draw content of departing rails (see the party guard there), and {@code removed}
     * runs them all, because a stored cleanup must never be dropped on the floor.
     */
    private final java.util.Map<PanelKind, java.util.List<StampedTeardown>> pendingTeardowns =
            new java.util.EnumMap<>(PanelKind.class);

    /** A cleanup with the arrangement it closed under, so a settle can tell stale from owed. */
    private record StampedTeardown(long epoch, Runnable teardown) {
    }

    /**
     * How many arrangements this screen has written: bumped by {@link #applyColumns} on every call.
     *
     * <p>What a scheduled cleanup is stamped with, and what the tick compares before running one. Equal
     * means the close is still the latest thing that happened — nothing reopened, so nothing fresh
     * stands where the nullings land. Different means something moved since, and the cleanup waits for
     * its own kind's reopen (or the screen's end) rather than nulling under a new panel.
     */
    private long arrangementEpoch;

    /**
     * Runs a rail's owed cleanups, if any — whatever arrangement they were stamped under — then
     * forgets them.
     *
     * <p>For reopening (teardown before setup, unconditionally: the cleanup predates this open) and
     * for {@code removed}. The tick's settle is the selective one; see its own note.
     */
    private void runTeardowns(PanelKind kind) {
        java.util.List<StampedTeardown> owed = pendingTeardowns.remove(kind);
        if (owed != null) {
            for (StampedTeardown stamped : owed) {
                stamped.teardown().run();
            }
        }
    }

    /** Runs every owed cleanup. For {@code removed}: exits end when their world does. */
    private void runAllTeardowns() {
        for (PanelKind kind : java.util.List.copyOf(pendingTeardowns.keySet())) {
            runTeardowns(kind);
        }
    }

    /**
     * The rails the latest {@link #applyColumns} retired, in presented order.
     *
     * <p>What a close schedules its deferred nullings under: the call just moved these rails out, so
     * they are the exits that still need their content. Read immediately — the next arrangement change
     * overwrites it — which is why every close below snapshots nothing and simply calls
     * {@link #deferForLastDeparted} before doing anything else that moves rails.
     */
    private java.util.List<PanelKind> lastDeparted = java.util.List.of();

    /**
     * Schedules a cleanup under every rail the latest arrangement change retired, once.
     *
     * <p>Under <i>every</i> one rather than the first, because nested closes share the outer call's
     * departed set: {@code closeOverlay} closes the quest and the picker together, and the picker's own
     * nullings belong to the same exit. The once-guard makes the sharing exact — reopening any of them
     * runs the cleanup early (teardown before setup, as ever), the tick's settle runs what is left, and
     * neither ever runs twice.
     *
     * <p>With no retirees it falls back to every rail still sinking, for the closes that move rails in
     * several steps: a later step that moved nothing must not run at once what an earlier step retired.
     * Only with no exit anywhere does the cleanup run now — a close that moved nothing has no surface to
     * draw from, so deferring would only leak it to the next settle.
     */
    void deferForLastDeparted(Runnable teardown) {
        deferForLastDeparted(teardown, new PanelKind[0]);
    }

    /** One scheduling of a cleanup under a set of rails, guarded to run once across all of them. */
    private void deferUnder(java.util.List<PanelKind> kinds, Runnable teardown) {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable once = () -> {
            if (done.compareAndSet(false, true)) {
                teardown.run();
            }
        };
        StampedTeardown stamped = new StampedTeardown(arrangementEpoch, once);
        for (PanelKind kind : kinds) {
            pendingTeardowns.computeIfAbsent(kind, key -> new java.util.ArrayList<>()).add(stamped);
        }
    }

    /**
     * The same, with extra rails that share the state being cleaned.
     *
     * <p>For the table panels: the browser and the editor read one set of fields, so a cleanup owed by
     * either must run early when <i>either</i> reopens — teardown before setup cannot wait for the same
     * page. The retirees still carry it for the settle path, and the guard still runs it once.
     *
     * <p>With motion off there is no exit to draw from, so the cleanup runs at once: today's exact
     * behavior, with no tenth-of-a-second of retained state behind it.
     */
    void deferForLastDeparted(Runnable teardown, PanelKind... family) {
        if (panelExitMillis() <= 0) {
            teardown.run();
            return;
        }
        java.util.LinkedHashSet<PanelKind> keys = new java.util.LinkedHashSet<>(lastDeparted);
        for (PanelKind kind : family) {
            if (kind != null && kind != PanelKind.NONE) {
                keys.add(kind);
            }
        }
        if (keys.isEmpty()) {
            teardown.run();
            return;
        }
        deferUnder(java.util.List.copyOf(keys), teardown);
    }

    /**
     * How long a departure takes: a touch quicker than the arrival, the standard exit-is-faster rule.
     *
     * <p>Short enough that a close-then-open reads as one gesture rather than two queues, long enough
     * to say where the panel went. Zero with no motion, like the enter path: a close is simply gone.
     */
    private static final int PANEL_EXIT_MILLIS = 150;

    int panelExitMillis() {
        long asked = Math.min(PANEL_EXIT_MILLIS, Motion.defaultDuration());
        return (int) Motion.scaledDuration(asked);
    }

    /**
     * Milliseconds since one rail started leaving, or a huge number once it is gone.
     *
     * <p>The exit's {@link #panelRevealElapsed}: a kind with no entry is not departing, so there is no
     * "settled means draw it whole" tail — the drawing only ever runs for entries in
     * {@link #panelDepartStarts}.
     */
    long panelDepartElapsed(PanelKind kind, long now) {
        Long start = panelDepartStarts.get(kind);
        return start == null ? Long.MAX_VALUE : now - start;
    }

    /** Whether one rail is still sinking out, which is when its surface is still drawn. */
    private boolean panelDeparting(PanelKind kind, long now) {
        return panelDepartStarts.containsKey(kind)
                && PanelLayout.revealed(panelDepartElapsed(kind, now), panelExitMillis()) < 1F;
    }

    /**
     * The rail a departed draw answers with, while it answers.
     *
     * <p>Set around each departed kind's {@link BookOverlay#drawSurface} and cleared after: {@link #panelRail} reads
     * the live arrangement, which no longer holds the kind, so without this the exiting surface would
     * draw itself into whichever rail the arrangement now gives that answer. Scoped per draw rather than
     * per frame, so a throw cannot leak one kind's rectangle into another's.
     */
    PanelKind departDrawKind;
    BookGeometry.Rect departDrawRail;

    /**
     * A resize in flight: the rail as grabbed, the width it had, and where the pointer was.
     *
     * <p>Read while the hand is down so the rail's chrome, its content and its travelling widgets all
     * follow the pointer; written back once, on release, through the same path as any other width.
     */
    private BookGeometry.Rect panelDragRail;
    private int panelDragStartWidth;
    private double panelDragStartX;

    /**
     * Which rail the hand is holding, for the whole of a resize.
     *
     * <p>A field rather than a read of the arrangement, and that is a fix rather than tidiness: every rail
     * carries a grip on its inner edge, so the hand can be on any of the three. Every part of the gesture —
     * the clamp its kind gives the width, the rectangle the live width is fed to, and which number is
     * written when the hand lets go — has to be about <b>the rail that was grabbed</b>. Read from the first
     * panel, grabbing the child's edge resized and then remembered the <i>first</i> panel's width.
     */
    private PanelKind panelDragKind = PanelKind.NONE;

    /**
     * The width a resize has reached, or zero when none is in flight.
     *
     * <p>A live override rather than a write per frame: {@link #railWidth} answers with it while the hand is
     * on that kind, so the rail's chrome and its content follow the pointer without the settings file being
     * rewritten sixty times a second. It is written once, on release, and dropped in the same breath — so
     * the width on screen and the width in the file cannot disagree.
     *
     * <p><b>One field, read for one kind.</b> There used to be a second for the child column, on the
     * reasoning that column 2 owns no stored number and so its drag was forgotten; the two fields were a
     * live override <i>per column</i>, which is the shape {@link #railWidth} exists to replace. A width is
     * a property of a kind now — see {@code DevMode.panelWidth} — and this is the one override in flight,
     * guarded by {@link #panelDragKind} so it can only ever be read for the kind the hand is on.
     */
    private int panelLiveWidth;

    // ------------------------------------------------------------------
    // The reward-table panels
    // ------------------------------------------------------------------

    /** The table the editor is on, or null when no table panel is open. */
    dev.ellipog.tenet.editor.TableAddress tableAddress;

    /**
     * The tables the editor walked through to get here, most recent first.
     *
     * <p>A stack rather than a single address, because a table's entries can be tables: pressing one
     * opens it, and `Done`/Escape must come back rather than close the panel on the author.
     */
    final java.util.Deque<dev.ellipog.tenet.editor.TableAddress> tableStack =
            new java.util.ArrayDeque<>();

    /**
     * What this client has had accepted in the current frame, and what it has taken back.
     *
     * <p>The frame's undo floor and redo ceiling. Undo is allowed only while {@code tableApplied} is
     * positive, so Ctrl+Z cannot reach past the table the author opened into the file's earlier history
     * — and redo only while {@code tableUndone} is, so it cannot replay a step this frame never undid.
     * Both move on an <b>accepted</b> reply and on nothing else: a refused edit changes neither.
     */
    int tableApplied;
    int tableUndone;

    /** The mode the editor is reading the table as: what the chances are computed against. */
    dev.ellipog.tenet.quest.reward.TableReward.Mode tablePreview =
            dev.ellipog.tenet.quest.reward.TableReward.Mode.RANDOM;

    /**
     * The table panels' scrolls: <b>one viewport each</b>, and the bar that owns each one.
     *
     * <h2>Why these are two fields where there used to be one</h2>
     *
     * <p>There was one {@code int tableScroll} for both panels, and the two lists are different
     * lengths in different bands: the browser's rows are 22 pixels and the editor's 20, and each panel
     * clamped the offset against its own {@code maxScroll} but read the number the other had left
     * there. Scroll to the bottom of the editor's entries, go back to the browser, and the browser's
     * list was where the editor had put it — a list arriving part-way down for no reason the person
     * looking at it could see. The viewport is what makes that unrepresentable rather than unlikely:
     * the offset, the clamp and the bar's range are one object's, and there is one object per list.
     *
     * <p>The roll pane keeps its own, on the panel's own terms: it is a page of its own, and moving it
     * must not move the entry list under it.
     */
    final Viewport tableBrowserBody = Viewport.fixed();
    final ScrollBar tableBrowserBar = new ScrollBar(tableBrowserBody);
    final Viewport tableEditorBody = Viewport.fixed();
    final ScrollBar tableEditorBar = new ScrollBar(tableEditorBody);
    final Viewport rollBody = Viewport.fixed();
    final ScrollBar rollBar = new ScrollBar(rollBody);

    /** Whether the toolbar's Import popover is showing. See `drawTableImportPopover`. */
    boolean tableImportOpen;

    /** Where the popover drew itself last frame, which is what a press inside it is measured against. */
    BookGeometry.Rect tableImportBox;

    /** The roll pane's line pitch. */
    static final int ROLL_PITCH = 12;

    /**
     * How many grants a test roll simulates.
     *
     * <p>Named because the button's own label says the number: the request sent {@code 10} while the
     * button spelled "x10" in a string literal two methods away, so changing one would have made the
     * control lie about what it was about to do.
     */
    static final int TABLE_ROLL_COUNT = 10;
    int tableSelected = -1;
    dev.ellipog.tenet.client.dev.TableBrowserLayout.Frame tableBrowserFrame;
    dev.ellipog.tenet.client.dev.TableEditorLayout.Frame tableEditorFrame;

    /** The entries the editor has folded open, by position. */
    final java.util.Set<Integer> tableFolded = new java.util.LinkedHashSet<>();

    /**
     * A picker opened from the table editor, and what the pick is for.
     *
     * <p>Empty string means "a new entry from the picked item"; anything else is the path the item goes
     * to — {@code icon.item}, or an entry's {@code entries.2.reward.item}. The picker itself is the
     * book's own, so a table is edited with the same list an item reward is.
     */
    String tablePickPath;

    /** Whether the roll report is showing over the entry list. */
    boolean tableShowRoll;

    /** Whether the next press on a table would throw away an inline table, so it asks first. */
    boolean tableConfirmReplace;

    /**
     * The same, for the Assets panel's own {@code ×}.
     *
     * <p>A field of its own rather than {@link BookRewards#tableConfirmDelete}, and that is the point: the two panels
     * are two surfaces that both list tables, and one field would let an arm made in one of them fire on
     * the first press in the other. The rule is "the control that asked is the control that confirms", and
     * sharing the arming is how that stops being true.
     *
     * <p>The Assets panel was the last destructive control in this book with no second press at all:
     * {@code deleteTable} straight from the press, one chip from Copy, with no status line and no arm.
     * Nothing found it, because the browser's {@code ×} <i>does</i> ask and the two verbs read as one
     * control in two places -- which is exactly the fault a check for this rule has to catch.
     */
    String assetsConfirmRemove;


    /**
     * The table ops this client has sent and not yet heard about, oldest first.
     *
     * <p>A marker per op: a table op's own kind, and an empty string for a chapter's. The two families
     * share one payload and answer in send order, so the reply that arrives is only attributable by
     * remembering what was sent — and a chapter reply must not be mistaken for a table one, which is
     * how a quest named after a table would otherwise be selected on the canvas.
     */
    /**
     * The sentinel a request that is answered by something other than an edit reply records.
     *
     * <p>Not a table op's kind and not the empty string a quest op records: the reply loop needs to know
     * this answer belongs to a <b>request</b> rather than to an edit, so it neither moves the undo
     * budget nor forgets the panel's drafts.
     *
     * <p>The queue itself lives in {@code ClientEditReplies}, beside the replies it matches, because a
     * request is not always answered by an edit reply: a replica fetch and a test roll answer with their
     * own payloads when they succeed and with an edit reply when they are refused. Both doors consume
     * one marker, and the marker has to be somewhere the payload handlers can reach -- they have no
     * screen. The sentinel moved there with it, for the same reason: the marker is the queue's own
     * contract rather than the screen's.
     */
    static final String REPLICA_SENTINEL = ClientEditReplies.REPLICA_SENTINEL;

    /** Whether the picker on screen was opened by the table editor, so it closes back into it. */
    boolean tablePickReturn;

    /**
     * The same, for the type picker: a list of rewards opened from the table editor.
     *
     * <p>Its own flag rather than a shared one because the two pages are closed by different code --
     * the item picker's `closeItemPicker` knows how to leave a table, and the type page's own close is
     * below -- and because the press that adds a type has to know which file it is adding to. See
     * {@code pressTypeRow}, which is where the difference matters.
     */
    boolean typePickReturn;

    /**
     * The table field the open inline editor is editing, and what kind of value it is, or null.
     *
     * <p>The card's inline editor commits a field of the <b>quest</b>; a weight is a field of a table.
     * The two share the widget and the commit path, so the one that knows which file it writes to has to
     * say so -- without this, typing a weight sent {@code entries.1.weight} to the quest file.
     *
     * <p>The kind travels with the path because the box hands back a <b>string</b> and the file does not
     * hold one: this began as a path alone and the commit was written for the only field it had, so the
     * branch parsed a double. That is right for a weight and wrong for everything else the fold now
     * edits — a command is a string, {@code levels} is a boolean, an id is an id, and the raw row is
     * whole JSON. One kind, read from the control that opened the box, is what keeps the commit from
     * guessing.
     */
    TableEdit tableEdit;

    /** One field of a table, and how the text typed into a box becomes its value. */
    record TableEdit(String path, dev.ellipog.tenet.client.dev.TableRowFields.Kind kind) {
    }

    /**
     * The overlay a table panel was opened over, and must give back when it closes.
     *
     * <p>{@code PanelKind.QUEST} when the panel came from a reward's card — which is the usual way — so
     * selecting a table, pressing Done or pressing Escape returns to the card the author was editing
     * rather than dropping them onto the canvas. The first version always closed to {@code NONE}, which
     * is what made picking a table feel like being thrown out of the quest.
     *
     * <p>Empty for a panel a command asked for: there is no card behind that one, and the book is where
     * it should end up.
     */
    PanelKind tableReturn = PanelKind.NONE;

    /**
     * The table a replica request is outstanding for, so a refusal can be filed against it.
     *
     * <p>A refused replica answers with an <b>empty chapter</b> — a table is a file, not a chapter's — so it
     * arrived looking like news about some other chapter and was recorded against a chapter with no name.
     * The panel's copy stayed missing, `ClientTableReplica.refusal` stayed null, and the panel said "Waiting
     * for this table's file..." for as long as the author was willing to stare at it, while the server had
     * already said why. One field is what connects the refusal back to the table it is about.
     */
    String replicaAskedFor;

    /** The quest whose overlay is open, by id. */
    String overlayQuest;

    /**
     * The card's back stack: one entry per "Requires" jump, newest last.
     *
     * <p>A prerequisite chain is four deep — Rocket, Fuel Tank, Steel Plate, Blast Furnace — before a
     * reader notices they cannot step back without losing the thread, so every jump out of a card
     * remembers the card it left: which quest, and where its body was scrolled and folded. Cleared
     * when the card closes or a fresh card opens from the canvas; a jump or a step back keeps it.
     */
    final List<CardVisit> overlayHistory = new ArrayList<>();

    /** One card in the back stack: the quest, and the body state it was left in. */
    record CardVisit(String questId, int scrollY, Set<String> folded) {
    }

    /**
     * The locate-on-canvas move in flight: which node it is going to, and where it started.
     *
     * <p>Advanced once a frame from the canvas drawing — the only place the move can be seen, since a
     * card hides the canvas — and cancelled by any manual pan or zoom, because a camera that fights the
     * hand is worse than no camera move at all.
     */
    String glideQuest;
    long glideStart;
    int glideFromX;
    int glideFromY;
    int glideToX;
    int glideToY;

    /** The node whose outline is flashing after a locate, and when the flash began. */
    String flashQuest;
    long flashStart;

    /**
     * The party panel's laid-out rows, and the scroll view that places its controls from them.
     *
     * <h2>Why the layout is a field rather than a local of the widget pass</h2>
     *
     * <p>Because the Remove buttons and the action buttons are <b>widgets</b>, and a widget's position is
     * fixed at construction. So the layout that placed those buttons has to be the one the drawing reads,
     * or the row a member's name is written on and the button that removes them would come from two
     * computations that agree until one of them changed — and the panel scrolls, so the two also have to
     * agree about how far it has been scrolled.
     *
     * <p>The scroll is a {@link ScrollView} rather than a pair of origin fields, and that is the whole of
     * what closed the panel's stated limitation: it was "clipped rather than scrollable", which meant a
     * roster taller than its card simply lost its last rows — the drawing skipped them and the widgets
     * were never placed. A control now gets its rectangle from the same viewport the drawing reads, and
     * the ones outside it are hidden rather than misplaced, which is also what stops a row scrolled past
     * the card's edge from answering the pointer. See {@code PartyPanelLayout} for the layout and
     * {@code ScrollView} for the placement; between them there is one expression for where a row is.
     */
    Layout partyLeftLayout;
    Layout partyRightLayout;
    PartyPanelLayout.Face partyLeftFace;
    PartyPanelLayout.Face partyRightFace;
    final ScrollView partyLeftView = ScrollView.of(Viewport.fixed());
    final ScrollView partyRightView = ScrollView.of(Viewport.fixed());

    /**
     * The card the panel was built into: what the widgets were placed inside, and what the drawing and
     * the click-outside test read.
     *
     * <h2>Why this is a field rather than a second computation</h2>
     *
     * <p>Because the card is built once and everything inside it is placed from that one rectangle — the
     * two columns, their viewports, the footer and the clip. A drawing that re-derived it would be the
     * second arithmetic this panel was rewritten twice to remove.
     */
    BookGeometry.Rect partyCard;

    /**
     * The rows each column drew, kept because a row's <i>label</i> is drawn by the panel rather than by
     * a widget: only the controls are widgets, so the drawing needs the same lines the controls were
     * made from, and a second derivation of them is how a name ends up beside somebody else's button.
     */
    List<PartyPanelLayout.Line> partyLeftLines = new ArrayList<>();
    List<PartyPanelLayout.Line> partyRightLines = new ArrayList<>();

    /**
     * The footer's Disband control, and whether it has been pressed once and is waiting for a second.
     *
     * <h2>Why Disband asks and the rest do not</h2>
     *
     * <p>Because it is the only control on the panel whose mistake cannot be undone by pressing
     * something else: Remove takes one player out and Leave takes you out, and the party survives both.
     * Disband ends it for everybody in it, including the members who are offline and cannot be asked.
     *
     * <p>The control is kept rather than found by key because the label is changed <b>on it</b>. A
     * rebuild would be the other way to change a label, and it cannot be: a rebuild goes through
     * {@code init}, which disarms — so arming and rebuilding would be the same press, and the second
     * press would send. The window is three seconds: a confirmation that never expires is not a
     * confirmation, it is a flag a player sets and forgets.
     */
    ArmatureButton disbandButton;
    final ArmedPress disbandPress = new ArmedPress();

    /** Which question the panel is asking, if any: the flows that need an answer before acting. */
    enum PartyPhase {
        /** The ordinary panel. */
        NONE,
        /** Hand the party to one member, confirmed. */
        CONFIRM_TRANSFER,
        /** The owner is leaving and must pick who owns it — or disband. */
        PICK_SUCCESSOR,
        /** Dissolving a party with members in it, confirmed. Solo owners skip this. */
        CONFIRM_DISBAND
    }

    PartyPhase partyPhase = PartyPhase.NONE;

    /**
     * What the solo face's two fields hold between rebuilds.
     *
     * <p>Kept because a rebuild recreates the widgets — the roster revision moves on every push, and a
     * rebuild is what redraws the panel — so the text a player was typing has to survive in a field the
     * screen owns, or it would be wiped by somebody else's join.
     */
    String partyCreateName = "";
    String partySearch = "";
    boolean partyCreateFocused;
    boolean partySearchFocused;
    ArmatureTextField partyCreateField;
    ArmatureTextField partySearchField;
    ArmatureTextField partyNameField;

    /**
     * The member rows' owner controls, by member id.
     *
     * <p>Kept because their <b>visibility</b> is decided at draw time from the row's hover: the widgets
     * exist whenever the viewer may use them, and a row that is not hovered draws neither. See
     * {@code drawPartyMembers} — the alternative, creating them on hover, would be widgets made in a
     * drawing pass.
     */
    final Map<UUID, ArmatureButton> partyRemoveButtons = new java.util.HashMap<>();
    final Map<UUID, ArmatureButton> partyTransferButtons = new java.util.HashMap<>();

    /**
     * Every row control the panel built, by control key.
     *
     * <h2>Why these are placed here rather than registered with the scroll view</h2>
     *
     * <p>{@code ScrollView} matches a registered widget to a slot <b>by key</b>, and its slots are the
     * layout's rows. A row with one control registered under the row's own key works -- and that is how
     * the search field is handled. A row with <b>two</b> controls cannot: two widgets would need one
     * row key, and a control key like {@code right:open:switch} is a key no row carries -- so the view
     * hides it as "registered but not in this layout". That is exactly what happened to the settings
     * switches and the rename pencil: built, added, invisible.
     *
     * <p>So the controls are placed from {@link PartyPanelLayout#controlSlots} every frame, in the same
     * pass that reads the layout for everything else -- one derivation for the rectangle, read by the
     * placement, the hover logic and the preview. The scroll views keep what they are good at: the
     * clamp, the bar and the wheel.
     */
    final Map<String, net.minecraft.client.gui.components.AbstractWidget> partyControls =
            new java.util.HashMap<>();

    /** The measure every party row is built with. Fixed-width, because a row is a fixed height. */
    static final Measure TEXT_MEASURE = Measure.monospace(6, 9);

    /**
     * The member row's own metrics live in {@link PartyPanelLayout}, where the row's composition is.
     *
     * <p>They were six private constants here, which is where they were unreadable from: a drawing
     * metric in the one class in either mod that cannot be asked anything. See that class's note.
     */

    /**
     * The party strip's control, so the drawing can keep its label current.
     *
     * <p>Nulled in {@code init} with the list it comes from, exactly as {@link #closeButton} is: leaving
     * it set across a rebuild would have {@code drawPartyStrip} writing a message onto a control the
     * screen no longer owns.
     */
    ArmatureButton partyButton;

    /**
     * The overlay body's scroll, as a scroll view rather than an int.
     *
     * <h2>What this replaced</h2>
     *
     * <p>An {@code int overlayScroll} that the wheel wrote <b>unclamped</b>, and that
     * {@link BookSettings#drawOverlay} clamped much later against a height produced by a separate
     * {@code measureOverlay} pass. Three places holding one number — the writer, the clamp and the
     * scrollbar — which agreed only by hand, and only until somebody scrolled. A flick past the bottom
     * left the offset out of range until the next frame happened to correct it.
     *
     * <p>Here the clamp, the position and the thumb are one object's, and {@link BookSettings#overlayLayout} is the
     * single call that says how much content there is, so the bar cannot claim a range the drawing does
     * not fill.
     *
     * <p>The viewport is fixed-scale: there is no canvas inside the overlay, so a zoom control here
     * would be a control for something that does not exist.
     *
     * <p>No widgets are registered in it. The overlay's two controls are placed by {@link BookGeometry}
     * from the card's own rectangles rather than by the scroll, so what this contributes is the scroll
     * range, the clamp and the scrollbar.
     */
    final ScrollView overlayView = ScrollView.of(Viewport.fixed());

    /**
     * The choice offer's own list, while its card is open: one row per entry the server offered.
     *
     * <p>A view of its own rather than {@link #overlayView}, for the reason that one is the card's: the
     * two are never open at once, and a shared view would carry the quest card's scroll into a question
     * with three answers.
     */
    final ScrollView choiceView = ScrollView.of(Viewport.fixed());

    /** The choice offer's rows and layout, for the card that is open. */
    List<InspectRow> choiceRows = List.of();

    /**
     * The accepted-items list's own view, while its rail is open: one row per match the
     * expression names.
     *
     * <p>Its own view for the reason {@link #choiceView} is: the two lists are never open on the
     * same quest at once in the same scroll position, and a shared view would carry one list's
     * position into the other.
     */
    final ScrollView filterView = ScrollView.of(Viewport.fixed());
    Layout filterLayout;

    /** The expression the open list was read from; empty when no list is open. */
    String filterExpression = "";

    /**
     * The rewards panel's own list: a chapter accordion whose quests fold open onto their rewards.
     *
     * <p>Its own view for the reason {@link #choiceView} is: the two cards are never open at once, and a
     * shared scroll would carry one list's position into the other.
     */
    final ScrollView rewardView = ScrollView.of(Viewport.fixed());

    /**
     * What a press has already asked the server for, and when it asked — the optimistic half of the book.
     *
     * <p>A row reads collected the moment it is pressed, and a hand-in reads handed in. What the mark is
     * <i>not</i> is a second source of truth: it changes what is drawn and never what is sent, and it goes
     * for exactly three reasons — the server's answer agreed with it, the thing it named left the tree, or
     * it outlived {@link PendingClaims#BACKSTOP_MILLIS}. See that class for why the third one is reported
     * rather than silent, and for why this is no longer cleared on a progress revision: <b>any</b> sync
     * used to empty it, which made a row the player had just pressed flicker back to ready.
     */
    final PendingClaims pendingClaims = new PendingClaims();

    /**
     * The controls the rewards card built, so a refresh can take them down before it builds again.
     *
     * <p>Needed because {@code ScrollView.clear()} forgets its own map and does not unregister anything —
     * so a card rebuilt without a full {@code init()} would leave its previous buttons on the screen:
     * drawn nowhere, and still taking presses. This is the list that makes a scoped refresh possible.
     */
    List<ArmatureButton> rewardControls = List.of();

    /** The marks the rewards card's controls were built for; see {@link BookRewards#reconcilePendingClaims}. */
    long rewardMarksRevision = -1L;

    /**
     * The offer rows' rectangles, from the last frame's drawing.
     *
     * <p>Stored rather than recomputed, because the rows are drawn by this screen and a press must land
     * on the row it was drawn under -- the rule every drawn list in this book follows.
     */
    final List<BookGeometry.Rect> choiceRowRects = new ArrayList<>();

    /** The header's way into the panel, kept so its tooltip can say whether anything is waiting. */
    ArmatureButton rewardsButton;

    /**
     * The pinned list's own rows, scroll view and layout: one switch and one row per pin.
     *
     * <p>Per-kind state rather than part of the arrangement, the way the element panel's rows are: the
     * arrangement names that a pinned rail is open, and these name what it is showing.
     */
    final dev.ellipog.armature.client.ui.kit.ScrollView pinnedView =
            dev.ellipog.armature.client.ui.kit.ScrollView.of(
                    dev.ellipog.armature.client.ui.kit.Viewport.fixed());
    List<ToolsLayout.Action> pinnedRows = List.of();
    Layout pinnedLayout;

    /**
     * What each pin row draws at its left edge, resolved when the rows are built.
     *
     * <p>At build rather than per frame: the rows are rebuilt whenever the tree moves, and a frame that
     * walked the cache per row would pay the lookup sixty times a second for an answer that cannot
     * change between rebuilds. An id the tree does not hold keeps an empty stack, and the row keeps
     * the icon's room either way, so a late icon does not shift the title it lands beside.
     */
    final Map<String, ItemStack> pinnedIcons = new HashMap<>();

    /**
     * The pinned panel's drawn targets: what the chrome registers, and what its own press walks.
     *
     * <p>Its own list rather than the editor's, for the reason the element panel has one: the editor's
     * list still holds the marks the panel behind it drew, and a press inside this panel must not be
     * able to land on one of those.
     */
    final List<EditTarget> pinnedTargets = new ArrayList<>();

    /** The progress revision the rewards panel's rows were built at; see {@link #tick}. */
    long rewardsRevision = -1;

    /**
     * The settings card's own list, rows and view; see {@link SettingsLayout}.
     *
     * <p>Named for the appearance rather than for the card, because the card's own Settings *page* --
     * the quest editor's property inspector -- already owns {@code settingsView} and its siblings. Two
     * surfaces called settings in one screen is one too many; this one is the player's.
     */
    final ScrollView appearanceView = ScrollView.of(Viewport.fixed());
    List<InspectRow> appearanceRows = List.of();
    Layout appearanceLayout;

    /** The card's own rect, sized to its rows; null until the card is built. */
    BookGeometry.Rect appearanceCard;

    /**
     * The canvas's keyboard focus: which node the arrow keys are standing on.
     *
     * <p>The nodes are custom-drawn and hit-tested, so they are invisible to focus, tab order and the
     * narrator — this is the screen's half of the answer. See {@link CanvasFocus} for the rule.
     */
    final CanvasFocus canvasFocus = new CanvasFocus();

    /**
     * The chapter list, as a scroll view over {@link BookGeometry#sidebarViewport()}.
     *
     * <h2>Why the rows are widgets rather than drawn lines</h2>
     *
     * <p>Because they are the thing a click lands on, and a hand-drawn row cannot take focus, cannot be
     * narrated, and has no hover state unless this class reimplements one per row. Every one of those is
     * already solved by {@code AbstractWidget}, and the price of getting them is a class that moves the
     * widget when the content scrolls — which is {@link ScrollView}'s whole job.
     *
     * <p>The viewport is fixed-scale: a sidebar does not zoom, and a view that reported a zoom range of
     * one would be a view whose zoom controls are drawn, enabled, and do nothing.
     *
     * <p>The bar this view draws is a control, and the kit owns it rather than this screen:
     * {@link ScrollBar} decides whether a press is the grip's or the groove's, maps a pointer's y back
     * onto a scroll offset, and pages on a held groove. That mapping is the inverse of the drawing's own
     * formula, so it lives beside it — see that class's note.
     *
     * <p>It is <b>not</b> cleared on a resize, and that is deliberate. {@code init} runs on every resize
     * and on every rebuild, and the outline's expansion state — and so the player's collapses — lives in
     * {@link #sidebar}, which is keyed on the tree rather than on the window. The scroll offset lives in
     * this object's viewport, so it survives a resize too: a window dragged narrower while the list is
     * scrolled keeps its place, which is what every other list on any platform does.
     */
    final ScrollView sidebarView = ScrollView.of(Viewport.fixed());

    /**
     * Whether the sidebar's rows currently leave room for the scrollbar's gutter.
     *
     * <p>Decided in {@link BookQuestEditor#buildSidebarWidgets} from the rows it places, because that is the one
     * place that knows the count — and read by {@link #sidebarViewport()}, which binds the bounds
     * every frame. A field rather than a recompute, so the per-frame bounds and the placed rows
     * cannot disagree: both answer from the same build. True until the first build, which is
     * today's reservation, so a screen that never builds rows draws what it always drew.
     */
    boolean sidebarReserveScrollbar = true;

    /**
     * The waiting-counts the row suffixes were last set from.
     *
     * <p>Compared by content every tick: a claim lands as a changed map, and only a changed map
     * touches the buttons — so thirty rows are not re-examined for a progress tick that moved
     * nothing. Set by the build for the buttons it just made, so a row rebuilt mid-drag carries
     * its suffix from its first frame rather than waiting a tick for it.
     */
    Map<String, Integer> sidebarWaiting;

    /**
     * The selection the suffix colours were last set for.
     *
     * <p>The count keeps the accent dimmed on the selected row, so a chapter change re-applies
     * even when no count moved — otherwise the newly selected row keeps full accent until the
     * next claim. Compared beside the map above: one string equality per tick, and the row walk
     * only when something actually changed.
     */
    String sidebarSuffixChapter;

    /**
     * The sidebar's outline, and the tree revision it was built from.
     *
     * <h2>Why the outline is kept rather than rebuilt</h2>
     *
     * <p>Because it holds the player's collapsed groups. Rebuilding it from the server's data on every
     * <code>init</code> would re-seed it from the authored defaults, so a resize would silently reopen
     * every group the player had closed — and a rebuild happens on every window resize, every click that
     * selects a chapter, and every quest the screen opens.
     *
     * <p>The revision is what decides when to rebuild, and it is the counter
     * {@code ClientQuestCache.treeRevision()} exists to provide: it moves when a tree arrives and when a
     * cache is cleared, and <b>not</b> when a screen merely reads one. So a resize, a scroll, a toggle
     * and a redraw all leave the outline alone, and a reload — which is a tree arriving — builds a new
     * one. See that method's own note for why the unit is "a tree arrived" rather than "the tree
     * differs".
     *
     * <p>{@code -1} rather than {@code 0} for the initial value, because a revision is only ever
     * compared and a real one could legitimately be zero on the first tree. A sentinel that cannot be
     * mistaken for a real value is the difference between "definitely stale" and "probably stale".
     */
    static SidebarLayout sidebar;
    static List<SidebarLayout.ChapterRow> sidebarChapters = List.of();
    static long sidebarRevision = -1;

    /** And the other three inputs the outline's rows depend on. See {@link #sidebar()}. */
    static long sidebarProgress = -1;

    /**
     * And the language, since the rows carry chapter and group <b>names</b>.
     *
     * <p>Its own field rather than folded into {@code sidebarRevision}, because that one is the tree's
     * and a locale arriving is not a tree: the outline's rows, their order and the player's own
     * expansions are all unchanged, and only the words in them are. Keeping them apart is what lets
     * this rebuild the outline for a language change without pretending the questline moved.
     */
    static long sidebarText = -1;

    /**
     * Which groups the player has opened or closed, over the groups' own defaults.
     *
     * <p>Kept across sidebar rebuilds, and a sidebar is rebuilt every time the tree arrives — which is
     * after every structural edit. Without this the authored {@code collapsedByDefault} won every time,
     * so a group snapped shut the moment something was dragged into it.
     */
    static final Map<String, Boolean> sidebarExpansion = new LinkedHashMap<>();

    /**
     * The chapter list's slide: when the wipe started, and the rectangle it started from. {@code -1}
     * when settled, which is most frames: the wipe is 160 milliseconds of travel between two
     * states the widgets already hold, so there is nothing to animate the rest of the time.
     *
     * <p>The target is never stored because it is never needed: the geometry is built from the
     * effective state, so its sidebar rectangle <i>is</i> where the wipe lands. A rebuild for any
     * other reason mid-slide keeps the same target, which is what stops a replica arriving
     * mid-wipe from stranding the list between two widths.
     */
    long sidebarSlideStart = -1;
    private BookGeometry.Rect sidebarSlideFromRect;

    /**
     * Hover-peek: the strip is hovered, so the collapsed list shows as a floating preview.
     *
     * <p>Transient — the file never hears about it. The effective state resolves it before the
     * geometry is built (collapsed + peeking draws as floating expanded), so no rectangle ever
     * describes a hover, and lifting the pointer puts the strip back without writing anything.
     */
    boolean sidebarPeeking;

    /**
     * How long the pointer must rest on the strip before the peek opens, in milliseconds.
     *
     * <p>Without it every crossing of the panel's left edge — on the way to a node, to the view
     * cluster, to anywhere left — would unfold the list under the hand and fold it back a moment
     * later. A crossing never dwells; an intention does. Leaving stays instant, so the preview
     * never overstays: arriving is the part that has to prove itself, not leaving.
     */
    private static final long SIDEBAR_PEEK_DWELL_MILLIS = 120;

    /** When the current sliver hover began, or -1 outside it. Read by the dwell above. */
    long sidebarPeekHoverStart = -1;

    /**
     * How much room the sliver's hover zone gets past its visible edge, in GUI pixels.
     *
     * <p>Presses stay exact — a press past the sliver is the canvas's — but a hover that ends a
     * pixel short of twenty pixels of tab reads as a peek that flickers, especially along the
     * top where the pointer arrives diagonally from the header.
     */
    private static final int SIDEBAR_PEEK_HOVER = 6;

    /**
     * How much room the open box's keep zone gets past its edge.
     *
     * <p>Same reason one step out: ending the preview the frame the pointer brushes the border —
     * behind the box, above its header — is a preview that cannot be looked at, only triggered.
     */
    private static final int SIDEBAR_KEEP_HOVER = 12;

    /**
     * How long the pointer may sit outside the keep zone before the preview ends, in milliseconds.
     *
     * <p>The mirror of the enter dwell: arriving has to prove itself, and leaving gets the same
     * courtesy — brushing past the border on the way somewhere else must not fold the list the
     * hand just opened. Short enough that an honest leave still reads as instant.
     */
    private static final long SIDEBAR_PEEK_LEAVE_MILLIS = 200;

    /** When the current outside-the-keep-zone spell began, or -1 inside it. */
    long sidebarPeekLeaveStart = -1;

    /**
     * Rows the collapsing list left behind, drawn by hand till the wipe lands.
     *
     * <p>A collapse files the list's live rows here and removes them from the screen at once —
     * the rails' arrangement, where a close rebuilds immediately and the exit draws from a
     * snapshot. The rows ride the card out as orphans: positioned from their filed bases every
     * frame, drawn cold through the button's own {@code draw} inside the wipe's own clip, and
     * discarded when the wipe lands. Nothing interactive can reach them meanwhile — presses over
     * the wipe are absorbed, and the screen no longer owns them — so a row can never answer,
     * hover, or be narrated from a position the card has already left, which is the ghost the
     * kept-widgets ride flashed.
     */
    private record SidebarDeparted(AbstractWidget widget, int x, int y) {
    }

    /** The departed rows, in build order. Empty unless a collapse or unpeek wipe is travelling. */
    private final List<SidebarDeparted> sidebarDeparted = new ArrayList<>();

    /**
     * The box the departed rows were built for, filed when the collapse starts.
     *
     * <p>The travel's anchor: orphans and the cluster are positioned from this, never from the
     * wipe's start, so a collapse that reverses an expand mid-wipe continues from mid-wipe
     * instead of snapping the rows back to full for a frame. Null outside a departure.
     */
    private BookGeometry.Rect sidebarDepartAnchor;

    /**
     * How far the departed rows lead the wipe's edge, measured when the collapse starts.
     *
     * <p>Glued rows end a fixed overhang past the panel's edge — the viewport stops them short
     * of the box's own edge, but not short of the book's — and discarding them at the settle
     * would snap that overhang away as a glitch at the end of the ride. Ramping this from
     * nothing to exactly that overhang over the wipe puts every row fully past the edge the
     * frame it lands, so the discard changes nothing visible. Measured eagerly against the
     * sliver, so no constant can go stale against another window size.
     */
    private int sidebarDepartOverhang;

    /**
     * Whether the list is sliding out with departed rows aboard.
     *
     * <p>A collapse writes the file at once, files the full rows as orphans, and leaves the
     * sliver built behind them: the rows ride the card back to the sliver instead of vanishing
     * the frame it starts, and the settle only discards them. The rails' departed panels are
     * the same arrangement — state rebuilt at close, content drawn till it lands — for the same
     * reason: a close that pops its content reads as a glitch, not a gesture. With no motion
     * there is no wipe to ride, so this never sets and the collapse lands immediately.
     */
    boolean sidebarDeparting;

    /**
     * Whether the open unpinned box has seen the pointer inside it yet, since it opened.
     *
     * <p>See {@code updateSidebarPeek}: auto-hide fires on leaving, and leaving needs an arrival
     * first — otherwise a freshly opened box slams shut on its first frame with the pointer
     * elsewhere. Cleared on every expand, set while hovered.
     */
    boolean sidebarBoxHovered;

    /**
     * Whether the last unpeek was an act rather than a pointer leaving.
     *
     * <p>Escape, a map click or B puts the strip back while the pointer is still over it; without
     * this the dwell would start over at once and re-open the preview under a stationary hand.
     * Cleared the moment the pointer leaves the strip, so an honest return still peeks.
     */
    boolean sidebarPeekCooling;

    /**
     * A resize of the list in flight: the list as grabbed, the width it had, and where the
     * pointer was. The mirror of {@code panelDragRail} and its two companions, for the opposite
     * edge: the list docks left, so its handle is its right.
     */
    private BookGeometry.Rect sidebarDragRail;
    private int sidebarDragStartWidth;
    private double sidebarDragStartX;

    /**
     * A live width override while the edge is held: {@link #sidebarEffective} answers with it
     * instead of the file, so the chrome, the rows and the controls all follow the pointer
     * without the settings file being written sixty times a second. Null when the hand is off.
     */
    private Integer sidebarLiveWidth;

    /**
     * Every widget the chapter list built, for the wipe to travel with while it expands.
     * <p>Filed as the list builds — rows, toolbar, box squares — and read only on the frame path:
     * an expanding wipe offsets them by the wipe's own shortfall, so the rows ride the card's
     * edge instead of popping in once it has landed. Same-frame restore, so nothing can leak
     * into the next frame whatever happens between the two. A collapse needs none of it: its
     * rows were filed as orphans when it started (see {@code sidebarDeparted}) and are drawn by
     * hand, so the strip's own icons sit inside the wipe at every frame. The mirror of
     * `slideBaseX`, for the list rather than the rails.
     */
    final List<AbstractWidget> sidebarRowWidgets = new ArrayList<>();

    /** Each travelling row's placed position, filed on the first slide frame like the rails' own. */
    final Map<AbstractWidget, int[]> sidebarTravelBase = new IdentityHashMap<>();

    /**
     * The view cluster's three buttons, for the sidebar's wipe to travel with.
     *
     * <p>The cluster sits beside the list at the rows' height, so when the list slides the
     * cluster glides with its edge rather than jumping between its docked and floating seats.
     * Filed here rather than read from the children: the pass holds every widget, and a slide
     * that moved all of them would move the header too.
     */
    final List<AbstractWidget> viewClusterWidgets = new ArrayList<>();

    boolean dragging;
    boolean pressMoved;

    /**
     * Whether the press that started this gesture landed on a column without anything in it claiming it.
     *
     * <p>Such a press is the canvas's — the column floats over the graph rather than being a hole in it, so
     * dragging from the panel's own surface moves the graph, which is what "the canvas stays live" has to
     * mean when the column covers most of a reader's window. But it is <b>not</b> a click on empty canvas,
     * and this is what tells the two apart at the release: without it, letting go over the panel would read
     * as "press blank space" and close the panel the reader was dragging from.
     */
    boolean panelPress;

    /**
     * The scrollbar the pointer is holding, or null.
     *
     * <h2>Why one field holds the gesture rather than a branch per list</h2>
     *
     * <p>Because this screen has fifteen scrollable regions and a drag has to survive the pointer
     * leaving whichever one it started in. The version this replaces re-derived the subject from the
     * <i>live pointer position</i> on every move, which had two consequences that both arrived as
     * reports about feel rather than about logic: dragging the left party column's bar and moving the
     * pointer rightwards transferred the drag to the right column's bar, and any bar whose release
     * branch somebody had forgotten — there were three — stayed held until the next press.
     *
     * <p>So a press captures one bar here, and the move, the release and the hold-repeat are that
     * bar's and nothing else's. It is also the whole of "the grab survives leaving the bar": the
     * pointer may leave the strip, the panel and the window, and the gesture is still the button.
     */
    private ScrollBar heldBar;

    double pressX;
    double pressY;

    /**
     * The content point the pan is holding, not the offset it started at.
     *
     * <p>In content coordinates because a drag says "I am holding this bit of the world", and a zoom
     * during the drag moves the world. The offset captured at press was the first version and it had a
     * visible fault: hold the canvas and turn the wheel, and each mouse move slid the canvas back to
     * where the drag began -- because the pan put back an offset that the zoom had deliberately
     * changed. See {@code Viewport.dragTo}, which is that arithmetic with a test.
     */
    float panContentX;
    float panContentY;

    /** The node under the pointer when the press began, if any. */
    String pressedNode;

    /**
     * The element the press landed on, until the release decides what it meant.
     *
     * <p>Beside {@link #pressedNode} and read the same way, because an element is claimed by the same gesture:
     * a press that travelled is a pan, and one that did not is a click — which selects in edit mode and runs
     * the element's action in play mode. Only one of the two is ever set, because an element is only reached
     * when no node was under the pointer.
     */
    private String pressedElement;

    /**
     * The editors this session has open, by chapter. Created on first use.
     *
     * <p>Null until the book is in a state where editing is possible at all — see {@link #editor()} — and
     * a field rather than a local because an editor holds unsaved work: a rebuild, a resize or a glance at
     * another chapter must not throw it away.
     */
    /** Positions asked for and not yet answered. Built once: it holds no chapter, so nothing to reset. */
    final EditorSession editors = new EditorSession();

    /**
     * Which panel the dock is showing: the tools this panel started as, or the quest the canvas has
     * selected. The strip that replaced the title row switches it.
     *
     * <p>The theme panel's own state — the selected colour, the scroll, the fold — lives in fields the
     * tab switch never touches, so going to the Quest tab and back is exactly where you left it. That
     * permanence is the reason the dock grew tabs rather than swapping its contents.
     */
    ToolsLayout.Tab toolsTab = ToolsLayout.Tab.BOOK;

    /**
     * Whether the copy row is waiting for its second press, and the button its label is changed on.
     *
     * <h2>Why the label is changed on the button rather than by a rebuild</h2>
     *
     * <p>The same reason the party's Disband does it (see {@code disarmDisband}): a rebuild goes through
     * {@code init}, which disarms -- so arming and rebuilding cannot be the same press. The label the
     * row model carries is still the armed one while armed (see {@code ToolsLayout.canvasRows}), which is
     * what the arming press's own rebuild draws; any later rebuild builds the plain one.
     */
    boolean canvasCopyArmed;
    ArmatureButton canvasCopyButton;

    /**
     * Whether the rebuild in flight is the arming press's own.
     *
     * <p>One flag for the one rebuild that must not disarm: {@code init} clears {@link #canvasCopyArmed}
     * because a rebuild is a fresh panel, and the arming press deliberately rebuilds the rows so the
     * armed label is the row's own. Without the guard the arm would be cleared by the rebuild that shows
     * it, and the second press could never confirm anything.
     */
    boolean canvasCopyArming;

    /**
     * Whether the panel is editing the open chapter's theme rather than the player's own.
     *
     * <p>Derived from the tab in {@link BookQuestEditor#buildToolsWidgets}, never toggled: the chapter tab edits the
     * chapter and the book tab edits the player, so there is no switch that can leave one tab's edits
     * pointed at the other's file. The chapter's writes go through the same field op every other
     * chapter edit uses, so an undo takes them back like any other change.
     */
    boolean themeTargetsChapter;

    /** The chapter tab's appearance rows, built with the rest of the chapter's list. */
    List<ToolsLayout.Action> chapterAppearanceRows = List.of();

    /** The panel's list: its rows are widgets, so they move when it scrolls. */
    final dev.ellipog.armature.client.ui.kit.ScrollView toolsView =
            dev.ellipog.armature.client.ui.kit.ScrollView.of(
                    dev.ellipog.armature.client.ui.kit.Viewport.fixed());

    ToolsLayout.Frame toolsFrame;

    /** The colour picker overlay: one colour at a time, opened from a row's chip. */
    final dev.ellipog.tenet.client.dev.ColourPopover colourPopover =
            new dev.ellipog.tenet.client.dev.ColourPopover();

    /** The fields the drawer built, for the label half of their gesture. See {@link #armLabelScrub}. */
    final List<dev.ellipog.tenet.client.dev.ScrubField> rowFields = new ArrayList<>();

    /** The field a label press claimed, between its press and its release. */
    private dev.ellipog.tenet.client.dev.ScrubField labelScrub;

    /** The chapter the picker was opened for, so a chapter switch under it closes it. */
    String popoverChapter;

    /**
     * When the picker opened, in {@code Util.getMillis()} terms; -1 when it is shut.
     *
     * <p>Stamped by both openers beside the filing below. The settle is measured from it, so a picker
     * reopened onto another chip starts its arrival over rather than continuing the old one.
     */
    private long popoverOpenedAt = -1L;

    /**
     * The picker's four fields as placed, by widget.
     *
     * <p>Filed on open, when {@code place} has just run: the per-frame settle writes
     * {@code base + offset} and never the built position itself, so a rebuild mid-settle — same
     * widgets, re-registered — cannot strand a field off its surface. Cleared on close with the
     * widgets it names.
     */
    private final java.util.Map<AbstractWidget, Integer> popoverBaseX =
            new java.util.IdentityHashMap<>();

    /** How far beside its anchor the picker opens before settling into place. Small and quick. */
    private static final int POPOVER_SETTLE_PX = 12;

    /** How long the settle takes: a nudge, in the same band as the menu's. */
    private static final int POPOVER_SETTLE_MILLIS = 110;

    /**
     * How far right of its rest the picker still stands.
     *
     * <p>The panel sits left of its chip, so it arrives by moving left: the full nudge on the frame it
     * opens, nothing once settled, and nothing with motion off. One number for the surface drawing, the
     * four fields and the pointer, so the three cannot disagree mid-settle.
     */
    private int popoverOffset(long now) {
        if (!colourPopover.isOpen() || popoverOpenedAt < 0) {
            return 0;
        }
        float arrived = PanelLayout.revealed(now - popoverOpenedAt, POPOVER_SETTLE_MILLIS);
        return Math.round(POPOVER_SETTLE_PX * (1F - arrived));
    }

    /**
     * Moves the picker's fields with its surface, for this frame.
     *
     * <p>Beside {@link #slidePanelWidgets} in {@code render} and on its terms: absolute writes from
     * filed bases, inert nothing (a 12-pixel settle is not worth disarming presses for), and the
     * pointer shifted by the same offset at the surface's hit sites, so a press meets the surface
     * where it is drawn.
     */
    private void slidePopoverWidgets(long now) {
        if (!colourPopover.isOpen()) {
            return;
        }
        int offset = popoverOffset(now);
        for (net.minecraft.client.gui.components.AbstractWidget widget : colourPopover.widgets()) {
            Integer base = popoverBaseX.get(widget);
            if (base != null) {
                widget.setX(base + offset);
            }
        }
    }

    /** Files the picker's fields at their placed positions and stamps the settle. Both openers call. */
    void filePopoverArrival() {
        popoverBaseX.clear();
        for (net.minecraft.client.gui.components.AbstractWidget widget : colourPopover.widgets()) {
            popoverBaseX.put(widget, widget.getX());
        }
        popoverOpenedAt = net.minecraft.util.Util.getMillis();
    }

    /**
     * The pointer as the picker's surface sees it: shifted left by the settle, so a press meets the
     * tracks and swatches where they are drawn rather than where they rest.
     *
     * <p>The fields need none of this: their widgets travelled, so the vanilla pass already aims at
     * them. Only the hand-tested surface — the panel hit, the press, the drag and the release —
     * reads through here.
     */
    private double pickerPointerX(double mouseX) {
        return colourPopover.isOpen() ? mouseX - popoverOffset(net.minecraft.util.Util.getMillis()) : mouseX;
    }

    /**
     * The colour chip the picker is anchored to: its row, the list that row is drawn in, and how that list
     * composed it.
     *
     * <h2>Why the anchor is a row rather than a token</h2>
     *
     * <p>Because a chip's row is not always a theme token's. An element's fill is a colour in a file and its
     * chip is a row like any other, so the anchor used to be told apart by a token that the element path
     * filled in with <b>null</b> -- and the check that a chip is still on screen answers "not on screen" for
     * a token it cannot find, so an element's picker closed on the frame after it opened whenever the dock was
     * there to run the check. {@code view} is what makes it exact: the same element row can be drawn in the
     * drawer and in the element panel at once, and only the list it came from can say where it is -- see
     * {@link #chipOnScreen}.
     *
     * <p>The composition travels with it rather than being re-derived, because the chip's own press already
     * had to resolve it: a stacked row's chip is in a band under its label, a side-by-side row's is the row.
     */
    record PopoverChip(ToolsLayout.Action row,
                               dev.ellipog.armature.client.ui.kit.ScrollView view,
                               dev.ellipog.armature.client.ui.inspect.InspectLayout.Mode mode) {
    }

    /** What the open picker is anchored to, or null when none is open. */
    PopoverChip popoverChip;

    /** The controls the open colour picker muted, so closing it wakes those and only those. */
    private final List<net.minecraft.client.gui.components.AbstractWidget> mutedBehind = new ArrayList<>();

    /** The frame's pointer, for redraws that run outside the pass that receives it. */
    int renderMouseX;
    int renderMouseY;


    Layout toolsLayout;
    List<ToolsLayout.Action> toolsRows = List.of();

    /** The quest panel's rows and layout, for the tab it is shown on. */
    private List<InspectRow> questRows = List.of();
    private Layout questLayout;

    /**
     * The entries folded to their badges, by {@code member.index}.
     *
     * <p>The same index-keyed shape {@link BookQuestEditor#questFolded} uses, and for the same reason: the key names a
     * position in a list the card rebuilds from the tree, and a key that survived a switch of quests would
     * be folding an entry nobody chose.
     */
    final Set<String> entryFolded = new LinkedHashSet<>();

    /**
     * The tree and replica revisions the quest panel's rows were built at.
     *
     * <p>Two, because the panel reads the <b>replica</b> and the canvas reads the tree, and they move at
     * different times: an op's answer moves the tree first, and the replica it asked for arrives a round
     * trip later. Rebuilding on the tree alone would build rows from a copy that is about to be replaced,
     * and nothing would ever rebuild again -- the panel would show the edit it just committed as it was
     * before it. Either number moving rebuilds the rows.
     */
    private long questPanelRevision = -1;
    private long questReplicaRevision = -1;

    /** What a click in the card's editor can land on. */
    enum EditAction {
        FIELD, FLAG, ITEM, RAW, ADD_TASK, ADD_REWARD, ADD_DEP, PICK_DEP, REMOVE_DEP, COPY_ENTRY,
        REMOVE_ENTRY,
        /** An entry's star: save this task or reward as a component template. */
        SAVE_ENTRY,
        /** A condition's star: save this condition as a component template. */
        SAVE_CONDITION,
        /** A prerequisite's name: the press opens that quest's card, the reader's own jump. */
        NAVIGATE_DEP,
        /** A prerequisite's locate icon: close the card and take the canvas to that node. */
        LOCATE_DEP,
        /** The row's leading strip: a press that travels becomes a reorder. */
        DRAG_ENTRY,
        /** A stepper's two chips: the number moves by one, or by ten with shift held. */
        STEP_UP,
        STEP_DOWN,
        /** A choice: the press moves to the next option in the field's own ring. */
        CYCLE_CHOICE,
        /** A position's button: the corner and the dimension become where the player stands. */
        USE_POSITION,
        /** A field whose ids are a list: the press opens the picker on the field's own source. */
        SEARCH,
        /** The triangle in an entry's corner: fold it to its badge, or unfold it. */
        TOGGLE_ENTRY,
        /** The row under an entry that opens the condition picker. */
        CONDITION_ADD,
        /** A condition's cross: the condition is removed from the entry's list. */
        CONDITION_REMOVE,
        /** The table control's card: the press opens the browser to choose or create a table. */
        TABLE_OPEN,
        /** The table control's chip: the press opens the editor on the table the reward has. */
        TABLE_EDIT,
        /** The table control's cross: the press clears the reference, asking first if a table would go. */
        TABLE_CLEAR,
        /** The browser: take a row, or act on one. */
        TABLE_ROW,
        TABLE_ROW_EDIT,
        TABLE_ROW_COPY,
        TABLE_ROW_DELETE,
        /** The editor's header: the icon, the number of rolls, and the mode the chances are read as. */
        TABLE_ICON,
        TABLE_ROLLS_UP,
        TABLE_ROLLS_DOWN,
        TABLE_PREVIEW,
        /** The editor's header, first line: how a reward row draws this table. */
        TABLE_USE_TITLE,
        TABLE_HIDE_TOOLTIP,
        /** One entry: its weight, its fold, its own fields, and its cross. */
        TABLE_WEIGHT,
        TABLE_WEIGHT_UP,
        TABLE_WEIGHT_DOWN,
        TABLE_FOLD,
        TABLE_REMOVE,
        TABLE_ENTRY_ITEM,
        TABLE_OPEN_CHILD,
        /**
         * A control inside a folded entry, which is where every reward type's own fields live.
         *
         * <p>Five actions rather than one, because what a press does depends on the control's kind and
         * not on the field it names: a switch writes a boolean, a chip cycles a word, a stepper nudges a
         * number, a box opens the inline editor, and an item opens the picker. The fold's control list is
         * what says which of the five a given field is — see {@code TableRowFields.Kind}.
         */
        TABLE_FOLD_FLAG,
        TABLE_FOLD_CHOICE,
        TABLE_FOLD_STEP_UP,
        TABLE_FOLD_STEP_DOWN,
        TABLE_FOLD_TEXT,
        TABLE_FOLD_RAW,
        /** The editor's toolbar and footer. */
        TABLE_ADD_ITEM,
        TABLE_ADD_REWARD,
        /** The toolbar's one Import button: the press opens the two sources under it. */
        TABLE_IMPORT_MENU,
        TABLE_IMPORT,
        TABLE_IMPORT_CHEST,
        TABLE_ROLL,
        TABLE_UNDO,
        /** The footer's way out: one table up, or out of the panel at the root. */
        TABLE_DONE,
        /**
         * The same button off a page above the list: back to the list, panel and all.
         *
         * <p>One action for both pages, because it is one act — "leave whatever is on top" — and the page
         * it leaves is read from the state. Two actions is how the roll report came to have no way out at
         * all: the picker's had one and the report's press fell through to Done.
         */
        TABLE_PAGE_BACK,
        /** The browser's footer: make a table, or leave the panel. */
        TABLE_NEW,
        TABLE_BACK,
        /** The Assets panel: a row, a row's two controls, its footer's New table, and the way out. */
        ASSETS_ROW,
        ASSETS_COPY,
        ASSETS_REMOVE,
        ASSETS_NEW,
        /** A tab in the panel's strip: show the section it names. */
        ASSETS_SECTION,
        /** The Templates page's footer: cycle the sort the shelf is listed in. */
        ASSETS_TEMPLATE_SORT,
        /** A saved template's cross: delete it from this client's shelf, asking first. */
        ASSETS_TEMPLATE_DELETE,
        /** A saved template's pencil: rename it in place, through the inline editor. */
        ASSETS_RENAME,
        /** The element panel's close chip: put the form away and leave the element selected. */
        ELEMENT_CLOSE,
        /** The pinned list's close chip: put the list away. */
        PINNED_CLOSE,
        ASSETS_CLOSE
    }

    /**
     * One pressable piece of the card's editor: what it does, where it is, and what it is about.
     *
     * <p>Rebuilt every frame by the drawing and read by the click -- the radius stepper's arrangement,
     * one derivation for both halves. {@code box} is in screen coordinates, which is what a click is.
     */
    record EditTarget(EditAction action, String path, BookGeometry.Rect box, int textX,
                              int textY, String value, String member, int index) {
    }

    /** The editor's pressable pieces, from the last frame's drawing. */
    final List<EditTarget> editTargets = new ArrayList<>();

    /** The book's own transient messages; see {@link ToastStack} for why chat is not enough. */
    final ToastStack toasts = new ToastStack();

    /**
     * The reward counts the badges read, rebuilt only when the progress, the tree or the marks move.
     *
     * <p>One walk over the entries per revision rather than one per node per frame: the maps answer
     * "what is waiting" for the canvas and the sidebar at once, and both are read every frame.
     *
     * <p>{@code marks} is in the key because a press in flight is not something the player can still take,
     * and it moves nothing else: a claim that has not been answered yet is not a progress revision, so
     * without it the badge over a row the player had just pressed would go on counting it.
     */
    record RewardCounts(long progress, long tree, long marks, java.util.UUID player,
                                Map<String, Integer> byQuest, Map<String, Integer> byChapter) {
    }

    private RewardCounts rewardCounts;

    /** The badge counts in force, recomputed when the progress, the tree, the viewer or the marks change. */
    RewardCounts rewardCounts() {
        long progress = ClientQuestCache.progressRevision();
        long tree = ClientQuestCache.treeRevision();
        long marks = pendingClaims.revision();
        java.util.UUID self = minecraft.player == null ? null : minecraft.player.getUUID();
        RewardCounts current = rewardCounts;
        if (current != null && current.progress() == progress && current.tree() == tree
                && current.marks() == marks && java.util.Objects.equals(current.player(), self)) {
            return current;
        }
        // Walked here rather than taken from the cache's own two maps, because the counts have to come off
        // for a press in flight -- see `outstandingWithMarks`. The two shapes are the cache's deliberately:
        // a quest's figure counts its rewards, and a chapter's counts its *quests*, which is what makes the
        // sidebar's number mean "quests with something waiting" rather than a sum of rewards.
        Map<String, Integer> byQuest = new LinkedHashMap<>();
        Map<String, Integer> byChapter = new LinkedHashMap<>();
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            int outstanding = outstandingWithMarks(self, entry);
            if (outstanding > 0) {
                byQuest.put(entry.id(), outstanding);
                byChapter.merge(entry.chapterId(), 1, Integer::sum);
            }
        }
        rewardCounts = new RewardCounts(progress, tree, marks, self,
                Map.copyOf(byQuest), Map.copyOf(byChapter));
        return rewardCounts;
    }

    /**
     * How many of a quest's rewards are still owed to this player, with their own presses taken off.
     *
     * <p>The one place the counts meet the marks, so every badge in the book reads the same arithmetic as
     * the rows under it. A quest-level or chapter-level mark counts every one of its claimable rewards,
     * because that is what the press asked for; a row-level mark counts its own.
     */
    private int outstandingWithMarks(java.util.UUID self, ClientQuestCache.Entry entry) {
        if (self == null) {
            return 0;
        }
        boolean whole = pendingClaims.isMarked(PendingClaims.questKey(entry.id()))
                || pendingClaims.isMarked(PendingClaims.chapterKey(entry.chapterId()));
        return PendingClaims.outstanding(entry.rewards().size(),
                index -> ClientQuestCache.canClaimReward(self, entry, index),
                index -> whole || pendingClaims.isMarked(PendingClaims.rewardKey(entry.id(), index)));
    }

    /** Whether this player has anything at all left to collect from one quest. */
    boolean claimableWithMarks(java.util.UUID self, ClientQuestCache.Entry entry) {
        return outstandingWithMarks(self, entry) > 0;
    }

    /**
     * The chapter completion counts the header and the sidebar ring read, rebuilt only when the
     * progress or the tree moves.
     *
     * <p>One walk over the entries per revision rather than one per chapter per frame, and the same
     * shape as {@link #rewardCounts} deliberately — but kept a separate cache because it answers a
     * different question. A "count" on a chapter row already means "quests with rewards waiting"
     * ({@code claimableByChapter}); this one means "quests finished", and two maps that both looked
     * like chapter counts would eventually be read for one another.
     *
     * <p>The walk applies the canvas's own visibility rule ({@link #questsIn}): an author counts
     * every quest, hidden flags or not, and a reader counts only what the canvas would draw. The
     * header saying 12/20 beside a canvas showing twelve finished nodes is the point; a denominator
     * nobody can see would make the two disagree by design.
     */
    record ChapterCounts(long progress, long tree, java.util.UUID player, boolean authoring,
                                 Map<String, ChapterProgress> byChapter) {
    }

    private ChapterCounts chapterCounts;

    /** The per-chapter completion in force, recomputed when the progress, the tree or the viewer moves. */
    ChapterCounts chapterCounts() {
        long progress = ClientQuestCache.progressRevision();
        long tree = ClientQuestCache.treeRevision();
        java.util.UUID self = minecraft.player == null ? null : minecraft.player.getUUID();
        boolean authoring = mayEditNow();
        ChapterCounts current = chapterCounts;
        if (current != null && current.progress() == progress && current.tree() == tree
                && java.util.Objects.equals(current.player(), self) && current.authoring() == authoring) {
            return current;
        }

        // Tallied as a mutable pair while walking, because the walk sees one entry at a time and a
        // `ChapterProgress` is immutable by design; the pairs become records in one pass after.
        Map<String, int[]> tally = new LinkedHashMap<>();
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            if (!authoring && !questVisible(entry.id())) {
                continue;
            }
            int[] pair = tally.computeIfAbsent(entry.chapterId(), id -> new int[2]);
            pair[1]++;
            if (ClientQuestCache.stateOf(entry.id()) == QuestState.COMPLETED) {
                pair[0]++;
            }
        }

        Map<String, ChapterProgress> counts = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> entry : tally.entrySet()) {
            counts.put(entry.getKey(), new ChapterProgress(entry.getValue()[0], entry.getValue()[1]));
        }
        // An always-invisible chapter reads complete, whatever its quests say: the legacy
        // getRelativeProgress answers 100 for one. Counted here rather than in the walk above
        // because the walk counts quests and this is a property of the chapter.
        for (ClientQuestCache.ChapterEntry chapter : ClientQuestCache.chapters()) {
            int[] pair = tally.get(chapter.id());
            if (chapter.alwaysInvisible() && pair != null) {
                counts.put(chapter.id(), new ChapterProgress(pair[1], pair[1]));
            }
        }
        chapterCounts = new ChapterCounts(progress, tree, self, authoring, counts);
        return chapterCounts;
    }

    /**
     * Says something just happened, in the book's own stack.
     *
     * <p>Called by {@code QuestNotifier} -- the one detector -- when this screen is the one on screen, for
     * every kind of notice it produces: a quest done, a task of one done, a chapter done. The screen used to
     * diff the cache itself; it is a sink now, which is what lets a completion while the book is closed be
     * announced by the notifier's own sink, and keeps the two from both speaking for one event.
     *
     * <p>It was {@code notifyQuestCompleted} while a completion was the only notice there was. The name says
     * what it does now rather than what it was written for, because a method named for one of its three
     * callers is a method somebody will write a sibling of.
     */
    void notifyNotice(Component message) {
        toast(message.getString(), false);
    }

    /**
     * Says a grant overflowed: what the player's inventory could not hold is on the floor.
     *
     * <p>Called by {@code QuestNotifier} when the server's overflow payload arrives, and only while
     * this screen is the one being read — the action bar carries the same sentence to a player who is
     * not looking at the book, and the HUD is not drawn behind a screen. See {@link ToastStack}.
     */
    void notifyRewardOverflow(int stacks) {
        toast(Component.translatable("tenet.screen.rewards.overflow", stacks).getString(), true);
    }

    /**
     * Says how a sweep ended, over the book.
     *
     * <p>Called by {@code QuestNotifier} when the server's summary arrives. A sweep that stopped for
     * want of room and one that finished both make rows disappear, so the sentence is the only thing
     * that tells them apart — and a silent fifteen-row change reads as a click that did nothing.
     */
    void notifyClaimSummary(int claimed, int total, boolean halted) {
        toast(Component.translatable(halted
                ? "tenet.screen.rewards.claim_halted"
                : "tenet.screen.rewards.claim_done", claimed, total).getString(), halted);
    }

    /**
     * Says a grant overflowed: what the player's inventory could not hold is on the floor.
     *
     * <p>Called by {@code QuestNotifier} when the server's overflow payload arrives, and only while
     * this screen is the one being read — the action bar carries the same sentence to a player who is
     * not looking at the book, and the HUD is not drawn behind a screen. See {@link ToastStack}.
     */
    /**
     * Labels waiting to be drawn, after everything else.
     *
     * <p>A tooltip drawn where it is discovered is a tooltip the next row can paint over -- the row
     * below draws later and wins. Collected and drawn last, the same rule the screen's own tooltip
     * pass follows.
     *
     * <p>What a producer records is the <b>anchor</b> -- the control the caption belongs to -- and not
     * a box. Sizing, folding, the choice of side and the promise that the box is on the screen are
     * {@link Tooltips#drawAt}'s, because a caption is the same surface as a pointer tooltip and a
     * screen that hand-rolled one drew its border a pixel outside the box and clamped against the
     * <i>card</i> -- which is the window's edge only while the book is full-bleed, and never the
     * window's edge on a docked panel. A producer cannot forget any of that now, because it has
     * nothing left to forget.
     */
    final List<PendingLabel> pendingLabels = new ArrayList<>();

    record PendingLabel(BookGeometry.Rect anchor, String text) {
    }

    /**
     * The explanation of a hovered task or reward row, waiting to be drawn with the tooltips.
     *
     * <p>Collected the same way the pending labels are, and for a sharper version of the same reason:
     * the row is drawn <b>inside the card body's clip</b>, and a tooltip belongs over everything,
     * including the edge it reaches past. A box drawn at the row would be cut off at the body's edge;
     * drawn in the tooltip layer it is not. One entry at most per frame -- a pointer is a single point
     * -- but a list, so the collecting code does not have to know that.
     */
    final List<RowTooltip> rowTooltips = new ArrayList<>();

    record RowTooltip(Slot box, List<String> lines) {
    }

    /**
     * A task's or reward's row that can send the player to a recipe viewer, from the last frame's
     * own drawing — the contract {@code editTargets} and the picker rows already use, so a press
     * lands on the row that was drawn and a row scrolled out of the card is not pressable.
     */
    final List<RowItem> rowItems = new ArrayList<>();

    record RowItem(Slot box, RecipeLookups.Target target) {
    }

    /**
     * Filter rows that open the accepted-items list. The whole row, icon to text end: a filter
     * row carries no recipe target ({@code BookRowTargets} answers null for it, since only
     * {@code tenet:item_tag} tasks send a tag), so nothing competes for the press and the row
     * that lights up is the row that answers.
     */
    final List<FilterRow> filterRows = new ArrayList<>();

    record FilterRow(Slot box, String expression) {}

    /**
     * Checkmark rows that hand in on press. The whole row, like a filter row's: a bare checkmark
     * carries no recipe target, so nothing competes for the press — and a titled one answers here
     * only when it has no target either, because a press that could open recipes must keep doing
     * that. Registered while drawing, read by the click, cleared with the other row lists.
     */
    final List<CheckmarkRow> checkmarkRows = new ArrayList<>();

    record CheckmarkRow(Slot box, String questId, int taskIndex) {}

    /**
     * Reward rows that collect on press. Only claimable rows are registered — a finished quest's
     * outstanding rewards — so a press that could open recipes keeps doing that everywhere else.
     * Registered while drawing, read by the click, cleared with the other row lists.
     */
    final List<RewardClaimRow> rewardClaimRows = new ArrayList<>();

    record RewardClaimRow(Slot box, String questId, int rewardIndex) {}

    /** The field being edited inline, by path, and the widget editing it. One at a time. */
    String editingPath;
    ArmatureTextField inlineField;
    ArmatureTextArea inlineArea;

    /**
     * The settings page: shape, size, placement and rules, over the whole card body.
     *
     * <p>It was a popover of stepper rows above the Settings button, and the third playtest's report
     * was that it read as a form — a shape you cycle with + and −, a size that does nothing above 48,
     * and a preview nowhere. The page draws the node it is changing, offers the shapes as shapes, and
     * spans the size the format actually allows. The rows are gone with the popover: what is left of
     * the inspector is the dock's own panel and the type picker.
     */
    boolean settingsOpen;
    final dev.ellipog.armature.client.ui.kit.ScrollView settingsView =
            dev.ellipog.armature.client.ui.kit.ScrollView.of(
                    dev.ellipog.armature.client.ui.kit.Viewport.fixed());
    List<dev.ellipog.tenet.client.dev.QuestSettingsLayout.Row> settingsRows = List.of();
    Layout settingsLayout;
    final dev.ellipog.tenet.client.dev.SettingsDraft settingsDraft =
            new dev.ellipog.tenet.client.dev.SettingsDraft();

    /**
     * The card's field values the server has not answered yet — see {@code FieldDraft} for why a
     * stepper cannot wait for the round trip. It is a second draft rather than the settings page's
     * because it expires on a different signal: the settings preview reads the tree, which arrives in
     * milliseconds, while these fields read the replica, which lags by up to its retry window.
     */
    static final dev.ellipog.tenet.client.dev.FieldDraft fieldDraft =
            new dev.ellipog.tenet.client.dev.FieldDraft();

    /**
     * The canvas elements this client has asked for and the tree has not confirmed: the array's shape.
     *
     * <p>{@code fieldDraft} beside it holds the <i>values</i> inside that shape, under one owner per element;
     * the two are read together in {@link #elementsNow}. See {@code ElementDraft} for why one draft could not
     * do both.
     */
    static final dev.ellipog.tenet.client.dev.ElementDraft elementDraft =
            new dev.ellipog.tenet.client.dev.ElementDraft();

    /**
     * The quest links this client has asked for and the tree has not confirmed: the array's shape.
     *
     * <p>{@code fieldDraft} beside it holds the <i>values</i> inside that shape, under one owner per link;
     * the two are read together in {@link #linksNow}. See {@code LinkDraft} for why one draft could not
     * do both — and why this is not the element draft with a second list in it.
     */
    static final dev.ellipog.tenet.client.dev.LinkDraft linkDraft =
            new dev.ellipog.tenet.client.dev.LinkDraft();

    /**
     * The {@code fieldDraft} owner of one quest link's own fields.
     *
     * <p>A prefix rather than the link's id alone, for the reason the element's own prefix gives: an
     * owner is a key inside a chapter, and a link's id is a name a quest could also have — except that
     * here it cannot, because the index refuses it. The prefix stays anyway: two namespaces sharing
     * one drawer is how a value lands on the wrong row, and the drawer is cheap.
     */
    static final String LINK_OWNER_PREFIX = "#link:";

    /**
     * The {@code fieldDraft} owner of one canvas element's own fields.
     *
     * <p>A prefix rather than the element's id alone, because an owner is a key inside a chapter and an
     * element's id is a name that a quest could also have: an element called {@code first_steps} and a quest
     * with the same id are two different things, and one shared owner would put their pending values in one
     * place.
     */
    static final String ELEMENT_OWNER_PREFIX = "#element:";

    /** Which slider the pointer has hold of ("size"/"iconScale"/"rotation"), or null. */
    String draggingSlider;

    /** The chapter panel's rows and layout, for the dock's Chapter tab. */
    /**
     * The Chapter tab's own rows: the open chapter's identity, rules, group and quest list.
     *
     * <p>Tools rows, like the Book tab's beside them -- see {@code ChapterPanelLayout.rows}, which builds
     * them, and {@code ToolsPanel}, which draws them. They were {@code InspectRow}s until this round, which
     * is why several comments in this file still name the inspector: the chapter's rows are the drawer's
     * now, and the inspector keeps the quest card, the settings card and the rewards list.
     */
    List<ToolsLayout.Action> chapterRows = List.of();
    Layout chapterLayout;

    /** Which list the modal's type picker is adding to ("tasks"/"rewards"), or null when it is closed. */
    String pickingEntryType;

    /**
     * A type picker row that applies a saved setup instead of a fresh default: the template's id
     * under this prefix.
     *
     * <p>The prefix because a picker row's id is otherwise a type id, and a template's name is
     * free text an author chose — "Kill" could be either. The Assets page needs no prefix: its
     * rows resolve inside their own section, where a template id can never meet a quest id.
     */
    static final String TEMPLATE_ROW_PREFIX = "template:";

    /**
     * Which entry's conditions list the picker is adding to, as a path ("tasks.3"), or null.
     *
     * <p>Separate from {@link #pickingEntryType} rather than a third member name, because the insert is
     * a different op: a task is a new element of a top-level list ({@code EditorOp.Insert}), and a
     * condition is a rebuilt nested array written with {@code SetField} — nested arrays have no Insert.
     * When this is set the picker lists condition types; when the member is set it lists that member's.
     */
    String pickingConditionFor;

    /** Which field the item picker is setting, or null when it is closed. */
    String pickingItemPath;

    /**
     * What the open picture picker is showing: one tab of the four arms.
     *
     * <p>Only meaningful when the pick is for an icon (see {@code isIconPick}); every other pick —
     * an item field, a registry search, a table entry — is items-only exactly as before, with no
     * tabs drawn. The kind resets to ITEM on every open, so a file pick last week does not greet
     * this week's item field; switching tabs resets that tab's query and selection, because an
     * index into another tab's list names nothing here (the fresh-list rule the texture picker
     * already follows).
     */
    /* Package-visible for the picker tests: the tabs are screen state, the payloads are not. */
    enum PickerKind { ITEM, FILE, SPRITE, ENTITY }

    PickerKind pickerKind = PickerKind.ITEM;
    String pickerFileQuery = "";

    /** The tab strip's height, in pixels: one row of four tabs under the picker's header. */
    static final int PICKER_TAB_STRIP = 16;

    /**
     * Whether the open pick is for an icon rather than an item: the dock's chapter/group/book
     * picks always are, and a quest pick is when its path names an icon object (the quest's own,
     * the book's, or a task's or reward's override). Only icon picks draw the kind tabs; every
     * other pick is items-only exactly as before. Table icons are item-only by schema, so a
     * table pick never qualifies even though its path says "icon".
     */
    boolean isIconPick() {
        if (tablePickPath != null) {
            return false;
        }
        if (pickTarget != null && pickTarget != PickTarget.QUEST) {
            return true;
        }
        return isIconPath(pickingItemPath);
    }

    /**
     * Whether a quest field path names an icon object: the quest's own, the book's, or a task's
     * or reward's override.
     *
     * <p>Pure, so the picker tests pin which paths greet with tabs: an icon path is any path the
     * icon commits write whole objects to, and the two shapes are the quest's own {@code icon}
     * (leaf or book) and an entry's {@code icon} object.
     */
    static boolean isIconPath(String path) {
        return "icon.item".equals(path) || "bookIcon".equals(path)
                || (path != null && path.endsWith(".icon"));
    }

    /**
     * What the open picker is listing, when it is not items.
     *
     * <p>A search field -- a dimension, a biome, a statistic -- opens the same card, the same list and the
     * same box as the item picker does; what changes is where the rows come from. Null means items, which
     * is what every pick before this one was.
     */
    EditorField.Source pickerSource;

    /**
     * The observation mode the open search field was opened under, and empty for every other source.
     *
     * <p>Kept rather than re-read while drawing, because it is what the list was gathered for: the
     * target field of an observation task lists blocks for a block observation and entity tags for an
     * entity one, so a mode re-read a frame later would be a list and a rule that disagreed. It is also
     * what says whether the field's values are ids or a `#tag` -- see {@code EditorSources#valueKind}.
     */
    String pickerObserveType = "";

    /**
     * Whether the picker was opened from the settings page, and should hand back to it.
     *
     * <p>The page's Icon row opens the same picker the card does, and the picker closes the page while it
     * is up -- a list is what you are reading, and the page behind it is context. What the report caught
     * was the other half: choosing an item closed the picker and left the author in the card's editor,
     * with the page they were working on gone. Coming back is the whole of "Change…" as a button on that
     * page rather than a detour out of it.
     */
    boolean pickerFromSettings;

    /**
     * Which of the three things an open picker is setting: a quest's field, the chapter's icon, or its
     * group's icon.
     *
     * <p>The picker is one surface with three callers now, and the pick decides three different ops on
     * two different files -- {@code SetField} on a quest, {@code SetChapter} and {@code SetGroup} on the
     * chapter's own and its group's. A single nullable field rather than three booleans, so "which file"
     * has one answer and a pick from the dock can never be written to whatever quest happens to be
     * selected. Cleared with the picker's other state.
     */
    enum PickTarget { QUEST, CHAPTER, GROUP, BOOK }

    /**
     * What the open picker card says it is for, in the caller's own words.
     *
     * <p>Empty means "ask {@link PickTarget}" — which is right for the three icon picks, whose sentences
     * name the subject they already carry in {@code pickName}. A pick that is not about a chapter, a
     * group or the book has no sentence there, and used to get the chapter's.
     */
    String pickTitle = "";

    /** The open picker's target, or null when it is closed. */
    PickTarget pickTarget;

    /**
     * The icon and the name the picker's own card shows, so the card says what it is about.
     *
     * <p>Set when the picker opens and refreshed whenever its widgets are rebuilt: the row the press came
     * from is behind the card, and "Pick an item" alone would not say which chapter's or group's icon is
     * being set. For a group these are the group's <b>own</b> icon and title -- an empty stack is the
     * honest picture of a group that has none of its own yet.
     */
    ItemStack pickIcon = ItemStack.EMPTY;
    String pickName = "";
    /** The picker's subject picture when it wears a texture or a sprite icon. */
    String pickTexture = "";
    String pickSprite = "";

    /** The value that field holds now, for the clear row; empty when there is none. */
    String pickingItemCurrent = "";

    /**
     * The path a clear removes, when clearing is legal at all; null leaves the clear row off.
     *
     * <p>From {@link ItemPicker#clearPath}: the icon, and only the icon. A clear row on a task used
     * to delete a required field, save, and take the quest out of the tree.
     */
    String pickingItemClearPath;

    /** The picker's search box, while it is open. Read for its text and cleared with the picker. */
    ArmatureTextField itemSearch;

    /**
     * The type picker's own state: its search box, its rows, the band they are drawn in and the scroll.
     *
     * <p>Beside the item picker's rather than sharing it, which is not tidiness: the two are laid out in
     * different bands (that one over the card, this one inside whichever card owns the page), and one set
     * of fields would be two layouts in one variable. The rows are the last drawing's, so the press reads
     * what was drawn rather than a second computation of it.
     */
    ArmatureTextField typeSearch;
    List<ItemPickerLayout.Row> typeRows = List.of();
    ItemPickerLayout.Frame typeFrame;
    String typeQuery = "";
    int typeSelected = -1;
    /** The list's scroll, its clamp and its bar as one object, and the strip the bar is drawn in. */
    final Viewport typeBody = Viewport.fixed();
    final ScrollBar typeBar = new ScrollBar(typeBody);

    /** The registry's entries, and the player's carried stacks -- gathered once, when the picker opens. */
    List<ItemPicker.Entry> pickerEntries = List.of();
    List<ItemPicker.Entry> pickerInventory = List.of();

    /**
     * The picker's last drawing: the rows, the frame and the scroll the press has to agree with.
     *
     * <p>The click reads these rather than recomputing, for the reason every drawn control here is
     * hit-tested from the drawing's own derivation: two computations of the same list, taken a frame
     * apart, are two chances to land on different rows.
     */
    List<ItemPickerLayout.Row> pickerRows = List.of();
    ItemPickerLayout.Frame pickerFrame;
    List<ItemPicker.Entry> pickerMatches = List.of();
    String pickerQuery = "";
    int pickerSelected = -1;
    /** The list's scroll, its clamp and its bar as one object, on the type page's own terms. */
    final Viewport pickerBody = Viewport.fixed();
    final ScrollBar pickerBar = new ScrollBar(pickerBody);
    List<ItemPicker.Entry> textureMatches = List.of();
    List<ItemPickerLayout.Row> textureRows = List.of();
    ItemPickerLayout.Frame textureFrame;
    int textureSelected = -1;
    /** The list's scroll, its clamp and its bar as one object, on the same terms again. */
    final Viewport textureBody = Viewport.fixed();
    final ScrollBar textureBar = new ScrollBar(textureBody);

    /**
     * The sizes the texture rows' pictures read, keyed by file.
     *
     * <p>Their own map because the row and the picker both draw {@code ToolsPanel.drawTextureThumb} and
     * each asks the PNG's header for its dimensions; a keep per file turns a per-frame read into one per
     * file per session. It lives with the screen rather than the drawing for the reason every other
     * piece of state does -- the drawing is a pure function of what it is handed.
     */
    final Map<Identifier, Optional<GuiRenderer.TextureSize>> textureSizes =
            new LinkedHashMap<>();

    /**
     * The row drag: which list, which row, and the y the line is drawn at.
     *
     * <p>One state for three lists -- a quest's tasks, its rewards, and the dock Chapter tab's quest
     * list -- because the gesture is one gesture. What differs is the op the release sends, and that is
     * read off {@link #dragRowMember}. Null/&#8209;1 between drags.
     */
    String dragRowMember;
    String dragRowQuest;
    int dragRowFrom = -1;
    boolean dragRowLive;
    double dragRowPointerY;

    /**
     * The sidebar tree's drag: the row a press landed on, whether it has travelled, and what it means.
     *
     * <p>A second set of fields rather than reuse of the row drag's, because the tree's gesture has one
     * question the flat lists do not: <b>which container</b>. The row keys are the sidebar's own
     * ({@code group:} / {@code chapter:} prefixes), and {@link SidebarDrag} answers with a container key
     * and a position among its children — so what is kept here is the key and the last answer, not an
     * index into a list that the drop itself may move things out of.
     */
    String sidebarDragKey;
    boolean sidebarDragGroup;
    boolean sidebarDragLive;
    double sidebarDragPointerY;
    double sidebarDragPressX;
    double sidebarDragPressY;
    SidebarDrag.Drop sidebarDrop;

    /** Whether the naming card is open, and what it is naming. */
    Naming naming;

    /** Which of the three things the naming card does. */
    enum NamingMode {
        CREATE,
        RENAME,
        DUPLICATE
    }

    /**
     * One naming request: what is being named, what is being done to it, and where a new one goes.
     *
     * <p>One record for all three modes rather than three paths, because create, rename and duplicate
     * differ only in the op they send and the id they start from -- and a second copy of the card for
     * each would be three places the validation rule could drift apart.
     */
    record Naming(String kind, NamingMode mode, String targetId, String groupId, int index) {
    }

    /**
     * The sidebar's inline create or rename, when one is in flight.
     *
     * <p>The naming card used to do this from the right rail; creates and renames happen in the
     * list itself now, where the author clicked. The card keeps duplicates, which is the one mode
     * that pre-fills both fields rather than growing an id from a title.
     */
    InlineNaming inlineEdit;

    /**
     * One inline create or rename: what is being made, and where it goes.
     *
     * <p>The op layer is untouched — the session carries what {@code submitNaming} read from the
     * card, and the commit sends the same ops with the same ids. {@code index} is not carried
     * because a create always appends: the server clamps the index into the target group.
     */
    record InlineNaming(String kind, NamingMode mode, String targetId, String groupId,
                                boolean showId) {
        InlineNaming withShowId(boolean show) {
            return new InlineNaming(kind, mode, targetId, groupId, show);
        }
    }

    /** The inline session's title field, and the id field while its disclosure is open. */
    ArmatureTextField inlineTitle;
    ArmatureTextField inlineId;
    static final String INLINE_STATUS_KEY = SidebarLayout.INLINE_PREFIX + "status";

    /** The sidebar's right-click menu: its items and where it was opened. Empty when it is shut. */
    List<BookMenu.MenuItem> menu = List.of();
    int menuX;
    int menuY;

    /**
     * When the menu opened, in {@code Util.getMillis()} terms; -1 when it is shut.
     *
     * <p>Stamped by {@link #openMenu} — the one writer — and read by {@link BookMenu#menuRise}. Cleared with the
     * items in {@link BookMenu#closeMenu}, so a shut menu never owes an arrival.
     */
    long menuOpenedAt = -1L;

    /**
     * The menu row whose submenu is showing, or -1.
     *
     * <p>State rather than a question asked of the pointer, and that is the whole of the fix for a
     * submenu that vanished on the way to it: while the pointer is on the panel — or in the bridge
     * between the two panels — this stays where it was, so travelling to a group does not cross a frame
     * where the row is not hovered and the destinations are gone.
     */
    int submenuRow = -1;

    /** Whether the press being released was a right-press on the canvas, for the click-vs-drag split. */
    private boolean canvasRightPressed;

    /**
     * The curve being bent by hand: which line, whether the drag has started, and the bow it previews.
     *
     * <p>The preview is a field rather than a file write because a drag is one edit: the drawing reads it
     * so the curve follows the hand, and the release is the one op that makes it real.
     */
    String bendDragFrom;
    String bendDragTo;
    private boolean bendDragLive;
    private Double bendPreview;

    /**
     * How far the pointer was from the handle's own value when it was grabbed.
     *
     * <p>The whole of the "it jumps when I start dragging" fix: the drag is applied as a delta from the
     * grab, so the first live pixel moves the line by one pixel rather than snapping the value to wherever
     * the pointer happened to be pressed.
     */
    private double bendDragGrab;
    private double anchorDragGrab;

    /** Where inside a control point's own value the pointer grabbed it, in the chord's frame. */
    private double handleGrabAlong;
    private double handleGrabAcross;

    /** Which handle of that line is being dragged: "from", "to" or "bend". */
    String bendDragKind;
    /** The angle an end handle is previewing, or null. */
    private Double anchorPreview;

    /** A split control point being dragged, as `[along, across]`. */
    private java.util.List<Double> handlePreview;

    /**
     * The rows each list is drawn at, this frame, in drawing order -- the seam the gap is counted
     * against. Rebuilt by the drawing and read by the release, one derivation for both.
     */
    final Map<String, List<BookGeometry.Rect>> dragRowSlots = new LinkedHashMap<>();

    /** Whether the modal's Delete has been pressed once and is waiting for the confirming second. */
    boolean confirmingDelete;

    /**
     * The card's armed entry removal: which row's cross asked, or null.
     *
     * <p>Keyed by the row's own path, because that is what the press names — and consumed by the <b>next</b>
     * press whatever it is, because a path is a position rather than an id: an arm that outlived the row it
     * named would be spent on whatever moved into that position. {@code pressEditTarget} takes it at the
     * top, which is the one door every card press comes through.
     *
     * <p>The table editor's own entry cross has asked twice since it was written, and the comment there
     * says it is "the same confirm-in-place the inline table's removal uses" — but the card's rows, which
     * are the same control in the other panel, did not ask at all.
     */
    String entryConfirmRemove;

    /**
     * The Delete key's own two-press rule, which is the same rule.
     *
     * <p>Held as an object rather than a boolean because it also remembers <b>which</b> quests were named,
     * and that is the part a field cannot express: a boolean would let the confirming press delete a
     * selection the author was never asked about. See {@link ArmedDelete}, which is where the rule lives
     * so it can be tested without a running client.
     */
    final ArmedDelete armedDelete = new ArmedDelete();

    /** The modal's Delete, so a press anywhere else can disarm it. The party's Disband set the shape. */
    ArmatureButton questDeleteButton;

    /**
     * The node the drag is carrying, once the drag is one: past the threshold, following the pointer.
     *
     * <p>Null between a press and the threshold, so a click never looks like a wiggle; the node under
     * the pointer at press is {@link #pressedNode} until then.
     */
    String draggedNode;

    /**
     * Where that node is now, in content coordinates, and where inside it the pointer grabbed it.
     *
     * <p><b>Not named {@code dragX} and {@code dragY}.</b> Vanilla's {@code mouseDragged} declares its
     * own parameters under exactly those two names, and a field under the same name is shadowed by
     * them for the whole method body: the follow step's {@code dragX = ...} wrote the <i>parameter</i>,
     * the field never moved, and the drag — which reads the field to draw the node and to commit it —
     * did nothing at all while looking exactly like code that worked. Every commit was 0,0 until the
     * press read the position first, after which every commit was the press position: a drag that
     * cannot move the thing it is dragging. The names are the fix; the state machine around them is
     * the rewrite.
     */
    float dragNodeX;
    float dragNodeY;
    float dragGrabX;
    float dragGrabY;

    /**
     * Whether the drag has passed the threshold and is now carrying the node.
     *
     * <p>Press, then threshold, then follow: a pointer that has moved a pixel or two since the press
     * has not decided to drag anything yet, and a node that twitched after every click would read as a
     * jitter the author did not ask for. Between the press and this flag the press is still a possible
     * click; once it is set the node follows and release commits.
     */
    private boolean nodeDragLive;

    /**
     * Which modifiers the canvas press was made under.
     *
     * <p>Remembered at press and not re-asked at release, because letting go of shift a moment before
     * the mouse comes up must not turn a shift-click into a plain one -- that would select and open
     * the node the press was adding, and clear the rest of the selection doing it.
     */
    private boolean pressedShift;
    private boolean pressedCtrl;

    /** Read at the press like the others: Alt+click straightens a node, Alt+drag places it freely. */
    private boolean pressedAlt;

    /**
     * This screen's own controls, in the order they were created.
     *
     * <p>Kept because {@code Screen.renderables} is private — established by the compiler after being
     * assumed otherwise. Keeping the list is the better arrangement anyway: a screen that knows which
     * widgets are its own can ask them questions the vanilla base class has no concept of, which is
     * what {@link #drawTooltips} does.
     */
    final List<ArmatureButton> buttons = new ArrayList<>();

    /**
     * What an open panel draws <b>after</b> the widget pass, because the widget pass cannot draw it.
     *
     * <h2>What is in here, and the much longer list that is not</h2>
     *
     * <p>A panel is drawn in the chrome band, <i>before</i> the widget pass, so its controls are already
     * on top of their own surface and need nothing from this list. What needs it is the ink the pass has
     * no widget for: a search box's placeholder, which is text drawn over the field's own fill, and the
     * clip a placeholder needs so it cannot paint outside the band it belongs to.
     *
     * <p><b>This list used to hold the fields themselves</b>, and every one of them was there for a reason
     * that no longer exists: a card was painted <i>after</i> the widget pass, so a field left to that pass
     * was painted over -- placed, clickable and invisible, which is the fourth playtest's "labels drawn by
     * the panel, every field blank". A column is drawn before the pass, so the fields are visible with no
     * help, and re-drawing them here would be the same pixels paid for twice. They are gone; the
     * placeholders they shared a closure with are what stayed.
     *
     * <h2>The one thing the pass genuinely cannot draw</h2>
     *
     * <p>{@code drawOpenEditor} -- the inline field an author types into -- is a {@code addWidget} child
     * rather than a renderable one, deliberately: a renderable copy could not be clipped and showed through
     * the world as a blurred ghost. So it is drawn by hand, and it used to be drawn <b>only</b> inside the
     * modal path -- which means that in the one presentation this mod has, the field an author is typing
     * into was never painted at all. It is drawn after this list for the same reason these entries are.
     */
    final List<java.util.function.Consumer<GuiRenderer>> panelOverlays = new ArrayList<>();

    /**
     * How many rosters had arrived when the party panel was last built.
     *
     * <h2>Why a counter, and why the panel needs one at all</h2>
     *
     * <p>Because a party's roster is <b>pushed while the panel describing it is open</b>.
     * {@code PartySyncPayload}'s own note names that as the case that stops the roster being
     * request-only: somebody accepts an invite or an officer removes somebody, and the person looking
     * at the panel is owed a redraw. The widgets are created in {@code init} and placed once, so a
     * message arriving in between changes {@link ClientPartyCache} and nothing else — the panel goes on
     * drawing the roster it was built with, which is a party that has since gained or lost a member.
     * Every row in it is still a real name, which is what makes it convincing.
     *
     * <p>Compared against a counter rather than against the two snapshots, and that is
     * {@code ClientQuestCache.treeRevision}'s argument read the other way: "a roster arrived" is a fact
     * about a message, while "the roster differs" is a judgement this screen would have to make with
     * its own answer for an identical re-send. {@code -1} rather than {@code 0} so the initial value
     * cannot be mistaken for a real roster that has been drawn.
     */
    /**
     * Whether this client has told Armature what the book's panels watch.
     *
     * <h2>Why the list is here, and why it is guarded</h2>
     *
     * <p>Because a panel that watches nothing is exactly the failure this replaces — the one that has to
     * be closed and reopened — and the sources are <b>this screen's own</b>: each is a cache it draws
     * from. Registering on the first {@code init} keeps the list beside the drawing that depends on it,
     * rather than in a loader's setup file, once per loader, which is where the third panel's entry gets
     * forgotten.
     *
     * <p>Guarded because {@code init} runs on every resize and after every automatic rebuild, and the
     * registry is a list: registering again on each pass would grow it with the number of times the
     * window was resized.
     *
     * <p>What each one buys: the <b>tree</b> is the sidebar's rows, so a reload rebuilds them; the
     * <b>party</b> is the roster's rows and the Remove buttons placed in them. Progress is deliberately
     * not watched here: it increments on every item-count tick and lock refresh, and tearing down the
     * whole widget list for a task count moving is a hitch on every sync. Progress-driven widgets
     * (chapter gates, reward counts, the reader footer and the rewards inbox) check their own state.
     */
    private static boolean watchingSources;

    private static void watchSources() {
        if (watchingSources) {
            return;
        }
        watchingSources = true;
        ArmatureLive.watch("tenet.tree", ClientQuestCache::treeRevision);
        ArmatureLive.watch("tenet.party", ClientPartyCache::rosterRevision);
    }

    /**
     * Close, which is in {@link #buttons} but is not drawn by the widget pass.
     *
     * <h2>Why this is a field rather than one more entry in the list</h2>
     *
     * <p>Because Close is the only control that lives in the header, and the widget pass is clipped
     * from the sidebar's list top downwards — so that a scrolled row can never be drawn through the
     * title bar. That clip would swallow Close, which is why it is drawn by hand in the chrome layer
     * instead. The field is what lets that happen; see `buildHeaderChrome`.
     *
     * <p>It is still in {@link #buttons} as well, and that is deliberate rather than redundant:
     * {@code drawTooltips} walks that list, so a control missing from it would draw and respond and
     * simply have no tooltip.
     */
    ArmatureButton closeButton;

    public QuestBookScreen() {
        super(Component.translatable("tenet.screen.quest_book.title"));
    }

    /**
     * The screen's client, for the book's slices: {@code Screen.minecraft} is protected, and a slice
     * holds the screen rather than extending it.
     */
    Minecraft minecraft() {
        return minecraft;
    }

    /** {@code Screen.removeWidget}, for the book's slices, for the reason {@link #minecraft()} is. */
    void removeBookWidget(net.minecraft.client.gui.components.events.GuiEventListener widget) {
        removeWidget(widget);
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    // Every position below now comes from BookGeometry, which has no Minecraft in it and so can be
    // tested. These stay as named methods because the drawing code reads better for them --
    // `canvasRight()` says more at a call site than `geometry().canvas().right()` -- and because
    // renaming them all would be a large diff for no behavioural gain. The *bodies* are what had to
    // change, and that is the fix: there is now exactly one expression per position, and the tests
    // assert the properties of it.

    private int panelWidth() {
        return geometry().panel().width();
    }

    private int panelHeight() {
        return geometry().panel().height();
    }

    private int panelLeft() {
        return geometry().panel().x();
    }

    private int panelTop() {
        return geometry().panel().y();
    }

    int canvasLeft() {
        return geometry().canvas().x();
    }

    /**
     * The canvas' right edge — the whole of it, under the tools panel and all.
     *
     * <h2>The panel floats, and this is the whole of it</h2>
     *
     * <p>The canvas is drawn across its full rectangle and the tools panel sits over the right of it,
     * so the surface shows through the panel's margins and the panel reads as hovering over the graph
     * rather than glued to a differently-coloured strip. That is what {@code ToolsLayout} has always
     * said the panel does; this method used to say the opposite and the code agreed with the method —
     * the canvas was shrunk by the panel's width, the margins around the panel were the book's
     * background, and setting a chapter palette made the seam visible as a band.
     *
     * <p>Everything that reads the canvas reads this: the backdrop and its clip, the node and edge
     * culls, and the viewport, so the graph may pan under the panel — where nodes are hidden by it and
     * a press is intercepted by {@code inTools} before {@code inCanvas} is asked. One expression, so
     * the drawn canvas and the interactive canvas cannot be two rectangles.
     */
    int canvasRight() {
        return geometry().canvas().right();
    }

    /**
     * Where the canvas' content should stay <b>visible</b>: left of the tools panel, when it is open.
     *
     * <p>For the furniture that is no use under a panel — the zoom hint, a node's caption and label
     * clamps, and where a new quest is born. Everything else draws and hit-tests to
     * {@link #canvasRight()}; these four ask where the graph is still <i>seen</i>.
     */
    int visibleCanvasRight() {
        // **One expression for every dock, the author's included.** A column covers the graph, so the
        // furniture that is no use under it -- a node's caption, a label's clamp, where a new quest is born
        // -- stays clear of the innermost presented column's inner edge. With nothing presented this is the
        // canvas's own right edge, which is what a caller with no panel wants and why it needs no branch.
        //
        // It used to ask two different questions: a docked overlay's rail, or the tools dock's frame. The
        // dock is a column now, so the question is the one the columns can answer -- and the branch that
        // named it was the reason a 300-pixel dock and a 340-pixel column disagreed about the clamp.
        return PanelLayout.visibleRight(geometry().canvas(), panelRails());
    }

    /**
     * The rails on screen, in draw order.
     *
     * <p>One list, for the reason {@link #panelColumns()} is one list: the furniture's clamp, the press's
     * columns and the auto-pan all have to agree about where the panels are, and two loops over the same
     * arrangement would be two chances to disagree about the folded case.
     */
    private List<BookGeometry.Rect> panelRails() {
        List<BookGeometry.Rect> out = new ArrayList<>(3);
        for (PanelKind kind : PanelStack.presented(columns(), panelFolded())) {
            out.add(panelRail(kind));
        }
        return out;
    }

    int canvasTop() {
        return geometry().canvas().y();
    }

    int canvasBottom() {
        return geometry().canvas().bottom();
    }

    private boolean inCanvas(double mouseX, double mouseY) {
        return geometry().canvas().contains(mouseX, mouseY);
    }

    // --- the shared positions. One expression each, used by drawing and by the controls ---

    /**
     * Where the header's right-hand text has to stop, so the quest count does not run under Close.
     *
     * <p>There is no footer to ask about any more. The four controls that were in it are three square
     * map buttons in the canvas's own corner and a close button in the header — see
     * {@link BookGeometry}'s class comment for why each moved, and what each replaced.
     */
    private int headerRightLimit() {
        // One expression for every player now. It used to ask whether the author's pair would be built,
        // because that pair sat in the header and the count had to stop short of it; the pair is a pill
        // over the canvas now, and the header is the same four controls whoever is looking at it.
        return geometry().headerRightLimit();
    }

    /**
     * The header's right-hand summary: the book's total quests, the open chapter's own count, and how
     * much of that chapter is done.
     *
     * <h2>Three draws rather than one string</h2>
     *
     * <p>The total is deliberately the faintest of the three. The numbers a player can act on are the
     * open chapter's own, and the pack-wide count is context for them rather than a figure in its own
     * right — so it recedes, and the chapter's pair stays the ink the summary always was. One
     * {@code text} call cannot carry two colours, so the string is drawn in segments, right-aligned to
     * the same edge the old summary used.
     *
     * <h2>Dropped from the left</h2>
     *
     * <p>Too narrow for all three drops the total first, then — still too narrow — the whole summary:
     * a missing count is a smaller fault than a title with a number drawn through it, and the
     * chapter's own pair is the part worth keeping. The zoom percentage this replaced is simply gone;
     * the view cluster already answers what the zoom is by what it does, and the slot says something
     * about the chapter instead.
     */
    private void drawHeaderSummary(GuiRenderer r, int left, int top) {
        String chapter = effectiveChapter();
        ChapterProgress progress = chapter == null ? null : chapterCounts().byChapter().get(chapter);
        int done = progress == null ? 0 : progress.done();
        int quests = progress == null ? 0 : progress.total();
        int percent = progress == null ? -1 : progress.percent();

        String sep = " \u00b7 ";
        String totalText = Integer.toString(ClientQuestCache.questCount());
        String countText = Integer.toString(quests);
        String percentText = percent < 0 ? null : percent + "%";

        int titleLimit = headerTitleX(left) + r.textWidth(bookTitle().getString()) + 12;
        int width = r.textWidth(totalText) + r.textWidth(sep) + r.textWidth(countText)
                + (percentText == null ? 0 : r.textWidth(sep) + r.textWidth(percentText));
        int x = headerRightLimit() - width;
        boolean withTotal = true;
        if (x <= titleLimit) {
            withTotal = false;
            width = r.textWidth(countText)
                    + (percentText == null ? 0 : r.textWidth(sep) + r.textWidth(percentText));
            x = headerRightLimit() - width;
            if (x <= titleLimit) {
                return;
            }
        }

        if (withTotal) {
            r.text(totalText, x, top + 9, Colour.shade(ArmatureTheme.faint(), -0.4F));
            x += r.textWidth(totalText);
            r.text(sep, x, top + 9, ArmatureTheme.faint());
            x += r.textWidth(sep);
        }
        r.text(countText, x, top + 9, ArmatureTheme.faint());
        x += r.textWidth(countText);
        if (percentText != null) {
            r.text(sep, x, top + 9, ArmatureTheme.faint());
            x += r.textWidth(sep);
            r.text(percentText, x, top + 9, ArmatureTheme.faint());
        }
    }

    /** The backing panel behind the three view buttons, drawn so they read as one cluster. */
    private BookGeometry.Rect viewControls() {
        return geometry().viewControls();
    }

    /**
     * The book's identity in the header: the pack's icon when it declares one, then its name.
     *
     * <p>Both come from the tree, so a pack that names its book is announced as that book rather than
     * as the generic one; a pack that declares nothing gets the translatable title and no icon, which
     * is exactly what every pack predating the fields gets. An icon id that did not resolve draws the
     * same "missing item" mark the chapter header draws — a "?" in the blocked colour — so a typo in
     * the file reads as a missing item rather than as an empty space.
     */
    private void drawHeaderIdentity(GuiRenderer r, int left, int top) {
        int icon = headerIconSize();
        if (icon > 0) {
            int iconY = top + 9 - (icon - 8) / 2;
            ItemStack stack = ClientQuestCache.bookIcon();
            String texture = ClientQuestCache.bookTextureIcon();
            String sprite = ClientQuestCache.bookSpriteIcon();
            if (!stack.isEmpty()) {
                r.icon(stack, left + BookGeometry.HEADER_INSET, iconY, icon);
            }
            else if (!texture.isEmpty()
                    && net.minecraft.resources.Identifier.tryParse(texture) != null) {
                r.texture(net.minecraft.resources.Identifier.parse(texture),
                        left + BookGeometry.HEADER_INSET, iconY, icon, icon);
            }
            else if (!sprite.isEmpty()
                    && net.minecraft.resources.Identifier.tryParse(sprite) != null) {
                r.sprite(net.minecraft.resources.Identifier.parse(sprite),
                        left + BookGeometry.HEADER_INSET, iconY, icon, icon, 0xFFFFFFFF);
            }
            else {
                int x = left + BookGeometry.HEADER_INSET;
                r.fill(x, iconY, x + icon, iconY + icon, ArmatureTheme.blocked());
                r.centredText("?", x + icon / 2, iconY + (icon - 8) / 2, ArmatureTheme.title());
            }
        }
        // Truncated to the room the header has, because the name is a pack's string now: an overlong one
        // must not run under the close button or the summary beside it.
        String name = Measure.truncate(bookTitle().getString(),
                Math.max(0, headerRightLimit() - headerTitleX(left) - 12),
                Measure.of(r::textWidth, r.lineHeight()));
        r.text(name, headerTitleX(left), top + 9, ArmatureTheme.title());
    }

    /** The header's icon box, or zero when the pack declares no icon at all. */
    private static int headerIconSize() {
        return ClientQuestCache.bookIconId().isEmpty() && ClientQuestCache.bookTextureIcon().isEmpty()
                && ClientQuestCache.bookSpriteIcon().isEmpty()
                ? 0 : 12;
    }

    /** Where the header's title starts: after the inset, and after the icon when there is one. */
    private static int headerTitleX(int left) {
        int icon = headerIconSize();
        return left + BookGeometry.HEADER_INSET + (icon > 0 ? icon + 4 : 0);
    }

    /** The book's title: the pack's own when it declares one, else the translatable default. */
    Component bookTitle() {
        String pack = ClientQuestCache.bookTitle();
        return pack.isEmpty() ? title : Component.literal(pack);
    }

    /**
     * What this screen is called — the pack's name when it declares one.
     *
     * <p>Overridden rather than left as the field so narration and accessibility read the same name
     * the header draws; a screen whose spoken title disagreed with its written one is the kind of
     * mismatch nobody reports and everybody notices.
     */
    @Override
    public Component getTitle() {
        return bookTitle();
    }

    /** Where a chapter row starts, and so the top of the list. */
    private int chapterListTop() {
        return geometry().chapterListTop();
    }
    /**
     * The local player's id, or null when there is none.
     *
     * <p>A roster needs a <i>viewer</i>: it decides which row is yours and which rows carry a Remove
     * button. {@code minecraft.player} is null for a screen driven by a test's recording renderer, and
     * null is passed through rather than substituted -- {@code PartyRoster} reads "no viewer" as "may
     * remove nobody", and a made-up id would offer somebody a button the server refuses.
     */
    private UUID viewerId() {
        return minecraft == null || minecraft.player == null ? null : minecraft.player.getUUID();
    }

    /** The roster this client was last sent, as seen by the player looking at it. */
    PartyRoster partyRoster() {
        return ClientPartyCache.roster(viewerId());
    }

    /**
     * The region the sidebar's rows scroll within, with its bounds taken from the current window.
     *
     * <p>Re-applied on every use, like {@link #viewport()} and for the same reason: a resize is announced
     * to {@code init} and to nothing else, and {@link #render} and {@code mouseClicked} are not in an
     * order this class could rely on — so a bound rectangle stored once would be describing the previous
     * window for an unknown number of frames.
     *
     * <h2>This is what replaced {@code chapterRows()}</h2>
     *
     * <p>That answered "how many rows fit above the bottom", which is the question a screen asks when it
     * has to decide where row <i>n</i> goes. The rows are placed by a {@link dev.ellipog.armature.client.ui.kit.Stack}
     * inside a {@link ScrollView} now, so a count is no longer part of the contract and the arithmetic
     * that produced it has no owner. What the framing still owes the list is the <b>region</b> — a list
     * cannot know where its own edge is — and that is what this returns.
     *
     * <p>Both the scrolling and the culling read it, so it is the one rectangle that decides what is on
     * screen: {@link ScrollView#apply} places against it and hides what falls outside, and
     * {@link #mouseScrolled} routes the wheel by it.
     */
    Viewport sidebarViewport() {
        BookGeometry.Rect rect = geometry().sidebarViewport(mayEditNow(), sidebarReserveScrollbar);
        // The pitch goes with the bounds, because both are the frame's: this list moves a row at a time
        // -- the wheel snaps to `pitch()`, and the bar's page keeps one of those rows on screen -- and
        // the bar is where both of those numbers now live.
        sidebarView.bar().pitch(SidebarLayout.pitch());
        return sidebarView.viewport().bounds(rect.x(), rect.y(), rect.width(), rect.height());
    }

    /**
     * Offsets one travelling widget by this frame's wipe, filing its placed position first.
     *
     * <p>One method for the rows and the cluster because the filing is the delicate half: the
     * base is read on the first slide frame rather than at build, since a field built for a
     * scrolling list is constructed at 0,0 and placed by its view afterwards.
     */
    private void travelSidebarWidget(AbstractWidget widget, int travelX, int travelY) {
        int[] base = sidebarTravelBase.get(widget);
        if (base == null) {
            base = new int[] {widget.getX(), widget.getY()};
            sidebarTravelBase.put(widget, base);
        }
        widget.setX(base[0] + travelX);
        widget.setY(base[1] + travelY);
    }

    /** Puts one travelled widget back where the build placed it. See {@link #travelSidebarWidget}. */
    private void restoreSidebarTravel(AbstractWidget widget) {
        int[] base = sidebarTravelBase.get(widget);
        if (base != null) {
            widget.setX(base[0]);
            widget.setY(base[1]);
        }
    }

    /**
     * Draws the departed rows over the sliding card.
     *
     * <p>The rails' exit in miniature: the orphans ride the wipe from their filed bases, leading
     * its edge by the filed overhang so every one of them is fully past the panel's edge the
     * frame the wipe lands — and all of it inside the wipe's own clip, so a row outside the card
     * is cut rather than drawn. That clip is the difference from the kept-widgets ride this
     * replaces: the rows can never paint past the card's edge, whatever the anchor, the window
     * or the frame rate does, which is what makes the ghost structurally impossible rather than
     * merely unreproduced.
     *
     * <p>Drawn cold: the rows answer nothing while they ride, so no hover washes over the card
     * on its way out — and no tooltip is left pointing at a row that is already gone. Positions
     * are restored before returning, so the filed bases stay the single source of truth for the
     * next frame.
     */
    private void drawDepartedSidebar(GuiRenderer renderer) {
        long now = net.minecraft.util.Util.getMillis();
        if (!sidebarSliding(now) || sidebarDepartAnchor == null) {
            return;
        }
        BookGeometry.Rect wipe = sidebarWipeRect(now);
        int travelX = wipe.x() - sidebarDepartAnchor.x()
                + wipe.width() - sidebarDepartAnchor.width();
        int travelY = wipe.y() - sidebarDepartAnchor.y();
        float progress = PanelLayout.revealed(now - sidebarSlideStart, panelRevealMillis());
        int lead = Math.round(sidebarDepartOverhang * progress);
        try (GuiRenderer.Scoped clip = renderer.clip(wipe.x(), wipe.y(), wipe.right(), wipe.bottom())) {
            for (SidebarDeparted departed : sidebarDeparted) {
                AbstractWidget widget = departed.widget();
                widget.setX(departed.x() + travelX - lead);
                widget.setY(departed.y() + travelY);
                if (widget instanceof ArmatureButton button) {
                    button.hoverTold(false).draw(renderer);
                }
                widget.setX(departed.x());
                widget.setY(departed.y());
            }
        }
    }

    /**
     * Whether the chapter list's wipe is still travelling, on this frame's clock.
     *
     * <p>Asked with the frame's time because the wipe is state with a deadline, not a flag: with
     * motion off the duration is zero and no wipe ever starts, so the list is simply where the
     * file says on the frame of the press.
     */
    boolean sidebarSliding(long now) {
        return sidebarSlideStart >= 0 && now - sidebarSlideStart < panelRevealMillis();
    }

    /**
     * Where the list's sliding surface is on this frame: the wipe from where the action found
     * it to where the geometry now says it lives.
     *
     * <p>Every side travels, because docking, floating and stripping move different edges — see
     * {@code PanelLayout#sidebarWipeRect}. Settled, this is the target itself, so callers that
     * read it never branch on whether a wipe is running.
     */
    BookGeometry.Rect sidebarWipeRect(long now) {
        if (sidebarSlideStart < 0 || sidebarSlideFromRect == null) {
            return geometry().sidebar();
        }
        return PanelLayout.sidebarWipeRect(sidebarSlideFromRect, geometry().sidebar(),
                now - sidebarSlideStart, panelRevealMillis());
    }

    /**
     * Begins the wipe from where the list is drawn right now.
     *
     * <p>Called before the {@code DevMode} write the action makes, so the wipe starts at the old
     * rectangles and lands on the new geometry the rebuild produces. An action that finds the
     * same state it would set does not start one: a wipe to where the list already is would
     * travel nowhere and hold the list inert for its duration.
     */
    void beginSidebarSlide() {
        long now = net.minecraft.util.Util.getMillis();
        // From where the list is drawn right now, not from where it is going: reversing mid-wipe
        // starts the way back from mid-wipe rather than jumping to the far end first. The
        // departure's own filings — anchor box and overhang — measure in `orphanSidebarRows`,
        // against the sliver, once the collapse knows it is one.
        sidebarSlideFromRect =
                sidebarSliding(now) ? sidebarWipeRect(now) : geometry().sidebar();
        sidebarSlideStart = now;
    }

    /**
     * Rebuilds the chapter list and the view cluster, and nothing else.
     *
     * <p>A collapse or an expand moves no rail, no header and no canvas: the arrangement is the
     * same before and after, so rebuilding every column would recreate hovered controls under a
     * stationary pointer and blink them the frame the wipe lands. The geometry cache re-derives
     * on its own key, and the viewport rebinds inside the sidebar build, so these two builders
     * are the whole of what a slide owes. Anything that moves a rail — floating, docking —
     * still takes the full rebuild.
     */
    private void rebuildSidebarAndCluster() {
        clearFrameSnapshot();
        questEditor.buildSidebarWidgets();
        questEditor.buildViewCluster();
    }

    /**
     * Files the list's live rows as departed orphans for the wipe that just started.
     *
     * <p>Called after the collapse's {@code DevMode} write, so the geometry already answers the
     * sliver the wipe lands on while the widgets still stand where the box was: the anchor filed
     * here is the expanded box the rows were built for, and the overhang is measured against the
     * sliver, both before anything moves. The rows leave the screen with this call — presses,
     * hover and narration cannot reach an orphan — and the wipe draws them by hand till it
     * lands. With no rows aboard (an empty list) this still files the anchor, so the settle has
     * one rule whatever the list held.
     */
    private void orphanSidebarRows() {
        clearFrameSnapshot();
        sidebarDeparted.clear();
        for (AbstractWidget widget : sidebarRowWidgets) {
            sidebarDeparted.add(new SidebarDeparted(widget, widget.getX(), widget.getY()));
            removeWidget(widget);
            buttons.remove(widget);
            sidebarTravelBase.remove(widget);
        }
        sidebarRowWidgets.clear();
        sidebarView.clear();
        // Rebound to the collapsed zero-area viewport now, not at the settle: the bar, the wheel
        // and the menu all read this, and for the wipe's length there is no list for them to act on.
        sidebarViewport();
        sidebarDepartAnchor = geometry().sidebarExpandedRect();
        BookGeometry.Rect target = geometry().sidebar();
        int endShiftX = target.x() - sidebarDepartAnchor.x()
                + target.width() - sidebarDepartAnchor.width();
        int overhang = 0;
        for (SidebarDeparted departed : sidebarDeparted) {
            overhang = Math.max(overhang,
                    departed.x() + departed.widget().getWidth() + endShiftX - panelRect().x());
        }
        sidebarDepartOverhang = overhang;
    }

    /** Slides the list away to its sliver, or brings it back, and writes the choice. */
    private void setSidebarCollapsed(boolean collapsed) {
        if (DevMode.isSidebarCollapsed() == collapsed) {
            return;
        }
        beginSidebarSlide();
        DevMode.setSidebarCollapsed(collapsed);
        if (collapsed) {
            sidebarPeeking = false;
            sidebarPeekCooling = true;
            // Out with the rows aboard, as orphans: filed and removed here, drawn by hand till
            // the wipe lands (see `sidebarDeparted`), so the full list rides the card back
            // instead of popping to the sliver at once — and nothing interactive is left where
            // the card was, which is the ghost the old kept-widgets ride flashed.
            sidebarDeparting = panelRevealMillis() > 0;
            if (sidebarDeparting) {
                orphanSidebarRows();
            }
            else {
                rebuildWidgets();
                cancelSidebarSlideIfStatic();
            }
        }
        else {
            // An expand re-arms the auto-hide below: without this a stale "was inside" from
            // before the collapse would fold a box whose pointer never arrived. The leave clock
            // restarts with it, for the same reason the peek's own clocks do on a transition.
            sidebarBoxHovered = false;
            sidebarPeekLeaveStart = -1;
            sidebarDeparting = false;
            rebuildWidgets();
            cancelSidebarSlideIfStatic();
        }
    }

    /** The same, from the B key while floating: docked, the old column cannot collapse. */
    private void toggleSidebarCollapsed() {
        if (!DevMode.isSidebarFloating()) {
            return;
        }
        // Pinned never collapses: a box that stays open does not fold for a key. Unpin it first
        // — then it slides away on its own — or expand a sliver, which is always allowed.
        if (DevMode.isSidebarPinned() && !DevMode.isSidebarCollapsed()) {
            return;
        }
        setSidebarCollapsed(!DevMode.isSidebarCollapsed());
    }

    /**
     * Pins the floating box open, or lets it slide away when the pointer leaves.
     *
     * <p>Writes the choice and nothing else: pinning moves no edge, so there is no wipe to run
     * and no widget to rebuild — the dot reads the file live on the next frame. Docked, there
     * is nothing to pin and this decides nothing — which is why the docked book carries no pin
     * control at all.
     */
    private void setSidebarPinned(boolean pinned) {
        if (DevMode.isSidebarPinned() == pinned) {
            return;
        }
        DevMode.setSidebarPinned(pinned);
    }

    /**
     * What toggling the pin leaves behind: the three answers the screen must hold after it.
     *
     * <p>A static truth table rather than a screen, like {@link #editEffective}: pinning means
     * "stay open", so from a sliver or its hover preview pinning is an expand rather than a flag
     * flip. Without that, pinning a peek flips the dot and changes nothing — the leave-check ends
     * the preview either way, which reads as a pin that does nothing when it is clicked.
     */
    record SidebarPinAfter(boolean pinned, boolean collapsed, boolean peeking) {
    }

    /** The pin toggle's answer for one state, as pure inputs. See {@link #toggleSidebarPinned}. */
    static SidebarPinAfter pinAfterToggle(boolean pinned, boolean collapsed, boolean peeking) {
        boolean next = !pinned;
        if (next && (peeking || collapsed)) {
            return new SidebarPinAfter(true, false, false);
        }
        return new SidebarPinAfter(next, collapsed, peeking);
    }

    /** The same, from the box's own square and Ctrl+B; docked, there is no pin to toggle. */
    private void toggleSidebarPinned() {
        if (!DevMode.isSidebarFloating()) {
            return;
        }
        SidebarPinAfter after = pinAfterToggle(DevMode.isSidebarPinned(),
                DevMode.isSidebarCollapsed(), sidebarPeeking);
        setSidebarPinned(after.pinned());
        if (after.pinned() && DevMode.isSidebarCollapsed()) {
            // Promoting a sliver or its hover preview into a real open box. The preview's
            // rows are already built and no rail moves either way, so this is the sidebar's
            // own rebuild rather than the whole screen's: a full one hitches visibly on a
            // press whose picture barely changes, which reads as the pin lagging.
            beginSidebarSlide(); // from the drawn preview or sliver, before the triple lands
            sidebarPeeking = after.peeking();
            DevMode.setSidebarCollapsed(after.collapsed());
            sidebarBoxHovered = false;
            sidebarPeekLeaveStart = -1;
            sidebarDeparting = false;
            // An arrival, like every other: a pin mid-collapse cuts the departure short, and the
            // box rebuilds its rows live below — orphans filed for the interrupted wipe would draw
            // over them.
            sidebarDeparted.clear();
            sidebarDepartAnchor = null;
            rebuildSidebarAndCluster();
            cancelSidebarSlideIfStatic();
        }
        else {
            sidebarPeeking = after.peeking();
            if (DevMode.isSidebarCollapsed() != after.collapsed()) {
                setSidebarCollapsed(after.collapsed());
            }
        }
    }

    /**
     * Shows the collapsed list as a floating preview, or puts the strip back.
     *
     * <p>Writes nothing: the file keeps saying "collapsed" while the pointer hovers, which is
     * what makes lifting the pointer the whole of the way back. The wipe runs either way, so a
     * peek arrives like an expand and leaves like a collapse.
     */
    private void setSidebarPeeking(boolean peeking) {
        if (sidebarPeeking == peeking) {
            return;
        }
        beginSidebarSlide();
        sidebarPeeking = peeking;
        if (!peeking) {
            // An acted unpeek (Escape, a map click) with the pointer still over the strip must
            // not re-dwell at once: the cooldown below waits for the pointer to leave first.
            sidebarPeekCooling = true;
            // Out with the rows aboard, like any other collapse: filed as orphans and drawn by
            // hand till the wipe lands.
            sidebarDeparting = panelRevealMillis() > 0;
            if (sidebarDeparting) {
                orphanSidebarRows();
            }
            else {
                rebuildWidgets();
                cancelSidebarSlideIfStatic();
            }
            // A transition restarts every hover clock: a stale leave-stamp would otherwise end the
            // new state the frame it begins.
            sidebarPeekLeaveStart = -1;
            sidebarPeekHoverStart = -1;
            return;
        }
        // A transition restarts every hover clock: a stale leave-stamp would otherwise end the
        // new state the frame it begins.
        sidebarPeekLeaveStart = -1;
        sidebarPeekHoverStart = -1;
        // An arrival, even one that cuts a departure short: the preview rebuilds its rows live
        // below, so no orphans survive the switch (see `init`, which drops them with the wipe).
        sidebarDeparting = false;
        rebuildWidgets();
        cancelSidebarSlideIfStatic();
    }

    /**
     * Stands down a wipe that would travel nowhere.
     *
     * <p>Pinning a list that is already its drawn width — docked expanded to floating expanded —
     * moves no edge: starting a wipe anyway would hold the list's presses for 160 milliseconds
     * while nothing visibly moves. Read after the rebuild, when the geometry already answers the
     * new state, so the comparison is between the width the wipe left from and the one it would
     * land on.
     */
    void cancelSidebarSlideIfStatic() {
        if (sidebarSlideFromRect != null && sidebarSlideFromRect.equals(geometry().sidebar())) {
            sidebarSlideStart = -1;
        }
    }

    /**
     * Follows the pointer over the slid-away sliver and the unpinned box: resting on the sliver
     * peeks the full box, leaving either puts the sliver back.
     *
     * <p>Edge-triggered on the frame path: only a change rebuilds, so a stationary pointer costs
     * nothing and a travelling one pays one rebuild each way. Never while a wipe travels (the
     * sliver it would read is mid-flight), while a menu owns the pointer, or while the pointer is
     * owned by a popover at all — a peek opening under a menu would bury the menu's own press.
     * Docked, the old column is always out and there is nothing to follow.
     *
     * <p>A peek writes nothing: the file keeps saying "collapsed" while the pointer hovers, which
     * is what makes lifting the pointer the whole of the way back. An unpinned box left behind
     * writes its collapse, because that is the state it is in — reopening the book finds the
     * sliver, which is where it was left.
     *
     * <p>The keep zone is the box itself: the box carries its own pin and close squares, so
     * nothing outside it is needed while open, and the canvas lifting the preview is the gesture
     * the sliver promises.
     */
    private void updateSidebarPeek(int hoverX, int hoverY, boolean owned, long now) {
        if (owned || sidebarSliding(now) || !menu.isEmpty() || colourPopover.isOpen()) {
            sidebarPeekHoverStart = -1;
            return;
        }
        if (!DevMode.isSidebarFloating()) {
            sidebarPeekHoverStart = -1;
            sidebarPeekCooling = false;
            return;
        }
        if (!sidebarPeeking && !DevMode.isSidebarCollapsed()) {
            // An open box that is not a peek: unpinned, it slides away once the pointer leaves
            // it; pinned, it stays however long the pointer is gone. The dwell does not apply
            // here — leaving is instant, because a preview that overstays is the complaint, not
            // one that arrives late.
            //
            // Armed by the pointer being inside first: a freshly opened box with the pointer
            // elsewhere must not slam shut on its first frame, before the hand has had a chance
            // to reach it. And never while a panel holds the pointer's attention or a list
            // gesture is in flight: folding the navigation while a quest panel is being read, or
            // mid row-drag, trades focus for tidiness.
            boolean inside = geometry().sidebar().inflate(SIDEBAR_KEEP_HOVER)
                    .contains(hoverX, hoverY);
            if (!DevMode.isSidebarPinned() && sidebarBoxHovered && !inside
                    && overlay == PanelKind.NONE && overlay2 == PanelKind.NONE && !dockOpen
                    && sidebarDragKey == null && sidebarDragRail == null) {
                if (sidebarPeekLeaveStart < 0) {
                    sidebarPeekLeaveStart = now;
                }
                else if (now - sidebarPeekLeaveStart >= SIDEBAR_PEEK_LEAVE_MILLIS) {
                    sidebarPeekLeaveStart = -1;
                    sidebarBoxHovered = false;
                    setSidebarCollapsed(true);
                }
            }
            else {
                sidebarPeekLeaveStart = -1;
                sidebarBoxHovered = geometry().sidebar().contains(hoverX, hoverY);
            }
            return;
        }
        if (!sidebarPeeking) {
            if (sidebarPeekCooling) {
                // The last unpeek was an act with the pointer still over the sliver: wait for the
                // pointer to leave before the dwell can start over, or the preview re-opens under
                // a hand that just put it away.
                if (!geometry().sidebar().inflate(SIDEBAR_PEEK_HOVER).contains(hoverX, hoverY)) {
                    sidebarPeekCooling = false;
                }
                return;
            }
            if (geometry().sidebar().inflate(SIDEBAR_PEEK_HOVER).contains(hoverX, hoverY)) {
                if (sidebarPeekHoverStart < 0) {
                    sidebarPeekHoverStart = now;
                }
                else if (now - sidebarPeekHoverStart >= SIDEBAR_PEEK_DWELL_MILLIS) {
                    sidebarPeekHoverStart = -1;
                    setSidebarPeeking(true);
                }
            }
            else {
                sidebarPeekHoverStart = -1;
            }
            return;
        }
        if (!geometry().sidebarExpandedRect().inflate(SIDEBAR_KEEP_HOVER).contains(hoverX, hoverY)) {
            if (sidebarPeekLeaveStart < 0) {
                sidebarPeekLeaveStart = now;
            }
            else if (now - sidebarPeekLeaveStart >= SIDEBAR_PEEK_LEAVE_MILLIS) {
                sidebarPeekLeaveStart = -1;
                setSidebarPeeking(false);
            }
        }
        else {
            sidebarPeekLeaveStart = -1;
        }
    }

    /**
     * Ties one list's viewport and bar to the band it is drawn in, for one frame.
     *
     * <h2>One call, because the numbers are one decision</h2>
     *
     * <p>A drawn list needs its region, its content height, its bar's strip and its row pitch, and every
     * one of those is either the frame's or the content's — so a screen that set them at its own draw
     * site would write the same four lines at every list and get one of them wrong. Worse, the clamp is
     * the thing that used to be written by hand: an offset clamped against a maximum the bar computed a
     * second way is the fault this kit exists to make unrepresentable, and the viewport is where the
     * clamp lives now.
     *
     * <p>The strip is passed rather than derived from the region's edge, and that is the whole reason
     * this takes five arguments: these lists each reserved a strip <b>inside</b> their own width
     * ({@code SCROLLBAR} on the three layout classes), so the bar sits beside the rows rather than over
     * the last few pixels of them. A bar placed at {@code region.right()} would draw on the row it
     * describes — which is what the hand-rolled thumb used to do.
     *
     * @param region        the band the rows are drawn in and clipped to
     * @param strip         the column the bar owns, from the layout that reserved it
     * @param contentHeight how tall the whole list is, from the layout that owns it
     * @param pitch         one row of it: what a wheel notch moves, and what a page keeps
     */
    static void bindList(Viewport body, ScrollBar bar, BookGeometry.Rect region,
                                 BookGeometry.Rect strip, int contentHeight, int pitch) {
        body.bounds(region.x(), region.y(), region.width(), region.height());
        body.setContentSize(region.width(), contentHeight);
        bar.strip(strip.right() - ScrollBar.WIDTH, strip.y(), ScrollBar.WIDTH, strip.height()).pitch(pitch);
    }

    /**
     * Draws one list's bar in whatever state the pointer puts it in.
     *
     * <p>The clock is read here rather than threaded through every drawing method that owns a list: a
     * bar's hover is a frame-driven ease, the value only has to be the frame's, and the alternative is a
     * {@code long now} parameter on eight methods whose only other use for it would be passing it along.
     */
    static void drawBar(GuiRenderer r, ScrollBar bar, int mouseX, int mouseY) {
        bar.draw(r, ArmatureScrollStyle.skin(), mouseX, mouseY, net.minecraft.util.Util.getMillis());
    }

    /**
     * Offers a press to one bar, and captures it if the bar takes it.
     *
     * <h2>Why every press goes through here</h2>
     *
     * <p>Because a press that a bar takes has to become <b>this screen's</b> gesture rather than the
     * bar's alone: the move, the release and the hold-repeat are routed from {@link #heldBar}, and a
     * call site that pressed a bar without capturing it would leave the bar paging forever with nothing
     * able to release it. One method means the two cannot come apart, and it is why the fifteen press
     * branches in this class are one line each.
     *
     * @return whether the bar took the press
     */
    private boolean pressBar(ScrollBar bar, double mouseX, double mouseY) {
        if (!bar.press(mouseX, mouseY, net.minecraft.util.Util.getMillis())) {
            return false;
        }
        heldBar = bar;
        return true;
    }

    /**
     * Keeps the held bar honest, once a frame: the hold-repeat, and the release that never arrived.
     *
     * <h2>Why a frame is involved at all</h2>
     *
     * <p>Because a groove held down has to keep paging, and Minecraft has no mouse auto-repeat: a press
     * is one event and a hold is silence. So the repeat is driven from here, with the frame's own clock
     * — the same "time is passed in" rule every animation in this toolkit follows, which is why the
     * repeat can be asserted by passing a larger number rather than by waiting.
     *
     * <h2>And why the window's focus ends the gesture</h2>
     *
     * <p>Because a drag can end without a mouse-up. {@code MouseHandler} stops dispatching
     * {@code mouseDragged} the moment the window is inactive, and a button released while it is inactive
     * is an event no screen is ever handed — so alt-tabbing away mid-drag used to leave the bar held,
     * which is invisible until the pointer comes back and the list moves without a button being down.
     * The condition that stops the events is therefore the condition that ends the gesture, and it is the
     * same one Minecraft asks ({@code Minecraft.isWindowActive}) before it dispatches a drag at all.
     */
    private void maintainHeldBar(long now) {
        if (heldBar == null) {
            return;
        }
        if (!Minecraft.getInstance().isWindowActive()) {
            heldBar.release();
            heldBar = null;
            return;
        }
        heldBar.advance(now);
    }

    /**
     * The rectangle one kind draws itself into: its rail, always.
     *
     * <h2>One expression, and the reason it is still a method</h2>
     *
     * <p>Every position inside a panel — the header's icon and title, the body's clip, the footer's
     * controls, the scrollbar's strip — is computed from the four numbers below rather than from the
     * window, which is what let one drawing serve the two presentations this class used to have. The card is
     * gone, so this is a forward to {@link #panelRail} — and it stays a method because it is <b>the seam the
     * removal rests on</b>: thirty call sites ask this question and every one of them is still right, which
     * is the test of whether the conversion was really a seam or only a coat of paint.
     */
    BookGeometry.Rect surfaceCard(PanelKind kind) {
        return panelRail(kind);
    }

    /**
     * The rectangle a kind occupies, whatever its content came to.
     *
     * <h2>Why the content's height is no longer an argument</h2>
     *
     * <p>It exists for the kinds whose content sizes them — the rewards inbox, the settings card, a party
     * face — and in a column there is no such kind: a rail is as tall as the canvas, and the framing a card
     * once did for a three-row list is what the column already is. So the parameter is gone rather than
     * ignored, which is the difference between a reader trusting this signature and a reader wondering what
     * the second argument does.
     *
     * <p>It is kept as a method rather than replaced by {@code panelRail} at the call sites because the two
     * are different questions even when they have the same answer today: this one is "the surface this kind
     * draws into", asked by four builders that used to care about the shape.
     */
    BookGeometry.Rect framed(PanelKind kind) {
        return panelRail(kind);
    }

    /**
     * Whether this column carries the fold control.
     *
     * <p>The column that holds the child, in both arrangements: the second column when two are shown,
     * because that is the one a press folds away, and the single column when folded, because the child is
     * what it is showing. That is one kind in both cases -- {@link PanelStack#shown} is the child whenever
     * there is one -- so this is a question about <b>which kind</b> and not about the shape of the frame.
     *
     * <p><b>The dock never carries it</b>, and that is now a property of the kind rather than of a guard
     * here: folding is about a panel and the thing it opened, and the dock is neither. A guard asking whether
     * column 1 can hold a child used to sit in front of this — it was about whether a second column would be
     * drawn at all — and it is gone with the presentation switch, because column 1 can always hold a child.
     *
     * <p>One expression rather than the guard spelled out at the two sites that need it: the control and the
     * header text that has to stop short of it. The two must agree, or a long name is drawn through a button
     * that is there.
     */
    boolean carriesFold(PanelKind kind) {
        return overlay2 != PanelKind.NONE && overlay2 == kind;
    }

    /**
     * Where a column's header text has to stop: at the fold control when the column carries one.
     *
     * <p>The reading end of {@link #carriesFold}, and it exists so that the drawing of a title and the
     * placing of the control cannot disagree: {@link PanelLayout#headerRight} is the layout's own number,
     * and every header in this book that draws a title inside a column asks for it here.
     */
    int headerTextRoom(BookGeometry.Rect rail) {
        return PanelLayout.headerRight(rail, carriesFold(surfaceKind));
    }

    /**
     * The Author pill's press: the author's dock, on or off.
     *
     * <h2>Why the dock is a control's business and not a mode's</h2>
     *
     * <p>It used to be edit mode's: the dock appeared when the Edit pill latched, and went with it, so there
     * was no way to be editing without a rail over the canvas and no way to have the dock without being in
     * edit mode. That is what forced the Edit pill to double as the dock's escape — see {@link
     * #pressEditPill} — and it made the dock impossible to put away without leaving the mode.
     *
     * <h2>And what it stopped being after that</h2>
     *
     * <p>It was the fallback occupant of column 1, so opening a panel hid the dock and closing that panel
     * brought it back in the same rectangle — the fault this round exists to fix. <b>The dock owns a rail
     * now</b>, so this press is the only thing that takes it away (with the X and Escape), and it goes
     * through the rules like every other opening rather than writing the field directly: one writer of the
     * arrangement, which is what keeps the drawing, the controls and the press from ever disagreeing about
     * whether the dock is open.
     */
    void pressAuthorPill() {
        boolean open = !dockOpen;
        // The arrival stamp is `applyColumns`' — every rail's enter and exit is — so opening and
        // closing alike animate with no stamp at the call site. See `startPanelReveal`.
        applyColumns(PanelStack.withDock(columns(), open));
        if (!open) {
            // Its transient state goes with it, the same set `closeOverlay` clears for a panel: a colour
            // picker left anchored to a row that is no longer drawn would float over the canvas, and a
            // picker armed for a chapter would greet the next opening aimed at a file nobody chose.
            closeColourPopover();
            clearPickers();
        }
        rebuildWidgets();
    }

    /**
     * Closes one column, through the unwind its kind needs.
     *
     * <h2>One method behind the X, the breadcrumb and Escape</h2>
     *
     * <p>Because three routes to one act are three chances to skip the part that matters: a table editor's
     * field is committed by the blur its own close causes, so a route that popped the column without that
     * call would discard a half-typed entry — the fault those per-kind closes exist for. So the peel and the
     * kind's own unwind are one call, and no caller has to know which kinds need one.
     *
     * <p><b>Each arm clears the column it is closing</b>, and that is not tidiness: the arrangement has one
     * writer ({@code applyColumns}), so a per-kind close that only reset the picker's own fields left the
     * second column standing — an Escape that closed nothing the player could see. The picker's own close is
     * where its column goes; the texture's puts the book back; everything else peels here.
     *
     * @param child whether the outermost column is the one going: Escape and the X both peel the outer one
     *              first, which is what "one press, one step back" means with two columns open
     */
    void closePanelColumn(boolean child) {
        if (!child) {
            closeOverlay();
            return;
        }
        // The child's kind decides how it leaves; each of these rebuilds for itself.
        if (overlay2 == PanelKind.PICKER) {
            itemPicker.closePickerOverlay();
            return;
        }
        if (overlay2 == PanelKind.TEXTURE) {
            texturePicker.closeTexturePicker();
            return;
        }
        applyColumns(PanelStack.afterClose(columns(), true));
        rebuildWidgets();
    }

    /**
     * There is no modal, and this note is what stands in its place.
     *
     * <h2>The two questions that used to be here</h2>
     *
     * <p>{@code modalUp()} asked whether the book was covered by a centred card — hidden behind a scrim and
     * inert — and {@code bookLive()} was its complement. They existed because "an overlay is open" and "the
     * book is not there" were once the same sentence, and separating them was a real fix: the six places
     * that had spelled the first as the second left the canvas undrawn, the world wash missing, the sidebar
     * never rebuilt, its scrollbar refusing the press, its row drag disarmed and a line's handles neither
     * drawn nor grabbable.
     *
     * <p><b>Every kind is a column now</b>, so the first question has one answer for every arrangement
     * there is: nothing covers the book, nothing dims it, nothing is inert. The sites that asked
     * {@code bookLive()} have lost the condition rather than gained a constant — a guard that is always
     * true is a guard nobody should have to read past to find the code that runs.
     */

    /**
     * The arrangement as {@code PanelStack} sees it. Built rather than stored: the rules are its, the fields
     * are this class's.
     *
     * <h2>The destructured record is the whole of the bridge, and there is one of each part</h2>
     *
     * <p>{@code overlay} and {@code overlay2} are the two panel columns and {@code dockOpen} is the dock's
     * own rail, which is why this method and {@link #applyColumns} are the only two places that know the
     * arrangement is three rails rather than two. <b>The dock is not the fallback occupant of column 1 any
     * more</b>, and that is the round's whole change: it used to be {@code drawerInColumn() ? TOOLS : overlay},
     * so opening a panel took the dock's rectangle and closing that panel gave it back — the author watched
     * their tools vanish and reappear in the same place. Here it is simply the third rail.
     */
    PanelStack.Columns columns() {
        return new PanelStack.Columns(dockOpen ? PanelKind.TOOLS : PanelKind.NONE, overlay, overlay2,
                foldChoice);
    }

    /**
     * What occupies the first panel column.
     *
     * <p>One word, where it used to be a question about the dock: the dock had its own slot before this
     * round and shares one with no panel now. Named anyway, because two dozen call sites read better for it
     * than for {@code overlay} — every one of them is asking "what is the panel I am working in", which is
     * the same question for the header's lit buttons, the drag's floor and the fold's control.
     */
    PanelKind leftKind() {
        return overlay;
    }

    /**
     * Takes an arrangement back from the rules, which are the only thing that decides one.
     *
     * <p>The one writer of all three rails, and it is written as three assignments rather than as a check
     * per field on purpose: a transition that named only the panel columns would leave the dock where it
     * was, which is right, and one that forgot to read {@code dock()} back would leave the field and the
     * rules disagreeing about whether the dock is open — the state that draws a rail with no controls in it,
     * or controls with no rail.
     *
     * <p>It is also the one place arrivals and departures are stamped: every open and every close in the
     * book routes through here, so no opener — and no Done button — has to remember the animation. Kinds
     * appearing in the new arrangement get an enter stamp (reopens continue from a mid-exit position);
     * kinds leaving get their current clip snapshotted and an exit stamp, and draw from those plus the
     * retained content until the exit sinks.
     */
    void applyColumns(PanelStack.Columns next) {
        java.util.List<PanelKind> before = PanelStack.presented(columns(), panelFolded());
        java.util.List<PanelKind> afterNext = PanelStack.presented(next, panelFoldedFor(next));
        arrangementEpoch++;
        long now = net.minecraft.util.Util.getMillis();
        int enterMs = panelRevealMillis();
        // Snapshotted from the rails as they stand — fields still hold the old arrangement, so the rails
        // are valid — and shown rather than whole: a rail closed mid-enter leaves from where it stood,
        // its current clip, rather than jumping to whole and sinking.
        for (PanelKind kind : before) {
            if (!afterNext.contains(kind)) {
                BookGeometry.Rect rail = panelRail(kind);
                departRails.put(kind,
                        PanelLayout.revealRect(rail, panelRevealElapsed(kind, now), enterMs));
                panelDepartStarts.put(kind, now);
            }
        }
        dockOpen = next.dock() == PanelKind.TOOLS;
        overlay = next.left();
        overlay2 = next.right();
        foldChoice = next.fold();
        java.util.List<PanelKind> after = PanelStack.presented(columns(), panelFolded());
        // Retired by this call, for the closes below to schedule under: read promptly, before anything
        // else moves rails. Empty when the call showed without hiding, which is when a deferred cleanup
        // would have no exit to belong to and runs at once instead.
        java.util.List<PanelKind> departed = new java.util.ArrayList<>();
        for (PanelKind kind : before) {
            if (!after.contains(kind)) {
                departed.add(kind);
            }
        }
        lastDeparted = java.util.List.copyOf(departed);
        for (PanelKind kind : after) {
            if (before.contains(kind)) {
                continue;
            }
            // A teardown owed to an earlier close of this same kind runs first: close-then-open has
            // always meant teardown before setup, and the deferred nullings are no exception.
            runTeardowns(kind);
            if (panelDepartStarts.containsKey(kind)) {
                // Reopened mid-exit: the enter continues from where the exit stood rather than jumping
                // back to a whole rail off screen. Symmetric durations make the velocities roughly
                // match; exactness lives in the helpers' ends, which both hold.
                long exitElapsed = now - panelDepartStarts.get(kind);
                int exitMs = Math.max(1, panelExitMillis());
                long mapped = enterMs <= 0 ? Long.MAX_VALUE
                        : enterMs - exitElapsed * enterMs / exitMs;
                panelRevealStarts.put(kind, now - Math.max(0, mapped));
                panelDepartStarts.remove(kind);
                departRails.remove(kind);
            }
            else {
                bookRewards.startPanelReveal(kind);
            }
        }
        // **Anything that left the arrangement forgets how far its arrival had got.** Without this, a rail
        // closed mid-slide and opened again later would start its second arrival part-way through the first
        // — a panel that appears already half-drawn, which reads as a glitch rather than as an opening.
        // Pruned here, at the one writer of the arrangement, so no opener has to remember to.
        panelRevealStarts.keySet().retainAll(after);
    }

    /**
     * Whether the second column is paged into the first at this moment.
     *
     * <p>The one question the fold's rules cannot answer alone: {@code PanelLayout} needs both columns'
     * <i>drawn</i> widths, which are the kinds' widths clamped to what each kind may be, and this is where
     * those are known.
     */
    boolean panelFolded() {
        return panelFoldedFor(columns());
    }

    /**
     * Whether the second column of an arrangement is paged into the first.
     *
     * <p>The same question as {@link #panelFolded}, asked of an arrangement rather than of the fields:
     * {@link #applyColumns} needs the fold of the arrangement it is moving to — which rails the move
     * shows and hides — before the fields hold it. Geometry and widths are arrangement-independent
     * reads, so the answers agree wherever both can be asked.
     */
    private boolean panelFoldedFor(PanelStack.Columns cols) {
        if (cols.right() == PanelKind.NONE) {
            // Nothing to fold: a fold with one column is a no-op, so the arrangement is the same either
            // way and asking the layout would be a call for an answer nothing reads.
            return false;
        }
        // **The room the pair has is the room beside the dock.** The fold is about column 1 and column 2 --
        // the panel you opened and the thing it opened -- so measuring it against the whole canvas would let
        // the pair claim the dock's rail as spare room and fold itself on a window that had space for both.
        // With no dock latched this is the canvas's own width, which is every reader's arrangement and the
        // expression this method has always had.
        int room = geometry().canvas().width();
        if (cols.dock() == PanelKind.TOOLS) {
            room -= dockRail().width() + BookGeometry.PANEL_GAP * 2;
        }
        return PanelLayout.folded(room, railWidth(cols.left()), railWidth(cols.right()), cols.fold());
    }

    /**
     * The air between two of the editor's footer actions, and between them and Done.
     *
     * <p>Named because the floor is measured from it: {@link #editorMinimumWidth} has to add the same gaps
     * the builder leaves, and a floor that guessed a different gap from the one drawn is a floor that is
     * wrong by a few pixels per control.
     */
    static final int EDITOR_ACTION_GAP = 2;

    /**
     * The measured floor, remembered. Zero until it is first asked for.
     *
     * <p>A field rather than a constant because the labels are resolved from the language file: a static
     * final would measure whatever language the class happened to load under, and the first frame the floor
     * is wanted — a drag, or a panel opening — is the first moment it can honestly be measured.
     */
    private int editorFloor;

    /**
     * The narrowest the quest editor's column may be dragged: four action buttons that still read.
     *
     * <h2>Why the content has a floor of its own, above its kind's</h2>
     *
     * <p>Because the editor's footer puts four controls in whatever width is left of Back, and at the
     * panel's own minimum that is a few tens of pixels each — "Duplicate" drawn as "Du…", which reads as a
     * broken control rather than as a narrow one. The reader's column has no such floor: prose reflows, and
     * a narrow page is a narrow page rather than a wrong one. So this is a floor about what the column
     * <i>holds</i>, which is why the drag asks for both this and the kind's own.
     *
     * <h2>Measured, and measured once</h2>
     *
     * <p>It used to be a constant with "about fifty pixels" in its justification, which is a guess about a
     * font in place of a measurement of one. The four labels are fixed strings, so they are measured here on
     * the first ask and remembered for the life of the screen — nothing is measured on the frame path, and
     * a drag reads a number. {@code Delete} is measured in its <b>armed</b> state, because that state's label
     * is half again as wide and a floor that ignored it would let the column be dragged to a width where the
     * button asking "Really delete?" no longer fits its own word.
     */
    private int editorMinimumWidth() {
        if (editorFloor > 0) {
            return editorFloor;
        }
        Measure measure = Measure.monospace(6, 9);
        editorFloor = PanelStack.footerMinimumWidth(List.of(
                        measure.width("Really delete?"),
                        measure.width(Labels.of("tenet.screen.duplicate")),
                        measure.width(Labels.of("tenet.screen.copy")),
                        measure.width(Labels.of("tenet.screen.settings"))),
                EDITOR_ACTION_GAP, BookGeometry.BACK_WIDTH, BookGeometry.MODAL_INSET);
        return editorFloor;
    }

    /**
     * What the open column's content needs, above whatever its kind needs.
     *
     * <p>One question with one answer, asked by both the drag and the width a panel opens at: a column that
     * respected this only while being dragged could still be opened too narrow by a hand-edited settings
     * file, which is the fault this exists to stop.
     */
    private int panelContentFloor(PanelKind kind) {
        // **The kind whose content it measures.** The floor is the quest editor's own footer -- four actions
        // and Back -- so it applies where that footer is. It used to be "whenever this player may edit",
        // which was the same answer while the editor was the only column a floor existed for; the dock is a
        // column now, and clamping it to a card's action bar would be a measurement about a control it does
        // not have.
        int floor = kind == PanelKind.QUEST && mayEditNow() ? editorMinimumWidth() : 0;
        // A quest's own minimum width, since version 17. The author asks for a panel at least this
        // wide; the geometry still caps it above, so a 3000-wide ask reads as the widest rail rather
        // than a window wider than the screen. Zero is unset — the kind decides — which is what a
        // version-16 server always says.
        if (kind == PanelKind.QUEST) {
            ClientQuestCache.Entry shown = entryFor(editTarget());
            if (shown != null && shown.minWidth() > floor) {
                floor = shown.minWidth();
            }
        }
        return floor;
    }

    /**
     * The width one kind's rail is drawn at: the player's for that kind, its own floor, and its content's.
     *
     * <h2>One lookup, keyed by the kind, and the live override belongs to a kind rather than a column</h2>
     *
     * <p>There were two of these methods — column 1's, which read the stored width, and column 2's, which
     * ignored it — and a live-override field each, on the reasoning that "column 2 is transient: its width is
     * a property of what it holds". <b>That was one number short of true.</b> A picker's width is a property
     * of the picker rather than of the player, and so is a table's; but the thing that decided it was which
     * <i>column</i> the kind happened to be in, so the same picker opened from the dock and from a quest got
     * the same number by accident rather than by rule, and a drag of the dock's edge was filed as the dock's
     * panel width because the dock <i>was</i> column 1.
     *
     * <p>So the player's remembered width is per kind ({@code DevMode.panelWidth}) and the override in flight
     * is per kind too: {@link #panelLiveWidth} is read only for {@link #panelDragKind}, which is the kind the
     * hand is holding. A width can therefore not leak from one rail to another mid-drag, which is the fault
     * the two fields were introduced to stop and did not.
     */
    private int railWidth(PanelKind kind) {
        if (kind == null || kind == PanelKind.NONE) {
            return 0;
        }
        if (panelLiveWidth > 0 && panelDragKind == kind) {
            // A resize in flight: the edge the hand is holding, which is why this is read here rather than
            // written into the settings file on every frame of the drag. Written once, on release.
            return panelLiveWidth;
        }
        return Math.max(PanelStack.columnWidth(kind, DevMode.panelWidth(kind)), panelContentFloor(kind));
    }

    /** The rail the author's dock owns, anchored to the canvas's own right edge. */
    private BookGeometry.Rect dockRail() {
        return geometry().panelRail(railWidth(PanelKind.TOOLS));
    }

    /**
     * The rail the first <b>panel</b> column gets, which is a question about the dock beside it.
     *
     * <p>With the dock latched, column 1 hangs off the dock's rail — one gap to its left — so the dock is the
     * only rail anchored to the canvas and the only one an opening cannot move. With no dock this is the
     * canvas edge, which is every reader's arrangement and every rectangle a reader had before this round.
     *
     * <p>The folded case takes the <b>child's</b> width, because folding means the child is what is showing,
     * which is why a folded table is wider than a folded picker. It takes the child's <i>slot</i> as well as
     * its width: the pair pages into the one rail the panel already occupied rather than jumping to the
     * canvas's edge, so folding a child away from beside the dock does not slide the whole pairing left.
     */
    private BookGeometry.Rect columnOneRail() {
        int width = panelFolded() ? railWidth(overlay2) : railWidth(leftKind());
        return dockOpen ? geometry().panelRailBefore(width, dockRail())
                : geometry().panelRail(width);
    }

    /**
     * The rail one presented kind occupies.
     *
     * <p>Three answers, and they are the arrangement's three rails: the dock's own, column 1 (or the folded
     * slot, which is column 1 holding the child), and column 2 one gap to column 1's left. Every inside-a-panel
     * rectangle in this class comes from here through {@link #surfaceCard}, so the drawing, the widget build
     * and the press all agree about which rail a kind is in without any of them deriving it again.
     */
    BookGeometry.Rect panelRail(PanelKind kind) {
        if (kind == departDrawKind && departDrawRail != null) {
            return departDrawRail;
        }
        if (kind == PanelKind.TOOLS) {
            return dockRail();
        }
        BookGeometry.Rect first = columnOneRail();
        if (panelFolded() || overlay2 == PanelKind.NONE || overlay2 != kind) {
            return first;
        }
        return geometry().panelRailBefore(railWidth(overlay2), first);
    }

    /**
     * How long the arrival takes: the theme's own motion, capped so it cannot be felt as lag.
     *
     * <p>The cap is the one deliberate deviation from "the theme's motion is the motion". A reveal this
     * long is a surface arriving, not a tween a reader is watching — and a press landing mid-slide is
     * answered by a control still travelling, so the cap is also the longest any widget stays inert.
     * A theme with no motion, and the accessibility switch, both give zero, which is a panel that is simply
     * there: that path is the one a player who has turned motion off gets, and it has no window at all.
     */
    int panelRevealMillis() {
        long asked = Math.min(PANEL_REVEAL_MAX, Motion.defaultDuration());
        return (int) Motion.scaledDuration(asked);
    }

    /** Milliseconds since one rail started arriving, or a huge number once it is settled. */
    long panelRevealElapsed(PanelKind kind, long now) {
        Long start = panelRevealStarts.get(kind);
        return start == null || start == 0 ? Long.MAX_VALUE : now - start;
    }

    /**
     * The presented rails as a press sees them: what each holds, where it is, and how much has arrived.
     *
     * <p>Every presented kind is a rail now, so this list is the rules' own list with rectangles attached.
     * <b>Each rail is measured against its own arrival</b>, which is the difference the dock's own column
     * makes: a panel opening beside a dock that is already there slides one rail and leaves the other alone,
     * so the author's tools do not slide in again on every click.
     */
    private List<PanelLayout.PanelColumn> panelColumns() {
        long now = net.minecraft.util.Util.getMillis();
        int duration = panelRevealMillis();
        List<PanelLayout.PanelColumn> out = new ArrayList<>(3);
        for (PanelKind kind : PanelStack.presented(columns(), panelFolded())) {
            BookGeometry.Rect rail = panelRail(kind);
            out.add(new PanelLayout.PanelColumn(kind, rail,
                    PanelLayout.revealRect(rail, panelRevealElapsed(kind, now), duration)));
        }
        return out;
    }

    /**
     * Which rail a point belongs to, or null when it belongs to the book beside them.
     *
     * <p>One resolver for the press, the wheel and the drag handle. Three callers asking separately would
     * be three chances to disagree about the boundary — and the boundary is the revealed edge of a rail
     * that may still be arriving, which is the one answer this arrangement cannot afford to have twice.
     */
    PanelKind panelColumnAt(double mouseX, double mouseY) {
        return PanelLayout.columnAt(panelColumns(), mouseX, mouseY);
    }

    /**
     * Whether one rail is still arriving, which is when its controls are travelling and inert.
     *
     * <p>Keyed on the kind rather than on the arrangement: a rail is arriving exactly when a start has been
     * recorded for <i>it</i> and its slide has not finished. Asked per rail by the slide and the settle
     * rebuild, so a rail that is already there keeps live controls while the one beside it arrives.
     */
    boolean panelRevealing(PanelKind kind, long now) {
        return panelRevealStarts.containsKey(kind)
                && PanelLayout.revealed(panelRevealElapsed(kind, now), panelRevealMillis()) < 1F;
    }

    /** Whether any rail that is on screen is still arriving, which is when a rebuild has to wait. */
    private boolean anyPanelRevealing(long now) {
        for (PanelKind kind : PanelStack.presented(columns(), panelFolded())) {
            if (panelRevealing(kind, now)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Moves every presented rail's widgets with its surface, for this frame.
     *
     * <p>Called from {@code render} before the widget pass, with the frame's own time: a widget is
     * written {@code base + offset} — its placed position plus {@link PanelLayout#slideOffset}'s answer
     * for its rail — so the controls travel left with the sheet they sit on rather than appearing on
     * it once it has landed. Stateless by design: the placed position is never overwritten, so a
     * rebuild for any other reason mid-slide simply re-files new bases and the next frame derives the
     * same answer again.
     *
     * <p>The base is read here, on the first frame that slides, rather than where the widget is added:
     * a field built for a scrolling list is constructed at 0,0 and placed by its view afterwards, and
     * filing the construction position pinned every one of those fields at the window's edge. By the
     * first render the build — placement included — is done, so what is read is where the widget lives.
     *
     * <p>Travelling widgets are inert ({@code active = false}) but shown: a press landing mid-slide
     * must not catch a control that has not arrived, while hiding them would be the old pop-in again.
     * The settle rebuild in {@code tick} hands back their live state; {@link PanelLayout#columnAt}
     * already routes presses on the unarrived strip to the canvas, so the dead frames have nowhere to
     * leak through.
     */
    private void slidePanelWidgets(long now) {
        int duration = panelRevealMillis();
        for (PanelKind kind : PanelStack.presented(columns(), panelFolded())) {
            java.util.List<AbstractWidget> owned = slideWidgets.get(kind);
            if (owned == null || owned.isEmpty()) {
                continue;
            }
            int offset = PanelLayout.slideOffset(panelRail(kind), panelRevealElapsed(kind, now),
                    duration);
            if (offset != 0) {
                panelBuildPending = true;
            }
            for (AbstractWidget widget : owned) {
                Integer base = slideBaseX.get(widget);
                if (base == null) {
                    base = widget.getX();
                    slideBaseX.put(widget, base);
                }
                widget.setX(base + offset);
                if (offset != 0) {
                    widget.active = false;
                }
            }
        }
    }

    /**
     * The surface the current {@link #surfaceKind} occupies, as its four numbers.
     *
     * <p>Read by every position inside a panel — its header's icon, its body's clip, its footer's controls,
     * its rows' rectangles — so a kind being drawn, built or pressed measures itself against its own rail
     * and never against the one beside it.
     */
    int overlayLeft() {
        return surfaceCard(surfaceKind).x();
    }

    int overlayTop() {
        return surfaceCard(surfaceKind).y();
    }

    int overlayWidth() {
        return surfaceCard(surfaceKind).width();
    }

    int overlayHeight() {
        return surfaceCard(surfaceKind).height();
    }

    // ------------------------------------------------------------------
    // Content
    // ------------------------------------------------------------------

    /**
     * Every chapter the book can show, by id, in the order the sidebar draws them.
     *
     * <h2>Why this is remembered rather than rebuilt</h2>
     *
     * <p>Because it is not a small map: it walks every chapter <b>and every quest</b>, and the frame path
     * asks for it constantly — {@link #effectiveChapter()} is that map's first key, and it is read by the
     * drawing, the theme lookups, the draft reconciliation and every panel, several times a frame. On a
     * nine-hundred-quest pack that was nine hundred map insertions per call, twenty-odd times a frame, for
     * an answer that cannot change until the tree does.
     *
     * <p>The tree revision is the whole of the key: both inputs are the cache's chapter list and its
     * entries, and neither moves without a tree arriving. Progress is deliberately not in it — a quest
     * completing cannot rename a chapter.
     *
     * <p>A chapter withheld until its gate is met is left out, which is the one thing here that is not a
     * rename: this map is what {@link #effectiveChapter} opens the book <i>by</i>, so a hidden chapter
     * kept in it would be the chapter a reader with nothing selected lands on. The gate's own state
     * therefore does move this answer, and the caller that hides a chapter is the same predicate that
     * makes {@link #sidebar()} rebuild on progress — see {@link #chapterHidden}.
     */
    static Map<String, String> chapters() {
        long revision = ClientQuestCache.treeRevision();
        // Progress and the editing flag are in the key as well as the tree, because since chapter gates
        // this map's *contents* move with both: a chapter leaves it when the gate it was hiding behind is
        // met, and an author sees every chapter. The note above says the tree was the whole of the key --
        // that was true while the only thing this map did was name chapters.
        long progress = ClientQuestCache.progressRevision();
        if (chaptersHeld != null && chaptersRevision == revision && chaptersProgress == progress
                && chaptersEditing == editingView) {
            return chaptersHeld;
        }
        Map<String, String> chapters = new LinkedHashMap<>();
        // The explicit list first, then the quests: the same merge, in the same order, as the sidebar's
        // own build. An empty chapter has to be selectable here too, or the book would draw a row for it
        // and then refuse to open on it -- the selection falls back to the first chapter that a *quest*
        // names.
        for (ClientQuestCache.ChapterEntry chapter : ClientQuestCache.chapters()) {
            if (chapterHidden(chapter.id())) {
                // A chapter its author asked to withhold until its gate is met is not a chapter the book
                // knows about yet -- and this map is what the book opens *by*, so leaving it in would
                // make the hidden chapter the one a reader lands on with nothing selected.
                continue;
            }
            chapters.putIfAbsent(chapter.id(), chapter.titleText());
        }
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            if (chapterHidden(entry.chapterId())) {
                continue;
            }
            chapters.putIfAbsent(entry.chapterId(), entry.chapterTitleText());
        }
        chaptersHeld = chapters;
        chaptersRevision = revision;
        chaptersProgress = progress;
        chaptersEditing = editingView;
        return chapters;
    }

    /** The chapter map as it was last built, and the tree it was built from. See {@link #chapters}. */
    private static Map<String, String> chaptersHeld;
    private static long chaptersRevision = Long.MIN_VALUE;

    /** And the other two inputs that map's contents now depend on. See {@link #chapters}. */
    private static long chaptersProgress = Long.MIN_VALUE;
    private static boolean chaptersEditing;

    /**
     * The chapter the book is showing.
     *
     * <p>Simply this map's first key, or the selection when it is still one of them — and that is enough,
     * because {@link #chapters()} has already left out every chapter this viewer should not see. The
     * filter is deliberately in that one place: a second copy here would be a second answer to "may this
     * chapter be opened", and the two would disagree the first time one of them changed.
     */
    static String effectiveChapter() {
        if (frameChapterActive) {
            return frameChapter;
        }
        return computeEffectiveChapter();
    }

    private static String computeEffectiveChapter() {
        Map<String, String> chapters = chapters();
        if (chapters.isEmpty()) {
            return null;
        }
        if (selectedChapter != null && chapters.containsKey(selectedChapter)) {
            return selectedChapter;
        }
        return chapters.keySet().iterator().next();
    }

    List<ClientQuestCache.Entry> questsIn(String chapterId) {
        // An author sees every quest, hidden flags or not. The flags are exactly the ones an author
        // sets and then has to keep working on -- a node that vanished from the canvas the moment its
        // `hideUntilDependenciesVisible` was set could not be placed, wired or clicked, which makes the
        // feature unusable by the person who asked for it. So developer mode shows them all, and
        // `drawNode` marks the ones the reader would not see; the reader's own book hides them, which
        // is the whole point of the flags.
        boolean authoring = mayEditNow();

        // Built once per (chapter, tree, progress, authoring) rather than once per call, and the calls are
        // per frame and per press: the canvas asks what to draw, the hover asks what was drawn, and a click
        // asks again -- each one a walk of every quest in the pack with a visibility rule per entry.
        //
        // **Four inputs, and the draft is deliberately not one of them.** The walk reads
        // `entry.chapterId()` and `questVisible(entry.id())`, and `questVisible` is remembered per tree and
        // per progress alone -- its own note says "nothing here reads a draft". So a draft version in this
        // key invalidated a whole-pack walk for a question the draft cannot change: every stepper press, and
        // every preview write of a scrub, re-walked and re-allocated the canvas's quest list.
        //
        // The old comment here claimed "the five inputs are every one of the walk's", which was the
        // over-keying stated as a virtue. It is worth keeping the mistake in view: an input added to a key
        // "to be safe" is a cache that misses, and a comment asserting completeness is not the same as one
        // that has been checked against the walk.
        long tree = ClientQuestCache.treeRevision();
        long progress = ClientQuestCache.progressRevision();
        if (questsInHeld != null && java.util.Objects.equals(questsInChapter, chapterId)
                && questsInTree == tree && questsInProgress == progress
                && questsInAuthoring == authoring) {
            return questsInHeld;
        }

        List<ClientQuestCache.Entry> built = ClientQuestCache.entries().stream()
                .filter(entry -> entry.chapterId().equals(chapterId))
                // Hidden quests are filtered out rather than withheld: every flag travels with the tree,
                // so a quest that becomes visible because it was completed -- or because a prerequisite
                // was -- needs no second sync to appear. The rules themselves are `QuestVisibility`'s,
                // where a test can hold them; this only asks.
                .filter(entry -> authoring || questVisible(entry.id()))
                .toList();
        questsInHeld = built;
        questsInChapter = chapterId;
        questsInTree = tree;
        questsInProgress = progress;
        questsInAuthoring = authoring;
        return built;
    }

    /** The chapter's quests as they were last built, and every input they were built from. */
    private List<ClientQuestCache.Entry> questsInHeld;
    private String questsInChapter;
    private long questsInTree = Long.MIN_VALUE;
    private long questsInProgress = Long.MIN_VALUE;
    private boolean questsInAuthoring;

    /**
     * Whether a quest is drawn at all: {@code QuestVisibility}'s rules, read off the client's cache.
     *
     * <p>The three functions the rules take are the client's three answers: the team's state for a
     * quest, whether its prerequisite rule is met (through {@code DependencyProgress}, so
     * {@code minRequired} and the started-based modes are the same here as everywhere else), and how
     * many of its tasks have any progress.
     */
    static boolean questVisible(String id) {
        // Remembered per tree and per progress, and those two are the whole of the answer's inputs: the
        // rules read the quest's own flags (the tree), its state, its prerequisite rule and how many of its
        // tasks have progress (progress) — and the exclusion answer below reads the file's setting (the
        // tree) and the server's mark (progress). Nothing here reads a draft or the view.
        //
        // It is worth remembering because `drawNode` asks it for **every node it draws**, every frame, in
        // edit mode — and each call is a recursive walk of the prerequisite chain that allocates a deque
        // before it starts. At nine hundred nodes that is nine hundred graph walks a frame to decide whether
        // to draw a dashed "hidden" mark.
        long tree = ClientQuestCache.treeRevision();
        long progress = ClientQuestCache.progressRevision();
        if (visibleCache == null || visibleTree != tree || visibleProgress != progress) {
            visibleCache = new HashMap<>();
            visibleTree = tree;
            visibleProgress = progress;
        }
        Boolean known = visibleCache.get(id);
        if (known != null) {
            return known;
        }
        // The exclusion answer travels with the rule's own inputs — the file's setting on the tree,
        // the server's mark on the progress — so the tree-plus-progress key above stays the whole of
        // what this answer depends on. See `QuestVisibility.visible` for the rule itself.
        boolean answer = dev.ellipog.tenet.client.dev.QuestVisibility.visible(id, VISIBILITY_LOOKUP,
                ClientQuestCache::stateOf, QuestBookScreen::prerequisiteRuleMet,
                QuestBookScreen::tasksWithProgress, ClientQuestCache::excludedOf,
                ClientQuestCache.hideExcludedQuests());
        visibleCache.put(id, answer);
        return answer;
    }

    /** The visibility answers for one tree and one progress revision. See {@link #questVisible}. */
    private static Map<String, Boolean> visibleCache;
    private static long visibleTree = Long.MIN_VALUE;
    private static long visibleProgress = Long.MIN_VALUE;

    /**
     * Whether a chapter is withheld from this viewer.
     *
     * <h2>The rule is {@code ChapterVisibility}'s; this is the cache and the answers</h2>
     *
     * <p>Three things hide a chapter from a reader: its author asking for it to be always
     * invisible, its author asking to be withheld until its own gate is met, and it having
     * <b>nothing visible in it</b> — every quest still hidden behind its own
     * prerequisites, every quest an unmoved easter egg, or no quests at all. The third is the base rule
     * and it is why the earlier version of this method was wrong: it only asked about the author's flag,
     * so a chapter of fifty quests that none of which were visible yet still drew a row into an empty
     * canvas — and so did a chapter with a single note in it and nothing else.
     *
     * <p>An author bypasses all three rules, which is a parameter of the rule rather than a second check here,
     * so the whole of it can be asserted in {@code ChapterVisibilityTest} — the screen cannot be
     * instantiated by a test, and this is behaviour a person only ever sees as a missing row.
     */
    static boolean chapterHidden(String chapterId) {
        return !dev.ellipog.tenet.client.dev.ChapterVisibility.visible(chapterId,
                CHAPTER_VISIBILITY_LOOKUP, editingView);
    }

    /** The cache's answer to {@code ChapterVisibility}'s questions. */
    private static final dev.ellipog.tenet.client.dev.ChapterVisibility.Lookup CHAPTER_VISIBILITY_LOOKUP =
            new dev.ellipog.tenet.client.dev.ChapterVisibility.Lookup() {
                @Override
                public boolean hidesUntilDependenciesComplete(String chapterId) {
                    ClientQuestCache.ChapterEntry chapter = chapterEntryFor(chapterId);
                    return chapter != null && chapter.hideUntilDependenciesComplete();
                }

                @Override
                public boolean alwaysInvisible(String chapterId) {
                    ClientQuestCache.ChapterEntry chapter = chapterEntryFor(chapterId);
                    return chapter != null && chapter.alwaysInvisible();
                }

                @Override
                public QuestState state(String chapterId) {
                    return ClientQuestCache.chapterStateOf(chapterId);
                }

                @Override
                public boolean hasVisibleQuest(String chapterId) {
                    return chaptersWithVisibleQuests().contains(chapterId);
                }
            };

    /**
     * Which chapters hold at least one quest this reader can see.
     *
     * <h2>Why a set rather than a per-chapter walk</h2>
     *
     * <p>Because it is asked for every chapter, by the sidebar, {@code chapters()} and the jump paths, and
     * a walk of every quest per chapter is a walk of the whole pack per chapter — a nine-hundred-quest
     * book would pay for itself twelve times to answer one question twelve ways. One pass collects the
     * answer for all of them, and it is remembered per tree and per progress, which is the whole of its
     * inputs: {@code questVisible} is itself cached on those two, so this pass is a set insert per entry
     * rather than a second graph walk.
     *
     * <p>Deliberately <b>not</b> keyed on the editing flag, unlike {@link #chapters()}: this is the
     * reader's answer, and an author never asks it — {@code ChapterVisibility} answers "visible" for an
     * author before consulting it at all.
     */
    private static Set<String> chaptersWithVisibleQuests() {
        long tree = ClientQuestCache.treeRevision();
        long progress = ClientQuestCache.progressRevision();
        if (visibleChapters != null && visibleChaptersTree == tree
                && visibleChaptersProgress == progress) {
            return visibleChapters;
        }
        Set<String> shown = new HashSet<>();
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            if (questVisible(entry.id())) {
                shown.add(entry.chapterId());
            }
        }
        visibleChapters = Set.copyOf(shown);
        visibleChaptersTree = tree;
        visibleChaptersProgress = progress;
        return visibleChapters;
    }

    /** The chapters with something visible in them, and the tree and progress they were read from. */
    private static Set<String> visibleChapters;
    private static long visibleChaptersTree = Long.MIN_VALUE;
    private static long visibleChaptersProgress = Long.MIN_VALUE;

    /**
     * The server's own record of a chapter, or null for one only the quests name.
     *
     * <p>Null rather than a synthesised entry, because a chapter that arrived as a property of its quests
     * is a chapter of a server older than the explicit list — and such a server sends no gate either, so
     * "not in the list" and "no gate" are the same answer here.
     */
    static ClientQuestCache.ChapterEntry chapterEntryFor(String chapterId) {
        for (ClientQuestCache.ChapterEntry chapter : ClientQuestCache.chapters()) {
            if (chapter.id().equals(chapterId)) {
                return chapter;
            }
        }
        return null;
    }

    /** The cache's answer to the visibility rules' questions, for any quest id -- other chapters too. */
    private static final dev.ellipog.tenet.client.dev.QuestVisibility.Lookup VISIBILITY_LOOKUP =
            new dev.ellipog.tenet.client.dev.QuestVisibility.Lookup() {
                @Override
                public boolean invisible(String id) {
                    ClientQuestCache.Entry entry = cacheEntryFor(id);
                    return entry != null && entry.invisible();
                }

                @Override
                public int invisibleUntilTasks(String id) {
                    ClientQuestCache.Entry entry = cacheEntryFor(id);
                    return entry == null ? 0 : entry.invisibleUntilTasks();
                }

                @Override
                public boolean hideUntilDependenciesComplete(String id) {
                    ClientQuestCache.Entry entry = cacheEntryFor(id);
                    return entry != null && entry.hideUntilDependenciesComplete();
                }

                @Override
                public boolean hideUntilDependenciesVisible(String id) {
                    ClientQuestCache.Entry entry = cacheEntryFor(id);
                    return entry != null && entry.hideUntilDependenciesVisible();
                }

                @Override
                public List<String> dependencies(String id) {
                    ClientQuestCache.Entry entry = cacheEntryFor(id);
                    return entry == null ? List.of() : dependenciesOf(entry);
                }
            };

    /** Whether a quest's prerequisite rule is satisfied. An unknown quest has none, so it is met. */
    private static boolean prerequisiteRuleMet(String id) {
        ClientQuestCache.Entry entry = cacheEntryFor(id);
        return entry == null || dependencyProgressOf(entry).met(ClientQuestCache::stateOf);
    }

    /** How many of a quest's tasks have any progress: what `invisibleUntilTasks` counts. */
    private static int tasksWithProgress(String id) {
        ClientQuestCache.Entry entry = cacheEntryFor(id);
        if (entry == null) {
            return 0;
        }
        int touched = 0;
        for (int i = 0; i < entry.tasks().size(); i++) {
            if (ClientQuestCache.taskProgressOf(id, i) > 0) {
                touched++;
            }
        }
        return touched;
    }

    ClientQuestCache.Entry entryFor(String questId) {
        return cacheEntryFor(questId);
    }

    /** The same lookup, static: the visibility rules are static and have to reach the cache too. */
    private static ClientQuestCache.Entry cacheEntryFor(String questId) {
        if (questId == null) {
            return null;
        }
        // By id or alias, in any letter case: the tree carries the aliases since version 16, and a
        // press naming a pre-rename spelling opens the quest rather than reporting a broken control.
        // An older server sends none, and the match falls back to the id alone.
        return ClientQuestCache.entries().stream()
                .filter(entry -> entry.matches(questId))
                .findFirst()
                .orElse(null);
    }

    // ------------------------------------------------------------------
    // Canvas coordinates
    // ------------------------------------------------------------------

    /**
     * The canvas transform, with its bounds taken from the current window.
     *
     * <p>Re-applied on every use rather than cached, so the transform cannot outlive the canvas it
     * describes. A resize is announced to {@code init} and to nothing else, and {@link #render} and
     * {@code mouseClicked} are not in an order this class could rely on — so a bound rectangle stored
     * once would be describing the previous window for an unknown number of frames.
     */
    Viewport viewport() {
        if (frameActive && frameViewport != null) {
            return frameViewport;
        }
        BookGeometry.Rect canvas = geometry().canvas();
        return VIEW.bounds(canvas.x(), canvas.y(), canvas.width(), canvas.height());
    }

    /**
     * The overlay's body rectangle, as a viewport.
     *
     * <p>Re-applied on every use, like {@link #viewport()} and for the same reason. Its offset is the
     * scroll, its content height is whatever the last {@link BookSettings#overlayLayout} said, and it is the one
     * rectangle the clip, the scroll clamp and the scrollbar all read — which is the point, because
     * they used to be three.
     */
    Viewport overlayBody() {
        return overlayView.viewport().bounds(
                overlayLeft() + BODY_INSET,
                overlayTop() + BODY_TOP,
                overlayWidth() - BODY_INSET * 2,
                overlayHeight() - BODY_TOP - BODY_BOTTOM);
    }

    /**
     * A node's drawn size: the quest's own size, times the zoom.
     *
     * <p>This used to clamp the size to 26..48, which made the file's 16..512 range a field that did
     * nothing above 48 — a 96-pixel node drew at 48 and the author had no way to see that their number
     * was being ignored. The clamp is gone: the number in the file is the number on screen. The floor of
     * twelve is not a clamp on the size but the smallest a node can be drawn and still be a target, and
     * it is reached by zooming out, not by a file.
     */
    int nodeSize(ClientQuestCache.Entry entry) {
        int size = fieldDraft.number(entry.chapterId(), entry.id(), "size", entry.size());
        return Math.max(12, Math.round(size * viewport().scale()));
    }

    int nodeScreenX(ClientQuestCache.Entry entry) {
        return viewport().screenX(nodeX(entry));
    }

    int nodeScreenY(ClientQuestCache.Entry entry) {
        return viewport().screenY(nodeY(entry));
    }

    /**
     * Where a node's x is, for the drawing and for the hit test.
     *
     * <h2>Three answers, in this order</h2>
     *
     * <p>The node being dragged right now; else a position the editor has changed but the server has not
     * sent back yet; else what the server sent. The first two exist because the canvas draws the
     * <i>server's</i> tree and the editor writes <i>files</i>: between a drag and the reload that follows
     * it, the server's answer is stale, and a canvas that used it would snap the node back under the
     * pointer — which reads as a drag that did nothing.
     *
     * <p>One method, used by the drawing, the hit test and the dependency lines, because the alternative
     * is three places that each have to remember the same exception. A node dragged with its line left
     * behind is the picture of what that costs.
     *
     * <p>And the <b>press</b> reads it too, and that order is load-bearing: a press picks a node up by
     * asking where it is <i>before</i> claiming it, because claiming is what makes this method answer
     * with {@link #dragNodeX} — which, before the follow has run, is whatever the last drag left there.
     * The first version of the drag read after claiming and every commit landed at 0,0.
     */
    float nodeX(ClientQuestCache.Entry entry) {
        if (entry.id().equals(draggedNode)) {
            return dragNodeX;
        }
        return editors != null && editors.hasMoved(entry.id())
                ? (float) editors.movedX(entry.id()) : entry.x();
    }

    /** The same, for y. See {@link #nodeX}. */
    float nodeY(ClientQuestCache.Entry entry) {
        if (entry.id().equals(draggedNode)) {
            return dragNodeY;
        }
        return editors != null && editors.hasMoved(entry.id())
                ? (float) editors.movedY(entry.id()) : entry.y();
    }

    /** Whether a node is selected at all: the primary the panel edits, or one of the multi-selection. */
    static boolean isSelected(String id) {
        return id.equals(selectedQuest) || multiSelection.contains(id);
    }

    /** The whole selection: the primary first, then the rest in the order they were added. */
    static List<String> selection() {
        List<String> all = new ArrayList<>();
        if (selectedQuest != null) {
            all.add(selectedQuest);
        }
        for (String id : multiSelection) {
            if (!id.equals(selectedQuest)) {
                all.add(id);
            }
        }
        return all;
    }

    /**
     * Whether this player may edit at all: edit mode on, and the permission the server will check for itself.
     *
     * <p>This used to also open the chapter's files on the *client*, which went with the client's write path.
     * The files belong to the server now; a client on a dedicated server has none of its own to open, and the
     * gate that matters is the one in the payload handler. What is left here is the courtesy — not offering a
     * control whose only answer would be a refusal.
     *
     * <p>Two ops-2 authors on one server are therefore a supported state, and the optimistic drafts assume
     * one: a structural edit by the other author moves the indices a pending value names. Locally that
     * shift is forgotten at the gesture ({@code FieldDraft.forgetList}); from the other author it is
     * caught only when the copy comes back shorter than the draft's index (the guard in
     * {@code reconcile}), and a same-length reorder is left to the {@code STALE_MILLIS} backstop.
     */
    boolean mayEditNow() {
        return editEffective(DevMode.on(), mayEdit(), previewAsPlayer);
    }

    /**
     * The effective edit gate, as pure inputs: edit mode on, the operator permission, and the
     * player's-view preview latch.
     *
     * <p>Static and pure so a test can hold it: the three flags are one truth table, and the only row
     * that answers false with the first two true is the preview. Previewing pauses editing without
     * touching edit mode itself, so leaving the preview restores exactly the mode that was left.
     */
    static boolean editEffective(boolean devOn, boolean hasPermission, boolean preview) {
        return devOn && hasPermission && !preview;
    }

    /**
     * Whether the sidebar's rows leave room for the scrollbar's gutter.
     *
     * <p>Static and pure so a test can hold it: the content height is rows times the pitch the
     * composition spaces them with, minus the one gap the composition puts between rows rather
     * than after the last one — see {@link SidebarLayout#composition} for why that gap is not
     * trailing. Exactly fitting is not scrolling (a bar with nowhere to go draws nothing), so
     * the comparison is strict.
     */
    static boolean sidebarReservesScrollbar(int rowCount, int viewportHeight) {
        return Math.max(0, rowCount * SidebarLayout.pitch() - BookGeometry.SIDEBAR_ROW_GAP)
                > viewportHeight;
    }

    /**
     * Whether this screen is previewing the book as a player sees it: every hidden chapter and quest
     * filtered out, every editing gesture off, every quest card the reader's.
     *
     * <p>Session-only on purpose, like the dock's own openness: nothing is written to disk, so a
     * reopened book is an editor again rather than a preview somebody forgot they asked for. The
     * geometry follows it too — see {@link #geometry} — so the panel is the reader's card at the
     * reader's size, and the way back out is the floating pill over the canvas rather than the band,
     * which a reader's geometry does not have.
     */
    boolean previewAsPlayer;

    /**
     * Whether the showing preview was entered through {@code /tenet preview} rather than the pill.
     *
     * <p>A command-entered preview draws no floating exit pill: the pill is operator chrome a
     * player never sees, so drawing it would make the preview exactly what it exists not to be.
     * The way out is the command again (or closing the book, which builds an editor next time),
     * while a pill-entered preview keeps the pill as its way out.
     *
     * <p>It also hides vanilla chat — see {@link #tickPreviewChat} — for the same reason: the
     * command and its reply sit in the history a player would never have.
     */
    boolean previewFromCommand;

    /**
     * The intro's anchors and buttons, re-placed every frame it shows.
     *
     * <p>Null unless drawn this frame: the quest card's add rows file their boxes as they draw,
     * and the tooltip files its buttons as it draws, so a closed panel or a hidden tour cannot
     * leave a stale box behind — and a press tests the fields rather than re-deriving them.
     */
    BookGeometry.Rect introTaskBox;
    BookGeometry.Rect introRewardBox;

    /**
     * A {@code /tenet preview} ask parked between the payload and the screen's construction.
     *
     * <p>Null means no ask: the book opens as it was. Non-null means the command named an answer,
     * consumed once by {@code init} like {@link viewer.QuestBookFocus}. Tri-state rather than a
     * boolean because "toggle" cannot be resolved until there is a latch to flip — and the handler
     * cannot assume this screen exists, so it parks the ask instead of flipping anything itself.
     * A toggle parked while the book is closed flips "off" (there is no preview to leave), which is
     * the only reading that opens the player's view rather than re-opening the editor.
     */
    private static Boolean previewRequest;

    /**
     * Parks a {@code /tenet preview} ask for the next time the book is constructed.
     *
     * @param toggle whether to flip the current preview rather than set it
     * @param on     the absolute answer when {@code toggle} is false
     */
    public static void requestPreview(boolean toggle, boolean on) {
        if (toggle) {
            // Null (no ask) and false (an ask for the editor) both flip to the player's view: a
            // toggle with the book closed is an operator asking to see the player's view, not an
            // operator asking to re-open the editor they are already standing outside of.
            previewRequest = previewRequest == null ? Boolean.TRUE : !previewRequest;
        }
        else {
            previewRequest = on;
        }
    }

    /** Takes the parked preview ask, if there is one. The second call always answers empty. */
    static java.util.Optional<Boolean> consumePreviewRequest() {
        Boolean ask = previewRequest;
        previewRequest = null;
        return java.util.Optional.ofNullable(ask);
    }

    /** Drops a parked preview ask. Called with the rest of the view state on disconnect. */
    static void clearPreviewRequest() {
        previewRequest = null;
    }

    /**
     * Whether the player's-view preview is actually showing: the latch lit, and still an operator.
     *
     * <p>Both halves, because the latch outlives neither a de-op (cleared in
     * {@link BookCanvas#refreshEditingView}) nor edit mode being turned off (cleared in {@link BookQuestEditor#setEditing}).
     * The geometry and the band guard both read this one method, so the panel's shape and the
     * controls on it cannot disagree about whether a preview is showing.
     */
    boolean previewing() {
        return mayEdit() && previewAsPlayer;
    }

    /**
     * The chat visibility a command-entered preview overrode, or null when it overrode nothing.
     *
     * <p>Static because the screen that hid chat may be gone by the time it is restored: the
     * command-off path rebuilds the book (a new instance with the latch off), and closing the
     * book drops the instance entirely. Reconciled per tick rather than on transitions, so every
     * exit — command, close, de-op, disconnect — restores through the same line.
     */
    private static net.minecraft.world.entity.player.ChatVisiblity chatHiddenFrom;

    /**
     * Hides vanilla chat while a command-entered preview is open, restoring it after.
     *
     * <p>Called once per client tick by each loader (like every other per-tick bookkeeping in
     * their tick blocks). The command and its reply sit in the history no player would have, so
     * a preview that left chat up would not be the player's view. Only the command path hides:
     * a pill-entered preview keeps chat exactly as it was.
     *
     * <p>Restores the stored value rather than a default, so a player who normally runs
     * commands-only gets commands-only back. If they change the setting mid-preview it is
     * re-hidden next tick and the original still wins on exit; a crash mid-preview is the one
     * case the stored value outlives, and that is one row in the options screen to flip back.
     *
     * <p>Public because both loaders call it from their tick, like {@link #openOn}.
     */
    public static void tickPreviewChat() {
        Minecraft game = Minecraft.getInstance();
        if (game == null || game.options == null) {
            return;
        }
        boolean hidden = game.gui.screen() instanceof QuestBookScreen book
                && book.previewing() && book.previewFromCommand;
        var chat = game.options.chatVisibility();
        if (hidden) {
            if (chatHiddenFrom == null) {
                chatHiddenFrom = chat.get();
            }
            if (chat.get() != net.minecraft.world.entity.player.ChatVisiblity.HIDDEN) {
                chat.set(net.minecraft.world.entity.player.ChatVisiblity.HIDDEN);
            }
        }
        else if (chatHiddenFrom != null) {
            chat.set(chatHiddenFrom);
            chatHiddenFrom = null;
        }
    }

    /**
     * Whether the viewer may edit, as the <b>static</b> helpers see it.
     *
     * <h2>Why a mirrored flag rather than a parameter</h2>
     *
     * <p>Because three of the answers that decide what the book contains are static and cached —
     * {@link #chapters()}, {@link #effectiveChapter()} and {@link #sidebar()} — and all three are asked
     * for dozens of times a frame from places that have no screen in reach. Threading a boolean through
     * {@code effectiveChapter}'s forty-odd call sites would put the same argument in forty places for one
     * fact, and every one of them would be a place to get it wrong.
     *
     * <p>Written in the two places the answer can change while the screen is alive: every
     * {@code rebuildWidgets}, which is where a screen's own state is picked up, and {@link #init}, which
     * runs before the first rebuild and builds panels that read it on the way. It defaults to false,
     * which is the reader's answer — the direction that hides a chapter rather than showing one to
     * somebody who may not be an author.
     */
    static boolean editingView;

    // ------------------------------------------------------------------
    // Widgets
    // ------------------------------------------------------------------

    /**
     * Creates a control, adds it to the screen, and remembers it.
     *
     * <p>One method rather than {@code addRenderableWidget(new ArmatureButton(...))} at each site,
     * because forgetting the remembering half is silent: the control draws and responds and simply has
     * no tooltip. Which is the sort of bug that survives a lot of testing.
     */
    ArmatureButton control(int x, int y, int w, int h, Component label, Runnable onPress) {
        ArmatureButton button = new ArmatureButton(x, y, w, h, label, onPress);
        buttons.add(button);
        // Nothing is recorded for a redraw, and that is the one-presentation simplification worth naming:
        // a rail is drawn in the chrome band *before* the widget pass, so this button is already on top of
        // its own surface. A card was drawn after the pass, which is why every control on one used to need a
        // second draw here -- and why filling this list now would paint the whole panel twice a frame.
        return addRenderableWidget(button);
    }

    /**
     * The same, from a rectangle {@link BookGeometry} produced.
     *
     * <p>This overload is what ties the screen to the test. {@code BookGeometryTest} can only assert
     * about rectangles it can be given, so the screen has to *create its controls from* those same
     * rectangles rather than from numbers written beside them. Otherwise the test would verify a
     * parallel description of the layout while the real one drifted — passing while the screen
     * overlapped, which is worse than having no test, because it would have been believed.
     *
     * <p>{@code null} is tolerated rather than asserted: a rectangle can legitimately be absent (no
     * quest selected, so no Open button) and {@code controls()} says so by omitting the key. A control
     * is skipped, not drawn at 0,0.
     */
    ArmatureButton control(BookGeometry.Rect rect, Component label, Runnable onPress) {
        if (rect == null) {
            return null;
        }
        return control(rect.x(), rect.y(), rect.width(), rect.height(), label, onPress);
    }

    /**
     * Creates a control in the header: added for <b>input</b>, drawn by hand instead.
     *
     * <h2>Why a second registration path exists at all</h2>
     *
     * <p>{@code Screen} keeps two lists, and until now nothing here cared. {@code addRenderableWidget}
     * puts a widget in both: {@code children} for input — clicks, hover, keyboard, narration — and
     * {@code renderables} for the base class's single drawing pass. {@code addWidget} puts it in
     * {@code children} only, which is exactly the arrangement a control in the header needs.
     *
     * <p>The reason is the clip. The widget pass is now clipped to start at the sidebar's list top, so
     * that a scrolled row cannot be drawn through the title bar — see {@code render}. Close sits above
     * that line, so the base pass would never draw it. Registering it here keeps every part of it that
     * is about <i>behaviour</i> with the base class, and moves only the drawing to the chrome layer,
     * which is also where a close button belongs: above the canvas, at the raised Z.
     *
     * <p>The alternative was to duplicate press and hover handling for one button, which would be a
     * second implementation of {@code AbstractWidget}'s own logic — the class of mistake this screen
     * has already been bitten by once, when the preview carried its own copy of the control styles.
     *
     * <p>Remembered in {@link #buttons} as well, so tooltips still find it.
     */
    /**
     * The party panel's controls: a Remove button per member the roster permits, plus Leave, Disband and
     * Back.
     *
     * <h2>What is deliberately not a widget, and why</h2>
     *
     * <p>The member <b>rows</b>. A press on a member would either do nothing or remove somebody, and a
     * control that removes a player on one click with no confirmation is not something to add merely
     * because a row is clickable. So the rows are drawn and the Remove buttons are controls, which is
     * the shape the roster itself describes -- {@code PartyRoster.removeSlot} returns null for a member
     * the viewer may not remove, and this makes a widget only where a slot exists.
     *
     * <h2>Why the member geometry is not from BookGeometry</h2>
     *
     * <p>Because a roster's rows are not a fixed layout: how many there are depends on who is in the
     * party. {@code BookGeometry} holds what is true of every window; this is what is true of one roster
     * in one window, and {@code PartyPanelLayout} -- Armature's, and game-free -- produces it. The
     * footer's own controls <i>are</i> fixed, so those do come from the geometry map, as every other
     * fixed control does.
     *
     * <h2>A limit, stated rather than implied</h2>
     *
     * <p>A roster taller than the card is <b>clipped rather than scrollable</b>, and the drawing is what
     * enforces it -- it skips a row that would fall outside the body rather than drawing it over the
     * header. That is a real limitation and an acceptable one: a party of more than six or seven is not
     * the case this is for, and the alternative is a second {@code ScrollView} that moves these widgets
     * the way the sidebar's does. Named here so whoever raises the limit knows what they are raising.
     */
    /**
     * The party button's tooltip: the roster, in words.
     *
     * <h2>Why the state lives here and not on the label</h2>
     *
     * <p>A label is fixed at construction and a tooltip is read per frame, and the roster is the thing
     * that changes. So the button says "Party", true whether you are in one or not, and the answer to
     * which party is one hover away. That also keeps the button's width a property of its label rather
     * than of the longest possible roster, which is what made the hand-written widths in this file wrong
     * in the first place.
     *
     * <p>Three states, and the middle one is why {@code isReal} exists rather than a member count: alone
     * in a party of one is not the same as having no party, and the two have the same number of members.
     */
    private List<Component> partyTooltip() {
        PartyRoster roster = partyRoster();

        if (!roster.isReal()) {
            return List.of(Component.translatable("tenet.screen.party.none"),
                    Component.translatable("tenet.screen.party.hint"));
        }

        List<Component> lines = new ArrayList<>();
        lines.add(roster.memberCount() == 1
                ? Component.translatable("tenet.screen.party.solo")
                : Component.translatable("tenet.screen.party.count", roster.memberCount()));
        for (PartyRoster.Member member : roster.members()) {
            lines.add(Component.translatable("tenet.screen.party.member", memberLabel(member),
                    roleName(member)));
        }
        return List.copyOf(lines);
    }

    /**
     * A member's name, marked when it is the reader.
     *
     * <p>Composed here rather than taken from Armature's {@code Member.label()}, which returns English
     * — "you" in parentheses — from a library that deliberately ships no language file. The library
     * owns the facts (a name, a role, whether this is you); the words are this mod's.
     */
    private static Component memberLabel(PartyRoster.Member member) {
        return member.self()
                ? Component.translatable("tenet.screen.party.you", member.name())
                : Component.literal(member.name());
    }

    /** The role word, from the same facts the roster carries. */
    static Component roleName(PartyRoster.Member member) {
        return dev.ellipog.tenet.PartyWords.role(member.role());
    }

    /**
     * The two columns' viewports of a card's body, left then right.
     *
     * <p>One function rather than two rectangles computed at each use: the split is
     * {@code PartyPanelLayout}'s share, and the two widths must add up to the body — a caller deriving
     * "the right column is what is left" a second time is the arithmetic that agrees until the gap
     * changes.
     */
    Viewport partyColumn(boolean left) {
        ScrollView view = left ? partyLeftView : partyRightView;
        BookGeometry.Rect body = partyBody();
        if (body == null) {
            return view.viewport().bounds(0, 0, 0, 0);
        }
        // **One face gets the whole body.** A panel with no right face -- the notice, a phase, and the
        // docked party's stack -- is one column of content, so it takes the full width rather than the left
        // share of a split with nothing on the other side of it.
        if (partyRightFace == null) {
            return view.viewport().bounds(body.x(), body.y(), body.width(), body.height());
        }
        int wide = PartyPanelLayout.leftWidth(body.width());
        int x = left ? body.x() : body.x() + wide + PartyPanelLayout.COLUMN_GAP;
        int width = left ? wide : PartyPanelLayout.rightWidth(body.width());
        return view.viewport().bounds(x, body.y(), width, body.height());
    }

    /**
     * The scroll view a pointer is over: the left column left of the split, the right column after.
     *
     * <p>The two columns scroll independently because they are different lists; a wheel aimed at the
     * roster must not move the settings out from under it. A pointer outside the card still lands on
     * one of them by this rule, which is the same thing the old single view did (clamped).
     */
    private ScrollView partyScrollAt(double mouseX) {
        BookGeometry.Rect body = partyBody();
        if (body == null || partyRightFace == null) {
            // One column of content: there is no split to aim at, and a bar drawn on the right half of a
            // panel with nothing in it would be a scrollbar for a view nobody can see.
            return partyLeftView;
        }
        int split = body.x() + PartyPanelLayout.leftWidth(body.width()) + PartyPanelLayout.COLUMN_GAP / 2;
        return mouseX < split ? partyLeftView : partyRightView;
    }

    /**
     * The band the party's faces fill: <b>the rail's own body</b>.
     *
     * <p>The same band every other kind's content gets, and not the card's inset rectangle: a card's body
     * runs the full height of a content-sized card, but a rail's must stop above the footer band or the
     * last roster rows would sit under the controls.
     */
    private BookGeometry.Rect partyBody() {
        if (partyCard == null) {
            return null;
        }
        PanelLayout.Frame frame = PanelLayout.frame(partyCard, carriesFold(PanelKind.PARTY),
                BookGeometry.MODAL_FOOTER_HEIGHT);
        if (carriesFold(PanelKind.PARTY)) {
            return frame.body();
        }
        // No header content: this panel draws no title in the column's 54px header band, so the
        // body starts below the border instead of below the band. The fold cell only exists when
        // this column carries it, which a root never does -- the Back control is built on the
        // child column -- so nothing overlaps up there.
        BookGeometry.Rect body = frame.body();
        int top = Math.min(partyCard.y() + PanelLayout.BODY_INSET, body.bottom());
        return BookGeometry.Rect.at(body.x(), top, body.width(), Math.max(0, body.bottom() - top));
    }

    /** A content-sized face's height, to hand {@code modalFramed}. */
    private int faceHeight(PartyPanelLayout.Face face, int width) {
        return face.build(width - BookGeometry.MODAL_INSET * 2, TEXT_MEASURE).height();
    }

    /** Whether parties can be used at all: singleplayer with LAN closed is the one refusal. */
    boolean partiesAvailable() {
        if (minecraft == null || !minecraft.hasSingleplayerServer()) {
            // A dedicated server, or a multiplayer one: parties are what the panel is for.
            return true;
        }
        var integrated = minecraft.getSingleplayerServer();
        return integrated == null || integrated.isPublished();
    }

    /**
     * A row's words: a translation key when one is given, the text itself otherwise.
     *
     * <p>The layout is game-free and cannot call {@code Component}, so it carries keys for the strings
     * a translator owns and literal text for the ones it composes from data — a member's name, a
     * count, an age. This is the one place the two are told apart, and the marker is the key prefix
     * every such key shares.
     *
     * <p>A label may carry one format arg after a {@code \0} separator (see
     * {@code PartyPanelLayout.ARG_SEP}): the outgoing {@code Invited %s} row and the filtered
     * {@code Nobody matches} search row. Split here and resolved via
     * {@code Component.translatable(key, arg)} so translators can reorder the sentence.
     */
    static String partyText(String label) {
        if (label == null) {
            return "";
        }
        int sep = label.indexOf('\0');
        if (sep >= 0) {
            String key = label.substring(0, sep);
            String arg = label.substring(sep + 1);
            if (key.startsWith("tenet.")) {
                return Component.translatable(key, arg).getString();
            }
            return arg.isEmpty() ? key : key + " " + arg;
        }
        return label.startsWith("tenet.") ? Component.translatable(label).getString() : label;
    }

    // ------------------------------------------------------------------
    // The drawer's controls
    // ------------------------------------------------------------------

    /**
     * Puts a menu up, and stamps when it opened.
     *
     * <p>The one writer every opener goes through — the sidebar's, the canvas's, the drawer's — so the
     * rise has one start to measure from. Rebuilding in place (arming Delete) re-stamps: the menu is
     * new content, and a 90-millisecond settle replays rather than sticks.
     */
    void openMenu(List<BookMenu.MenuItem> items) {
        menu = List.copyOf(items);
        menuOpenedAt = net.minecraft.util.Util.getMillis();
    }

    /**
     * When the Import popover opened, in {@code Util.getMillis()} terms; -1 when it never has.
     *
     * <p>Stamped at the toggle that opens it — the resets elsewhere only ever shut it. Read by
     * {@link BookRewards#importRise}, which is the popover's share of the menus' climb.
     */
    long tableImportOpenedAt = -1L;

    // --- the previews: the same patches the writers build, stamped as pending and not sent.

    /**
     * The pending chapter look: a patch a drag is showing but has not written.
     *
     * <p>Kept beside the {@code FieldDraft} rather than only in it because {@link #viewportTheme} is
     * static -- the same convention {@code selectedQuest} uses for screen-level state -- and because the
     * canvas needs the whole patch, not one path's value. It is what makes the canvas follow a drag before
     * the round trip; the commit then writes the same patch, and the draft's own reconcile retires both
     * when the tree carries the value.
     */
    static JsonObject pendingChapterTheme;

    JsonObject previewBase() {
        return themeTargetsChapter && effectiveChapter() != null
                ? questEditor.chapterThemePatchOf(effectiveChapter()).deepCopy() : null;
    }

    void previewChapterPatch(JsonObject patch) {
        String chapter = effectiveChapter();
        if (chapter == null) {
            return;
        }
        pendingChapterTheme = patch;
        fieldDraft.set(chapter, dev.ellipog.tenet.client.dev.FieldDraft.CHAPTER_OWNER, "themePatch",
                patch, ClientQuestCache.treeRevision(), net.minecraft.util.Util.getMillis());
    }

    /** A colour a picker is dragging: the same patch shape {@code writeThemeToken} writes. */
    private void previewToken(String token, int argb) {
        JsonObject patch = previewBase();
        if (patch == null) {
            return;
        }
        JsonObject colours = patch.has("colours") && patch.get("colours").isJsonObject()
                ? patch.getAsJsonObject("colours") : new JsonObject();
        colours.addProperty(token, String.format("#%08X", argb));
        patch.add("colours", colours);
        previewChapterPatch(patch);
    }

    // --- the chips and the picker

    /** Whether a press landed on a colour chip: the one rectangle a chip owns. */
    private boolean pressToolsChip(double mouseX, double mouseY) {
        for (ToolsLayout.Action row : activeRows()) {
            if (!row.isChip()) {
                continue;
            }
            BookGeometry.Rect chip = chipRect(row);
            if (chip == null || !chip.contains(mouseX, mouseY)) {
                continue;
            }
            // **Two kinds of chip, two pickers, and the prefix is the whole of the difference.** A token chip
            // edits the theme in force; an element's chip edits a colour in the chapter's own file, which is
            // the same popover pointed at a different door -- see `openElementColourPopover`. Without this
            // branch the Chapter tab's copy of the element form drew its colours and answered nothing, which
            // is the fault the shared-row design exists to prevent: one form, two places, both live.
            String[] element = ChapterPanelLayout.elementFieldOf(row.key());
            if (element != null) {
                bookPins.openElementColourPopover(row, toolsView, chipMode(row.key()), chip);
            }
            else {
                openColourPopover(row, chip);
            }
            return true;
        }
        return false;
    }

    /**
     * Where a colour row's chip is on screen right now, or null when that row is not in the list.
     *
     * <p>One derivation for the press that opens the picker and for the test that closes it again: a chip
     * the picker is anchored to has to be a chip that is <i>drawn</i>, and a second computation of the same
     * rectangle is the fault this screen has had from the drawer's first version. The composition is part of
     * that derivation, not a detail of it -- see {@link #chipMode}.
     */
    private BookGeometry.Rect chipRect(ToolsLayout.Action row) {
        Layout active = activeLayout();
        if (active == null || row == null || !row.isChip()) {
            return null;
        }
        dev.ellipog.armature.client.ui.kit.Slot slot = active.slot(row.key());
        if (slot == null) {
            return null;
        }
        dev.ellipog.armature.client.ui.kit.Slot onScreen =
                ToolsLayout.onScreen(toolsView.viewport(), slot);
        return ToolsLayout.chipOf(onScreen, chipMode(row.key()));
    }

    /**
     * How the drawer composed one chip row: stacked for the Chapter tab's own rows, side by side otherwise.
     *
     * <h2>Why this can be read from the key rather than remembered</h2>
     *
     * <p>Because the drawer composes by <i>list</i> and the lists are told apart by exactly this test: the
     * chapter's own rows -- the element form among them -- go through
     * {@code ToolsLayout.stack(chapterRows, STACKED)}, and the appearance rows appended under them are built
     * side by side. Every element row is one of the first and every token chip is one of the second, so the
     * prefix answers the same question the two builders answered, and answers it the same way for the
     * drawing and for the press.
     */
    private dev.ellipog.armature.client.ui.inspect.InspectLayout.Mode chipMode(String key) {
        return toolsTab == ToolsLayout.Tab.CHAPTER && ChapterPanelLayout.elementFieldOf(key) != null
                ? dev.ellipog.armature.client.ui.inspect.InspectLayout.Mode.STACKED
                : dev.ellipog.armature.client.ui.inspect.InspectLayout.Mode.SIDE_BY_SIDE;
    }

    /**
     * Whether the chip the picker is open for is still inside the list it was opened from.
     *
     * <p>A row scrolled out of the panel leaves its chip somewhere off the list, and the picker anchored to
     * it would float over whatever is there instead -- so it closes. The band is that list's own viewport,
     * which is what the chip is drawn inside of, so a chip half scrolled past the edge counts as gone:
     * pressing it is not possible either.
     *
     * <p>The anchor's own list is asked, and a caller looking at another list answers {@code true} for it:
     * the check runs where the dock is drawn, and an element panel's picker is none of the dock's business --
     * which is the bug this signature closes. See {@link PopoverChip}.
     */
    boolean chipOnScreen(dev.ellipog.armature.client.ui.kit.ScrollView view) {
        PopoverChip anchor = popoverChip;
        if (anchor == null || anchor.view() != view || view.layout() == null) {
            return true;
        }
        dev.ellipog.armature.client.ui.kit.Slot slot = view.layout().slot(anchor.row().key());
        if (slot == null) {
            return false;
        }
        BookGeometry.Rect chip = ToolsLayout.chipOf(ToolsLayout.onScreen(view.viewport(), slot),
                anchor.mode());
        dev.ellipog.armature.client.ui.kit.Viewport band = view.viewport();
        return chip.bottom() > band.originY() && chip.y() < band.originY() + band.viewHeight();
    }

    /** A field's label, armed for a scrub: the box is the widget's, the label is the screen's. */
    private boolean armLabelScrub(double mouseX, double mouseY) {
        for (dev.ellipog.tenet.client.dev.ScrubField field : rowFields) {
            if (field.beginLabelScrub(mouseX, mouseY)) {
                labelScrub = field;
                return true;
            }
        }
        return false;
    }

    /**
     * Opens the picker at a chip.
     *
     * <p>The quick picks are the theme's own four surfaces, handed to the picker per open rather than
     * seeded into a list it keeps for the session: a quick pick belongs to the colour being edited, and a
     * list that outlived the picker grew without bound and offered a `+` that silently stopped working
     * once the row was full. See {@code ColourPopover.presets}. The preview and the commit are the same
     * pair every other control uses -- a drag previews, a release writes once.
     *
     * <p>The row is the argument rather than the token it names, because the row is what the picker is
     * <i>anchored</i> to: the token is what it edits, and the anchor needs the row's key and the list it was
     * pressed in to answer "is the chip still drawn" a frame later. See {@link PopoverChip}.
     */
    private void openColourPopover(ToolsLayout.Action row, BookGeometry.Rect anchor) {
        String token = row == null ? null : ToolsLayout.tokenId(row.key());
        if (token == null || token.isEmpty()) {
            return;
        }
        closeColourPopover();
        int argb = questEditor.editedTheme().colour(token);
        popoverChapter = effectiveChapter();
        popoverChip = new PopoverChip(row, toolsView, chipMode(row.key()));
        colourPopover.open(anchor, geometry().canvas(), BookQuestEditor.labelOfToken(token), argb,
                List.of(questEditor.editedTheme().colour("panel"), questEditor.editedTheme().colour("raised"),
                        questEditor.editedTheme().colour("title"), questEditor.editedTheme().colour("accent")),
                value -> previewToken(token, value), value -> questEditor.writeThemeToken(token, value));
        buildPopoverWidgets();
        filePopoverArrival();
        // Everything the picker now covers stops answering presses: see `refreshOverlayMute`.
        refreshOverlayMute();
    }

    /**
     * Registers the picker's four fields with the screen, at the positions it has just placed them.
     *
     * <p>At most once each, however often the build runs: the fields are long-lived instances shared
     * by every open, so adding one that is already a child is a second live registration — and the
     * close below removes only one, which strands the other past the dismiss with the surface gone.
     * Both openers keep the close-first contract and every rebuild clears first, so a second copy
     * should be unreachable; the guard makes it unrepresentable instead of unexpected.
     */
    void buildPopoverWidgets() {
        if (!colourPopover.isOpen()) {
            return;
        }
        for (net.minecraft.client.gui.components.AbstractWidget widget : colourPopover.widgets()) {
            if (!children().contains(widget)) {
                addRenderableWidget(widget);
            }
        }
    }

    /**
     * Makes every control an open overlay covers inert while it is open, and gives back the ones it no
     * longer does.
     *
     * <h2>Why this is not the press order</h2>
     *
     * <p>The colour picker is drawn over the drawer, and the drawer's controls are widgets — so a press
     * that lands on the picker was offered to whatever it covers first, because the widget pass runs before
     * the picker's own hit test. The report was exact: <i>"i can click the reset button when its behind the
     * colour picker popup"</i> — the Revert at the drawer's foot, which the picker's own height puts it
     * over. Reordering the press instead would have broken the picker's fields, which <i>are</i> widgets and
     * need the container's pass to take focus when they are clicked.
     *
     * <p>So this is the rule the modal card already applies, in its own words: <i>"a control the player
     * cannot see is a control they cannot aim at, so a press there is a press on whatever happens to be
     * under the pointer"</i> — the same fault, reported for the header's Close and Party, fixed the same
     * way. The picker's own four fields are exempt because they are what is on screen.
     *
     * <h2>Two policies, and why they are not one</h2>
     *
     * <p>The picker's exemption is <b>everything but its own fields</b>, because that is what its dismissal
     * rule needs: a press anywhere while it is open is either the picker's or the press that closes it, so a
     * control beside it that stayed live would act <i>and</i> leave the picker standing.
     *
     * <p>A page inside a rail is the other case, and there the exemption is only what the page's band
     * covers. A page's way out is a button in its own rail's footer — `Back`, which every card's footer
     * carries — so muting the whole rail to close a hole under the page would take Back with it, and the
     * page would have no way out but Escape.
     *
     * <p><b>And a dropdown is not here at all.</b> A menu or the Import popover has no widgets of its own,
     * so it needs nothing muted: it claims the press outright, one step earlier — see
     * {@link #pressDropdown}. Two mechanisms, one rule: what is drawn on top is what may be aimed at.
     *
     * <h2>Only the ones this muted</h2>
     *
     * <p>The list is what it turned off, not everything it found: a control that was already inert — the
     * toolbar's Undo with nothing to undo, a type a table refuses — must stay inert when the overlay closes,
     * and a blanket "make everything active again" is how a disabled button comes back to life.
     *
     * <p>Re-applied after every rebuild: a rebuild replaces every widget with a fresh, active one, and an
     * open overlay whose fields were rebuilt would otherwise let the next press through again.
     */
    void refreshOverlayMute() {
        // Give back exactly what this took, before deciding what to take now.
        for (net.minecraft.client.gui.components.AbstractWidget widget : mutedBehind) {
            widget.active = true;
        }
        mutedBehind.clear();

        java.util.List<net.minecraft.client.gui.components.AbstractWidget> mine = new ArrayList<>();
        java.util.List<BookGeometry.Rect> bands = new ArrayList<>();
        boolean everythingElse = false;
        if (colourPopover.isOpen()) {
            everythingElse = true;
            mine.addAll(colourPopover.widgets());
        }
        // The three pages, each of which takes the card's body while it is open -- and one band for all of
        // them, because that is what they take: `typePageBand` is this same rectangle.
        boolean pageOpen = typePageOpen() || (pickingItemPath != null && !itemPicker.pickerAsColumn()) || settingsOpen;
        if (pageOpen) {
            bands.add(overlayBodyRect());
        }
        if (typePageOpen() && typeSearch != null) {
            mine.add(typeSearch);
        }
        if (pickingItemPath != null && !itemPicker.pickerAsColumn() && itemSearch != null) {
            mine.add(itemSearch);
        }
        if (settingsOpen) {
            mine.addAll(settingsPageWidgets());
        }
        if (!everythingElse && bands.isEmpty()) {
            return;
        }
        for (net.minecraft.client.gui.components.events.GuiEventListener child : children()) {
            if (!(child instanceof net.minecraft.client.gui.components.AbstractWidget widget)
                    || !widget.active || mine.contains(widget)) {
                continue;
            }
            if (!everythingElse && !coveredByAny(widget, bands)) {
                continue;
            }
            mutedBehind.add(widget);
            widget.active = false;
        }
    }

    /** Whether a widget's own rectangle meets any of the bands an open page has taken. */
    private static boolean coveredByAny(net.minecraft.client.gui.components.AbstractWidget widget,
                                        java.util.List<BookGeometry.Rect> bands) {
        BookGeometry.Rect box = BookGeometry.Rect.at(widget.getX(), widget.getY(), widget.getWidth(),
                widget.getHeight());
        for (BookGeometry.Rect band : bands) {
            if (band.intersects(box)) {
                return true;
            }
        }
        return false;
    }

    /** The widgets the open settings page owns: the fields its own rows placed, and nothing else. */
    private java.util.List<net.minecraft.client.gui.components.AbstractWidget> settingsPageWidgets() {
        java.util.List<net.minecraft.client.gui.components.AbstractWidget> mine = new ArrayList<>();
        for (dev.ellipog.tenet.client.dev.QuestSettingsLayout.Row row : settingsRows) {
            net.minecraft.client.gui.components.AbstractWidget widget = settingsView.get(row.key());
            if (widget != null) {
                mine.add(widget);
            }
        }
        return mine;
    }

    /**
     * A rebuild replaces every control, so the mute has to be re-applied over it.
     *
     * <p>See {@link #refreshOverlayMute}: the overlay's own fields are re-added by the panel's widget pass,
     * so what is left to redo is making everything the overlay covers inert again — and, because a rebuild
     * can also be the thing that *closes* an overlay, giving back whatever the last one took.
     */
    @Override
    public void rebuildWidgets() {
        super.rebuildWidgets();
        // Before the panel passes, because `chapters()` and the sidebar both ask it and both cache their
        // answers against it. See `editingView`.
        bookCanvas.refreshEditingView();
        refreshOverlayMute();
    }

    /**
     * Closes the picker, writing whatever its last gesture left unwritten.
     *
     * <p>Every copy of every field, not one: the add above registers at most one each, and this
     * removes until none is left, so the two are a funnel no dismiss path can leave a field in —
     * click-out, Escape, chip-off-screen, tab switch or rebuild alike. A field left registered with
     * the surface gone is the report this answers: the picker draws nothing and its boxes stay.
     */
    void closeColourPopover() {
        if (!colourPopover.isOpen()) {
            return;
        }
        colourPopover.close();
        for (net.minecraft.client.gui.components.AbstractWidget widget : colourPopover.widgets()) {
            while (children().contains(widget)) {
                removeWidget(widget);
            }
        }
        popoverOpenedAt = -1L;
        popoverBaseX.clear();
        // The controls it was covering are controls again.
        refreshOverlayMute();
    }

    /** The list layout the open tab is showing: the book's rows, or the chapter's combined list. */
    Layout activeLayout() {
        return toolsTab == ToolsLayout.Tab.CHAPTER ? chapterLayout : toolsLayout;
    }

    /**
     * The rows that layout was built from: the book tab's list, or the chapter's appearance rows.
     *
     * <p>Beside {@link #activeLayout} because the two are one answer -- a layout is built from a list and
     * a control is found by walking the list it came from ({@link ToolsLayout#controlSlot}), so anywhere
     * that has one needs the other.
     */
    List<ToolsLayout.Action> activeRows() {
        if (toolsTab != ToolsLayout.Tab.CHAPTER) {
            return toolsRows;
        }
        // **Both halves of the chapter tab**, not just the appearance rows: the chapter's own rows are
        // drawn by the same dispatch now, so the tooltip lookup, the chooser's slot lookup and the chips'
        // walk all read one list -- and the layout they are asking is `chapterLayout`, which holds exactly
        // this pair in this order.
        if (chapterRows.isEmpty()) {
            return chapterAppearanceRows;
        }
        List<ToolsLayout.Action> both = new ArrayList<>(chapterRows.size() + chapterAppearanceRows.size());
        both.addAll(chapterRows);
        both.addAll(chapterAppearanceRows);
        return List.copyOf(both);
    }

    /**
     * Whether the open tab shows appearance controls at all.
     *
     * <p>The book tab always does. The chapter tab's section is the author's -- it writes the chapter
     * file -- so it is offered only where there is a chapter and this client may edit it, and a reader's
     * chapter tab keeps the content-only layout it has always had.
     */
    boolean toolsAppearanceVisible() {
        return toolsTab == ToolsLayout.Tab.BOOK || (mayEditNow() && effectiveChapter() != null);
    }

    // ------------------------------------------------------------------
    // The quest panel's edits
    // ------------------------------------------------------------------

    /**
     * The selected quest's own tree, from the replica, or null.
     *
     * <p>The one read every quest-panel handler makes, and null is the answer for all three ways there
     * can be nothing: no chapter on screen, nothing selected, or the copy not arrived yet. Every caller
     * treats it as "nothing to edit here" rather than as an error.
     */
    JsonObject replicaQuest() {
        String chapter = effectiveChapter();
        String target = editTarget();
        if (chapter == null || target == null) {
            return null;
        }
        // The draft applied at the paths the ops write, and that is the whole of the card's optimism:
        // every read below goes through here, so a stepper shows what it asked for before the replica
        // catches up. It has to be an overlay rather than a check per read -- the draft is keyed by the
        // op's path (`tasks.0.count`) while the drawing reads a field's path relative to its entry
        // (`count`), and the first version's per-read check therefore never matched a task or reward.
        return fieldDraft.overlaid(chapter, target, ClientChapterReplica.quest(chapter, target));
    }

    /**
     * A quest's prerequisites, with a pending dependency edit winning over the tree's list.
     *
     * <p>The canvas reads the *tree*, not the replica, so a dependency edit needs this read to be
     * optimistic: the edge is drawn from the list this returns, which is why adding or removing a
     * prerequisite shows on the press rather than on the tree that follows it. Static, and keyed by the
     * entry's own chapter, because the canvas and the delete notes ask from static helpers.
     */
    static List<String> dependenciesOf(ClientQuestCache.Entry entry) {
        return fieldDraft.strings(entry.chapterId(), entry.id(), "dependsOn", entry.dependencies());
    }

    /** A quest's title, with a committed rename winning over the tree the sidebar draws. */
    static String titleOf(ClientQuestCache.Entry entry) {
        return fieldDraft.text(entry.chapterId(), entry.id(), "title", entry.titleText());
    }

    /**
     * A quest's title with its legacy tokens still in it, for the surfaces that wear colours.
     *
     * <p>The same rename-winning read as {@link #titleOf} — the same words, agreed by construction —
     * and only the ink differs. Measuring still takes the stripped reading, because colours are
     * widthless and the two must agree about how wide the words are.
     */
    static String titleOfRaw(ClientQuestCache.Entry entry) {
        return fieldDraft.text(entry.chapterId(), entry.id(), "title", entry.titleTextRaw());
    }

    /**
     * The quest the editor's commits are about: the modal's quest while its editor is open, else the
     * dock's selection.
     *
     * <p>One method, so the dock panel's handlers and the modal's cannot disagree about who a field
     * edit is for -- the two surfaces share every commit path below.
     */
    String editTarget() {
        return overlay == PanelKind.QUEST && overlayQuest != null ? overlayQuest : selectedQuest;
    }

    static boolean flagOn(JsonObject quest, String path) {
        JsonElement current = quest == null ? null : QuestPanelLayout.get(quest, path);
        return current != null && current.isJsonPrimitive()
                && current.getAsJsonPrimitive().isBoolean() && current.getAsBoolean();
    }

    /**
     * Leaves whichever page is over the card: the table's type pick closes back into the editor, and the
     * card's own pages are left by {@link BookRewards#leaveCardPage}.
     *
     * <p>One method for the three ways out of a page -- Escape, the footer's Back and a press outside --
     * so the page cannot be left one way by the key and another by the button.
     */
    void leaveTypePage() {
        if (typePickReturn) {
            questEditor.closeTypePick();
        }
        else {
            bookRewards.leaveCardPage();
        }
    }


    /**
     * Adds one dependency, by the id typed into the Add row.
     *
     * <p>The two refusals the server would make anyway are made here first, because a refusal that
     * arrives after a round trip reads as a delay rather than as an answer: a duplicate is already in
     * the list, and a quest cannot depend on itself. Everything else -- a mistyped id, a cycle, a quest
     * in another chapter -- is the loader's validator, and its message is the one the reply carries.
     */
    /**
     * Opens a description's link in the platform's browser -- <b>http and https only</b>.
     *
     * <p>A description is content somebody else may have written -- a published questline -- so the scheme
     * is checked here rather than trusted: a {@code file:} target or a made-up scheme is refused with a word
     * in the status line, never opened and never silently ignored.
     */
    void openLink(String url) {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url);
        }
        catch (IllegalArgumentException malformed) {
            status("tenet.status.that_link_is_not_a_valid_address", true);
            return;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            status("tenet.status.only_http_and_https_links_open", true);
            return;
        }
        // The opener moved with the platform code: `Util.OS` is an enum again, and links open
        // through Blaze3D.
        com.mojang.blaze3d.Blaze3D.openUri(uri);
    }

    /**
     * Arms the dependency pick: the next canvas click names the quest to depend on, Escape cancels.
     *
     * <p>The card closes with it, because the pick needs the canvas the card covers -- the one gesture the
     * modal cannot host -- so <b>what the pick is for is captured here</b>: the edited quest, the chapter
     * it lives in, and the prerequisites it already has. Reading those at the click would read them after
     * the card that knew them is gone.
     *
     * <p>The author may switch chapter or group between arming and picking, and that is the point rather
     * than a tolerated side effect: a prerequisite may live anywhere in the tree, ids resolve across the
     * whole file, and the operation is sent to the chapter that was being *edited* however far the sidebar
     * has moved since. See {@code addPickedDependency}.
     */
    void armDependencyPick() {
        String quest = editTarget();
        if (!mayEditNow() || quest == null) {
            status("tenet.status.open_a_quest_to_add_a_prerequisite_to", true);
            return;
        }
        pendingPick = new dev.ellipog.tenet.client.dev.DependencyPick(quest, effectiveChapter(),
                QuestPanelLayout.strings(replicaQuest(), "dependsOn"));
        pickingDependency = true;
        bookCanvas.pendingLinkPick = null;
        closeOverlay();
        status("Click the quest to depend on \u2014 any chapter or group \u2014 Escape cancels", false);
    }

    static JsonElement stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    /**
     * The texture row's commit: the id canonicalised and looked up before anything is written.
     *
     * <h2>Why the file is checked here, and why the check can only be a courtesy</h2>
     *
     * <p>An id that resolves to nothing draws nothing -- the painter returns on an unresolvable
     * texture -- so writing one would be an edit whose only visible effect is a canvas that quietly
     * stopped being tiled. The resource manager is the client's own copy of the pack, so the check is
     * exactly as current as the pack the author is looking at, and a path that passes it may still be
     * absent on another player's client. What it catches is the typo, which is the common case; what it
     * cannot catch is a pack that disagrees with itself.
     *
     * <p>The status names the <b>resolved</b> path rather than what was typed, because the two differ
     * whenever the input was lowercase-folded, namespace-defaulted or carried a leading
     * {@code textures/} -- and the author needs to see the id the file will actually carry.
     *
     * <p>The boolean answer exists for the picker: the field's own submit ignores it and shows the
     * status line instead, while a pressed row has to know whether it may close -- a press that failed
     * the file check keeps the list open with the refusal beside it, rather than closing over the
     * reason.
     *
     * @return true when the texture was written, false when the id did not resolve to a file
     */
    boolean commitCanvasTexture(String typed) {
        String raw = typed == null ? "" : typed.trim();
        String canonical = CanvasBackground.canonicalTexture(raw);
        if (canonical.isEmpty()) {
            status("\"" + raw + "\" is not a texture id - try mymod:textures/gui/bg.png", true);
            return false;
        }
        Identifier id = Identifier.tryParse(canonical);
        if (id == null || minecraft == null || minecraft.getResourceManager() == null
                || minecraft.getResourceManager().getResource(id).isEmpty()) {
            status("No texture at " + canonical, true);
            return false;
        }
        if (texturePickElement != null) {
            // An element's picture: the whole image object, because the two arms are exclusive and choosing a
            // file is what clears the sprite. Drafted first, like every other element edit, so the canvas shows
            // the new file at once rather than a round trip later.
            JsonObject source = new JsonObject();
            source.addProperty("texture", canonical);
            questEditor.draftElementField(texturePickElement, "image", source);
            send(new EditorOp.SetElement(texturePickElement, "image", source));
            status("Picture file " + canonical, false);
            rebuildWidgets();
            return true;
        }
        CanvasBackground current = editedBackground();
        questEditor.writeThemeBackground(new CanvasBackground(current.kind(), current.space(), current.spacing(),
                current.tuning(),
                new CanvasBackground.Image(canonical, current.image().fit(),
                        current.image().tileSize())));
        status("Canvas texture " + canonical, false);
        rebuildWidgets();
        return true;
    }

    /** The canvas background in force for the panel's target. */
    CanvasBackground editedBackground() {
        return themeTargetsChapter ? questEditor.editedTheme().background() : ClientAppearance.LOOK.background();
    }

    /**
     * The toast, and the chat, because a toast can be missed and a panel can cover it.
     *
     * <p>This used to write the drawer's status line as well. That line is gone: it was a third copy of
     * the same sentence, in a band that cost every author twelve pixels of list whether or not anything
     * had happened.
     */
    void status(String message, boolean error) {
        // A key for everything the sweep has converted, and a plain sentence for the composed ones:
        // `translatable` passes an unknown key through unchanged, so both kinds go through one door and
        // a translator sees one namespace.
        //
        // Through the author's door, because every caller is an editor affordance: a canvas pattern, a
        // motion switch, a task added, a dependency picked, a table row. A player who may not edit cannot
        // reach any of them, and the gate is what makes that true of the message as well as the control.
        bookInput.authorToast(Component.translatable(message).getString(), error);
    }

    @Override
    protected void init() {
        clearFrameSnapshot();
        // First, because the panels built below ask what this viewer may see: a chapter withheld until
        // its gate is met is a row to an author and not a row to a reader, and the static helpers that
        // decide it read this flag. `rebuildWidgets` re-reads it too. See `editingView`.
        bookCanvas.refreshEditingView();
        // A command can ask for a table's editor -- `/tenet table edit` -- and the request waits in
        // `ClientTableOpen` until there is a screen to open it on. Taken here rather than in the payload
        // handler because the handler cannot assume this screen exists, and read before the dispatch
        // below because opening the editor changes which branch builds the widgets.
        String requestedTable = dev.ellipog.tenet.client.ClientTableOpen.take();
        if (requestedTable != null) {
            tableAddress = dev.ellipog.tenet.editor.TableAddress.of(requestedTable);
            tableStack.clear();
            tableApplied = 0;
            tableUndone = 0;
            tableFolded.clear();
            tableShowRoll = false;
            tableImportOpen = false;
            tablePreview = dev.ellipog.tenet.quest.reward.TableReward.Mode.RANDOM;
            applyColumns(PanelStack.asRoot(columns(), PanelKind.TABLE_EDITOR));
        }
        // Which dock tab the author was reading, from the file that remembers it. Seeded here rather than
        // as a field initialiser because the file is read at client start, and a screen built before that
        // read would otherwise decide the tab from a default it then keeps for the session.
        toolsTab = dev.ellipog.tenet.client.ClientWorking.CHAPTER_TAB.equals(
                dev.ellipog.tenet.client.ClientWorking.drawerTab())
                ? ToolsLayout.Tab.CHAPTER : ToolsLayout.Tab.BOOK;
        // And what the player asked the second column to do, from the file that remembers it — the same
        // reason and the same place: it is a preference about this client, read before anything can fold.
        foldChoice = DevMode.panelFold();
        // First, because everything below is built from the caches it names -- and the frame that
        // must notice them moving is the one after this returns. See `watchSources`.
        watchSources();
        clearWidgets();
        buttons.clear();
        panelOverlays.clear();
        // And the slide's filing: the widgets are gone with the clear above, so their bases go too —
        // a rebuilt rail re-files as it builds, and a base left behind would slide a control that no
        // longer belongs to the kind it names.
        slideWidgets.clear();
        slideBaseX.clear();
        sidebarTravelBase.clear();
        // And the departed rows — unless a collapse is riding out, in which case the rebuild about
        // to happen builds the sliver behind them and the orphans keep riding: dropping them here
        // would vanish the list mid-slide, which is the pop they exist to prevent. Every arrival
        // (expand, peek, float, dock) clears `sidebarDeparting` first, so keeping them here only
        // ever preserves a departure still in flight.
        if (!sidebarDeparting) {
            sidebarDeparted.clear();
            sidebarDepartAnchor = null;
        }
        // Nulled with the list it came from. `init` rebuilds Close only on the branch that builds the
        // book, so leaving this set would have `render` draw a control the screen no longer owns — see
        // the guard at the drawing site for what that looks like.
        closeButton = null;
        partyButton = null;
        rewardsButton = null;
        // Cleared with them: the rows are drawn from these lists, so a rebuild that left them alone
        // would draw the previous panel's rows over the new one. The card and the layouts go with them,
        // because one pass records them together and a card left behind would draw a box for a panel
        // that no longer exists.
        //
        // Unless the party is sinking out rather than gone: its exit draws from these same faces for the
        // length of its slide, and the close rebuild is what would take them. Skipped while departing —
        // an open panel rebuilds them below, a settled close has no exit reading them, and a reopen
        // recomputes before anything draws.
        if (!panelDeparting(PanelKind.PARTY, net.minecraft.util.Util.getMillis())) {
            partyLeftLines = new ArrayList<>();
            partyRightLines = new ArrayList<>();
            partyLeftFace = null;
            partyRightFace = null;
            partyCard = null;
            partyLeftLayout = null;
            partyRightLayout = null;
        }
        // And the scroll views' widgets, for the reason the three above are cleared: they belong to
        // the panel being replaced. `buildPartyWidgets` clears them too, but only on the branch that
        // builds a panel -- a rebuild for a quest would otherwise leave the views holding controls
        // that are no longer on the screen.
        partyLeftView.clear();
        partyRightView.clear();
        // And the armed Disband, because a rebuild is a new panel: the row a player armed may not be
        // there any more, and a control that says "Confirm" for a press it no longer remembers is
        // worse than one that forgot.
        disbandButton = null;
        disbandPress.disarm();
        // And the armed canvas copy, for the same reason -- with one exception: the arming press's own
        // rebuild sets `canvasCopyArming`, because a rebuild is what puts the armed label on the row.
        if (!canvasCopyArming) {
            canvasCopyArmed = false;
        }
        canvasCopyButton = null;
        partyNameField = null;
        partyCreateField = null;
        partySearchField = null;
        partyControls.clear();
        // `partyRenaming` is deliberately NOT reset here, and that is a fix for a report: the name's
        // own press sets the flag and then rebuilds, so a reset in this path cancelled the edit on the
        // same click -- "edit party name doesn't do anything". A panel opened afresh still resets it,
        // in `openPartyOverlay`; a rebuild keeps whatever interaction was in progress.

        // No theme is applied here, and there used to be one call. A chapter's palette is now a scope
        // opened and closed within a single frame -- see `drawCanvas` and `renderWith` -- so there is
        // nothing to establish before the controls are built, and the label below reads from
        // `Appearance`, which no chapter can influence.
        //
        // That is a real simplification rather than a relocation. The sequence here used to matter: the
        // theme had to be applied before the controls were made, because each control reads its colours
        // when it is constructed. With the two palettes separated by region, a control reads the chrome
        // and a canvas reads the chapter, and neither has an order dependency on the other.

        // A recipe viewer that asked for the book opens it on its quest: the request is consumed here,
        // before the branches below build for whatever overlay it names, so the card's own build path
        // is the one that runs -- exactly as if the player had clicked the node. A request for a quest
        // the tree no longer holds -- a viewer's click that outlived a reload -- is dropped rather than
        // opening a card for nothing.
        QuestBookFocus.consume().ifPresent(questId -> {
            ClientQuestCache.Entry entry = cacheEntryFor(questId);
            if (entry != null) {
                bookSidebar.selectChapter(entry.chapterId());
                selectedQuest = questId;
                centred = true;
                // A node click, wherever it came from: the quest takes the first column and the second is
                // emptied, because a child belonged to the quest that is no longer showing.
                applyColumns(PanelStack.afterNodeClick(columns()));
                overlayQuest = questId;
                // A card opened from outside the book is a fresh session, not a continuation of one:
                // there is no "where I came from" for the arrow to return to.
                overlayHistory.clear();
            }
        });

        // A `/tenet preview` ask parked by the payload handler: the command runs on the server and
        // the latch lives here, so the handler parks and this consumes. Taken before the widgets build,
        // because the geometry, the band and the editing gate all read the latch. A toggle parked while
        // the book was closed was already resolved to "on" by `requestPreview`, so this sets absolutely.
        // A non-operator keeps the editor's answer: the latch is only meaningful with the permission
        // that `previewing()` already guards, and `refreshEditingView` clears it on de-op.
        consumePreviewRequest().ifPresent(ask -> {
            previewAsPlayer = ask;
            // A command-entered preview shows no exit pill (see the field): the ask carries
            // the way in, so the pill would be operator chrome over a player's view.
            previewFromCommand = ask;
            if (ask) {
                bookMenu.closeMenu();
                closeColourPopover();
                applyColumns(PanelStack.afterClose(columns(), false));
            }
        });

        // **One path, because there is one presentation.** This was a ladder of eleven branches -- one per
        // kind, plus the book's own -- and every one of them built the same three things in the same order
        // and then returned, with the differences being exactly the faults that had accumulated: the
        // branches that built the header and the cluster and stopped left Close missing behind a dialog,
        // the ones that forgot the view cluster left three map buttons out, and the five that skipped
        // `buildColumns` gave a child column with no search box, no Back and no fold control.
        //
        // With the card gone there is nothing left for a branch to decide. The book's chrome is built for
        // every arrangement (it is drawn behind *and beside* a panel, so a sidebar left unbuilt would be a
        // hole in the picture), the presented columns are built from `PanelStack.presented` alone, and
        // nothing is made inert -- a column covers nothing the reader was using, which is the whole reason
        // there is one presentation now.
        questEditor.buildSidebarWidgets();
        questEditor.buildHeaderChrome();
        questEditor.buildViewCluster();

        // The columns that are on screen, and only those: the dock when it is latched, the panel, and the
        // child it opened when the pair is not folded. One call, driven by the rules, so a rail that is not
        // drawn cannot have a control registered behind the one that is.
        questEditor.buildColumns();

        // The choice panel's rows are a page of its own rather than a list a kind owns -- see
        // `buildChoiceWidgets` -- so it is the one kind with a build step of its own. Every other kind's
        // controls come from `buildColumn`, which is what makes "a column is built from what it holds"
        // true for all of them.
        if (overlay == PanelKind.CHOICE) {
            PanelKind outer = slideBucket;
            slideBucket = PanelKind.CHOICE;
            try {
                bookOverlay.buildChoiceWidgets();
            }
            finally {
                slideBucket = outer;
            }
        }
        return;
    }

    /**
     * Whether this player may edit the questline.
     *
     * <p>Permission level two: the level `/tenet reload` declares, read here through the player rather
     * than through a command source. The two cannot drift because there is one number and this comment
     * names it -- and the day the rule changes, the command is where it should change.
     */
    boolean mayEdit() {
        if (frameActive) {
            return frameMayEdit;
        }
        return dev.ellipog.tenet.QuestAuthority.mayEditClient(minecraft);
    }

    /**
     * Where the open panel's footer controls go.
     *
     * <p>One wrapper rather than a {@link #surfaceCard} call at each of the seven sites that ask, because
     * they all ask the same question — "where do this panel's buttons go" — and the answer has to be the
     * same for every one of them or a panel's footer would be placed from two different rectangles.
     */
    Map<String, BookGeometry.Rect> overlayControls(boolean hasSubmit) {
        return geometry().questFooter(surfaceCard(surfaceKind), hasSubmit);
    }

    /**
     * The item picker's search box: the one widget it has, because every row is drawn and hit-tested by
     * the same derivation the list class owns.
     *
     * <h2>One method, two ways in</h2>
     *
     * <p>The picker opens as a page of the quest card and, since the Chapter tab's icon became pickable,
     * as an overlay of its own -- see {@link PanelKind#PICKER}. Both need this box built identically, and
     * the alternative to extracting it was a second copy of the placeholder, the clip and the redraw
     * ordering below, each of which is a thing that only looks right when it is the same in both.
     *
     * <p>Rebuilt with the value it already held: a tick rebuild (a replica arriving mid-search) must not
     * clear what is being typed.
     */
    /**
     * The pickers' search boxes: the item picker's while it is over the card, and the type page's.
     *
     * <p>They are the same control in the same place of the same shape of page, so they are built by one
     * method — and rebuilt with the text they already held, because a tick rebuild (a replica arriving
     * mid-search) must not clear what is being typed.
     */
    void buildPickerWidgets() {
        questEditor.buildItemSearch();
        questEditor.buildTypeSearch();
    }

    /** The card's body as a rectangle, for the layouts that take one. */
    BookGeometry.Rect overlayBodyRect() {
        return BookGeometry.Rect.at(overlayBody().originX(), overlayBody().originY(),
                overlayBody().viewWidth(), overlayBody().viewHeight());
    }

    /**
     * The same, without the mark: for a piece whose mark is drawn somewhere the press is not -- the
     * description's frame, which is drawn outside the content's clip. Returns whether anything was
     * registered, so a caller drawing the mark can skip a box with no size.
     */
    boolean registerTarget(EditAction action, String path, BookGeometry.Rect box, int textX,
                                   int textY, String value, String member, int index) {
        return registerInto(editTargets, action, path, box, textX, textY, value, member, index);
    }

    /**
     * The same, into the list the caller names.
     *
     * <p>The list is a parameter because a panel has its own: the Assets panel draws its chrome and its
     * footer with the helpers below, presses from {@link #assetsTargets}, and never reads
     * {@link #editTargets} while it is open. A helper that decides the destination for its caller made
     * New table, Done and the card's cross unreachable -- drawn as controls, registered in a list the
     * panel that presses them does not walk. See {@code drawAssets} for the other half.
     *
     * <p>The box and its text origin arrive shifted for the slide: a press must meet rows where they
     * are drawn rather than where their layout says, for the tenth of a second a rail is travelling.
     * See {@link #shiftHit}.
     */
    boolean registerInto(List<EditTarget> into, EditAction action, String path,
                                 BookGeometry.Rect box, int textX, int textY, String value,
                                 String member, int index) {
        if (box.width() <= 0 || box.height() <= 0) {
            return false;
        }
        BookGeometry.Rect hit = shiftHit(box);
        into.add(new EditTarget(action, path, hit, textX + hit.x() - box.x(), textY, value, member,
                index));
        return true;
    }

    /**
     * A hit rectangle moved with its rail's slide, for this frame.
     *
     * <p>Every press target is registered afresh each frame (the lists are rebuilt by the drawing), so
     * the frame's own offset is the right one: a press between frames meets positions at most a frame
     * stale, which is the same staleness every control in the game already has. Zero unless the rail
     * this draw belongs to is travelling — read off {@link #surfaceKind}, which the surface sets before
     * anything inside it registers, so a caller outside a surface can never borrow the wrong rail's
     * motion. Hover washes and scrollbar thumbs are deliberately not shifted: a highlight may lag the
     * slide by a frame, but a press must not land anywhere but on what is drawn.
     */
    BookGeometry.Rect shiftHit(BookGeometry.Rect box) {
        if (box == null || surfaceKind == null || surfaceKind == PanelKind.NONE) {
            return box;
        }
        int offset = PanelLayout.slideOffset(panelRail(surfaceKind),
                panelRevealElapsed(surfaceKind, net.minecraft.util.Util.getMillis()), panelRevealMillis());
        if (offset == 0) {
            return box;
        }
        return BookGeometry.Rect.at(box.x() + offset, box.y(), box.width(), box.height());
    }

    /**
     * The mark on something editable: a faint box at rest, brighter under the pointer.
     *
     * <p>The playtest's answer for how an author finds the editable parts: discoverable without
     * hovering, quiet enough that the card still reads as the preview it is. Four one-pixel lines, so
     * the text inside is never dimmed by its own affordance.
     */
    static void drawEditAffordance(GuiRenderer r, BookGeometry.Rect box, boolean hot) {
        int colour = hot
                ? Colour.alphaOf(ArmatureTheme.title(), 0.55F)
                : Colour.alphaOf(ArmatureTheme.faint(), 0.30F);
        r.fill(box.x(), box.y(), box.right(), box.y() + 1, colour);
        r.fill(box.x(), box.bottom() - 1, box.right(), box.bottom(), colour);
        r.fill(box.x(), box.y() + 1, box.x() + 1, box.bottom() - 1, colour);
        r.fill(box.right() - 1, box.y() + 1, box.right(), box.bottom() - 1, colour);
    }

    /**
     * A value box: a faint frame, its text, and a right-aligned tail for a unit or a mark.
     *
     * <p>{@code replaced} is the inline editor's contract: the field drawing over this box draws the value,
     * and two drawings of one value is the fault that contract exists to prevent. The frame stays -- an
     * empty box where a value was reads as the row having lost it.
     */
    void drawValue(GuiRenderer r, BookGeometry.Rect box, String text, String tail, boolean faint,
                           boolean replaced, int mouseX, int mouseY) {
        drawEditAffordance(r, box, box.contains(mouseX, mouseY));
        int baseline = box.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2;
        int room = box.width() - 8 - (tail.isEmpty() ? 0 : r.textWidth(tail) + 4);
        if (!text.isEmpty() && !replaced) {
            r.text(Measure.truncate(text, Math.max(0, room), textMeasure(r)), box.x() + 4, baseline,
                    faint ? ArmatureTheme.faint() : ArmatureTheme.body());
        }
        if (!tail.isEmpty()) {
            r.text(tail, box.right() - 4 - r.textWidth(tail), baseline, ArmatureTheme.faint());
        }
    }

    /** The chip a stepper's `-` and `+` are drawn as. */
    void drawChip(GuiRenderer r, BookGeometry.Rect box, String glyph, boolean hot) {
        drawEditAffordance(r, box, hot);
        r.text(glyph, box.x() + (box.width() - r.textWidth(glyph)) / 2,
                box.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2,
                hot ? ArmatureTheme.title() : ArmatureTheme.body());
    }

    /** The switch a flag is drawn as: filled when on, empty when off. */
    void drawSwitch(GuiRenderer r, BookGeometry.Rect cell, boolean on) {
        int size = EntryFormLayout.BUTTON;
        BookGeometry.Rect chip = BookGeometry.Rect.at(cell.x(), cell.y() + (EntryFormLayout.LINE_HEIGHT - size) / 2,
                size, size);
        int edge = on ? ArmatureTheme.title() : ArmatureTheme.faint();
        r.fill(chip.x(), chip.y(), chip.right(), chip.y() + 1, edge);
        r.fill(chip.x(), chip.bottom() - 1, chip.right(), chip.bottom(), edge);
        r.fill(chip.x(), chip.y() + 1, chip.x() + 1, chip.bottom() - 1, edge);
        r.fill(chip.right() - 1, chip.y() + 1, chip.right(), chip.bottom() - 1, edge);
        if (on) {
            // A filled middle rather than a tick: the dot matrix has no check mark -- see the glyph list
            // `BookGeometry.TOOLS_PILL_WIDTH` records, where `\u2713` is absent.
            r.fill(chip.x() + 3, chip.y() + 3, chip.right() - 3, chip.bottom() - 3, ArmatureTheme.title());
        }
    }

    /** The mark that says a box cycles when pressed. `\u25bc` is a glyph the default font carries. */
    void drawOptionsMark(GuiRenderer r, BookGeometry.Rect box) {
        String mark = "\u25bc";
        r.text(mark, box.right() - 4 - r.textWidth(mark),
                box.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2, ArmatureTheme.faint());
    }

    /**
     * An item field's box: the icon, the item's own name, and -- on hover -- the id a file spells.
     *
     * <p>The name rather than the id, which is what the fields beside it do: a search field shows "Plains"
     * and a tag shows "Ores", so an item field showing {@code minecraft:cobblestone} made one form speak
     * two vocabularies. The id is a hover away, which is where it belongs -- and it is what a missing item
     * still shows, because there is no name to show for one.
     */
    int drawItemValue(GuiRenderer r, BookGeometry.Rect box, String value, boolean replaced,
                              int mouseX, int mouseY) {
        int boxSize = Math.max(8, box.height() - 2);
        ItemStack stack = itemStack(value);
        boolean missing = !value.isEmpty() && stack.isEmpty();
        if (missing) {
            drawItemPlaceholder(r, box.x() + 1, box.y() + 1, boxSize);
        }
        else {
            r.icon(stack, box.x() + 1, box.y() + 1, boxSize);
        }
        int baseline = box.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2;
        int textX = box.x() + box.height() + 2;
        int room = Math.max(0, box.right() - 4 - textX - (missing ? r.textWidth("missing") + 4 : 0));
        if (replaced) {
            // The open field draws the words; the icon stays, because a blank where the item was would
            // read as the row having lost it.
            return textX;
        }
        if (missing) {
            r.text(Measure.truncate(value, room, textMeasure(r)), textX, baseline, ArmatureTheme.blocked());
            r.text("missing", box.right() - 4 - r.textWidth("missing"), baseline, ArmatureTheme.blocked());
            if (box.contains(mouseX, mouseY)) {
                pendingLabels.add(new PendingLabel(box,
                        "missing - the mod is not installed; the id is kept"));
            }
            return textX;
        }
        String shown = stack.isEmpty() ? "pick an item" : stack.getHoverName().getString();
        r.text(Measure.truncate(shown, room, textMeasure(r)), textX, baseline,
                stack.isEmpty() ? ArmatureTheme.faint() : ArmatureTheme.body());
        // The id, on hover: the badge says "Item" and the box says "Cobblestone", and the spelling a file
        // and an error message use is the one thing neither of them says.
        if (!value.isEmpty() && box.contains(mouseX, mouseY)) {
            pendingLabels.add(new PendingLabel(box, value));
        }
        return textX;
    }

    /** A field's value as stored, for a form control: empty when it is absent or not a primitive. */
    static String rawValue(JsonObject entry, String path) {
        JsonElement found = QuestPanelLayout.get(entry, path);
        return found == null || !found.isJsonPrimitive() ? "" : found.getAsString();
    }

    /** A reward row's sentence; the task row's rule, one member over. */
    static String rowText(String member, ClientQuestCache.RewardEntry reward) {
        // A table that asks for its title names the row: the legacy `use_title`. The reward's own
        // sentence is the generic roll ("Roll the X table"); the table's title is what the author
        // wants read instead. Only for a named table whose summary says so — an inline table has no
        // summary, and a table that says nothing keeps the generic sentence a version-16 client drew.
        ClientQuestCache.TableSummary table = BookQuestEditor.tableOf(reward);
        if (table != null && table.useTitle()) {
            return table.titleText();
        }
        String arg = reward.labelArg();
        return arg.isEmpty()
                ? reward.text().getString()
                : reward.text(BookQuestEditor.prettyArg(member, reward.type(), arg)).getString();
    }

    // ------------------------------------------------------------------
    // What the form's controls do
    // ------------------------------------------------------------------

    /** Whether a type page -- a list of tasks, rewards or conditions -- is over the card. */
    boolean typePageOpen() {
        return pickingEntryType != null || pickingConditionFor != null;
    }

    static ItemStack itemStack(String id) {
        net.minecraft.resources.Identifier location =
                net.minecraft.resources.Identifier.tryParse(id);
        return location == null ? ItemStack.EMPTY : new dev.ellipog.tenet.quest.ItemRef(location, 1).toStack();
    }

    // ------------------------------------------------------------------
    // The editor's presses
    // ------------------------------------------------------------------

    void closeInlineEditor() {
        // References first: removing a focused widget blurs it, and a blur submits -- the field's own
        // re-entrancy guard catches that, but the state should not outlive the widget for a moment
        // longer than it has to either.
        ArmatureTextField field = inlineField;
        ArmatureTextArea area = inlineArea;
        inlineField = null;
        inlineArea = null;
        editingPath = null;
        if (field != null) {
            removeWidget(field);
        }
        if (area != null) {
            removeWidget(area);
        }
    }

    // ------------------------------------------------------------------
    // The item picker
    // ------------------------------------------------------------------

    void openItemPicker(EditTarget target, boolean returnToSettings) {
        closeInlineEditor();
        pickingEntryType = null;
        settingsOpen = false;
        pickerFromSettings = returnToSettings;
        // The card's picker, named as such: the dock's two icons are the other targets, and a stale one
        // left set would route this pick to a chapter's or a group's file. The reset comes first, so the
        // title can never be a sentence the last pick left behind.
        forgetPickSubject();
        pickTarget = PickTarget.QUEST;
        draggingSlider = null;
        settingsDraft.clear();
        pickingItemPath = target.path();
        JsonObject quest = replicaQuest();
        JsonElement current = quest == null ? null : QuestPanelLayout.get(quest, target.path());
        pickingItemCurrent = current != null && current.isJsonPrimitive() ? current.getAsString() : "";
        pickingItemClearPath = ItemPicker.clearPath(target.path());
        // The item catalogue, and this pick is items: a search left set from the last one would list
        // biomes for a field that wants an item.
        pickerSource = null;
        pickerObserveType = "";
        pickerEntries = BookItemPicker.catalogue();
        pickerInventory = BookItemPicker.carried();
        pickerMatches = List.of();
        pickerRows = List.of();
        pickerFrame = null;
        pickerQuery = "";
        // An empty box, for the same reason the search picker gets one: the last field's query must not
        // filter this field's list.
        itemSearch = null;
        pickerSelected = -1;
        pickerBody.setScrollY(0);
        // Items first, every time: the tabs greet an icon pick on the arm every icon picker before
        // this one chose, and a file tab left over would list textures for an item field.
        pickerKind = PickerKind.ITEM;
        itemPicker.placeArmedPick();
        rebuildWidgets();
    }

    /** Closes it without committing, and forgets its list: the next open gathers a fresh one. */
    void closeItemPicker() {
        // **Its own column goes first, when it had one.** Four callers arrive here -- the Back control,
        // Escape, a committed pick and a panel leaving -- and the column a picker occupied is part of the
        // arrangement, so the arrangement is what has to lose it. It used to be nobody's job: this method
        // reset the picker's own fields, `closePanelColumn` returned early for a picker, and the second
        // column stayed on screen through every way out of it. `overlay` is deliberately untouched by the
        // peel, because the two tests below are about which overlay owns the picker.
        //
        // The path goes now rather than with the exit below: `buildItemSearch` reads it to decide whether
        // the quest card owns a search box, and a rebuild between the close and the settle would resurrect
        // the box — and its placeholder — from the retained path, then watch the settle null the field out
        // from under the placeholder. That draw is the crash, not a glitch. Everything the sinking surface
        // itself reads stays for the slide; the path is the one thing a build also reads.
        pickingItemPath = null;
        if (overlay2 == PanelKind.PICKER) {
            applyColumns(PanelStack.afterClose(columns(), true));
        }
        // Back to the page it was opened from, before anything else looks at the state: the picker is
        // closed either way, and an author who pressed Change… on the settings page belongs there
        // afterwards rather than in the card's editor.
        if (pickerFromSettings) {
            pickerFromSettings = false;
            settingsOpen = true;
        }
        // The dock's picker has no card behind it to return to: its overlay *is* the picker, so closing
        // it puts the book back. Read before the target is cleared, because only a dock pick owns the
        // overlay -- a pick from the card closes back into the card.
        boolean ownOverlay = pickTarget != null && pickTarget != PickTarget.QUEST && overlay == PanelKind.PICKER;
        // A pick made from the table editor closes back into the editor rather than into the card: the
        // picker is a page of *that* panel while it is open, and the panel is where the author was.
        if (tablePickReturn && overlay == PanelKind.PICKER) {
            tablePickReturn = false;
            applyColumns(PanelStack.asRoot(columns(), PanelKind.TABLE_EDITOR));
        }
        if (ownOverlay) {
            // The dock picker's own overlay goes with it: arranged before the teardown is scheduled, so
            // the exit below is keyed under the rail that actually left rather than a stale set.
            applyColumns(PanelStack.afterClose(columns(), false));
        }
        // The picker's own state is what its exit draws from — the rows, the frame, the box — so it is
        // owed to the slide rather than nulled under it. Runs at settle, or early on reopen.
        deferForLastDeparted(() -> {
            forgetPickSubject();
            pickingItemClearPath = null;
            pickerKind = PickerKind.ITEM;
            pickerEntries = List.of();
            pickerInventory = List.of();
            pickerMatches = List.of();
            pickerRows = List.of();
            pickerFrame = null;
            pickerQuery = "";
            pickerSelected = -1;
            pickerBody.setScrollY(0);
            itemSearch = null;
        });
    }

    /**
     * A stand-in for an item this build cannot draw: a recessed box and a question mark.
     *
     * <p>For an id with no item behind it -- a mod that is not installed, an item that was removed.
     * The id is kept and drawn beside this, so the placeholder is what stops "cannot draw it" from
     * reading as "there is nothing here".
     */
    static void drawItemPlaceholder(GuiRenderer r, int x, int y, int box) {
        r.fill(x + 1, y + 1, x + box - 1, y + box - 1, ArmatureTheme.recessed());
        r.text("?", x + (box - r.textWidth("?")) / 2, y + (box - 8) / 2, ArmatureTheme.blocked());
    }

    static final int MENU_ROW_HEIGHT = 16;

    /** How far below its rest a menu opens before climbing into place. A nudge, not a travel. */
    static final int MENU_RISE_PX = 3;

    /** How long the climb takes: felt as a settle rather than watched as a transition. */
    static final int MENU_RISE_MILLIS = 90;

    /**
     * A label cut to the panel's width.
     *
     * <p>The menu is a fixed-width panel and a group title is an author's string, so a long one used to
     * be drawn straight past the panel and over the canvas — which reads as a rendering fault rather
     * than as a long name. Same rule as a control's label, on the same terms.
     */
    static String fitLabel(GuiRenderer r, String label) {
        int room = BookMenu.MENU_WIDTH - 8;
        if (r.textWidth(label) <= room) {
            return label;
        }
        String cut = label;
        while (cut.length() > 1 && r.textWidth(cut + "\u2026") > room) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "\u2026";
    }

    /**
     * Whether a press belongs to an open dropdown, and answers it: the row the pointer is on, or the press
     * that dismisses it.
     *
     * <h2>Why this is the first thing `mouseClicked` asks</h2>
     *
     * <p>Everything that could answer a press -- the base class's widget pass, the rails' own drawn targets,
     * the pan tail and the canvas -- stands <b>below</b> this gate, so while a dropdown is open it is the
     * only thing on screen a press can reach. That is the whole of the fault this closes, and the report
     * was general because the cause was: *"any button with a dropdown when the dropdown has buttons under
     * it"*. Every {@code ChoiceField} on the dock opens this one menu and the menu lands on the rows below
     * it, so a press on a menu row over another chooser ran that chooser's own press instead.
     *
     * <p>The wheel has had this rule since it was written -- see {@code mouseScrolled}, which closes an open
     * menu before it scrolls anything, because the lists and the canvas move under the pointer. A press is
     * the same argument with a larger blast radius, and {@code .utils/check_dropdowns.py} holds the two
     * together so neither can drift from the other.
     *
     * <h2>A claim, rather than a mute</h2>
     *
     * <p>Both owners' rows are drawn by this screen, so neither has a widget that needs the container's
     * pass -- which is what makes "answer it here, and return" the honest shape. The other half of the rule
     * is for the overlays that <i>do</i> own widgets: see {@link #refreshOverlayMute}, where the same
     * argument is made in its own words.
     */
    private boolean pressDropdown(double mouseX, double mouseY, int button) {
        // The shared menu and the flyout beside it. `pressMenu` already means "a row runs, a flyout tunes or
        // chooses, anything else closes and is spent", so the whole of this half is asking it first.
        if (!menu.isEmpty()) {
            return bookMenu.pressMenu(mouseX, mouseY);
        }
        // The table editor's Import popover, on the menu's terms: two drawn rows in a box, and a press
        // anywhere inside that box that is not on one of them dismisses it rather than reaching the entry
        // row, the field or the canvas under it.
        if (tableImportOpen && overlay == PanelKind.TABLE_EDITOR) {
            return itemPicker.pressImportPopover(mouseX, mouseY, button);
        }
        return false;
    }

    /**
     * Whether an open overlay has claimed the pointer, so that nothing under it may hover.
     *
     * <h2>The rule, and why it is these three</h2>
     *
     * <p>A thing drawn over the book that answers a press <b>anywhere</b> owns the pointer while it is
     * open: what a player cannot press is what they cannot point at. Three do that. The shared menu and its
     * flyout claim the press outright ({@link #pressDropdown}); the table editor's Import popover claims it
     * on the same terms; and the colour picker mutes every other control and spends every press its own
     * fields do not take. The report this closes was the other half of that fix: the press stopped reaching
     * the control underneath, and the *hover* did not -- a covered button still lit up, and still showed
     * its tooltip, because a widget works its hover out from its own rectangle and nothing else.
     *
     * <h2>Why a page is not one of them</h2>
     *
     * <p>The type page, the item picker and the settings page take the card's body and are drawn over it,
     * and a press <i>outside</i> one of them is the book's -- the canvas pans beside an open page, which is
     * the behaviour those pages were built to have. So a page does not own the pointer, and it does not need
     * to: everything a page covers is a control {@link #refreshOverlayMute} has already made inert, and the
     * band a page takes holds nothing hand-drawn that hovers. Making a page own the pointer would cost the
     * page's own search box and fields their hover to close a hole that is not open.
     *
     * <p>Read once per frame by {@code render}, which is where the drawing's pointer is decided; the three
     * draws that <i>are</i> an overlay read {@code renderMouseX}/{@code renderMouseY} instead.
     * {@code .utils/check_dropdowns.py} holds the three names here and the press gate's together, so a new
     * overlay that claims presses cannot leave hover behind.
     */
    private boolean overlayOwnsPointer() {
        return !menu.isEmpty()
                || (tableImportOpen && overlay == PanelKind.TABLE_EDITOR)
                || colourPopover.isOpen();
    }

    // ------------------------------------------------------------------
    // Pinning to the HUD
    // ------------------------------------------------------------------

    /** "(3 quests, 1 dependent)", counted the same way the sidebar's delete note is. */
    static String questDeleteNote(List<String> targets) {
        Set<String> ids = new HashSet<>(targets);
        int dependents = 0;
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            if (ids.contains(entry.id())) {
                continue;
            }
            for (String dependency : dependenciesOf(entry)) {
                if (ids.contains(dependency)) {
                    dependents++;
                    break;
                }
            }
        }
        return BookMenu.note(targets.size(), "quest", dependents, "dependent");
    }

    /**
     * Makes a tree's id unique against what this chapter has, and reads it back as an element.
     *
     * <p>Null when the tree does not decode, which is the honest refusal: an element this build cannot read
     * is not one it can draw optimistically, so it goes to the server and appears when the tree does.
     */
    CanvasElement placeElement(com.google.gson.JsonObject tree) {
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (CanvasElement element : elementsNow()) {
            taken.add(element.id());
        }
        String wanted = tree.has("id") && tree.get("id").isJsonPrimitive()
                ? tree.get("id").getAsString() : "element";
        tree.addProperty("id", dev.ellipog.tenet.client.dev.ElementDefaults.uniqueId(wanted, taken));
        return CanvasElement.fromJson(tree).orElse(null);
    }

    /**
     * Where a new element goes in the chapter's list: the end.
     *
     * <p>The end rather than the front, because the list is drawn in the order it is written when two elements
     * share an {@code order} — so a decoration added last draws over one added first, which is what "I have
     * just put this here" means. An author who wants it underneath writes an {@code order}.
     *
     * <p>Counted through {@link #elementsNow}, so two elements added in a row get successive positions rather
     * than both landing on the tree's length — which is the same reason the whole feature reads that view.
     */
    int elementInsertIndex() {
        return elementsNow().size();
    }

    /**
     * The chapter's canvas elements as this client currently believes them to be, in draw order.
     *
     * <h2>The one read the whole feature goes through</h2>
     *
     * <p>Three layers, in this order: the <b>tree</b> — what the server last sent; the <b>shape draft</b>,
     * which adds an element that has been asked for and drops one that has been asked away; and the
     * <b>field draft</b>, which amends each element's own JSON with the values the author has given it. The
     * result is sorted by the model's own {@link CanvasElement#inDrawOrder}, so the preview and the real list
     * cannot disagree about what is on top.
     *
     * <p><b>Everything reads this and nothing reads the tree directly:</b> the stamp, the boxes, the gates,
     * the grips, the hit tests and the panel's rows. That is what makes an edit appear in the frame it is
     * made — and it is the whole of "no rubberband", because there is no second path that could still be
     * showing the old value.
     */
    List<CanvasElement> elementsNow() {
        String chapter = effectiveChapter();
        if (chapter == null) {
            return List.of();
        }
        List<CanvasElement> shaped = elementDraft.apply(chapter, ClientQuestCache.elements(chapter),
                Util.getMillis());
        List<CanvasElement> out = new ArrayList<>(shaped.size());
        for (CanvasElement element : shaped) {
            out.add(BookPins.overlaidElement(chapter, element));
        }
        return CanvasElement.inDrawOrder(out);
    }

    /**
     * The element with this id as this client believes it to be, or null.
     *
     * <p>Not the cached slot's element: that one carries a gesture's live preview, so a gesture that started
     * from it would compound its own last frame. This is the settled belief — the tree plus the drafts — which
     * is what a new gesture takes hold of and what a commit is diffed against.
     */
    CanvasElement elementNow(String id) {
        if (id == null) {
            return null;
        }
        for (CanvasElement element : elementsNow()) {
            if (element.id().equals(id)) {
                return element;
            }
        }
        return null;
    }

    /**
     * The chapter's links as this client believes them to be: the tree, the shape draft, and the
     * pending field values, in that order.
     *
     * <p><b>Everything reads this and nothing reads the tree directly:</b> the stamp, the boxes, the
     * hit tests and the panel's rows. That is what makes an edit appear in the frame it is made --
     * and it is the whole of "no rubberband", because there is no second path that could still be
     * showing the old value. The element form's own note says the same for decorations, and for the
     * same reason.
     */
    List<QuestLink> linksNow() {
        String chapter = effectiveChapter();
        if (chapter == null) {
            return List.of();
        }
        List<QuestLink> shaped = linkDraft.apply(chapter, ClientQuestCache.links(chapter),
                Util.getMillis());
        List<QuestLink> out = new ArrayList<>(shaped.size());
        for (QuestLink link : shaped) {
            out.add(BookPins.overlaidLink(chapter, link));
        }
        return List.copyOf(out);
    }

    /**
     * The link with this id as this client believes it to be, or null.
     *
     * <p>Not the cached slot's link: that one carries a gesture's live preview, so a gesture that started
     * from it would compound its own last frame. This is the settled belief — the tree plus the drafts — which
     * is what a new gesture takes hold of and what a commit is diffed against.
     */
    QuestLink linkNow(String id) {
        if (id == null) {
            return null;
        }
        for (QuestLink link : linksNow()) {
            if (link.id().equals(id)) {
                return link;
            }
        }
        return null;
    }

    /**
     * Removes one element from the chapter's file.
     *
     * <p>And lets go of it, because a selection that outlives its subject is a ring around nothing — the same
     * reason a deleted quest clears {@code selectedQuest}.
     *
     * <p>The removal is <b>drafted</b> before it is sent, so the element and its ring leave the canvas on the
     * press rather than a round trip later, and its pending field values are forgotten with it: a value for an
     * element that no longer exists would be applied to whatever the next element with that id turns out to be.
     */
    void deleteElement(String id) {
        elementDraft.remove(effectiveChapter(), id, Util.getMillis());
        fieldDraft.forgetOwner(effectiveChapter(), ELEMENT_OWNER_PREFIX + id);
        send(new EditorOp.RemoveElement(id));
        if (id.equals(selectedElement)) {
            selectedElement = null;
        }
    }

    /**
     * Writes one pending value for a link, which is what makes the edit visible before the tree arrives.
     *
     * <p>Before the send, not after, and unconditional: the draft is the <i>preview</i>, so a value the author
     * asked for shows even if the request could not go out — and the backstop in {@code FieldDraft} is what
     * stops a value the server never accepted from being believed forever. See {@code draftElementField} for
     * the same argument on a decoration.
     */
    void draftLinkField(String link, String field, JsonElement value) {
        fieldDraft.set(effectiveChapter(), LINK_OWNER_PREFIX + link, field, value,
                ClientQuestCache.treeRevision(), Util.getMillis());
    }

    /**
     * Opens the panel for one element — what a click on the canvas does.
     *
     * <p>Nothing happens for an id this client cannot see, which is the honest answer for a press that
     * arrived about an element the tree has since dropped: a panel about nothing would draw its heading and
     * no fields, and every press in it would go nowhere.
     */
    void openElementPanel(String id) {
        if (id == null || elementNow(id) == null) {
            return;
        }
        bookCanvas.elementPanelId = id;
        selectedElement = id;
        applyColumns(PanelStack.afterOpen(columns(), PanelKind.ELEMENT));
        rebuildWidgets();
    }

    // ------------------------------------------------------------------
    // The pinned list
    // ------------------------------------------------------------------

    /** The empty list's one row. */
    static final String PINNED_EMPTY = "pinned:empty";

    // ------------------------------------------------------------------
    // The Assets panel: the pack, listed
    // ------------------------------------------------------------------

    /** Which section the panel is showing. Seeded from the file, written back when it changes. */
    dev.ellipog.tenet.client.dev.AssetsLayout.Section assetsSection =
            dev.ellipog.tenet.client.dev.AssetsLayout.shownOrFirst(
                    dev.ellipog.tenet.client.dev.AssetsLayout.Section.TABLES);

    /** The panel's frame, from the card, once a frame. */
    dev.ellipog.tenet.client.dev.AssetsLayout.Frame assetsFrame;

    /** The panel's pressable pieces, from the last frame's drawing -- the house rule, one list. */
    final List<EditTarget> assetsTargets = new ArrayList<>();

    /** Opens the pack's panel, on the section the author was last reading. */
    void openAssets() {
        bookMenu.closeMenu();
        // The remembered section, coerced to one this depth shows: a page it does not show would draw its
        // list under no selected section and offer that section's own footer action. The coercion is for
        // the view only -- `ClientWorking` keeps what the author chose, so Advanced finds "tables" again.
        assetsSection = dev.ellipog.tenet.client.dev.AssetsLayout.shownOrFirst(
                BookAssets.sectionNamed(ClientWorking.section()));
        bookAssets.assetsBody.setScrollY(0);
        applyColumns(PanelStack.asRoot(columns(), PanelKind.ASSETS));
        rebuildWidgets();
    }

    /** Whether this build can apply a template: its type is registered, under a parseable id. */
    static boolean knownTemplateType(
            dev.ellipog.tenet.client.dev.ComponentTemplate.Template template) {
        net.minecraft.resources.Identifier id =
                net.minecraft.resources.Identifier.tryParse(template.typeId());
        if (id == null) {
            return false;
        }
        return switch (template.kind()) {
            case TASK -> dev.ellipog.tenet.quest.task.TaskTypes.ids().contains(id);
            case REWARD -> dev.ellipog.tenet.quest.reward.RewardTypes.ids().contains(id);
            case CONDITION -> dev.ellipog.tenet.quest.condition.ConditionTypes.ids().contains(id);
        };
    }

    /**
     * A template row's picture: the item the setup is about where it names one, else the type's
     * own badge.
     *
     * <p>The subject first, because a row of ten chest icons says nothing while a row of the
     * actual items says what each template hands over or asks for. An item reward wearing an
     * override still reads as its payout — the icon override is presentation, and the row is
     * about content.
     */
    static ItemStack templateIcon(
            dev.ellipog.tenet.client.dev.ComponentTemplate.Template template) {
        com.google.gson.JsonObject payload = template.payload();
        if (payload != null && payload.has("item")) {
            com.google.gson.JsonElement item = payload.get("item");
            if (item.isJsonPrimitive() && !item.getAsString().isBlank()) {
                ItemStack stack = itemStack(item.getAsString());
                if (!stack.isEmpty()) {
                    return stack;
                }
            }
            if (item.isJsonObject() && item.getAsJsonObject().has("item")) {
                try {
                    ItemStack stack = itemStack(item.getAsJsonObject().get("item").getAsString());
                    if (!stack.isEmpty()) {
                        return stack;
                    }
                }
                catch (RuntimeException ignored) {
                    // A hand-edited template with an item object of an unexpected shape still gets
                    // its type's badge below rather than an empty row.
                }
            }
        }
        if (template.kind()
                == dev.ellipog.tenet.client.dev.ComponentTemplate.Kind.CONDITION) {
            net.minecraft.resources.Identifier id =
                    net.minecraft.resources.Identifier.tryParse(template.typeId());
            return id == null ? ItemStack.EMPTY
                    : dev.ellipog.tenet.quest.condition.ConditionTypes.iconOf(id).toStack();
        }
        return BookEditorControls.typeIcon(template.kind()
                == dev.ellipog.tenet.client.dev.ComponentTemplate.Kind.REWARD ? "rewards" : "tasks",
                template.typeId());
    }

    void pressTableEditorTarget(EditTarget target, double mouseX, double mouseY) {
        int index = target.index();
        switch (target.action()) {
            // The footer's way out, answered on press like everything else in this panel. One table up
            // while a nested table is on the stack, out of the panel at the root -- the same thing the
            // button's own label says, because both read `tableStack`.
            case TABLE_DONE -> bookRewards.popTableEditor();
            case TABLE_PAGE_BACK -> {
                // One step back from whatever the list has over it: the page of types, or the roll report.
                // The panel stays open, which is what the button now says. The rebuild is the field
                // question: the title box is built either way, but the picker's widget host has to be torn
                // down.
                pickingEntryType = null;
                tableShowRoll = false;
                rebuildWidgets();
            }
            case TABLE_IMPORT_MENU -> {
                if (!tableImportOpen) {
                    // Stamped on the way in, like every menu: the popover climbs into place below.
                    tableImportOpenedAt = net.minecraft.util.Util.getMillis();
                }
                tableImportOpen = !tableImportOpen;
            }
            case TABLE_PREVIEW -> {
                tablePreview = dev.ellipog.tenet.client.dev.TableEditorLayout
                        .nextPreview(tablePreview);
            }
            // How a reward row draws this table: each press flips the drafted value, so a burst of
            // presses accumulates like the rolls stepper above rather than fighting the replica.
            case TABLE_USE_TITLE, TABLE_HIDE_TOOLTIP -> {
                var model = bookRewards.tableModel();
                if (model.isPresent()) {
                    boolean next = target.action() == EditAction.TABLE_USE_TITLE
                            ? !model.get().useTitle()
                            : !model.get().hideTooltip();
                    bookRewards.sendTableValue(target.action() == EditAction.TABLE_USE_TITLE
                            ? "useTitle" : "hideTooltip",
                            new com.google.gson.JsonPrimitive(next));
                }
            }
            case TABLE_ROLLS_UP, TABLE_ROLLS_DOWN -> {
                // Read from the drafted model, so a burst of presses accumulates: the second press sees
                // the first press's value rather than the replica the server has not answered about yet.
                var model = bookRewards.tableModel();
                // The bounds are the codec's, read from the table rather than restated here: a stepper
                // that could reach one past the end would send a value the loader refuses, and the author
                // would watch the number spring back with nothing said about why.
                if (model.isPresent()) {
                    int next = Math.max(dev.ellipog.tenet.quest.loot.RewardTable.LOOT_SIZE_MIN,
                            Math.min(dev.ellipog.tenet.quest.loot.RewardTable.LOOT_SIZE_MAX,
                                    model.get().lootSize()
                                            + (target.action() == EditAction.TABLE_ROLLS_UP ? 1 : -1)));
                    bookRewards.sendTableValue("lootSize", new com.google.gson.JsonPrimitive(next));
                }
            }
            case TABLE_WEIGHT_UP, TABLE_WEIGHT_DOWN -> {
                var model = bookRewards.tableModel();
                if (model.isPresent() && index >= 0 && index < model.get().entryCount()) {
                    double step = BookEditorControls.shiftDown() ? 10 : 1;
                    double next = Math.max(0, model.get().entries().get(index).weight()
                            + (target.action() == EditAction.TABLE_WEIGHT_UP ? step : -step));
                    bookRewards.sendTableValue("entries." + index + ".weight", new com.google.gson.JsonPrimitive(next));
                }
            }
            case TABLE_WEIGHT -> {
                // The field edits the value it was drawn from, which is the drafted one -- so typing a
                // weight and pressing Enter commits what the author read, not what the file last said.
                var model = bookRewards.tableModel();
                String shown = model.isPresent() && index >= 0 && index < model.get().entryCount()
                        ? trimNumber(model.get().entries().get(index).weight())
                        : target.value();
                // And the commit goes to the TABLE, with the kind that says what the text becomes: the
                // inline editor's own commit path writes a quest field, which for a path like
                // `entries.1.weight` is a stray member in the quest file -- a weight box that corrupted
                // the file it was opened from.
                bookAssets.openTableFieldEditor(index, "weight",
                        dev.ellipog.tenet.client.dev.TableRowFields.Kind.DECIMAL, shown,
                        target.box(), target.textX(), target.textY(), mouseX, mouseY);
            }
            case TABLE_FOLD -> {
                if (!tableFolded.remove(index)) {
                    tableFolded.add(index);
                }
            }
            case TABLE_FOLD_FLAG -> bookAssets.toggleTableFlag(index, target.path());
            case TABLE_FOLD_CHOICE -> bookAssets.cycleTableChoice(index, target.path());
            case TABLE_FOLD_STEP_UP -> bookAssets.nudgeTableNumber(index, target.path(), 1);
            case TABLE_FOLD_STEP_DOWN -> bookAssets.nudgeTableNumber(index, target.path(), -1);
            case TABLE_FOLD_TEXT -> {
                // The value in the box is the drafted one, so what is typed over is what was read.
                bookAssets.openTableFieldEditor(index, target.path(),
                        dev.ellipog.tenet.client.dev.TableRowFields.Kind.TEXT,
                        bookAssets.tableFieldText(index, target.path()), target.box(), target.textX(),
                        target.textY(), mouseX, mouseY);
            }
            case TABLE_FOLD_RAW -> bookAssets.openTableFieldEditor(index, "",
                    dev.ellipog.tenet.client.dev.TableRowFields.Kind.RAW,
                    bookAssets.entryRewardText(index), target.box(), target.textX(), target.textY(), mouseX,
                    mouseY);
            case TABLE_REMOVE -> {
                // One press, and the entry is gone -- so it asks first, the same confirm-in-place the
                // inline table's removal uses. A cross is the narrowest control on the row and the row
                // below it is another cross, so a mis-click here costs an entry and a second mis-click
                // costs another.
                if (!tableConfirmReplace) {
                    tableConfirmReplace = true;
                    status("Press again to remove this entry", true);
                    return;
                }
                tableConfirmReplace = false;
                // A list position that moved is no longer about the entry the author touched: the same
                // rule the card follows when a task is removed. See FieldDraft#forgetList.
                BookRewards.tableDraft.forgetList(effectiveChapter(), bookRewards.tableOwnerKey(), "entries");
                // And the folds move with it: they are keyed by position, so every open fold above the
                // hole has to shift down with the row it belongs to -- otherwise entry 3's fields are
                // drawn under entry 2's name, and the next press on that fold edits the wrong entry.
                bookAssets.forgetFoldAfterRemove(index);
                bookRewards.sendTableOp(new dev.ellipog.tenet.editor.TableOp.Remove(tableAddress, index));
            }
            case TABLE_ADD_ITEM -> bookAssets.openTableItemPick("", -1, "Add an item to the table");
            case TABLE_ENTRY_ITEM -> bookAssets.openTableItemPick(
                    tableEntryPath(index, target.path().isEmpty() ? "item" : target.path()), index,
                    "Choose the item");
            case TABLE_ICON -> bookAssets.openTableItemPick("icon.item", -1, "Choose this table's icon");
            case TABLE_IMPORT -> {
                // The popover's choice is the press: it closes with the row it ran.
                tableImportOpen = false;
                // A sentinel: a refused import answers with the editor's own reply payload, and without
                // one it would consume the next edit's -- misattributing every reply after it.
                // An import is answered by an edit reply whatever happens -- `handleTableImport` replies
                // on success and on refusal -- so its marker is consumed by the reply loop, like an op's.
                if (ClientEditReplies.noteSent(REPLICA_SENTINEL) != null) {
                    ArmatureNetwork.sendToServer(
                            new dev.ellipog.tenet.net.TableImportRequestPayload(tableAddress, false));
                }
            }
            case TABLE_IMPORT_CHEST -> {
                tableImportOpen = false;
                if (ClientEditReplies.noteSent(REPLICA_SENTINEL) != null) {
                    ArmatureNetwork.sendToServer(
                            new dev.ellipog.tenet.net.TableImportRequestPayload(tableAddress, true));
                }
            }
            case TABLE_ROLL -> {
                // One button, two meanings, and the label says which: over the entries it rolls, over the
                // roll it goes back. The roll's own state is what decides, so the two can never disagree.
                if (tableShowRoll) {
                    tableShowRoll = false;
                    tableImportOpen = false;
                    rollBody.setScrollY(0);
                    return;
                }
                rollBody.setScrollY(0);
                // The panel only flips to the roll when the request can actually be sent: showing a roll
                // nobody asked for would be a panel describing a question that was never put.
                if (ClientEditReplies.noteSent(REPLICA_SENTINEL) == null) {
                    return;
                }
                tableShowRoll = true;
                // The reading the author has selected travels as it stands. It used to travel as a
                // derived `includeEmpty` flag, which the server turned back into one of two modes --
                // so "All once" and "Choice" came back as weighted rolls of a table whose own panel
                // said every entry is granted. See TableRollRequestPayload.
                ArmatureNetwork.sendToServer(new dev.ellipog.tenet.net.TableRollRequestPayload(
                        tableAddress, TABLE_ROLL_COUNT, tablePreview));
            }
            case TABLE_UNDO -> tableUndo();
            case TABLE_OPEN_CHILD -> {
                var model = bookRewards.tableModel();
                if (model.isPresent() && index >= 0 && index < model.get().entryCount()
                        && model.get().entries().get(index).reward()
                                instanceof dev.ellipog.tenet.quest.reward.TableReward nested
                        && nested.tableId().isPresent()) {
                    bookRewards.pushTableEditor(dev.ellipog.tenet.editor.TableAddress.of(nested.tableId().get()));
                }
            }
            case TABLE_ADD_REWARD -> questEditor.openTableTypePick("rewards", "Add a reward to the table");
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------
    // A folded entry's own fields
    // ------------------------------------------------------------------

    /** The file path of one field of one entry's reward: {@code entries.2.reward.amount}. */
    static String tableEntryPath(int index, String field) {
        String prefix = "entries." + index + ".reward";
        return field == null || field.isEmpty() ? prefix : prefix + "." + field;
    }

    /**
     * One entry's reward as JSON, with this panel's pending values applied.
     *
     * <p>Through the draft, like every other read in this panel: the fold's controls show what the author
     * has just set, and a box drawn from the replica while the box beside it drew from the draft is two
     * answers about one entry — which is what the fold did before this, for the item id and its count.
     */
    JsonObject draftedEntry(int index) {
        return BookAssets.entryAt(bookRewards.tableDraftedRoot(), index);
    }

    /**
     * The next word in a cycling control's ring, or the same word back when the ring does not hold it.
     *
     * <p>{@code indexOf} answers -1 for an absent value <i>and</i> for one this build does not know, and
     * wrapping both to {@code ring[0]} meant a press rewrote the file: a reward carrying {@code no_toast}
     * -- which a shipped example does -- became {@code default} on one stray click, and an addon's word
     * became the first option of a field it had nothing to do with. Absence is the one case that means
     * "unset", so it is the one case that advances.
     */
    static String nextOption(List<String> ring, String current) {
        int at = ring.indexOf(current);
        if (at < 0) {
            return current == null || current.isEmpty() ? ring.get(0) : current;
        }
        return ring.get((at + 1) % ring.size());
    }

    /**
     * The folds as they stand, with each open entry's line count read from its own type.
     *
     * <p>Built once per frame beside the odds and the weight total, because it walks the entries: the
     * drawing, the wheel and the drop all ask for the same value, and three walks of one list is three
     * answers waiting to disagree.
     */
    dev.ellipog.tenet.client.dev.TableEditorLayout.Folds tableFolds() {
        return dev.ellipog.tenet.client.dev.TableEditorLayout.Folds.of(tableFolded,
                bookAssets::tableFoldLines);
    }

    /**
     * The icon a table pick's card shows: the table's own, or the entry's item.
     *
     * <h2>What the header's icon means, since it is one derivation for four buttons</h2>
     *
     * <p>It is the pick's <b>subject</b>: the icon row shows the icon that will be replaced, an entry's
     * item field shows the item that will be replaced, and a pick that <i>adds</i> an entry — `+ Item` and
     * `+ Reward` alike — shows the table it is being added to, because that is the subject there is. It
     * used to answer {@code EMPTY} for a new entry, so `+ Item` had no icon while `+ Reward` had one, and
     * the first fix for that took the icon off the reward instead of putting it on the item: two reports
     * for one line. The fallback below is the half that makes them agree.
     */
    ItemStack tablePickSubjectFor(String path, int entryIndex) {
        var model = bookRewards.tableModel();
        if (model.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (path != null && path.startsWith("icon")) {
            return model.get().displayIcon().toStack();
        }
        if (entryIndex >= 0 && entryIndex < model.get().entryCount()
                && model.get().entries().get(entryIndex).reward()
                        instanceof dev.ellipog.tenet.quest.reward.ItemReward item) {
            return item.item().toStack();
        }
        // A new entry: the table, which is what the pick is for.
        return model.get().displayIcon().toStack();
    }

    /** Leaves the picker a table opened: back to the editor, with the pick forgotten. */
    void closeTablePick() {
        tablePickPath = null;
        closeItemPicker();
        if (tableAddress != null) {
            applyColumns(PanelStack.asRoot(columns(), PanelKind.TABLE_EDITOR));
        }
        rebuildWidgets();
    }

    /** Ctrl+Z in the editor: the frame's own history, and never past the table it opened on. */
    void tableUndo() {
        if (tableAddress == null || tableApplied <= 0) {
            status("Nothing to undo in this table", true);
            return;
        }
        // The drafts go first: the server is about to put the file back, and a pending value still
        // holding what was undone would keep drawing it -- a Ctrl+Z that looks like it did nothing.
        BookRewards.tableDraft.forgetOwner(effectiveChapter(), bookRewards.tableOwnerKey());
        bookRewards.sendTableOp(new dev.ellipog.tenet.editor.TableOp.Undo(tableAddress));
    }

    void tableRedo() {
        if (tableAddress == null || tableUndone <= 0) {
            status("Nothing to redo in this table", true);
            return;
        }
        BookRewards.tableDraft.forgetOwner(effectiveChapter(), bookRewards.tableOwnerKey());
        bookRewards.sendTableOp(new dev.ellipog.tenet.editor.TableOp.Redo(tableAddress));
    }

    // ------------------------------------------------------------------
    // Dropping an item in from a recipe viewer
    // ------------------------------------------------------------------

    /**
     * Where a dragged stack may land: the table editor's entry list, and nothing else.
     *
     * <p>Null whenever the book is showing something that is not that list — which is most of the time,
     * and is the honest answer: a viewer that highlighted the card while a chapter was open would be
     * offering a drop the screen is about to refuse.
     */
    @Override
    public net.minecraft.client.renderer.Rect2i dropArea() {
        if (overlay != PanelKind.TABLE_EDITOR || tableAddress == null || tableEditorFrame == null
                || tableShowRoll || pickingEntryType != null) {
            return null;
        }
        BookGeometry.Rect list = tableEditorFrame.list();
        return new net.minecraft.client.renderer.Rect2i(list.x(), list.y(), list.width(), list.height());
    }

    /**
     * Takes a stack dropped on the entry list: one entry, at the row under the pointer.
     *
     * <p>The coordinates are what makes a drop land where it was aimed; a viewer that cannot report them
     * sends {@code NaN}, which {@code dropIndexAt} reads as the end of the list. The entry is an item
     * reward carrying the stack's count and its data — a dragged enchanted sword is that sword, the same
     * rule the item picker follows.
     */
    @Override
    public boolean acceptDrop(double mouseX, double mouseY, ItemStack stack) {
        if (dropArea() == null || stack == null || stack.isEmpty()) {
            return false;
        }
        int entries = bookRewards.tableModel().map(dev.ellipog.tenet.quest.loot.RewardTable::entryCount).orElse(0);
        int at = Double.isNaN(mouseX) || Double.isNaN(mouseY)
                ? entries
                : dev.ellipog.tenet.client.dev.TableEditorLayout.dropIndexAt(entries, tableEditorFrame,
                        tableEditorBody.scrollY(), mouseX, mouseY, tableFolds());
        if (at < 0) {
            return false;
        }
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        com.google.gson.JsonElement components = null;
        if (!stack.getComponentsPatch().isEmpty()) {
            components = net.minecraft.core.component.DataComponentPatch.CODEC
                    .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, stack.getComponentsPatch())
                    .result()
                    .orElse(null);
        }
        // The same factory the item picker commits through, and with the same count rule: a drop and a
        // pick of one stack are one entry, and the count is the stack's either way.
        JsonObject entry = QuestPanelLayout.itemEntry(id, stack.getCount(), components);
        BookRewards.tableDraft.forgetList(effectiveChapter(), bookRewards.tableOwnerKey(), "entries");
        bookRewards.sendTableOp(new dev.ellipog.tenet.editor.TableOp.Insert(tableAddress, at, entry));
        status("Added " + (stack.getCount() > 1 ? stack.getCount() + "x " : "") + id, false);
        return true;
    }

    /** A number without a trailing {@code .0}, for a panel. */
    static String trimNumber(double value) {
        return value == Math.floor(value) && !Double.isInfinite(value)
                ? String.valueOf((long) value) : String.valueOf(value);
    }

    /**
     * Forgets every picker that could be armed.
     *
     * <h2>One place, because the rule was enforced in one of two</h2>
     *
     * <p>{@code closeOverlay} cleared the type and condition pickers; {@code closeTablePanel} cleared
     * neither. So pressing Done — or Escape — on the table editor's type picker closed the panel and left
     * the picker armed, and the quest card that appeared behind it drew a list of reward types over
     * itself. The card's own comment already stated the rule ("a picker left armed would greet the next
     * quest with a list of types"); it was simply written where only one of the two exits could see it.
     *
     * <p>Adding a picker kind means adding it here, and this is the only place that has to know.
     */
    /**
     * Forgets what the picker card is about: its target, its title, its icon and its subject line.
     *
     * <p>The four are one thing — what the card says it is for — and three callers each set them, which
     * is how a stale one gets drawn: a table pick set no target of its own, fell back to the target's own
     * sentence, and told the author it was choosing a chapter's icon. One reset, called by every picker
     * that arms, so the next field added to the subject cannot be forgotten by two of the three.
     */
    void forgetPickSubject() {
        pickTarget = null;
        pickTitle = "";
        pickIcon = ItemStack.EMPTY;
        pickTexture = "";
        pickSprite = "";
        pickName = "";
    }

    void clearPickers() {
        pickingEntryType = null;
        pickingConditionFor = null;
        tablePickPath = null;
        // Cleared before the item picker closes, because that is what tells it *not* to hand the overlay
        // back to the table editor: this is a exit, not a return.
        tablePickReturn = false;
        // And the type page's own return flag with it, for the same reason: a stale one would have the
        // next page of a quest card leaving itself back into a table that is not on screen.
        typePickReturn = false;
        closeItemPicker();
    }

    void closeOverlay() {
        // Both columns, through the rules: a second column is always something the first one opened, so
        // there is no arrangement in which it outlives it. See `PanelStack.afterClose(now, false)`.
        applyColumns(PanelStack.afterClose(columns(), false));
        // No reveal to cancel and no width to drop for the dock's sake: `applyColumns` has just forgotten the
        // arrival of anything that left the arrangement, and the dock is not one of the things this closes.
        panelDragRail = null;
        panelDragKind = PanelKind.NONE;
        panelLiveWidth = 0;
        rowHover.clear();
        // The content nullings are deferred, not dropped: the exiting rails draw from this state for the
        // length of their slide. They run when the exits sink, or early if one of these rails reopens
        // first — teardown before setup, as ever. The gestures and the commits below stay immediate: a
        // drag has no surface to follow, and a picker's commit must not wait out an animation.
        deferForLastDeparted(() -> {
            overlayQuest = null;
            overlayView.scrollTo(0);
            // And the element panel is about nothing once it is closed: its rows were built for one element, and a
            // subject left behind would be a panel that could be reopened onto whatever that id means later.
            bookCanvas.elementPanelId = null;
            bookCanvas.elementRows = List.of();
            bookCanvas.elementLayout = null;
            // And a Delete left asking its question goes with it: the next element opened gets one press, like
            // the quest card's own Delete, which this exit also forgets. See `clearPickers` below.
            bookCanvas.elementConfirmingDelete = null;
            // The chain of jumps ends with the card: a stale stack would replay a trail from a card the
            // reader closed, and the next card they open is a fresh session with its own history.
            overlayHistory.clear();
            // The folds are the card's too: the next card has its own list, and an index folded here names
            // nothing there.
            entryFolded.clear();
            // The Assets panel's pressable list, which belongs to the frame that drew it: a list left behind
            // would answer a press on a row that is no longer on screen.
            assetsTargets.clear();
            bookAssets.assetsBody.setScrollY(0);
            // An arm made in the Assets panel is that panel's, so it goes when the panel does: left behind, the
            // next table of the same name would be removed on one press by a chip nobody had asked about.
            assetsConfirmRemove = null;
            entryConfirmRemove = null;
            confirmingDelete = false;
            settingsOpen = false;
            draggingSlider = null;
            settingsDraft.clear();
            // The widgets go with the clear; the references and the path must not outlive them.
            inlineField = null;
            inlineArea = null;
            editingPath = null;
        });
        // And the colour picker when it belongs to this panel: its anchor rows are gone with it, and a
        // picker left standing would commit to an element nobody is looking at. A dock-anchored picker
        // stays -- the dock is not what closed.
        if (popoverChip != null && popoverChip.view() == bookCanvas.elementView) {
            closeColourPopover();
        }
        // The editor's transient state goes with the card: a picker left armed would greet the next
        // quest with a list of types, and a Delete left confirmed would delete on one press. One call,
        // so every picker kind is forgotten by every exit rather than by whichever ones were remembered.
        clearPickers();
        // And the table panels', which name a table and a reward the closed card was showing.
        bookRewards.closeTablePanel();
        rebuildWidgets();
    }

    /**
     * Opens a quest's card from a "Requires" jump, putting the card being left on the back stack.
     *
     * <p>Navigation rather than selection: the reader is following a chain, and the chain is the
     * context — so the card being left keeps its scroll and its folds, and the header's arrow is one
     * press away. The canvas selection follows the jump as well, because the card is a detour: closing
     * it should leave the canvas where the reader ended up, not where they started.
     *
     * <p>A quest in another chapter is reached by switching chapters first, exactly as the sidebar
     * would: a card cannot be shown for a chapter the book is not on, and in edit mode every commit
     * would otherwise go to the wrong file.
     */
    void navigateToQuest(String questId) {
        ClientQuestCache.Entry target = entryFor(questId);
        if (target == null || questId.equals(overlayQuest)) {
            // An id the tree does not know cannot be opened, and a jump to the card already showing is
            // not a jump. Both are silent: there is nothing here for a player to fix.
            return;
        }
        if (overlay == PanelKind.QUEST && overlayQuest != null) {
            overlayHistory.add(new CardVisit(overlayQuest, overlayView.viewport().scrollY(),
                    Set.copyOf(entryFolded)));
        }
        if (!target.chapterId().equals(effectiveChapter())) {
            bookSidebar.selectChapter(target.chapterId());
        }
        selectedQuest = questId;
        multiSelection.clear();
        bookRewards.openOverlay(questId);
    }

    /**
     * Takes the canvas to a quest: closes the card, then {@link BookAssets#revealNode}.
     *
     * <p>What the prerequisite row's locate icon and the shift/middle-click gesture both call.
     */
    void locateOnCanvas(String questId) {
        closeOverlay();
        bookAssets.revealNode(questId);
    }

    /** The first task a player hands over by hand <b>and still has to</b>, or -1. */
    int firstManualTask(ClientQuestCache.Entry quest) {
        // The rule lives in the cache, which holds all three of its inputs -- the task's own kind, what
        // is recorded and what the server would accept a press on. See ClientQuestCache.firstSubmitTask
        // for why a checkmark and a task that takes are offered their button at opposite moments.
        for (int index = 0; index < quest.tasks().size(); index++) {
            if (bookAssets.submitOfferedWithMarks(quest.id(), index)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Hands one task over, and marks it handed in until the server answers.
     *
     * <h2>What changed here, and what the old note got right</h2>
     *
     * <p>This made no local change, and its reason was that the server may refuse: <i>"showing the outcome
     * before it arrives would mean showing something the server may refuse"</i>. That is still true, and it
     * is why the mark is <b>not</b> a claim that it happened — it is a claim that it was <i>asked for</i>,
     * it is drawn as pending rather than as a settled count, and it goes when the answer arrives, when the
     * task's subject leaves the tree, or at the backstop. See {@code PendingClaims}.
     *
     * <p>What the old note missed is the shape of a refusal here: the submit handler sends <b>nothing</b>
     * when nothing changed, so a refused hand-in produced no message at all — the row went on reading
     * "hand in" and the only trace was an action-bar line, which is not drawn behind this screen. So the
     * press looked like it had not registered, which is the one reading a player never recovers from.
     *
     * <p>The button is offered only when the server has already said a press would be accepted
     * ({@code ClientQuestCache.submitOffered}), so a refusal is the narrow window between that answer and
     * the press — a stale inventory, or a stage granted in the last second.
     */
    void submit(String questId, int taskIndex) {
        if (!pendingClaims.mark(PendingClaims.taskKey(questId, taskIndex),
                net.minecraft.util.Util.getMillis())) {
            // Already in flight: a second press for one task is not a second packet.
            return;
        }
        ArmatureNetwork.sendToServer(new SubmitTaskPayload(questId, taskIndex));
        Constants.LOG.debug("asked the server to submit task {} of {}", taskIndex, questId);
    }

    /**
     * Asks the server to hand over a finished quest's rewards, and marks them collected until it answers.
     *
     * <p>The reader card's Claim button and the rewards menu's quest header are the same press asked from
     * two places, and they now mark the same keys — the header used to be optimistic and the card was not,
     * which is how the menu could read a quest collected while the card behind it still offered to claim it.
     *
     * <p>Whether anything is owed is still the server's decision, and it is the one that has the stored
     * progress; a stale client showing a Claim button for a quest it collected a minute ago still gets its
     * refusal, and the refusal still puts the mark back — because a claim always answers.
     */
    void claim(String questId) {
        if (!bookRewards.markQuest(questId, net.minecraft.util.Util.getMillis())) {
            return;
        }
        ArmatureNetwork.sendToServer(new ClaimRewardPayload(questId));
        Constants.LOG.debug("asked the server to hand over the rewards for {}", questId);
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    /**
     * Deliberately empty.
     *
     * <p>The background this screen draws over the world is its own scrim, and vanilla's blurred menu
     * background is suppressed on {@code ArmatureScreen} — the base class — so no panel has to remember
     * it. See this class's comment for the mechanism and that class's for why it moved.
     */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
            float partialTick) {
        renderMouseX = mouseX;
        renderMouseY = mouseY;
        // The intro's anchors are re-placed while drawing, so a closed panel cannot leave a stale
        // one: each frame starts with none, and the quest card's add rows file theirs as they draw.
        introTaskBox = null;
        introRewardBox = null;
        // **What the drawing sees, which is not always where the pointer is.** An overlay that is drawn over
        // the book and answers a press anywhere *owns the pointer* while it is open: nothing under it may
        // hover, and the three draws that *are* the overlay read `renderMouseX`/`renderMouseY` instead. See
        // `overlayOwnsPointer` for the rule, the three overlays and why a page is not one of them -- and the
        // fields above for the real pointer, which existed for exactly this before anything read them.
        boolean owned = overlayOwnsPointer();
        int hoverX = owned ? BookItemPicker.POINTER_NONE : mouseX;
        int hoverY = owned ? BookItemPicker.POINTER_NONE : mouseY;
        // The strip's hover-peek, read before anything draws: a change rebuilds for the new state,
        // so everything below already answers it. Edge-triggered — a stationary pointer costs
        // nothing — and never mid-wipe, under a menu, or while the pointer is owned.
        updateSidebarPeek(hoverX, hoverY, owned, Util.getMillis());
        snapshotFrame();
        try {
        // The one forced signature in this class for drawing, and the whole of the seam at this call
        // site: a GuiGraphics arrives because Minecraft's Screen hands over one and there is no other
        // override, so it is wrapped and handed on. Nothing below this line names the type.
        //
        // The widget pass stays here rather than moving into renderWith, and that is deliberate: the
        // controls are AbstractWidgets and the base class draws them from the context it was given, so
        // there is no version of "draw the widgets" that takes a renderer. Keeping it on this side is
        // what lets renderWith — the part that decides what the book looks like — be driven by a
        // RecordingRenderer with no client at all.
        //
        // And the tooltip is drawn last of all, after the controls, which is the whole of the fix for
        // a tooltip that appeared *underneath* the button it described.
        // The plain renderer is kept as well as the counting one, and the dev overlay below draws
        // through *this* one. That is not tidiness: an overlay measured by the counters it reports
        // would add its own seven measurements and its own panel to the very numbers it is showing, so
        // the fill and width counts would each carry a fixed observer's offset for as long as the
        // overlay was up -- which is precisely when they are being read.
        GuiGraphicsRenderer plain = new GuiGraphicsRenderer(graphics);
        // Counted only when an operator has asked for vitals, because the counters are drawn only then: a
        // counting renderer measures **every** drawing call, and building one for an overlay nobody is
        // going to see was edit mode paying a per-call cost for a panel it no longer draws. See Vitals for
        // the switch and `drawDevOverlay` for the drawing.
        GuiRenderer renderer = Vitals.showing()
                ? new dev.ellipog.tenet.client.dev.CountingRenderer(plain)
                : plain;
        // The cache hit/miss counter is switched by the same gate, and for the same reason: its line is
        // reported beside this one's, so a client that is not reporting the frame's counters has nothing to
        // read a cache ratio against. See `CacheHits` for why the ratio is worth a switch of its own --
        // briefly, a cache that is never consulted and one that is consulted on every call return the same
        // values, and the ratio is the only thing that tells them apart.
        dev.ellipog.armature.client.ui.CacheHits.set(Vitals.showing());

        // The world behind the book is **dimmed rather than blurred**, and the reason is the frame budget.
        //
        // This was `renderer.blur(partialTick)` on every frame, which is
        // `gameRenderer.processBlurEffect` — and that chain is six full-screen box-blur passes at the
        // **window's** resolution, alternating between the main target and a swap target
        // (`assets/minecraft/shaders/post/blur.json`; the six passes are H/V at three radius
        // multipliers). At 1080p with the default menu blurriness that is about twelve million fragments
        // through forty-odd filtered taps each, every frame, whether the book is drawing one node or a
        // thousand. Measured against the drawing code it is the frame's dominant fixed cost, and it is
        // the reason a blank canvas was not fast: nothing about it scales with the canvas.
        //
        // A fill is one quad. `ArmatureTheme.dim()` is the token for exactly this — Theme describes it as
        // "the wash behind a whole screen, over the world" — so using it here keeps the reading the blur
        // was there for at none of the cost.
        //
        // **There is no longer a second, heavier version of this.** The modal path drew its own scrim over
        // the book as well, and a blur with it, because a card is read over whatever is behind it. Nothing
        // is over the book now: every kind is a rail beside a canvas that must stay undimmed, so the wash
        // under the world is the only one in the frame and the blur is gone entirely.
        //
        // This fill goes under the book, so what it darkens is the world the book floats on — the reason the
        // original design has it at all, since without it an open book reads as a window cut into a live
        // world. And a book with a rail open takes it too: without that, the world behind a docked book was
        // the one surface with no wash at all.
        renderer.fill(0, 0, width, height, ArmatureTheme.dim());

        // One region for the whole of the book's own drawing — its panels, its surfaces, its header, the
        // canvas and the chrome it draws itself. On 1.21.1 this region also turned hundreds of per-fill
        // submissions into one; extraction submits in order either way, so what is left of it is the
        // layering boundary the callers mark with it.
        //
        // The regions nest harmlessly: inner regions cost nothing extra.
        // The canvas caption, carried out of the batch above: what the pointer hovered, for the
        // TOOLTIP band below. Read here rather than re-derived there, because the hover is this
        // frame's and a second hit test could answer differently.
        BookCanvas.CanvasCaption captioned =
                renderer.batched(() -> renderWith(renderer, hoverX, hoverY, partialTick));


        // Everything from here on is the **chrome layer**, drawn after the book and before the
        // widgets -- and order is the whole of the mechanism now. Read the next paragraph before
        // moving any of it: this ordering once needed depth to enforce, and it was misdiagnosed
        // once already.
        //
        // On 1.21.1 an item icon wrote depth 150 while fills drew at depth 0 without writing any,
        // so a fill drawn *after* an icon failed the depth test where the icon was and was never
        // drawn there, however late it was drawn. The chrome therefore rode 400 units up and beat
        // icons by depth rather than by order. Extraction records every draw in call order, so a
        // later draw is simply on top: the layers below hold by sequence alone, and the Z the old
        // code pushed here is gone with the pipeline that needed it. Verified the only way the new
        // model allows: vanilla's own inventory screens interleave fills and icons with no depth
        // tricks, so submission order is sufficient -- and the in-game list still asks eyes to
        // confirm no icon punches through a panel, because this is exactly the fault order cannot
        // be trusted about without looking.
        //
        // The rails' widgets travel with their surfaces, before either is drawn or hit: the slide is
        // placed here rather than in the drawing because the widget pass below reads the positions, and
        // here rather than in `tick` because the positions belong to this frame's clock, not the last
        // tick's. See `slidePanelWidgets` for the inert-while-travelling half of the arrangement.
        long slideNow = net.minecraft.util.Util.getMillis();
        slidePanelWidgets(slideNow);
        slidePopoverWidgets(slideNow);

        // The chrome band draws in plain order now (see above): no Z push, because there is no Z
        // left to push on a 2D pose. The pose itself stays for the popover's slide below, which is a
        // real 2D travel.
        var pose = graphics.pose();
        {
            // **One call for every rail on screen** -- the dock, the panel and the child it opened -- drawn
            // in this band rather than in `drawBook` for the same reason as the cluster panel below: they
            // float over the canvas, and a rail drawn before the nodes would disappear behind them. No draw
            // order can fix that the other way round; see the chrome note above for why order is the whole
            // mechanism now.
            //
            // Before the widget pass (further down), so a rail's own controls sit on its surface -- which is
            // why nothing on a rail needs a redraw of its own. `drawPanels` asks `presented` what to draw, so
            // this needs no guard of its own: an empty arrangement draws nothing.
            //
            // The chapter tab draws an item icon of its own; icons draw late like everything else here,
            // so it lands with the band rather than punching through it.
            //
            // The chapter list's own surface first, so the rails stay above it: a maximally wide rail
            // may reach the floating list, and the quest it carries is what the press is about. See
            // `drawSidebarChrome` for why the surface lives up here rather than in `drawBook`.
            bookOverlay.drawSidebarChrome(renderer, hoverX, hoverY, net.minecraft.util.Util.getMillis());
            bookOverlay.drawPanels(renderer, pose, hoverX, hoverY, net.minecraft.util.Util.getMillis());

            // The view cluster's backing panel, and it is drawn here rather than in `drawBook` for
            // exactly this reason: it has to be inside the raised Z, and `drawBook` cannot raise it.
            //
            // Nothing else moved: the panel is still positioned and sized by `BookGeometry`, and it is
            // still drawn before the widgets so the three buttons sit on top of it.
            // Guarded on having data, which is the same condition `drawBook` returns early on: with no
            // quests there are no widgets in the cluster, so the panel would be a raised box with three
            // things missing from it. `drawBook` used to reach this line only after that early return,
            // and moving the panel here would have quietly dropped the guard with it.
            // Drawn whether or not a modal is open, because the cluster is built in every branch now
            // and the mat is what makes the three buttons read as one group. The guard used to include
            // `overlay == PanelKind.NONE`, which was right while only the book's branch built the buttons
            // and wrong the moment the modal branches did too: three controls with no panel behind them.
            if (ClientQuestCache.hasData()) {
                BookGeometry.Rect cluster = viewControls();
                // Riding the sidebar's wipe like its buttons below: a backing drawn at its landing
                // while the three buttons travel would frame empty air for 160 milliseconds. Same
                // anchor the buttons read, so the mat and its controls never disagree mid-slide.
                long clusterNow = net.minecraft.util.Util.getMillis();
                if (sidebarSlideStart >= 0 && sidebarSliding(clusterNow)
                        && (geometry().sidebarState().expanded() || sidebarDeparting)) {
                    BookGeometry.Rect wipe = sidebarWipeRect(clusterNow);
                    BookGeometry.Rect target = geometry().sidebar();
                    BookGeometry.Rect anchor = PanelLayout.sidebarTravelAnchor(sidebarDeparting,
                            sidebarSlideFromRect, target, sidebarDepartAnchor);
                    BookGeometry.Rect base = geometry().viewControlsFor(anchor);
                    cluster = BookGeometry.Rect.at(base.x() + wipe.right() - anchor.right(),
                            base.y() + wipe.y() - anchor.y(), base.width(), base.height());
                }
                ArmatureTheme.panel(renderer, cluster.x(), cluster.y(), cluster.width(),
                        cluster.height(), ArmatureTheme.panel(), ArmatureTheme.panelEdge());
            }

            // (The author's pills' mat stood here: raised at this Z because the mat floated over the canvas
            // at the pills' own offsets. The band's strip is a surface of the panel instead, so it is painted
            // by `drawBook` with the header's -- and the band's *buttons* are still drawn by hand, further
            // down, because they are above the widget pass's clip exactly as the header's four are.)

            // The widget pass, clipped from the sidebar's list top downwards.
            //
            // **Clipping at the list top rather than at the panel is the fix, not a tightening.**
            //
            // This used to clip to the panel and rely on the *scroll* to keep rows off the header:
            // every wheel notch was snapped to a whole row pitch, so a row was never left half past the
            // top edge. That was true, and then the scrollbar became draggable — which sets arbitrary
            // offsets, because the thumb has to follow the pointer. The first drag left a row sitting
            // through the title bar, and the picture of it is what this comment is answering. The old
            // note here said "what keeps rows off the header is therefore the scroll rather than the
            // clip", which was a correct description of an arrangement that one input later stopped
            // holding.
            //
            // So the clip does the work now, which is the only version that survives a second way to
            // scroll: snapping is a property of one input and a clip is a property of the drawing.
            // Whatever a row does, it does it below `chapterListTop()`.
            //
            // `chapterListTop()` rather than the sidebar's own viewport rectangle, because the base
            // class draws every widget in one call and there is no way to clip one group of them and
            // not another — so this is one rectangle for the whole screen. Using the list's *top* as
            // the bound is what makes one rectangle do the job: it excludes the header and the gap
            // under it, and the only widgets it has to spare are the three view buttons, which start
            // below it. `BookGeometryTest` asserts that clearance, because two pixels of margin is
            // exactly the kind of thing a later layout change eats without anybody noticing.
            //
            // Close is above this line, so it is drawn by hand in the chrome layer below — see
            // the narrow clip, which is what keeps it out of the pass's own drawing.
            BookGeometry.Rect book = panelRect();
            // The clip is a **horizontal band**: the chapter list's top edge down, across the panel. It
            // is what keeps a scrolled row from being drawn up through the title bar.
            //
            // It therefore excludes the header, which is above it -- and that is why the header's two
            // controls cannot be drawn by this pass. A round tried narrowing it to the sidebar's column
            // as a fix for their hover; that was the wrong axis (the header is above the band, not beside
            // it) and it cut the canvas's view cluster out of the pass as well, since the cluster is to
            // the right of that column. Reverted, and their hover is fixed where it belongs: see the
            // chrome layer below, which now draws them through the widget's own `render`.
            try (GuiRenderer.Scoped clip = renderer.clip(book.x(), geometry().chapterListTop(),
                    book.right(), book.bottom())) {
                // The whole widget pass in one region, and the clip above is what makes that safe rather
                // than a problem: a scissor change flushes a managed batch *itself* before the scissor
                // moves — `GuiGraphics.applyScissor` calls `flushIfManaged` — which is the invariant that
                // would otherwise forbid a batch spanning a clip. The text fields in this pass open clips
                // of their own, and that is exactly the case this relies on.
                //
                // The wipe travels the list's rows with its surface while it expands — and back out
                // while it departs: offset from the rectangle the widgets were built for, so the rows
                // ride the card's edge instead of popping in once it has landed or vanishing the frame
                // it starts leaving. Bases filed on the first slide frame and restored right after the
                // pass, the rails' own arrangement, so a rebuild mid-wipe cannot strand a row off its
                // list: the next frame re-derives the same answer from the same base.
                //
                // The view cluster rides the same wipe, right-edge anchored beside the list: without
                // it the three buttons jump between their docked and floating seats while the card
                // glides. Same bases, same restore, one frame later the same answer again.
                boolean sidebarWipeTravelling = sidebarSlideStart >= 0
                        && sidebarSliding(Util.getMillis())
                        && (geometry().sidebarState().expanded() || sidebarDeparting);
                if (sidebarWipeTravelling) {
                    BookGeometry.Rect wipe = sidebarWipeRect(Util.getMillis());
                    BookGeometry.Rect target = geometry().sidebar();
                    // The anchor is where the widgets were built: the target for an arrival, the
                    // filed departure box for a collapse. Either way the offset starts at nothing
                    // and lands whole, so live widgets are glued to the surface at both ends —
                    // and a collapse that reverses an expand mid-wipe continues from mid-wipe
                    // instead of snapping the rows back to full for a frame.
                    BookGeometry.Rect anchor = PanelLayout.sidebarTravelAnchor(sidebarDeparting,
                            sidebarSlideFromRect, target, sidebarDepartAnchor);
                    if (!sidebarDeparting) {
                        // The position's travel plus the width's shortfall: a wipe that only
                        // narrows keeps the rows' right edge on the moving edge, and one that
                        // moves the card carries the rows the same pixels. Either is zero when
                        // it does not apply. Departing rows need none of this: they left the
                        // screen when the collapse started and are drawn by hand below.
                        int rowTravelX = wipe.x() - anchor.x() + wipe.width() - anchor.width();
                        int rowTravelY = wipe.y() - anchor.y();
                        for (AbstractWidget widget : sidebarRowWidgets) {
                            travelSidebarWidget(widget, rowTravelX, rowTravelY);
                        }
                    }
                    // One pill that moves: the three buttons ride inside the same travelling mat
                    // the backing draws above, seated at fixed mat-relative slots — so buttons and
                    // mat agree by construction on every frame, whatever the anchor, the window or
                    // the frame rate does. No bases filed, nothing restored, nothing rebuilt for a
                    // slide: seats are a pure function of the wipe, and settled frames simply keep
                    // the exact landing seats the last travelling frame put them at.
                    BookGeometry.Rect mat = geometry().viewControlsFor(anchor);
                    int matX = mat.x() + wipe.right() - anchor.right();
                    int matY = mat.y() + wipe.y() - anchor.y();
                    int slot = 0;
                    for (AbstractWidget widget : viewClusterWidgets) {
                        widget.setX(matX + BookGeometry.VIEW_MAT);
                        widget.setY(matY + BookGeometry.VIEW_MAT
                                + slot * (BookGeometry.VIEW_BUTTON + BookGeometry.VIEW_GAP));
                        slot++;
                    }
                }
                renderer.batched(() -> {
                    // The hover pointer for the pass and the rows' marks, and the real one for the menu:
                    // see `overlayOwnsPointer`. The menu *is* the overlay, so it is the one thing here that
                    // is still aimed at where the pointer really is.
                    super.extractRenderState(graphics, hoverX, hoverY, partialTick);
                    // The departed rows over the sliding card, drawn by hand: they left the screen
                    // when the collapse started, so the pass above cannot draw them — and the wipe's
                    // own clip inside is what keeps them on the card, which is the whole of this
                    // rewrite. Only while orphans are aboard: settled, they are already discarded.
                    if (sidebarDeparting && !sidebarDeparted.isEmpty()) {
                        drawDepartedSidebar(renderer);
                    }
                    // The chapter rows' progress bars and reward counts, over the rows they belong to and
                    // inside the same clip: a mark drawn outside the list would be a mark over the header.
                    // And only while the rows are settled: mid-wipe they travel with the card while the
                    // marks read unshifted rectangles, and the rail's icons carry no bars — the selected
                    // fill is the rail's answer to "where am I".
                    if (!sidebarWipeTravelling && geometry().sidebarState().expanded()) {
                        bookSidebar.drawSidebarChapterProgress(renderer, hoverX, hoverY);
                    }
                    // The drag's own marks, above the rows it is about and inside the clip for the same
                    // reason the rows are: a seam line that escaped the list would draw into the header.
                    // Unlike the bars above, these draw for a live rail drag too: reordering from the
                    // rail is a supported gesture, and a drag with no seam is a drag with no target.
                    if (!sidebarWipeTravelling
                            && (geometry().sidebarState().expanded() || sidebarDragLive)) {
                        bookSidebar.drawSidebarDrag(renderer);
                    }
                    // The inline session's faint line under its field: the path preview as the
                    // author types, or the rename's id line. Inside the clip for the same reason
                    // the bars are, and only while the rows are settled.
                    if (!sidebarWipeTravelling && geometry().sidebarState().expanded()) {
                        bookSidebar.drawInlineStatus(renderer);
                    }
                    // And the menu, which is outside the rows' clip only in the sense that it overhangs
                    // them -- it belongs to the list, and it is drawn after it so nothing paints over it.
                    bookMenu.drawMenu(renderer, renderMouseX, renderMouseY);
                    return null;
                });
                // The rows' travel is undone; the cluster's seats are always valid — freshly built
                // or wipe-derived — so there is nothing to put back.
                if (sidebarWipeTravelling && !sidebarDeparting) {
                    for (AbstractWidget widget : sidebarRowWidgets) {
                        restoreSidebarTravel(widget);
                    }
                }
            }

            // Close, drawn by hand rather than by the widget pass above.
            //
            // It is in the header, and the widget clip now starts at the list's top edge so that
            // sidebar rows cannot be drawn through the title bar. That clip would swallow Close, so
            // the one control that lives above it is drawn here instead -- at the chrome Z, which is
            // also where a close button belongs.
            //
            // It is still a widget in every other respect: it is registered, so presses reach it
            // through the base class's own input handling, and -- since this round -- so does hover,
            // which `addWidget` alone never gave it. What it does not do is get *drawn* by that pass:
            // the clip discards it, and the drawing that counts is here.
            // Guarded on the overlay being shut, because `closeButton` is only rebuilt by the branch of
            // `init` that builds the book. Opening an overlay clears every widget and builds the
            // overlay's two controls instead, so without this the field would still be pointing at a
            // control that has been removed — and `draw` checks `visible`, not membership, so it would
            // cheerfully draw Close over the quest card.
            // Drawn whether or not an overlay is open, and its absence here was a reported fault: the
            // guard read `overlay == PanelKind.NONE`, so the one control every dialog has disappeared at
            // the moment a dialog was open. It closes the modal rather than the book when one is up --
            // see `buildHeaderChrome` -- which is what Escape already does.
            // Close and Party are drawn here rather than by the pass -- and now they are drawn by the
            // widget's **own `render`**, which is the difference between a control that fades under the
            // pointer and one that never did.
            //
            // The pass cannot draw them: its clip is the band below the header, so anything in the header
            // is discarded. And a control the pass never renders is a control whose hover is never worked
            // out -- `isHovered` is set in `render`, from the pointer -- which is why these two were the
            // only controls in the book that did not fade.
            //
            // And every control the chrome above the clip owns, drawn by this layer in one pass. The list is
            // filled by `buildHeaderChrome` as it builds them -- so **the build site is the draw list**, and
            // a control cannot be added to the chrome and left unpainted.
            //
            // That is a fix rather than a tidy-up, and the two comments this replaces are the evidence: the
            // rewards button and then the settings button were each built, added as widgets and clickable
            // from the day they landed, while being invisible -- because the widget pass is clipped to the
            // band below the header and the draw list here was written by hand, somewhere else. A control
            // that exists, takes clicks and cannot be seen is the report this block exists to prevent, and
            // the hand-written list produced it twice.
            //
            // The band's four are in the same list for the same reason, and their arrival is what made the
            // hand-written version untenable: they are above the clip too (the clip starts at
            // `chapterListTop()`, below the band), so four more lines had to be remembered. The pills they
            // replaced were the one case that did *not* need a line here -- they floated over the canvas,
            // which the pass does reach, so they painted themselves -- and that difference is exactly the
            // kind of thing a later reader gets wrong.
            //
            // `hoverTold` is a control's hover said out loud by the caller that draws it, and `isMouseOver`
            // is vanilla's own test -- which answers false for an inert control too, so nothing here fades
            // while a modal has the book behind it, and nothing here can be pressed.
            for (ArmatureButton control : questEditor.chromeControls) {
                control.hoverTold(control.isMouseOver(hoverX, hoverY)).draw(renderer);
            }
            // **The panel's own ink, and the pass cannot draw either piece of it.** Two things live here:
            // the placeholder text a search box or a title field carries, which is ink no widget owns, and
            // the inline editor an author types into, which is a widget deliberately kept out of the
            // renderable list so that it can be clipped. Both belong *over* what the widget pass drew, which
            // is why they are here rather than in it.
            //
            // **Both used to be drawn for a card and only for a card**, which is the fault this fixes rather
            // than a tidy-up: with the card gone, a docked search box drew no placeholder and the field an
            // author was typing into was never painted at all -- invisible, since `drawOpenEditor` had one
            // caller and it was inside `if (modalUp())`.
            //
            // The chapter's palette when the panel is the chapter's -- a quest's own fields -- and the main
            // theme for the player's panels, by position rather than by a special case: see
            // `chapterBoundOverlay`.
            if (chapterBoundOverlay()) {
                try (ArmatureTheme.Scope theme = ArmatureTheme.scope(viewportTheme())) {
                    bookOverlay.drawPanelOverlays(renderer);
                }
            }
            else {
                bookOverlay.drawPanelOverlays(renderer);
            }

            // The colour picker, above the book and any card, below the tooltips. Its own fields were
            // drawn by the widget pass underneath, so they are redrawn here on top of its surface -- the
            // same arrangement `panelOverlays` uses for a card's controls.
            //
            // **The real pointer, and this is the whole of why it is the real one.** The picker is one of
            // the overlays that owns the pointer -- the pass above it was handed a pointer over nothing, so
            // nothing under the picker hovers -- and the picker's own four fields are the exception: they
            // are drawn here, and `ScrubField` works its hover out from the coordinates it is given rather
            // than from `isHovered`. See `overlayOwnsPointer`.
            if (colourPopover.isOpen()) {
                // The settle, shared with the fields and the pointer: the surface shifts inside its scope
                // while its four fields travel by their own slide, so both land together. Two scopes
                // rather than one, because the fields are already moved — translating them again here
                // would double-shift every box they type into.
                int arrival = popoverOffset(net.minecraft.util.Util.getMillis());
                pose.pushMatrix();
                pose.translate(arrival, 0F);
                try {
                    // Aimed where it is drawn: the hover washes and the arrow-key part read the pointer
                    // through the same shift the presses do.
                    colourPopover.render(renderer, (int) pickerPointerX(renderMouseX), renderMouseY);
                }
                finally {
                    pose.popMatrix();
                }
                for (net.minecraft.client.gui.components.AbstractWidget widget
                        : colourPopover.widgets()) {
                    if (widget instanceof dev.ellipog.tenet.client.dev.ScrubField field) {
                        field.render(renderer, renderMouseX, renderMouseY);
                    }
                    else if (widget instanceof dev.ellipog.armature.client.ArmatureTextField text) {
                        text.render(renderer);
                    }
                }
            }

            // Tooltips last, because they describe what is under the pointer: a tooltip is a panel
            // and some text, drawn after everything it could cover, and order is what keeps it on
            // top now (see the chrome note above -- there is no Z left to raise).
            //
            // Outside the clip, deliberately: a tooltip belongs over everything, including the edge it
            // happens to reach past.
            // The canvas captions first, outside the themed block below: a caption is a label about
            // a node, not part of it, so it keeps the book's own ink rather than the chapter's --
            // and drawn late it stays over pictures by submission order, the same as tooltips.
            if (captioned != null) {
                if (captioned.quest() != null) {
                    bookCanvas.drawNodeCaption(renderer, captioned.quest());
                }
                if (captioned.link() != null) {
                    bookCanvas.drawLinkCaption(renderer, captioned.link());
                }
                // The hovered line names itself at the pointer: a line brightens where the menu
                // opens, and the caption says which line that is. A tooltip rather than a caption
                // box, because a line has no box to sit under -- and drawn late, no icon covers it
                // however it is drawn.
                if (captioned.line() != null) {
                    drawTooltip(renderer, List.of(captioned.line()), hoverX, hoverY);
                }
            }
            // The notices first, so a tooltip -- which is what the pointer is asking for -- stays on top.
            // They keep the main theme: a toast is the book talking, not the chapter.
            bookInput.drawToasts(renderer, Util.getMillis());
            // The tooltips describe what is under the pointer, so they wear that surface's palette: a
            // caption over a chapter's canvas is that chapter's, and one over the sidebar is the book's
            // own. The toasts above are outside this scope on purpose.
            try (ArmatureTheme.Scope theme = ArmatureTheme.scope(tooltipTheme(hoverX, hoverY))) {
                drawPendingLabels(renderer);
                drawRowTooltips(renderer, hoverX, hoverY);
                drawLinkTarget(renderer, hoverX, hoverY);
                drawTooltips(renderer, hoverX, hoverY);
                // The intro over the tooltips: its spotlight dims the book and its tooltip must
                // beat theirs, while owning no press but its own three buttons.
                bookIntro.drawIntroGuide(renderer, hoverX, hoverY);
            }

            // Consumed, because they are drawn here rather than where they are collected: the card fills
            // the list while it draws, and a frame that draws no card (the overlay closed, a chapter
            // switch) must not leave last frame's label standing over the canvas.
            pendingLabels.clear();
            rowTooltips.clear();
        }
        // The frame is over: the counting renderer (dev mode only) logs its totals at most once a second.
        // Here rather than anywhere earlier because the tooltips above are the last thing a frame draws.
        dev.ellipog.tenet.client.dev.CountingRenderer.endFrame(renderer);

        // And the overlay, after that call and through the plain renderer: after, so this frame's
        // numbers are the frame's own; through the plain one, so the overlay is not counted by the
        // instrument it exists to display. Last of all, so nothing covers it.
        drawDevOverlay(plain);
        }
        finally {
            clearFrameSnapshot();
        }
    }

    /** How far the dev overlay's panel sits from the frame's top-left corner. */
    private static final int DEV_OVERLAY_INSET = 4;

    /**
     * The dev overlay: the frame rate, then the counting renderer's last report, over the top-left corner.
     *
     * <h2>Why an overlay rather than only the log line</h2>
     *
     * <p>Because the numbers describe a frame, and the frame a person needs to read them on is the one
     * they are changing: zoom a canvas in, pan it, drag a node. Reading a log line after each of those
     * and holding four numbers in your head to compare them is a feedback loop nobody completes, and
     * the two decisions this instrument was added for — whether the canvas's batching holds at high
     * zoom, and whether a measurement cache would have anything to hit — are exactly the kind that are
     * answered by watching a number move while you do the thing.
     *
     * <h2>The frame rate, and whose number it is</h2>
     *
     * <p>{@code Minecraft.getFps()} rather than a counter of this screen's own. The game already measures
     * it once a second for the debug screen, so this is a read of that rather than a second answer to the
     * same question — and a second answer is one that <i>disagrees</i> with F3, which makes both numbers
     * suspect. It is also the number that matters while authoring: not how long this screen took, but
     * whether the client is still smooth with the book open.
     *
     * <p>It is drawn whether or not there is anything counted yet, which is what the first line of the
     * overlay is for. The counters arrive a second into the frame after the first, and a frame rate that
     * appeared a second late would be one you had already finished the gesture without.
     *
     * <p>That is also the whole of its cost on a client with the mode off: this returns immediately, and
     * nothing else here is built — the summary is only ever written by a counting renderer, and one is
     * only built when the mode is on.
     */
    private void drawDevOverlay(GuiRenderer r) {
        // **One switch, and it is the operator's.** The frame rate and the counters are one instrument now:
        // `/tenet vitals` draws both, and edit mode draws neither. The counters used to be edit mode's --
        // they were the whole reason a counting renderer was built whenever the tools were on -- which put a
        // panel of numbers over the canvas for every author, measuring nothing in particular. See Vitals for
        // why the switch is an operator's, and why it is re-checked here rather than trusted from the server.
        if (!Vitals.showing()) {
            return;
        }
        String[] lines = devOverlayLines(true, dev.ellipog.tenet.client.dev.CountingRenderer.summary(),
                minecraft.getFps(), minecraft.getFrameTimeNs(),
                Math.round(viewport().scale() * 100F));
        if (lines.length == 0) {
            return;
        }

        int lineHeight = r.lineHeight();
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, r.textWidth(line));
        }
        ArmatureTheme.panel(r, DEV_OVERLAY_INSET, DEV_OVERLAY_INSET, width + 10,
                lines.length * lineHeight + 6, ArmatureTheme.panel(), ArmatureTheme.panelEdge());
        for (int i = 0; i < lines.length; i++) {
            r.text(lines[i], DEV_OVERLAY_INSET + 5, DEV_OVERLAY_INSET + 3 + i * lineHeight,
                    // The frame rate reads as a heading rather than as one more counter: it is the one line
                    // here that is about the whole client rather than about this screen's drawing.
                    i == 0 ? ArmatureTheme.title() : ArmatureTheme.body());
        }
    }

    /**
     * The dev overlay's lines: the frame rate and frame time, then the counters.
     *
     * <h2>Why this is a method rather than four lines inside the drawing</h2>
     *
     * <p>Because the one thing worth pinning about the overlay is that <b>edit mode alone draws nothing</b>,
     * and that is a fact about the lines rather than about the panel drawn around them — so it can be
     * asserted here, with no client, no window and no pixels. The drawing then has one job left: draw what
     * it is handed, or nothing when it is handed nothing.
     *
     * <p>The frame rate comes first because it is the one line about the whole client rather than about this
     * screen's drawing, and it is drawn even before the first counter report arrives — a frame rate that
     * appeared a second late would be one you had finished the gesture without.
     *
     * @param vitals     whether an operator has asked for the overlay; false returns no lines at all, which
     *                   is the whole of "edit mode does not draw it"
     * @param counted    the last reported frame's counters, from {@code CountingRenderer.summary()}
     * @param fps        {@code Minecraft.getFps()} — the game's own number, not a second answer to it
     * @param frameNanos the whole frame in nanoseconds, which has no ceiling where the frame rate does
     * @param zoom       the canvas's scale, as a percentage, or -1 when no canvas is drawn
     */
    static String[] devOverlayLines(boolean vitals, String[] counted, int fps, long frameNanos, int zoom) {
        if (!vitals) {
            return new String[0];
        }
        String[] lines = new String[1 + counted.length];
        // The frame rate **and the frame time**, and the second is the one to read. Frames per second is
        // capped: with vsync on, or a maximum frame rate set, it reports the cap rather than the work, so
        // a change that halves the frame's cost can leave the number exactly where it was.
        // `getFrameTimeNs` is the whole frame in milliseconds and has no ceiling.
        //
        // The zoom is on this line rather than in the counters because it is the same kind of number: a fact
        // about the frame rather than a count of what it drew. It is here at all because every reading this
        // overlay exists for is taken *at a zoom* -- the canvas's whole cost is a function of one -- and the
        // header's own percentage is quest completion, not scale. Reading the wrong number off the screen and
        // filing a row under it is a mistake this project has already made once.
        String scale = zoom < 0 ? "" : "   " + zoom + "%";
        lines[0] = "fps " + fps + "   " + (frameNanos / 1_000_000L) + " ms" + scale;
        System.arraycopy(counted, 0, lines, 1, counted.length);
        return lines;
    }

    /**
     * The frame's layer order, latest drawn on top.
     *
     * <p>On 1.21.1 these were three Z bands ({@code CHROME_Z}, {@code POPOVER_Z},
     * {@code TOOLTIP_Z}): item icons wrote depth 150 while fills drew at 0 without writing any,
     * so a later fill lost the depth test wherever an icon sat, and only height separated the
     * bands. Extraction records every draw in call order, so draw order <i>is</i> the layering
     * now: the book, then the chrome, then the picker, then tooltips, then the dev overlay. The
     * constants are gone with the depth buffer they were measured against; what remains is the
     * sequence in {@code extractRenderState}, which is the thing to keep in order.
     *
     * <p>Kept as a named idea rather than deleted outright, because the ordering constraints are
     * still real: tooltips describe what is under the pointer and must come after it, the picker
     * floats over panels whose controls can carry icons, and the dev overlay reads last so its
     * numbers are the frame's own. A reorder that breaks one of those reads as a layering bug,
     * and this is where that is written down.
     */

    /** The whole book, as a rectangle. Used by the render clip and by nothing else. */
    BookGeometry.Rect panelRect() {
        return geometry().panel();
    }

    /**
     * Draws the book. Takes a renderer, so this is the method a test can drive.
     *
     * <p>Separated from {@link #render} for exactly the reason the rest of this round exists: the
     * forced override cannot be tested — it needs a real {@code Screen} with a real client behind it —
     * and everything that decides <i>what is drawn</i> can be, once it is expressed in terms of the
     * seam. {@code RecordingRenderer} feeds this method and asserts on the result without a window.
     *
     * What it does <b>not</b> draw is the controls, and the honest reason is that they belong to
     * {@code AbstractWidget}: the base class iterates its renderables and hands each one the context it
     * was given. So a test driving this method sees the book and not its buttons. That is a real limit
     * worth stating rather than hiding — what it means is that button <i>placement</i> is
     * {@code BookGeometryTest}'s job, and button <i>appearance</i> is {@code ArmatureButton.draw}'s,
     * which does take a renderer and is testable on its own.
     *
     * <h2>Two palettes in one frame, and a test can tell</h2>
     *
     * <p>This is where the black is drawn and where the chapter's colours are drawn, and they are
     * different palettes on purpose — see {@code ArmatureTheme.scope}. The consequence for a test driving
     * this method is worth knowing: every colour it records is the one in force <i>at the point it was
     * drawn</i>, so a scope that leaks shows up as chrome drawn in a chapter's colours rather than as a
     * missing call. {@code ArmatureTheme.scopeDepth} is the blunt check for the same thing.
     */
    public BookCanvas.CanvasCaption renderWith(GuiRenderer renderer, int mouseX, int mouseY, float partialTick) {
        // **Tenet's look, scoped to Tenet's drawing.** The theme is an instance this mod owns now, and
        // this is where it reaches the toolkit: a scope opened around the whole of the book's own drawing,
        // closed however this returns. Another mod's screens open their own and see their own -- which is
        // the property the library had made impossible by holding one global theme for everybody.
        //
        // The canvas caption comes back up rather than drawing here: tests driving this method ignore
        // the return, `render` draws it at TOOLTIP_Z, and drawing order over widgets is not something
        // RecordingRenderer observes (see the tooltips note in renderBook).
        boolean ownFrame = !frameActive;
        if (ownFrame) {
            snapshotFrame();
        }
        try (ArmatureTheme.Scope look = ArmatureTheme.scope(ClientAppearance.LOOK.main())) {
            return renderBook(renderer, mouseX, mouseY, partialTick);
        }
        finally {
            if (ownFrame) {
                clearFrameSnapshot();
            }
        }
    }

    /** The book's own drawing, inside the look's scope. See {@link #renderWith}. */
    private BookCanvas.CanvasCaption renderBook(GuiRenderer renderer, int mouseX, int mouseY, float partialTick) {
        bookCanvas.centreCanvas();

        // A tree that arrived since the sidebar was built means the outline is describing a questline
        // this client no longer holds — and the same is true of a gate that opened, an author's view
        // changing, or a language arriving, which are the other three things the outline's rows depend
        // on. All four go through `sidebarStale`, the same question the builder asks, so the outline and
        // the widgets over it can never be built from different answers. See its javadoc.
        //
        // Rebuilt here rather than from a payload handler, because the handlers run before this screen
        // exists as often as after it, and because this is the first point in a frame where clearing and
        // recreating the widgets is safe — `super.render` has not started iterating them yet.
        //
        // No guard: the sidebar is built, drawn and answering in every arrangement there is, so it follows a
        // reload exactly as it does with nothing open. It used to skip while a card was up, on the reasoning
        // that `init` built only the card's controls -- and a rail beside the sidebar is not that case, so
        // the guard's only remaining effect would be a sidebar that went stale whenever a panel was open.
        if (BookSidebar.sidebarStale()) {
            rebuildWidgets();
            snapshotFrame();
        }

        // The animation clock, read once per frame and handed down. Nothing in this screen or in the
        // toolkit reads a clock itself -- see Tween's javadoc for why that is the property that makes
        // every animation here testable, and `Motion.tween` for how the client's setting reaches it.
        long now = Util.getMillis();

        // The one gesture here with no event to hang off, and the one release that never arrives.
        maintainHeldBar(now);

        // The book first, always, and a scrim over it when a modal is open. The scrim used to be drawn
        // *only* when no overlay was open, so opening a modal replaced the whole book with a flat dim
        // -- and the request was to keep it: "dont close whats behind them, just have it in the
        // background". It is no longer drawn from here -- see below -- but the decision it records is
        // the one the chrome layer still implements.
        //
        // A scrim under a blur, and the two are one decision made in two places: the scrim is what says
        // the book is inert, and the blur -- the call in `render`, once this method has drawn the book --
        // is what makes the card readable over it.
        //
        // This note used to say the blur was a thing deliberately not done, and then that it had been
        // tried and erased the card. Both are worth keeping in view: the fault was real, and its cause
        // was that a post-process leaves the pipeline set for its own passes, so the one layer drawn
        // with no clip of its own had nothing to draw into. `GuiRenderer.blur` puts the pipeline back.
        BookCanvas.CanvasCaption captioned = drawBook(renderer, mouseX, mouseY, now);

        // The scrim and the tools panel used to be drawn here, and both moved into `render`'s raised-Z
        // layer -- the same move, for the same reason, as the view cluster's backing panel: a fill at
        // Z = 0 fails the depth test wherever a node's item icon wrote depth 150, so the panel's own
        // pixels were missing under every icon ("items from nodes render over the tools panel") and the
        // scrim dimmed everything except the icons. Neither can be raised from here: this method sees
        // only a `GuiRenderer`, and the pose is a `GuiGraphics` thing. See the chrome layer.

        // The overlay is NOT drawn here any more. It is chrome, and chrome is drawn by
        // `render`, in the raised-Z layer, after the widget pass -- see `drawModal`. Two things
        // follow, and both were reported faults: the card is genuinely on top of the quest
        // canvas's item icons (which write depth at Z = 150 and so beat a card at Z = 0 whatever
        // the draw order), and the sidebar's rows behind it are *under* it rather than over it.
        //
        // The scrim went with it, in the same move and for the same arithmetic: at Z = 0 it dimmed
        // everything except the icons, which then read as bright smudges under the blur. It is the
        // first thing the raised layer draws, so it is still under the panel, the cluster and the
        // widget pass -- and still before the blur, which is the one ordering it cannot break.

        // Tooltips are deliberately NOT drawn here, and that is a bug fix rather than a preference.
        //
        // They belong over the controls, and the controls are drawn by `render` *after* this returns.
        // A tooltip drawn at this point is therefore underneath the widget it describes — which is
        // what it looked like on screen: hovering a chapter drew its name in a box that the chapter
        // button then painted over, so the tooltip showed as a ghost of text on the button's top edge
        // and read as a rendering fault rather than as a tooltip.
        //
        // Worth recording how it got here, because the comment that caused it is worth reading: this
        // method used to end with drawTooltips, annotated "kept here as well because a test driving
        // renderWith wants to see them". That is a rationalisation rather than a reason — a test can
        // call drawTooltips itself, and now does — and the cost was a visible defect that no test
        // could catch, because drawing order over widgets is not something RecordingRenderer observes.
        // A screenshot found it in five seconds, which is the honest argument for looking at the UI
        // as well as testing it.
        //
        // The canvas caption travels the same way: what drawBook names hoverable comes back up so
        // `render` can draw it at TOOLTIP_Z, where it beats depth-writing pictures by depth rather
        // than by order. See CanvasCaption.
        return captioned;
    }
    /**
     * One row's label, drawn where its slot is.
     *
     * <p>Centred in the row and truncated to the row's own width, with a small pad either side. Every row
     * of the party panel goes through here, so a title and an action label cannot disagree about where a
     * line of text sits in its row.
     */
    private void rowLabel(GuiRenderer r, Slot onScreen, String text, int colour) {
        int textY = onScreen.y() + (onScreen.height() - r.lineHeight()) / 2;
        r.text(Measure.truncate(text, Math.max(0, onScreen.width() - 8), textMeasure(r)),
                onScreen.x() + 4, textY, colour);
    }

    /**
     * The same, for a row looked up by its key in the panel's own layout.
     *
     * <p>Rows past the body are clipped by the caller's scissor rather than skipped here, because a
     * scrolled row is partly visible: a test that only asked "is it past the bottom" could not describe
     * one. A key the layout does not hold draws nothing, as before.
     */
    private void rowLabel(GuiRenderer r, Layout layout, String key, Viewport body, String text,
                          int colour) {
        Slot slot = layout.slot(key);
        if (slot == null) {
            return;
        }
        rowLabel(r, BookQuestEditor.screenSlot(body, slot), text, colour);
    }

    private BookCanvas.CanvasCaption drawBook(GuiRenderer r, int mouseX, int mouseY, long now) {
        // The server's own answer for a moved node arrives with a tree, and that is the moment the editor's
        // remembered position is no longer needed. Noticed here rather than in a handler because this is a
        // comparison of two numbers on the frame path that already reads them.
        long revision = ClientQuestCache.treeRevision();
        editors.onRevision(revision);
        // The settings page's pending values expire on the same signal: the tree arriving is what makes
        // the file's own answer the current one, and the draft is only there to cover the round trip.
        settingsDraft.onRevision(revision);
        // A drag outlives revisions: what the pointer is showing is the author's current ask, so the
        // expiry must not snap it back mid-gesture — the release commits the dragged value.
        bookRewards.stampDragging(revision);
        // The card fields' drafts expire on the copy instead, because these values are drawn from the
        // replica and the replica lags: a draft is dropped when the copy holds what was asked for, and
        // a copy that disagrees past the backstop wins. See FieldDraft for why the two rules differ.
        ClientChapterReplica.Copy copy = ClientChapterReplica.of(effectiveChapter());
        fieldDraft.reconcile(effectiveChapter(),
                copy == null ? null : copy.revision(),
                (owner, path) -> {
                    if (dev.ellipog.tenet.client.dev.FieldDraft.BOOK_OWNER.equals(owner)) {
                        // The book's values are in no chapter copy: the tree is their only source, so
                        // the tree's own value is what a book draft converges against. Empty means the
                        // key is absent, which is the null a cleared draft is waiting for. Raw, not
                        // resolved: a translation written back would replace the author's own words.
                        String value = "bookTitle".equals(path) ? ClientQuestCache.bookTitleRaw()
                                : "bookIcon".equals(path) ? ClientQuestCache.bookIconId() : null;
                        if (value == null) {
                            return null;
                        }
                        return value.isEmpty() ? com.google.gson.JsonNull.INSTANCE
                                : new JsonPrimitive(value);
                    }
                    JsonObject tree = copy == null ? null
                            : dev.ellipog.tenet.client.dev.FieldDraft.CHAPTER_OWNER.equals(owner)
                                    ? copy.chapterTree()
                                    : owner.startsWith(ELEMENT_OWNER_PREFIX)
                                            // An element's values are in the chapter's own copy, because the
                                            // element is a member of that file. Absent means the element is
                                            // gone -- deleted, or the id renamed -- which is the null a cleared
                                            // draft is waiting for.
                                            ? ChapterPanelLayout.elementById(copy.chapterTree(),
                                                    owner.substring(ELEMENT_OWNER_PREFIX.length()))
                                            : copy.quests().get(owner);
                    return tree == null ? null : QuestPanelLayout.get(tree, path);
                },
                Util.getMillis());
        // And the shape draft, against the same revision: an element the tree now holds is one whose insert
        // arrived, and one it no longer holds is a removal that did. See `ElementDraft` for why that exact
        // agreement needs no revision number, and why the backstop is what ends a belief the server renamed.
        elementDraft.reconcile(effectiveChapter(), ClientQuestCache.elements(effectiveChapter()),
                Util.getMillis());
        // And the links' shape draft, against the same revision and for the same reason: a marker the
        // tree now holds is one whose insert arrived, and one it no longer holds is a removal that did.
        linkDraft.reconcile(effectiveChapter(), ClientQuestCache.links(effectiveChapter()),
                Util.getMillis());
        // A pending look is spent once the draft behind it is: the preview and the write carry the same
        // patch, so a pending patch that outlived its draft would mask whatever changed the chapter next.
        if (pendingChapterTheme != null
                && fieldDraft.value(effectiveChapter(),
                        dev.ellipog.tenet.client.dev.FieldDraft.CHAPTER_OWNER, "themePatch") == null) {
            pendingChapterTheme = null;
        }
        // And the selection, because an id this tree does not hold is a phantom the next gesture would
        // act on -- see `pruneSelection` for why the revision is the right moment and the only one.
        BookCanvas.pruneSelection(revision);
        // And the same revision decides whether a panel's copy of the chapter is current. `claim` is what
        // keeps this from being one request per frame: it says yes only while no usable copy exists, and
        // no faster than its retry window -- so a refusal or an empty answer is retried rather than
        // leaving the panel stuck, without turning a broken chapter into a request flood.
        if (mayEditNow() && ClientChapterReplica.claim(effectiveChapter(), revision, Util.getMillis())
                && ClientEditReplies.noteSent(REPLICA_SENTINEL) != null) {
            TenetNetworking.requestReplica(effectiveChapter());
        }

        int left = panelLeft();
        int top = panelTop();
        int panelW = panelWidth();
        int panelH = panelHeight();

        ArmatureTheme.panel(r, left, top, panelW, panelH, ArmatureTheme.panel(), ArmatureTheme.panelEdge());

        // The two surfaces inside the panel, and both of them are now rounded on the corners they share
        // with it. Their rounds are one pixel tighter than the panel's, which is what keeps the gap
        // between the two curves even: reusing the panel's radius would make the panel's border thicker
        // at the corners than along its sides.
        //
        // The masks are the part that has to be right, and each says which corners the surface is
        // actually touching. The sidebar runs the full height of the left edge, so it rounds its two
        // left corners and leaves the right-hand pair square -- its right edge is interior, and rounding
        // it would leave two notches in the middle of the header strip. The header starts where the
        // sidebar ends and runs to the right edge, so it rounds its top-right corner and nothing else.
        // The author's band is under the header and touches nothing, so it rounds nothing.
        //
        // Getting a mask wrong is visible and diagnosable rather than subtle, which is the whole reason
        // the mask is a value at the call site instead of a guess inside `fillSurface`.
        int innerRadius = Math.max(0, ArmatureTheme.current().cornerRadius() - 1);
        // The wipe the header travels on when the list is docked: read here because the header
        // starts past the list. The list's own surface is NOT drawn here — it lives in the chrome
        // layer (`drawSidebarChrome`), because the canvas is drawn after this method and fills its
        // whole rect: a surface drawn here would be buried under the graph the moment the list
        // floats or slides, which is exactly the "no background" fault that moved it. Rails moved
        // for the same reason; see the chrome layer's own note.
        SidebarState sidebarShown = geometry().sidebarState();
        boolean sidebarTall = !sidebarShown.floating();
        // The wipe the header travels on when the list is docked: read here because the header
        // starts past the list.
        int sidebarWipeWidth = sidebarSliding(now) ? sidebarWipeRect(now).width()
                : geometry().sidebar().width();
        // The header beside (or above) it: docked, it starts past the list with a square left edge
        // against the travelling surface; floating, it spans the panel with both top corners round.
        int headerLeft = sidebarTall ? left + sidebarWipeWidth : left + 1;
        int headerWidth = Math.max(0, left + panelW - 1 - headerLeft);
        ArmatureTheme.fillSurface(r, headerLeft, top + 1, headerWidth,
                HEADER_HEIGHT - 2, ArmatureTheme.raised(), innerRadius,
                sidebarTall ? ArmatureTheme.TOP_RIGHT : ArmatureTheme.CORNERS_TOP);
        r.fill(left + 1, top + HEADER_HEIGHT - 1, left + panelW - 1, top + HEADER_HEIGHT,
                ArmatureTheme.panelEdge());
        // The author's band, which is the second chrome strip: the same raised surface as the header, the
        // same rule under it, and no round corners because it is interior on all four sides. It is drawn
        // over the sidebar's recess, which is what makes the recess read as starting below it.
        //
        // Full width, and that is the point of the band rather than a detail: it is the panel's chrome, so
        // it spans the sidebar and the canvas both, and no rail can reach it -- every rail is anchored to
        // the canvas, whose top edge is the band's bottom. The buttons on it are built by
        // `buildHeaderChrome` and painted by the chrome layer, because the widget pass's clip starts below
        // this.
        int bodyTop = geometry().bodyTop();
        if (bodyTop > top + HEADER_HEIGHT) {
            ArmatureTheme.fillSurface(r, left + 1, top + HEADER_HEIGHT, panelW - 2,
                    bodyTop - (top + HEADER_HEIGHT) - 1, ArmatureTheme.raised(), innerRadius,
                    ArmatureTheme.CORNERS_NONE);
            r.fill(left + 1, bodyTop - 1, left + panelW - 1, bodyTop, ArmatureTheme.panelEdge());
        }
        // A divider between the sidebar and everything else is drawn with the list's own surface
        // in the chrome layer (`drawSidebarChrome`): down here the canvas fill would bury it, which
        // is why the old one never showed. The band's rule above stays: it is above the canvas, so
        // this method's own fills still reach it.

        // The sidebar's surface, divider, grip and scrollbar all live in the chrome layer now
        // (`drawSidebarChrome`): the canvas is drawn after this method and fills its whole rect, so
        // anything of the list's drawn here would be buried under the graph the moment the list
        // floats or slides. See that method for the full reason.

        // The party strip, drawn here beside the sidebar's scrollbar because it is chrome rather than
        // content: it describes the player's own situation, not the questline on screen, so no chapter's
        // theme may reach it. Drawing it in the book's pass is what keeps it outside every scope.
        // The party button's tooltip, refreshed here, because that is where the roster's state goes now:
        // the label says "Party" and the tooltip says who is in it. A tooltip the drawing rebuilds cannot
        // go stale, which is the one thing a label set at construction can do.
        if (partyButton != null) {
            partyButton.tooltip(partyTooltip());
        }

        // The header's own inset, shared with Close's rectangle -- see `BookGeometry.HEADER_INSET`,
        // where the two ends of the header are one number rather than a literal here and a margin there.
        // The pack's icon and name, when its index declares them; the translatable title and no icon
        // otherwise. See `drawHeaderIdentity`.
        drawHeaderIdentity(r, left, top);
        // Which chapter is showing, read before the empty states because one of them is about it: "every
        // chapter is withheld from you" is not "this server has no quests", and it is the sentence a
        // reader of a pack that hides its content behind prerequisites actually sees.
        String chapter = effectiveChapter();
        if (ClientQuestCache.hasData() && chapter != null) {
            drawHeaderSummary(r, left, top);
        }

        if (!ClientQuestCache.hasData() || chapter == null) {
            // Three empty states, and each is a different thing to do about it. Saying "waiting" when it
            // is really "nothing loaded" sends someone hunting a sync bug that does not exist; saying
            // "nothing loaded" when the pack is simply holding everything back sends them to the server
            // log for a fault that is not there either. The count in the header is deliberately not drawn
            // in the third case, because "0" beside "nothing is visible yet" is two answers to one
            // question -- see `drawHeaderSummary`.
            String key = !ClientQuestCache.hasData()
                    ? (ClientQuestCache.hasTree()
                            ? "tenet.screen.quest_book.no_quests"
                            : "tenet.screen.quest_book.waiting")
                    : "tenet.screen.quest_book.nothing_visible";
            Component message = Component.translatable(key);
            // Centred on the graph as drawn — full-body when the list floats or has slid away, beside
            // it when docked — rather than on an assumed 156-pixel column.
            BookGeometry.Rect emptyCanvas = geometry().canvas();
            r.centredText(message.getString(), emptyCanvas.x() + emptyCanvas.width() / 2,
                    top + panelH / 2, ArmatureTheme.body());
            return null;
        }

        // **Skipped for a modal, and drawn beside a docked column**, which is the whole of the difference
        // between the two presentations and the fault this condition used to cause.
        //
        // A card wants the graph gone, for two reasons that are both about the card: it is drawn above the
        // canvas so a graph behind it is invisible anyway, and a graph *moving under a scrim* is a
        // distraction with no reader. A column wants the opposite -- it sits beside the graph, and the graph
        // beside it is the thing the mode exists to keep usable, so hiding it leaves an empty field with a
        // view cluster floating in it.
        //
        // The depth worry that kept this closed does not apply to a column either: the canvas's item icons
        // are 3D renders that write depth 150 above their pose, and the column is drawn at `CHROME_Z` (400),
        // which clears them by the arithmetic that constant already documents. The sidebar and the header
        // stay in both arrangements, because those are what "have it in the background" is about: where you
        // are, not what you were looking at. Null unless the canvas below names something hoverable.
        BookCanvas.CanvasCaption captioned = null;
        if (chapter != null) {
            captioned = bookCanvas.drawCanvas(r, mouseX, mouseY, questsIn(chapter), now);
        }

        // The canvas is done, and the view cluster's backing panel is *not* drawn here any more.
        //
        // It used to be, with a `flush()` in front of it and a comment claiming the flush was what put
        // the panel over the nodes. That comment was wrong and the flush was not the fix: a node's icon
        // is at Z=150 and writes depth, so a panel drawn after it at Z=0 is rejected by the depth test
        // whatever the draw order is. The panel now lives in `render`, inside the raised chrome Z, which
        // is where the ordering it actually needs can be expressed. See CHROME_Z.
        //
        // The flush stays, because draining the canvas here is still right: it puts the nodes on screen
        // before the chrome layer is queued, so the two are in separate batches and nothing about the
        // chrome depends on how the canvas happened to batch.
        r.flush();
        // No canvas, no caption: the modal and the empty states above name nothing hoverable.
        return captioned;
    }

    /**
     * Draws the hovered control's tooltip, last of all.
     *
     * <p>Not vanilla's tooltip: that would draw in vanilla's style, which is the thing this UI avoids.
     * Drawn here, after the widgets, so it is over everything and not clipped by the canvas scissor.
     */
    /**
     * A hovered link's target, in the same box a control's tooltip uses.
     *
     * <p>Because a link's whole affordance is an underline and a change of ink: without the address, the
     * reader knows something is clickable and not where it goes, and the address is the one thing a reader
     * of a link wants before pressing it.
     */
    private void drawLinkTarget(GuiRenderer r, int mouseX, int mouseY) {
        for (LinkRect link : linkRects) {
            if (link.box().contains(mouseX, mouseY)) {
                if (link.quest().isEmpty()) {
                    drawTooltip(r, List.of(link.url()), mouseX, mouseY);
                }
                else {
                    // The quest's own title, where there is one: the reader wants where it goes.
                    ClientQuestCache.Entry target = entryFor(link.quest());
                    drawTooltip(r, List.of(target == null ? link.quest() : target.titleText()),
                            mouseX, mouseY);
                }
                return;
            }
        }
    }

    /**
     * The type name beside a hovered icon, drawn with the tooltips rather than with the panel.
     *
     * <h2>Why "last in the panel" was not last enough</h2>
     *
     * <p>These were the last thing the panel drew, and that was not enough: an <b>item's</b> own render
     * writes depth, so a box drawn *after* an icon at the *same* Z can still lose to it -- and the report
     * was exactly that, *"items render over tooltips"*. A panel's contents are at {@code CHROME_Z}; what
     * belongs over everything is drawn two levels above it, which is where the tooltips already were and
     * where this now goes (see the call site in {@code render}).
     *
     * <p>The anchors are in screen coordinates and are recorded while the panel is drawn, so the only
     * thing left to do here is place the box: it opens under the control it names, moves when there is no
     * room below, and is folded and clamped by the toolkit, so the panel's own edges -- and the window's,
     * which the panel's are not always -- are answered in one place rather than eleven.
     */
    private void drawPendingLabels(GuiRenderer r) {
        for (PendingLabel label : pendingLabels) {
            BookGeometry.Rect anchor = label.anchor();
            // The toolkit's box, the same one a pointer tooltip gets: the theme's three tooltip tokens,
            // the fold, the move to a side that fits and the clamp -- so a caption at an icon by the
            // window's edge repositions rather than leaves, and a long id folds rather than runs off.
            Tooltips.drawAt(r, List.of(label.text()), anchor.x(), anchor.y(), anchor.width(),
                    anchor.height(), width, height);
        }
    }

    /**
     * The type's own explanation, under a hovered task or reward row.
     *
     * <h2>What the row cannot say, and what the sentence therefore leaves out</h2>
     *
     * <p>A row is one sentence: "Kill Zombie", "Grant the stage my_pack:inducted". That is the right
     * length for a list, and it is not enough to <i>act</i> on -- the reader who has never met the type
     * cannot tell a stage from a statistic from a command, and the row has no room to say. The tooltip
     * is where that answer goes: the type's name, what it is for, the fields it takes, and the id a
     * file spells.
     *
     * <p>Collected while the card draws and painted here, with the tooltips -- see
     * {@code rowTooltips} for why the card cannot draw it where the row is.
     */
    private void drawRowTooltips(GuiRenderer r, int mouseX, int mouseY) {
        for (RowTooltip tooltip : rowTooltips) {
            if (tooltip.box().contains(mouseX, mouseY)) {
                drawTooltip(r, tooltip.lines(), mouseX, mouseY);
                return;
            }
        }
    }

    public void drawTooltips(GuiRenderer r, int mouseX, int mouseY) {
        // **Every control is live, so every control's tooltip belongs here.** This used to start the walk
        // past the book's own controls while a modal was up, because they were drawn behind a scrim and
        // must not answer a hover. Nothing is behind anything now: a rail covers no control, and the
        // sidebar, the header and the view cluster are live whatever is open -- so skipping them would be
        // those three losing their tooltips for no reason at all.
        //
        // The hovered element's title comes first, and the order is the point: the pointer is over the canvas
        // rather than over a control, and a title suppressed by a button that happened to sit above the canvas
        // would be a tooltip that came and went. Only a title the author did not paint into the picture gets
        // one -- see `hoveredElementTitle`.
        String elementTitle = bookCanvas.hoveredElementTitle();
        if (elementTitle != null) {
            drawTooltip(r, List.of(elementTitle), mouseX, mouseY);
            return;
        }
        // The floating box's pin dot is chrome rather than a widget, so it owns no tooltip of
        // its own: one short line here, under the same hover the press answers, saying what the
        // press does.
        if (geometry().sidebarState().floating() && geometry().sidebarState().expanded()
                && geometry().sidebarBoxPin().contains(mouseX, mouseY)) {
            drawTooltip(r, List.of(Component.translatable(DevMode.isSidebarPinned()
                    ? "tenet.screen.sidebar_unpin" : "tenet.screen.sidebar_pin").getString()),
                    mouseX, mouseY);
            return;
        }
        for (ArmatureButton button : buttons) {
            if (button.tooltip() != null && button.isMouseOver(mouseX, mouseY)) {
                drawTooltip(r, button.tooltip(), mouseX, mouseY);
                return;
            }
        }
    }

    /**
     * A themed tooltip box.
     *
     * <p>Hand-drawn for the same reason the buttons are: vanilla's renderer paints its own frame and
     * background, and a tooltip is the most visible piece of chrome a screen has. Flipped to the other
     * side of the pointer when it would run off the right or the bottom, which is the one piece of
     * behaviour worth copying from vanilla's positioner.
     *
     * <p>Lines are plain strings. They were {@code FormattedCharSequence} until the seam landed, which
     * is a shape that cannot cross it without dragging {@code Style} along — and every tooltip in both
     * mods is built from {@code Component.literal}, so there was no style to lose. See
     * {@code ArmatureButton.tooltip} for the same note on the other side of the call.
     */
    /**
     * The box near the pointer, from the toolkit: the theme's tooltip tokens and the flip geometry
     * every screen shares. The wrapper stays because the call sites read better with the screen's own
     * size folded in.
     */
    private void drawTooltip(GuiRenderer r, List<String> lines, int mouseX, int mouseY) {
        Tooltips.draw(r, lines, mouseX, mouseY, width, height);
    }

    // ------------------------------------------------------------------
    // The canvas
    // ------------------------------------------------------------------

    // `overlapsAnotherNode` used to live here: a scan of every node, per label, recomputing each
    // candidate's size and screen position inside the loop. It is `LabelOverlap` now — one set of boxes
    // for the frame and a window search rather than a full scan — which is also what makes the rule
    // assertable without a canvas: `LabelOverlapTest` sweeps it against the scan it replaced.

    // trimToWidth(Font, String, int) used to live here, and it is now `Measure.truncate`.
    //
    // The move is worth a note because of what it was before *that*: the version before this one
    // divided the node size by the zoom and used the result as a character count, which has nothing to
    // do with how wide text is. Not imprecise -- the wrong kind of quantity. Then it became a wrapper
    // around `Font.plainSubstrByWidth`, which was right and lived in a screen, so a control that needed
    // the same thing grew its own copy.
    //
    // It is in the kit now, beside `TextWrap`, where both rules that answer "what fits in this width"
    // sit together -- one breaking a paragraph, one cutting a line. Neither needs a renderer: both take
    // a `Measure`, and `Measure.of(renderer::textWidth, renderer.lineHeight())` is how a caller with a
    // renderer gets one.

    /**
     * The style in force for one line: its own override, else the chapter's, else the built-ins.
     *
     * <p>One expression, and the same one the hover and the hit test use — a line drawn under one style
     * and hit-tested under another is a line whose pixels do not answer the pointer that can see them.
     *
     * <p>A pending write for this line's map wins over the tree, for the same reason the menu reads
     * through {@link BookRewards#dependencyLinesOf}: with a handle write still in flight the tree is the past, and
     * the menu would offer "Split handles" on a line the author just joined — and seed a split from the
     * stale bend. The synced tree stays the base otherwise: the canvas draws the tree, and the replica
     * can be older than it.
     */
    static DependencyStyle lineStyle(ClientQuestCache.Entry entry, String dependencyId) {
        JsonElement drafted = fieldDraft.value(entry.chapterId(), entry.id(), "dependencyLines");
        DependencyStyle own = drafted != null && drafted.isJsonObject()
                ? DependencyStyle.from(drafted.getAsJsonObject().get(dependencyId))
                : entry.dependencyLines().getOrDefault(dependencyId, DependencyStyle.UNSET);
        return own.over(entry.chapterDependencyStyle()).resolved();
    }

    /**
     * One line the chapter draws this frame: the two quests it joins, the style it is owed, and its route
     * in screen space — computed once, then read by the drawing, the hover and the handle layer.
     *
     * <h2>Why this keeps its rectangles rather than a record</h2>
     *
     * <p>Because the memo that holds them is keyed on the <b>route</b>, and the route a caller here has is
     * absolute while the route the memo wants is relative to the line's own first point. So every call paid
     * for the key before it could be told the answer was known: {@code LineArt.relative} builds a list and
     * one {@code Point} per point of the route, {@code List.copyOf} copies it again, and the key's
     * {@code hashCode} walks all of it — per edge, per frame, for a route that had not moved. The walk and
     * the stroke were saved; the price of asking was not.
     *
     * <p>An edge already lives exactly as long as its route does — {@code stampCanvas} builds one list of
     * these per canvas and reuses it until something the canvas depends on moves — so it is the right place
     * to keep the answer. Asking once is then once per <i>canvas</i> rather than once per frame, and a still
     * canvas re-issues rectangles and does no geometry at all.
     *
     * <p>Not a record, because a record's fields are final and this is computed on first use.
     */
    static final class FrameEdge {

        private final String fromId;
        private final String toId;
        private final ClientQuestCache.Entry from;
        private final ClientQuestCache.Entry to;
        private final DependencyStyle style;
        private final LineArt.Point endFrom;
        private final LineArt.Point endTo;
        private final int reachLeft;
        private final int reachTop;
        private final int reachRight;
        private final int reachBottom;
        private final boolean stamped;
        private final int baseColour;

        private List<LineArt.Point> stampPath;
        private int shiftedDx;
        private int shiftedDy;
        private List<LineArt.Point> shiftedPath;

        /** Null until the first draw asks; see the class note. */
        private LineArt.EdgeGeometry geometry;

        FrameEdge(String fromId, String toId, ClientQuestCache.Entry from, ClientQuestCache.Entry to,
                  DependencyStyle style, LineArt.Point endFrom, LineArt.Point endTo,
                  int reachLeft, int reachTop, int reachRight, int reachBottom,
                  boolean stamped, int baseColour) {
            this.fromId = fromId;
            this.toId = toId;
            this.from = from;
            this.to = to;
            this.style = style;
            this.endFrom = endFrom;
            this.endTo = endTo;
            this.reachLeft = reachLeft;
            this.reachTop = reachTop;
            this.reachRight = reachRight;
            this.reachBottom = reachBottom;
            this.stamped = stamped;
            this.baseColour = baseColour;
        }

        String fromId() {
            return fromId;
        }

        String toId() {
            return toId;
        }

        ClientQuestCache.Entry from() {
            return from;
        }

        ClientQuestCache.Entry to() {
            return to;
        }

        DependencyStyle style() {
            return style;
        }

        private List<LineArt.Point> stampPath() {
            List<LineArt.Point> held = stampPath;
            if (held == null) {
                held = stylePath(style, endFrom, endTo);
                stampPath = held;
            }
            return held;
        }

        boolean reaches(int panDx, int panDy, int left, int top, int right, int bottom) {
            int dx = stamped ? panDx : 0;
            int dy = stamped ? panDy : 0;
            return reachLeft + dx < right && reachRight + dx > left
                    && reachTop + dy < bottom && reachBottom + dy > top;
        }

        LineArt.Point origin(int panDx, int panDy) {
            List<LineArt.Point> base = stampPath();
            LineArt.Point first = base.get(0);
            if (!stamped || (panDx == 0 && panDy == 0)) {
                return first;
            }
            return new LineArt.Point(first.x() + panDx, first.y() + panDy);
        }

        List<LineArt.Point> path(int panDx, int panDy) {
            List<LineArt.Point> base = stampPath();
            if (!stamped || (panDx == 0 && panDy == 0)) {
                return base;
            }
            if (shiftedPath != null && shiftedDx == panDx && shiftedDy == panDy) {
                return shiftedPath;
            }
            List<LineArt.Point> shifted = new ArrayList<>(base.size());
            for (LineArt.Point p : base) {
                shifted.add(new LineArt.Point(p.x() + panDx, p.y() + panDy));
            }
            shiftedDx = panDx;
            shiftedDy = panDy;
            shiftedPath = shifted;
            return shifted;
        }

        int baseColour() {
            return baseColour;
        }

        /**
         * The route's ink and heads, measured from the route's own first point, worked out once.
         *
         * <p>The style cannot change under a built edge — {@code stampCanvas} keys on the tree, the draft
         * and the theme, and the style is a function of all three — so there is nothing to invalidate.
         *
         * <p><b>With one exception, and it is the one this note got wrong.</b> While a hand is bending a
         * line the style <i>does</i> change under it: {@code styleFor} lays the live preview over the
         * committed style, and a preview is not a tree, a draft or a theme, so the key does not move. That
         * is why the line used to sit still until the release. It is handled by rebuilding that one edge
         * per frame rather than by patching this one — see {@link BookCanvas#liveBendEdge} — so the stamped edge's
         * geometry here is simply the committed one and is not what gets drawn.
         */
        LineArt.EdgeGeometry geometry() {
            LineArt.EdgeGeometry held = geometry;
            if (held == null) {
                held = LineArt.geometry(stampPath(), style, LineArt.ArrowSpec.of(style));
                geometry = held;
            }
            return held;
        }

        /** The key the hover and the hit test use: the dependency first, the dependent second. */
        String[] key() {
            return new String[] { fromId, toId };
        }

        /**
         * Whether this is the line between these two quests: the dependency first, the dependent second.
         *
         * <p>The same order {@link #key} uses, and here rather than written out at each call site because
         * there are now three of them and they must agree: the handle layer asks it of every edge every
         * frame, and the live bend asks it twice to find the one line a hand is holding. The handle layer
         * still builds {@link #key} beside it, because {@code showsHandles} keeps that array as the
         * identity of the line it has revealed — so this is not a saved allocation there, it is one
         * question with one answer. The new callers are the ones that would otherwise allocate.
         */
        boolean matches(String from, String to) {
            return fromId.equals(from) && toId.equals(to);
        }
    }

    /**
     * One node ready to draw: everything {@code drawNode} recomputed per frame, worked
     * out once per stamp.
     *
     * <h2>Why a record of values rather than the entry</h2>
     *
     * <p>Because the draw asked the same questions sixty times a second: a draft lookup
     * and a viewport mapping for the position and size, a dependency walk for the shown
     * state, a task walk for the adopted icon, a draft read each for the shape, the
     * rotation and the icon scale. None of those answers moves without the stamp moving
     * — positions ride the viewport and the drag fields, states ride the tree, the
     * progress and the draft, icons ride the tree — and the stamp's key already names
     * every one of them, which is what makes the key complete rather than hopeful.
     *
     * <p>What is deliberately <b>not</b> here: the ring (hover eases per frame, selection
     * and flash are presses rather than revisions), the LOD answer (one map lookup per
     * node per frame, which is what the chapter index is for), and the hidden mark's
     * visibility question (a cached map lookup and a mode flag, both cheaper than the
     * record they would have to ride in on).
     */
    record FrameNode(ClientQuestCache.Entry entry, String id, String chapterId, int x, int y,
                             int size, QuestState state, QuestShape shape,
                             dev.ellipog.armature.client.ui.shape.Shape geometry,
                             ItemStack icon, String texture, String sprite, double iconScale,
                             int edge, int wash, boolean hideLock) {

        /** The same node with a new icon: the cycling-icon refresh below. */
        FrameNode withIcon(ItemStack icon) {
            return new FrameNode(entry, id, chapterId, x, y, size, state, shape, geometry, icon,
                    texture, sprite, iconScale, edge, wash, hideLock);
        }
    }

    /**
     * One canvas element as this canvas draws it: the element, its measured box, and whether it is hidden.
     *
     * <h2>Why the box is cached and the look is not</h2>
     *
     * <p>The box is <b>measured</b> — a label's is its own text, through the renderer — and a press arrives
     * outside the draw, where there is no renderer at all. So it is worked out once per stamp, where the
     * renderer is in hand, and the press path asks the box it already has. The <i>look</i> is the opposite
     * kind of thing: hover eases and a selection changes on a press, so caching it would freeze the cue it
     * describes.
     *
     * <p>{@code marked} is the third kind: a reader would not see this element and an author is seeing it
     * anyway, which is a fact about the element and the mode rather than about the pointer — so it is part of
     * what a stamp produces, like the box.
     */
    record ElementSlot(CanvasElement element, CanvasElementArt.Box box, boolean marked) {

        ElementSlot shifted(int dx, int dy) {
            if (dx == 0 && dy == 0) {
                return this;
            }
            return new ElementSlot(element,
                    new CanvasElementArt.Box(box.left() + dx, box.top() + dy,
                            box.right() + dx, box.bottom() + dy),
                    marked);
        }

        boolean overlaps(int dx, int dy, int left, int top, int right, int bottom) {
            CanvasElementArt.Box shiftedBox = dx == 0 && dy == 0 ? box
                    : new CanvasElementArt.Box(box.left() + dx, box.top() + dy,
                            box.right() + dx, box.bottom() + dy);
            CanvasElementArt.Box bounds = CanvasElementArt.turnedBounds(element, shiftedBox);
            return bounds.left() < right && bounds.right() > left
                    && bounds.top() < bottom && bounds.bottom() > top;
        }
    }

    /**
     * The element the author has selected, or empty.
     *
     * <p>Deliberately <b>not</b> folded into {@link #multiSelection}: element ids and quest ids are different
     * namespaces, and every bulk operation would otherwise have to ask which kind each id is. One element at a
     * time is also the honest shape of the gesture — an author moves a box or a logo, and the multi-select
     * exists for dragging a group of <i>quests</i> together. See the chapter tab's element list for the way to
     * reach an element the canvas cannot draw.
     */
    static String selectedElement;

    /**
     * Which element the texture picker is choosing a file for, or null for the canvas's own surface.
     *
     * <p>Set by {@code openTexturePickerFor} and read by the list and the commit, so one picker serves both
     * without either guessing: these are different fields on different objects -- a canvas background's
     * {@code image} and an element's -- and a picker that could not tell them apart would write one from the
     * other's list.
     */
    String texturePickElement;

    /**
     * The element under the pointer, or null.
     *
     * <h2>The drawn order, walked backwards</h2>
     *
     * <p>{@code canvasElements} is in draw order, so the last one is on top — and the element an author can see
     * on top is the one a press must belong to. Walking the declaration order instead would pick a different
     * element from the one under the pointer, which is a fault with no visible cause: both orders are
     * plausible, and the file's own order is the one nobody can see.
     *
     * <p>The reach is a line's own tolerance and nothing for the rest: a box or a picture is a rectangle, and a
     * press two pixels outside it is not a press on it. A line is the one arm whose shape is not its box, so it
     * is the one that needs a tolerance — the same one the dependency lines' hover uses.
     */
    String elementAt(double mouseX, double mouseY) {
        int dx = bookCanvas.panDx();
        int dy = bookCanvas.panDy();
        for (int i = bookCanvas.canvasElements.size() - 1; i >= 0; i--) {
            ElementSlot slot = bookCanvas.canvasElements.get(i).shifted(dx, dy);
            double reach = slot.element() instanceof CanvasElement.Line ? BookRewards.ELEMENT_LINE_HIT : 0;
            if (CanvasElementArt.distanceTo(slot.element(), slot.box(), viewport(), mouseX, mouseY) <= reach) {
                return slot.element().id();
            }
        }
        return null;
    }

    /** The slot with this id, or null. */
    ElementSlot elementSlot(String id) {
        if (id == null) {
            return null;
        }
        int dx = bookCanvas.panDx();
        int dy = bookCanvas.panDy();
        for (ElementSlot slot : bookCanvas.canvasElements) {
            if (slot.element().id().equals(id)) {
                return slot.shifted(dx, dy);
            }
        }
        return null;
    }

    /**
     * Where a line starts and ends: each node's rim in the other's direction, or this line's own anchor.
     *
     * <p>Automatic by default and exact for the shape, because {@link LineArt#rimPoint} asks the same
     * containment test the clicks and the hover use. An anchor is the fussy override: a line can name an
     * angle for either end, and the other end stays automatic.
     */
    LineArt.Point[] lineEnds(ClientQuestCache.Entry from, ClientQuestCache.Entry to,
                                     DependencyStyle style) {
        LineArt.Point fromCentre = new LineArt.Point(bookRewards.nodeCentreX(from), bookRewards.nodeCentreY(from));
        LineArt.Point toCentre = new LineArt.Point(bookRewards.nodeCentreX(to), bookRewards.nodeCentreY(to));
        return new LineArt.Point[] {
                bookCanvas.endPoint(from, fromCentre, toCentre, style.fromAnchorOrNull()),
                bookCanvas.endPoint(to, toCentre, fromCentre, style.toAnchorOrNull()) };
    }

    /**
     * The route a style asks for: a cubic when the line is split, else the form's own path.
     *
     * <p>One function, so the drawing, the hover and the hit test cannot disagree about which shape a
     * split line is — the same rule the rest of {@link LineArt} exists to keep.
     */
    static List<LineArt.Point> stylePath(DependencyStyle style, LineArt.Point from,
                                                 LineArt.Point to) {
        if (style.fromHandle().isPresent() && style.toHandle().isPresent()) {
            return LineArt.cubic(from, to, style.fromHandle().get(), style.toHandle().get());
        }
        return LineArt.path(style.formOr(DependencyStyle.Form.CHAMFERED), from, to,
                style.bendOr(DEFAULT_BEND));
    }

    /** Whether this line has been split into two control points. While it has, they are its shape. */
    static boolean isSplit(DependencyStyle style) {
        return style.fromHandle().isPresent() && style.toHandle().isPresent();
    }

    /** The bow a curve gets when nothing, anywhere, says otherwise. */
    static final double DEFAULT_BEND = 0.2;

    /**
     * The style a line is drawn with right now: its own, with the bend being dragged laid over it.
     *
     * <p>The preview is a screen fact, not a file one — nothing is written until the hand lets go — so it
     * belongs here rather than in {@code lineStyle}, which answers from what the server sent.
     */
    DependencyStyle styleFor(ClientQuestCache.Entry dependent, String dependencyId) {
        DependencyStyle style = lineStyle(dependent, dependencyId);
        if (bendDragFrom == null || !bendDragFrom.equals(dependencyId)
                || !bendDragTo.equals(dependent.id())) {
            return style;
        }
        java.util.Optional<Double> from = java.util.Optional.ofNullable(
                BookRewards.HANDLE_FROM.equals(bendDragKind) ? anchorPreview : style.fromAnchor().orElse(null));
        java.util.Optional<Double> to = java.util.Optional.ofNullable(
                BookRewards.HANDLE_TO.equals(bendDragKind) ? anchorPreview : style.toAnchor().orElse(null));
        java.util.Optional<java.util.List<Double>> fromHandle = style.fromHandle();
        java.util.Optional<java.util.List<Double>> toHandle = style.toHandle();
        if (handlePreview != null) {
            if (BookRewards.HANDLE_FROM_HANDLE.equals(bendDragKind)) {
                fromHandle = java.util.Optional.of(handlePreview);
            }
            else if (BookRewards.HANDLE_TO_HANDLE.equals(bendDragKind)) {
                toHandle = java.util.Optional.of(handlePreview);
            }
        }
        return new DependencyStyle(style.form(), style.arrows(), style.dash(), style.weight(),
                java.util.Optional.ofNullable(bendPreview == null ? style.bend().orElse(null) : bendPreview),
                from, to, fromHandle, toHandle,
                style.arrowHead(), style.arrowPlace(), style.arrowDensity());
    }

    /** The node under the pointer, or null. */
    ClientQuestCache.Entry nodeAt(double mouseX, double mouseY, List<ClientQuestCache.Entry> quests) {
        if (bookCanvas.canvasState != null) {
            int dx = bookCanvas.panDx();
            int dy = bookCanvas.panDy();
            for (int i = bookCanvas.frameNodes.size() - 1; i >= 0; i--) {
                FrameNode node = bookCanvas.frameNodes.get(i);
                int size = node.size();
                int x = node.x() + dx;
                int y = node.y() + dy;
                if (mouseX < x || mouseX >= x + size || mouseY < y || mouseY >= y + size) {
                    continue;
                }
                if (node.geometry().contains(mouseX, mouseY, x, y, size)) {
                    return node.entry();
                }
            }
            return null;
        }
        // Reverse order, so the node drawn last -- and therefore on top -- is the one picked. Two
        // quests may legitimately share a position, and the validator warns about it.
        for (int i = quests.size() - 1; i >= 0; i--) {
            ClientQuestCache.Entry entry = quests.get(i);
            int size = nodeSize(entry);
            int x = nodeScreenX(entry);
            int y = nodeScreenY(entry);
            // The bounding box first, and it is a **filter rather than the test**: a shape's spans never
            // leave its own square, so a pointer outside the square cannot be inside the shape. That makes
            // this free of any change in what a click does, and it skips the span lookup for every node
            // the pointer is not in — at nine hundred nodes, nine hundred integer comparisons instead of
            // nine hundred table walks. The note below is not arguing against this: the box decides
            // whether to ask, and the shape decides the answer.
            if (mouseX < x || mouseX >= x + size || mouseY < y || mouseY >= y + size) {
                continue;
            }
            // The shape's own test, not a bounding box. A circle's corners are outside it, so a
            // bounding-box hit test would let a click land on a node's transparent corner -- and with
            // two diagonal neighbours 34 pixels apart, that click belongs to neither of them.
            //
            // `contains` asks the same span table the renderer fills from, so a click lands on exactly
            // the pixels that were drawn. That is the whole reason the geometry lives on the enum.
            // The rotated geometry, so a click lands on the node as it is drawn: the same table the
            // renderer walked, which is the whole of "a click lands on exactly the pixels that were
            // drawn".
            if (entry.geometry().contains(mouseX, mouseY, x, y, size)) {
                return entry;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // The settings card
    // ------------------------------------------------------------------

    /** Whether the picker is choosing a picture: the tabs greet icon picks and no other pick. */
    boolean pickerTabsVisible() {
        return isIconPick() && !typePageOpen() && pickingItemPath != null;
    }

    /** The reader's card, as it was last built, and the key it was built from. */
    Layout readerCard;

    /**
     * The locate icon: a small diamond ring with a dot in it — "show me where this is".
     *
     * <p>Drawn from pixels rather than written as a character, for the same reason the tick and the
     * cross are chosen carefully: the font carries no crosshair, and a missing glyph is a box.
     */
    static void drawLocateIcon(GuiRenderer r, int cx, int cy, int colour) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                int distance = Math.abs(dx) + Math.abs(dy);
                if (distance == 2 || distance == 0) {
                    r.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, colour);
                }
            }
        }
    }

    /**
     * The rule a quest's prerequisites are judged by, from the replica's own fields.
     *
     * <p>One helper for the canvas and the card, because two expressions for "is this prerequisite
     * satisfied" is how the two would come to disagree -- and they did: both asked about completion,
     * which is the wrong question for half the modes.
     */
    static dev.ellipog.tenet.client.dev.DependencyProgress dependencyProgressOf(
            ClientQuestCache.Entry entry) {
        return new dev.ellipog.tenet.client.dev.DependencyProgress(entry.effectivePrerequisiteMode(),
                entry.minRequired(), dependenciesOf(entry));
    }

    /**
     * The state one quest's chrome draws as: gated visuals for a flexible quest whose rule is
     * unmet, the engine's own state everywhere else.
     *
     * <p>One helper for the node, its label, its padlock and the card's state word, because four
     * spellings of "gated" is how three of them would come to disagree. Playability never reads
     * this — submit buttons, accumulation and completion keep judging engine states — and neither
     * does the narrator, whose "worth opening" is true of a measurable quest.
     */
    static QuestState shownState(ClientQuestCache.Entry entry) {
        return dev.ellipog.tenet.client.dev.QuestVisibility.displayState(
                ClientQuestCache.stateOf(entry.id()), entry.flexible(),
                dependencyProgressOf(entry).met(ClientQuestCache::stateOf));
    }

    /** A section label and its rule, at the slot the layout reserved for it. */
    void drawHeading(GuiRenderer r, Slot slot, String label) {
        if (slot == null) {
            return;
        }
        r.text(label, slot.x(), slot.y(), ArmatureTheme.heading());
        r.fill(slot.x(), slot.y() + OverlayLayout.SECTION_LEAD, slot.x() + r.textWidth(label),
                slot.y() + OverlayLayout.SECTION_LEAD + 1, ArmatureTheme.panelEdge());
    }

    /**
     * A key's slot, moved onto the screen, or null if it was not placed or is scrolled out of view.
     *
     * <p>Null for two different reasons, and a caller treats them the same: an element this layout did
     * not place at all — a REQUIRES heading on a quest with no prerequisites — and one that is off the
     * screen right now. Both mean "do not draw it here".
     *
     * <p>This is the whole of the culling, and it is why the drawing can be written against keys: the
     * dispatch is on what an element <i>is</i>, so a row that moves when a metric changes takes its
     * drawing with it. Matching on coordinates is the version of this that leaves a working test naming
     * the wrong row.
     */
    static Slot placed(Layout layout, Viewport body, Object key) {
        Slot slot = layout.slot(key);
        if (slot == null) {
            return null;
        }
        // Wholly off screen: above the top of the view or below its bottom. A slot that is only partly
        // on screen is drawn and cut off by the clip, which is what the clip is for.
        if (slot.bottom() <= body.visibleTop() || slot.y() >= body.visibleBottom()) {
            return null;
        }
        // Moved by the viewport's own mapping, so the placement and the culling cannot disagree about
        // where a row is. The overlay's viewport is fixed-scale, so a slot's width is its screen width.
        return slot.moved(body.screenX(slot.x()) - slot.x(), body.screenY(slot.y()) - slot.y());
    }

    /**
     * The font as the overlay's layout measures it: the same face, at the line box this pane draws.
     *
     * <h2>What this used to be, and why the change matters</h2>
     *
     * <p>It was {@code FontMeasure.of(font).withLeading(...)} — a kit class whose whole job was to wrap
     * a Minecraft {@code Font} in a {@code Measure}. That class is gone, and the reason is the
     * clearest illustration of what the seam bought: a renderer <i>is</i> a measure of text. It answers
     * {@code textWidth} and {@code lineHeight} already, so the adapter that existed only to bridge a
     * font to the layout interface has nothing left to bridge.
     *
     * <p>The leading is still added, and still belongs inside the measure rather than at the drawing
     * site: this pane draws a line of body text every {@link OverlayLayout#LINE_HEIGHT} pixels, and a
     * layout measuring at the font's own height over a drawing advancing by ten is short by a line per
     * paragraph — which arrives as a scrollbar that stops early with nothing anywhere reporting it.
     *
     * <h2>One measure for the screen, and why it does not bind a renderer</h2>
     *
     * <p>This used to build a fresh measure per call, and its callers are per <i>row</i>: a truncated
     * label walks its own string a character at a time, so a canvas of labelled nodes measured
     * thousands of prefixes per frame and measured the same thousands again on the next one. The memo
     * in {@link Measure#cached} is what makes that free, and it only works if the measure <b>outlives
     * the frame</b> — which means it cannot be built from the frame's renderer.
     *
     * <p>So the measure is built once and reads whichever renderer is in force, through
     * {@link BookSettings#measuring}. That field is set on every call rather than the measure being rebuilt, which
     * is the whole of the change: the same object answers every frame, so the widths it has already
     * been asked for are still there.
     */
    Measure textMeasure(GuiRenderer r) {
        bookSettings.measuring = r;
        if (bookSettings.measure == null) {
            bookSettings.measure = Measure.cached(Measure.of(text -> bookSettings.measuring.textWidth(text), OverlayLayout.LINE_HEIGHT),
                    TextEpoch::now);
        }
        return bookSettings.measure;
    }

    // ------------------------------------------------------------------
    // Remembered rows
    // ------------------------------------------------------------------

    /**
     * Appends a locked row's explanation: the gates it is missing, by the server's own naming.
     *
     * <p>Only the unmet ones. The server sends which condition indices failed, and the tree carried what
     * those conditions say, so a row with three gates and one unmet names the one — and the list is the
     * same order the author wrote, because the mask indexes into it.
     */
    static void appendConditionLines(List<String> lines,
                                             List<ClientQuestCache.ConditionEntry> conditions,
                                             List<Integer> unmet) {
        if (unmet.isEmpty() || conditions.isEmpty()) {
            return;
        }
        lines.add("Locked - needs:");
        for (int index : unmet) {
            if (index >= 0 && index < conditions.size()) {
                lines.add("  " + conditions.get(index).line());
            }
        }
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        // The intro's buttons first, and only they: a press on Skip, Next or the checkbox is the
        // tour's, while every other press — including the press on the + Chapter strip or the
        // canvas the tour is pointing at — falls through to the book untouched. Asked before the
        // blur below, because a press on the tour is not a press away from a field.
        if (IntroGuide.isActive() && bookIntro.pressIntroGuide(mouseX, mouseY, button)) {
            return true;
        }
        // A press anywhere is "no" to an armed delete, which is the rule the menu's own Delete states: any
        // other press disarms. A press that selects a node does not need to re-arm by hand -- the next
        // Delete key press asks about the selection it made.
        armedDelete.disarm();
        fieldDrag = false;
        pressedLink = null;
        // Every gesture starts by forgetting the last one's: a press that ended over a column must not make
        // the next release look like one.
        panelPress = false;
        // A new press ends a row drag that never got its release -- one gesture at a time, and the row
        // rectangles a stale drag would aim at belong to the frame it started in.
        dragRowFrom = -1;
        dragRowMember = null;
        dragRowQuest = null;
        dragRowLive = false;

        // Clicking off a text field finishes with it: the blur is the commit, and a field that keeps
        // the keyboard after the pointer has moved on is a field that eats the next key typed. Before
        // anything else, so the press that closes one editor can also open the next.
        //
        // "Off it" is asked of the field's **frame** as well as of the widget's own box: they are the
        // same rectangle for the description (its frame *is* its box), and asking the target is what
        // keeps a press on the border or a pixel out of the widget's own hit test from being read as a
        // press away from the field.
        if (button == InputConstants.MOUSE_BUTTON_LEFT && getFocused() instanceof net.minecraft.client.gui.components.AbstractWidget focused
                && !focused.isMouseOver(mouseX, mouseY) && !editorControls.onOpenEditor(mouseX, mouseY)) {
            setFocused(null);
        }
        // **The dropdown owns the press, and it is asked before anything else that could answer one.**
        //
        // This is the rule the wheel has had since it was written -- `mouseScrolled` closes an open menu
        // before it scrolls anything -- and the press did not, and the cost was every press in the window
        // while a dropdown was open over a rail. The report is general because the cause is: *"any button
        // with a dropdown when the dropdown has buttons under it"*. Every `ChoiceField` on the dock opens
        // this one menu, and the menu lands on the rows beneath it; `pressMenu` stood below the panel
        // branch, whose widget pass, whose arms and whose pan tail all answer first -- so a press on a menu
        // row over another chooser ran *that* chooser's press and the row the player aimed at never ran.
        //
        // **Above the caret step below, and below the blur above.** A press that dismisses a dropdown is
        // still a press away from the field, so the blur commits first, which is what keeps two edits from
        // swapping places; and a press on the dropdown is not a press on the field it happens to cover, so
        // the caret step must not see it.
        if (pressDropdown(mouseX, mouseY, button)) {
            return true;
        }
        // And a press **on** the field that is already open moves the caret into it -- one gesture, one
        // path, and nothing else. Not a press "away" (the check above), and not a press that opens the
        // field again (the marked pieces further down, which would close it and make a new one).
        //
        // The report this is the answer to: *"when i click on the description box then click on it again
        // it unselects instead of just instantly moving my cursor"* -- the second click was being read as
        // a click off the field, so it committed and closed instead of putting the cursor in the word
        // that was clicked.
        if (button == InputConstants.MOUSE_BUTTON_LEFT && editorControls.onOpenEditor(mouseX, mouseY) && editorControls.moveOpenCaret(mouseX, mouseY)) {
            // And the drag that may follow this press belongs to the field: the same press, continued.
            fieldDrag = true;
            return true;
        }
        // Whether this press is the open colour picker's, wherever it floats. Rails float over the
        // canvas the picker is clamped to, so the picker can slide under one; the grip below and the
        // surface block past the widget pass are what keep that press the picker's.
        boolean onPicker = colourPopover.isOpen()
                && colourPopover.panel().contains(pickerPointerX(mouseX), mouseY);
        // **And the grip, before either column's own handling** -- one claim for every column, the author's
        // dock included.
        //
        // It used to live inside the overlay branch, which was correct while the only columns were an
        // overlay's: the dock is a column while *no* overlay is open, so a press on its edge never reached
        // that branch and the dock had no grip at all. Claimed here it can come before the widget passes,
        // because no content is ever drawn on that strip: the handle is narrower than every surface's own
        // inset (`PANEL_HANDLE < BODY_INSET`, asserted in `PanelLayoutTest`), so there is no control under it
        // to take the press first.
        //
        // Left button only, on the same terms as every other grip in this screen: the gesture is a drag and
        // a held page, and neither is a thing to hang off a right press.
        PanelKind grab = button == InputConstants.MOUSE_BUTTON_LEFT && !onPicker ? panelColumnAt(mouseX, mouseY) : null;
        if (grab != null && BookGeometry.panelHandle(panelRail(grab)).contains(mouseX, mouseY)) {
            BookGeometry.Rect rail = panelRail(grab);
            panelDragRail = rail;
            // Which rail the hand is on, so the drag below asks *its* kind what a legal width is and feeds
            // the answer back to the rail the hand is holding.
            panelDragKind = grab;
            panelDragStartWidth = rail.width();
            panelDragStartX = mouseX;
            return true;
        }
        // **And the chapter list's own grip, on its inner edge like every rail's.**
        //
        // Claimed here for the same reason the rails' is: no content is ever drawn on that strip --
        // the handle is narrower than the list's own inset, so there is no control under it to take
        // the press first. Expanded only: a strip has no width to drag, and its press brings the
        // list back instead (claimed below, past the widget pass, so a rail row still wins it).
        //
        // Left button only, on the same terms as every other grip in this screen.
        if (button == InputConstants.MOUSE_BUTTON_LEFT && !onPicker && geometry().sidebarState().expanded()
                && sidebarSlideStart < 0
                && BookGeometry.sidebarHandle(geometry().sidebar()).contains(mouseX, mouseY)) {
            sidebarDragRail = geometry().sidebar();
            sidebarDragStartWidth = geometry().sidebarWidth();
            sidebarDragStartX = mouseX;
            return true;
        }
        // **The panels first, before the widget pass**, and this is an ordering the present arrangement is
        // free to choose: every rail is drawn *before* the pass, so a rail's own rows and bars are drawn
        // targets this branch answers, while its real widgets are reached through `super.mouseClicked` a few
        // lines below. The order matters for the one case it always did -- a bar's grab band is wider than
        // the three-pixel bar, so the arm that claims it has to run before the tail that reads the press as
        // a press on the canvas.
        //
        // **And it is entered for the dock alone as well as for a panel**, because the dock is a rail: its
        // rows, bars and status line are handled by this branch's arms, and `overlay` is NONE while it is the
        // only thing open.
        if (overlay != PanelKind.NONE || overlay2 != PanelKind.NONE || dockOpen) {
            // **Which rail the pointer is in, and which kind answers.** One resolver for the press, the wheel
            // and the grip: a press outside every rail is the book's, and falling out of this branch is what
            // reaches the book's own handling below.
            //
            // `surfaceKind` is set with it, because the handlers below measure their rows and their footer
            // against the surface the four accessors answer for: a picker beside a quest has to measure itself
            // against *its* rectangle, not against the quest panel's.
            //
            // **There is no fallback here, and putting one back breaks the whole screen.**
            //
            // This is the second time this line has been wrong, so the trap is worth stating where it was
            // sprung. It used to read `if (on == null) { on = overlay; }` -- written when an overlay could be
            // a centred card, which had no rail, so `panelColumnAt` answered null for the very press the card
            // was meant to answer. It outlived the card, and the cost was every press in the window: with a
            // panel open, a click on the canvas, on the sidebar or on the header was read as a press on that
            // panel, so the tail below started a pan, set `panelPress` and nulled `pressedNode`. A canvas
            // click could no longer open a node or close the panel, and nothing below this branch -- the
            // sidebar's row drag and bar, the chapter menu, the node drag, the marquee, the edge gesture --
            // could be reached at all while a panel was open.
            //
            // **And the guard that should have caught it could not.** The tail asked
            // `presented(...).contains(on)`, which re-reads the *substituted* value, so it passed for every
            // point in the window. It was redundant even when it was right -- this resolver can only answer
            // with a presented kind, or with null -- which is why it is gone rather than restored. The fault
            // was not a missing null check here; it was this line manufacturing a kind the resolver had
            // declined to name. `.utils/check_press.py` is the mechanical guard against it coming back.
            //
            // `surfaceKind` is left as the last surface that claimed it when no rail answered, which is what
            // the field's own note says it is: nothing below reads it unless an arm runs, and no arm runs for
            // a kind that is not there.
            PanelKind on = panelColumnAt(mouseX, mouseY);
            if (on != null) {
                surfaceKind = on;
            }

            // The picker's own surface, before any rail answers. Its fields already had their press at
            // the pass above; what is left is its tracks, its swatches and its padding -- and a press
            // there is the picker's even when the panel floats over a rail that would otherwise answer
            // it. Without this a picker that has slid under a rail answers nothing: the rail's arms read
            // their own rectangles, which the picker's surface overlaps, and the pan tail answers
            // unconditionally. A press anywhere else still falls through to the arms, so switching chips
            // mid-picker and dismissing it with a press outside both work as before.

            // A description's link, before anything else the panel does with a press: a link inside a panel
            // must not be read as a press on the panel. Opened on release, so a press that turns into a drag
            // is not a click.
            if (button == InputConstants.MOUSE_BUTTON_LEFT && on == PanelKind.QUEST && !mayEditNow()) {
                for (LinkRect link : linkRects) {
                    if (link.box().contains(mouseX, mouseY)) {
                        pressedLink = link;
                        return true;
                    }
                }
            }
            // Whether this press is the armed Disband's, asked before the press is handled: a press on
            // anything else is a change of mind, and the label a player is looking at has to match what
            // the next press on Disband will do.
            boolean onDisband = disbandButton != null && disbandButton.isMouseOver(mouseX, mouseY);
            boolean onQuestDelete = questDeleteButton != null
                    && questDeleteButton.isMouseOver(mouseX, mouseY);
            if (super.mouseClicked(event, doubleClick)) {
                // One of the panel's own controls took it.
                if (!onDisband) {
                    questEditor.disarmDisband();
                }
                // The armed Delete works like the armed Disband: any other press is a change of mind,
                // and the label on screen has to match what the next press on Delete will do.
                if (!onQuestDelete && confirmingDelete) {
                    confirmingDelete = false;
                    rebuildWidgets();
                }
                return true;
            }

            // The picker's own surface, before any rail answers. Its fields already had their press at the
            // pass above; what is left is its tracks, its swatches and its padding -- and a press there is
            // the picker's even when the panel floats over a rail that would otherwise answer it. Without
            // this a picker that has slid under a rail answers nothing: the rail's arms read their own
            // rectangles, which the picker's surface overlaps, and the pan tail answers unconditionally.
            // A press anywhere else still falls through to the arms, so switching chips mid-picker and
            // dismissing it with a press outside both work as before.
            if (onPicker) {
                colourPopover.mouseClicked(BookEditorControls.shiftedClick(pickerPointerX(mouseX), mouseY, event));
                return true;
            }

            // **From here on, every arm either claims the press and returns, or falls through to the one
            // tail below.** That shape is this round's press rule, and it is a fix rather than a tidy-up:
            // the arms used to end in an unconditional `return true`, so a press that hit nothing at all
            // was *consumed* by whichever panel happened to be open. The reported case is the quest panel's
            // settings page — "i can pan while a quest is open, but if i go into settings on the quest and
            // then try to pan, i cant" — and the same hole was in the tables, the assets page, the choice
            // rows, the rewards list and the texture list.
            //
            // The bars come first in every arm that has one, and before the tail, for the reason they always
            // did: a bar's grab band is deliberately wider than the three-pixel bar, so a press one pixel off
            // it has to drag the bar rather than start a pan.
            if (on == PanelKind.PARTY && button == InputConstants.MOUSE_BUTTON_LEFT
                    && pressBar(partyScrollAt(mouseX).bar(), mouseX, mouseY)) {
                return true;
            }
            if ((on == PanelKind.TABLE_BROWSER || on == PanelKind.TABLE_EDITOR
                    || on == PanelKind.ASSETS) && button == InputConstants.MOUSE_BUTTON_LEFT) {
                ScrollBar panelBar = on == PanelKind.ASSETS ? bookAssets.assetsBar
                        : on == PanelKind.TABLE_BROWSER ? tableBrowserBar
                        : tableShowRoll ? rollBar : tableEditorBar;
                if (pressBar(panelBar, mouseX, mouseY)) {
                    return true;
                }
                // Whether a drawn target is under the pointer, asked of the same list the press methods walk
                // — so the two cannot disagree about what is on screen. It is what decides whether this arm
                // claims the press: the press methods themselves answer nothing, and a press that hit no row
                // must reach the tail rather than being swallowed by the panel it happened to be over.
                boolean onRow = on == PanelKind.ASSETS
                        ? bookInput.onAssetsTarget(mouseX, mouseY) : bookInput.onEditTarget(mouseX, mouseY);
                if (onRow) {
                    if (on == PanelKind.TABLE_BROWSER) {
                        bookRewards.pressTableBrowser(mouseX, mouseY);
                    }
                    else if (on == PanelKind.ASSETS) {
                        bookAssets.pressAssets(mouseX, mouseY);
                    }
                    else {
                        bookAssets.pressTableEditor(mouseX, mouseY);
                    }
                    return true;
                }
            }
            else if (on == PanelKind.CHOICE && button == InputConstants.MOUSE_BUTTON_LEFT) {
                // The rows are drawn by this screen -- see `drawChoiceRow` -- so the press is what answers.
                if (pressBar(choiceView.bar(), mouseX, mouseY)) {
                    return true;
                }
                for (int i = 0; i < choiceRowRects.size(); i++) {
                    if (choiceRowRects.get(i).contains(mouseX, mouseY)) {
                        if (i < choiceRows.size()) {
                            bookOverlay.pressChoiceRow(choiceRows.get(i).key());
                        }
                        return true;
                    }
                }
            }
            else if (on == PanelKind.REWARDS && button == InputConstants.MOUSE_BUTTON_LEFT) {
                // A header's body folds its rewards, and this is the press the strip's widget did not
                // take -- so a click on Claim Quest can never also fold the row it belongs to. The rows'
                // buttons are widgets and answer on release.
                if (bookRewards.toggleRewardRow(mouseX, mouseY)) {
                    return true;
                }
                // A reward's body claims it, exactly as its Claim button does -- the checkmark row's
                // rule, one menu over. Only a row whose button is live answers; anything else falls
                // through to the pan tail below, so a collected or gated row never claims or errors.
                if (bookRewards.pressRewardRow(mouseX, mouseY)) {
                    return true;
                }
                if (pressBar(rewardView.bar(), mouseX, mouseY)) {
                    return true;
                }
            }
            else if (on == PanelKind.TEXTURE && button == InputConstants.MOUSE_BUTTON_LEFT) {
                // The list's bar before the rows: it is the one control here whose target is a pixel wide
                // three times over, and a press aimed at it must not start a pan.
                if (pressBar(textureBar, mouseX, mouseY)) {
                    return true;
                }
                // From the last frame's own list, so a press lands on the row it was drawn under.
                int row = textureFrame == null ? -1
                        : ItemPickerLayout.rowAt(textureRows, textureFrame, textureBody.scrollY(), mouseY);
                if (row >= 0) {
                    texturePicker.pressTextureRow(row);
                    return true;
                }
            }
            else if (on == PanelKind.FILTER && button == InputConstants.MOUSE_BUTTON_LEFT) {
                // The accepted-items list's bar before its rows, then the rows from the last
                // frame's own drawing. A press that hits no row is not the panel's, so it reaches
                // the tail and the canvas stays pannable from over the list's gaps.
                if (pressBar(filterView.bar(), mouseX, mouseY)) {
                    return true;
                }
                if (bookSettings.onFilterListRow(mouseX, mouseY)) {
                    bookSettings.pressFilterListRow(mouseX, mouseY);
                    return true;
                }
            }
            else if ((on == PanelKind.QUEST || on == PanelKind.PICKER) && mayEditNow() && button == InputConstants.MOUSE_BUTTON_LEFT) {
                // The type page's bar, then its rows: it is the list on screen, and a press aimed at its
                // grip is the list's rather than the page's.
                if (typePageOpen()) {
                    if (pressBar(typeBar, mouseX, mouseY)) {
                        return true;
                    }
                    if (editorControls.pressTypePicker(mouseX, mouseY)) {
                        return true;
                    }
                }
                // The item picker's rows, from the last frame's own drawing -- and when the pick is a rail
                // of its own, only *that* rail takes it: a press in the panel's own rail belongs to the
                // panel, which is the whole point of having both on screen.
                else if (pickingItemPath != null && (on == PanelKind.PICKER || !itemPicker.pickerAsColumn())) {
                    // The kind tabs before the bar and the rows: a press on the strip switches lists,
                    // and the rows below are already the new tab's by the next frame. The arm's own
                    // rail rule applies — no stricter gate here, or the tabs would go dead exactly
                    // where the rows stay live.
                    if (pickerTabsVisible() && bookSettings.pressPickerTab(mouseX, mouseY)) {
                        return true;
                    }
                    // The sprite tab has no list rows: only its clear row answers, and anything
                    // else is not the picker's, for the same pannable-gaps reason as files.
                    if (pickerTabsVisible() && pickerKind == PickerKind.SPRITE) {
                        if (itemPicker.spriteClearRect != null && itemPicker.spriteClearRect.contains(mouseX, mouseY)) {
                            itemPicker.commitPicker(null, 1);
                            return true;
                        }
                        return false;
                    }
                    // The file tab's bar and rows are the texture picker's own state, not the item
                    // list's: a press must walk what was drawn, like every list here. A press that
                    // hits neither is not the picker's, so it reaches the tail and the canvas stays
                    // pannable from over the list's gaps.
                    if (pickerTabsVisible() && pickerKind == PickerKind.FILE) {
                        if (pressBar(textureBar, mouseX, mouseY)) {
                            return true;
                        }
                        int fileRow = textureFrame == null ? -1
                                : ItemPickerLayout.rowAt(textureRows, textureFrame,
                                        textureBody.scrollY(), mouseY);
                        if (fileRow >= 0) {
                            itemPicker.pressFileRow(fileRow);
                            return true;
                        }
                        return false;
                    }
                    if (pressBar(pickerBar, mouseX, mouseY)) {
                        return true;
                    }
                    int row = pickerFrame == null ? -1
                            : ItemPickerLayout.rowAt(pickerRows, pickerFrame, pickerBody.scrollY(), mouseY);
                    if (row >= 0) {
                        itemPicker.pressPickerRow(row);
                        return true;
                    }
                }
                // The settings page owns the body while it is open: a press on one of its rows is the
                // page's. Its bar is the page's own control, so it is asked before the page is. **And a
                // press that hits no row is not the page's** -- which is the report this arm was rewritten
                // for: `pressSettingsPage` answered nothing and the arm returned `true` anyway, so the page
                // swallowed every press on the panel and the canvas could not be panned from over it.
                else if (settingsOpen) {
                    if (pressBar(settingsView.bar(), mouseX, mouseY)) {
                        return true;
                    }
                    if (bookRewards.pressSettingsPage(mouseX, mouseY)) {
                        return true;
                    }
                }
                else {
                    // The panel's own body scroll: its bar was drawn here with no press branch at all, so
                    // the grip was a picture and its `draggingThumb` branch downstream was dead code.
                    if (pressBar(overlayView.bar(), mouseX, mouseY)) {
                        return true;
                    }
                    // The panel's own marked pieces, from the last frame's drawing -- one derivation for
                    // the mark and the press.
                    for (EditTarget target : editTargets) {
                        if (target.box().contains(mouseX, mouseY)) {
                            // Shift on a prerequisite's name is the locate gesture, the same as the
                            // reader's rows: "do not open it, show me where it is". Scoped to this one
                            // target, so the steppers' shift meaning (ten at a time) is untouched.
                            if (target.action() == EditAction.NAVIGATE_DEP && event.hasShiftDown()) {
                                locateOnCanvas(target.path());
                                return true;
                            }
                            editorControls.pressEditTarget(target, mouseX, mouseY);
                            // **The press that opens a field owns the drag that follows it.** Press at one end
                            // of the prose and drag to the other is how a description is selected -- and the
                            // description is not open before that press, so without this the opening press was
                            // the only one the field never got and the drag went to the canvas instead. A
                            // one-line value is nearly always dragged *after* being opened, which is why the
                            // same gesture already worked everywhere else.
                            fieldDrag = editingPath != null;
                            return true;
                        }
                    }
                }
            }
            else if (on == PanelKind.QUEST && !mayEditNow() && (button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_MIDDLE)) {
                // The reader's panel: a press on a task's or reward's row opens the chosen viewer on that
                // row's item (or tag) -- "how is this made", the direction the viewer pages do not cover.
                // From the last frame's own list, so the row that lights up is the row that answers.
                //
                // The body scroll before the rows: a press on the grip is the panel's. **Left only**, and
                // that is not a detail: this arm serves the middle button too -- a middle press on a
                // prerequisite row is the locate gesture -- so a bar that took any button would swallow the
                // middle click that was aimed past it.
                if (button == InputConstants.MOUSE_BUTTON_LEFT && pressBar(overlayView.bar(), mouseX, mouseY)) {
                    return true;
                }
                // A prerequisite row is the panel's own navigation: a plain press opens that quest, and its
                // locate icon -- or a middle-click or shift-click anywhere on the row -- takes the canvas to
                // the node instead.
                if (bookSettings.pressDependencyRow(mouseX, mouseY, button, event.hasShiftDown())) {
                    return true;
                }
                // The recipe-viewer rows answer the left press alone.
                if (button == InputConstants.MOUSE_BUTTON_LEFT && bookSettings.pressRowItem(mouseX, mouseY)) {
                    return true;
                }
                // A filter row previews what it accepts in the second column, after the recipe
                // rows: a filter row never carries a recipe target, so the two cannot claim one press.
                if (button == InputConstants.MOUSE_BUTTON_LEFT && bookSettings.pressFilterRow(mouseX, mouseY)) {
                    return true;
                }
                // A checkmark row hands in, after every other row press: it never carries a recipe
                // target either, and it is registered only while the hand-in is offered, so this
                // cannot steal a press something else answers.
                if (button == InputConstants.MOUSE_BUTTON_LEFT && bookSettings.pressCheckmarkRow(mouseX, mouseY)) {
                    return true;
                }
                // A claimable reward row collects, after the checkmark's: it carries no recipe
                // target while claimable, and it is registered only then, so this cannot steal
                // a press something else answers either.
                if (button == InputConstants.MOUSE_BUTTON_LEFT && bookSettings.pressRewardClaimRow(mouseX, mouseY)) {
                    return true;
                }
            }
            else if (on == PanelKind.SETTINGS && button == InputConstants.MOUSE_BUTTON_LEFT) {
                // The player's own panel: its one control is a widget and the pass has already offered it
                // the press, so what is left is the bar.
                if (pressBar(appearanceView.bar(), mouseX, mouseY)) {
                    return true;
                }
            }
            else if (on == PanelKind.PINNED && button == InputConstants.MOUSE_BUTTON_LEFT) {
                // The player's watchlist: the switch is a widget the pass has already offered, so what is
                // left is the bar and the rows the panel draws itself -- a row opens its quest, its Unpin
                // end lets go of it. Left only, like the element panel's: the canvas keeps the middle press.
                if (pressBar(pinnedView.bar(), mouseX, mouseY)) {
                    return true;
                }
                if (bookPins.onPinnedTarget(mouseX, mouseY)) {
                    bookPins.pressPinnedPanel(mouseX, mouseY);
                    return true;
                }
            }
            else if (on == PanelKind.ELEMENT && button == InputConstants.MOUSE_BUTTON_LEFT) {
                // The element's form: its rows are widgets and the pass has already offered them the press, so
                // what is left is the bar -- a form of twenty rows on a rail shorter than they are, which is a
                // bar somebody will try to drag -- and the chrome's own close chip. Left only, like the
                // settings page's, because a bar that took any button would swallow the middle press the
                // canvas uses.
                //
                // **The label half of a number's gesture comes first**, and it has to be read here rather than
                // left to the drawer's own arm: a stacked row's control band is the widget's bounds and its box
                // is only the right-hand part of it, so a press on the rest is the field's scrub -- see
                // `ScrubField.beginLabelScrub`. Falling past here, it reached the pan tail and dragged the graph
                // from under the row the author was aiming at.
                if (armLabelScrub(mouseX, mouseY)) {
                    return true;
                }
                if (pressBar(bookCanvas.elementView.bar(), mouseX, mouseY)) {
                    return true;
                }
                if (bookPins.onElementTarget(mouseX, mouseY)) {
                    bookPins.pressElementPanel(mouseX, mouseY);
                    return true;
                }
            }
            // Every other kind has no drawn targets of its own: its controls are widgets, and the widget
            // pass above has already offered them the press. So they reach the tail, which is right: a press
            // in a rail that answered nothing is a press on the canvas under it.

            // **One tail, for every kind but the dock. A press in a rail that nothing claimed is a press on
            // the canvas under it.**
            //
            // The rail floats over the graph; it is not a hole in it. So dragging from a panel's own surface
            // has to move the graph, or "the canvas stays live beside the panel" is only true of the strip the
            // panel does not cover -- which on a reader's window is a sliver. This is the report that found
            // it: *"allow me to drag and move canvas while sidebar is open"*, dragging from over the panel and
            // getting nothing -- and the settings page is where it came back, because that one arm returned
            // before reaching here.
            //
            // Set up by hand rather than left to the canvas branch below, because that branch asks what node
            // is under the pointer -- and the pointer is over a rail, so it would open whatever node the panel
            // happens to be covering. `panelPress` then keeps the release from reading this as a click on
            // empty canvas: a drag from the panel pans, and a click on it does nothing rather than closing the
            // panel the reader was working in.
            //
            // **`on` is null when the pointer is in no rail, and that is the whole test.** There is no
            // `presented(...).contains(on)` beside it, deliberately: the resolver above can only answer with a
            // presented kind or with null, so that test could only ever re-read the same answer -- and while
            // the card-era fallback was there it re-read the *substituted* one, which is how a press on the
            // canvas came to be treated as a press on the panel. A press with no rail behind it must fall out
            // of this branch, because the book's own handling -- opening a node, closing a panel, dragging a
            // row, the marquee, the canvas menu -- is what is below.
            //
            // **The dock is exempt**, and it is the one exemption: it is a surface of controls rather than a
            // reading surface, its blank space has always swallowed a press, and its own branch below is where
            // its rows, chips and bar are answered.
            if (on != null && on != PanelKind.TOOLS && (button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT)) {
                if (bookInput.startPanelPan(mouseX, mouseY, button)) {
                    return true;
                }
            }
        }

        // (The sidebar's menu was answered here, and the gate at the top of this method is where it is
        // answered now -- see `pressDropdown`. It stood here, below the panel branch, so every press that
        // landed over a rail was taken by the widget pass, an arm or the pan tail before it arrived.)
        //
        // And the right-click that opens it. On a row or on the empty list, in edit mode only: the menu
        // is all structural edits, and a reader has none of them.
        if (button == InputConstants.MOUSE_BUTTON_RIGHT && mayEditNow() && bookMenu.openSidebarMenuAt(mouseX, mouseY)) {
            return true;
        }

        // The sidebar's own press, read before the widget pass rather than after it, because the rows
        // are widgets and a widget consumes the press it is given: a row that is about to be dragged
        // must be remembered here, or the press is a click and there is nothing left to drag. It is not
        // consumed -- a press that never travels is still the row's click, and `super` below is what
        // makes it one.
        bookSidebar.rememberSidebarPress(mouseX, mouseY, button);

        // The sidebar's inline edit: a press on its fields is the edit's, and a press anywhere
        // else abandons it with nothing written. Cancelled before the widget pass, so the pass
        // offers the press to the list the cancel rebuilt rather than the one it replaced. The
        // rename's status line is the exception: it toggles the id disclosure instead.
        if (inlineEdit != null && !bookSidebar.pressOnInlineField(mouseX, mouseY)) {
            if (inlineEdit.mode() == NamingMode.RENAME && !inlineEdit.showId()
                    && bookSidebar.inlineStatusHit(mouseX, mouseY)) {
                bookSidebar.toggleInlineId();
                return true;
            }
            bookSidebar.cancelInline();
        }

        // Widgets first. A control that was clicked must keep the event.
        //
        // An armed "copy to all chapters" is undone by a press on anything else, the same rule the
        // party's Disband follows -- asked here, before the widget pass, because that pass may be the
        // confirm: a press on the armed row itself must not disarm it on the way in.
        boolean onCanvasCopy = canvasCopyButton != null
                && canvasCopyButton.isMouseOver(mouseX, mouseY);
        if (super.mouseClicked(event, doubleClick)) {
            if (!onCanvasCopy) {
                questEditor.disarmCanvasCopy();
            }
            return true;
        }
        if (!onCanvasCopy) {
            questEditor.disarmCanvasCopy();
        }

        // The colour picker next: its own fields are widgets and were just offered the press, so what is
        // left is its tracks and swatches -- and a press outside, which closes it and is consumed, so the
        // click that dismisses the picker never also acts on what was behind it.
        if (colourPopover.isOpen()) {
            if (colourPopover.mouseClicked(BookEditorControls.shiftedClick(pickerPointerX(mouseX), mouseY, event))) {
                return true;
            }
            closeColourPopover();
            return true;
        }

        // The tools panel first, and it swallows everything inside its own rectangle: a press on the
        // panel is never a press on the canvas, or choosing a colour would pan the graph underneath it.
        if (questEditor.inTools(mouseX, mouseY)) {
            // The colour chips first: they are drawn by the panel and hit-tested here, from the same
            // rectangle the chip is painted with (`ToolsLayout.chip`), so the chip that is seen is the
            // chip that answers. A field's box is a widget and the widget pass already offered it the
            // press; its *label* is not, which is why the label scrub is armed here too.
            if (toolsAppearanceVisible()) {
                if (armLabelScrub(mouseX, mouseY)) {
                    return true;
                }
                if (pressToolsChip(mouseX, mouseY)) {
                    return true;
                }
            }
            // The texture row's browse button: the third of its controls, drawn by the panel and
            // hit-tested from the layout's own box -- the same derivation the `...` is painted with,
            // so the button that is seen is the button that answers. The row's id field is a widget,
            // and this box is the one part of the row the widget pass does not own.
            if (toolsAppearanceVisible()) {
                Layout active = activeLayout();
                if (ToolsLayout.textureBrowseAt(toolsView.viewport(),
                        active == null ? null : active.slot(ToolsLayout.CANVAS_TEXTURE), mouseX, mouseY)) {
                    texturePicker.openTexturePicker();
                    return true;
                }
            }
            // An element row in the Chapter tab is a *selection* rather than a drag: a decoration is not a
            // list to reorder, and the fields under the list follow what is chosen. Read from the row's own
            // rectangle because the row holds no widget -- the same arrangement the quest rows below have,
            // with a different consequence.
            if (toolsTab == ToolsLayout.Tab.CHAPTER && mayEditNow() && chapterLayout != null) {
                String element = questEditor.chapterElementRowAt(mouseX, mouseY);
                if (element != null) {
                    // Pressing the chosen one again lets it go, which is how every other selection in this
                    // screen behaves and the only way to put the fields away without leaving the tab.
                    selectedElement = element.equals(selectedElement) ? null : element;
                    rebuildWidgets();
                    return true;
                }
            }
            // A link row in the Chapter tab is a *selection* rather than a drag, for the reason an
            // element row is: a marker is not a list to reorder, and the fields under the list follow
            // what is chosen. Read from the row's own rectangle because the row holds no widget.
            if (toolsTab == ToolsLayout.Tab.CHAPTER && mayEditNow() && chapterLayout != null) {
                String link = questEditor.chapterLinkRowAt(mouseX, mouseY);
                if (link != null) {
                    // Pressing the chosen one again lets it go, which is how every other selection in this
                    // screen behaves and the only way to put the fields away without leaving the tab.
                    BookCanvas.selectedLink = link.equals(BookCanvas.selectedLink) ? null : link;
                    bookCanvas.linkConfirmingDelete = null;
                    rebuildWidgets();
                    return true;
                }
            }
            // A fault row in the Chapter tab is read and copied: the row is too narrow to hold
            // the whole `file:line:column: severity: message`, so the press puts the full line in
            // the chat (where there is room for it and a history of it) and the file on the
            // clipboard (which an external editor can open). Before the quest drag below: a
            // fault is not draggable, and a press on one must never start a reorder.
            if (toolsTab == ToolsLayout.Tab.CHAPTER && mayEditNow() && chapterLayout != null) {
                int problem = questEditor.chapterProblemRowAt(mouseX, mouseY);
                if (problem >= 0) {
                    questEditor.pressProblemRow(problem);
                    return true;
                }
            }
            // A quest row in the Chapter tab is a draggable thing: the press claims it, and the drag
            // that may follow reorders the chapter's own list. `pressX`/`pressY` are recorded here
            // because the threshold compares against them and this press never reaches the canvas
            // branch that usually sets them.
            if (toolsTab == ToolsLayout.Tab.CHAPTER && mayEditNow() && chapterLayout != null) {
                int row = rowDrag.chapterQuestRowAt(mouseX, mouseY);
                if (row >= 0) {
                    dragRowMember = "chapter";
                    dragRowQuest = null;
                    dragRowFrom = row;
                    dragRowLive = false;
                    dragRowPointerY = mouseY;
                    pressX = mouseX;
                    pressY = mouseY;
                    return true;
                }
            }
            // The panel's own bar, last of its controls: the rows above are widgets and drawn targets, and
            // the bar is the one thing on the panel that is neither. Left button only, like every other bar
            // in this screen: the bar's gestures are a drag and a held page, and neither is a thing to hang
            // off a right press.
            //
            // **And the dock keeps its own rule for a press that hit nothing**, which is the one exemption
            // the shared pan tail makes: the dock is a surface of controls rather than a reading surface, its
            // blank space has always swallowed a press rather than panning the graph from under the row the
            // author is aiming at, and that is still what happens here. It has to swallow rather than fall
            // through, because the dock floats *over* the canvas -- so a press that fell through would open
            // whatever node happens to be under it.
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                pressBar(toolsView.bar(), mouseX, mouseY);
            }
            return true;
        }

        // A press landing mid-wipe is absorbed: the widgets are already at their target while the
        // surface travels, so anything it caught would act a fraction of a second before the list
        // has visibly arrived. The wipe is 160 milliseconds; the press waits for it. Either end
        // of the travel absorbs — the rectangle the wipe left from as well as the one it lands on.
        long sidebarSlideNow = net.minecraft.util.Util.getMillis();
        if (sidebarSlideStart >= 0 && sidebarSlideFromRect != null
                && sidebarSliding(sidebarSlideNow)
                && (sidebarSlideFromRect.contains(mouseX, mouseY)
                        || geometry().sidebar().contains(mouseX, mouseY))) {
            return true;
        }

        // A press on the slid-away sliver brings the list back: one press, no aiming at 20 pixels
        // of tab. Past the widget pass on purpose, so a row still wins its own press where rows
        // exist; what reaches here touched no row. Left and right both expand — a right press that
        // opened a menu for a sliver with no rows would be a menu about nothing.
        if ((button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT) && geometry().sidebarState().collapsed() && !sidebarPeeking
                && geometry().sidebar().contains(mouseX, mouseY)) {
            setSidebarCollapsed(false);
            return true;
        }

        // The box's pin dot, drawn chrome rather than a widget: filled while pinned, a ring while
        // not, and the press flips it. Past the widget pass because there is no widget to meet —
        // the rows live below the header band, so nothing up here answers first. Left button
        // only, like every grip: pinning is not a thing to hang off a right press. Peeked counts:
        // pinning the preview keeps it, now held open.
        if (button == InputConstants.MOUSE_BUTTON_LEFT && geometry().sidebarState().floating()
                && geometry().sidebarState().expanded()
                && geometry().sidebarBoxPin().contains(mouseX, mouseY)) {
            toggleSidebarPinned();
            return true;
        }

        // The sidebar's scrollbar, which is not a widget -- it is chrome this screen draws and
        // hit-tests itself, in the margin to the right of the rows. It has to be checked here rather
        // than left to the widget pass for that reason, and it cannot steal a click from a row because
        // its grab band starts just past the viewport's right edge.
        //
        // No guard: the sidebar is drawn and answering in every arrangement, beside a rail or with nothing
        // open, so its bar takes a press in all of them. The guard that used to stand here asked whether a
        // modal was up, and with one presentation the answer is always no.
        if (button == InputConstants.MOUSE_BUTTON_LEFT && pressBar(sidebarView.bar(), mouseX, mouseY)) {
            return true;
        }

        // (A block stood here that closed the quest panel for a press outside its rectangle, and it was
        // dead: every path into the branch above returns or falls through, and the behaviour it looks like
        // it provides has since moved to `mouseReleased`, where a click that did not travel closes the panel
        // and a drag pans the canvas. Deleted rather than left: it is what made a reader of this method
        // believe the canvas was reachable here.)

        // A press on the map while the list floats puts the list away instead of starting a pan:
        // the click that dismisses the panel is not the click that pans the graph. Peeked, it ends
        // the peek and the sliver stays; unpinned and open, it slides the list to its sliver. A
        // pinned box never collapses — unpin it first, and it slides away on its own. Either way
        // the press is spent dismissing, and the next one acts on the canvas.
        //
        // Past the rows, the bar and the sliver above, so only a press on the visible graph — outside
        // the list's own rectangle — reaches here. A press on the list's empty padding is the list's:
        // reading it as blank space would slide the panel away from under the hand that just opened
        // it, which is the fault the panel's own `!panelPress` guard exists for.
        if ((button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_MIDDLE || button == InputConstants.MOUSE_BUTTON_RIGHT) && inCanvas(mouseX, mouseY)
                && !geometry().sidebar().contains(mouseX, mouseY)
                && (sidebarPeeking
                        || (DevMode.isSidebarFloating() && !DevMode.isSidebarCollapsed()
                                && !DevMode.isSidebarPinned()))) {
            if (sidebarPeeking) {
                setSidebarPeeking(false);
            }
            else {
                setSidebarCollapsed(true);
            }
            return true;
        }

        // A press on the floating list's empty padding is the list's, not the graph's: the rows,
        // the bar and the strip above already had their chance, so what reaches here touched none
        // of them — and the canvas underneath must not answer through the panel floating over it.
        // Without this a click between two rows would pan the graph under the list, and its release
        // would read as blank space and close the quest panel the reader was working in. A docked
        // list needs none of this: beside the canvas, its padding is not over anything.
        if (geometry().sidebarState().floating() && geometry().sidebarState().expanded()
                && geometry().sidebar().contains(mouseX, mouseY)) {
            return true;
        }

        // Left or middle on the canvas: begin a pan, or -- in developer mode -- pick a node up, or
        // stretch a marquee. Whether it becomes a pan or a click is decided by whether the pointer
        // moves, which is why nothing is selected yet -- and why a pan that happens to start on a node
        // does not change the selection.
        // Left, right or middle on the canvas. Left is the selection's and the drag's; middle pans;
        // right is the edge's. The modifiers are read here, at the press, and remembered -- one rule
        // for every gesture, so letting go of a key a moment early cannot change what a press meant.
        if ((button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_MIDDLE || button == InputConstants.MOUSE_BUTTON_RIGHT) && inCanvas(mouseX, mouseY)) {
            dragging = button != InputConstants.MOUSE_BUTTON_RIGHT;
            pressMoved = false;
            pressX = mouseX;
            pressY = mouseY;
            pressedShift = event.hasShiftDown();
            pressedCtrl = event.hasControlDown();
            pressedAlt = event.hasAltDown();
            panContentX = viewport().contentX(mouseX);
            panContentY = viewport().contentY(mouseY);
            // A pan is the hand taking the camera: a glide still in flight must not fight it.
            glideQuest = null;

            String chapter = effectiveChapter();
            ClientQuestCache.Entry under = chapter == null ? null
                    : nodeAt(mouseX, mouseY, questsIn(chapter));
            pressedNode = under == null ? null : under.id();
            // And the link under the pointer, when no node is: a node is drawn over a marker, so a press
            // that lands on both belongs to the node. A link claims the press the way a node does, and
            // what the press *means* is decided on release, the same gesture rule both have: hold to
            // pan, click to open the target.
            bookCanvas.pressedQuestLink = under == null ? bookCanvas.linkAtId(mouseX, mouseY) : null;
            // And the element under the pointer, when neither is: a marker is drawn over a decoration,
            // so a press that lands on both belongs to the marker, for the same reason as above.
            pressedElement = under == null && bookCanvas.pressedQuestLink == null ? elementAt(mouseX, mouseY) : null;

            // A left press on a curve's handle is the handle's: claimed before the node and the pan, so
            // bending a line cannot also pick a node up or start a marquee.
            if (button == InputConstants.MOUSE_BUTTON_LEFT && mayEditNow()) {
                String[] bend = bookRewards.handleAt(mouseX, mouseY);
                if (bend != null) {
                    bendDragFrom = bend[0];
                    bendDragTo = bend[1];
                    bendDragKind = bend[2];
                    bendDragLive = false;
                    bendPreview = null;
                    anchorPreview = null;
                    handlePreview = null;
                    ClientQuestCache.Entry grabFrom = entryFor(bend[0]);
                    ClientQuestCache.Entry grabTo = entryFor(bend[1]);
                    if (grabFrom != null && grabTo != null) {
                        LineArt.Point fromCentre = new LineArt.Point(bookRewards.nodeCentreX(grabFrom),
                                bookRewards.nodeCentreY(grabFrom));
                        LineArt.Point toCentre = new LineArt.Point(bookRewards.nodeCentreX(grabTo),
                                bookRewards.nodeCentreY(grabTo));
                        DependencyStyle style = styleFor(grabTo, bend[0]);
                        if (BookRewards.HANDLE_BEND.equals(bend[2])) {
                            bendDragGrab = LineArt.bendAt(fromCentre, toCentre, mouseX, mouseY)
                                    - style.bendOr(DEFAULT_BEND);
                        }
                        else if (BookRewards.HANDLE_FROM_HANDLE.equals(bend[2]) || BookRewards.HANDLE_TO_HANDLE.equals(bend[2])) {
                            // Free 2D: the delta between the pointer and the control point, in the chord's
                            // own frame, so the drag lands where the hand is.
                            double[] chord = bookRewards.chordOf(grabFrom, grabTo);
                            LineArt.Point control = bookRewards.splitPointOf(bend, style,
                                    BookRewards.HANDLE_FROM_HANDLE.equals(bend[2]));
                            java.util.List<Double> pointer =
                                    LineArt.chordFraction(new LineArt.Point((int) chord[0], (int) chord[1]),
                                            new LineArt.Point((int) (chord[0] + chord[2] * chord[4]),
                                                    (int) (chord[1] + chord[3] * chord[4])), mouseX, mouseY);
                            java.util.List<Double> at = LineArt.chordFraction(
                                    new LineArt.Point((int) chord[0], (int) chord[1]),
                                    new LineArt.Point((int) (chord[0] + chord[2] * chord[4]),
                                            (int) (chord[1] + chord[3] * chord[4])),
                                    control.x(), control.y());
                            handleGrabAlong = pointer.get(0) - at.get(0);
                            handleGrabAcross = pointer.get(1) - at.get(1);
                        }
                        else {
                            LineArt.Point centre = BookRewards.HANDLE_FROM.equals(bend[2]) ? fromCentre : toCentre;
                            LineArt.Point[] ends = lineEnds(grabFrom, grabTo, style);
                            LineArt.Point end = BookRewards.HANDLE_FROM.equals(bend[2]) ? ends[0] : ends[1];
                            anchorDragGrab = LineArt.anchorAngle(centre, mouseX, mouseY)
                                    - LineArt.anchorAngle(centre, end.x(), end.y());
                        }
                    }
                    return true;
                }
            }

            // Middle-drag is a pan and never a click, so forget the node immediately. Otherwise a
            // middle-click that happens not to move would select whatever it landed on.
            if (button == InputConstants.MOUSE_BUTTON_MIDDLE) {
                pressedNode = null;
                bookCanvas.pressedQuestLink = null;
            }

            // Right claims the press for the edge gesture, on a node with an editor open. Anywhere else
            // it is remembered for the release, which decides between the canvas's menus: a right-press
            // that travels is an edge (or nothing, on empty canvas), and one that does not is a menu.
            if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                canvasRightPressed = true;
                if (under != null && mayEditNow()) {
                    edgeDragFrom = under.id();
                    edgeDragLive = false;
                }
                return true;
            }
            canvasRightPressed = false;

            // A left press *on* a node, with an editor open, picks the node up rather than panning. The
            // two cannot share the button, and this is the split every graph editor makes: a press on a
            // node is about that node, and a press on the canvas is about the view. The middle button
            // still pans from anywhere, which is what a trackpad-less mouse reaches for.
            //
            // The press is only the first of the four phases -- press, threshold, follow, release. The
            // node is claimed here but does not follow yet: that starts when the pointer has moved
            // further than a click's jitter, and until then this could still be a click.
            if (button == InputConstants.MOUSE_BUTTON_LEFT && under != null && mayEditNow()) {
                if (pickingDependency) {
                    // **While a pick is armed a press on a node is about the pair, not the node.** It
                    // claims nothing: no drag starts, the selection does not move, and the release lands
                    // the dependency -- which it could not do while the node was claimed, because the
                    // release answers a claimed node by opening it. That was the whole bug: the pick's
                    // landing code was correct and unreachable for the one gesture it exists for.
                    return true;
                }
                if (pressedCtrl) {
                    // Ctrl-click toggles one node in or out of the selection -- the standard gesture,
                    // and the reason the edge drag lives on the right button. It is a statement about
                    // the selection and nothing else: no pick-up, no open, no drag.
                    BookInput.toggleSelection(under.id());
                    return true;
                }
                if (pressedShift) {
                    // Shift adds to the selection as the press lands -- additive, so shift-clicking
                    // around a chapter collects nodes. The press still picks the node up, which is
                    // what makes shift-drag "add this one and move everything with it"; and a
                    // shift-click opens nothing, because collecting and opening are different jobs.
                    BookInput.addToSelection(under.id());
                }
                // **Read before claiming.** `nodeX` answers with `dragNodeX` for the node the drag is
                // carrying, so claiming first would have read the *last* drag's leftover position --
                // zero at startup, which is where every early commit landed. The override exists for
                // the drawing during a drag; the press needs the truth.
                dragNodeX = nodeX(under);
                dragNodeY = nodeY(under);
                draggedNode = under.id();
                nodeDragLive = false;
                // Where inside the node the pointer took hold, so the node does not jump to put its
                // corner under the pointer.
                dragGrabX = viewport().contentX(mouseX) - dragNodeX;
                dragGrabY = viewport().contentY(mouseY) - dragNodeY;
                return true;
            }

            // A grip on the **selected** element, claimed before the element's body and after the node. Both
            // halves of that order are deliberate: a grip is drawn on the element's own edge, which a hit test
            // counts as *outside* the element -- so without this a press on a corner would miss the element
            // entirely and start a marquee -- and a node drawn over a grip keeps the press, which is what the
            // drawing order says and what stops an invisible control from acting.
            //
            // Only the selected element's grips are live, so an invisible grip on an unselected element cannot
            // be grabbed by accident.
            if (button == InputConstants.MOUSE_BUTTON_LEFT && mayEditNow() && selectedElement != null) {
                ElementSlot slot = elementSlot(selectedElement);
                CanvasElementArt.Handle handle = slot == null ? null
                        : CanvasElementArt.handleAt(slot.element(), slot.box(), mouseX, mouseY);
                if (handle != null && slot != null) {
                    bookCanvas.elementDragging = selectedElement;
                    bookCanvas.elementDragLive = false;
                    bookCanvas.elementDragBase = elementNow(selectedElement);
                    bookCanvas.elementPreview = null;
                    bookCanvas.elementHandle = handle;
                    // **And the press is still the element's.** A grip sits on the element's own edge, which a
                    // hit test counts as outside it -- so `pressedElement` is null for a press here, and a click
                    // on a grip that never moved would fall through to the empty-canvas branch and *deselect*
                    // the very thing it landed on. Saying which element it is keeps the release's click honest.
                    //
                    // And it is not a link's: a grip is the selected element's own control, so a marker under
                    // it does not get the press -- the release opens the element's panel, as it always has.
                    pressedElement = selectedElement;
                    bookCanvas.pressedQuestLink = null;
                    return true;
                }
            }

            // A left press on an element takes hold of it, exactly as one on a node does -- claimed on the
            // press so a drag can start from it, with click-versus-drag left to the release. Edit mode only:
            // in play mode a press on a pressable element is its click action and nothing else, and a
            // decoration that could be dragged by a player would be a decoration that moved under them.
            if (button == InputConstants.MOUSE_BUTTON_LEFT && mayEditNow() && pressedElement != null) {
                CanvasElement held = elementNow(pressedElement);
                if (held != null) {
                    bookCanvas.elementDragging = pressedElement;
                    bookCanvas.elementDragLive = false;
                    bookCanvas.elementDragBase = held;
                    bookCanvas.elementPreview = null;
                    // No grip: this is the body, so the gesture is a move.
                    bookCanvas.elementHandle = null;
                    // The same grab offset the node drag uses, and the same two fields: a press is one gesture
                    // whichever object it landed on, and only one of the three is ever claimed.
                    dragGrabX = viewport().contentX(mouseX) - CanvasElementArt.originX(held);
                    dragGrabY = viewport().contentY(mouseY) - CanvasElementArt.originY(held);
                    return true;
                }
            }

            // A left press on a link takes hold of it, exactly as one on a node or an element does --
            // claimed on the press so a drag can start from it, with click-versus-drag left to the release.
            // Edit mode only: in play mode a press on a marker is its shortcut and nothing else, and a
            // marker that could be dragged by a player would be a shortcut that moved under them. A link
            // has no grips, so the body is the whole of it and the gesture is always a move.
            if (button == InputConstants.MOUSE_BUTTON_LEFT && mayEditNow() && bookCanvas.pressedQuestLink != null) {
                QuestLink held = linkNow(bookCanvas.pressedQuestLink);
                if (held != null) {
                    bookCanvas.linkDragging = bookCanvas.pressedQuestLink;
                    bookCanvas.linkDragLive = false;
                    bookCanvas.linkDragBase = held;
                    bookCanvas.linkPreview = null;
                    // The same grab offset the node drag uses: a press is one gesture whichever object it
                    // landed on, and only one of the three is ever claimed.
                    dragGrabX = viewport().contentX(mouseX) - held.x();
                    dragGrabY = viewport().contentY(mouseY) - held.y();
                    return true;
                }
            }

            // A shift-drag on the empty canvas stretches the additive marquee. Shift because the plain
            // left-drag on the canvas is the pan -- in both modes, which is the rule this round bought:
            // navigation does not change with a mode. Ctrl+A and shift-click already collect, so the
            // marquee is the third way to say "add these", and it says it with the same modifier.
            if (button == InputConstants.MOUSE_BUTTON_LEFT && under == null && pressedElement == null && mayEditNow() && pressedShift) {
                marqueeActive = true;
                marqueeX0 = (int) mouseX;
                marqueeY0 = (int) mouseY;
                marqueeX1 = marqueeX0;
                marqueeY1 = marqueeY0;
            }
            return true;
        }

        return false;
    }

    /**
     * The pointer's movement, which is what keeps a submenu open.
     *
     * <p>The first override of this method in the book, and it exists for one thing: `render` is told
     * where the pointer is every frame, but the pointer's *travel* is only announced here, and a submenu
     * has to survive the trip from its parent row to the panel beside it.
     */
    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        bookInput.updateSubmenuHover(mouseX, mouseY);
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        // A resize in flight, and it is the whole of what a drag can mean while one is: the column's inner
        // edge follows the pointer, and the chrome, the content **and the controls** all follow it.
        //
        // The width goes through the kind's own clamp and then the content's floor, so the edge stops where
        // a panel may not go rather than where the arithmetic would take it -- 240 is a narrow reader's rail
        // and a broken editor's, and the two are told apart by what the rail holds.
        //
        // **The rebuild is the fix for the buttons vanishing.** The first version hid the panel's controls
        // for the length of the drag, on the reasoning that a control drawn at the old width is one the
        // player would aim at wrongly; a playtest called that out, and it was right: hiding the thing you
        // are resizing reads as the UI breaking. Re-placing them costs a rebuild per frame the width
        // actually changes, which is the same cost a window resize already pays -- `Screen.resize` rebuilds
        // every frame of one -- and it is what keeps the footer on screen as the edge moves.
        if (panelDragRail != null) {
            // **One override, for the kind the hand is on.** There were two fields -- column 1's and column
            // 2's -- and which one was written was decided by asking whether the grabbed kind was the first
            // column, so grabbing the dock's edge filed the width as the dock's and grabbing a picker's filed
            // it under a *column*. A width is a property of a kind: `railWidth` reads this only for
            // `panelDragKind`, and the release writes it back under that same kind.
            int wanted = Math.max(PanelStack.widthWhileDragging(panelDragStartWidth, panelDragStartX,
                    mouseX, panelDragKind), panelContentFloor(panelDragKind));
            if (wanted != panelLiveWidth) {
                panelLiveWidth = wanted;
                rebuildWidgets();
            }
            return true;
        }

        // A resize of the chapter list in flight, and the same contract as the rails' above: the
        // edge follows the pointer, and the chrome, the rows and the controls all follow it. The
        // width goes through the list's own clamp, so the edge stops where a list may not go; the
        // draw-time cap in the geometry keeps the window's remainder for the graph, and a drag back
        // to the default removes the stored entry on release rather than restating it.
        if (sidebarDragRail != null) {
            int wanted = BookGeometry.sidebarWidthWhileDragging(sidebarDragStartWidth,
                    sidebarDragStartX, mouseX);
            if (sidebarLiveWidth == null || wanted != sidebarLiveWidth) {
                sidebarLiveWidth = wanted;
                rebuildWidgets();
            }
            return true;
        }

        // A field's label drag is the screen's, because a label is not a widget: the press armed it and
        // this keeps feeding the same field until the release commits it.
        if (labelScrub != null && labelScrub.labelDragged(mouseX, mouseY,
                event.hasShiftDown(),
                event.hasControlDown() || event.hasAltDown())) {
            return true;
        }
        // The picker's tracks, before anything behind it: a drag on the hue strip is the picker's, and a
        // drag in one of its fields falls through to the widget pass below.
        if (colourPopover.mouseDragged(BookEditorControls.shiftedClick(pickerPointerX(mouseX), mouseY, event))) {
            return true;
        }
        // The curve handle's follow. The bow is recomputed from the pointer rather than accumulated, so
        // letting the hand drift back over the chord straightens the line again.
        if (bendDragFrom != null) {
            if (!bendDragLive) {
                if (Math.abs(mouseX - pressX) <= DRAG_THRESHOLD
                        && Math.abs(mouseY - pressY) <= DRAG_THRESHOLD) {
                    return true;
                }
                bendDragLive = true;
                pressMoved = true;
            }
            ClientQuestCache.Entry bendFrom = entryFor(bendDragFrom);
            ClientQuestCache.Entry bendTo = entryFor(bendDragTo);
            if (bendFrom != null && bendTo != null) {
                LineArt.Point fromCentre = new LineArt.Point(bookRewards.nodeCentreX(bendFrom), bookRewards.nodeCentreY(bendFrom));
                LineArt.Point toCentre = new LineArt.Point(bookRewards.nodeCentreX(bendTo), bookRewards.nodeCentreY(bendTo));
                if (BookRewards.HANDLE_BEND.equals(bendDragKind)) {
                    bendPreview = LineArt.limitBend(LineArt.bendAt(fromCentre, toCentre, mouseX, mouseY)
                            - bendDragGrab);
                }
                else if (BookRewards.HANDLE_FROM_HANDLE.equals(bendDragKind) || BookRewards.HANDLE_TO_HANDLE.equals(bendDragKind)) {
                    // Free 2D, grab-relative, clamped so a control point cannot be flung off the graph.
                    double[] chord = bookRewards.chordOf(bendFrom, bendTo);
                    LineArt.Point origin = new LineArt.Point((int) chord[0], (int) chord[1]);
                    LineArt.Point other = new LineArt.Point((int) (chord[0] + chord[2] * chord[4]),
                            (int) (chord[1] + chord[3] * chord[4]));
                    java.util.List<Double> at = LineArt.chordFraction(origin, other, mouseX, mouseY);
                    handlePreview = java.util.List.of(
                            Math.max(-0.5, Math.min(1.5, at.get(0) - handleGrabAlong)),
                            Math.max(-0.9, Math.min(0.9, at.get(1) - handleGrabAcross)));
                }
                else {
                    // Free angle, around this end's own node, applied as the delta from the grab.
                    LineArt.Point centre = BookRewards.HANDLE_FROM.equals(bendDragKind) ? fromCentre : toCentre;
                    anchorPreview = LineArt.anchorAngle(centre, mouseX, mouseY) - anchorDragGrab;
                }
            }
            return true;
        }

        // A right-press that travels is a drag wherever it started: on a node it is the dependency edge,
        // which tracks its own liveness, and on empty canvas it is a gesture with no meaning — but either
        // way the release is not a click, and no menu may open for it. Recorded here because the pan
        // branch below only runs for the left and middle buttons, so a right-drag would otherwise reach
        // its release looking stationary.
        if (canvasRightPressed
                && (Math.abs(mouseX - pressX) > DRAG_THRESHOLD || Math.abs(mouseY - pressY) > DRAG_THRESHOLD)) {
            pressMoved = true;
        }

        // A slider's drag, before anything else: the press claimed the track, so the pan and the node
        // drag must not also answer it. The value follows the pointer and the preview with it; the
        // commit waits for the release, which is what keeps a drag to one operation rather than sixty.
        if (draggingSlider != null) {
            bookRewards.dragSliderTo(mouseX);
            return true;
        }

        // A drag that began on the open field extends its selection, and is consumed here: the press was
        // the field's (see `mouseClicked`), so the pan and the node drag must not also answer it. The
        // gesture came from play -- *"allow selecting as well, like dragging the cursor in text fields"*.
        if (fieldDrag) {
            if (inlineArea != null) {
                inlineArea.mouseDragged(BookEditorControls.shiftedClick(mouseX, mouseY, event), dragX, dragY);
            }
            else if (inlineField != null) {
                inlineField.mouseDragged(BookEditorControls.shiftedClick(mouseX, mouseY, event), dragX, dragY);
            }
            return true;
        }

        // The sidebar tree's drag, before the flat row drag because the two cannot both be live: a press
        // claims one list or the other, and the sidebar's rows are widgets while these lists are drawn.
        // The threshold separates a click from a drag exactly as it does everywhere else; the follow is
        // the pointer plus the drop it currently means, recomputed each frame so the mark the reader
        // sees is the drop they will get.
        if (sidebarDragKey != null) {
            if (!sidebarDragLive) {
                if (Math.abs(mouseX - sidebarDragPressX) <= DRAG_THRESHOLD
                        && Math.abs(mouseY - sidebarDragPressY) <= DRAG_THRESHOLD) {
                    return true;
                }
                sidebarDragLive = true;
                pressMoved = true;
            }
            sidebarDragPointerY = mouseY;
            sidebarDrop = SidebarDrag.target(bookSidebar.sidebarRows(), mouseY, sidebarDragKey, sidebarDragGroup);
            return true;
        }

        // The row drag, on the node drag's own phases as far as they apply: the press claimed the row,
        // the threshold separates a click from a drag, and the follow is just the pointer's y -- there
        // is no ghost row to carry, only the line the drop would land on. Consumed so the pan and the
        // node drag do not also answer a gesture that started on a row.
        if (dragRowFrom >= 0) {
            if (!dragRowLive) {
                if (Math.abs(mouseX - pressX) <= DRAG_THRESHOLD
                        && Math.abs(mouseY - pressY) <= DRAG_THRESHOLD) {
                    return true;
                }
                dragRowLive = true;
                pressMoved = true;
            }
            dragRowPointerY = mouseY;
            return true;
        }

        // The bar's drag, before the canvas pan and before the widgets. It has to be first because a drag
        // that started on a bar must stay on that bar: the pan would otherwise take the movement, and the
        // canvas would slide sideways while the pointer was over a scrollbar.
        //
        // One field rather than one branch per list, and that is the fix rather than a tidy-up. The
        // version this replaces asked each view in turn and re-derived the subject from the pointer's own
        // x for the party panel -- so dragging the left column's bar and moving rightwards handed the
        // drag to the right column, and any bar whose release branch was missing stayed held. The bar
        // captured at the press is the only one that can be dragged, and the pointer may be anywhere: off
        // the strip, off the card, off the window.
        if (heldBar != null) {
            heldBar.dragTo(mouseY);
            return true;
        }

        if (edgeDragFrom != null) {
            // The edge gesture's own threshold, the same one the other drags use: a shift-click that
            // jitters is still a selection toggle, and only a travelled press is an edge.
            if (!edgeDragLive) {
                if (Math.abs(mouseX - pressX) <= DRAG_THRESHOLD
                        && Math.abs(mouseY - pressY) <= DRAG_THRESHOLD) {
                    return true;
                }
                edgeDragLive = true;
                pressMoved = true;
            }
            edgeDragX = mouseX;
            edgeDragY = mouseY;
            return true;
        }

        if (bookCanvas.elementDragging != null) {
            // Press, threshold, follow -- the node drag's three steps, because a decoration that twitched
            // under every click would read as jitter in exactly the same way a node would.
            if (!bookCanvas.elementDragLive) {
                if (Math.abs(mouseX - pressX) <= DRAG_THRESHOLD
                        && Math.abs(mouseY - pressY) <= DRAG_THRESHOLD) {
                    return true;
                }
                // A pinned picture refuses here rather than at the press, so a click still selects it
                // and only a gesture that would have moved it is turned away -- with the way out named.
                // Grips funnel through the same drag, so a resize and a rotate are refused with it.
                if (bookCanvas.elementDragBase instanceof CanvasElement.Image image && image.locked()) {
                    String pinned = bookCanvas.elementDragging;
                    bookCanvas.elementDragging = null;
                    bookCanvas.elementDragLive = false;
                    bookCanvas.elementDragBase = null;
                    bookCanvas.elementPreview = null;
                    status("\"" + pinned + "\" is pinned: switch off Pinned in its Position section"
                            + " to move it", false);
                    return true;
                }
                bookCanvas.elementDragLive = true;
                pressMoved = true;
            }
            // What the gesture has produced so far, built from the element the press took hold of rather than
            // from the cached one -- the cache already carries the last frame's preview, so building on it
            // would compound the change every frame. Committed on release rather than here: a gesture is one
            // edit, and a file write per mouse move would be a file write per mouse move. What is committed is
            // what was last drawn, because release takes this one field.
            bookCanvas.elementPreview = bookPins.draggedElement(mouseX, mouseY);
            return true;
        }

        if (bookCanvas.linkDragging != null) {
            // Press, threshold, follow -- the node drag's three steps, because a marker that twitched
            // under every click would read as jitter in exactly the same way a node would.
            if (!bookCanvas.linkDragLive) {
                if (Math.abs(mouseX - pressX) <= DRAG_THRESHOLD
                        && Math.abs(mouseY - pressY) <= DRAG_THRESHOLD) {
                    return true;
                }
                bookCanvas.linkDragLive = true;
                pressMoved = true;
            }
            // What the gesture has produced so far, built from the link the press took hold of rather than
            // from the cached one -- the cache already carries the last frame's preview, so building on it
            // would compound the change every frame. A move follows the pointer with the grab offset kept,
            // snapped when the switch says so and Alt is not held -- the free placement, read per event so
            // it can be pressed mid-drag. Committed on release rather than here: a gesture is one edit.
            QuestLink base = bookCanvas.linkDragBase;
            int x = base == null ? 0 : (int) Math.round(BookGeometry.snap(
                    viewport().contentX(mouseX) - dragGrabX, BookGeometry.SNAP_GRID, snappingNow()));
            int y = base == null ? 0 : (int) Math.round(BookGeometry.snap(
                    viewport().contentY(mouseY) - dragGrabY, BookGeometry.SNAP_GRID, snappingNow()));
            bookCanvas.linkPreview = base == null ? null : base.translated(x - base.x(), y - base.y());
            return true;
        }

        if (draggedNode != null) {
            // Press, threshold, follow. Until the pointer has travelled further than a click's jitter,
            // the press is still a possible click — and a node that twitched under every click would
            // read as a jitter nobody asked for. The same threshold the pan uses, so a click means the
            // same amount of movement whichever surface it landed on.
            if (!nodeDragLive) {
                if (Math.abs(mouseX - pressX) <= DRAG_THRESHOLD
                        && Math.abs(mouseY - pressY) <= DRAG_THRESHOLD) {
                    return true;
                }
                nodeDragLive = true;
                pressMoved = true;
                // A selected node carries its selection with it. Where they all started is captured
                // now, once, because a delta can only be taken against where things were.
                dragStarts.clear();
                dragStartX = dragNodeX;
                dragStartY = dragNodeY;
                if (isSelected(draggedNode)) {
                    for (String id : selection()) {
                        ClientQuestCache.Entry entry = entryFor(id);
                        if (entry != null && !id.equals(draggedNode)) {
                            dragStarts.put(id, new float[] {nodeX(entry), nodeY(entry)});
                        }
                    }
                }
            }
            // A picked-up node follows the pointer, in content coordinates so the zoom does not
            // matter, and lands on the grid when the switch says so and Alt is not held — Alt is the
            // free placement, read per event so it can be pressed mid-drag. Committed on release
            // rather than here: a drag is one edit, and a file write per mouse move would be a file
            // write per mouse move. What is committed is what was last drawn — release takes these
            // fields — so the file never disagrees with the screen about where the node was dropped.
            dragNodeX = (float) BookGeometry.snap(viewport().contentX(mouseX) - dragGrabX,
                    BookGeometry.SNAP_GRID, snappingNow());
            dragNodeY = (float) BookGeometry.snap(viewport().contentY(mouseY) - dragGrabY,
                    BookGeometry.SNAP_GRID, snappingNow());
            // The rest of the selection moves by the same content delta, through the same pending
            // positions the canvas already reads -- so the lines and the labels move with them, and
            // release commits exactly what was drawn.
            float dx = dragNodeX - dragStartX;
            float dy = dragNodeY - dragStartY;
            if (!dragStarts.isEmpty()) {
                long revision = ClientQuestCache.treeRevision();
                for (Map.Entry<String, float[]> started : dragStarts.entrySet()) {
                    editors.moved(started.getKey(), started.getValue()[0] + dx,
                            started.getValue()[1] + dy, revision);
                }
            }
            return true;
        }

        if (marqueeActive) {
            pressMoved = true;
            marqueeX1 = (int) mouseX;
            marqueeY1 = (int) mouseY;
            return true;
        }

        if (dragging) {
            if (Math.abs(mouseX - pressX) > DRAG_THRESHOLD || Math.abs(mouseY - pressY) > DRAG_THRESHOLD) {
                pressMoved = true;
            }
            // The grabbed content point goes under the pointer, from the scale as it is *now*. Written
            // this way rather than as `panAtPress + (mouse - press)` for two reasons, and both of them
            // were faults: an offset captured at press is thrown away by anything else that moves the
            // view -- a zoom while the drag is held slid the canvas back to where the drag started, once
            // per wheel notch -- and the delta form accumulates a pixel per event unless it is anchored.
            // `Viewport.dragTo` is the one expression, and its test sweeps it against both.
            viewport().dragTo(mouseX, mouseY, panContentX, panContentY);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        // A resize ends here: the width is written, the live override is dropped, and the rail's controls
        // are rebuilt at the width the hand chose. Written once rather than per frame, so the file and the
        // picture cannot disagree about how wide the rail is -- which is the one thing a live override could
        // otherwise let them do.
        //
        // **Every kind's width is written, under its own name.** This used to remember column 1's and throw
        // column 2's away, because there was one stored number and it was the first column's; the dock, which
        // *was* the first column, had its drag filed as the panel width. A width is a property of a kind now,
        // so the write is the same line whoever the hand was on -- see `DevMode.setPanelWidth`, which drops
        // an entry that came back to its default rather than storing it beside it.
        if (panelDragRail != null) {
            int width = panelLiveWidth > 0 ? panelLiveWidth : panelDragRail.width();
            panelDragRail = null;
            panelLiveWidth = 0;
            DevMode.setPanelWidth(panelDragKind, width);
            panelDragKind = PanelKind.NONE;
            rebuildWidgets();
            return true;
        }
        // The list's edge let go: what the hand reached is what is remembered, through the same
        // clamp the drag used — and a width back at the default costs no entry, because the
        // file is a diff and the absence of the key already says "today's column".
        if (sidebarDragRail != null) {
            int width = sidebarLiveWidth != null ? sidebarLiveWidth : sidebarDragRail.width();
            sidebarDragRail = null;
            sidebarLiveWidth = null;
            DevMode.setSidebarWidth(width);
            rebuildWidgets();
            return true;
        }
        // A field's label scrub ends here: one release, one commit, whatever the drag did in between.
        if (labelScrub != null) {
            labelScrub.endLabelScrub();
            labelScrub = null;
            return true;
        }
        // A picker track's release writes once, if the drag changed anything; a release in one of the
        // picker's fields falls through to the widget pass.
        if (colourPopover.mouseReleased(BookEditorControls.shiftedClick(pickerPointerX(mouseX), mouseY, event))) {
            return true;
        }
        // A slider's release: the one commit for the whole drag. Read and cleared before anything else
        // looks at the gesture, because the drag is over the moment the button is up.
        if (draggingSlider != null) {
            bookRewards.releaseSlider();
            return true;
        }

        // Let go: the mark the drag made stays (the model keeps it until the next press or keystroke), and
        // the *gesture* ends -- a later drag belongs to whatever it starts on.
        fieldDrag = false;

        // The row drag's release, read and cleared before anything else looks at the gesture: the drop
        // is committed exactly where the line was last drawn, and a press that never travelled is a
        // click -- which, on a grip, is nothing at all.
        if (dragRowFrom >= 0) {
            int from = dragRowFrom;
            String member = dragRowMember;
            String quest = dragRowQuest;
            boolean live = dragRowLive;
            double pointerY = dragRowPointerY;
            dragRowFrom = -1;
            dragRowMember = null;
            dragRowQuest = null;
            dragRowLive = false;
            dragging = false;
            pressedNode = null;
            bookCanvas.pressedQuestLink = null;
            if (live) {
                rowDrag.commitRowDrop(member, quest, from, pointerY);
            }
            return true;
        }

        // The sidebar tree's release. Unlike the flat rows above, a press that never travelled has to
        // fall through to the widget pass: the row is a button, and a button's click is its release.
        // Only a live drag is swallowed here -- and swallowing it is what stops the same release firing
        // the row's press after it has just been dropped somewhere else.
        if (sidebarDragKey != null) {
            String key = sidebarDragKey;
            boolean group = sidebarDragGroup;
            boolean live = sidebarDragLive;
            SidebarDrag.Drop drop = sidebarDrop;
            sidebarDragKey = null;
            sidebarDragGroup = false;
            sidebarDragLive = false;
            sidebarDrop = null;
            if (live) {
                bookSidebar.commitSidebarDrop(key, group, drop);
                return true;
            }
        }

        // A link the press landed on, let go on the same rectangle: that is the click it was waiting for.
        LinkRect link = pressedLink;
        pressedLink = null;
        if (button == InputConstants.MOUSE_BUTTON_LEFT && link != null) {
            if (link.box().contains(mouseX, mouseY)) {
                if (!link.url().isEmpty()) {
                    openLink(link.url());
                }
                else if (!link.quest().isEmpty()) {
                    bookCanvas.openQuest(link.quest());
                }
            }
            return true;
        }

        // Consumed unconditionally once a bar is held, and deliberately not "only if the pointer is still
        // over the bar". Letting go outside the bar is how a drag ends in every program ever written, and
        // releasing on the last position the bar saw is what makes the end of a drag land where the
        // pointer was when it was let go.
        //
        // One field, so the seven-way disjunction that used to be here -- and the three bars somebody had
        // forgotten to add to it -- is not a list that can go stale.
        if (heldBar != null) {
            heldBar.release();
            heldBar = null;
            return true;
        }

        // The curve handle's release: one op, or nothing at all if the press never travelled.
        if (bendDragFrom != null) {
            String bendFrom = bendDragFrom;
            String bendTo = bendDragTo;
            boolean bendLive = bendDragLive;
            Double bend = bendPreview;
            Double anchor = anchorPreview;
            java.util.List<Double> handle = handlePreview;
            String kind = bendDragKind;
            bendDragFrom = null;
            bendDragTo = null;
            bendDragKind = null;
            bendDragLive = false;
            bendPreview = null;
            anchorPreview = null;
            handlePreview = null;
            dragging = false;
            if (bendLive && kind != null) {
                if (BookRewards.HANDLE_BEND.equals(kind) && bend != null) {
                    bookRewards.setLineBend(bendFrom, bendTo, bend);
                }
                else if (handle != null) {
                    bookRewards.setLineHandle(bendFrom, bendTo, kind, handle);
                }
                else if (anchor != null) {
                    bookRewards.setLineAnchor(bendFrom, bendTo, kind, anchor);
                }
            }
            return true;
        }

        if (edgeDragFrom != null) {
            String from = edgeDragFrom;
            boolean live = edgeDragLive;
            edgeDragFrom = null;
            edgeDragLive = false;
            dragging = false;
            pressedNode = null;
            bookCanvas.pressedQuestLink = null;

            if (live) {
                // The edge lands on whatever node is under the release, and the arrow points the way
                // the drag went: from the prerequisite to the quest that now depends on it. Landing on
                // empty canvas cancels -- a gesture that half-lands is a gesture that did not happen.
                String chapter = effectiveChapter();
                ClientQuestCache.Entry landing = chapter == null ? null
                        : nodeAt(mouseX, mouseY, questsIn(chapter));
                if (landing != null && !landing.id().equals(from)) {
                    JsonObject quest = ClientChapterReplica.quest(chapter, landing.id());
                    if (quest != null) {
                        // Built on the draft, not the replica: an edge drag that lands while another
                        // prerequisite is still pending must keep it rather than overwrite it.
                        List<String> dependencies = new ArrayList<>(fieldDraft.strings(chapter,
                                landing.id(), "dependsOn", QuestPanelLayout.strings(quest, "dependsOn")));
                        if (!dependencies.contains(from)) {
                            dependencies.add(from);
                            bookInput.sendField(chapter, landing.id(), "dependsOn",
                                    stringArray(dependencies));
                        }
                        else {
                            status(landing.id() + " already depends on " + from, true);
                        }
                    }
                }
            }
            // A right-press that never travelled is a click, and the canvas's menus are what a
            // right-click is for: on the node it was over, or on the line, or on the empty canvas.
            if (!live && !pressMoved) {
                bookMenu.openCanvasMenuAt(mouseX, mouseY);
            }
            return true;
        }

        // A right-press on empty canvas never arms an edge, so its release arrives here: a menu for the
        // canvas if the pointer stayed put, and nothing at all if it travelled — a right-drag on empty
        // canvas is not a pan, and it is not a menu either.
        if (button == InputConstants.MOUSE_BUTTON_RIGHT && canvasRightPressed) {
            canvasRightPressed = false;
            if (!pressMoved) {
                bookMenu.openCanvasMenuAt(mouseX, mouseY);
            }
            return true;
        }

        if (bookCanvas.elementDragging != null) {
            String id = bookCanvas.elementDragging;
            boolean live = bookCanvas.elementDragLive;
            CanvasElement base = bookCanvas.elementDragBase;
            CanvasElement now = bookCanvas.elementPreview;
            bookCanvas.elementDragging = null;
            bookCanvas.elementDragLive = false;
            bookCanvas.elementDragBase = null;
            bookCanvas.elementPreview = null;
            bookCanvas.elementHandle = null;

            if (live && base != null && now != null) {
                // What the canvas showed when the pointer let go is what is committed: this is the drawn
                // element rather than a re-derivation that could disagree with it -- the same rule the node
                // drag states, and the reason the two locals above exist before the fields are cleared.
                bookPins.commitElementEdit(id, base, now);
                // A gesture that moved is not a click, so the release tail must not read this as one. The
                // tail's own guard is `pressMoved`, which a live drag has set -- clearing this here says so
                // once rather than relying on that from a distance.
                pressedElement = null;
            }
            // **And nothing else here.** A press that did not travel is the *click*, and the click is the
            // release tail's: it selects the element and opens its panel, or in play mode runs what the element
            // says. The first version of this cleared `dragging` and left `pressedElement` for a shared tail to
            // find -- but that tail is guarded by `dragging`, which had just been cleared, so a left click on
            // an element did nothing at all and only the right-click menu answered. Deliberately no `return`:
            // the tail runs, which is where the click was always meant to be handled.
        }

        if (bookCanvas.linkDragging != null) {
            String id = bookCanvas.linkDragging;
            boolean live = bookCanvas.linkDragLive;
            QuestLink base = bookCanvas.linkDragBase;
            QuestLink now = bookCanvas.linkPreview;
            bookCanvas.linkDragging = null;
            bookCanvas.linkDragLive = false;
            bookCanvas.linkDragBase = null;
            bookCanvas.linkPreview = null;

            if (live && base != null && now != null) {
                // What the canvas showed when the pointer let go is what is committed: this is the drawn,
                // snapped position, not a re-derivation that could disagree with it -- the same rule the node
                // drag states. A gesture that moved is not a click, so the release tail must not read this as
                // one: `pressMoved` is already set by a live drag, and the tail's own guard reads it.
                bookPins.commitLinkEdit(id, base, now);
            }
            // **And nothing else here**, for the reason the element path gives: a press that did not travel
            // is the *click*, and the click is the release tail's. Deliberately no `return`: the tail runs,
            // which is where the click was always meant to be handled.
        }

        if (draggedNode != null) {
            String id = draggedNode;
            float x = dragNodeX;
            float y = dragNodeY;
            boolean live = nodeDragLive;
            boolean shift = pressedShift;
            draggedNode = null;
            nodeDragLive = false;
            dragging = false;
            pressedNode = null;
            bookCanvas.pressedQuestLink = null;

            if (live) {
                // What the canvas showed when the pointer let go is what is committed: these are the
                // drawn, snapped coordinates, not a re-derivation that could disagree with them.
                //
                // **One operation per chapter, not one per node.** A drag of seventy used to send seventy
                // ops, each costing a history snapshot and a save, so taking it back was seventy presses of
                // Ctrl+Z -- which is not an undo. The moves are recorded and then sent as a batch, and the
                // guard for a node whose pending position has been dropped lives inside that call: `movedX`
                // answers zero for an id it does not hold, and committing that zero wrote every other
                // selected quest to (0,0). See `commitMoveBatch`.
                // **`id` is passed rather than read from the field, and that is the whole of a bug this had.**
                // `draggedNode` was cleared nine lines above, so a callee that reached for it found null: the
                // dragged node never entered the id list, never got recorded, and no op was sent -- the node
                // snapped back on the next frame with nothing in any log. The locals above exist for exactly
                // this, which is why `x` and `y` were already arguments.
                bookInput.commitMoveBatch(id, new ArrayList<>(dragStarts.keySet()), x, y,
                        ClientQuestCache.treeRevision());
                dragStarts.clear();
            }
            else {
                // A press that never became a drag is still a click: it selects and opens, exactly as
                // it did before there was a drag to tell it apart from -- unless it was a shift-press,
                // which already added the node at press and opens nothing. The gesture is one gesture,
                // and developer mode only changed what "moved" means.
                if (shift) {
                    return true;
                }
                if (pressedAlt) {
                    // Alt+click: centre the node between its two nearest links. Not a card, and not a
                    // selection statement -- the gesture is about where the node sits.
                    bookRewards.straightenNode(id);
                    return true;
                }
                BookCanvas.selectForCard(id);
                bookRewards.openOverlay(id);
            }
            return true;
        }

        if (marqueeActive) {
            marqueeActive = false;
            boolean moved = pressMoved;
            dragging = false;
            pressedNode = null;
            bookCanvas.pressedQuestLink = null;
            if (moved) {
                bookInput.selectInsideMarquee();
            }
            // A shift-click on the empty canvas adds nothing and takes nothing away: the marquee's
            // modifier means "add", and an empty marquee adds an empty set. Clearing is the plain
            // click's job, below.
            return true;
        }

        if (dragging) {
            dragging = false;

            // An element's own click, and it is two gestures in one place. In edit mode it selects, so a
            // decoration can be moved or deleted like anything else on the canvas; in play mode it does what
            // the element says -- open a quest, open a page -- which is the whole point of a click action.
            //
            // Before the node's branch rather than after it, because the two cannot both be under the pointer:
            // `pressedElement` is only set when no node was. And on release rather than on press, for the same
            // reason a node's is: a press that travelled is a pan, and a pan must not select or open anything.
            if (!pressMoved && pressedElement != null && button == InputConstants.MOUSE_BUTTON_LEFT) {
                if (mayEditNow()) {
                    // **One click does both jobs**: it selects the element -- which is what puts the ring and
                    // the grips on it -- and it opens the panel that edits it, exactly as a node click selects
                    // and opens a quest. Selecting first and opening second is the two-gesture read the node
                    // click already rejected; here it would be worse, because the fields are the only way to
                    // change a decoration at all.
                    //
                    // A press that travelled never reaches this branch, so a drag still opens nothing.
                    openElementPanel(pressedElement);
                    selectedQuest = null;
                    BookCanvas.selectedLink = null;
                    multiSelection.clear();
                }
                else {
                    ElementSlot slot = elementSlot(pressedElement);
                    if (slot != null) {
                        bookCanvas.pressElement(slot.element());
                    }
                }
                pressedElement = null;
                return true;
            }

            // A press that never moved is a click on a marker. In play mode it opens the target --
            // there is nothing else a marker is for. In edit mode it selects, so a marker can be moved
            // or deleted like anything else on the canvas; its fields are in the Chapter tab, which is
            // where the selection points. The dependency pick names the target, because a pick is asking
            // "which quest" and the marker is standing in for one; a target pick in flight names it too.
            //
            // Before the node's branch for the same reason the element's is: at most one of the three
            // is ever claimed, so the order is documentation rather than arbitration -- and the pick
            // order is the draw order read backwards, which is what makes a click open what was on top.
            if (!pressMoved && bookCanvas.pressedQuestLink != null && button == InputConstants.MOUSE_BUTTON_LEFT) {
                BookCanvas.LinkSlot slot = bookCanvas.linkSlot(bookCanvas.pressedQuestLink);
                bookCanvas.pressedQuestLink = null;
                if (slot == null) {
                    return true;
                }
                if (pickingDependency) {
                    pickingDependency = false;
                    questEditor.addPickedDependency(slot.target().id());
                    return true;
                }
                // A target pick in flight does not land here: only a node click names a target, because
                // only quests resolve. A click on a marker while armed falls through to the selection
                // below, which is the gesture the author can see.
                if (mayEditNow()) {
                    // **One click selects**: the ring goes on the marker and the tab shows its fields --
                    // exactly as a node click selects and opens a quest, minus the card, because a link
                    // has no card to open. Selecting first and navigating second would fly the author to
                    // another chapter for trying to move a marker, which is the opposite of the gesture.
                    selectedQuest = null;
                    multiSelection.clear();
                    BookCanvas.selectedLink = BookCanvas.selectedLink != null
                            && BookCanvas.selectedLink.equals(slot.slot().link().id()) ? null : slot.slot().link()
                                    .id();
                    bookCanvas.linkConfirmingDelete = null;
                    rebuildWidgets();
                    return true;
                }
                navigateToQuest(slot.target().id());
                return true;
            }

            // A press that never moved is a click. Selecting on release rather than on press is what
            // makes "hold to pan" and "click to select" one gesture.
            if (!pressMoved && pressedNode != null && button == InputConstants.MOUSE_BUTTON_LEFT) {
                // A link-target pick in flight names this quest: only a node click lands one, because
                // only quests resolve -- a click on another marker would name a shortcut, and the file
                // wants the quest it stands in for.
                if (bookCanvas.pendingLinkPick != null) {
                    ClientQuestCache.Entry picked = entryFor(pressedNode);
                    pressedNode = null;
                    if (picked != null) {
                        bookPins.landLinkPick(picked);
                    }
                    else {
                        bookCanvas.pendingLinkPick = null;
                        rebuildWidgets();
                    }
                    return true;
                }
                // The dependency pick: armed by the panel's Add-from-canvas action, the next click
                // names the quest to depend on instead of opening one. Disarmed by Escape, and by
                // landing -- a pick that stayed armed would turn every later click into a dependency.
                if (pickingDependency) {
                    pickingDependency = false;
                    questEditor.addPickedDependency(pressedNode);
                    pressedNode = null;
                    return true;
                }

                selectedQuest = pressedNode.equals(selectedQuest) ? null : pressedNode;
                BookCanvas.selectedLink = null;
                bookCanvas.linkConfirmingDelete = null;
                multiSelection.clear();

                // A click on a node opens it, rather than only selecting it.
                //
                // Selecting first and opening second is a two-gesture read: click, then find the
                // Open button. Nobody wants that, so one click opens the quest -- which is also what a
                // graph UI is expected to do. There is no longer a second route to it: the summary
                // strip and its Open button are gone, because a button that opens what you just
                // clicked is a button for a place you are already standing.
                //
                // The pan is unaffected: a press that moved is a pan and never reaches here, so
                // holding to look around still works and cannot open anything by accident.
                bookRewards.openOverlay(pressedNode);
                return true;
            }
            if (!pressMoved && button == InputConstants.MOUSE_BUTTON_LEFT && !panelPress) {
                // A click on the empty canvas unselects everything -- the primary too, not only the
                // multi-selection: "press blank space to let go" is one gesture and not two. And the element
                // selection with it, because "everything" is what the sentence says: a decoration left
                // selected by a click on blank canvas would be a ring nobody could explain. And the link's,
                // for the same reason: a marker is a third kind of selection, and letting go means all three.
                selectedQuest = null;
                selectedElement = null;
                BookCanvas.selectedLink = null;
                bookCanvas.linkConfirmingDelete = null;
                multiSelection.clear();

                // And it closes the panel as well, which is the same gesture read the same way: a click on a
                // node opens one, so a click on nothing closes one. **It is here, on release, and not in the
                // press** -- this branch is reached only when the pointer did not travel, so a reader who
                // grabbed bare canvas to pan it has panned it and still has their panel. `pressMoved` is the
                // whole of that distinction.
                //
                // `!panelPress` is the other half of it: a click on a *panel's* own surface is a click on the
                // panel, and reading it as blank space would close the thing the reader just clicked -- and,
                // worse, the thing they may have been in the middle of dragging from.
                //
                // **And the dock is not closed by it**, which is the one exemption the close rule makes and
                // the one the author asked for: the dock is a working surface, and losing your tools because
                // you clicked a patch of empty graph would make it unusable. It goes away by the X, by Escape,
                // or by the Author pill -- the three controls that name it.
                if (overlay != PanelKind.NONE || overlay2 != PanelKind.NONE) {
                    closeOverlay();
                }
            }
            panelPress = false;
            pressedNode = null;
            bookCanvas.pressedQuestLink = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // The wheel closes an open menu, wherever it is. The canvas zooms and the lists scroll under the
        // pointer, so a menu left open would hang over a node or a row that has moved out from under it.
        if (!menu.isEmpty()) {
            bookMenu.closeMenu();
        }
        // **Which rail the wheel belongs to**, or null when the pointer is over the graph — in which case
        // the zoom at the foot of this method is what answers. One resolver, the same one the press and the
        // grip use, so the three cannot disagree about where a rail is or about how much of it has arrived.
        PanelKind wheel = panelColumnAt(mouseX, mouseY);
        if (wheel != null) {
            // The handlers below measure their rows against the four accessors, so this is the surface
            // they must answer for -- the same one assignment the drawing and the build make.
            surfaceKind = wheel;
        }
        // Every branch below reads `wheel`, so a rail answers only where it is and a wheel over the graph
        // beside it reaches the zoom at the foot of this method.
        if (wheel == PanelKind.PICKER) {
            // The wheel is the list's, and nothing behind the card answers it. First, before the dock's
            // branch below: the panel is still built behind the card, so without this the wheel scrolled
            // the dock underneath -- the same hole the party panel's note describes.
            //
            // Two lists arrive in this card, so the type page is asked first: its own band and its own
            // rows are what the pointer is over when a table's `+ Reward` is open.
            if (typePageOpen()) {
                if (typeFrame != null) {
                    typeBar.wheel(scrollY);
                }
                return true;
            }
            // The file tab scrolls the texture list's own bar: the wheel belongs to the list on
            // screen, and the item list behind this tab does not move.
            if (pickerTabsVisible() && pickerKind == PickerKind.FILE) {
                if (textureFrame != null) {
                    textureBar.wheel(scrollY);
                }
                return true;
            }
            if (pickerFrame != null) {
                pickerBar.wheel(scrollY);
            }
            return true;
        }

        if (wheel == PanelKind.TEXTURE) {
            // The same for the texture picker's list: the wheel is its own, clamped where it lands, and
            // the dock behind the card does not move.
            if (textureFrame != null) {
                textureBar.wheel(scrollY);
            }
            return true;
        }

        if (wheel == PanelKind.CHOICE) {
            // The card is a list, so the wheel is the list's -- and absorbed whether or not there is
            // anywhere to go, the same as every other card's.
            choiceView.bar().wheel(scrollY);
            return true;
        }

        if (wheel == PanelKind.FILTER) {
            // The list's wheel is the list's, like every other card's: absorbed, so the quest
            // behind it does not scroll.
            filterView.bar().wheel(scrollY);
            return true;
        }

        if (wheel == PanelKind.REWARDS) {
            rewardView.bar().wheel(scrollY);
            return true;
        }

        // Over a rail the wheel scrolls it -- and does not zoom the canvas behind it, for the same reason a
        // press on it does not pan: it is a surface, not a hole.
        //
        // Asked of `wheel` rather than of `inTools`, which is the same answer by a longer road: `inTools` is
        // `panelColumnAt(...) == TOOLS`, so this was the one arm that resolved the rail twice.
        if (wheel == PanelKind.TOOLS) {
            toolsView.bar().wheel(scrollY);
            return true;
        }

        if (wheel == PanelKind.PARTY) {
            // Scrolls the panel, and absorbs the wheel whether or not there is anywhere to go: this
            // method used to test only for QUEST, so the wheel went on scrolling the sidebar and zooming
            // the canvas behind a party panel. A modal that answers the pointer but not the wheel is a
            // modal with a hole in it -- and one that answers the wheel by doing nothing is still
            // answering it, which is what stops the list behind it from moving.
            //
            // Which column is the pointer's rather than which was grabbed: the wheel has no gesture, and
            // the two columns are separate surfaces side by side.
            partyScrollAt(mouseX).bar().wheel(scrollY);
            return true;
        }

        // The same rule as the press, on the wheel: in the docked presentation the panel answers the wheel
        // only where the column actually is, so a wheel over the graph beside it reaches the zoom at the
        // foot of this method. The condition's second half is what a card does -- no column, no question --
        // and `docked` is what tells the two arrangements apart.
        if (wheel == PanelKind.QUEST) {
            // The settings page owns the wheel while it is open, before anything else looks at it: it is
            // a page of the card rather than a list in it, so the card's own scroll behind it is not
            // what the pointer is over. Without this branch the wheel fell through to the body -- which
            // scrolled the invisible editor behind the page and left the column still, and the report
            // was simply "i cant scroll".
            if (settingsOpen) {
                settingsView.bar().wheel(scrollY);
                return true;
            }
            // While a picker is open the wheel is the list's, not the card's: the card is not what is
            // on screen. Clamped where it lands, because a flick past the bottom should stop at the
            // bottom -- the same rule as the body's below, for the same reason.
            if (typePageOpen()) {
                if (typeFrame != null) {
                    typeBar.wheel(scrollY);
                }
                return true;
            }
            if (pickingItemPath != null && !itemPicker.pickerAsColumn()) {
                if (pickerFrame != null) {
                    pickerBar.wheel(scrollY);
                }
                return true;
            }
            // Inside the overlay the wheel scrolls the text, which is what a long description wants.
            // Zooming here would be wrong: there is no canvas to zoom.
            // Clamped by the viewport rather than here, and that is the fix rather than a tidy-up:
            // the previous version wrote the offset unclamped and left the drawing pass to clamp it
            // against a height computed somewhere else, so a flick past the bottom sat out of range
            // until the next frame happened to correct it. Through the bar, like every other list here,
            // so the card's own wheel keeps the fraction a smooth wheel sends rather than truncating it.
            overlayView.bar().wheel(scrollY);
            return true;
        }

        // The player's own settings card, which had no branch at all: the wheel over it reached the
        // canvas and zoomed the graph behind the card. A modal that answers the pointer but not the
        // wheel is a modal with a hole in it, and this one has two -- see the naming card below.
        if (wheel == PanelKind.SETTINGS) {
            appearanceView.bar().wheel(scrollY);
            return true;
        }

        // The pinned list's own rows: a watchlist longer than its rail scrolls like every other list,
        // and the clamp is the viewport's, set from the same rows the drawing walks.
        if (wheel == PanelKind.PINNED) {
            pinnedView.bar().wheel(scrollY);
            return true;
        }

        // The element panel's own list: a form of twenty rows on a rail shorter than they are, so the wheel
        // belongs to the rows under the pointer -- and the clamp is the viewport's, set from the same rows the
        // drawing walks, so a short form cannot be scrolled past its end.
        if (wheel == PanelKind.ELEMENT) {
            bookCanvas.elementView.bar().wheel(scrollY);
            return true;
        }

        // The naming card, likewise: a card with one field and no list, and the wheel absorbed rather
        // than passed to the graph underneath it.
        if (wheel == PanelKind.NAMING) {
            return true;
        }

        // The Assets panel's page, and its own band: the wheel belongs to the page under the pointer, and
        // the clamp is the viewport's -- set from the same line count the drawing walks -- so a short
        // page cannot be scrolled past its end.
        if (wheel == PanelKind.ASSETS && scrollY != 0) {
            bookAssets.assetsBar.wheel(scrollY);
            return true;
        }

        // A branch stood here for the table editor's type page -- `overlay == TABLE_EDITOR &&
        // pickingEntryType != null`, scrolling `overlayView` and then scrolling the type page -- and it
        // was **unreachable**, which is worth recording so it is not added back. The type page's state is
        // set in exactly two places and neither can leave the overlay on the editor: `openTableTypePick`
        // sets `PanelKind.PICKER` in the same call that sets the member, and `closeTypePick` clears the
        // member *before* it returns the overlay to `TABLE_EDITOR`; the other two setters
        // (`openTypePicker`, `openConditionTypePicker`) are reached only from `pressEditTarget`, which
        // runs under `QUEST` or `PICKER`. So the wheel over a table's pick list was always answered by the
        // picker card's own branch below -- and the dead branch's `overlayView.scrollBy` moved an offset
        // that nothing in that card is drawn through, which is why it read as a bug when it was only ever
        // a branch nobody could reach.

        // The table panels' own lists. The wheel over a panel's *list* scrolls it and nothing else does:
        // the toolbar, the header and the breadcrumb are not lists, and until this existed a table with
        // more entries than the band could show had no way to reach the ones below it at all.
        //
        // The band, not the card. This asked `tableBody()` — the whole body — so the wheel over the
        // header or the toolbar scrolled the entries under the pointer, which the sentence above it
        // already said must not happen. A rule in a comment and a different rule in the code is the
        // shape of fault this panel keeps producing; the two panels scroll different bands, and
        // `overTableList` is where that is decided once.
        if ((wheel == PanelKind.TABLE_BROWSER || wheel == PanelKind.TABLE_EDITOR)
                && bookRewards.overTableList(mouseX, mouseY)) {
            if (wheel == PanelKind.TABLE_BROWSER) {
                if (tableBrowserFrame != null) {
                    tableBrowserBar.wheel(scrollY);
                }
            }
            else if (tableEditorFrame != null) {
                if (tableShowRoll) {
                    // The report's pitch is its own: twelve-pixel lines, not twenty-pixel rows.
                    rollBar.wheel(scrollY, ROLL_PITCH);
                }
                else {
                    tableEditorBar.wheel(scrollY);
                }
            }
            return true;
        }

        // A wipe in flight absorbs the wheel over it: the rows are travelling and the viewport
        // already answers for its target, so a notch now would scroll a list that is not where it
        // is drawn. The wipe is 160 milliseconds; the wheel waits for it.
        long sidebarWheelNow = net.minecraft.util.Util.getMillis();
        if (sidebarDeparting && sidebarSliding(sidebarWheelNow)
                && sidebarWipeRect(sidebarWheelNow).contains(mouseX, mouseY)) {
            return true;
        }

        // The wheel over the slid-away sliver is neither the list's nor the canvas's: it carries
        // no rows, and zooming the graph through a sliver the pointer is hovering to peek would
        // move the map out from under the preview being opened.
        if (geometry().sidebarState().collapsed() && geometry().sidebar().contains(mouseX, mouseY)
                && !sidebarViewport().containsScreen(mouseX, mouseY)) {
            return true;
        }

        // The wheel over the sidebar scrolls the list, and over the canvas it still zooms. Routed by
        // region rather than by a modifier, because the two regions are visibly separate things and a
        // player pointing at one does not want the other: a list that zoomed the graph behind it, or a
        // canvas that scrolled a sidebar it is not over, is a control answering a question nobody asked.
        //
        // Checked before the canvas, and the two cannot both match -- the sidebar is to the left of the
        // canvas's own left edge -- so the order is for the reader rather than for the logic.
        if (sidebarViewport().containsScreen(mouseX, mouseY)) {
            // Through the bar's own notch counter and not its `wheel`, because this list snaps: a wheel
            // landing between two rows is answered by `scrollSidebar`, which moves the offset onto the
            // row the notch was aimed at. The fraction the counter keeps is what makes a trackpad's
            // small deltas add up to that notch instead of being truncated away.
            int notches = sidebarView.bar().notches(scrollY);
            if (notches != 0) {
                bookSidebar.scrollSidebar(-notches * SidebarLayout.pitch());
            }
            return true;
        }

        if (inCanvas(mouseX, mouseY)) {
            // Zoom, about the pointer. Not a scroll: a canvas that pans by dragging and also scrolls is
            // two controls for one idea, and the wheel is the one people reach for to zoom.
            bookCanvas.zoomAt(mouseX, mouseY, scrollY > 0 ? 1.15F : 1F / 1.15F);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Escape closes the overlay rather than the book, if one is open. */
    /**
     * The party panel's two live fields.
     *
     * <h2>Why the search rebuilds on every character</h2>
     *
     * <p>Because the invite list is drawn from the layout, and the layout is built once per rebuild —
     * filtering at draw time would leave rows registered for players whose names no longer match, so a
     * click could land on an invisible button. A rebuild re-creates the field with the text preserved
     * in {@link #partySearch} and re-focuses it, which is why the screen keeps that string at all: the
     * widget is not the storage.
     */
    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        boolean handled = super.charTyped(event);
        if (!handled) {
            return false;
        }
        if (getFocused() == partySearchField && partySearchField != null) {
            partySearch = partySearchField.value();
            partySearchFocused = true;
            rebuildWidgets();
            return true;
        }
        if (getFocused() == partyCreateField && partyCreateField != null) {
            // No rebuild: the create field is the only thing its text affects, so there is nothing to
            // redraw until it is submitted.
            partyCreateName = partyCreateField.value();
            partyCreateFocused = true;
            return true;
        }
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        // The int the rest of this method reads: the SDL scancode side, matching the
        // `InputConstants.KEY_*` constants below (GLFW codes are not SDL scancodes).
        int keyCode = event.key();
        // **Any other key means "no".** An armed delete names a selection, and a key pressed between the
        // two is the author having moved on: the same "any other press disarms" rule the menus follow.
        // Delete and Backspace are the pair `ArmedDelete` owns, so they are left to answer for themselves.
        if (keyCode != InputConstants.KEY_DELETE && keyCode != InputConstants.KEY_BACKSPACE) {
            armedDelete.disarm();
        }
        // **The dropdown first, on the press's own rule.** A menu is the innermost thing on screen while it
        // is open, so Escape closes it and not what it was opened over -- which is what this line's own
        // comment has always said, and what it did not do: it stood below the table panels' Escape
        // branches, so with a menu opened over the table editor, Escape took the panel away and left the
        // menu standing over a card that no longer existed. See `pressDropdown` for the press's half of the
        // same rule, and `.utils/check_dropdowns.py` for the guard that holds the two halves together.
        if (!menu.isEmpty() && keyCode == InputConstants.KEY_ESCAPE) {
            bookMenu.closeMenu();
            return true;
        }
        // The table panels' own keys, read before anything else. Escape unwinds in the order the author
        // went in: the roll report first, then one table back, then the panel. A field left focused is
        // blurred by the rebuild that follows, and a blur is a commit -- so a half-typed title is kept
        // rather than dropped, which is the same rule the card's inline editor follows.
        if (overlay == PanelKind.TABLE_EDITOR && keyCode == InputConstants.KEY_ESCAPE) {
            // The innermost thing on screen answers first, in the order the author went in: the picker
            // page, then the roll report, then one table back, then the panel. The picker page had no
            // case here at all, so Escape closed the panel and left the picker armed -- which is how a
            // press on the type list ended up showing that list over a quest card.
            if (pickingEntryType != null) {
                pickingEntryType = null;
                rebuildWidgets();
                return true;
            }
            if (tableShowRoll) {
                tableShowRoll = false;
                tableImportOpen = false;
                return true;
            }
            bookRewards.popTableEditor();
            return true;
        }
        if (overlay == PanelKind.TABLE_BROWSER && keyCode == InputConstants.KEY_ESCAPE) {
            // Rebuild, for the same reason the outside press does: closing nulls the panel's fields
            // while its redraws and its footer buttons live until the next rebuild -- which left the
            // browser's own buttons drawn over the card and still taking presses, aimed at a reward that
            // had already been forgotten.
            bookRewards.closeTablePanelAndRebuild();
            return true;
        }
        if (overlay == PanelKind.ASSETS) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                // Escape and the footer's Done are one act, which is the rule every other card follows.
                closeOverlay();
                return true;
            }
            // The wheel's three keys, for the reader who never reaches for the mouse: the two arrows and
            // the page's own bounds. The arrows are one row and the page keys are one screen less that
            // row, which is the same pair the bar's own page-step uses -- so a page by key and a page by
            // groove land in the same place. (An if-chain rather than a switch: the SDL constants are
            // not compile-time constants, so they cannot be case labels.)
            int step = 0;
            if (keyCode == InputConstants.KEY_DOWN) {
                step = dev.ellipog.tenet.client.dev.AssetsLayout.ROW_HEIGHT;
            }
            else if (keyCode == InputConstants.KEY_UP) {
                step = -dev.ellipog.tenet.client.dev.AssetsLayout.ROW_HEIGHT;
            }
            // The bar's own page, not the band's whole height: one screen less the row that carries
            // over is what a page means everywhere else in this screen, and a key that landed
            // somewhere a groove press would not is two answers to one question.
            else if (keyCode == InputConstants.KEY_PAGEDOWN) {
                step = bookAssets.assetsBar.pageStep();
            }
            else if (keyCode == InputConstants.KEY_PAGEUP) {
                step = -bookAssets.assetsBar.pageStep();
            }
            if (step != 0) {
                bookAssets.assetsBody.setScrollY(bookAssets.assetsBody.scrollY() + step);
                return true;
            }
            return super.keyPressed(event);
        }
        // Ctrl+T opens the pack's panel: one chord per act, the way Ctrl+Z undoes and Ctrl+S reports that
        // there is nothing left to save. Only with nothing focused, so it cannot fire while a field is
        // being typed into — and never while previewing, where the Assets panel has no button and the
        // chord would open a surface the preview says is not there.
        if (overlay == PanelKind.NONE && keyCode == InputConstants.KEY_T && event.hasControlDown()
                && getFocused() == null && mayEdit() && !previewing()) {
            openAssets();
            return true;
        }
        // (Ctrl+P stood here, and it flipped the presentation: a card or a docked column. There is one
        // presentation now, so the chord and the client setting behind it are gone -- see `DevMode`, whose
        // parser still tolerates the old key without letting it decide anything.)
        // B slides the floating list to its sliver and back; Ctrl+B pins it open and lets it go.
        // Bare keys never fire from a field, like the editor's own keys below: a focused field's B is a
        // character, and a screen that slid the list out from under someone typing would be a trap.
        // Nor under an open menu or picker: the list would travel behind the very press the menu is
        // waiting for. Both no-op where they cannot apply — docked, or pinned for B — so the keys are
        // acceleration that never surprises rather than the only road.
        if (getFocused() == null && !event.hasControlDown() && !event.hasAltDown()
                && keyCode == InputConstants.KEY_B && overlay != PanelKind.NAMING && !typePageOpen()
                && pickingItemPath == null && menu.isEmpty() && !colourPopover.isOpen()) {
            toggleSidebarCollapsed();
            return true;
        }
        if (getFocused() == null && event.hasControlDown() && !event.hasAltDown()
                && keyCode == InputConstants.KEY_B) {
            toggleSidebarPinned();
            return true;
        }
        if (overlay == PanelKind.TABLE_BROWSER && (keyCode == InputConstants.KEY_RETURN
                || keyCode == InputConstants.KEY_NUMPADENTER)) {
            // Enter on the browser takes the row the keyboard is on: the same rule the item picker's
            // Enter has, so the two lists are driven the same way.
            bookRewards.pressTableBrowserRow(tableSelected);
            return true;
        }
        // And the keys that *move* the keyboard's row. Without these the sentence above was a promise
        // nothing could keep: the selection starts at -1 and only a press changed it, so Enter was a key
        // that did nothing until the pointer had already done the work.
        if (overlay == PanelKind.TABLE_BROWSER
                && (keyCode == InputConstants.KEY_UP || keyCode == InputConstants.KEY_DOWN)) {
            bookRewards.stepTableBrowser(keyCode == InputConstants.KEY_DOWN ? 1 : -1);
            return true;
        }
        // The editor's undo and redo, read *before* the focus gate further down. `keysForEditor` is only
        // reached while no widget holds the keyboard, and this panel focuses a field the moment it opens
        // one: the title box is focused whenever the table has no title of its own, which is every table
        // an author has just made. So Ctrl+Z did nothing on exactly the table where a mistake is most
        // likely, and the field it went to has no undo of its own. These are the panel's keys, like its
        // Escape branches above, not the focused field's.
        if (overlay == PanelKind.TABLE_EDITOR && event.hasControlDown()) {
            if (keyCode == InputConstants.KEY_Z) {
                tableUndo();
                return true;
            }
            if (keyCode == InputConstants.KEY_Y) {
                tableRedo();
                return true;
            }
        }
        // The naming card's own keys, read before anything else: Enter is the button the card is for,        // and Escape abandons it without touching a file. Handing Enter to the field would blur it,
        // which is not what a form's Enter means.
        if (overlay == PanelKind.NAMING) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                bookSidebar.closeNaming();
                return true;
            }
            if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
                bookSidebar.submitNaming();
                return true;
            }
        }
        // The sidebar's inline edit, on the card's terms but focus-guarded: the card is modal, so
        // its Enter always means submit; the inline row is not, so its keys only answer while one
        // of its fields holds the keyboard. Anywhere else Enter still means what the focused
        // control says it means.
        if (inlineEdit != null && (getFocused() == inlineTitle || getFocused() == inlineId)) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                bookSidebar.cancelInline();
                return true;
            }
            if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
                bookSidebar.commitInline();
                return true;
            }
        }
        // F2 renames the selected chapter where it stands. Groups rename from their menu: they
        // are never the selection, so there is nothing for the key to name.
        if (keyCode == InputConstants.KEY_F2 && inlineEdit == null && mayEditNow()
                && effectiveChapter() != null) {
            bookSidebar.startInlineRename("chapter", effectiveChapter());
            return true;
        }
        // The item picker's keys, and they are read here rather than left to the search box: a field's
        // Enter and Escape *submit*, and while a picker is open neither means that -- Enter commits the
        // rule above, and Escape leaves the field as it was. Handing them to the box would set the
        // field to the text someone pressed Escape to abandon.
        // The type page's keys, on the item picker's terms and read first: the page is a list, so Enter
        // adds the selected row, the arrows walk it, and Escape leaves the page rather than the card
        // (`leaveCardPage` is what the footer's Back runs, so the key and the button are one act).
        if (typePageOpen()) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                leaveTypePage();
                return true;
            }
            if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
                String chosen = editorControls.selectedType();
                if (chosen != null) {
                    questEditor.pressTypeRow(chosen);
                }
                return true;
            }
            if (keyCode == InputConstants.KEY_UP) {
                typeSelected = ItemPickerLayout.step(typeRows, typeSelected, -1);
                return true;
            }
            if (keyCode == InputConstants.KEY_DOWN) {
                typeSelected = ItemPickerLayout.step(typeRows, typeSelected, 1);
                return true;
            }
        }

        if (pickingItemPath != null) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                if (tablePickReturn) {
                    closeTablePick();
                    return true;
                }
                closeItemPicker();
                rebuildWidgets();
                return true;
            }
            if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
                itemPicker.commitFromPicker();
                return true;
            }
            if (keyCode == InputConstants.KEY_UP) {
                // The file tab walks its own rows: the keyboard's row is always the list on
                // screen, and the item list behind this tab is not.
                if (pickerTabsVisible() && pickerKind == PickerKind.FILE) {
                    textureSelected = ItemPickerLayout.step(textureRows, textureSelected, -1);
                }
                else {
                    pickerSelected = ItemPickerLayout.step(pickerRows, pickerSelected, -1);
                }
                return true;
            }
            if (keyCode == InputConstants.KEY_DOWN) {
                if (pickerTabsVisible() && pickerKind == PickerKind.FILE) {
                    textureSelected = ItemPickerLayout.step(textureRows, textureSelected, 1);
                }
                else {
                    pickerSelected = ItemPickerLayout.step(pickerRows, pickerSelected, 1);
                }
                return true;
            }
        }

        // The texture picker's keys, read on the item picker's terms and before the generic overlay
        // Escape below: Enter commits the typed id or the selected row, Escape leaves the field as it
        // was, and the arrows walk the list. Handing Enter or Escape to the search box would blur or
        // submit it, and neither is what a form's key means while a list is open.
        if (overlay == PanelKind.TEXTURE) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                texturePicker.closeTexturePicker();
                return true;
            }
            if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
                texturePicker.commitFromTexturePicker();
                return true;
            }
            if (keyCode == InputConstants.KEY_UP) {
                textureSelected = ItemPickerLayout.step(textureRows, textureSelected, -1);
                return true;
            }
            if (keyCode == InputConstants.KEY_DOWN) {
                textureSelected = ItemPickerLayout.step(textureRows, textureSelected, 1);
                return true;
            }
        }

        // Any overlay, not just the quest one. This tested `overlay == PanelKind.QUEST`, which was the
        // whole of the truth while there was one overlay and stopped being true the moment a second
        // existed: the party panel could then be left by clicking outside or pressing Back, and not by
        // the key every player reaches for first. A key that closes a dialog closes the dialog.
        // A focused editor's Escape: commit and blur, not close the card. **Forwarded to the widget by
        // name**, because "declining" here is not forwarding: this method's own `return false` leaves
        // without ever reaching `super.keyPressed`, which is the only thing that routes a key to the
        // focused child -- so the old shape left Escape answered by nobody. The widget's own Escape is
        // the edit ending, and this is the screen saying so out loud.
        //
        // **Unless the field is not on screen**: one scrolled out of the body is stood down and takes no
        // keys (see `repositionInlineEditor`), so Escape means what it means for the card -- leave it,
        // committing on the way out -- rather than being answered by nobody. The `visible` test below is
        // that case, and it is why this runs before everything else: a field with the keyboard is the one
        // thing that outranks the key's other meanings.
        if (keyCode == InputConstants.KEY_ESCAPE
                && getFocused() instanceof net.minecraft.client.gui.components.AbstractWidget focused
                && focused.visible) {
            boolean handled = focused.keyPressed(event);
            if (!focused.isFocused()) {
                // The widget blurred itself, and the screen's own focus pointer would otherwise stay on
                // it: a widget that is not focused refuses `charTyped`, so the field read as dead until
                // it was clicked again. See the same cleanup after `super.keyPressed` below.
                setFocused(null);
            }
            if (handled) {
                return true;
            }
            // The widget had no Escape of its own -- a button, say, which answers Enter and Space -- so
            // the key keeps its screen meanings rather than being swallowed by whatever happens to hold
            // the focus. Falling through, not returning: the branches below are those meanings (leave
            // the picker, close the card, close the book). A focused button used to eat Escape whole,
            // because the old shape returned here without forwarding it anywhere at all.
        }

        // The colour picker's Escape, after the focused field's: a field with the keyboard answers first
        // (a scrub field puts its value back), and the next Escape closes the picker and writes whatever
        // its last gesture left unwritten.
        if (colourPopover.isOpen() && keyCode == InputConstants.KEY_ESCAPE) {
            closeColourPopover();
            rebuildWidgets();
            return true;
        }

        // And its arrows: the picker's keyboard is the only way to nudge a colour by one, and it acts on
        // the part the pointer is over -- read from the frame the picker was last drawn with, so the key
        // and the crosshair cannot disagree. Shift is the ten-step. Read before the canvas focus below,
        // which would otherwise walk the nodes while a picker is open over them.
        if (colourPopover.isOpen()
                && colourPopover.keyPressed(keyCode, event.hasShiftDown())) {
            return true;
        }

        // A pick is armed on the canvas with the card closed, so its Escape is answered here rather than
        // inside the overlay branch below: the author who armed it has no overlay to close, and before
        // this was outside the guard Escape fell through to the screen's own handler and closed the whole
        // book. The field comment said "Escape cancels" and the code could not reach the branch that did.
        if (pickingDependency && keyCode == InputConstants.KEY_ESCAPE) {
            pickingDependency = false;
            dev.ellipog.tenet.client.dev.DependencyPick pick = pendingPick;
            pendingPick = null;
            status("tenet.status.add_cancelled", false);
            if (pick != null) {
                questEditor.returnToEditedQuest(pick);
            }
            return true;
        }
        // And a link-target pick is cancelled the same way: Escape forgets the armed link, and the tab
        // goes back to showing its fields rather than waiting for a click that will never land.
        if (bookCanvas.pendingLinkPick != null && keyCode == InputConstants.KEY_ESCAPE) {
            bookCanvas.pendingLinkPick = null;
            status("tenet.status.add_cancelled", false);
            rebuildWidgets();
            return true;
        }

        // **One Escape, one step back**, for every kind there is. This is the ladder the X and the same
        // header button also walk, and the order is "the innermost thing that is open first": the child rail
        // the player was in, then a page within a panel, then the panel, then the dock.
        //
        // **And the floating list is on it first — unless it is pinned.** A peek ends back at
        // its sliver, and an unpinned box slides to its sliver, before any rail peels: the list is
        // the outermost thing on screen, so it is the first thing a press puts away. A pinned box
        // never collapses — that is what pinned means — so Escape walks past it to the ladder,
        // and a docked list is navigation rather than a dialog, leaving it alone either way.
        //
        // **And the dock is on it now.** It used to fall through to the screen's own handler, which closes
        // the book -- so an author with their tools open pressed Escape and lost the whole book. It is a rail
        // like any other, it is put away by the thing that names it, and a second Escape then closes the
        // book, which is the pair every other arrangement already had.
        if (keyCode == InputConstants.KEY_ESCAPE && getFocused() == null) {
            if (sidebarPeeking) {
                setSidebarPeeking(false);
                return true;
            }
            if (DevMode.isSidebarFloating() && !DevMode.isSidebarCollapsed()
                    && !DevMode.isSidebarPinned()) {
                setSidebarCollapsed(true);
                return true;
            }
        }
        if (keyCode == InputConstants.KEY_ESCAPE
                && (overlay != PanelKind.NONE || overlay2 != PanelKind.NONE || dockOpen)) {
            // **Outermost first.** With a child rail open, the first press peels it -- the thing the player
            // was in -- and only the next one closes the panel they opened. That is what "one press, one
            // step back" means with a rail beside a rail, and it goes through the same unwind the X and the
            // breadcrumb use, so a table editor's field is committed on the way out.
            if (overlay2 != PanelKind.NONE) {
                closePanelColumn(true);
                return true;
            }
            if (settingsOpen) {
                // Back to the panel, and the page's pending values go with it: a draft is the page's own
                // state, and one left behind would make the canvas draw a number the file does not have.
                settingsOpen = false;
                draggingSlider = null;
                settingsDraft.clear();
                rebuildWidgets();
                return true;
            }
            if (pickingEntryType != null) {
                pickingEntryType = null;
                rebuildWidgets();
                return true;
            }
            if (overlay == PanelKind.CHOICE) {
                // Escape leaves the question unanswered, which loses nothing -- see `closeChoice`. This
                // branch rather than `closeOverlay`, which does not clear the offer: the next tick would
                // open the same question straight back up.
                bookOverlay.closeChoice();
                return true;
            }
            if (overlay != PanelKind.NONE) {
                closeOverlay();
                return true;
            }
            // Only the dock is left, so this press is the dock's -- the same act as its pill and its X.
            pressAuthorPill();
            return true;
        }

        // The editor's keys, and they are all modified or unclaimed: Ctrl+S is a save in every program
        // ever written, Ctrl+Z and Ctrl+Y are undo and redo, Ctrl+D duplicates, Ctrl+N is new. Delete is
        // the one bare key, and it only does anything when a node is selected — which is a state the
        // author put the screen in by clicking one.
        //
        // **Not while a text field has the keyboard.** A focused field's Backspace is a character and
        // its Ctrl+A is a selection; a screen that answered those with "delete the selected quest" and
        // "select every quest" would make typing in the editor a trap. Escape still reaches the card
        // above, and closing the card submits the focused field on the way out.
        if (mayEditNow() && getFocused() == null && bookInput.keysForEditor(event)) {
            return true;
        }

        boolean handled = super.keyPressed(event);
        // A widget that ended its own edit (Enter in a text field blurs it and commits) leaves the
        // screen's focus pointer on it -- `AbstractWidget` and the container's focus are two flags, and
        // only the container's is what input is routed by. Clearing it here is what makes the *next*
        // keystroke reach the screen again instead of a field that refuses characters because it is no
        // longer focused.
        if (getFocused() instanceof net.minecraft.client.gui.components.AbstractWidget focused
                && !focused.isFocused()) {
            setFocused(null);
        }
        // **A focused control that did not want the key leaves the editor's own chords to the screen.**
        // A number field is focused by the very press that starts a drag, and it answers false for
        // Ctrl+Z -- so before this, Ctrl+Z after touching a number did nothing at all: not the field's
        // undo, not the chapter's. Only the undo pair and Ctrl+S, and only once the widget has refused
        // them by returning false: a focused field's Backspace is a character and its Ctrl+A is a
        // selection, so the destructive chords stay behind the no-focus guard above. A text field
        // consumes Z and Y itself and keeps that meaning -- see `ArmatureTextField`.
        if (!handled && getFocused() != null && mayEditNow() && BookInput.unclaimedEditorChord(keyCode, event.hasControlDown())
                && bookInput.keysForEditor(event)) {
            return true;
        }
        return handled;
    }

    /**
     * Whether a position lands on the grid right now: the switch says so, and Alt is not held.
     *
     * <p>Read per event rather than remembered at press, because Alt is the bypass an author reaches
     * for mid-drag — hold it the moment the node is where they want it, and the follow stops rounding.
     * Live keyboard state is the keyboard's own answer (there is no event in half the call sites);
     * {@link BookGeometry#snap} stays a pure function by taking this one as its {@code on}.
     */
    static boolean snappingNow() {
        return DevMode.snap() && !BookEditorControls.altDown();
    }

    /** Asks the server for one edit. The client writes nothing: see {@code TenetNetworking.sendEditorOp}. */
    void send(EditorOp op) {
        bookInput.send(op, effectiveChapter());
    }

    /** Duplicates the whole selection: one history step per chapter it spans. */
    void duplicateSelection(List<String> ids) {
        bookInput.sendBulk(ids, EditorOp.Duplicate::new);
    }

    /** Deletes the whole selection: one history step per chapter it spans. */
    void deleteSelection(List<String> ids) {
        bookInput.sendBulk(ids, EditorOp.Delete::new);
    }

    /**
     * A card field's commit: the value is remembered locally, then sent.
     *
     * <p>The draft is what makes a stepper spammable — see {@code FieldDraft} for the whole story —
     * and one op per press stays, so Ctrl+Z still undoes one nudge. The chapter case is deliberately
     * not here: a chapter or group field is drawn from the tree, and its draft is the settings one.
     */
    void sendField(String quest, String path, JsonElement value) {
        bookInput.sendField(effectiveChapter(), quest, path, value);
    }

    /**
     * A chapter field's commit: the same draft, under the chapter's own owner.
     *
     * <p>The chapter's file is in the replica, so the same convergence rule applies — the value holds
     * until the copy holds it, and a refusal clears it. The Chapter tab reads it through
     * {@code FieldDraft.overlaid}, because it builds all of its rows from the tree at once.
     */
    void sendChapterField(String path, JsonElement value) {
        fieldDraft.set(effectiveChapter(), dev.ellipog.tenet.client.dev.FieldDraft.CHAPTER_OWNER,
                path, value, ClientQuestCache.treeRevision(), Util.getMillis());
        send(new EditorOp.SetChapter(path, value));
    }

    // ------------------------------------------------------------------
    // The multi-selection's whole-hand gestures
    // ------------------------------------------------------------------

    /** Copies the selection's own trees to the session clipboard. */
    void copySelection() {
        List<String> ids = selection();
        if (ids.isEmpty()) {
            return;
        }
        String chapter = effectiveChapter();
        List<JsonObject> trees = new ArrayList<>();
        for (String id : ids) {
            JsonObject tree = ClientChapterReplica.quest(chapter, id);
            if (tree != null) {
                // What the author sees is what is copied: a pending edit is part of the selection's
                // value, and the clipboard deep-copies, so a later overlay cannot reach into it.
                trees.add(fieldDraft.overlaid(chapter, id, tree));
            }
        }
        if (trees.isEmpty()) {
            status("tenet.status.nothing_to_copy_yet_the_chapter_s_copy_has_not_a", true);
            return;
        }
        ClientEditorClipboard.copy(trees);
        status("Copied " + trees.size() + (trees.size() == 1 ? " quest" : " quests"), false);
    }

    /**
     * Pastes the clipboard at a content position, snapped like every other placement.
     *
     * <p>Two callers, one body: Ctrl+V drops at the middle of what you are looking at, and the canvas
     * menu's "Paste here" drops where you right-clicked. A second copy would be a second thing to keep
     * in step about the 48-pixel stagger and the snap.
     */
    void pasteAt(double x, double y) {
        List<JsonObject> trees = ClientEditorClipboard.quests();
        if (trees.isEmpty()) {
            return;
        }
        boolean on = snappingNow();
        for (int i = 0; i < trees.size(); i++) {
            send(new EditorOp.Paste(trees.get(i),
                    BookGeometry.snap(x + i * 48, BookGeometry.SNAP_GRID, on),
                    BookGeometry.snap(y, BookGeometry.SNAP_GRID, on)));
        }
        status("Pasting " + trees.size() + (trees.size() == 1 ? " quest" : " quests"), false);
    }

    /** Selects every quest in the chapter: the primary first, the rest in the chapter's own order. */
    void selectAllInChapter() {
        List<ClientQuestCache.Entry> quests = questsIn(effectiveChapter());
        if (quests == null || quests.isEmpty()) {
            return;
        }
        multiSelection.clear();
        selectedQuest = quests.get(0).id();
        for (int i = 1; i < quests.size(); i++) {
            multiSelection.add(quests.get(i).id());
        }
        status("Selected " + quests.size() + (quests.size() == 1 ? " quest" : " quests"), false);
    }

    /**
     * A sentence for the reader of this book, said over the book.
     *
     * <p>Alongside `say`, not instead of it: chat keeps the history for a player who is not looking at the
     * book, and the toast is the same sentence shown where the book is -- which is the one place chat
     * cannot be read, because the HUD is not drawn behind a screen. See {@link ToastStack}.
     */
    void toast(String message, boolean error) {
        toasts.add(message, error, Util.getMillis());
    }

    /**
     * Reads whatever the server said since the last frame, and rebuilds the quest panel when its sources
     * have moved under it.
     *
     * <p>The only place an author is told about an edit, because the screen is the only thing that can tell
     * them: the payload handler keeps the answer where this finds it, since the reply arrives on the game
     * thread with whatever is open — which may be nothing. See {@code ClientEditReplies}.
     *
     * <p>The panel's rows are built from the replica at rebuild time, so a tree or a copy arriving after
     * they were built leaves the panel showing the past. The rebuild is here rather than on the frame
     * path, because a rebuild inside a render destroys the very field being typed into -- and tick runs
     * between frames, where a widget can be replaced safely.
     */
    @Override
    public void tick() {
        super.tick();

        // The rails have finished arriving, so their controls stop travelling and go live. One rebuild,
        // on the frame the last of them settles, and never on the frame path: a reveal read while drawing
        // would be a state read and a widget list changed inside a render.
        //
        // The two ways this is skipped are both deliberate: with motion off the duration is zero, so a rail
        // is already settled on the frame it opens and `init` built its controls with everything else; and a
        // rebuild that is not owed is not done. `panelBuildPending` is set by the per-frame slide for exactly
        // the case this exists for -- a rail whose widgets travelled and are still marked inert.
        //
        // **Per rail, and asked of the arrangement rather than of a field.** One timestamp for the whole
        // arrangement meant opening a panel re-slid the dock beside it, which reads as the dock closing and
        // reopening; and "is anything still arriving" has to be asked of every rail that is on screen, or a
        // rail that settles last would never go live.
        long now = Util.getMillis();
        if (panelBuildPending && !anyPanelRevealing(now)) {
            panelBuildPending = false;
            rebuildWidgets();
        }
        // The list's wipe has landed, so it forgets when it started: a toggle closed and reopened
        // later begins its second wipe at the beginning rather than part-way through the first. No
        // rebuild — the widgets were built for the target on the action's own frame, so settling
        // only ends the press-absorb and the travel, both of which read this flag. A departure
        // is the exception that needs nothing: its rows were filed as orphans when it started
        // and the sliver stands behind them, so settling only discards them — and rebuilding
        // every column here would recreate the rails and the header under a stationary pointer,
        // and every hovered control would blink the frame it lands.
        if (sidebarSlideStart >= 0 && !sidebarSliding(now)) {
            sidebarSlideStart = -1;
        }
        if (sidebarDeparting && sidebarSlideStart < 0) {
            sidebarDeparting = false;
            sidebarDeparted.clear();
            sidebarDepartAnchor = null;
        }
        // Everything settled forgets when it started, so a kind closed and reopened later begins its second
        // arrival at the beginning rather than part-way through the first.
        panelRevealStarts.entrySet().removeIf(entry -> !panelRevealing(entry.getKey(), now));
        // And everything sunk is owed its cleanup — selectively. A cleanup stamped under the
        // arrangement still standing runs: the close is the latest thing that happened, so nothing
        // fresh stands where its nullings land. One stamped under an older arrangement stays parked
        // for its own kind's reopen (or the screen's end): something moved since, and running it under
        // the new arrival would null state that arrival just set. The rail is forgotten either way, so
        // a later close schedules fresh rather than re-running the old.
        java.util.Iterator<java.util.Map.Entry<PanelKind, Long>> sunk =
                panelDepartStarts.entrySet().iterator();
        boolean settledExit = false;
        while (sunk.hasNext()) {
            PanelKind kind = sunk.next().getKey();
            if (!panelDeparting(kind, now)) {
                java.util.List<StampedTeardown> owed = pendingTeardowns.get(kind);
                if (owed != null) {
                    int before = owed.size();
                    owed.removeIf(stamped -> {
                        if (stamped.epoch() == arrangementEpoch) {
                            stamped.teardown().run();
                            return true;
                        }
                        return false;
                    });
                    settledExit |= owed.size() != before;
                    if (owed.isEmpty()) {
                        pendingTeardowns.remove(kind);
                    }
                }
                sunk.remove();
                departRails.remove(kind);
            }
        }
        if (settledExit) {
            // The mute was computed at close time, from state the teardown just took away: a settings
            // page that covered the body is gone, so recompute who answers rather than leaving the new
            // arrangement's controls muted behind it. No widget list changes, so tick may do it.
            refreshOverlayMute();
        }

        // A choice the server is holding comes to the front. It is a question the player asked by
        // claiming, so it is not left waiting behind a panel they opened since -- and it is read here
        // rather than in the drawing, because opening a panel re-places every widget.
        if (ClientChoiceOffers.current() != null && overlay != PanelKind.CHOICE) {
            bookOverlay.openChoiceOffer();
        }

        // The book's own notices: what has faded goes. A quest that has just completed is not read here
        // any more -- QuestNotifier owns that diff and calls `notifyQuestCompleted` -- and expiring is
        // bookkeeping, which is why it lives in tick rather than in the drawing.
        long millis = Util.getMillis();
        toasts.expire(millis);
        // The locate flash's own expiry, for the same reason: the drawing asks `CanvasReveal.flash`
        // every frame and draws nothing once it is over, and the state that named the node goes when
        // the flash does rather than outliving it.
        if (flashQuest != null && millis - flashStart >= CanvasReveal.FLASH_MILLIS) {
            flashQuest = null;
        }

        // The rewards panel is a list of what the server owes, so a progress sync changes it: a claim from
        // anywhere -- a row, Claim all, the command, a teammate's team reward -- lands here as a row that
        // should not be there any more. Rebuilt on the revision, the same way the dock's panel follows the
        // tree, and only while the card is open because that is the only time the rows are read.
        //
        // A **mark** moving rebuilds it too, and that is the second half of the fix: a mark that has been
        // answered or has expired changes which buttons are live, and the drawing alone cannot take a
        // button's label or its `active` flag back. The marks are no longer cleared here -- the ones still
        // in flight belong to presses nobody has answered yet, which is exactly what the wholesale clear
        // used to get wrong.
        bookRewards.reconcilePendingClaims(millis);
        // The sidebar's waiting counts ride the row buttons as suffixes, and a claim lands as a
        // changed map — see syncSidebarSuffixes. Here rather than in the drawing, because setting
        // a widget is state and state does not belong inside a render.
        bookSidebar.syncSidebarSuffixes();
        if (overlay == PanelKind.REWARDS) {
            long progress = ClientQuestCache.progressRevision();
            boolean marksMoved = rewardMarksRevision != pendingClaims.revision();
            if (progress != rewardsRevision || marksMoved) {
                rewardsRevision = progress;
                rewardMarksRevision = pendingClaims.revision();
                rebuildWidgets();
            }
        }
        else if (overlay == PanelKind.QUEST && !mayEditNow()) {
            ClientQuestCache.Entry entry = entryFor(overlayQuest);
            if (entry != null) {
                int taskIndex = firstManualTask(entry);
                java.util.UUID self = minecraft.player == null ? null : minecraft.player.getUUID();
                boolean claimable = self != null && claimableWithMarks(self, entry);
                if (claimable != bookSidebar.readerFooterClaimable || taskIndex != bookSidebar.readerFooterTaskIndex) {
                    rebuildWidgets();
                }
            }
        }

        long revision = ClientQuestCache.treeRevision();
        ClientChapterReplica.Copy copy = ClientChapterReplica.of(effectiveChapter());
        long replicaRevision = copy == null ? -1 : copy.revision();
        if (revision != questPanelRevision || replicaRevision != questReplicaRevision) {
            questPanelRevision = revision;
            questReplicaRevision = replicaRevision;
            // `dockOpen` rather than a second spelling of it: the question here is exactly "is the author's
            // dock up on the Chapter tab", which is the one rebuild a replica arriving has to trigger for a
            // tab nobody is looking at otherwise.
            boolean dockPanel = dockOpen && toolsTab == ToolsLayout.Tab.CHAPTER;
            // **And the element panel, which is the third reader of a chapter's own file.** Its rows are built
            // from the drafted element, so a flag, a colour or a word the server has just confirmed is a row
            // whose *value* has changed while its widget still holds the old one -- and a button cannot repaint
            // itself from a tree. Without this the panel a click opens was the one place in the editor that did
            // not follow an edit: the flag it had just written read the old state until something else happened
            // to rebuild the list.
            boolean elementPanel = overlay == PanelKind.ELEMENT;
            // The dock's picker counts as an editor for this purpose: a replica arriving while it is
            // open changes the chapter under the list, and the card's title and the "current" row are
            // read from that chapter. Rebuilding keeps the typed query -- `buildPickerWidgets` carries
            // the box's value across a rebuild -- so this cannot eat what is being searched for.
            boolean modalEditor = (overlay == PanelKind.QUEST || overlay == PanelKind.PICKER) && mayEditNow();
            if (dockPanel || elementPanel || modalEditor) {
                rebuildWidgets();
            }
        }

        // The card follows the **caret**, and only when the caret has moved: an editor below the fold is
        // an editor typed into blind, but a view that re-scrolls to its own box every tick is a view that
        // fights the wheel -- and with a description taller than the body it did not even settle, because
        // the "bottom is below the fold" and "top is above it" branches took turns. That was the report
        // (*"scrolling while having the cursor somewhere in the text works super gimmicky"*), and it asked
        // for exactly this split: the wheel wins, the caret follows when the caret is what moved.
        //
        // In tick rather than in the drawing, because scrolling re-places widgets and that is not a thing
        // to do inside a render.
        //
        // And not while the rail is still sliding in: the caret cannot have moved inside an editor the
        // player has had for a tenth of a second, while a scroll now would re-place widgets the slide is
        // about to move again — the two passes fighting over one list for the whole of the arrival.
        if (overlay == PanelKind.QUEST && mayEditNow()
                && !panelRevealing(PanelKind.QUEST, now)) {
            editorControls.followCaret();
        }

        // And the sidebar scrolls itself while a row is held near an edge. The flat lists deliberately
        // do not auto-scroll -- they are short enough to wheel mid-drag -- but the tree is the one list
        // that routinely does not fit, and "drag it to the group at the bottom" has to be possible
        // without letting go. In tick rather than in the drag's follow, because a pointer held still at
        // the edge stops producing mouse events and the list has to keep moving.
        if (sidebarDragLive) {
            bookSidebar.autoScrollSidebar();
        }

        // The server's undo history can be thrown away without this client having asked for anything --
        // `/tenet reload` drops every open editor. The *counters* behind the undo button are this screen's,
        // so they have to be reset here or the book keeps offering an undo over a history that is gone: a
        // Ctrl+Z that sends an op, gets nothing back, and says nothing. See `ClientEditHistory`.
        if (ClientEditHistory.takeDiscarded()) {
            tableApplied = 0;
            tableUndone = 0;
            // The drafts go with it, for the same reason the server's own `forget` drops the model: a pending
            // value is an edit recorded against the files as they were, and the reload has replaced them.
            fieldDraft.forgetChapter(effectiveChapter());
        elementDraft.clear(effectiveChapter());
        linkDraft.clear(effectiveChapter());
            settingsDraft.clear();
            bookInput.authorReport("The server reloaded, so the undo history was discarded");
        }

        // What the reload found wrong with the pack -- a dangling dependency, a cycle between files, an id in
        // two chapters. None of them is visible to the per-file validator that guards a save, so before this
        // the edit succeeded, the fault was written, and only the server log knew. Reported after the tree, so
        // the canvas is already drawing the pack these problems are about.
        //
        // **One short sentence per report, not one per line.** The lines used to be toasted one by one, which
        // put a `file:line:col: severity: message` on screen for each fault -- a sentence far longer than a
        // toast box, so it was truncated -- and, because the payload's text carried the validator's own `at
        // $.tasks[2]` continuation as a second line, a bare "at $" card beside it. The lines belong where
        // there is room for them: the Chapter tab lists them under `Problems`, and the server log has every
        // one with its file and line. A toast is a pointer, and a pointer has to fit.
        for (ClientEditProblems.Report report : ClientEditProblems.drain()) {
            for (String line : report.lines()) {
                Constants.LOG.info("the pack has a problem: {}", line);
            }
            bookInput.authorToast(Component.translatable("tenet.dev.problems.toast", report.count()).getString(), true);
        }

        // Every answer nobody has read yet, oldest first. A burst of quick edits -- spamming a stepper
        // -- puts several between two ticks, and the store used to keep only the last, so a refusal in
        // the middle was lost. A refusal also drops that chapter's pending values: the edit did not
        // stick, and the copy's own answer is the truth.
        for (EditorReplyPayload reply : ClientEditReplies.drain()) {
            // Which request this answers, and what it means for the table panel. **The id is preferred and
            // the position is the fallback**, not the other way round: an answer naming its request is
            // matched to that request however many others are in flight, and only an answer carrying no id
            // -- a broadcast, or a server that replied before reading one -- is matched to the oldest.
            //
            // Both doors consume from one queue, so a request matched by id is not then matched again by
            // position. That is why this is a choice between two calls rather than two lookups.
            ClientEditReplies.Request matched = reply.requestId() == EditorReplyPayload.NO_REQUEST
                    ? null
                    : ClientEditReplies.takeById(reply.requestId());
            String answered = reply.requestId() == EditorReplyPayload.NO_REQUEST
                    ? ClientEditReplies.takeSent()
                    : (matched == null ? null : matched.marker());
            boolean replicaAnswer = REPLICA_SENTINEL.equals(answered);
            if (answered != null && !answered.isEmpty() && !replicaAnswer) {
                bookInput.applyTableReply(answered, reply);
                if (!reply.ok()) {
                    // The edit did not stick, so nothing should pretend it did: the drafted value goes
                    // and the panel draws what the file actually holds.
                    BookRewards.tableDraft.forgetOwner(effectiveChapter(), bookRewards.tableOwnerKey());
                }
            }
            if (!reply.ok()) {
                fieldDraft.forgetChapter(reply.chapter());
            elementDraft.clear(reply.chapter());
            linkDraft.clear(reply.chapter());
                // The settings page's pending values are the same kind of ask and end the same way. A
                // refusal does not move the tree, so `onRevision` would never drop them and the preview
                // would keep drawing — and the next arrow press would accumulate from — the value the
                // server just said no to. The page belongs to the effective chapter, so only that
                // chapter's refusal clears it; a drag re-stamps itself on the next frame either way.
                if (reply.chapter().equals(effectiveChapter())) {
                    settingsDraft.clear();
                }
            }
            // A table op's chapter is empty by design -- a table is a file, not a chapter's -- so the
            // comparison below would classify every table reply as "about a chapter you navigated away
            // from" and skip it. That is what swallowed every table refusal and every import report: the
            // only thing an author saw was the optimistic line the client said before asking.
            boolean tableReply = answered != null && !answered.isEmpty() && !replicaAnswer;
            if (!tableReply && !reply.chapter().equals(effectiveChapter())) {
                // The answer is about a chapter the author has navigated away from. It is still news about
                // that chapter's copy -- a refusal is exactly why its panel would keep saying "has not
                // arrived yet" -- so it is recorded and logged rather than dropped in silence, which is what
                // made the placeholder's lie impossible to diagnose.
                if (!reply.ok()) {
                    String said = String.join(" ", reply.lines());
                    // A table replica first, because it is the one that arrives with no chapter to its name:
                    // recorded against the table it was asked for, which is the only way the panel that is
                    // waiting for that table can say what the server said instead of saying nothing.
                    if (replicaAnswer && replicaAskedFor != null) {
                        dev.ellipog.tenet.client.ClientTableReplica.refuse(replicaAskedFor, said);
                        Constants.LOG.info("table replica for \"{}\" was refused: {}",
                                replicaAskedFor, said);
                        replicaAskedFor = null;
                    }
                    ClientChapterReplica.refuse(reply.chapter(), said);
                    Constants.LOG.info("replica for \"{}\" was refused while another chapter was open: {}",
                            reply.chapter(), said);
                }
                continue;
            }
            for (String line : reply.lines()) {
                if (reply.ok()) {
                    bookInput.authorReport(line);
                }
                else {
                    // Recorded, so the Chapter tab's placeholder can say what the server said instead of
                    // claiming a copy is still on its way. Not for a table reply: there is no chapter to
                    // record it against, and the refusal is the panel's own answer.
                    if (!tableReply) {
                        ClientChapterReplica.refuse(reply.chapter(), line);
                    }
                    Constants.LOG.info("reply was refused: {}", line);
                    bookInput.authorToast(line, true);
                }
            }
            if (reply.ok() && !reply.questId().isEmpty() && (answered == null || answered.isEmpty())) {
                // A quest the server made: created, or duplicated. Selecting it here rather than when the op was
                // sent, because the id is the server's to choose and this is the first moment the author has it.
                // Only for a chapter's op: a table op's id is a table's, and selecting a quest named after a
                // table would move the canvas to a quest that does not exist.
                selectedQuest = reply.questId();
                bookInput.authorReport("Now editing " + reply.questId());
            }
            if (!tableReply) {
                // The intro's gates: a made quest, task or reward advances the tour. Chapter ops only —
                // a table op's id is a table's, and the tour's rungs are quests. Advisory rather than
                // exact (see IntroGuide): any accepted chapter edit on the task and reward rungs moves it.
                IntroGuide.noteReply(reply.ok(), reply.questId());
            }
        }

        // The first-run intro, evaluated on the counts the replies above just moved: an author facing
        // an empty book starts the guided making of one chapter, quest, task and reward, and the rungs
        // the tree satisfies advance. Here rather than in the drawing, because starting is state and
        // state does not belong inside a render.
        IntroGuide.tick(mayEdit(), DevMode.on(), previewing(), ClientQuestCache.hasTree(),
                ClientQuestCache.chapters().size(), ClientQuestCache.entries().size());
    }

    /**
     * Whether the book stills the world while it is open.
     *
     * <p>The file's {@code pauseGame}, read off the synced tree: false keeps the world ticking, so
     * an author reading mid-fight keeps the default, and a lore book may still it. False before
     * any tree arrives, which is the behaviour every pack that predates the field gets.
     */
    @Override
    public boolean isPauseScreen() {
        return ClientQuestCache.pauseGame();
    }

    // ------------------------------------------------------------------
    // Text helpers
    // ------------------------------------------------------------------

    /**
     * The state word a card and a viewer page both show.
     *
     * <p>Public since the viewer seam: an EMI or JEI row and the book's own card must not be able to
     * disagree about what "started" is called, and the alternative -- a second switch in the content
     * class -- is exactly how they would.
     */
    public static String stateLabel(QuestState state) {
        // With a fallback, not a bare key: this is read by game-free tests and by the viewer content,
        // and both can run where no language file has been loaded -- where `getString` on a bare key
        // returns the key itself. The fallback is the English the key holds, so the two paths agree.
        return switch (state) {
            case COMPLETED -> Component.translatableWithFallback("tenet.state.completed",
                    "Completed").getString();
            case STARTED -> Component.translatableWithFallback("tenet.state.started",
                    "In progress").getString();
            case UNLOCKED -> Component.translatableWithFallback("tenet.state.unlocked",
                    "Available").getString();
            case LOCKED -> Component.translatableWithFallback("tenet.state.locked",
                    "Locked").getString();
        };
    }

    // wrap(String, int) and drawParagraphs(...) used to live here: a hand-rolled word wrap, and the
    // drawing of it, in the same class that needed the height. That is why the scrollbar's range and
    // the text on screen were two computations that had to be kept in step. The wrap rule is TextWrap's
    // now, the composition is OverlayLayout's, and this class draws the lines it is handed.

    // trim(String, int) used to live here: a character-count truncation, which is the wrong kind of
    // quantity for this job -- see trimToWidth above. It had four callers and all four were wrong:
    // chapter titles cut to 16 characters in a 116px button, node labels cut to a number derived from
    // the zoom, strip titles trimmed a character at a time in a loop that re-measured each time, and a
    // summary passed a limit of 999, which is not a truncation at all.

    /**
     * Forgets the view: chapter, selection, zoom, pan.
     *
     * <p>Called on disconnect. Without it, leaving one server and joining another shows the first
     * server's chapter and pan until the new tree arrives — which looks exactly like a sync failure and
     * is not one, so it sends you looking in the wrong place.
     */
    /**
     * Opens the book on one quest, with its card up -- the entry point a recipe viewer calls.
     *
     * <p>Static and id-taking because the screen opener Armature offers carries an id and nothing
     * else, and the book is constructed by Minecraft's screen machinery a moment later: the request is
     * parked in {@link QuestBookFocus} and consumed by {@code init}. A quest this client does not hold
     * -- a stale click, or a click while the tree is still arriving -- opens nothing; there is no book
     * state for a quest that is not there.
     */
    public static void openOn(String questId) {
        if (questId == null || questId.isEmpty() || cacheEntryFor(questId) == null) {
            return;
        }
        if (!checkOpenAllowed()) {
            return;
        }
        QuestBookFocus.request(questId);
        ArmatureClient.openScreen(Tenet.QUEST_BOOK_SCREEN);
    }

    /**
     * Whether the book may open right now, telling the player when it may not.
     *
     * <p>The one choke every client-side open path asks — the keybind, the inventory button, the
     * book item's client half, a recipe viewer's {@link #openOn}, and the {@code open_book} payload
     * handler — so a pack that disables its book refuses the same way everywhere: one sentence in
     * chat instead of a screen. The server refuses {@code /tenet open_book} itself, because that
     * path never reaches a client check; every other path ends here.
     *
     * @return true when the book may open; false after messaging the player
     */
    public static boolean checkOpenAllowed() {
        if (!ClientQuestCache.guiDisabled()) {
            return true;
        }
        Minecraft game = Minecraft.getInstance();
        if (game.player != null) {
            game.player.sendSystemMessage(
                    Component.translatable("tenet.screen.book_disabled"));
        }
        return false;
    }

    public static void forgetViewState() {
        selectedChapter = null;
        frameChapterActive = false;
        selectedQuest = null;
        multiSelection.clear();
        // The viewer request goes with the selection: it names a quest of the server being left, and a
        // book opened on the next server would look for a quest that is not there.
        QuestBookFocus.clear();
        // And the preview ask with it: it names a view of the server being left, and a book opened on
        // the next server must open as its own editor or reader rather than as somebody's preview.
        clearPreviewRequest();
        // And the intro with it: its rung names that server's book, and a book opened on the next
        // server starts its own tour rather than resuming this one's. The seen flag stays — it is
        // the client's, not the server's.
        IntroGuide.clearSession();
        // The clipboard goes too: another server's quests are not this one's to paste, and a tree
        // carried across a disconnect is an op the new server would dutifully apply to its own files.
        ClientEditorClipboard.clear();
        // And the template landing context with it: it names an entry of the server being left, and
        // a condition applied on the next server must land where the author is there. The shelf
        // itself stays — templates are this client's, not the server's.
        dev.ellipog.tenet.client.dev.ComponentTemplates.clearEntryContext();
        // And the server's lists, for the same reason: a picker still offering the last server's worlds
        // or structures would write an id the new one has never heard of.
        ClientServerLists.clear();
        // And the stages: a gate answered from the last server's flags would open a quest this one
        // has not unlocked.
        ClientStages.clear();
        VIEW.setScale(1.0F);
        VIEW.setOffset(0, 0);
        pannedChapter = null;
        centred = false;
        // The warning memory goes with them, for the same reason as everything else here: it describes
        // a chapter of a server this client is no longer connected to. Without this, rejoining the same
        // server would stay quiet about a chapter theme that is still misspelled.
        //
        // There is no theme to release any more, and there were two calls here that did it. A chapter's
        // palette lives inside one frame now, so there is nothing that could outlive a disconnect.
        warnedThemeFor = null;

        // The sidebar's outline goes too, and the revision with it. The outline holds which groups the
        // player had collapsed, which is a fact about a questline on a server this client is no longer
        // connected to -- so keeping it would open the next server's book with the last one's collapses,
        // and `-1` means the next tree definitely re-seeds rather than possibly matching a revision it
        // happens to share.
        sidebar = null;
        sidebarChapters = List.of();
        sidebarRevision = -1;
        sidebarProgress = -1;
        sidebarText = -1;
        // And the player's own expansions, for the reason the outline goes: they belong to a questline
        // on a server this client has left.
        sidebarExpansion.clear();
    }

    @Override
    public void removed() {
        super.removed();
        // Exits end with their world: any cleanup a close deferred runs now rather than never — several
        // free drafts and close commits, and there is no next settle coming.
        runAllTeardowns();
        panelDepartStarts.clear();
        departRails.clear();
        // And the departed rows, if the book closes mid-collapse: unregistered orphans nobody
        // draws after this, dropped with the screen rather than left for a settle that never comes.
        sidebarDeparted.clear();
        sidebarDepartAnchor = null;
        // The pending values die with the screen: reopening reads the server's copy, and a draft from
        // a session that is over must not answer for it.
        fieldDraft.clear();
        elementDraft.clear();
        linkDraft.clear();
        // Nothing to undo, and that is worth recording because there used to be three lines here.
        //
        // A chapter's theme was a global claim in an earlier round: it was applied when the chapter was
        // opened and had to be released when the book closed, or browsing the gallery and then walking
        // away left that chapter's palette on every other Armature screen until the client restarted.
        // The release was written correctly; the need for it was the design being wrong.
        //
        // A chapter's palette is a *region* now -- a scope around the canvas and the overlay, closed on
        // every exit path by the compiler -- so leaving the book cannot leave anything behind. This
        // comment stays so that the next person does not add the release back.
        warnedThemeFor = null;
    }

    // ------------------------------------------------------------------
    // Appearance
    // ------------------------------------------------------------------

    /**
     * The palette the canvas and the quest overlay are drawn in: the chapter's theme, or the main one.
     *
     * <h2>This is the whole of the chapter-theme rule, and it is deliberately four lines</h2>
     *
     * <p>A chapter's theme used to be an <i>override</i> of the player's own: it won while you were in
     * that chapter, a click declined it, the decline lasted one visit, and there were two flags to
     * remember that. All of it is gone, and not because it was buggy — it worked. It is gone because the
     * two things were never competing: "how do I want this program to look" and "what does this chapter
     * look like" are different questions, answered for different regions of the screen. Two palettes,
     * live at once, with no rule between them.
     *
     * <p>What that buys, concretely: a player can be looking at a violet chapter's canvas inside an
     * otherwise-unchanged book, and there is no state in which a control reports one theme while the
     * screen shows another.
     *
     * <h2>Quest-level themes slot in here, and nowhere else</h2>
     *
     * <p>A quest may override its chapter's palette. That merge is a {@code ThemePatch} composition —
     * main → chapter → quest — and this method is the single place it happens, which is why the chapter's
     * patch will be applied here rather than at the call site. Nothing else in this screen knows how a
     * palette is assembled.
     *
     * <p><b>A name this build has no theme for falls back and says so</b>, once per chapter. Ignoring it
     * silently would make a typo indistinguishable from a deliberately plain chapter; refusing to draw
     * the chapter would let one bad string block a player from their own quests. See
     * {@link #warnAboutThemeOnce}.
     */
    static Theme viewportTheme() {
        String chapter = effectiveChapter();
        JsonObject pending = pendingChapterTheme;
        if (chapter != null && pending != null) {
            // A drag's own patch, drawn in place of the file's: the canvas follows the gesture before
            // anything is written. The patch is whole -- the same one the release writes -- so the two
            // cannot show different looks for a frame.
            return ChapterTheme.compose(ClientQuestCache.chapterTheme(chapter), pending,
                    ClientAppearance.LOOK.main());
        }
        return themeFor(chapter);
    }

    /**
     * The palette one chapter asks for, by id: its named theme with its token patch over it, or the
     * player's own when it names neither.
     *
     * <p>Takes the chapter rather than reading the effective one, because the sidebar's counts are
     * drawn for every chapter with something waiting — each row's count is that chapter's own mark,
     * and it has to resolve the palette of the chapter it counts, not of the one on screen.
     */
    static Theme themeFor(String chapter) {
        if (chapter == null) {
            return ClientAppearance.LOOK.main();
        }
        String named = ClientQuestCache.chapterTheme(chapter);
        if (named != null && !named.isBlank() && !ChapterTheme.known(named)) {
            warnAboutThemeOnce(chapter, named);
        }
        // The chapter's token patch composes over whatever the name resolved to — including over the
        // book's own theme, for a chapter that set colours without naming one. What this returns is
        // final for the chapter's content: the book's look (and any edits made in its tab) is the
        // default a chapter speaks over, so nothing is re-applied on top. See `ChapterTheme.compose`.
        return ChapterTheme.compose(named, ClientQuestCache.chapterThemePatch(chapter),
                ClientAppearance.LOOK.main());
    }

    /**
     * Whether the open overlay belongs to the chapter rather than to the player.
     *
     * <p>The quest card, the item picker and the naming card are all about the chapter on screen — the
     * picker is choosing that chapter's or its quest's icon, the naming card is renaming that chapter.
     * The party, choice and rewards cards are the player's own, wherever they are standing, so they keep
     * the main theme. This is the one list, so a modal added later is themed by a decision rather than
     * by whether somebody remembered to open a scope.
     */
    private boolean chapterBoundOverlay() {
        return overlay == PanelKind.QUEST || overlay == PanelKind.PICKER || overlay == PanelKind.NAMING
                || overlay == PanelKind.ELEMENT;
    }

    /**
     * The palette a tooltip belongs to: the surface under the pointer, or the book's own.
     *
     * <p>A tooltip describes what it is next to, so it takes that surface's theme — the caption over a
     * chapter's canvas wears the chapter's colours, and one over the sidebar wears the book's. Chrome
     * answers with {@link ArmatureTheme#chrome()} rather than the main theme, so a tooltip drawn while
     * a chapter scope happens to be open cannot inherit it by accident.
     */
    private Theme tooltipTheme(int mouseX, int mouseY) {
        if (chapterBoundOverlay() || (overlay == PanelKind.NONE && inCanvas(mouseX, mouseY))) {
            return viewportTheme();
        }
        return ArmatureTheme.chrome();
    }

    /**
     * Reports a chapter whose theme name this build cannot resolve, once per chapter.
     *
     * <p>Warned on the client rather than the server, and that boundary is deliberate rather than an
     * omission: the theme catalogue is a client concept, and a dedicated server has no appearance and no
     * themes, so teaching the validator about them would make a quest file depend on which client reads
     * it. {@code Chapter.theme} carries the same argument.
     *
     * <p>Once per chapter because this runs inside a per-frame drawing path: a line of log per frame for
     * one misspelled name would bury every other message the client writes.
     */
    private static void warnAboutThemeOnce(String chapter, String named) {
        if (chapter.equals(warnedThemeFor)) {
            return;
        }
        warnedThemeFor = chapter;
        Constants.LOG.warn("chapter '{}' asks to be drawn in a theme called '{}', which this"
                + " build does not have, so your own theme is in use. The themes it has are: {}",
                chapter, named, Themes.names());
    }

    // ------------------------------------------------------------------
    // Appearance, and the two controls that used to be here
    // ------------------------------------------------------------------
    //
    // `cycleTheme()` and `toggleMotion()` were the callbacks for the sidebar's two appearance rows, and
    // both have gone with the buttons. Neither did any work: one called `ClientAppearance.LOOK.cycleTheme()` and
    // the other `ClientAppearance.LOOK.setMotion(!ClientAppearance.LOOK.motion())`, and what they added was a `rebuildWidgets()`
    // to refresh a label that no longer exists.
    //
    // That is the right split rather than a deletion that loses something. The operations live in
    // Armature, are tested there, and are what a dev-mode screen will call; the screen only ever wrapped
    // them to redraw a button. A dev-mode control that cycles the theme does not need this class to know
    // about it at all, which is the property worth having -- the alternative is that every screen wanting
    // a theme control reimplements the rebuild.
    //
    // What remains here is `viewportTheme()`, which is not a control: it decides which palette a *chapter*
    // is drawn in, and that is this screen's business because a chapter is this screen's concept. A
    // theme control is not.
}
