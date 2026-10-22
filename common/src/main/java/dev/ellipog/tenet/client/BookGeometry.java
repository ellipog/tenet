package dev.ellipog.tenet.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where everything on the quest book screen goes — and nothing else.
 *
 * <h2>Why this is a separate class with no Minecraft in it</h2>
 *
 * <p>Because the bug that produced it could not be tested. Two controls were drawn on top of each
 * other in the bottom-right corner, and the cause was that "near the bottom right" had been written
 * <b>twice</b>, as two expressions that were equal only by coincidence:
 *
 * <pre>
 * Open: canvasRight() - 78,               canvasBottom() + 11
 * Done: panelLeft() + panelWidth() - 68,  panelTop() + panelHeight() - 24
 * </pre>
 *
 * <p>{@code canvasRight() == panelLeft() + panelWidth()}, so the two x values were ten pixels apart
 * and the two y values about fifteen. The fix was to compute each shared position once — but that
 * fix lived inside {@code QuestBookScreen}, which extends {@code Screen} and needs a running
 * Minecraft to instantiate. So the property "these controls do not overlap" was still not testable,
 * and a future edit to those private helpers would have re-broken it with nothing to notice.
 *
 * <p>This class holds only that arithmetic: rectangles, and integers. No {@code GuiGraphics}, no
 * {@code Minecraft}, no rendering. It loads and runs in a plain JVM, so
 * {@code BookGeometryTest} can assert the properties that matter —
 * <b>no two controls overlap, and every control is inside the surface it belongs to</b> — across a
 * sweep of window sizes. That is a test of the exact failure that was reported.
 *
 * <h2>The shape of the screen, and what changed</h2>
 *
 * <pre>
 * +--------------------------------------------------------------+
 * | Quest Book                       20 quests . 100%      [ x ] |  header, close at the right
 * +-------------+------------------------------------------------+
 * | First Steps | [+]                                            |
 * | Toolsmith   | [-]                                            |
 * | Desert Road | [o]                                            |
 * |             |                                                |
 * |             |          (the graph, the whole canvas)         |
 * +-------------+------------------------------------------------+
 * </pre>
 *
 * <p>Two things moved, and each replaced something that was spending space without earning it:
 *
 * <ul>
 *   <li><b>The sidebar footer is gone.</b> It held four controls across two rows — zoom in, zoom out,
 *       re-centre and Done — in 116 pixels of a 132-pixel column. They are map controls, so they now
 *       sit on the map, as small square icon buttons in the canvas's top-left corner. That is where a
 *       player looks for them, it costs the chapter list no room at all, and it frees both footer
 *       rows.</li>
 *   <li><b>Done is now a close button in the header</b>, top-right, beside the quest count. A modal
 *       panel is closed by the thing in its corner, and the header had a mostly empty right end.</li>
 * </ul>
 *
 * <h2>And the summary strip is gone entirely</h2>
 *
 * <p>There used to be a third thing here: a bar across the bottom describing the selected quest with
 * an <b>Open</b> button. It went through two forms — a 46-pixel band reserved below the graph, then a
 * floating bar that appeared only when something was selected — and both were waste, for a reason
 * neither form addressed.
 *
 * <p><b>Clicking a node already opens the quest.</b> That is what a graph UI does, and the code has
 * done it since the pan/select/open gesture was written: a press that does not move selects
 * <i>and</i> opens, so the strip was a second route to a place you were already standing. A summary
 * of what you are looking at is only useful if you are not looking at it, and you just clicked it.
 *
 * <p>So the canvas is the whole area below the header, and there is nothing to keep clear of. That
 * also removes the class's only reason to distinguish a <i>canvas</i> from a <i>view port</i>: content
 * is centred in the canvas, because there is nothing on top of it.
 *
 * <h2>Screens are smaller than you think</h2>
 *
 * <p>Minecraft's GUI space is the window divided by the GUI scale, and an auto scale on a small
 * window gives remarkably little of it: the screenshot this was debugged from was
 * <b>427 × 240</b> GUI pixels. At that size the panel is 387 × 200. Anything that assumes 800 × 480
 * works on the developer's monitor and breaks on a laptop.
 *
 * <p>So the panel has a minimum size that can physically hold its own chrome, and below that minimum
 * the panel is allowed to run off the edge of the screen rather than overlapping itself. Off-screen is
 * clipped and obviously wrong; overlapping controls look like a rendering fault and are not obviously
 * anything.
 */
public final class BookGeometry {

    // ------------------------------------------------------------------
    // Dimensions
    // ------------------------------------------------------------------

    /**
     * The chapter list down the left.
     *
     * <p>156 rather than the 132 it was, and the reason is the grouped sidebar rather than taste. A
     * heading's label and a chapter's are now at two different indents inside one column, so the room
     * a title has is {@code 156 - EDGE*2 - SIDEBAR_SCROLLBAR - SIDEBAR_HANDLE - SIDEBAR_INDENT} = 119
     * rather than the 116 the flat list had — and the flat list was already truncating
     * {@code "Getting Started"}.
     * Grouping the list without widening it would have made the truncation worse and blamed it on the
     * grouping.
     *
     * <p>Worth recording what the number is measured against, since a sidebar is easy to over-widen: the
     * gallery's longest chapter title is {@code "High Contrast"} and the longest heading is
     * {@code "Getting Started"}, so 119 is roughly twice what the content asks for. That margin is
     * deliberate — a pack author's titles are not this file's — and it costs the canvas 24 pixels out
     * of a 200-pixel minimum, which is why {@link #MIN_PANEL_WIDTH} still fits a screenshot-sized
     * window comfortably.
     */
    public static final int SIDEBAR_WIDTH = 156;

    /**
     * The narrowest the chapter list is dragged to, in GUI pixels.
     *
     * <p>120 rather than something smaller, and it is measured against the same titles
     * {@link #SIDEBAR_WIDTH} is: the inner room is {@code 120 - EDGE*2 - SIDEBAR_SCROLLBAR} = 96,
     * less one {@link #SIDEBAR_INDENT} for a nested chapter, so ~88 pixels of title. Below that a
     * chapter row is a scrollbar beside an ellipsis, which is a width that cannot be read rather
     * than a narrow one.
     */
    public static final int SIDEBAR_MIN_WIDTH = 120;

    /**
     * The widest the chapter list is dragged to.
     *
     * <p>260: past this the list is map rather than navigation — a chapter title longer than 228
     * inner pixels is a sentence, not a name — and every pixel past it comes off the graph the
     * panel is for. The draw-time cap in {@link #drawnSidebarWidth} still has the last word on a
     * window too small for even this, so a remembered width can never eat the canvas.
     */
    public static final int SIDEBAR_MAX_WIDTH = 260;

    /**
     * How much of the slid-away box still sticks out past the panel's left edge.
     *
     * <p>20: the box keeps its size and slides left until 20 pixels of its right edge show — its
     * grip and its edge, enough to hover and to grab the eye, not enough to read as a panel. The
     * collapsed state is one translation rather than a second layout, so there is nothing about
     * it that can disagree with the box it belongs to.
     */
    public static final int SIDEBAR_PEEK_WIDTH = 20;

    /**
     * The strip a press drags the chapter list by, on its inner (right) edge and inside it.
     *
     * <p>The same figure as {@link #PANEL_HANDLE}, and for the same reason: a band reaching past
     * the edge into the canvas would be a press that either resizes or answers the graph. The
     * relation to the body's own inset is asserted the same way — narrower than every surface's
     * inset, so there is no control under it.
     *
     * <p>Five rather than a reference to {@link #PANEL_HANDLE}, because a static initializer cannot
     * read a field declared below it — and the sameness is asserted where it matters, in the tests
     * that hold both handles inside their columns.
     */
    public static final int SIDEBAR_HANDLE = 5;

    /**
     * The floating list's own header band: room for its title and its close square.
     *
     * <p>26 rather than the rail's 45: a rail's header carries a title, a state tag and a chapter
     * line, while this one carries one word and one square. Tall enough for an 18-pixel control
     * with air either side, like every other band measured this way.
     */
    public static final int SIDEBAR_BOX_HEADER = 26;

    /** The title bar across the top of the panel. Holds the title, the count and Close. */
    public static final int HEADER_HEIGHT = 26;

    /** A control's height. One number, so a row of them lines up. */
    public static final int ROW_HEIGHT = 18;

    /** Between two stacked controls. */
    public static final int ROW_GAP = 4;

    /**
     * The Edit button's width: a pencil, a space and the word.
     *
     * <h2>Which glyphs this font has</h2>
     *
     * <p>Measured rather than assumed, by reading {@code assets/minecraft/font/include/default.json} out
     * of the 1.21.1 client jar: its three bitmap pages carry 2414 codepoints -- Latin-1 complete but for
     * one, Latin Extended, Greek and Cyrillic, and a short list of symbols. {@code include/unifont.json}
     * is <b>empty</b>, so there is no unicode fallback: a codepoint outside those pages draws as a box.
     *
     * <p><b>Present</b>: {@code + - × · • ← ↑ → ↓ ↔ ▲ ▼ ○ ● □ ‹ › « » ⌂ ✎ § ° ± ÷}. <b>Absent</b>,
     * though an earlier version of this comment claimed otherwise: {@code ⚙ ◉ ✕ ▸ ▾ ✓}, and every other
     * geometric-shape or dingbat symbol -- {@code ✕} as a close button and {@code ◉} for centre were
     * both drawing as boxes until this was measured. A control's glyph comes from the present list or it
     * does not get one, and {@code .utils/check_glyphs.py} holds the same list and fails the build on an
     * escape outside it.
     *
     * <p>The pencil is {@code \u270E}, which that list carries.
     */
    public static final int EDIT_WIDTH = 56;

    /** The Assets button's width, in the author's band. Wider than Edit's by two characters. */
    public static final int ASSETS_WIDTH = 72;

    /**
     * The Author button's width: the way into the author's dock.
     *
     * <p>The same figure as Assets', and it is a measurement rather than a reuse: "Author" and "Assets" are
     * both six characters, so a width that fits one fits the other. Kept as its own constant so that
     * changing one label cannot silently resize the other — see {@link #EDIT_WIDTH} for why a button's
     * width follows its own word.
     *
     * <p><b>This javadoc used to claim the label had changed to "Panels".</b> It has not:
     * {@code tenet.screen.author} is "Author" in the language file, which is also what
     * {@code AGENT.md} was corrected to say. The claim was a rename that was considered and did not
     * happen, left behind as a fact — the width is right either way, because the two words are the same
     * length.
     */
    public static final int AUTHOR_WIDTH = 72;

    /**
     * The Advanced button's width: the latch over the editor's depth.
     *
     * <p>Eight characters rather than six, and the same figure as the two six-character buttons because
     * {@link #EDIT_WIDTH}'s rule is a measurement rather than an arithmetic: this is the width at which
     * "Advanced" fits at the UI's font, and a button that truncated the name of the mode it toggles would
     * be a control whose label depends on the font.
     */
    public static final int ADVANCED_WIDTH = 72;

    /**
     * The Preview button's width: the latch over the player's view.
     *
     * <p>Seven characters, and the same figure as the six- and eight-character buttons because
     * {@link #EDIT_WIDTH}'s rule is a measurement rather than an arithmetic: this is the width at which
     * "Preview" fits at the UI's font, and a button that truncated the name of the mode it toggles would
     * be a control whose label depends on the font.
     */
    public static final int PREVIEW_WIDTH = 72;

    /**
     * The party button's width, in the header.
     *
     * <p>Its own constant rather than a reuse of the footer's, because the two are sized from different
     * labels that happen to be a similar length today -- and a button whose width followed a word it
     * does not contain would be a coincidence rather than a measurement.
     */
    public static final int PARTY_BUTTON_WIDTH = 52;

    /** The rewards button's width, in the header, between Close and Party. Wider than Party's: the word is. */
    public static final int REWARDS_BUTTON_WIDTH = 64;

    /**
     * The pinned quests button's width, in the header, left of Settings.
     *
     * <p>For every player, like the three beside it: pins are a player's own watchlist rather than an
     * author's. Wider than Party's: the word is.
     */
    public static final int PINNED_BUTTON_WIDTH = 56;

    /**
     * The settings button's width, in the header, between Party and the author's split control.
     *
     * <p>Drawn for every player, unlike the split control beside it: the theme, the Motion switch and
     * the corner radius are the player's own eye, and they used to be reachable only through the tools
     * panel -- which is behind the edit permission, so the player the settings are for could not reach
     * them. Wider than Party's: the word is.
     */
    public static final int SETTINGS_BUTTON_WIDTH = 56;

    /** Between the panel's edge and the controls inside it. */
    public static final int EDGE = 8;

    /**
     * The gap between two controls of the author's band.
     *
     * <p>Three pixels under the general {@link #EDGE}: at the full edge they read as five controls that
     * happen to be near each other, and they are one row — one band, drawn as a single strip
     * ({@link #authorBand}). Declared here rather than beside the button widths because a constant cannot
     * name one declared after it, which is what the first attempt at the pills did.
     *
     * <p><b>It was four, and the seam was one pixel too wide.</b> Playtest, on the pills this band
     * replaced: they read as one cluster already, and what was left was the air between them, which is the
     * only part of a seam a person can measure. The value carried over unchanged, and
     * {@link #MIN_PANEL_WIDTH} names this constant rather than repeating the number, so the minimum
     * panel's width follows it.
     */
    public static final int BAND_BUTTON_GAP = EDGE - 5;

    /** The air above and below a band button, inside the band. */
    public static final int BAND_PAD = 3;

    /**
     * The band's height: one row of controls and the air around it.
     *
     * <p>Derived rather than chosen, like every other band in this class: {@link #HEADER_HEIGHT} holds a
     * {@link #ROW_HEIGHT} control centred in it, and this holds one the same way with {@link #BAND_PAD}
     * either side. It is <b>reserved whether or not the band is drawn</b>, because it is part of
     * {@link #MIN_PANEL_HEIGHT} and a minimum that moved with the mode would be two window geometries to
     * test and one of them never seen until a permission changed.
     */
    public static final int AUTHOR_BAND_HEIGHT = ROW_HEIGHT + BAND_PAD * 2;

    /**
     * How far the header's **text** sits from the panel's edge — the title at the left, the quest count
     * at the right.
     */
    public static final int HEADER_INSET = 10;

    /**
     * How far a header **control's box** sits from the panel's edge. See {@link #HEADER_INSET}.
     *
     * <h2>Why a box is inset by less than a label</h2>
     *
     * <p>Because a label starts where it is drawn and a control's glyph sits inside its own box: put the
     * box at the label's inset and the resting gap to the X reads wider than the gap to the title. How
     * much wider is the button's own padding, and it was measured by eye across two reports rather than
     * derived — "a tiny bit too much space to the right of the x", then "move them 4 pixels to the right",
     * then two more. Six, in total, and it is subtracted once here rather than at any call site.
     */
    public static final int HEADER_CONTROL_INSET = HEADER_INSET - 6;


    // --- the sidebar's list --------------------------------------------------

    // These four replace a single CHAPTER_ROW_PITCH, and the reason is the whole of what changed here.
    // A pitch is a *geometry* decision -- "row n is at y + n * pitch" -- and it can only be made by a
    // class that knows how many rows there are and which of them are on screen. That was true while the
    // chapter list was a flat map the screen walked with an index. It is not true now: the rows come
    // from a collapsible outline, are nested, and are scrollable, so the arithmetic belongs to the
    // Stack that places them and the ScrollView that clips them.
    //
    // So what is left here is what is genuinely this class's: how tall a row is, how far apart two of
    // them sit, how far one level of nesting is indented, and how much of the column the scrollbar
    // needs. Those are metrics. The *positions* are not.

    /**
     * The height of one row in the sidebar's list, chapter or group.
     *
     * <p>Equal to {@link #ROW_HEIGHT} rather than a second 18, because a sidebar row and a control are
     * the same kind of thing drawn at the same size, and the previous arrangement had them as two
     * numbers that agreed by inspection.
     */
    public static final int SIDEBAR_ROW_HEIGHT = ROW_HEIGHT;

    /**
     * Between two rows of the sidebar's list.
     *
     * <h2>Half {@link #ROW_GAP}, and this is where the two stop agreeing</h2>
     *
     * <p>This was {@code ROW_GAP} — 4 — on the reasoning that a sidebar row and a control are the same
     * kind of thing. They are, and they are not laid out the same way. A stack of buttons is two or
     * three controls that each do something different; a sidebar is <b>nineteen rows in one list</b>,
     * and spacing between list items is not spacing between unrelated controls. Four pixels of gap
     * around eighteen-pixel rows spends a fifth of the column on nothing, and the report was exact:
     * <i>"not utilizing its space well enough"</i>.
     *
     * <p>Two, so the pitch is 20 rather than 22. On a 174-pixel sidebar that is eight full rows instead
     * of seven, and the rows still read as separate items because each has its own box.
     */
    public static final int SIDEBAR_ROW_GAP = 2;

    /**
     * How far a row is indented per level of nesting.
     *
     * <p>One level exists today — a chapter under its group — so a chapter row is indented by this and a
     * group row is not. It is a per-level number rather than a single chapter indent because the
     * outline that places these rows is depth-general: {@code Outline.depth} already answers for a tree
     * of any depth, and a second constant here would be this class disagreeing with it about a question
     * it does not need to have an opinion on.
     *
     * <p>Eight, which is a little under half a row's height. Wide enough that the indent reads as
     * nesting rather than as a random left margin, and narrow enough that the widest chapter title in
     * the shipped examples still fits — see {@link #SIDEBAR_WIDTH} for that arithmetic. It was ten,
     * and it came down by two when the column widened: the indent has to be <i>visible</i> rather than
     * large, and every pixel of it is taken off the label.
     */
    public static final int SIDEBAR_INDENT = 8;

    /**
     * The width the scroll view's bar needs, taken off the right of the list.
     *
     * <p>{@code ScrollBar} draws a three-pixel bar four pixels right of the viewport's right edge, so
     * the bar occupies {@code viewRight() + 4} to {@code viewRight() + 7}. Eight leaves that a pixel
     * clear of {@link #EDGE}, which is the whole of the arithmetic: the bar has to sit in the sidebar's
     * inner margin and not on a row.
     *
     * <p>Named here rather than in {@code ScrollView} because it is this screen's column that has to
     * reserve it. A view that draws a bar in space its caller did not leave is a view drawing over the
     * caller's content, which is why the bar's position is the kit's business and the room for it is
     * not.
     */
    public static final int SIDEBAR_SCROLLBAR = 8;

    /**
     * The gap between the sidebar's toolbar and the first row under it.
     *
     * <p>The toolbar is reserved for every player, not only authors: its height is part of the list's
     * geometry, and a layout that changed shape with a mode would be two window geometries to test and
     * one of them never seen until somebody toggled Edit.
     */
    public static final int SIDEBAR_TOOLBAR_GAP = 4;

    /** Between the header and the top of the sidebar's list. */
    public static final int CHAPTER_GAP = 6;


    /** The gap inside the full-screen overlay, between its edge and its content. */
    public static final int OVERLAY_MARGIN = 24;

    // --- the view cluster ----------------------------------------------------

    /**
     * One button of the view cluster, and so the cluster's width.
     *
     * <p>Square and small, because it is a map control: the graph is the content and these are a tool
     * for looking at it. They used to be 30 pixels wide with the word "Centre" in one of them, which
     * is a label earning its keep in a footer and not on a map.
     */
    public static final int VIEW_BUTTON = ROW_HEIGHT;

    /** Between two buttons of the cluster. Small, because they are one control group. */
    public static final int VIEW_GAP = 2;

    /** The height of the three stacked buttons. */
    public static final int VIEW_COLUMN_HEIGHT = VIEW_BUTTON * 3 + VIEW_GAP * 2;

    /**
     * How far the cluster's backing panel extends past the buttons.
     *
     * <p>The buttons have their own fills, so this is not what makes them visible — it is what makes
     * them read as <b>one</b> cluster rather than as three controls that happen to be stacked. Three
     * pixels, and the three of them sit inside a single raised panel.
     */
    public static final int VIEW_MAT = 3;

    /**
     * How far below the rows' top edge the view cluster sits, in pixels.
     *
     * <p>Three rather than level: level with the chapter bar the cluster reads as attached to the
     * list, a hair below it reads as on the map beside it. Both anchors that place the cluster —
     * the buttons in {@link #controls} and the backing in {@link #viewControlsFor} — add this,
     * so the two cannot disagree about where it lives. At the minimum panel height this spends
     * three of the canvas term's bottom margin; seven remain, and the cluster stays inside the
     * canvas, which is what the sweep holds.
     */
    public static final int VIEW_DROP = 3;

    /**
     * The smallest panel that can hold its own chrome, derived rather than written out.
     *
     * <p>Two constraints, and the taller one wins. Both are re-derived here rather than carried over,
     * because the thing the sidebar's term was measuring has changed.
     *
     * <h2>The sidebar term, re-derived</h2>
     *
     * <p>The sidebar needs the header, a gap, <b>one row</b> and the bottom edge:
     * {@code CHAPTER_GAP + SIDEBAR_ROW_HEIGHT + EDGE}. A list with no rows is a book whose only chapter
     * cannot be selected, which is a blank screen with no way forward.
     *
     * <p>What is guaranteed is now <i>weaker and more honest</i> than it was. It used to be "at least one
     * chapter fits above the bottom", measured by {@code chapterRows()} against a pitch — so the promise
     * was about the list being <b>complete</b>, and the arithmetic had to be right about how many rows
     * fit or the last one was drawn off the end. Now it is "the viewport is at least one row tall", and
     * everything past that scrolls. The consequences, both of them improvements:
     *
     * <ul>
     *   <li>There is <b>no pitch in the term</b>, and no row count. The pitch is the Stack's (see
     *       {@link #SIDEBAR_ROW_HEIGHT} for where it went) and the count is the outline's, so this
     *       constant cannot be invalidated by a change to either. That is the pressure that has gone,
     *       and it is why this is a re-derivation rather than a renumbering.</li>
     *   <li>The number is <b>unchanged</b> at {@code 6 + 18 + 8 = 32}, because the first row's position
     *       and its height are the same as they were. It is worth saying plainly that the figure not
     *       moving is not evidence that nothing changed: the sum that produces it is a different sum
     *       describing a different guarantee, which is exactly the kind of coincidence that makes an
     *       unchanged test count reassuring and meaningless.</li>
     * </ul>
     *
     * <p>A third thing went out of this term too: it used to be measured to the <i>appearance rows'</i>
     * top, because those were anchored to the panel's bottom and a row counted past them would have been
     * drawn underneath. They are gone, and the list measures to the panel's edge now — see
     * {@link #sidebarViewport()}.
     *
     * <h2>The canvas term, re-derived</h2>
     *
     * <p>{@code (EDGE - VIEW_MAT) + (VIEW_COLUMN_HEIGHT + VIEW_MAT * 2) + EDGE}. It is the view cluster,
     * which is the tallest thing that sits <i>on</i> the canvas: three square buttons, the gaps between
     * them, and the mat that makes them read as one group, all inside the canvas's own {@link #EDGE}.
     * Without it the cluster runs past the bottom of a short canvas, which is the same class of fault as
     * a row drawn underneath a footer — a control outside the surface it belongs to, and therefore
     * clickable outside it.
     *
     * <p>This term is <b>arithmetically unchanged</b>, and unlike the sidebar's it is not a coincidence:
     * nothing about scrolling touches the canvas. Writing it out again is the point of the exercise
     * rather than a formality — a "re-derive both terms" that quietly copied one of them would not be a
     * check, and the useful half of this paragraph is that the canvas's term is the one that did not
     * move, for a reason that can be stated.
     *
     * <h2>The band, and the third term this constant gained</h2>
     *
     * <p>{@link #AUTHOR_BAND_HEIGHT} sits between the header and both of the terms above, because both
     * start below it: the sidebar's list and the canvas are the panel's body, and the band is chrome over
     * the top of it. So this is {@code HEADER_HEIGHT + AUTHOR_BAND_HEIGHT + max(sidebar, canvas)}, and the
     * band is paid for by both columns rather than one.
     *
     * <p>It is the same height whether or not the band is drawn, which is deliberate: the band belongs to
     * an operator, and an author's panel that was taller than a reader's at the same window would be two
     * geometries to test with one of them never seen until somebody was de-opped.
     *
     * <p>The numbers, for whoever needs them: the sidebar needs {@code 6 + 18 + 8 = 32} and the canvas
     * {@code (8 - 3) + (58 + 6) + 8} = <b>77</b>, so the canvas still decides the body;
     * {@link #AUTHOR_BAND_HEIGHT} is {@code 18 + 3 * 2} = 24 and {@link #HEADER_HEIGHT} is 26, which makes
     * {@code MIN_PANEL_HEIGHT} {@code 26 + 24 + 77 = 127}. Every figure is written in terms of the
     * constants the bands are drawn with, so changing {@link #VIEW_BUTTON} or {@link #ROW_HEIGHT} cannot
     * silently invalidate it.
     *
     * <p><b>The margin between the two body terms is the number to watch, and it is why both are written
     * out rather than one being carried.</b> The canvas asks for 77 and the sidebar for 32, so the column
     * has 45 pixels of room for another feature before it starts deciding the minimum. A band does not
     * spend it -- the band is above both terms rather than inside one -- and a reader who does not know the
     * margin exists finds out from a screenshot.
     */
    public static final int MIN_PANEL_HEIGHT = HEADER_HEIGHT + AUTHOR_BAND_HEIGHT + Math.max(
            CHAPTER_GAP + SIDEBAR_ROW_HEIGHT + EDGE,
            (EDGE - VIEW_MAT) + (VIEW_COLUMN_HEIGHT + VIEW_MAT * 2) + EDGE);

    /**
     * Enough canvas for the graph and the view cluster beside it.
     *
     * <h2>What this term lost, and why it is a third of what it was</h2>
     *
     * <p>It was "enough canvas to be worth showing beside the sidebar" -- 80, a judgement -- and then it
     * became an arithmetic term, because the view cluster <i>and</i> the author's three pills floated over
     * the canvas's left edge in one band. That band was most of the minimum: 251 of the 407-pixel panel
     * this class asked for, so the canvas's furniture was what bound the whole book's width.
     *
     * <p><b>The band moved out of the canvas, so the term is the cluster alone.</b> The author's controls
     * are a strip of the panel's own chrome now ({@link #authorBand}), above the canvas rather than on it,
     * which leaves this as the one cluster that genuinely sits on the canvas: {@code (EDGE - VIEW_MAT)} of
     * mat, the buttons and their gaps, and one {@link #EDGE} at the canvas's right edge -- which is not
     * spare, because the dock and a docked column float there. The last {@code EDGE} is what the band used
     * to add on the far side of itself and no longer needs.
     *
     * <p>It is written from the constants the cluster is placed with rather than measured by eye, which is
     * how the width that used to be a judgement became something a test can sweep.
     */
    public static final int MIN_CANVAS_WIDTH =
            (EDGE - VIEW_MAT) + (VIEW_BUTTON + VIEW_MAT * 2) + EDGE;

    /**
     * The room the header's title needs, left of the controls: the inset it starts at and a word.
     *
     * <p>A term in {@link #MIN_PANEL_WIDTH} rather than a number in the drawing, because that is where it
     * is enforced -- a panel too narrow to hold it runs off the window instead.
     */
    public static final int HEADER_TITLE_ROOM = 32;

    /**
     * The narrowest the panel gets.
     *
     * <h2>Three terms, and the band is the one that binds</h2>
     *
     * <p>The header's run of controls -- Close, Rewards, Party, Settings -- plus {@link #HEADER_TITLE_ROOM}
     * for the book's own name; the sidebar beside a canvas worth showing
     * ({@code SIDEBAR_WIDTH + MIN_CANVAS_WIDTH}); and the author's band, which is its five buttons, the
     * seams between them and the panel's own {@link #EDGE} at each end.
     *
     * <p><b>The canvas used to be what bound, and that is the sentence to keep corrected.</b> While the
     * author's pills floated over it, the canvas's term was <b>407</b> (156 of sidebar and 251 of canvas)
     * against the header's 238, so the furniture of the graph decided how narrow the book could be. With the
     * band in the panel's chrome instead, the canvas's term falls to 193 and the header's 238 governs --
     * until the band's own term, <b>372</b>, passes both. So the minimum is the author's toolbar now, which
     * is the honest thing for it to be: that is the row whose controls must fit before anything else can be
     * read.
     *
     * <p>The figures in the paragraph above are prose, and no test reads them: the <i>sums</i> below are
     * asserted and the sentences about them are not, which is how a stale 257 once sat here unnoticed.
     * Anyone who needs a number should read it off the sums. Each is written from the constants its controls
     * are placed with, so widening one moves this with it -- the header's own test caught exactly that when
     * the rewards button arrived, which is the drift it exists to catch. A panel narrower than this comes
     * from a window too small to hold the book, and the band's buttons narrow with the panel rather than
     * running off it; see {@link #authorBandButtons}.
     */
    public static final int MIN_PANEL_WIDTH = Math.max(
            Math.max(SIDEBAR_WIDTH + MIN_CANVAS_WIDTH,
                    HEADER_CONTROL_INSET + ROW_HEIGHT                // Close
                            + ROW_GAP + REWARDS_BUTTON_WIDTH         // Rewards
                            + ROW_GAP + PARTY_BUTTON_WIDTH           // Party
                            + ROW_GAP + SETTINGS_BUTTON_WIDTH        // Settings
                            + ROW_GAP + PINNED_BUTTON_WIDTH          // Pinned
                            + HEADER_TITLE_ROOM),
            // And the band, which binds at 372 -- see above.
            EDGE + AUTHOR_WIDTH + BAND_BUTTON_GAP + ASSETS_WIDTH + BAND_BUTTON_GAP + EDIT_WIDTH
                    + BAND_BUTTON_GAP + ADVANCED_WIDTH + BAND_BUTTON_GAP + PREVIEW_WIDTH + EDGE);

    /** The largest the panel gets, however big the window is. */
    public static final int MAX_PANEL_WIDTH = 800;

    public static final int MAX_PANEL_HEIGHT = 480;

    /** How far the panel is inset from the window's edge, when the window is big enough for that. */
    public static final int PANEL_MARGIN = 20;

    // --- the modal card ------------------------------------------------------

    /**
     * The widest a modal card gets.
     *
     * <p>The overlay used to be <code>screenWidth - 48</code> by <code>screenHeight - 48</code>, which
     * at a 640-pixel GUI is a 592-by-300 card: not a panel but a second screen with a border. A six-row
     * roster inside one is a small list in a large empty room, and a column of prose inside one is
     * mostly margin.
     *
     * <p>380 is a compromise with a reason rather than a round number: the quest overlay's prose wraps
     * at this width to about sixty characters, which is where a line of text stays readable, and a
     * roster two hundred pixels wider than its longest name is the empty room the report was about.
     */
    public static final int MAX_MODAL_WIDTH = 520;

    /**
     * The widest a card gets, for the one surface that reads in columns.
     *
     * <h2>Why a second cap rather than a wider first one</h2>
     *
     * <p>Because the two answers are answers to different questions. {@link #MAX_MODAL_WIDTH}'s 380 was
     * measured against <i>prose</i> — the width at which a line of a quest's description stays readable
     * — and raising it would make every card in the book a wider room for the same short sentences. The
     * claim menu is not prose: it is four columns of icons, counts and buttons, and the width it needs
     * is the sum of the four, not a readability limit. Widening {@code MAX_MODAL_WIDTH} would have moved
     * the party roster, the item picker and the settings card to fix a fault in none of them.
     *
     * <p>660 rather than a rounder number: {@code MODAL_INSET * 2} off it leaves 636 for the row, which
     * is the widest context column ({@code CONTEXT}) plus the progression, reward and action columns and
     * their gaps with room left for a quest title. It is a <b>cap</b>, not a size — a card narrower than
     * this gets the window, and the columns clamp rather than running off it.
     */
    public static final int MAX_WIDE_MODAL_WIDTH = 660;

    /** The tallest a modal card gets. Past this the body scrolls rather than the card growing. */
    public static final int MAX_MODAL_HEIGHT = 340;

    /** Below this a card cannot hold a control and its label without the two colliding. */
    public static final int MIN_MODAL_WIDTH = 200;

    public static final int MIN_MODAL_HEIGHT = 120;

    /** How far a modal card is inset from the window's edge, when the window is too small for the cap. */
    public static final int MODAL_MARGIN = 24;

    /**
     * How far a modal's content sits from its card's edge — the same on all four sides.
     *
     * <h2>One number, and it replaced four that disagreed</h2>
     *
     * <p>A card's padding was 16 on the left and right ({@code EDGE * 2}), 10 at the top, 8 at the
     * bottom, and the footer's controls were nudged by a further half a control's height on the right
     * for no stated reason. The report was exact and is the reason this is one constant: <i>"there is
     * more space on the x axis border than y, fix that, make it basically equal"</i>.
     *
     * <p>It is worth saying why four numbers ever looked acceptable. Each of them was arrived at
     * separately and each was defensible on its own — {@code EDGE * 2} because a row inside the card
     * should line up with a row inside the panel, 10 because a title's line box is only nine pixels
     * tall, 8 because it was {@code EDGE}, reused. None of them was ever compared with the others,
     * which is the whole of the fault: padding is a <b>single</b> decision, and four decisions that
     * happen to be plausible are four different answers to one question.
     *
     * <p>Twelve, because it has to be at least the panel's own {@link #EDGE} to read as a card rather
     * than as content touching its frame, and because it is the figure a footer's controls already sat
     * close to — so the card's height changes by a hair and its look changes completely.
     */
    public static final int MODAL_INSET = 12;

    /**
     * Between the body's bottom and the footer's controls. Not an inset: it separates two things.
     *
     * <p>Twelve, and that it equals {@link #MODAL_INSET} is the point rather than a coincidence: the
     * overlay draws its footer as one band, the controls are {@code MODAL_INSET} above the card's bottom,
     * and a band with anything else above them has them off its centre — which is what a report about a
     * Back button sitting high turned out to be. Ten was a number chosen on its own, next to twelve
     * chosen on its own, and the two never met until the band was drawn around both.
     */
    public static final int MODAL_FOOTER_GAP = 12;

    /**
     * The height of a control in the overlay's footer. Taller than a sidebar row: the overlay is the
     * screen where a player hands something in, so its controls are the primary thing on it.
     *
     * <h2>Why it is declared above the modal constants rather than with the overlay's own</h2>
     *
     * <p>Because {@link #MODAL_CHROME} is derived from it, and a static initializer cannot read a field
     * declared below it. That is a rule about the language rather than a preference, and it points the
     * right way here: a modal reserves exactly one control's height for its footer whether it is the
     * full-screen overlay or a content-sized card, so this number belongs to both and is declared
     * between them rather than with one.
     */
    public static final int OVERLAY_CONTROL_HEIGHT = 20;

    /**
     * The card's own chrome: the top inset, the body-to-footer gap, the controls, and the bottom inset.
     *
     * <p>What {@link #modalFramed} adds to a body's height, and it is now <b>derived</b> from the four
     * numbers that actually place things rather than written out as a figure of its own. It used to be
     * 84, a number that agreed with none of them: a body built for content plus 84 was 30 pixels taller
     * than the footer and the padding accounted for, so a card sized to a short roster had a hand's
     * width of nothing between its last row and its buttons. That is half of what the reported
     * "still almost blank" panel was.
     *
     * <p>Written as the sum because the sum is the definition. A reader who wants to know how tall a
     * card has to be should be able to read it off the four constants that decide it, not multiply
     * {@code MODAL_CHROME} by one and check {@link #partyControls} by eye.
     */
    public static final int MODAL_CHROME =
            MODAL_INSET + MODAL_FOOTER_GAP + OVERLAY_CONTROL_HEIGHT + MODAL_INSET;

    /**
     * The footer's whole region: the gap above the controls, the controls, and the inset below them.
     *
     * <p>What the card reserves for its footer, and what the overlay draws as the footer's band. One
     * constant rather than two expressions, because the band's height and the row's position are the
     * same decision: a band computed separately is a band the controls are not centred in.
     */
    public static final int MODAL_FOOTER_HEIGHT =
            MODAL_FOOTER_GAP + OVERLAY_CONTROL_HEIGHT + MODAL_INSET;

    // --- the docked side panel -----------------------------------------------

    /**
     * The air around a docked column, and between two of them.
     *
     * <p>The tools dock's own {@code ToolsLayout.GAP}, and the same number for the same reason: a column
     * touching the canvas's edge would read as a canvas that had been re-laid-out rather than as
     * something floating over it. It costs six pixels of graph on each side, which is the trade the dock
     * already makes.
     */
    public static final int PANEL_GAP = 6;

    /**
     * How wide the strip is that a press drags a column by.
     *
     * <p>On the column's <b>inner</b> edge and inside it, so the grab band is a pixel the column owns. A
     * band reaching past that edge into the canvas would be a press that either resizes or answers the
     * graph, and which of the two it was would come down to a rounding — the fault {@code ToolsLayout}
     * records about its own clamp.
     *
     * <p><b>Deliberately narrower than {@link #MODAL_INSET}</b>, which is the inset every body already
     * uses: the handle owns the outermost pixels of the column and no content may be drawn there, so the
     * relation between the two numbers is what keeps a text field's first pixel from being a resize. It
     * is asserted in {@code PanelLayoutTest} rather than left to be noticed — the same treatment the
     * panel's minimum width gets.
     */
    public static final int PANEL_HANDLE = 5;

    // --- the full-screen overlay ---------------------------------------------

    /**
     * Submit's width, and Back's.
     *
     * <p>Named rather than written at the call site because the screen used to pass <b>130</b> here
     * while this class said <b>120</b>. That is the whole reason this class exists: two descriptions of
     * one control, which agree until somebody edits one. The overlap test would have passed on 120
     * while the screen drew 130 and ran into Back — a green test guarding a layout that was not on
     * screen, which is worse than no test, because it would have been believed.
     */
    public static final int SUBMIT_WIDTH = 130;

    public static final int BACK_WIDTH = 88;

    /**
     * Leave's and Disband's width, in the party panel's footer.
     *
     * <h2>Why these are not {@link #SUBMIT_WIDTH}</h2>
     *
     * <p>Because a 130-pixel button is sized for the one action a screen is about -- handing a task in.
     * "Leave" and "Disband" are six- and seven-letter words with a whole roster above them, and two of
     * them at Submit's width would take 260 of the 379 pixels a 427-wide window gives the card, pushing
     * Back onto a second row for no reason.
     *
     * <p>Wide enough for the longer word with the control's own insets: "Disband" is seven characters,
     * around 42 pixels at the font this UI draws with, plus the button's padding either side. The margin
     * above that is deliberate rather than measured to the pixel -- a translated label is longer than
     * the English one in most languages, and a button that truncates its own label reads as a fault in
     * whichever language noticed it first.
     */
    /**
     * How wide a control has to be for a short word, at the font this UI draws with.
     *
     * <h2>Why this exists, and what it is a fix for</h2>
     *
     * <p>64 and 88 were both chosen against an estimate of the font, and the estimate was short. The
     * screenshot came back with Leave drawn as "e" and Back as "Bac", because {@code ArmatureButton}
     * truncates against the real font at the client's GUI scale. A width written out by hand is a width
     * that is wrong on somebody else's window, and wrong in the direction that reads as a bug in the
     * button rather than in the constant.
     *
     * <p>Six pixels a character is close to the advance of the default font for the characters in these
     * labels, and it is measured rather than invented: "Disband" is seven characters, "Leave" five,
     * "Back" four. The padding either side is the button's own and is larger than a bare control's,
     * because the label sits inside a bordered box.
     *
     * <p>This deliberately does not measure the real font, because a geometry class has no font and must
     * not grow one; {@code Measure} exists for that and lives in the kit. So it is a margin of safety
     * for a known set of short English words, plus ~30% headroom for longer translations (German
     * "Auflösen", French "Dissoudre"): 66 was exact for English and tight everywhere else, 80 fits.
     */
    public static final int PARTY_SHORT_LABEL_WIDTH = 80;

    /** Leave's and Disband's width, in the party panel's footer. See {@link #PARTY_SHORT_LABEL_WIDTH}. */
    public static final int PARTY_ACTION_WIDTH = PARTY_SHORT_LABEL_WIDTH;

    /**
     * How wide the party panel's card wants to be.
     *
     * <p>Wider than a paragraph needs and wider than the default cap was, because a roster is rows of
     * two things -- a name on the left and a rank on the right -- and the two drift apart into a gap
     * nobody reads across if the card is narrow. Its own constant rather than the panel's width,
     * because "how wide should this card be" is a question each modal answers for itself; see
     * {@link #modalFramed(int, int)}.
     */
    public static final int PARTY_MODAL_WIDTH = 320;

    // --- labels --------------------------------------------------------------

    /** A node label is never wider than this, however much room there is. */
    public static final int MAX_LABEL_WIDTH = 120;

    /** Below this there is no room for a readable label, so none are drawn. */
    public static final int MIN_LABEL_WIDTH = 30;

    /** Kept between a label and the node beside it. */
    public static final int LABEL_GAP = 4;

    // ------------------------------------------------------------------
    // A rectangle, and the three questions anything asks of one
    // ------------------------------------------------------------------

    /**
     * A rectangle in GUI coordinates.
     *
     * <p>{@link #intersects} is <b>exclusive</b> at the edges: two rectangles that share only an edge
     * do not intersect. That is what makes it usable for "do these two controls collide" — a pair
     * drawn edge to edge is touching, not overlapping, and reporting it as a collision would make the
     * test useless.
     */
    public record Rect(int x, int y, int width, int height) {

        public static Rect at(int x, int y, int width, int height) {
            return new Rect(x, y, width, height);
        }

        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        /**
         * The rectangle grown by {@code margin} on every side: hover room, not layout.
         *
         * <p>Hit tests for presses stay exact — a press that spills onto the canvas must act on
         * the canvas — but a hover that ends a pixel short of an edge reads as a control that
         * flickers. The sliver's peek zone and the box's keep zone both read through this, so
         * the pointer has room to live around the thing it is holding open.
         */
        public Rect inflate(int margin) {
            return new Rect(x - margin, y - margin, width + margin * 2, height + margin * 2);
        }

        public boolean intersects(Rect other) {
            return x < other.right() && other.x < right()
                    && y < other.bottom() && other.y < bottom();
        }

        /**
         * Whether a point is inside. {@code double} because the mouse coordinates are, and a
         * {@code double} silently truncated to an int puts the pointer one pixel off at a boundary.
         */
        public boolean contains(double px, double py) {
            return px >= x && px < right() && py >= y && py < bottom();
        }

        /** Whether this rectangle is wholly inside {@code other}. */
        public boolean isInside(Rect other) {
            return x >= other.x && y >= other.y && right() <= other.right() && bottom() <= other.bottom();
        }

        @Override
        public String toString() {
            return x + "," + y + " " + width + "x" + height;
        }
    }

    // ------------------------------------------------------------------
    // The screen this was built for
    // ------------------------------------------------------------------

    private final int screenWidth;
    private final int screenHeight;
    private final boolean authorBand;
    private final SidebarState sidebarState;
    /** The expanded list's drawn width: the player's, clamped and capped to the window. */
    private final int sidebarWidth;
    private final Rect panel;
    private final Rect header;
    private final Rect canvas;
    private final Rect sidebar;
    private final Rect overlay;

    /** The book with a margin around it: the shape every player sees, and no author's band. */
    public BookGeometry(int screenWidth, int screenHeight) {
        this(screenWidth, screenHeight, false, false);
    }

    /**
     * The same, as a card or full-bleed -- and without an author's band.
     *
     * <p>The band is the last thing a geometry decides, and it is not the same question as full-bleed even
     * though the screen currently answers both with {@code mayEdit()}: full-bleed is how much window the
     * book takes, and the band is whether the author's controls are drawn over the top of it. Kept apart so
     * a reader's card and an operator's full-bleed book can be swept independently -- and so the overlap
     * sweep can build a band with no margin, or a margin with no band, and see the geometry rather than a
     * combination that only ever occurs by accident.
     */
    public BookGeometry(int screenWidth, int screenHeight, boolean fullBleed) {
        this(screenWidth, screenHeight, fullBleed, false);
    }

    /**
     * The book: a card with a margin or full-bleed, with or without the author's band.
     *
     * <h2>Why an author gets the whole window</h2>
     *
     * <p>Because the margin and the maximum size are there to keep a <i>reading</i> panel from looking
     * like a wall of text -- and an author is not reading. While the tools are reachable, every pixel the
     * window is not using is a pixel of canvas or of colour list, and the report was exact: <i>"make the
     * quest book menu itself take up 100% of the screen instead of just almost 100%"</i>.
     *
     * <p>It is one flag rather than two geometries because every rectangle here is derived from the panel:
     * the header, the sidebar, the canvas and the tools panel all follow from this one decision, which is
     * the property this class exists to keep.
     *
     * <h2>The band, and why its buttons are the panel's rather than the canvas's</h2>
     *
     * <p>{@code authorBand} reserves {@link #AUTHOR_BAND_HEIGHT} between the header and the body and moves
     * both columns down by it. That is the whole of it, and it is what makes the author's controls
     * impossible to cover: a rail is anchored to the canvas ({@link #panelRail}), so the highest thing a
     * panel can ever draw over is the canvas's top edge -- which is now below the band.
     *
     * <p>It is a flag rather than something the screen draws over the top, because the alternative is
     * worse than it looks: the pills this band replaced floated over the canvas, so a maximally wide panel
     * covered them, and the canvas's own minimum had to carry their width (see {@link #MIN_CANVAS_WIDTH}).
     * The band costs 24 pixels of canvas height and gives back 214 of minimum canvas width -- and 110 of the
     * panel's, because the canvas's term was what bound it. That is the trade this flag records.
     */
    public BookGeometry(int screenWidth, int screenHeight, boolean fullBleed, boolean authorBand) {
        this(screenWidth, screenHeight, fullBleed, authorBand, SidebarState.DEFAULT);
    }

    /**
     * The book, with the chapter list's own state beside the window it is drawn in.
     *
     * <p>The sidebar's three answers travel together ({@link SidebarState}) because every
     * rectangle below follows from all three at once: the canvas is full-width unless the list
     * is pinned <i>and</i> expanded, and the list's own rectangle is the expanded one or its
     * sticking-out strip. A caller holding a peek therefore resolves it first — a collapsed
     * state being peeked is passed as floating expanded — so this never sees a state that is
     * half held by the pointer, and the file's three answers stay the whole of what is drawn.
     */
    public BookGeometry(int screenWidth, int screenHeight, boolean fullBleed, boolean authorBand,
            SidebarState sidebar) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.authorBand = authorBand;
        this.sidebarState = sidebar == null ? SidebarState.DEFAULT : sidebar;

        // Clamped to a minimum, and allowed to exceed the window below it. A window smaller than this
        // cannot show the book sensibly at any layout, so the choice is between a panel that runs off
        // the edge -- which is clipped, and obviously the window being too small -- and a panel whose
        // own contents overlap, which looks like a bug in the drawing code.
        // Full bleed: the panel is the window, margins and maximums and all. The minimums still apply to
        // the *card*, since a window smaller than the book cannot show it either way.
        int panelWidth = fullBleed ? screenWidth
                : clamp(Math.min(MAX_PANEL_WIDTH, screenWidth - PANEL_MARGIN * 2),
                        MIN_PANEL_WIDTH, MAX_PANEL_WIDTH);
        int panelHeight = fullBleed ? screenHeight
                : clamp(Math.min(MAX_PANEL_HEIGHT, screenHeight - PANEL_MARGIN * 2),
                        MIN_PANEL_HEIGHT, MAX_PANEL_HEIGHT);

        // Integer division, so a panel of odd width sits one pixel further left than right rather
        // than leaving a half pixel. Every other rectangle is derived from these two, so an odd
        // panel width is a one-pixel asymmetry everywhere and never a rounding drift.
        int panelLeft = (screenWidth - panelWidth) / 2;
        int panelTop = (screenHeight - panelHeight) / 2;

        this.panel = Rect.at(panelLeft, panelTop, panelWidth, panelHeight);
        this.header = Rect.at(panelLeft, panelTop, panelWidth, HEADER_HEIGHT);

        // The body: everything below the header and, for an author, below the band. Both columns start
        // here, so a reader's taller canvas and a shorter one for the operator follow from one number --
        // and the band's own strip is drawn over exactly the 24 pixels this skips.
        int bodyTop = panelTop + HEADER_HEIGHT + (authorBand ? AUTHOR_BAND_HEIGHT : 0);
        int bodyHeight = panelHeight - (bodyTop - panelTop);

        // The expanded list's drawn width: the player's, clamped to its own range and then capped
        // against the window, so a remembered width can never eat the canvas on a window too small
        // for it. The cap is drawn, not stored: growing the window brings the width back without a
        // second drag.
        this.sidebarWidth = drawnSidebarWidth(panelWidth, this.sidebarState.width());

        // Docked is today's rectangle at the drawn width; floating is the same rectangle over the
        // canvas rather than beside it, so sliding between them is one wipe rather than two
        // surfaces. Collapsed is the sticking-out strip at the panel's left edge, where the tab
        // the pointer hovers is.
        // Docked is today's rectangle at the drawn width and cannot collapse — the old mode is
        // the old column, whatever the file's other answers say. Floating is a box on the canvas;
        // collapsed is that same box slid left until only its peek sticks out.
        Rect body = Rect.at(panelLeft, bodyTop, panelWidth, bodyHeight);
        if (!this.sidebarState.floating()) {
            this.sidebar = Rect.at(panelLeft, bodyTop, this.sidebarWidth, bodyHeight);
        }
        else if (this.sidebarState.expanded()) {
            this.sidebar = sidebarFloatRect(this.sidebarWidth, body);
        }
        else {
            Rect box = sidebarFloatRect(this.sidebarWidth, body);
            int over = Math.max(0, box.width() - SIDEBAR_PEEK_WIDTH);
            this.sidebar = Rect.at(box.x() - over, box.y(), box.width(), box.height());
        }
        // Pinned and expanded, the canvas starts past the list, as it always has. Anything else —
        // floating, or slid away to its sliver — and the graph gets the whole body: that regained
        // map is the point of both.
        this.canvas = !this.sidebarState.floating()
                ? Rect.at(panelLeft + this.sidebarWidth, bodyTop, panelWidth - this.sidebarWidth,
                        bodyHeight)
                : Rect.at(panelLeft, bodyTop, panelWidth, bodyHeight);
        this.overlay = Rect.at(OVERLAY_MARGIN, OVERLAY_MARGIN,
                Math.max(MIN_PANEL_WIDTH, screenWidth - OVERLAY_MARGIN * 2),
                Math.max(MIN_PANEL_HEIGHT, screenHeight - OVERLAY_MARGIN * 2));
    }

    /** Whether this geometry reserves the author's band. */
    public boolean hasAuthorBand() {
        return authorBand;
    }

    /**
     * Where the panel's body starts: below the header, and below the band when there is one.
     *
     * <p>Public because two things outside this class need the same edge and must not state it twice: the
     * chrome's painting, which fills the band's strip between the header's rule and this line, and the
     * sidebar's clip, which starts at {@link #chapterListTop()} just below it. One number, in the class
     * that owns the panels -- which is the rule this class exists for.
     */
    public int bodyTop() {
        return header.bottom() + (authorBand ? AUTHOR_BAND_HEIGHT : 0);
    }

    public int screenWidth() {
        return screenWidth;
    }

    public int screenHeight() {
        return screenHeight;
    }

    /** The whole book. */
    public Rect panel() {
        return panel;
    }

    /** The title bar, which holds the title, the quest count and Close. */
    public Rect header() {
        return header;
    }

    /** The graph, inside the panel. */
    public Rect canvas() {
        return canvas;
    }

    /** The chapter list, inside the panel. */
    public Rect sidebar() {
        return sidebar;
    }

    /**
     * How the chapter list is shown: the player's width, pin and collapse, as the file holds them.
     *
     * <p>Exposed because the screen builds its cache key from it: a toggle that left the geometry
     * cached would draw yesterday's rectangles under today's widgets, which is the class of fault
     * this class exists to end.
     */
    public SidebarState sidebarState() {
        return sidebarState;
    }

    /**
     * The expanded list's drawn width: the player's, clamped and capped to the window.
     *
     * <p>What the list is drawn at and what a drag starts from. The file's own number lives in
     * the state above; this is that number made legal for this window, so a remembered width is
     * never the thing that eats the canvas.
     */
    public int sidebarWidth() {
        return sidebarWidth;
    }

    // ------------------------------------------------------------------
    // The chapter list's own arithmetic
    // ------------------------------------------------------------------

    /**
     * The player's width made legal: into its own range, whatever the file says.
     *
     * <p>One function rather than a floor and a ceiling at the call site, for the reason
     * {@code PanelStack#clampWidth} states: a drag and a hand-edited file both go through it,
     * because a remembered number is only as legal as this makes it.
     */
    public static int clampSidebarWidth(int wanted) {
        return clamp(wanted, SIDEBAR_MIN_WIDTH, SIDEBAR_MAX_WIDTH);
    }

    /**
     * The width the expanded list is drawn at in a panel this wide.
     *
     * <p>The clamp above, then a cap against the window: the list keeps {@link #MIN_CANVAS_WIDTH}
     * of graph beside it even in a full-bleed window smaller than the book, so a remembered 260
     * never covers the canvas it is for. The cap is drawn, not stored — growing the window
     * brings the width back without a second drag.
     */
    public static int drawnSidebarWidth(int panelWidth, int wanted) {
        return Math.max(0,
                Math.min(clampSidebarWidth(wanted), Math.max(0, panelWidth - MIN_CANVAS_WIDTH - EDGE)));
    }

    /**
     * The floating box's pin zone: what keeps the box open when the pointer leaves it.
     *
     * <p>Right-aligned in the box's own header, where the close square stood: pinned, the box
     * stays; unpinned, it slides to its sliver when the pointer leaves, like a peek that never
     * ends. Drawn as a dot rather than a square — filled while pinned, a ring while not — and
     * hit-tested here, the scrollbar's own arrangement: chrome the screen draws and answers
     * itself, with the settings switch as the focusable, narrated road to the same choice.
     */
    public Rect sidebarBoxPin() {
        return sidebarBoxPinFor(sidebar);
    }

    /**
     * The same zone for any box rectangle, settled or mid-wipe.
     *
     * <p>The drawing reads it rather than the field: mid-wipe the surface travels and the dot
     * travels on it, while presses keep answering the settled zone — a press landing mid-wipe is
     * absorbed before it reaches either.
     */
    public static Rect sidebarBoxPinFor(Rect box) {
        return Rect.at(box.right() - 4 - ROW_HEIGHT,
                box.y() + (SIDEBAR_BOX_HEADER - ROW_HEIGHT) / 2, ROW_HEIGHT, ROW_HEIGHT);
    }

    /**
     * The box a floating list sits in: inset from the canvas on every side, like a rail anchored
     * left where rails anchor right.
     *
     * <h2>Why this mirrors {@link #panelRail} instead of sharing it</h2>
     *
     * <p>Because the anchor is the whole difference: a rail hangs off the canvas's right edge and
     * this hangs off its left, so the column the graph keeps clear is on opposite sides. The cap
     * is the same — the canvas less a {@link #PANEL_GAP} on each side — and a width of zero or
     * less, or a canvas with no room at all, gives a zero-width rectangle rather than a negative
     * one, for the same reason that method states.
     *
     * @param drawnWidth the expanded list's drawn width, already clamped and capped for the window
     * @param body       the whole body the graph gets while the list floats
     */
    public static Rect sidebarFloatRect(int drawnWidth, Rect body) {
        int limit = Math.max(0, body.width() - PANEL_GAP * 2);
        int w = Math.max(0, Math.min(drawnWidth, limit));
        int h = Math.max(0, body.height() - PANEL_GAP * 2);
        return Rect.at(body.x() + PANEL_GAP, body.y() + PANEL_GAP, w, h);
    }

    /** The whole body the graph gets while the list floats: the canvas's own rectangle then. */
    private Rect sidebarBody() {
        return Rect.at(panel.x(), bodyTop(), panel.width(), panel.bottom() - bodyTop());
    }

    /**
     * The width a drag of the list's edge has reached.
     *
     * <p>The mirror of {@code PanelStack#widthWhileDragging}: a rail docks right so its inner
     * edge is its left and moving the pointer left widens it, while the list docks left so its
     * inner edge is its right and moving the pointer <i>right</i> widens it. Pure, so the gesture
     * is asserted without a mouse.
     *
     * @param startWidth the width when the press landed
     * @param startX     where the press landed
     * @param currentX   where the pointer is now
     */
    public static int sidebarWidthWhileDragging(int startWidth, double startX, double currentX) {
        return clampSidebarWidth((int) Math.round(startWidth + (currentX - startX)));
    }

    /**
     * The strip a press drags the list by: its inner (right) edge, inside it.
     *
     * <p>The mirror of {@link #panelHandle}, which sits on a rail's left because rails dock
     * right. Static and taking the list, for the same reason that one does: a caller holding
     * the list should be able to ask where its handle is without holding this geometry.
     */
    public static Rect sidebarHandle(Rect list) {
        return Rect.at(list.right() - Math.min(SIDEBAR_HANDLE, Math.max(0, list.width())), list.y(),
                Math.min(SIDEBAR_HANDLE, Math.max(0, list.width())), list.height());
    }

    // ------------------------------------------------------------------
    // The docked column
    // ------------------------------------------------------------------

    // (The centred card stood here: `modal()`, `wideModal()`, the private `centred` they shared, the two
    // `...ModalFramed` entry points, the private `framed` they shared, `modalBody` and the `overlay` alias.
    // They are gone with the presentation they described -- every kind is a rail now, and the rails' own
    // rectangles are below. `MAX_WIDE_MODAL_WIDTH` stays because it is a live number rather than a dead
    // rectangle: `PanelStack.WIDE_WIDTH` is it, which is why a wide kind opens at the width its layout was
    // drawn against when it was a card. `MODAL_CHROME`, `MODAL_INSET` and the footer's two figures stay for
    // the same reason -- the columns' own bands are measured with them.)

    /**
     * The left edge no rail may cross: the canvas's own, except beside an open floating list.
     *
     * <p>The floating list is drawn over the canvas and its rows over anything beneath it, so a rail
     * reaching under the list had chapter rows printed across its content.
     */
    public int railFloor() {
        return sidebarState.floating() && sidebarState.expanded()
                ? Math.max(canvas.x(), sidebar.right())
                : canvas.x();
    }

    /** The width the rails share: the canvas right of {@link #railFloor()}. */
    public int railRoom() {
        return Math.max(0, canvas.right() - railFloor());
    }

    /**
     * The column a docked panel occupies: inside the canvas, against its right edge, never off the book.
     *
     * <h2>Why the width is a parameter rather than a constant here</h2>
     *
     * <p>Because it is the player's. Every other rectangle in this class follows from the window; this one
     * follows from the window <i>and</i> a width the player drags and the settings file remembers, so it
     * is the one position that cannot be a field. The arithmetic is still this class's, which is the point
     * — {@code BookGeometryTest} sweeps window sizes and widths together, and the width a drag writes is
     * the width the column draws at, because there is only one expression.
     *
     * <h2>The canvas is what it covers, and it never covers the sidebar</h2>
     *
     * <p>A column is anchored to the canvas's right edge and may cover the whole of it — that is what "the
     * panel wins" means — but the cap is the canvas less a {@link #PANEL_GAP} on each side, so it can
     * never reach a docked chapter list. Hiding the navigation to show a quest would be a trade nobody asked
     * for: the list is how the reader gets anywhere else. An open <i>floating</i> list is held clear the same
     * way, through {@link #railFloor()}: it sits over the canvas's left corner, and a rail reaching under it
     * had the list's rows drawn across the quest.
     *
     * <p>At the cap the column is the canvas minus a hair on each side, which reads as a full-page panel
     * rather than as a slab pinned to one edge. A width of zero or less, or a canvas with no room at all,
     * gives a zero-width rectangle rather than a negative one: a caller drawing nothing is a caller whose
     * bug is visible, and a caller drawing a rectangle of negative width is a crash.
     */
    public Rect panelRail(int width) {
        int limit = Math.max(0, railRoom() - PANEL_GAP * 2);
        int w = Math.max(0, Math.min(width, limit));
        int h = Math.max(0, canvas.height() - PANEL_GAP * 2);
        return Rect.at(canvas.right() - PANEL_GAP - w, canvas.y() + PANEL_GAP, w, h);
    }

    /**
     * A rail one gap to the left of another: the one expression every panel but the outermost is placed by.
     *
     * <h2>Anchored to the rail beside it rather than to the canvas</h2>
     *
     * <p>So a chain of rails keeps its gaps whatever any one of their widths is — dragging the outer column
     * moves the inner ones with it, which is what makes the three read as one dock rather than as three
     * panels that happen to be near each other. The room a rail has is what is left of the canvas to the
     * left of the one it hangs off, and it is <b>zero-width when there is none</b>: whether to fold instead
     * is {@code PanelLayout}'s question, and it answers it before asking for this rectangle. Answering it
     * here as null would give every caller a second thing to check for the same fact.
     *
     * <p>This is the author's dock's own rule, written once: with the dock latched, column 1 hangs off it and
     * column 2 hangs off column 1, so the dock is the only rail anchored to the canvas and the only one that
     * cannot move. With no dock the chain starts at the canvas edge and every rectangle is what it always
     * was — {@link #panelRail2} is the two-link case of this, spelled out because that is the name its own
     * callers and tests know it by.
     *
     * @param width what this rail wants
     * @param inner the rail it sits to the left of
     */
    public Rect panelRailBefore(int width, Rect inner) {
        int limit = Math.max(0, inner.x() - railFloor() - PANEL_GAP * 2);
        int w = Math.max(0, Math.min(width, limit));
        return Rect.at(inner.x() - PANEL_GAP - w, inner.y(), w, inner.height());
    }

    /**
     * The second column: the same rail, one gap to the left of the first. See {@link #panelRailBefore}.
     */
    public Rect panelRail2(int width2, int width1) {
        return panelRailBefore(width2, panelRail(width1));
    }

    /**
     * The strip a press drags a column by: its inner edge, inside it.
     *
     * <p>Static, and takes the column, because it is a function of one rectangle and no window — the same
     * shape {@link #modalBody} has, and for the same reason: a caller holding a column should be able to
     * ask where its handle is without holding the geometry that produced it.
     */
    public static Rect panelHandle(Rect rail) {
        return Rect.at(rail.x(), rail.y(), Math.min(PANEL_HANDLE, Math.max(0, rail.width())),
                rail.height());
    }

    // ------------------------------------------------------------------
    // The positions that the drawing and the controls both need
    // ------------------------------------------------------------------

    /**
     * Where the sidebar's list starts. The top of {@link #sidebarViewport()}, and nothing else.
     *
     * <p>Measured from {@link #bodyTop()} rather than from the header, so an author's list starts below the
     * band: the band is chrome over the whole width of the panel, and a chapter row under it would be a row
     * drawn underneath a control.
     */
    public int chapterListTop() {
        return bodyTop() + CHAPTER_GAP;
    }

    /**
     * The region the sidebar's rows are scrolled within.
     *
     * <h2>What this replaces, and why a rectangle is the right thing to hand out</h2>
     *
     * <p>Two methods used to be here: {@code chapterRows()}, which answered how many rows fit, and
     * {@code chapterRowY(int)}, which answered where row {@code n} is. Both are gone, and the reason is
     * not that they were wrong — they were right, and asserted — but that they were <b>a second place
     * that knew where a row goes</b>. The rows are placed by a {@link dev.ellipog.armature.client.ui.kit.Stack}
     * now, so that it, and the
     * {@link dev.ellipog.armature.client.ui.kit.ScrollView} over it, own hit-testing and culling. A
     * {@code chapterRowY} still standing here would be an arithmetic that agrees with the Stack on the
     * day it is written and drifts the first time either changes — the failure mode this whole class
     * exists to make impossible.
     *
     * <p>What is left that genuinely belongs to the framing is the <b>region</b>: where the list is
     * allowed to draw. That is a fact about the panel, which is this class's business, and it is the one
     * thing a caller cannot derive from the rows themselves — a list cannot know where its own edge is.
     *
     * <h2>The three subtractions, each with a reason</h2>
     *
     * <ul>
     *   <li><b>Top: {@link #chapterListTop()}.</b> Below the header and its gap.</li>
     *   <li><b>Bottom: the panel's own {@link #EDGE}.</b> Measured to the panel's edge, because there is
     *       nothing else below the list any more — see {@link #MIN_PANEL_HEIGHT} for the two appearance
     *       rows that used to be anchored there and the bug their anchoring caused.</li>
     *   <li><b>Right: {@link #SIDEBAR_SCROLLBAR} and {@link #SIDEBAR_HANDLE}.</b> Room for the
     *       scrollbar, so the bar is drawn in the sidebar's margin rather than over the end of a row. The
     *       alternative — a bar drawn over the content, or a viewport that stops short of one — puts the
     *       bar and the row it belongs to in the same pixels, and a row that a bar can be read through is
     *       a row whose text is cut off. Past the bar, room for the resize handle, for the same reason
     *       one step further out: a grip over the bar would shadow the thumb it overlaps.</li>
     * </ul>
     *
     * <p>Both dimensions are floored at zero rather than assumed. A window can be dragged smaller than
     * {@link #MIN_PANEL_WIDTH}, and a negative-width rectangle places a widget at a position that reads
     * correctly everywhere it is used — which is the shape of bug that survives to a screenshot. A
     * zero-area viewport draws nothing and can be clicked through, which is visibly wrong.
     */
    public Rect sidebarViewport() {
        return sidebarViewport(true);
    }

    /**
     * The same region, with or without the add-buttons strip above it.
     *
     * <p>The strip is drawn only in edit mode, and its height is therefore only part of the list's
     * geometry then: reserving it for a reader leaves dead space above the first row, which is the report
     * this parameter answers. The parameterless form reserves it, because the headerless question -- "how
     * much room would the rows have if the toolbar were there" -- is the one the overlap sweep asks, and
     * the caller that knows which mode it is drawing in passes it.
     */
    public Rect sidebarViewport(boolean toolbar) {
        return sidebarViewport(toolbar, true);
    }

    /**
     * The same region, with the scrollbar's gutter reserved only when asked.
     *
     * <p>An always-reserved gutter is dead space beside every list short enough not to scroll:
     * the bar draws nothing when everything fits (a full-height grip would say there is more),
     * so the rows stop a scrollbar short of the strip above them and a handle short of symmetric
     * with the left edge — which is exactly what a selected row makes visible. The caller that
     * knows the list fits passes false and the rows take the gutter; every caller that does not
     * know keeps today's reservation.
     */
    public Rect sidebarViewport(boolean toolbar, boolean scrollbar) {
        if (sidebarState.collapsed()) {
            // The sliver carries no rows: hovering it peeks the box, and the box it peeks is
            // built when the peek begins. A zero-area viewport is what tells every caller that.
            return Rect.at(panel.x(), chapterListTop(), 0, 0);
        }
        if (sidebarState.floating()) {
            // The floating box's rows: below its own header (and the toolbar when authors), above
            // its bottom inset, between its side insets with room for the scrollbar and the grip —
            // the same three reservations the docked list makes, measured from the box instead.
            int boxTop = sidebar.y() + SIDEBAR_BOX_HEADER + ROW_GAP
                    + (toolbar ? SIDEBAR_ROW_HEIGHT + SIDEBAR_TOOLBAR_GAP : 0);
            int boxBottom = sidebar.bottom() - EDGE;
            return Rect.at(sidebar.x() + EDGE, boxTop,
                    Math.max(0, sidebar.width() - EDGE * 2 - (scrollbar ? SIDEBAR_SCROLLBAR : 0)
                            - SIDEBAR_HANDLE),
                    Math.max(0, boxBottom - boxTop));
        }
        int top = toolbar
                ? chapterListTop() + SIDEBAR_ROW_HEIGHT + SIDEBAR_TOOLBAR_GAP
                : chapterListTop();
        // To the party strip, not to the panel's edge, and that is what the strip costs the list. The
        // alternative -- letting the rows run the full height and drawing the strip over them -- is the
        // class of fault this whole class exists to prevent: a row that stays clickable under a control
        // drawn on top of it.
        int bottom = panel.bottom() - EDGE;
        // Room for the scrollbar and then for the resize handle, side by side: the bar sits in
        // its own 8 pixels and the handle owns the outermost 5, so a press on one can never be
        // read as the other. The rows end where the bar begins, exactly as they always have —
        // only the bar stands 5 pixels further from the edge than it did, which is what keeps the
        // grip from covering the thumb it would otherwise shadow. Callers that know no bar will
        // show pass scrollbar as false (see the overload), so a list that fits takes the gutter.
        return Rect.at(panel.x() + EDGE, top,
                Math.max(0, sidebarInner(sidebarWidth) - (scrollbar ? SIDEBAR_SCROLLBAR : 0)
                        - SIDEBAR_HANDLE),
                Math.max(0, bottom - top));
    }


    /**
     * The sidebar's add buttons: two halves of a strip above the list.
     *
     * <p>A strip rather than a control in the header, because these add to <i>this list</i>: a button
     * that makes a chapter belongs where the chapters are, and the header's right-hand cluster is
     * already the place controls go when they are about the book rather than about the list.
     */
    public Map<String, Rect> sidebarToolbar() {
        Map<String, Rect> out = new LinkedHashMap<>();
        if (sidebarState.collapsed()) {
            // No room on the strip for two half-width buttons: an author expands to add chapters,
            // which is one more reason the strip is a preview rather than a home.
            return out;
        }
        if (sidebarState.floating()) {
            // The box's own strip, under its header: the same two halves, measured from the box
            // rather than the panel, and short of the handle like the rows below it.
            int boxTop = sidebar.y() + SIDEBAR_BOX_HEADER + ROW_GAP;
            int boxWidth = Math.max(0, sidebar.width() - EDGE * 2 - SIDEBAR_HANDLE);
            int boxEach = Math.max(0, (boxWidth - SIDEBAR_ROW_GAP) / 2);
            out.put("addChapter",
                    Rect.at(sidebar.x() + EDGE, boxTop, boxEach, SIDEBAR_ROW_HEIGHT));
            out.put("addGroup", Rect.at(sidebar.x() + EDGE + boxEach + SIDEBAR_ROW_GAP, boxTop,
                    boxEach, SIDEBAR_ROW_HEIGHT));
            return out;
        }
        int top = chapterListTop();
        // Short of the resize handle, like the rows below: the handle owns the list's outermost
        // 5 pixels for its whole height, toolbar strip included, so a button under it would take
        // presses the edge answers.
        int width = Math.max(0, sidebarInner(sidebarWidth) - SIDEBAR_HANDLE);
        int each = Math.max(0, (width - SIDEBAR_ROW_GAP) / 2);
        out.put("addChapter", Rect.at(panel.x() + EDGE, top, each, SIDEBAR_ROW_HEIGHT));
        out.put("addGroup", Rect.at(panel.x() + EDGE + each + SIDEBAR_ROW_GAP, top, each,
                SIDEBAR_ROW_HEIGHT));
        return out;
    }

    /**
     * The close button: a row-height square in the header, against the panel's right edge.
     */
    public Rect closeRect() {
        return Rect.at(panel.right() - HEADER_CONTROL_INSET - ROW_HEIGHT,
                panel.y() + (HEADER_HEIGHT - ROW_HEIGHT) / 2, ROW_HEIGHT, ROW_HEIGHT);
    }

    /**
     * Where the header's right-hand text has to stop, so the quest count does not run under Close.
     *
     * <p>One expression, used by the drawing and by nothing else — but it is here rather than in the
     * screen because that is the rule this class exists to enforce, and because the alternative is a
     * second {@code panelWidth() - 12} that agrees until somebody moves the button.
     *
     * <p>Measured from the leftmost player control -- Pinned now, Settings before it -- because the
     * author's controls sat in this row once and the count had to stop short of them. They left this
     * row long ago, and they are not in it now either -- the band
     * ({@link #authorBand}) is a strip of its own below the header, so the count and the title have the
     * whole of this row whatever a player may edit.
     */
    public int headerRightLimit() {
        return pinnedButton().x() - 10;
    }

    /**
     * The party button, in the header to the left of the rewards button.
     *
     * <h2>Why it moved here from the sidebar's foot</h2>
     *
     * <p>The column is a list of chapters and everything in it is about the questline; a party is not.
     * The header is where a screen puts the controls that act on the screen rather than on its content,
     * which is what Close is doing there -- and the request named the spot precisely: a small button top
     * right, left of the close, next to where a Claim All will go.
     *
     * <p>It also gives the sidebar its twenty-two pixels back. The strip and its gap were taken off the
     * chapter list; nothing needs that room now, so {@link #sidebarViewport} measures to the panel's
     * edge again.
     *
     * <p>Anchored right to left from the rewards button, itself anchored from Close, so the pair keeps
     * the order the request gave it and nothing moves when either changes width. A cluster laid out
     * forwards from an origin would move every control when one was added.
     */
    public Rect partyButton() {
        return Rect.at(rewardsButton().x() - ROW_GAP - PARTY_BUTTON_WIDTH, rewardsButton().y(),
                PARTY_BUTTON_WIDTH, ROW_HEIGHT);
    }

    /**
     * The settings button, in the header beside the party button: the way into the player's own text-size
     * card.
     *
     * <p>For every player, and that is the point of it existing: how large the text draws is about the
     * player's own body rather than the pack's look, so it is the one appearance row that cannot live
     * behind edit permission with the palette and the corner radius. Anchored right to left from the
     * party button, like every other member of the cluster.
     */
    public Rect settingsButton() {
        return Rect.at(partyButton().x() - ROW_GAP - SETTINGS_BUTTON_WIDTH, partyButton().y(),
                SETTINGS_BUTTON_WIDTH, ROW_HEIGHT);
    }

    /**
     * The pinned quests button, in the header beside the settings button: the way into the player's own
     * watchlist.
     *
     * <p>For every player, and left of Settings because it is used less often than the three beside it:
     * the cluster reads right to left in use order, and a fourth button must not push the other three.
     * Anchored from the settings button, like every other member of the cluster.
     */
    public Rect pinnedButton() {
        return Rect.at(settingsButton().x() - ROW_GAP - PINNED_BUTTON_WIDTH, settingsButton().y(),
                PINNED_BUTTON_WIDTH, ROW_HEIGHT);
    }

    /**
     * The rewards button, in the header between Close and Party: the way into the rewards panel.
     *
     * <p>The spot this class was already holding. {@link #partyButton}'s anchor was written right to
     * left from Close for one stated reason -- so that "a future Claim All goes to its left and pushes
     * nothing" -- and this is that control. It opens the panel rather than claiming outright: what the
     * server owes is a list, and the press that spends a reward should be a press on a row that names it.
     *
     * <p>Wider than Party's, because the word is.
     */
    public Rect rewardsButton() {
        return Rect.at(closeRect().x() - ROW_GAP - REWARDS_BUTTON_WIDTH, closeRect().y(),
                REWARDS_BUTTON_WIDTH, ROW_HEIGHT);
    }

    /**
     * The author's band: a strip of the panel's own chrome, under the title row and above the body.
     *
     * <h2>Why the author's controls left the canvas</h2>
     *
     * <p>They floated over the canvas's top-left corner for two rounds, as three pills, and the corner was
     * chosen deliberately -- the header belongs to the book and every player reads it, and a panel's rail
     * is anchored to the right, so the top-left was the one spot on the canvas nothing else occupied.
     * <b>Two things were wrong with it, and the second is what settled it.</b> A rail may cover the whole
     * canvas ({@link #panelRail}'s cap is the canvas less a gap), so a maximally wide panel drew over the
     * author's controls; and the canvas's minimum width had to carry their 214 pixels, which made the
     * graph's furniture the thing that decided how narrow the whole book could be.
     *
     * <p>Both go away with the band: the rails cannot reach above the canvas's top edge, and
     * {@link #MIN_CANVAS_WIDTH} drops to the view cluster alone. What it costs is 24 pixels of canvas and
     * sidebar height, for the operator only -- see {@link #MIN_PANEL_HEIGHT}, where the height is reserved
     * for every player so that the panel's minimum does not move with a permission.
     *
     * <h2>Four controls, in the author's reading order</h2>
     *
     * <p>Author, Assets, Edit and Advanced: the surfaces they work in first, then the mode, then the depth.
     * The order is the pills' own with the latch appended, and the reason for that order is unchanged --
     * {@code Author} opens the dock, {@code Assets} opens the pack's own files, {@code Edit} latches edit
     * mode and nothing else, and {@code Advanced} says how much of every editor menu to draw.
     */
    public Rect authorBand() {
        return Rect.at(panel.x(), header.bottom(), panel.width(),
                authorBand ? AUTHOR_BAND_HEIGHT : 0);
    }

    /**
     * The five controls of the author's band, left to right, or an empty map for a reader.
     *
     * <h2>Why an empty map rather than five rectangles nobody draws</h2>
     *
     * <p>Because a band is not a control that floats at a fixed offset: it is the panel's own strip, and a
     * reader's geometry has no such strip -- their canvas starts where the header ends. Five rectangles
     * computed anyway would sit across the header and the sidebar's first row, which is a lie the overlap
     * sweep would then check against the wrong surface. The pills were the other case, and the reason the
     * rule is stated rather than assumed: they floated over the canvas at offsets that meant something in
     * every geometry, so they were always in the map and merely not drawn.
     *
     * <p>This is the screen's one conditional about the band, and {@code BookGeometryTest} sweeps both
     * shapes -- a reader's and an author's -- so "the map holds five more keys" is asserted rather than
     * discovered.
     *
     * <h2>And why the buttons narrow rather than run off</h2>
     *
     * <p>A window can be smaller than {@link #MIN_PANEL_WIDTH} -- a full-bleed author's panel is the window
     * itself, minimums and all -- and five fixed widths would then put the last control outside the panel,
     * clickable where nothing draws. So the widths are shared when they do not fit, the way
     * {@link #sidebarToolbar} shares two buttons across the sidebar: at the minimum nothing narrows, and
     * below it the labels are what give way.
     */
    public Map<String, Rect> authorBandButtons() {
        Map<String, Rect> out = new LinkedHashMap<>();
        if (!authorBand) {
            return out;
        }
        int[] wanted = {AUTHOR_WIDTH, ASSETS_WIDTH, EDIT_WIDTH, ADVANCED_WIDTH, PREVIEW_WIDTH};
        int[] width = new int[wanted.length];
        int room = Math.max(0, panel.width() - EDGE * 2);
        int gaps = BAND_BUTTON_GAP * (wanted.length - 1);
        int used = gaps;
        for (int each : wanted) {
            used += each;
        }
        if (used <= room) {
            System.arraycopy(wanted, 0, width, 0, wanted.length);
        }
        else {
            int share = Math.max(0, (room - gaps) / wanted.length);
            java.util.Arrays.fill(width, share);
        }

        int x = panel.x() + EDGE;
        int y = header.bottom() + BAND_PAD;
        String[] keys = {"author", "assets", "edit", "advanced", "preview"};
        for (int i = 0; i < keys.length; i++) {
            out.put(keys[i], Rect.at(x, y, width[i], ROW_HEIGHT));
            x += width[i] + BAND_BUTTON_GAP;
        }
        return out;
    }

    // (`AUTHOR_PILL_BAND` and `authorRail()` stood here, and they are gone with the reason for them.)
    //
    // The inspector drawer used to be laid out from a canvas that started below the pills, because the
    // pills floated over the canvas's top-RIGHT corner -- exactly where the drawer docks -- so a rail
    // running to the canvas's top edge would have put its first row under a control. The drawer's corner is
    // empty now and it takes the same rail every docked panel takes (`panelRail`); what the corner keeps is
    // the view cluster, and the author's own controls have left the canvas altogether -- see
    // `authorBand()`.

    /**
     * The backing panel behind the three view buttons.
     *
     * <p>Three pixels larger than the buttons on every side, and it is what makes them read as one
     * cluster rather than three controls that happen to be stacked. Beside the chapter list
     * rather than in the canvas's corner, so no floating panel ever covers a map control: the
     * sweep holds the clearance because a cluster under a panel is a control that looks broken
     * rather than hidden.
     */
    public Rect viewControls() {
        return viewControlsFor(sidebar);
    }

    /**
     * The same backing for any list rectangle, settled or mid-wipe.
     *
     * <p>The screen's slide reads it rather than moving the field's answer by hand: the backing
     * and its buttons share one anchor, so the mat and its controls never disagree mid-slide.
     */
    public Rect viewControlsFor(Rect list) {
        return Rect.at(list.right() + EDGE - VIEW_MAT, bodyTop() + CHAPTER_GAP - VIEW_MAT + VIEW_DROP,
                VIEW_BUTTON + VIEW_MAT * 2, VIEW_COLUMN_HEIGHT + VIEW_MAT * 2);
    }

    /**
     * The expanded list's rectangle at the panel's left: what a collapse wipes from and an
     * expand wipes to, whatever the list's current state is.
     *
     * <p>The screen's slide reads it rather than the {@link #sidebar()} field because that field
     * is the <i>target</i> — the strip, once collapsed — while the wipe travels between the two.
     * Docked, this is the column; floating, the box; from a strip, the box a peek opens. One
     * expression, so the wipe and the press-absorb around it cannot disagree about where the
     * list is going.
     */
    public Rect sidebarExpandedRect() {
        if (!sidebarState.collapsed()) {
            return sidebar;
        }
        // A collapsed list always peeks as a floating box, pinned or not — so the wipe travels
        // between the strip and the box, never the docked column.
        return sidebarFloatRect(sidebarWidth, sidebarBody());
    }

    /**
     * The width available inside a list this wide, between its two edges. */
    private static int sidebarInner(int listWidth) {
        return listWidth - EDGE * 2;
    }

    // ------------------------------------------------------------------
    // The controls
    // ------------------------------------------------------------------

    /**
     * Every control on the book screen, by name.
     *
     * <p>This is the method the screen and the test share, and that sharing is the whole point. The
     * screen creates its widgets from these rectangles; the test asserts they do not overlap. A
     * layout change therefore cannot move a control without the test seeing it.
     *
     * <p>Note what is <b>not</b> here: nothing named for the footer that used to hold these, no control
     * at all whose only job is to close a panel that Escape already closes, and nothing for the summary
     * strip that used to sit across the bottom. Close is one square in the header, and the four former
     * footer controls are three map buttons and that square.
     *
     * <h2>The chapter rows used to be handed out from here, and they are not any more</h2>
     *
     * <p>This was {@code controls(int chapters)}, and it put {@code chapter0} … {@code chapterN} into the
     * map, capped at the number that fitted. The map now holds only the controls whose positions are
     * <b>fixed</b> — the header's close button and the three map buttons — and the argument that told it
     * how many chapters there are is gone with them.
     *
     * <p>The reason is worth stating precisely, because it is not "the rows moved elsewhere" but
     * "something else owns them now". A chapter row's position depends on the row's own index (its y),
     * on whether its group is collapsed (whether it is drawn at all), and on the scroll (how far it has
     * been moved) — and the last two of those are properties of a <i>view</i>, not of a layout. A map
     * built once from a size cannot express "row 5, currently scrolled out of view" without also
     * becoming the thing that decides it, which is what a {@code Stack}, a {@code Layout} and a
     * {@code ScrollView} already are.
     *
     * <p>So this method keeps the half of its job it can still do honestly — the fixed chrome — and
     * {@link #sidebarViewport()} says where the rows are allowed to go. What the overlap test sweeps
     * therefore shrinks to four controls, which is correct rather than a loss: those are the four that
     * can collide, and the rows cannot collide with each other because a Stack places them in a column
     * one after another.
     */
    public Map<String, Rect> controls() {
        Map<String, Rect> out = new LinkedHashMap<>();

        // Close, in the header's right corner.
        out.put("close", closeRect());

        // The rewards button, then the party strip: right to left, the order the header reads in. The
        // rewards button is in the map for every player, like Close and Party -- claiming is a player's
        // business rather than an author's, so it is not gated the way Edit and its gear are.
        out.put("rewards", rewardsButton());
        out.put("party", partyButton());

        // The settings button, for every player, left of the party button. In the map and drawn for
        // everybody, unlike the author's controls, which are not in this row at all any more: these are
        // the player's own settings.
        out.put("settings", settingsButton());

        // The pinned quests button, left of Settings: the player's watchlist beside their settings.
        out.put("pinned", pinnedButton());

        // The author's band, under the header. In the map for an author's geometry and absent from a
        // reader's, which is the one place this map's contents depend on something other than the window --
        // and it is a stated exception rather than an accident: the band is the panel's own strip, so a
        // reader's geometry has no such rectangle, and five offsets invented for one would be checked
        // against the wrong surface. See `authorBandButtons`. The map's source order still reads as the
        // chrome first and the canvas furniture last, where the band stands in the slot the pills held.
        out.putAll(authorBandButtons());

        // The sidebar's two add buttons, in a strip above the list. In the map for every player, drawn
        // only for an author -- the same convention as `edit` and `tools` above, and for the same
        // reason: the overlap sweep walks this map, and a control that appeared in it only sometimes
        // would be a control the sweep tests in one build and not the next.
        out.putAll(sidebarToolbar());

        // The floating box's pin zone is drawn chrome rather than a widget, so it is not in this
        // map: see `sidebarBoxPin` for the arrangement. Everything drawable here is a widget the
        // screen builds, which is what the overlap sweep holds.

        // The theme and motion controls are deliberately absent, and their absence is a decision worth
        // recording because the geometry for them existed and worked.
        //
        // They were the theme picker and the motion switch, anchored at the foot of the sidebar as two
        // full-width rows -- the only part of the whole overlay system that was not part of the design.
        // They are dev tools now: the same operations, reached from a mode rather than from a row sitting
        // permanently under the chapter list of every book. The picker belongs next to the editor, and
        // the motion switch belongs next to the accessibility settings it duplicates.
        //
        // Nothing here needs a replacement. `chapterRows()` measures to the panel's edge now, so the
        // space the rows occupied went back to the chapter list -- which is the honest use for it, since
        // it was always a compromise between a list of chapters and two controls that are not chapters.

        // The view cluster, beside the chapter list at the rows' height: docked, that is the
        // canvas's own corner, and floating, it is clear of the box. One rule for every state, so
        // the cluster never sits under the list and never needs a state of its own to avoid it.
        // Three pixels below the rows' top edge rather than level with it (see VIEW_DROP).
        int vx = sidebar.right() + EDGE;
        int vy = bodyTop() + CHAPTER_GAP + VIEW_DROP;
        out.put("zoomIn", Rect.at(vx, vy, VIEW_BUTTON, VIEW_BUTTON));
        out.put("zoomOut", Rect.at(vx, vy + VIEW_BUTTON + VIEW_GAP, VIEW_BUTTON, VIEW_BUTTON));
        out.put("centre", Rect.at(vx, vy + (VIEW_BUTTON + VIEW_GAP) * 2, VIEW_BUTTON, VIEW_BUTTON));

        return out;
    }

    // (`overlayControls(boolean)` stood here, and it was the quest footer's controls measured against the
    // centred card. The card is gone and the panel's rectangle is the caller's -- which is `questFooter`
    // below, the method this was a wrapper around.)

    /**
     * The quest editor's own bar, in the footer: from the card's inset to just short of Back.
     *
     * <h2>Why this is here rather than in the screen</h2>
     *
     * <p>Because the screen got it wrong in a way no test could see. The four controls in it — Delete,
     * Duplicate, Copy and the Settings button that opens a node's shape, size, placement and rules — were
     * placed from the <b>reader's Submit slot</b>, read out of {@code overlayControls(false)}: a map that
     * deliberately has no {@code "submit"} key, because asking for one moves Back up a row on a narrow
     * card, above the band the body reserves for it. So the key answered null and the whole bar was
     * skipped, which is the report "the editor is missing the button it used to have".
     *
     * <p>The bar is one expression, and it is the same expression the reader's footer is laid out from —
     * the card's inset, the row Back is in, and everything left of Back. Written here, the sweep in
     * {@code BookGeometryTest} holds it: a bar that ran into Back, left the card, or sat in another row
     * would fail there rather than in a playtest.
     *
     * @return the bar's rectangle, or null when the footer has no Back to place it against
     */
    /**
     * The quest editor's own bar, inside the surface the caller supplies.
     *
     * <h2>Why the surface is a parameter</h2>
     *
     * <p>Because the editor's bar spans from the surface's inset to just short of Back, and there is one
     * surface now: the rail the editor is drawn in. It took no argument while there were two presentations
     * — the no-argument form asked the centred card — and that form is gone with the card, which is the
     * same argument {@code questFooter} below makes for taking its rectangle from the caller: two ways to
     * ask "how wide is the panel I am in" is how the two answers come to disagree.
     */
    public Rect editorBar(Rect surface) {
        Rect back = questFooter(surface, false).get("back");
        if (back == null) {
            return null;
        }
        int left = surface.x() + MODAL_INSET;
        return Rect.at(left, back.y(), Math.max(0, back.x() - ROW_GAP * 2 - left), back.height());
    }

    /**
     * The quest overlay's footer, inside a card the caller supplies.
     *
     * <p>Submit on the left and Back on the right, and Back moves up a row when the two would
     * collide. Kept separate from {@link #partyControls} because the two cards hold different
     * controls -- see that method's note -- and this one takes its own width for Submit rather
     * than the short party actions'.
     */
    public Map<String, Rect> questFooter(Rect card, boolean hasSubmit) {
        Map<String, Rect> out = new LinkedHashMap<>();
        int height = OVERLAY_CONTROL_HEIGHT;
        // The same inset as the body and the footer, and the same on both sides. See MODAL_INSET: this
        // row used to be 8 from the bottom edge and 16 from the left, with Back a further half a
        // control's height in from the right for no reason anybody could state.
        int rowY = card.bottom() - MODAL_INSET - height;

        Rect back = Rect.at(card.right() - MODAL_INSET - BACK_WIDTH, rowY, BACK_WIDTH, height);

        if (hasSubmit) {
            Rect submit = Rect.at(card.x() + MODAL_INSET, rowY, SUBMIT_WIDTH, height);
            if (submit.intersects(back)) {
                back = Rect.at(back.x(), rowY - height - ROW_GAP, BACK_WIDTH, height);
            }
            out.put("submit", submit);
        }

        out.put("back", back);
        return out;
    }

    /**
     * The party panel's footer: Disband and Leave on the left, Done on the right.
     *
     * <h2>Why this is a separate shape rather than reusing the quest one</h2>
     *
     * <p>Because its controls are not a Submit/Back pair, and pretending they are would put a 130-pixel
     * button in a card whose real actions are "Leave" and "Disband". Reusing {@code overlayControls(boolean)}
     * would also mean asking it a question it cannot answer -- whether the viewer may leave is a fact about
     * a roster, not about a quest -- and the signatures say so.
     *
     * <h2>The weighting is the spec's, and it is deliberate</h2>
     *
     * <p><b>Disband</b> takes the far left, alone and red-bordered, because it is the only action here
     * that destroys something. <b>Leave</b> sits beside it as an ordinary control. <b>Done</b> is
     * right-aligned and worn as the primary, because leaving the panel is what most presses of this
     * footer mean — a footer whose dangerous action is the easiest to hit is a footer that will be hit.
     *
     * <p>The origin of each is a function of which controls exist rather than of how many: a lone owner
     * and an owner with members present put Disband in the same place, so the panel's one stable row
     * stays stable when a member joins. The old shape counted "actions" and put Leave first; this one
     * names them, because the two have different weights and a count cannot say so.
     *
     * @param hasDisband whether the viewer may dissolve the party. Owner only
     * @param hasLeave   whether the viewer may leave it. False for a party of one, where Disband is the
     *                   only honest word for the one exit
     * @param hasDone    whether to place Done at all -- a caller drawing a preview may not want it
     */
    public Map<String, Rect> partyControls(Rect card, boolean hasDisband, boolean hasLeave, boolean hasDone) {
        Map<String, Rect> out = new LinkedHashMap<>();
        int height = OVERLAY_CONTROL_HEIGHT;
        // The same equal inset as the quest footer and as the panel's own rows. See MODAL_INSET for the
        // four numbers this replaced.
        int rowY = card.bottom() - MODAL_INSET - height;

        if (hasDone) {
            out.put("done", Rect.at(card.right() - MODAL_INSET - BACK_WIDTH, rowY, BACK_WIDTH, height));
        }
        if (hasDisband) {
            out.put("disband", Rect.at(card.x() + MODAL_INSET, rowY, PARTY_ACTION_WIDTH, height));
        }
        if (hasLeave) {
            int x = hasDisband
                    ? card.x() + MODAL_INSET + PARTY_ACTION_WIDTH + ROW_GAP
                    : card.x() + MODAL_INSET;
            out.put("leave", Rect.at(x, rowY, PARTY_ACTION_WIDTH, height));
        }
        return out;
    }


    // ------------------------------------------------------------------
    // Pure arithmetic the screen also uses
    // ------------------------------------------------------------------

    // A `Zoom` record and a `zoomAbout` method used to live here, with four tests of their own in
    // BookGeometryTest. They are gone, and the reason is worth keeping because it is the rule this
    // whole round was about rather than tidiness.
    //
    // They were a second implementation of zoom-about-the-pointer. The first was in
    // QuestBookScreen itself, as three static fields and the same algebra written out twice more --
    // in the pan, in the centring. The transform is `ui.kit`'s `Viewport` now, which the screen
    // calls, and `ViewportTest.zoomAtKeepsTheContentPointUnderThePointer` asserts the same
    // invariant this copy's suite did -- swept over more pans and scales than it managed, and
    // asserted against the transform rather than against a re-derivation of it.
    //
    // So this copy had no callers. Nothing said so: it compiled, its tests passed, and a reader
    // would reasonably have taken it for the one the screen uses. That is the failure mode of a
    // duplicate — not that it is wrong, but that it is *plausible*, and it stays plausible after
    // the thing it duplicates has moved on.
    //
    // What stays here is the framing: rectangles, the control map, label room, hit-test bounds.
    // That is one screen's own layout and nothing else's. Content-to-screen mapping is not framing,
    // it is a viewport, and it lives in the kit where a scrolling list can use it too.

    /**
     * The horizontal room a label may take, given the columns of nodes that are on screen.
     *
     * <h2>The invariant that makes crowding survivable</h2>
     *
     * <p>The result is <b>strictly less than the narrowest gap between columns</b>, because a margin is
     * subtracted at each side. That one fact is what stops two labels touching: each is truncated to
     * the room, and every room is narrower than the distance between the nodes it labels.
     *
     * <p>So a questline authored 64 pixels apart carrying 90-pixel titles yields 56-pixel labels, not
     * overlapping ones. That is the fix for the reported symptom — three whole titles centred on nodes
     * 64 apart ran through each other and read as one corrupted string — and it is why the answer here
     * is 56 rather than 0.
     *
     * <h2>Two thresholds, and they are not the same one</h2>
     *
     * <p>This returns zero only when a gap is no wider than the two margins together — 8 pixels at the
     * usual margin of 4. Between that and a comfortable gap sits a band where the room is positive and
     * still too narrow to be worth drawing; there the caller skips the labels, on its own
     * {@link #MIN_LABEL_WIDTH} test. Conflating the two is an easy mistake and was made in this file's
     * own first test.
     *
     * <p>Fewer than two columns returns {@link #MAX_LABEL_WIDTH}: there is no neighbour to crowd. For
     * an empty list that is moot — the caller iterates no quests — so it is the same branch rather than
     * a special case with no consequences.
     */
    public static int labelRoom(List<Integer> columnXs, int labelGap, int maxWidth) {
        List<Integer> sorted = new ArrayList<>(columnXs);
        sorted.sort(Integer::compareTo);
        return labelRoomSorted(sorted, labelGap, maxWidth);
    }

    /**
     * The same answer without the copy and the sort, for a caller asking per label.
     *
     * <p>The label pass asks once per named node over the same columns; copying and sorting them
     * per node is one allocation and one {@code O(C log C)} per label for an answer that cannot
     * move between labels. Sort once at the call site and ask here.
     *
     * @param sortedColumnXs the columns, already sorted ascending; not checked, because checking
     *                       would be the walk this exists to avoid
     */
    public static int labelRoomSorted(List<Integer> sortedColumnXs, int labelGap, int maxWidth) {
        if (sortedColumnXs.size() < 2) {
            // One column, so no neighbour to crowd. The cap still applies.
            return maxWidth;
        }
        int gap = Integer.MAX_VALUE;
        for (int i = 1; i < sortedColumnXs.size(); i++) {
            gap = Math.min(gap, sortedColumnXs.get(i) - sortedColumnXs.get(i - 1));
        }
        return clamp(gap - labelGap * 2, 0, maxWidth);
    }

    /**
     * The room for the label on the node at {@code x}: its own neighbours' gap, not the
     * chapter's.
     *
     * <h2>Why this exists beside {@link #labelRoom}</h2>
     *
     * <p>Because the narrowest gap in the chapter is the wrong answer for every node but the two
     * that make it: a single crowded pair in an otherwise airy row used to decide every label in
     * it, so titles truncated to a gap they were nowhere near. Each label is centred on its own
     * node, so what crowds it is the nearest column either side — nothing further away can touch
     * it, and the overlap test already guards what is below.
     *
     * <p>The arithmetic is the same rule halved: a centred label reaches half its width each way,
     * so half the width must clear the nearer gap less the margin. Alone in its chapter — or
     * sharing its column — a node has no neighbour to crowd and gets the cap.
     */
    public static int labelRoomFor(List<Integer> columnXs, int x, int labelGap, int maxWidth) {
        List<Integer> sorted = new ArrayList<>(columnXs);
        sorted.sort(Integer::compareTo);
        return labelRoomForSorted(sorted, x, labelGap, maxWidth);
    }

    /**
     * The same answer without the copy and the sort, for a caller asking per label.
     *
     * <p>See {@link #labelRoomSorted}: the label pass sorts the columns once and asks per node.
     *
     * @param sortedColumnXs the columns, already sorted ascending; not checked
     */
    public static int labelRoomForSorted(List<Integer> sortedColumnXs, int x, int labelGap,
                                         int maxWidth) {
        int leftGap = Integer.MAX_VALUE;
        int rightGap = Integer.MAX_VALUE;
        for (int column : sortedColumnXs) {
            if (column < x) {
                // The last one below wins, which is the nearest: the list is sorted.
                leftGap = x - column;
            }
            else if (column > x) {
                rightGap = column - x;
                break;
            }
        }
        int near = Math.min(leftGap, rightGap);
        if (near == Integer.MAX_VALUE) {
            return maxWidth;
        }
        return clamp(2 * near - labelGap * 2, 0, maxWidth);
    }

    /**
     * Which of {@code rects} the pointer is in, or -1.
     *
     * <p>Backwards, so the last drawn — and therefore the topmost — wins. Two quests may legitimately
     * be at the same position, which the index reports as a warning, and picking the first would
     * select whichever happened to be authored earlier rather than the one you can see.
     */
    public static int hitTest(List<Rect> rects, double px, double py) {
        for (int i = rects.size() - 1; i >= 0; i--) {
            if (rects.get(i).contains(px, py)) {
                return i;
            }
        }
        return -1;
    }

    /** Whether two controls are clear of each other *and* of a margin, for a sanity check. */
    public static boolean clearOf(Rect a, Rect b, int margin) {
        return !a.intersects(Rect.at(b.x() - margin, b.y() - margin,
                b.width() + margin * 2, b.height() + margin * 2));
    }

    // ------------------------------------------------------------------
    // The editor's grid
    // ------------------------------------------------------------------

    /**
     * The grid a dragged node lands on, in content units: 8.
     *
     * <p>Positions in a quest file are authored numbers, and a node dropped at x=37.4182 carries a
     * number nobody typed into every diff it appears in afterwards. Eight is coarse enough that two
     * drags two pixels apart write the same file and fine enough that it never fights a layout the
     * author meant — and it divides the 16- and 32-pixel spacings the shipped chapters are laid out on.
     */
    public static final int SNAP_GRID = 8;

    /**
     * One coordinate snapped to the grid — or itself, when snapping is off.
     *
     * <p>A pure function on purpose: the drag state lives in the screen, and this answers "where does
     * this coordinate land" and nothing else. A negative grid, or a zero one, is a grid that cannot
     * divide anything, so it reads as off rather than as a division by zero; a coordinate that is
     * already on the grid stays put, which is what makes a re-snap during a held drag idempotent.
     *
     * <p>The Alt bypass is not here. It is a question about the keyboard, and this class has none —
     * the caller asks {@code Screen.hasAltDown()} and passes the answer in as {@code on}.
     */
    public static double snap(double value, double grid, boolean on) {
        if (!on || grid <= 0) {
            return value;
        }
        return Math.round(value / grid) * grid;
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }
}
