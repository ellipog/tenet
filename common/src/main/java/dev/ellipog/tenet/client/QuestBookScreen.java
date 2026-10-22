package dev.ellipog.tenet.client;

import dev.ellipog.armature.api.client.ArmatureClient;
import dev.ellipog.armature.client.ArmatureButton;
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
import dev.ellipog.tenet.net.TenetNetworking;
import dev.ellipog.tenet.client.ClientChapterReplica;
import dev.ellipog.tenet.client.ClientEditReplies;
import dev.ellipog.tenet.client.viewer.QuestBookFocus;
import dev.ellipog.tenet.client.viewer.RecipeLookups;
import dev.ellipog.tenet.quest.CanvasElement;
import dev.ellipog.tenet.client.dev.ChapterTheme;
import dev.ellipog.tenet.client.dev.ClientEditorClipboard;
import dev.ellipog.tenet.client.dev.Labels;
import dev.ellipog.tenet.client.dev.QuestSettingsLayout;
import dev.ellipog.tenet.client.dev.QuestPanelLayout;
import dev.ellipog.tenet.client.dev.ToolsLayout;
import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.client.ClientPartyCache;
import dev.ellipog.tenet.progress.QuestState;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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

    private static final int OVERLAY_MARGIN = BookGeometry.OVERLAY_MARGIN;

    /** The sidebar's footer, as two rows of its own. Four controls do not fit across one. */
    private static final int ROW_HEIGHT = BookGeometry.ROW_HEIGHT;
    private static final int ROW_GAP = BookGeometry.ROW_GAP;

    /** The quest book's shared state. See {@link BookScreenState}. */
    final BookScreenState bookState = new BookScreenState(this);

    /** The quest book's frame. See {@link BookFrame}. */
    final BookFrame bookFrame = new BookFrame(this);

    /** The quest book's content. See {@link BookContent}. */
    final BookContent bookContent = new BookContent(this);

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

    void snapshotFrame() {
        bookState.frameActive = false;
        BookScreenState.frameChapterActive = false;
        bookState.frameMayEdit = dev.ellipog.tenet.QuestAuthority.mayEditClient(minecraft);
        boolean author = bookState.frameMayEdit && !bookCanvas.previewAsPlayer;
        SidebarState sidebar = sidebarEffective();
        if (bookState.geometry == null || bookState.geometryWidth != width || bookState.geometryHeight != height
                || bookState.geometryAuthor != author || !bookState.geometrySidebar.equals(sidebar)) {
            bookState.geometry = new BookGeometry(width, height, author, author, sidebar);
            bookState.geometryWidth = width;
            bookState.geometryHeight = height;
            bookState.geometryAuthor = author;
            bookState.geometrySidebar = sidebar;
        }
        bookState.frameGeometry = bookState.geometry;
        BookGeometry.Rect canvas = bookState.frameGeometry.canvas();
        bookState.frameViewport = BookScreenState.VIEW.bounds(canvas.x(), canvas.y(), canvas.width(), canvas.height());
        BookScreenState.frameChapter = BookContent.computeEffectiveChapter();
        bookState.frameActive = true;
        BookScreenState.frameChapterActive = true;
    }

    void clearFrameSnapshot() {
        bookState.frameActive = false;
        BookScreenState.frameChapterActive = false;
        bookState.frameGeometry = null;
        bookState.frameViewport = null;
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
        int width = bookState.sidebarLiveWidth != null ? bookState.sidebarLiveWidth : stored.width();
        if (!stored.floating()) {
            // Docked is the old column, always expanded: the old mode cannot collapse, whatever
            // the file's other answers say.
            return new SidebarState(width, false, true, false);
        }
        if (stored.collapsed() && bookState.sidebarPeeking) {
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
        if (bookState.frameActive && bookState.frameGeometry != null) {
            return bookState.frameGeometry;
        }
        // A previewing operator gets the reader's geometry: the card, no band, no full-bleed. That is
        // the whole point of the preview — the panel shrinks to exactly what a player without the
        // permission sees — and the way back out is the floating pill `buildViewCluster` puts over the
        // canvas, not the band, which this geometry does not have.
        boolean author = questEditor.mayEdit() && !bookCanvas.previewAsPlayer;
        SidebarState sidebar = sidebarEffective();
        if (bookState.geometry == null || bookState.geometryWidth != width || bookState.geometryHeight != height
                || bookState.geometryAuthor != author || !bookState.geometrySidebar.equals(sidebar)) {
            // Full-bleed for an author, a card for a reader, and a band for the same author. See
            // `BookGeometry`'s constructor: the margin and the maximum size exist so a reading panel does
            // not read as a wall, and an author is not reading -- every spare pixel is canvas or colour
            // list.
            bookState.geometry = new BookGeometry(width, height, author, author, sidebar);
            bookState.geometryWidth = width;
            bookState.geometryHeight = height;
            bookState.geometryAuthor = author;
            bookState.geometrySidebar = sidebar;
        }
        return bookState.geometry;
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
    static final float MIN_ZOOM = 0.2F;
    static final float MAX_ZOOM = 2.2F;

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
        if (bookState.slideBucket == null || !(added instanceof AbstractWidget widget)) {
            return;
        }
        bookState.slideWidgets.computeIfAbsent(bookState.slideBucket, kind -> new java.util.ArrayList<>()).add(widget);
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

    /** A cleanup with the arrangement it closed under, so a settle can tell stale from owed. */
    record StampedTeardown(long epoch, Runnable teardown) {
    }

    /**
     * Runs a rail's owed cleanups, if any — whatever arrangement they were stamped under — then
     * forgets them.
     *
     * <p>For reopening (teardown before setup, unconditionally: the cleanup predates this open) and
     * for {@code removed}. The tick's settle is the selective one; see its own note.
     */
    private void runTeardowns(PanelKind kind) {
        java.util.List<StampedTeardown> owed = bookState.pendingTeardowns.remove(kind);
        if (owed != null) {
            for (StampedTeardown stamped : owed) {
                stamped.teardown().run();
            }
        }
    }

    /** Runs every owed cleanup. For {@code removed}: exits end when their world does. */
    private void runAllTeardowns() {
        for (PanelKind kind : java.util.List.copyOf(bookState.pendingTeardowns.keySet())) {
            runTeardowns(kind);
        }
    }

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
        StampedTeardown stamped = new StampedTeardown(bookState.arrangementEpoch, once);
        for (PanelKind kind : kinds) {
            bookState.pendingTeardowns.computeIfAbsent(kind, key -> new java.util.ArrayList<>()).add(stamped);
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
        java.util.LinkedHashSet<PanelKind> keys = new java.util.LinkedHashSet<>(bookState.lastDeparted);
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
     * {@link BookScreenState#panelDepartStarts}.
     */
    long panelDepartElapsed(PanelKind kind, long now) {
        Long start = bookState.panelDepartStarts.get(kind);
        return start == null ? Long.MAX_VALUE : now - start;
    }

    /** Whether one rail is still sinking out, which is when its surface is still drawn. */
    boolean panelDeparting(PanelKind kind, long now) {
        return bookState.panelDepartStarts.containsKey(kind)
                && PanelLayout.revealed(panelDepartElapsed(kind, now), panelExitMillis()) < 1F;
    }

    // ------------------------------------------------------------------
    // The reward-table panels
    // ------------------------------------------------------------------

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

    /** One field of a table, and how the text typed into a box becomes its value. */
    record TableEdit(String path, dev.ellipog.tenet.client.dev.TableRowFields.Kind kind) {
    }

    /** One card in the back stack: the quest, and the body state it was left in. */
    record CardVisit(String questId, int scrollY, Set<String> folded) {
    }

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

    /**
     * How long the pointer must rest on the strip before the peek opens, in milliseconds.
     *
     * <p>Without it every crossing of the panel's left edge — on the way to a node, to the view
     * cluster, to anywhere left — would unfold the list under the hand and fold it back a moment
     * later. A crossing never dwells; an intention does. Leaving stays instant, so the preview
     * never overstays: arriving is the part that has to prove itself, not leaving.
     */
    private static final long SIDEBAR_PEEK_DWELL_MILLIS = 120;

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
    record SidebarDeparted(AbstractWidget widget, int x, int y) {
    }

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
        if (!bookState.colourPopover.isOpen() || bookState.popoverOpenedAt < 0) {
            return 0;
        }
        float arrived = PanelLayout.revealed(now - bookState.popoverOpenedAt, POPOVER_SETTLE_MILLIS);
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
        if (!bookState.colourPopover.isOpen()) {
            return;
        }
        int offset = popoverOffset(now);
        for (net.minecraft.client.gui.components.AbstractWidget widget : bookState.colourPopover.widgets()) {
            Integer base = bookState.popoverBaseX.get(widget);
            if (base != null) {
                widget.setX(base + offset);
            }
        }
    }

    /** Files the picker's fields at their placed positions and stamps the settle. Both openers call. */
    void filePopoverArrival() {
        bookState.popoverBaseX.clear();
        for (net.minecraft.client.gui.components.AbstractWidget widget : bookState.colourPopover.widgets()) {
            bookState.popoverBaseX.put(widget, widget.getX());
        }
        bookState.popoverOpenedAt = net.minecraft.util.Util.getMillis();
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
        return bookState.colourPopover.isOpen() ? mouseX - popoverOffset(net.minecraft.util.Util.getMillis()) : mouseX;
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

    /** The badge counts in force, recomputed when the progress, the tree, the viewer or the marks change. */
    RewardCounts rewardCounts() {
        long progress = ClientQuestCache.progressRevision();
        long tree = ClientQuestCache.treeRevision();
        long marks = bookState.pendingClaims.revision();
        java.util.UUID self = minecraft.player == null ? null : minecraft.player.getUUID();
        RewardCounts current = bookState.rewardCounts;
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
        bookState.rewardCounts = new RewardCounts(progress, tree, marks, self,
                Map.copyOf(byQuest), Map.copyOf(byChapter));
        return bookState.rewardCounts;
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
        boolean whole = bookState.pendingClaims.isMarked(PendingClaims.questKey(entry.id()))
                || bookState.pendingClaims.isMarked(PendingClaims.chapterKey(entry.chapterId()));
        return PendingClaims.outstanding(entry.rewards().size(),
                index -> ClientQuestCache.canClaimReward(self, entry, index),
                index -> whole || bookState.pendingClaims.isMarked(PendingClaims.rewardKey(entry.id(), index)));
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
     * shape as {@link BookScreenState#rewardCounts} deliberately — but kept a separate cache because it answers a
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

    /** The per-chapter completion in force, recomputed when the progress, the tree or the viewer moves. */
    ChapterCounts chapterCounts() {
        long progress = ClientQuestCache.progressRevision();
        long tree = ClientQuestCache.treeRevision();
        java.util.UUID self = minecraft.player == null ? null : minecraft.player.getUUID();
        boolean authoring = mayEditNow();
        ChapterCounts current = bookState.chapterCounts;
        if (current != null && current.progress() == progress && current.tree() == tree
                && java.util.Objects.equals(current.player(), self) && current.authoring() == authoring) {
            return current;
        }

        // Tallied as a mutable pair while walking, because the walk sees one entry at a time and a
        // `ChapterProgress` is immutable by design; the pairs become records in one pass after.
        Map<String, int[]> tally = new LinkedHashMap<>();
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            if (!authoring && !BookContent.questVisible(entry.id())) {
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
        bookState.chapterCounts = new ChapterCounts(progress, tree, self, authoring, counts);
        return bookState.chapterCounts;
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

    record PendingLabel(BookGeometry.Rect anchor, String text) {
    }

    record RowTooltip(Slot box, List<String> lines) {
    }

    record RowItem(Slot box, RecipeLookups.Target target) {
    }

    record FilterRow(Slot box, String expression) {}

    record CheckmarkRow(Slot box, String questId, int taskIndex) {}

    record RewardClaimRow(Slot box, String questId, int rewardIndex) {}

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
        if (bookState.tablePickPath != null) {
            return false;
        }
        if (bookState.pickTarget != null && bookState.pickTarget != PickTarget.QUEST) {
            return true;
        }
        return isIconPath(bookState.pickingItemPath);
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
    static final String INLINE_STATUS_KEY = SidebarLayout.INLINE_PREFIX + "status";

    private static void watchSources() {
        if (BookScreenState.watchingSources) {
            return;
        }
        BookScreenState.watchingSources = true;
        ArmatureLive.watch("tenet.tree", ClientQuestCache::treeRevision);
        ArmatureLive.watch("tenet.party", ClientPartyCache::rosterRevision);
    }

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

    /** The backing panel behind the three view buttons, drawn so they read as one cluster. */
    private BookGeometry.Rect viewControls() {
        return geometry().viewControls();
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
        BookGeometry.Rect rect = geometry().sidebarViewport(mayEditNow(), bookState.sidebarReserveScrollbar);
        // The pitch goes with the bounds, because both are the frame's: this list moves a row at a time
        // -- the wheel snaps to `pitch()`, and the bar's page keeps one of those rows on screen -- and
        // the bar is where both of those numbers now live.
        bookState.sidebarView.bar().pitch(SidebarLayout.pitch());
        return bookState.sidebarView.viewport().bounds(rect.x(), rect.y(), rect.width(), rect.height());
    }

    /**
     * Offsets one travelling widget by this frame's wipe, filing its placed position first.
     *
     * <p>One method for the rows and the cluster because the filing is the delicate half: the
     * base is read on the first slide frame rather than at build, since a field built for a
     * scrolling list is constructed at 0,0 and placed by its view afterwards.
     */
    private void travelSidebarWidget(AbstractWidget widget, int travelX, int travelY) {
        int[] base = bookState.sidebarTravelBase.get(widget);
        if (base == null) {
            base = new int[] {widget.getX(), widget.getY()};
            bookState.sidebarTravelBase.put(widget, base);
        }
        widget.setX(base[0] + travelX);
        widget.setY(base[1] + travelY);
    }

    /** Puts one travelled widget back where the build placed it. See {@link #travelSidebarWidget}. */
    private void restoreSidebarTravel(AbstractWidget widget) {
        int[] base = bookState.sidebarTravelBase.get(widget);
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
        if (!sidebarSliding(now) || bookState.sidebarDepartAnchor == null) {
            return;
        }
        BookGeometry.Rect wipe = sidebarWipeRect(now);
        int travelX = wipe.x() - bookState.sidebarDepartAnchor.x()
                + wipe.width() - bookState.sidebarDepartAnchor.width();
        int travelY = wipe.y() - bookState.sidebarDepartAnchor.y();
        float progress = PanelLayout.revealed(now - bookState.sidebarSlideStart, panelRevealMillis());
        int lead = Math.round(bookState.sidebarDepartOverhang * progress);
        try (GuiRenderer.Scoped clip = renderer.clip(wipe.x(), wipe.y(), wipe.right(), wipe.bottom())) {
            for (SidebarDeparted departed : bookState.sidebarDeparted) {
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
        return bookState.sidebarSlideStart >= 0 && now - bookState.sidebarSlideStart < panelRevealMillis();
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
        if (bookState.sidebarSlideStart < 0 || bookState.sidebarSlideFromRect == null) {
            return geometry().sidebar();
        }
        return PanelLayout.sidebarWipeRect(bookState.sidebarSlideFromRect, geometry().sidebar(),
                now - bookState.sidebarSlideStart, panelRevealMillis());
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
        bookState.sidebarSlideFromRect =
                sidebarSliding(now) ? sidebarWipeRect(now) : geometry().sidebar();
        bookState.sidebarSlideStart = now;
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
        bookState.sidebarDeparted.clear();
        for (AbstractWidget widget : bookState.sidebarRowWidgets) {
            bookState.sidebarDeparted.add(new SidebarDeparted(widget, widget.getX(), widget.getY()));
            removeWidget(widget);
            bookState.buttons.remove(widget);
            bookState.sidebarTravelBase.remove(widget);
        }
        bookState.sidebarRowWidgets.clear();
        bookState.sidebarView.clear();
        // Rebound to the collapsed zero-area viewport now, not at the settle: the bar, the wheel
        // and the menu all read this, and for the wipe's length there is no list for them to act on.
        sidebarViewport();
        bookState.sidebarDepartAnchor = geometry().sidebarExpandedRect();
        BookGeometry.Rect target = geometry().sidebar();
        int endShiftX = target.x() - bookState.sidebarDepartAnchor.x()
                + target.width() - bookState.sidebarDepartAnchor.width();
        int overhang = 0;
        for (SidebarDeparted departed : bookState.sidebarDeparted) {
            overhang = Math.max(overhang,
                    departed.x() + departed.widget().getWidth() + endShiftX - bookFrame.panelRect().x());
        }
        bookState.sidebarDepartOverhang = overhang;
    }

    /** Slides the list away to its sliver, or brings it back, and writes the choice. */
    void setSidebarCollapsed(boolean collapsed) {
        if (DevMode.isSidebarCollapsed() == collapsed) {
            return;
        }
        beginSidebarSlide();
        DevMode.setSidebarCollapsed(collapsed);
        if (collapsed) {
            bookState.sidebarPeeking = false;
            bookState.sidebarPeekCooling = true;
            // Out with the rows aboard, as orphans: filed and removed here, drawn by hand till
            // the wipe lands (see `sidebarDeparted`), so the full list rides the card back
            // instead of popping to the sliver at once — and nothing interactive is left where
            // the card was, which is the ghost the old kept-widgets ride flashed.
            bookState.sidebarDeparting = panelRevealMillis() > 0;
            if (bookState.sidebarDeparting) {
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
            bookState.sidebarBoxHovered = false;
            bookState.sidebarPeekLeaveStart = -1;
            bookState.sidebarDeparting = false;
            rebuildWidgets();
            cancelSidebarSlideIfStatic();
        }
    }

    /**
     * What toggling the pin leaves behind: the three answers the screen must hold after it.
     *
     * <p>A static truth table rather than a screen, like {@link BookCanvas#editEffective}: pinning means
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
        if (bookState.sidebarPeeking == peeking) {
            return;
        }
        beginSidebarSlide();
        bookState.sidebarPeeking = peeking;
        if (!peeking) {
            // An acted unpeek (Escape, a map click) with the pointer still over the strip must
            // not re-dwell at once: the cooldown below waits for the pointer to leave first.
            bookState.sidebarPeekCooling = true;
            // Out with the rows aboard, like any other collapse: filed as orphans and drawn by
            // hand till the wipe lands.
            bookState.sidebarDeparting = panelRevealMillis() > 0;
            if (bookState.sidebarDeparting) {
                orphanSidebarRows();
            }
            else {
                rebuildWidgets();
                cancelSidebarSlideIfStatic();
            }
            // A transition restarts every hover clock: a stale leave-stamp would otherwise end the
            // new state the frame it begins.
            bookState.sidebarPeekLeaveStart = -1;
            bookState.sidebarPeekHoverStart = -1;
            return;
        }
        // A transition restarts every hover clock: a stale leave-stamp would otherwise end the
        // new state the frame it begins.
        bookState.sidebarPeekLeaveStart = -1;
        bookState.sidebarPeekHoverStart = -1;
        // An arrival, even one that cuts a departure short: the preview rebuilds its rows live
        // below, so no orphans survive the switch (see `init`, which drops them with the wipe).
        bookState.sidebarDeparting = false;
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
        if (bookState.sidebarSlideFromRect != null && bookState.sidebarSlideFromRect.equals(geometry().sidebar())) {
            bookState.sidebarSlideStart = -1;
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
        if (owned || sidebarSliding(now) || !bookState.menu.isEmpty() || bookState.colourPopover.isOpen()) {
            bookState.sidebarPeekHoverStart = -1;
            return;
        }
        if (!DevMode.isSidebarFloating()) {
            bookState.sidebarPeekHoverStart = -1;
            bookState.sidebarPeekCooling = false;
            return;
        }
        if (!bookState.sidebarPeeking && !DevMode.isSidebarCollapsed()) {
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
            if (!DevMode.isSidebarPinned() && bookState.sidebarBoxHovered && !inside
                    && bookState.overlay == PanelKind.NONE && bookState.overlay2 == PanelKind.NONE
                            && !bookState.dockOpen
                    && bookState.sidebarDragKey == null && bookState.sidebarDragRail == null) {
                if (bookState.sidebarPeekLeaveStart < 0) {
                    bookState.sidebarPeekLeaveStart = now;
                }
                else if (now - bookState.sidebarPeekLeaveStart >= SIDEBAR_PEEK_LEAVE_MILLIS) {
                    bookState.sidebarPeekLeaveStart = -1;
                    bookState.sidebarBoxHovered = false;
                    setSidebarCollapsed(true);
                }
            }
            else {
                bookState.sidebarPeekLeaveStart = -1;
                bookState.sidebarBoxHovered = geometry().sidebar().contains(hoverX, hoverY);
            }
            return;
        }
        if (!bookState.sidebarPeeking) {
            if (bookState.sidebarPeekCooling) {
                // The last unpeek was an act with the pointer still over the sliver: wait for the
                // pointer to leave before the dwell can start over, or the preview re-opens under
                // a hand that just put it away.
                if (!geometry().sidebar().inflate(SIDEBAR_PEEK_HOVER).contains(hoverX, hoverY)) {
                    bookState.sidebarPeekCooling = false;
                }
                return;
            }
            if (geometry().sidebar().inflate(SIDEBAR_PEEK_HOVER).contains(hoverX, hoverY)) {
                if (bookState.sidebarPeekHoverStart < 0) {
                    bookState.sidebarPeekHoverStart = now;
                }
                else if (now - bookState.sidebarPeekHoverStart >= SIDEBAR_PEEK_DWELL_MILLIS) {
                    bookState.sidebarPeekHoverStart = -1;
                    setSidebarPeeking(true);
                }
            }
            else {
                bookState.sidebarPeekHoverStart = -1;
            }
            return;
        }
        if (!geometry().sidebarExpandedRect().inflate(SIDEBAR_KEEP_HOVER).contains(hoverX, hoverY)) {
            if (bookState.sidebarPeekLeaveStart < 0) {
                bookState.sidebarPeekLeaveStart = now;
            }
            else if (now - bookState.sidebarPeekLeaveStart >= SIDEBAR_PEEK_LEAVE_MILLIS) {
                bookState.sidebarPeekLeaveStart = -1;
                setSidebarPeeking(false);
            }
        }
        else {
            bookState.sidebarPeekLeaveStart = -1;
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
        return bookState.overlay2 != PanelKind.NONE && bookState.overlay2 == kind;
    }

    /**
     * Where a column's header text has to stop: at the fold control when the column carries one.
     *
     * <p>The reading end of {@link #carriesFold}, and it exists so that the drawing of a title and the
     * placing of the control cannot disagree: {@link PanelLayout#headerRight} is the layout's own number,
     * and every header in this book that draws a title inside a column asks for it here.
     */
    int headerTextRoom(BookGeometry.Rect rail) {
        return PanelLayout.headerRight(rail, carriesFold(bookState.surfaceKind));
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
        boolean open = !bookState.dockOpen;
        // The arrival stamp is `applyColumns`' — every rail's enter and exit is — so opening and
        // closing alike animate with no stamp at the call site. See `startPanelReveal`.
        applyColumns(PanelStack.withDock(columns(), open));
        if (!open) {
            // Its transient state goes with it, the same set `closeOverlay` clears for a panel: a colour
            // picker left anchored to a row that is no longer drawn would float over the canvas, and a
            // picker armed for a chapter would greet the next opening aimed at a file nobody chose.
            closeColourPopover();
            bookAssets.clearPickers();
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
        if (bookState.overlay2 == PanelKind.PICKER) {
            itemPicker.closePickerOverlay();
            return;
        }
        if (bookState.overlay2 == PanelKind.TEXTURE) {
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
        return new PanelStack.Columns(bookState.dockOpen ? PanelKind.TOOLS : PanelKind.NONE,
                bookState.overlay, bookState.overlay2,
                bookState.foldChoice);
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
        return bookState.overlay;
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
        bookState.arrangementEpoch++;
        long now = net.minecraft.util.Util.getMillis();
        int enterMs = panelRevealMillis();
        // Snapshotted from the rails as they stand — fields still hold the old arrangement, so the rails
        // are valid — and shown rather than whole: a rail closed mid-enter leaves from where it stood,
        // its current clip, rather than jumping to whole and sinking.
        for (PanelKind kind : before) {
            if (!afterNext.contains(kind)) {
                BookGeometry.Rect rail = panelRail(kind);
                bookState.departRails.put(kind,
                        PanelLayout.revealRect(rail, panelRevealElapsed(kind, now), enterMs));
                bookState.panelDepartStarts.put(kind, now);
            }
        }
        bookState.dockOpen = next.dock() == PanelKind.TOOLS;
        bookState.overlay = next.left();
        bookState.overlay2 = next.right();
        bookState.foldChoice = next.fold();
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
        bookState.lastDeparted = java.util.List.copyOf(departed);
        for (PanelKind kind : after) {
            if (before.contains(kind)) {
                continue;
            }
            // A teardown owed to an earlier close of this same kind runs first: close-then-open has
            // always meant teardown before setup, and the deferred nullings are no exception.
            runTeardowns(kind);
            if (bookState.panelDepartStarts.containsKey(kind)) {
                // Reopened mid-exit: the enter continues from where the exit stood rather than jumping
                // back to a whole rail off screen. Symmetric durations make the velocities roughly
                // match; exactness lives in the helpers' ends, which both hold.
                long exitElapsed = now - bookState.panelDepartStarts.get(kind);
                int exitMs = Math.max(1, panelExitMillis());
                long mapped = enterMs <= 0 ? Long.MAX_VALUE
                        : enterMs - exitElapsed * enterMs / exitMs;
                bookState.panelRevealStarts.put(kind, now - Math.max(0, mapped));
                bookState.panelDepartStarts.remove(kind);
                bookState.departRails.remove(kind);
            }
            else {
                bookRewards.startPanelReveal(kind);
            }
        }
        // **Anything that left the arrangement forgets how far its arrival had got.** Without this, a rail
        // closed mid-slide and opened again later would start its second arrival part-way through the first
        // — a panel that appears already half-drawn, which reads as a glitch rather than as an opening.
        // Pruned here, at the one writer of the arrangement, so no opener has to remember to.
        bookState.panelRevealStarts.keySet().retainAll(after);
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
        if (bookState.editorFloor > 0) {
            return bookState.editorFloor;
        }
        Measure measure = Measure.monospace(6, 9);
        bookState.editorFloor = PanelStack.footerMinimumWidth(List.of(
                        measure.width("Really delete?"),
                        measure.width(Labels.of("tenet.screen.duplicate")),
                        measure.width(Labels.of("tenet.screen.copy")),
                        measure.width(Labels.of("tenet.screen.settings"))),
                EDITOR_ACTION_GAP, BookGeometry.BACK_WIDTH, BookGeometry.MODAL_INSET);
        return bookState.editorFloor;
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
     * is per kind too: {@link BookScreenState#panelLiveWidth} is read only for {@link BookScreenState#panelDragKind}, which is the kind the
     * hand is holding. A width can therefore not leak from one rail to another mid-drag, which is the fault
     * the two fields were introduced to stop and did not.
     */
    private int railWidth(PanelKind kind) {
        if (kind == null || kind == PanelKind.NONE) {
            return 0;
        }
        if (bookState.panelLiveWidth > 0 && bookState.panelDragKind == kind) {
            // A resize in flight: the edge the hand is holding, which is why this is read here rather than
            // written into the settings file on every frame of the drag. Written once, on release.
            return bookState.panelLiveWidth;
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
        int width = panelFolded() ? railWidth(bookState.overlay2) : railWidth(leftKind());
        return bookState.dockOpen ? geometry().panelRailBefore(width, dockRail())
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
        if (kind == bookState.departDrawKind && bookState.departDrawRail != null) {
            return bookState.departDrawRail;
        }
        if (kind == PanelKind.TOOLS) {
            return dockRail();
        }
        BookGeometry.Rect first = columnOneRail();
        if (panelFolded() || bookState.overlay2 == PanelKind.NONE || bookState.overlay2 != kind) {
            return first;
        }
        return geometry().panelRailBefore(railWidth(bookState.overlay2), first);
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
        Long start = bookState.panelRevealStarts.get(kind);
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
        return bookState.panelRevealStarts.containsKey(kind)
                && PanelLayout.revealed(panelRevealElapsed(kind, now), panelRevealMillis()) < 1F;
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
            java.util.List<AbstractWidget> owned = bookState.slideWidgets.get(kind);
            if (owned == null || owned.isEmpty()) {
                continue;
            }
            int offset = PanelLayout.slideOffset(panelRail(kind), panelRevealElapsed(kind, now),
                    duration);
            if (offset != 0) {
                bookState.panelBuildPending = true;
            }
            for (AbstractWidget widget : owned) {
                Integer base = bookState.slideBaseX.get(widget);
                if (base == null) {
                    base = widget.getX();
                    bookState.slideBaseX.put(widget, base);
                }
                widget.setX(base + offset);
                if (offset != 0) {
                    widget.active = false;
                }
            }
        }
    }

    /**
     * The surface the current {@link BookScreenState#surfaceKind} occupies, as its four numbers.
     *
     * <p>Read by every position inside a panel — its header's icon, its body's clip, its footer's controls,
     * its rows' rectangles — so a kind being drawn, built or pressed measures itself against its own rail
     * and never against the one beside it.
     */
    int overlayLeft() {
        return surfaceCard(bookState.surfaceKind).x();
    }

    int overlayTop() {
        return surfaceCard(bookState.surfaceKind).y();
    }

    int overlayWidth() {
        return surfaceCard(bookState.surfaceKind).width();
    }

    int overlayHeight() {
        return surfaceCard(bookState.surfaceKind).height();
    }

    // ------------------------------------------------------------------
    // Content
    // ------------------------------------------------------------------

    /**
     * The chapter the book is showing.
     *
     * <p>Simply this map's first key, or the selection when it is still one of them — and that is enough,
     * because {@link BookContent#chapters()} has already left out every chapter this viewer should not see. The
     * filter is deliberately in that one place: a second copy here would be a second answer to "may this
     * chapter be opened", and the two would disagree the first time one of them changed.
     */
    static String effectiveChapter() {
        if (BookScreenState.frameChapterActive) {
            return BookScreenState.frameChapter;
        }
        return BookContent.computeEffectiveChapter();
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
        if (bookContent.questsInHeld != null && java.util.Objects.equals(bookContent.questsInChapter, chapterId)
                && bookContent.questsInTree == tree && bookContent.questsInProgress == progress
                && bookContent.questsInAuthoring == authoring) {
            return bookContent.questsInHeld;
        }

        List<ClientQuestCache.Entry> built = ClientQuestCache.entries().stream()
                .filter(entry -> entry.chapterId().equals(chapterId))
                // Hidden quests are filtered out rather than withheld: every flag travels with the tree,
                // so a quest that becomes visible because it was completed -- or because a prerequisite
                // was -- needs no second sync to appear. The rules themselves are `QuestVisibility`'s,
                // where a test can hold them; this only asks.
                .filter(entry -> authoring || BookContent.questVisible(entry.id()))
                .toList();
        bookContent.questsInHeld = built;
        bookContent.questsInChapter = chapterId;
        bookContent.questsInTree = tree;
        bookContent.questsInProgress = progress;
        bookContent.questsInAuthoring = authoring;
        return built;
    }

    ClientQuestCache.Entry entryFor(String questId) {
        return BookContent.cacheEntryFor(questId);
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
        if (bookState.frameActive && bookState.frameViewport != null) {
            return bookState.frameViewport;
        }
        BookGeometry.Rect canvas = geometry().canvas();
        return BookScreenState.VIEW.bounds(canvas.x(), canvas.y(), canvas.width(), canvas.height());
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
        return bookState.overlayView.viewport().bounds(
                overlayLeft() + BODY_INSET,
                overlayTop() + BODY_TOP,
                overlayWidth() - BODY_INSET * 2,
                overlayHeight() - BODY_TOP - BODY_BOTTOM);
    }

    /** The whole selection: the primary first, then the rest in the order they were added. */
    static List<String> selection() {
        List<String> all = new ArrayList<>();
        if (BookScreenState.selectedQuest != null) {
            all.add(BookScreenState.selectedQuest);
        }
        for (String id : BookScreenState.multiSelection) {
            if (!id.equals(BookScreenState.selectedQuest)) {
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
        return BookCanvas.editEffective(DevMode.on(), questEditor.mayEdit(), bookCanvas.previewAsPlayer);
    }

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
            BookCanvas.previewRequest = BookCanvas.previewRequest == null ? Boolean.TRUE : !BookCanvas.previewRequest;
        }
        else {
            BookCanvas.previewRequest = on;
        }
    }

    /** Takes the parked preview ask, if there is one. The second call always answers empty. */
    static java.util.Optional<Boolean> consumePreviewRequest() {
        Boolean ask = BookCanvas.previewRequest;
        BookCanvas.previewRequest = null;
        return java.util.Optional.ofNullable(ask);
    }

    /** Drops a parked preview ask. Called with the rest of the view state on disconnect. */
    static void clearPreviewRequest() {
        BookCanvas.previewRequest = null;
    }

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
                && book.bookCanvas.previewing() && book.bookCanvas.previewFromCommand;
        var chat = game.options.chatVisibility();
        if (hidden) {
            if (BookCanvas.chatHiddenFrom == null) {
                BookCanvas.chatHiddenFrom = chat.get();
            }
            if (chat.get() != net.minecraft.world.entity.player.ChatVisiblity.HIDDEN) {
                chat.set(net.minecraft.world.entity.player.ChatVisiblity.HIDDEN);
            }
        }
        else if (BookCanvas.chatHiddenFrom != null) {
            chat.set(BookCanvas.chatHiddenFrom);
            BookCanvas.chatHiddenFrom = null;
        }
    }

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
        bookState.buttons.add(button);
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
        ScrollView view = left ? bookState.partyLeftView : bookState.partyRightView;
        BookGeometry.Rect body = partyBody();
        if (body == null) {
            return view.viewport().bounds(0, 0, 0, 0);
        }
        // **One face gets the whole body.** A panel with no right face -- the notice, a phase, and the
        // docked party's stack -- is one column of content, so it takes the full width rather than the left
        // share of a split with nothing on the other side of it.
        if (bookState.partyRightFace == null) {
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
        if (bookState.partyCard == null) {
            return null;
        }
        PanelLayout.Frame frame = PanelLayout.frame(bookState.partyCard, carriesFold(PanelKind.PARTY),
                BookGeometry.MODAL_FOOTER_HEIGHT);
        if (carriesFold(PanelKind.PARTY)) {
            return frame.body();
        }
        // No header content: this panel draws no title in the column's 54px header band, so the
        // body starts below the border instead of below the band. The fold cell only exists when
        // this column carries it, which a root never does -- the Back control is built on the
        // child column -- so nothing overlaps up there.
        BookGeometry.Rect body = frame.body();
        int top = Math.min(bookState.partyCard.y() + PanelLayout.BODY_INSET, body.bottom());
        return BookGeometry.Rect.at(body.x(), top, body.width(), Math.max(0, body.bottom() - top));
    }

    /** A content-sized face's height, to hand {@code modalFramed}. */
    private int faceHeight(PartyPanelLayout.Face face, int width) {
        return face.build(width - BookGeometry.MODAL_INSET * 2, BookScreenState.TEXT_MEASURE).height();
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
        bookState.menu = List.copyOf(items);
        bookState.menuOpenedAt = net.minecraft.util.Util.getMillis();
    }

    JsonObject previewBase() {
        return bookState.themeTargetsChapter && effectiveChapter() != null
                ? questEditor.chapterThemePatchOf(effectiveChapter()).deepCopy() : null;
    }

    void previewChapterPatch(JsonObject patch) {
        String chapter = effectiveChapter();
        if (chapter == null) {
            return;
        }
        BookScreenState.pendingChapterTheme = patch;
        BookScreenState.fieldDraft.set(chapter, dev.ellipog.tenet.client.dev.FieldDraft.CHAPTER_OWNER, "themePatch",
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
        PopoverChip anchor = bookState.popoverChip;
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
        if (!bookState.colourPopover.isOpen()) {
            return;
        }
        for (net.minecraft.client.gui.components.AbstractWidget widget : bookState.colourPopover.widgets()) {
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
        for (net.minecraft.client.gui.components.AbstractWidget widget : bookState.mutedBehind) {
            widget.active = true;
        }
        bookState.mutedBehind.clear();

        java.util.List<net.minecraft.client.gui.components.AbstractWidget> mine = new ArrayList<>();
        java.util.List<BookGeometry.Rect> bands = new ArrayList<>();
        boolean everythingElse = false;
        if (bookState.colourPopover.isOpen()) {
            everythingElse = true;
            mine.addAll(bookState.colourPopover.widgets());
        }
        // The three pages, each of which takes the card's body while it is open -- and one band for all of
        // them, because that is what they take: `typePageBand` is this same rectangle.
        boolean pageOpen = typePageOpen() || (bookState.pickingItemPath != null
                && !itemPicker.pickerAsColumn()) || bookState.settingsOpen;
        if (pageOpen) {
            bands.add(overlayBodyRect());
        }
        if (typePageOpen() && bookState.typeSearch != null) {
            mine.add(bookState.typeSearch);
        }
        if (bookState.pickingItemPath != null && !itemPicker.pickerAsColumn() && bookState.itemSearch != null) {
            mine.add(bookState.itemSearch);
        }
        if (bookState.settingsOpen) {
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
            bookState.mutedBehind.add(widget);
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
        for (dev.ellipog.tenet.client.dev.QuestSettingsLayout.Row row : bookState.settingsRows) {
            net.minecraft.client.gui.components.AbstractWidget widget = bookState.settingsView.get(row.key());
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
        if (!bookState.colourPopover.isOpen()) {
            return;
        }
        bookState.colourPopover.close();
        for (net.minecraft.client.gui.components.AbstractWidget widget : bookState.colourPopover.widgets()) {
            while (children().contains(widget)) {
                removeWidget(widget);
            }
        }
        bookState.popoverOpenedAt = -1L;
        bookState.popoverBaseX.clear();
        // The controls it was covering are controls again.
        refreshOverlayMute();
    }

    /** The list layout the open tab is showing: the book's rows, or the chapter's combined list. */
    Layout activeLayout() {
        return bookState.toolsTab == ToolsLayout.Tab.CHAPTER ? bookState.chapterLayout : bookState.toolsLayout;
    }

    /**
     * The rows that layout was built from: the book tab's list, or the chapter's appearance rows.
     *
     * <p>Beside {@link #activeLayout} because the two are one answer -- a layout is built from a list and
     * a control is found by walking the list it came from ({@link ToolsLayout#controlSlot}), so anywhere
     * that has one needs the other.
     */
    List<ToolsLayout.Action> activeRows() {
        if (bookState.toolsTab != ToolsLayout.Tab.CHAPTER) {
            return bookState.toolsRows;
        }
        // **Both halves of the chapter tab**, not just the appearance rows: the chapter's own rows are
        // drawn by the same dispatch now, so the tooltip lookup, the chooser's slot lookup and the chips'
        // walk all read one list -- and the layout they are asking is `chapterLayout`, which holds exactly
        // this pair in this order.
        if (bookState.chapterRows.isEmpty()) {
            return bookState.chapterAppearanceRows;
        }
        List<ToolsLayout.Action> both = new ArrayList<>(bookState.chapterRows.size()
                + bookState.chapterAppearanceRows.size());
        both.addAll(bookState.chapterRows);
        both.addAll(bookState.chapterAppearanceRows);
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
        return bookState.toolsTab == ToolsLayout.Tab.BOOK || (mayEditNow() && effectiveChapter() != null);
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
        return BookScreenState.fieldDraft.overlaid(chapter, target, ClientChapterReplica.quest(chapter, target));
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
        return BookScreenState.fieldDraft.strings(entry.chapterId(), entry.id(), "dependsOn", entry.dependencies());
    }

    /** A quest's title, with a committed rename winning over the tree the sidebar draws. */
    static String titleOf(ClientQuestCache.Entry entry) {
        return BookScreenState.fieldDraft.text(entry.chapterId(), entry.id(), "title", entry.titleText());
    }

    /**
     * The quest the editor's commits are about: the modal's quest while its editor is open, else the
     * dock's selection.
     *
     * <p>One method, so the dock panel's handlers and the modal's cannot disagree about who a field
     * edit is for -- the two surfaces share every commit path below.
     */
    String editTarget() {
        return bookState.overlay == PanelKind.QUEST && bookState.overlayQuest != null ? bookState.overlayQuest
                : BookScreenState.selectedQuest;
    }

    static boolean flagOn(JsonObject quest, String path) {
        JsonElement current = quest == null ? null : QuestPanelLayout.get(quest, path);
        return current != null && current.isJsonPrimitive()
                && current.getAsJsonPrimitive().isBoolean() && current.getAsBoolean();
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
        bookState.pendingPick = new dev.ellipog.tenet.client.dev.DependencyPick(quest, effectiveChapter(),
                QuestPanelLayout.strings(replicaQuest(), "dependsOn"));
        bookState.pickingDependency = true;
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

    /** The canvas background in force for the panel's target. */
    CanvasBackground editedBackground() {
        return bookState.themeTargetsChapter ? questEditor.editedTheme().background()
                : ClientAppearance.LOOK.background();
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
            bookState.tableAddress = dev.ellipog.tenet.editor.TableAddress.of(requestedTable);
            bookState.tableStack.clear();
            bookState.tableApplied = 0;
            bookState.tableUndone = 0;
            bookState.tableFolded.clear();
            bookState.tableShowRoll = false;
            bookState.tableImportOpen = false;
            bookState.tablePreview = dev.ellipog.tenet.quest.reward.TableReward.Mode.RANDOM;
            applyColumns(PanelStack.asRoot(columns(), PanelKind.TABLE_EDITOR));
        }
        // Which dock tab the author was reading, from the file that remembers it. Seeded here rather than
        // as a field initialiser because the file is read at client start, and a screen built before that
        // read would otherwise decide the tab from a default it then keeps for the session.
        bookState.toolsTab = dev.ellipog.tenet.client.ClientWorking.CHAPTER_TAB.equals(
                dev.ellipog.tenet.client.ClientWorking.drawerTab())
                ? ToolsLayout.Tab.CHAPTER : ToolsLayout.Tab.BOOK;
        // And what the player asked the second column to do, from the file that remembers it — the same
        // reason and the same place: it is a preference about this client, read before anything can fold.
        bookState.foldChoice = DevMode.panelFold();
        // First, because everything below is built from the caches it names -- and the frame that
        // must notice them moving is the one after this returns. See `watchSources`.
        watchSources();
        clearWidgets();
        bookState.buttons.clear();
        bookState.panelOverlays.clear();
        // And the slide's filing: the widgets are gone with the clear above, so their bases go too —
        // a rebuilt rail re-files as it builds, and a base left behind would slide a control that no
        // longer belongs to the kind it names.
        bookState.slideWidgets.clear();
        bookState.slideBaseX.clear();
        bookState.sidebarTravelBase.clear();
        // And the departed rows — unless a collapse is riding out, in which case the rebuild about
        // to happen builds the sliver behind them and the orphans keep riding: dropping them here
        // would vanish the list mid-slide, which is the pop they exist to prevent. Every arrival
        // (expand, peek, float, dock) clears `sidebarDeparting` first, so keeping them here only
        // ever preserves a departure still in flight.
        if (!bookState.sidebarDeparting) {
            bookState.sidebarDeparted.clear();
            bookState.sidebarDepartAnchor = null;
        }
        // Nulled with the list it came from. `init` rebuilds Close only on the branch that builds the
        // book, so leaving this set would have `render` draw a control the screen no longer owns — see
        // the guard at the drawing site for what that looks like.
        bookState.closeButton = null;
        bookState.partyButton = null;
        bookState.rewardsButton = null;
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
            bookState.partyLeftLines = new ArrayList<>();
            bookState.partyRightLines = new ArrayList<>();
            bookState.partyLeftFace = null;
            bookState.partyRightFace = null;
            bookState.partyCard = null;
            bookState.partyLeftLayout = null;
            bookState.partyRightLayout = null;
        }
        // And the scroll views' widgets, for the reason the three above are cleared: they belong to
        // the panel being replaced. `buildPartyWidgets` clears them too, but only on the branch that
        // builds a panel -- a rebuild for a quest would otherwise leave the views holding controls
        // that are no longer on the screen.
        bookState.partyLeftView.clear();
        bookState.partyRightView.clear();
        // And the armed Disband, because a rebuild is a new panel: the row a player armed may not be
        // there any more, and a control that says "Confirm" for a press it no longer remembers is
        // worse than one that forgot.
        bookState.disbandButton = null;
        bookState.disbandPress.disarm();
        // And the armed canvas copy, for the same reason -- with one exception: the arming press's own
        // rebuild sets `canvasCopyArming`, because a rebuild is what puts the armed label on the row.
        if (!bookState.canvasCopyArming) {
            bookState.canvasCopyArmed = false;
        }
        bookState.canvasCopyButton = null;
        bookState.partyNameField = null;
        bookState.partyCreateField = null;
        bookState.partySearchField = null;
        bookState.partyControls.clear();
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
            ClientQuestCache.Entry entry = BookContent.cacheEntryFor(questId);
            if (entry != null) {
                bookSidebar.selectChapter(entry.chapterId());
                BookScreenState.selectedQuest = questId;
                BookScreenState.centred = true;
                // A node click, wherever it came from: the quest takes the first column and the second is
                // emptied, because a child belonged to the quest that is no longer showing.
                applyColumns(PanelStack.afterNodeClick(columns()));
                bookState.overlayQuest = questId;
                // A card opened from outside the book is a fresh session, not a continuation of one:
                // there is no "where I came from" for the arrow to return to.
                bookState.overlayHistory.clear();
            }
        });

        // A `/tenet preview` ask parked by the payload handler: the command runs on the server and
        // the latch lives here, so the handler parks and this consumes. Taken before the widgets build,
        // because the geometry, the band and the editing gate all read the latch. A toggle parked while
        // the book was closed was already resolved to "on" by `requestPreview`, so this sets absolutely.
        // A non-operator keeps the editor's answer: the latch is only meaningful with the permission
        // that `previewing()` already guards, and `refreshEditingView` clears it on de-op.
        consumePreviewRequest().ifPresent(ask -> {
            bookCanvas.previewAsPlayer = ask;
            // A command-entered preview shows no exit pill (see the field): the ask carries
            // the way in, so the pill would be operator chrome over a player's view.
            bookCanvas.previewFromCommand = ask;
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
        if (bookState.overlay == PanelKind.CHOICE) {
            PanelKind outer = bookState.slideBucket;
            bookState.slideBucket = PanelKind.CHOICE;
            try {
                bookOverlay.buildChoiceWidgets();
            }
            finally {
                bookState.slideBucket = outer;
            }
        }
        return;
    }

    /**
     * Where the open panel's footer controls go.
     *
     * <p>One wrapper rather than a {@link #surfaceCard} call at each of the seven sites that ask, because
     * they all ask the same question — "where do this panel's buttons go" — and the answer has to be the
     * same for every one of them or a panel's footer would be placed from two different rectangles.
     */
    Map<String, BookGeometry.Rect> overlayControls(boolean hasSubmit) {
        return geometry().questFooter(surfaceCard(bookState.surfaceKind), hasSubmit);
    }

    /** The card's body as a rectangle, for the layouts that take one. */
    BookGeometry.Rect overlayBodyRect() {
        return BookGeometry.Rect.at(overlayBody().originX(), overlayBody().originY(),
                overlayBody().viewWidth(), overlayBody().viewHeight());
    }

    /**
     * A hit rectangle moved with its rail's slide, for this frame.
     *
     * <p>Every press target is registered afresh each frame (the lists are rebuilt by the drawing), so
     * the frame's own offset is the right one: a press between frames meets positions at most a frame
     * stale, which is the same staleness every control in the game already has. Zero unless the rail
     * this draw belongs to is travelling — read off {@link BookScreenState#surfaceKind}, which the surface sets before
     * anything inside it registers, so a caller outside a surface can never borrow the wrong rail's
     * motion. Hover washes and scrollbar thumbs are deliberately not shifted: a highlight may lag the
     * slide by a frame, but a press must not land anywhere but on what is drawn.
     */
    BookGeometry.Rect shiftHit(BookGeometry.Rect box) {
        if (box == null || bookState.surfaceKind == null || bookState.surfaceKind == PanelKind.NONE) {
            return box;
        }
        int offset = PanelLayout.slideOffset(panelRail(bookState.surfaceKind),
                panelRevealElapsed(bookState.surfaceKind, net.minecraft.util.Util.getMillis()), panelRevealMillis());
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

    /** A field's value as stored, for a form control: empty when it is absent or not a primitive. */
    static String rawValue(JsonObject entry, String path) {
        JsonElement found = QuestPanelLayout.get(entry, path);
        return found == null || !found.isJsonPrimitive() ? "" : found.getAsString();
    }

    // ------------------------------------------------------------------
    // What the form's controls do
    // ------------------------------------------------------------------

    /** Whether a type page -- a list of tasks, rewards or conditions -- is over the card. */
    boolean typePageOpen() {
        return bookState.pickingEntryType != null || bookState.pickingConditionFor != null;
    }

    static ItemStack itemStack(String id) {
        net.minecraft.resources.Identifier location =
                net.minecraft.resources.Identifier.tryParse(id);
        return location == null ? ItemStack.EMPTY : new dev.ellipog.tenet.quest.ItemRef(location, 1).toStack();
    }

    // ------------------------------------------------------------------
    // The item picker
    // ------------------------------------------------------------------

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
        if (!bookState.menu.isEmpty()) {
            return bookMenu.pressMenu(mouseX, mouseY);
        }
        // The table editor's Import popover, on the menu's terms: two drawn rows in a box, and a press
        // anywhere inside that box that is not on one of them dismisses it rather than reaching the entry
        // row, the field or the canvas under it.
        if (bookState.tableImportOpen && bookState.overlay == PanelKind.TABLE_EDITOR) {
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
        return !bookState.menu.isEmpty()
                || (bookState.tableImportOpen && bookState.overlay == PanelKind.TABLE_EDITOR)
                || bookState.colourPopover.isOpen();
    }

    // ------------------------------------------------------------------
    // Pinning to the HUD
    // ------------------------------------------------------------------

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
        for (CanvasElement element : bookPins.elementsNow()) {
            if (element.id().equals(id)) {
                return element;
            }
        }
        return null;
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
        if (bookState.overlay != PanelKind.TABLE_EDITOR || bookState.tableAddress == null
                || bookState.tableEditorFrame == null
                || bookState.tableShowRoll || bookState.pickingEntryType != null) {
            return null;
        }
        BookGeometry.Rect list = bookState.tableEditorFrame.list();
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
                : dev.ellipog.tenet.client.dev.TableEditorLayout.dropIndexAt(entries, bookState.tableEditorFrame,
                        bookState.tableEditorBody.scrollY(), mouseX, mouseY, bookAssets.tableFolds());
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
        bookRewards.sendTableOp(new dev.ellipog.tenet.editor.TableOp.Insert(bookState.tableAddress, at, entry));
        status("Added " + (stack.getCount() > 1 ? stack.getCount() + "x " : "") + id, false);
        return true;
    }

    void closeOverlay() {
        // Both columns, through the rules: a second column is always something the first one opened, so
        // there is no arrangement in which it outlives it. See `PanelStack.afterClose(now, false)`.
        applyColumns(PanelStack.afterClose(columns(), false));
        // No reveal to cancel and no width to drop for the dock's sake: `applyColumns` has just forgotten the
        // arrival of anything that left the arrangement, and the dock is not one of the things this closes.
        bookState.panelDragRail = null;
        bookState.panelDragKind = PanelKind.NONE;
        bookState.panelLiveWidth = 0;
        bookState.rowHover.clear();
        // The content nullings are deferred, not dropped: the exiting rails draw from this state for the
        // length of their slide. They run when the exits sink, or early if one of these rails reopens
        // first — teardown before setup, as ever. The gestures and the commits below stay immediate: a
        // drag has no surface to follow, and a picker's commit must not wait out an animation.
        deferForLastDeparted(() -> {
            bookState.overlayQuest = null;
            bookState.overlayView.scrollTo(0);
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
            bookState.overlayHistory.clear();
            // The folds are the card's too: the next card has its own list, and an index folded here names
            // nothing there.
            bookState.entryFolded.clear();
            // The Assets panel's pressable list, which belongs to the frame that drew it: a list left behind
            // would answer a press on a row that is no longer on screen.
            bookAssets.assetsTargets.clear();
            bookAssets.assetsBody.setScrollY(0);
            // An arm made in the Assets panel is that panel's, so it goes when the panel does: left behind, the
            // next table of the same name would be removed on one press by a chip nobody had asked about.
            bookState.assetsConfirmRemove = null;
            bookState.entryConfirmRemove = null;
            bookState.confirmingDelete = false;
            bookState.settingsOpen = false;
            bookState.draggingSlider = null;
            bookState.settingsDraft.clear();
            // The widgets go with the clear; the references and the path must not outlive them.
            bookState.inlineField = null;
            bookState.inlineArea = null;
            bookState.editingPath = null;
        });
        // And the colour picker when it belongs to this panel: its anchor rows are gone with it, and a
        // picker left standing would commit to an element nobody is looking at. A dock-anchored picker
        // stays -- the dock is not what closed.
        if (bookState.popoverChip != null && bookState.popoverChip.view() == bookCanvas.elementView) {
            closeColourPopover();
        }
        // The editor's transient state goes with the card: a picker left armed would greet the next
        // quest with a list of types, and a Delete left confirmed would delete on one press. One call,
        // so every picker kind is forgotten by every exit rather than by whichever ones were remembered.
        bookAssets.clearPickers();
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
        if (target == null || questId.equals(bookState.overlayQuest)) {
            // An id the tree does not know cannot be opened, and a jump to the card already showing is
            // not a jump. Both are silent: there is nothing here for a player to fix.
            return;
        }
        if (bookState.overlay == PanelKind.QUEST && bookState.overlayQuest != null) {
            bookState.overlayHistory.add(new CardVisit(bookState.overlayQuest,
                    bookState.overlayView.viewport().scrollY(),
                    Set.copyOf(bookState.entryFolded)));
        }
        if (!target.chapterId().equals(effectiveChapter())) {
            bookSidebar.selectChapter(target.chapterId());
        }
        BookScreenState.selectedQuest = questId;
        BookScreenState.multiSelection.clear();
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
        bookState.renderMouseX = mouseX;
        bookState.renderMouseY = mouseY;
        // The intro's anchors are re-placed while drawing, so a closed panel cannot leave a stale
        // one: each frame starts with none, and the quest card's add rows file theirs as they draw.
        bookCanvas.introTaskBox = null;
        bookCanvas.introRewardBox = null;
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
                renderer.batched(() -> bookFrame.renderWith(renderer, hoverX, hoverY, partialTick));


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
                if (bookState.sidebarSlideStart >= 0 && sidebarSliding(clusterNow)
                        && (geometry().sidebarState().expanded() || bookState.sidebarDeparting)) {
                    BookGeometry.Rect wipe = sidebarWipeRect(clusterNow);
                    BookGeometry.Rect target = geometry().sidebar();
                    BookGeometry.Rect anchor = PanelLayout.sidebarTravelAnchor(bookState.sidebarDeparting,
                            bookState.sidebarSlideFromRect, target, bookState.sidebarDepartAnchor);
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
            BookGeometry.Rect book = bookFrame.panelRect();
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
                boolean sidebarWipeTravelling = bookState.sidebarSlideStart >= 0
                        && sidebarSliding(Util.getMillis())
                        && (geometry().sidebarState().expanded() || bookState.sidebarDeparting);
                if (sidebarWipeTravelling) {
                    BookGeometry.Rect wipe = sidebarWipeRect(Util.getMillis());
                    BookGeometry.Rect target = geometry().sidebar();
                    // The anchor is where the widgets were built: the target for an arrival, the
                    // filed departure box for a collapse. Either way the offset starts at nothing
                    // and lands whole, so live widgets are glued to the surface at both ends —
                    // and a collapse that reverses an expand mid-wipe continues from mid-wipe
                    // instead of snapping the rows back to full for a frame.
                    BookGeometry.Rect anchor = PanelLayout.sidebarTravelAnchor(bookState.sidebarDeparting,
                            bookState.sidebarSlideFromRect, target, bookState.sidebarDepartAnchor);
                    if (!bookState.sidebarDeparting) {
                        // The position's travel plus the width's shortfall: a wipe that only
                        // narrows keeps the rows' right edge on the moving edge, and one that
                        // moves the card carries the rows the same pixels. Either is zero when
                        // it does not apply. Departing rows need none of this: they left the
                        // screen when the collapse started and are drawn by hand below.
                        int rowTravelX = wipe.x() - anchor.x() + wipe.width() - anchor.width();
                        int rowTravelY = wipe.y() - anchor.y();
                        for (AbstractWidget widget : bookState.sidebarRowWidgets) {
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
                    for (AbstractWidget widget : bookState.viewClusterWidgets) {
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
                    if (bookState.sidebarDeparting && !bookState.sidebarDeparted.isEmpty()) {
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
                            && (geometry().sidebarState().expanded() || bookState.sidebarDragLive)) {
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
                    bookMenu.drawMenu(renderer, bookState.renderMouseX, bookState.renderMouseY);
                    return null;
                });
                // The rows' travel is undone; the cluster's seats are always valid — freshly built
                // or wipe-derived — so there is nothing to put back.
                if (sidebarWipeTravelling && !bookState.sidebarDeparting) {
                    for (AbstractWidget widget : bookState.sidebarRowWidgets) {
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
            if (bookState.colourPopover.isOpen()) {
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
                    bookState.colourPopover.render(renderer, (int) pickerPointerX(bookState.renderMouseX),
                            bookState.renderMouseY);
                }
                finally {
                    pose.popMatrix();
                }
                for (net.minecraft.client.gui.components.AbstractWidget widget
                        : bookState.colourPopover.widgets()) {
                    if (widget instanceof dev.ellipog.tenet.client.dev.ScrubField field) {
                        field.render(renderer, bookState.renderMouseX, bookState.renderMouseY);
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
                    bookFrame.drawTooltip(renderer, List.of(captioned.line()), hoverX, hoverY);
                }
            }
            // The notices first, so a tooltip -- which is what the pointer is asking for -- stays on top.
            // They keep the main theme: a toast is the book talking, not the chapter.
            bookInput.drawToasts(renderer, Util.getMillis());
            // The tooltips describe what is under the pointer, so they wear that surface's palette: a
            // caption over a chapter's canvas is that chapter's, and one over the sidebar is the book's
            // own. The toasts above are outside this scope on purpose.
            try (ArmatureTheme.Scope theme = ArmatureTheme.scope(tooltipTheme(hoverX, hoverY))) {
                bookFrame.drawPendingLabels(renderer);
                bookFrame.drawRowTooltips(renderer, hoverX, hoverY);
                bookFrame.drawLinkTarget(renderer, hoverX, hoverY);
                bookFrame.drawTooltips(renderer, hoverX, hoverY);
                // The intro over the tooltips: its spotlight dims the book and its tooltip must
                // beat theirs, while owning no press but its own three buttons.
                bookIntro.drawIntroGuide(renderer, hoverX, hoverY);
            }

            // Consumed, because they are drawn here rather than where they are collected: the card fills
            // the list while it draws, and a frame that draws no card (the overlay closed, a chapter
            // switch) must not leave last frame's label standing over the canvas.
            bookState.pendingLabels.clear();
            bookState.rowTooltips.clear();
        }
        // The frame is over: the counting renderer (dev mode only) logs its totals at most once a second.
        // Here rather than anywhere earlier because the tooltips above are the last thing a frame draws.
        dev.ellipog.tenet.client.dev.CountingRenderer.endFrame(renderer);

        // And the overlay, after that call and through the plain renderer: after, so this frame's
        // numbers are the frame's own; through the plain one, so the overlay is not counted by the
        // instrument it exists to display. Last of all, so nothing covers it.
        bookFrame.drawDevOverlay(plain);
        }
        finally {
            clearFrameSnapshot();
        }
    }

    // ------------------------------------------------------------------
    // The settings card
    // ------------------------------------------------------------------

    /** Whether the picker is choosing a picture: the tabs greet icon picks and no other pick. */
    boolean pickerTabsVisible() {
        return isIconPick() && !typePageOpen() && bookState.pickingItemPath != null;
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

    /** Asks the server for one edit. The client writes nothing: see {@code TenetNetworking.sendEditorOp}. */
    void send(EditorOp op) {
        bookInput.send(op, effectiveChapter());
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
        BookScreenState.fieldDraft.set(effectiveChapter(), dev.ellipog.tenet.client.dev.FieldDraft.CHAPTER_OWNER,
                path, value, ClientQuestCache.treeRevision(), Util.getMillis());
        send(new EditorOp.SetChapter(path, value));
    }

    // ------------------------------------------------------------------
    // The multi-selection's whole-hand gestures
    // ------------------------------------------------------------------

    /**
     * A sentence for the reader of this book, said over the book.
     *
     * <p>Alongside `say`, not instead of it: chat keeps the history for a player who is not looking at the
     * book, and the toast is the same sentence shown where the book is -- which is the one place chat
     * cannot be read, because the HUD is not drawn behind a screen. See {@link ToastStack}.
     */
    void toast(String message, boolean error) {
        bookState.toasts.add(message, error, Util.getMillis());
    }

    /** Minecraft's call; the book's answer is {@link BookFrame#tick}. */
    @Override
    public void tick() {
        bookFrame.tick();
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
        if (questId == null || questId.isEmpty() || BookContent.cacheEntryFor(questId) == null) {
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
        BookScreenState.selectedChapter = null;
        BookScreenState.frameChapterActive = false;
        BookScreenState.selectedQuest = null;
        BookScreenState.multiSelection.clear();
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
        BookScreenState.VIEW.setScale(1.0F);
        BookScreenState.VIEW.setOffset(0, 0);
        BookScreenState.pannedChapter = null;
        BookScreenState.centred = false;
        // The warning memory goes with them, for the same reason as everything else here: it describes
        // a chapter of a server this client is no longer connected to. Without this, rejoining the same
        // server would stay quiet about a chapter theme that is still misspelled.
        //
        // There is no theme to release any more, and there were two calls here that did it. A chapter's
        // palette lives inside one frame now, so there is nothing that could outlive a disconnect.
        BookScreenState.warnedThemeFor = null;

        // The sidebar's outline goes too, and the revision with it. The outline holds which groups the
        // player had collapsed, which is a fact about a questline on a server this client is no longer
        // connected to -- so keeping it would open the next server's book with the last one's collapses,
        // and `-1` means the next tree definitely re-seeds rather than possibly matching a revision it
        // happens to share.
        BookScreenState.sidebar = null;
        BookScreenState.sidebarChapters = List.of();
        BookScreenState.sidebarRevision = -1;
        BookScreenState.sidebarProgress = -1;
        BookScreenState.sidebarText = -1;
        // And the player's own expansions, for the reason the outline goes: they belong to a questline
        // on a server this client has left.
        BookScreenState.sidebarExpansion.clear();
    }

    @Override
    public void removed() {
        super.removed();
        // Exits end with their world: any cleanup a close deferred runs now rather than never — several
        // free drafts and close commits, and there is no next settle coming.
        runAllTeardowns();
        bookState.panelDepartStarts.clear();
        bookState.departRails.clear();
        // And the departed rows, if the book closes mid-collapse: unregistered orphans nobody
        // draws after this, dropped with the screen rather than left for a settle that never comes.
        bookState.sidebarDeparted.clear();
        bookState.sidebarDepartAnchor = null;
        // The pending values die with the screen: reopening reads the server's copy, and a draft from
        // a session that is over must not answer for it.
        BookScreenState.fieldDraft.clear();
        BookScreenState.elementDraft.clear();
        BookScreenState.linkDraft.clear();
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
        BookScreenState.warnedThemeFor = null;
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
        JsonObject pending = BookScreenState.pendingChapterTheme;
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
        return bookState.overlay == PanelKind.QUEST || bookState.overlay == PanelKind.PICKER
                || bookState.overlay == PanelKind.NAMING
                || bookState.overlay == PanelKind.ELEMENT;
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
        if (chapterBoundOverlay() || (bookState.overlay == PanelKind.NONE && inCanvas(mouseX, mouseY))) {
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
        if (chapter.equals(BookScreenState.warnedThemeFor)) {
            return;
        }
        BookScreenState.warnedThemeFor = chapter;
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

    /** {@code super.tick}, for the slice that answers it. */
    void superTick() {
        super.tick();
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
