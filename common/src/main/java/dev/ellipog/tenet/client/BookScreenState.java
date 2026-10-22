package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ArmatureButton;
import dev.ellipog.armature.client.ArmatureTextArea;
import dev.ellipog.armature.client.ArmatureTextField;
import dev.ellipog.armature.client.Tooltips;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.inspect.InspectRow;
import dev.ellipog.armature.client.ui.kit.Hover;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.ScrollBar;
import dev.ellipog.armature.client.ui.kit.ScrollView;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tenet.editor.EditorOp;
import dev.ellipog.tenet.client.dev.ChapterPanelLayout;
import dev.ellipog.tenet.quest.EditorField;
import dev.ellipog.tenet.quest.EditorSources;
import dev.ellipog.tenet.client.dev.ItemPicker;
import dev.ellipog.tenet.client.dev.ItemPickerLayout;
import dev.ellipog.tenet.client.dev.Labels;
import dev.ellipog.tenet.client.dev.QuestSettingsLayout;
import dev.ellipog.tenet.client.dev.SettingsDraft;
import dev.ellipog.tenet.client.dev.SidebarDrag;
import dev.ellipog.tenet.client.dev.ToolsLayout;
import dev.ellipog.tenet.client.dev.ToolsPanel;
import dev.ellipog.tenet.editor.EditorSession;
import dev.ellipog.tenet.client.ClientPartyCache;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The quest book's shared state: what more than one of the book's slices reads, or what the screen
 * itself keeps between frames, held in one place with one owner.
 *
 * <p>Moved out of {@link QuestBookScreen} as it stood. A field only one slice reads lives in that
 * slice; what is here is what is shared, and the static fields are the view state that outlives one
 * open of the book (the selected chapter and quest, the canvas's view). The screen reaches this
 * through its {@code bookState} field and every slice through {@code book.bookState}.
 */
final class BookScreenState {

    private final QuestBookScreen book;

    BookScreenState(QuestBookScreen book) {
        this.book = book;
    }

    // ------------------------------------------------------------------
    // Layout constants. See the class comment for why these are shared.
    // ------------------------------------------------------------------

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
    BookGeometry geometry;
    int geometryWidth = -1;
    int geometryHeight = -1;
    /** Whether the cached geometry was built for an author. See {@link #geometry}. */
    boolean geometryAuthor;
    /**
     * The sidebar state the cached geometry was built from. See {@link QuestBookScreen#sidebarEffective}: a
     * toggle that left the geometry cached would draw yesterday's rectangles under today's
     * widgets.
     */
    SidebarState geometrySidebar = SidebarState.DEFAULT;

    /**
     * Per-frame snapshot of the geometry, canvas viewport, permission check, and active chapter.
     *
     * <p>Captured once at the top of {@link QuestBookScreen#extractRenderState} (and {@link BookFrame#renderWith} when driven
     * directly by a test), refreshed if the frame triggers a widget rebuild, and cleared when the frame
     * finishes or {@link QuestBookScreen#init} runs. Without this, {@link QuestBookScreen#viewport} and {@link #geometry} re-ran
     * permission checks and allocated {@link SidebarState} records hundreds of times per frame.
     */
    boolean frameActive;
    BookGeometry frameGeometry;
    Viewport frameViewport;
    boolean frameMayEdit;
    static boolean frameChapterActive;
    static String frameChapter;
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
     * The reader's description, parsed and wrapped for this frame.
     *
     * <p>One parse serves three things -- the layout takes the lines and widths, the drawing takes the runs,
     * and the link rectangles are cut from what the drawing did -- so they cannot disagree about what the
     * prose says or where it is. Rebuilt where the reader's card is drawn, which is once a frame and the
     * same cost class as the wrap it replaces.
     */
    final List<QuestBookScreen.ProseBlock> readerProse = new ArrayList<>();

    /** What the reader's card drew this frame: the targets a hover and a click are matched against. */
    final List<QuestBookScreen.LinkRect> linkRects = new ArrayList<>();

    /**
     * The chapter whose missing theme was last reported, so the warning is not repeated every frame.
     *
     * <h2>What this guards, and what it used to</h2>
     *
     * <p>It used to guard the <i>application</i> of a chapter's theme, because applying one was a global
     * side effect that {@link QuestBookScreen#init} would otherwise repeat on every resize and every widget rebuild.
     * A chapter's theme is now applied by opening a scope around the canvas and the overlay, which costs
     * nothing to re-open and resets no tween — so there is nothing left to guard except the message.
     *
     * <p>The message does still need guarding: it is emitted from inside a per-frame drawing path, and a
     * chapter with a misspelled theme name would otherwise write a line of log per frame, which buries
     * everything else in it. Once per chapter, cleared on disconnect, is the useful amount.
     */
    static String warnedThemeFor;

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
    static final Viewport VIEW = Viewport.of(QuestBookScreen.MIN_ZOOM, QuestBookScreen.MAX_ZOOM);

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
     * {@link QuestBookScreen#columns()} and {@link QuestBookScreen#applyColumns} are the whole of the bridge.
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
     * <p>It is the screen's mirror of {@link PanelStack.Columns#dock()}, written by {@link QuestBookScreen#applyColumns}
     * and by nothing else — {@link QuestBookScreen#pressAuthorPill} goes through the rules like every other opening, so
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
     * <p>{@link QuestBookScreen#applyColumns} drops the entries of anything that left the arrangement, so a kind closed
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
     * read on the first slide frame rather than at filing (see {@link QuestBookScreen#slidePanelWidgets}). The slide
     * never writes a placed position — it writes {@code base + offset} every frame — so a rebuild for
     * any other reason (a replica arriving mid-slide, a press) cannot strand a widget off its rail: the
     * next frame re-derives the same answer from the same base.
     */
    final java.util.Map<PanelKind, java.util.List<AbstractWidget>> slideWidgets =
            new java.util.EnumMap<>(PanelKind.class);
    final java.util.Map<AbstractWidget, Integer> slideBaseX =
            new java.util.IdentityHashMap<>();

    /**
     * When each departed rail started leaving, in {@code Util.getMillis()} terms.
     *
     * <p>The exit's answer to {@link #panelRevealStarts}: a rail that left the arrangement keeps
     * drawing — sinking into the edge it arrived from — until its duration is spent. Recorded in
     * {@link QuestBookScreen#applyColumns}, the one writer of the arrangement, beside the arrival stamps; forgotten
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
     * in {@link QuestBookScreen#applyColumns} when the same kind reopens first (teardown before setup, the order a
     * close-then-open has always had). A settle that finds a newer arrangement leaves them parked: the
     * reopen they belong to has not happened yet, and running them under it would null state a new
     * panel just set. {@code init} runs none of them — a rebuild is not a settle — but it keeps its
     * hands off the draw content of departing rails (see the party guard there), and {@code removed}
     * runs them all, because a stored cleanup must never be dropped on the floor.
     */
    final java.util.Map<PanelKind, java.util.List<QuestBookScreen.StampedTeardown>> pendingTeardowns =
            new java.util.EnumMap<>(PanelKind.class);

    /**
     * How many arrangements this screen has written: bumped by {@link QuestBookScreen#applyColumns} on every call.
     *
     * <p>What a scheduled cleanup is stamped with, and what the tick compares before running one. Equal
     * means the close is still the latest thing that happened — nothing reopened, so nothing fresh
     * stands where the nullings land. Different means something moved since, and the cleanup waits for
     * its own kind's reopen (or the screen's end) rather than nulling under a new panel.
     */
    long arrangementEpoch;

    /**
     * The rails the latest {@link QuestBookScreen#applyColumns} retired, in presented order.
     *
     * <p>What a close schedules its deferred nullings under: the call just moved these rails out, so
     * they are the exits that still need their content. Read immediately — the next arrangement change
     * overwrites it — which is why every close below snapshots nothing and simply calls
     * {@link QuestBookScreen#deferForLastDeparted} before doing anything else that moves rails.
     */
    java.util.List<PanelKind> lastDeparted = java.util.List.of();

    /**
     * The rail a departed draw answers with, while it answers.
     *
     * <p>Set around each departed kind's {@link BookOverlay#drawSurface} and cleared after: {@link QuestBookScreen#panelRail} reads
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
     * <p>A live override rather than a write per frame: {@link QuestBookScreen#railWidth} answers with it while the hand is
     * on that kind, so the rail's chrome and its content follow the pointer without the settings file being
     * rewritten sixty times a second. It is written once, on release, and dropped in the same breath — so
     * the width on screen and the width in the file cannot disagree.
     *
     * <p><b>One field, read for one kind.</b> There used to be a second for the child column, on the
     * reasoning that column 2 owns no stored number and so its drag was forgotten; the two fields were a
     * live override <i>per column</i>, which is the shape {@link QuestBookScreen#railWidth} exists to replace. A width is
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
    QuestBookScreen.TableEdit tableEdit;

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
    final List<QuestBookScreen.CardVisit> overlayHistory = new ArrayList<>();

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

    QuestBookScreen.PartyPhase partyPhase = QuestBookScreen.PartyPhase.NONE;

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
    final List<QuestBookScreen.EditTarget> pinnedTargets = new ArrayList<>();

    /** The progress revision the rewards panel's rows were built at; see {@link BookFrame#tick}. */
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
     * place that knows the count — and read by {@link QuestBookScreen#sidebarViewport()}, which binds the bounds
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

    /** When the current sliver hover began, or -1 outside it. Read by the dwell above. */
    long sidebarPeekHoverStart = -1;

    /** When the current outside-the-keep-zone spell began, or -1 inside it. */
    long sidebarPeekLeaveStart = -1;

    /** The departed rows, in build order. Empty unless a collapse or unpeek wipe is travelling. */
    final List<QuestBookScreen.SidebarDeparted> sidebarDeparted = new ArrayList<>();

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
    int sidebarDepartOverhang;

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
     * A live width override while the edge is held: {@link QuestBookScreen#sidebarEffective} answers with it
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
    long popoverOpenedAt = -1L;

    /**
     * The picker's four fields as placed, by widget.
     *
     * <p>Filed on open, when {@code place} has just run: the per-frame settle writes
     * {@code base + offset} and never the built position itself, so a rebuild mid-settle — same
     * widgets, re-registered — cannot strand a field off its surface. Cleared on close with the
     * widgets it names.
     */
    final java.util.Map<AbstractWidget, Integer> popoverBaseX =
            new java.util.IdentityHashMap<>();

    /** What the open picker is anchored to, or null when none is open. */
    QuestBookScreen.PopoverChip popoverChip;

    /** The controls the open colour picker muted, so closing it wakes those and only those. */
    final List<net.minecraft.client.gui.components.AbstractWidget> mutedBehind = new ArrayList<>();

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

    /** The editor's pressable pieces, from the last frame's drawing. */
    final List<QuestBookScreen.EditTarget> editTargets = new ArrayList<>();

    /** The book's own transient messages; see {@link ToastStack} for why chat is not enough. */
    final ToastStack toasts = new ToastStack();

    QuestBookScreen.RewardCounts rewardCounts;

    QuestBookScreen.ChapterCounts chapterCounts;

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
    final List<QuestBookScreen.PendingLabel> pendingLabels = new ArrayList<>();

    /**
     * The explanation of a hovered task or reward row, waiting to be drawn with the tooltips.
     *
     * <p>Collected the same way the pending labels are, and for a sharper version of the same reason:
     * the row is drawn <b>inside the card body's clip</b>, and a tooltip belongs over everything,
     * including the edge it reaches past. A box drawn at the row would be cut off at the body's edge;
     * drawn in the tooltip layer it is not. One entry at most per frame -- a pointer is a single point
     * -- but a list, so the collecting code does not have to know that.
     */
    final List<QuestBookScreen.RowTooltip> rowTooltips = new ArrayList<>();

    /**
     * A task's or reward's row that can send the player to a recipe viewer, from the last frame's
     * own drawing — the contract {@code editTargets} and the picker rows already use, so a press
     * lands on the row that was drawn and a row scrolled out of the card is not pressable.
     */
    final List<QuestBookScreen.RowItem> rowItems = new ArrayList<>();

    /**
     * Filter rows that open the accepted-items list. The whole row, icon to text end: a filter
     * row carries no recipe target ({@code BookRowTargets} answers null for it, since only
     * {@code tenet:item_tag} tasks send a tag), so nothing competes for the press and the row
     * that lights up is the row that answers.
     */
    final List<QuestBookScreen.FilterRow> filterRows = new ArrayList<>();

    /**
     * Checkmark rows that hand in on press. The whole row, like a filter row's: a bare checkmark
     * carries no recipe target, so nothing competes for the press — and a titled one answers here
     * only when it has no target either, because a press that could open recipes must keep doing
     * that. Registered while drawing, read by the click, cleared with the other row lists.
     */
    final List<QuestBookScreen.CheckmarkRow> checkmarkRows = new ArrayList<>();

    /**
     * Reward rows that collect on press. Only claimable rows are registered — a finished quest's
     * outstanding rewards — so a press that could open recipes keeps doing that everywhere else.
     * Registered while drawing, read by the click, cleared with the other row lists.
     */
    final List<QuestBookScreen.RewardClaimRow> rewardClaimRows = new ArrayList<>();

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
     * the two are read together in {@link BookPins#elementsNow}. See {@code ElementDraft} for why one draft could not
     * do both.
     */
    static final dev.ellipog.tenet.client.dev.ElementDraft elementDraft =
            new dev.ellipog.tenet.client.dev.ElementDraft();

    /**
     * The quest links this client has asked for and the tree has not confirmed: the array's shape.
     *
     * <p>{@code fieldDraft} beside it holds the <i>values</i> inside that shape, under one owner per link;
     * the two are read together in {@link BookPins#linksNow}. See {@code LinkDraft} for why one draft could not
     * do both — and why this is not the element draft with a second list in it.
     */
    static final dev.ellipog.tenet.client.dev.LinkDraft linkDraft =
            new dev.ellipog.tenet.client.dev.LinkDraft();

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

    QuestBookScreen.PickerKind pickerKind = QuestBookScreen.PickerKind.ITEM;
    String pickerFileQuery = "";

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
     * What the open picker card says it is for, in the caller's own words.
     *
     * <p>Empty means "ask {@link PickTarget}" — which is right for the three icon picks, whose sentences
     * name the subject they already carry in {@code pickName}. A pick that is not about a chapter, a
     * group or the book has no sentence there, and used to get the chapter's.
     */
    String pickTitle = "";

    /** The open picker's target, or null when it is closed. */
    QuestBookScreen.PickTarget pickTarget;

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
    QuestBookScreen.Naming naming;

    /**
     * The sidebar's inline create or rename, when one is in flight.
     *
     * <p>The naming card used to do this from the right rail; creates and renames happen in the
     * list itself now, where the author clicked. The card keeps duplicates, which is the one mode
     * that pre-fills both fields rather than growing an id from a title.
     */
    QuestBookScreen.InlineNaming inlineEdit;

    /** The inline session's title field, and the id field while its disclosure is open. */
    ArmatureTextField inlineTitle;
    ArmatureTextField inlineId;

    /** The sidebar's right-click menu: its items and where it was opened. Empty when it is shut. */
    List<BookMenu.MenuItem> menu = List.of();
    int menuX;
    int menuY;

    /**
     * When the menu opened, in {@code Util.getMillis()} terms; -1 when it is shut.
     *
     * <p>Stamped by {@link QuestBookScreen#openMenu} — the one writer — and read by {@link BookMenu#menuRise}. Cleared with the
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
     * what {@link BookFrame#drawTooltips} does.
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
    static boolean watchingSources;

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

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    /**
     * The measured floor, remembered. Zero until it is first asked for.
     *
     * <p>A field rather than a constant because the labels are resolved from the language file: a static
     * final would measure whatever language the class happened to load under, and the first frame the floor
     * is wanted — a drag, or a panel opening — is the first moment it can honestly be measured.
     */
    int editorFloor;

    // ------------------------------------------------------------------
    // The drawer's controls
    // ------------------------------------------------------------------

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
     * <p>Kept beside the {@code FieldDraft} rather than only in it because {@link QuestBookScreen#viewportTheme} is
     * static -- the same convention {@code selectedQuest} uses for screen-level state -- and because the
     * canvas needs the whole patch, not one path's value. It is what makes the canvas follow a drag before
     * the round trip; the commit then writes the same patch, and the draft's own reconcile retires both
     * when the tree carries the value.
     */
    static JsonObject pendingChapterTheme;

    // ------------------------------------------------------------------
    // The canvas
    // ------------------------------------------------------------------

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
}
