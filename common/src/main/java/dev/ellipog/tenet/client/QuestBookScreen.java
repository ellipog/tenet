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

    void clearFrameSnapshot() {
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

    /** One block of the reader's description: what it is, the lines it was wrapped to, and the token look over it. */
    record ProseBlock(dev.ellipog.armature.client.ui.kit.RichText.Paragraph paragraph,
            List<dev.ellipog.armature.client.ui.kit.RichText.Line> lines,
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
    BookGeometry.Rect panelDragRail;

    /**
     * Which rail the hand is holding, for the whole of a resize.
     *
     * <p>A field rather than a read of the arrangement, and that is a fix rather than tidiness: every rail
     * carries a grip on its inner edge, so the hand can be on any of the three. Every part of the gesture —
     * the clamp its kind gives the width, the rectangle the live width is fed to, and which number is
     * written when the hand lets go — has to be about <b>the rail that was grabbed</b>. Read from the first
     * panel, grabbing the child's edge resized and then remembered the <i>first</i> panel's width.
     */
    PanelKind panelDragKind = PanelKind.NONE;

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
    int panelLiveWidth;

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
    BookGeometry.Rect sidebarSlideFromRect;

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
    final List<SidebarDeparted> sidebarDeparted = new ArrayList<>();

    /**
     * The box the departed rows were built for, filed when the collapse starts.
     *
     * <p>The travel's anchor: orphans and the cluster are positioned from this, never from the
     * wipe's start, so a collapse that reverses an expand mid-wipe continues from mid-wipe
     * instead of snapping the rows back to full for a frame. Null outside a departure.
     */
    BookGeometry.Rect sidebarDepartAnchor;

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
    BookGeometry.Rect sidebarDragRail;

    /**
     * A live width override while the edge is held: {@link #sidebarEffective} answers with it
     * instead of the file, so the chrome, the rows and the controls all follow the pointer
     * without the settings file being written sixty times a second. Null when the hand is off.
     */
    Integer sidebarLiveWidth;

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
    ScrollBar heldBar;

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

    /** The fields the drawer built, for the label half of their gesture. See {@link BookInput#armLabelScrub}. */
    final List<dev.ellipog.tenet.client.dev.ScrubField> rowFields = new ArrayList<>();

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
    double pickerPointerX(double mouseX) {
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

    /**
     * The curve being bent by hand: which line, whether the drag has started, and the bow it previews.
     *
     * <p>The preview is a field rather than a file write because a drag is one edit: the drawing reads it
     * so the curve follows the hand, and the release is the one op that makes it real.
     */
    String bendDragFrom;
    String bendDragTo;
    Double bendPreview;

    /** Which handle of that line is being dragged: "from", "to" or "bend". */
    String bendDragKind;
    /** The angle an end handle is previewing, or null. */
    Double anchorPreview;

    /** A split control point being dragged, as `[along, across]`. */
    java.util.List<Double> handlePreview;

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

    boolean inCanvas(double mouseX, double mouseY) {
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
     * {@link BookInput#mouseScrolled} routes the wheel by it.
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
    void setSidebarCollapsed(boolean collapsed) {
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

    /** The pin toggle's answer for one state, as pure inputs. See {@link BookInput#toggleSidebarPinned}. */
    static SidebarPinAfter pinAfterToggle(boolean pinned, boolean collapsed, boolean peeking) {
        boolean next = !pinned;
        if (next && (peeking || collapsed)) {
            return new SidebarPinAfter(true, false, false);
        }
        return new SidebarPinAfter(next, collapsed, peeking);
    }

    /**
     * Shows the collapsed list as a floating preview, or puts the strip back.
     *
     * <p>Writes nothing: the file keeps saying "collapsed" while the pointer hovers, which is
     * what makes lifting the pointer the whole of the way back. The wipe runs either way, so a
     * peek arrives like an expand and leaves like a collapse.
     */
    void setSidebarPeeking(boolean peeking) {
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
    int panelContentFloor(PanelKind kind) {
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
     * The band the party's faces fill: <b>the rail's own body</b>.
     *
     * <p>The same band every other kind's content gets, and not the card's inset rectangle: a card's body
     * runs the full height of a content-sized card, but a rail's must stop above the footer band or the
     * last roster rows would sit under the controls.
     */
    BookGeometry.Rect partyBody() {
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
    boolean pressDropdown(double mouseX, double mouseY, int button) {
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
            bookSettings.measure = Measure.cached(Measure.of(text -> bookSettings.measuring.textWidth(text),
                    OverlayLayout.LINE_HEIGHT),
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

    /** Minecraft's call; the book's answer is {@link BookInput#mouseClicked}. */
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        return bookInput.mouseClicked(event, doubleClick);
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

    /** Minecraft's call; the book's answer is {@link BookInput#mouseDragged}. */
    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        return bookInput.mouseDragged(event, dragX, dragY);
    }

    /** Minecraft's call; the book's answer is {@link BookInput#mouseReleased}. */
    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        return bookInput.mouseReleased(event);
    }

    /** Minecraft's call; the book's answer is {@link BookInput#mouseScrolled}. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return bookInput.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Minecraft's call; the book's answer is {@link BookInput#charTyped}. */
    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        return bookInput.charTyped(event);
    }

    /** Minecraft's call; the book's answer is {@link BookInput#keyPressed}. */
    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        return bookInput.keyPressed(event);
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
    // The base class, for the slices that answer Minecraft's calls
    // ------------------------------------------------------------------

    /** {@code super.charTyped}, for the slice that answers it. */
    boolean superCharTyped(net.minecraft.client.input.CharacterEvent event) {
        return super.charTyped(event);
    }

    /** {@code super.keyPressed}, for the slice that answers it. */
    boolean superKeyPressed(net.minecraft.client.input.KeyEvent event) {
        return super.keyPressed(event);
    }

    /** {@code super.mouseClicked}, for the slice that answers it. */
    boolean superMouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        return super.mouseClicked(event, doubleClick);
    }

    /** {@code super.mouseDragged}, for the slice that answers it. */
    boolean superMouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        return super.mouseDragged(event, dragX, dragY);
    }

    /** {@code super.mouseReleased}, for the slice that answers it. */
    boolean superMouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        return super.mouseReleased(event);
    }

    /** {@code super.mouseScrolled}, for the slice that answers it. */
    boolean superMouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
