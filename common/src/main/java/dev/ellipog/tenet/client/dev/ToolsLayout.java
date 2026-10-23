package dev.ellipog.tenet.client.dev;

import dev.ellipog.armature.client.ui.CanvasBackground;
import dev.ellipog.armature.client.ui.ThemeToken;
import dev.ellipog.armature.client.ui.inspect.InspectLayout;
import dev.ellipog.armature.client.ui.kit.Insets;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Stack;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tenet.client.BookGeometry;
import dev.ellipog.tenet.client.PanelLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The tools panel's composition: a preview, the mode switches, the palette list, the shape and colour
 * sections, and the band that edits one colour.
 *
 * <h2>Docked beside the canvas, not a modal in the middle</h2>
 *
 * <p>It was a centred modal opened by a key of its own, and both halves of that were wrong for the job: a
 * theme editor's whole point is watching the thing you are changing, and a modal covers it. The panel
 * floats over the canvas's right-hand side, inside the book, so the graph a colour belongs to is on screen
 * while the colour changes.
 *
 * <p>It <b>overlays</b> the canvas rather than shrinking it, and that is the one place this departs from
 * "docked". Shrinking the canvas would re-clamp the pan and the zoom under the author's hands every time
 * the panel opened or closed — the thing they were looking at would move — and the panel is a tool for a
 * few minutes at a time. The cost is that nodes under it are hidden while it is open, which closing it
 * restores.
 *
 * <h2>The two things that make it a tool rather than a word list</h2>
 *
 * <p>Each answers a complaint from the first playtest, and each is a <i>rect</i> here rather than a
 * drawing: the panel is game-free and testable, and the drawing is {@code ToolsPanel}'s.
 *
 * <ul>
 *   <li><b>Sections that fold</b>: the palette list and the colours are headings you can collapse,
 *       because forty-three rows and sixteen palettes in one column is a wall.</li>
 *   <li><b>Controls on the rows</b>: a number is dragged or typed where it is named, a colour is a chip
 *       that opens the picker, and the two actions that write the player's own theme sit at the foot of
 *       the book tab.</li>
 * </ul>
 */
public final class ToolsLayout {

    // ------------------------------------------------------------------
    // Metrics
    // ------------------------------------------------------------------

    /**
     * Between the panel and the canvas' edges, and between the panel's own bands.
     *
     * <p>The same number as {@code BookGeometry.PANEL_GAP}, and the same reason: the dock takes the rail
     * every column takes, so its gap is that rail's gap. It is a separate constant because it also spaces
     * this panel's own bands, which the rail knows nothing about.
     */
    public static final int GAP = 6;

    /** The tab strip: the Book panel and the Chapter panel, side by side above the list. */
    public static final int TAB_HEIGHT = 16;
    /** One row of a list: a theme, a colour, or a switch. */
    public static final int ROW_HEIGHT = 16;
    public static final int ROW_GAP = 1;
    public static final int HEADING_HEIGHT = 13;
    public static final int SECTION_GAP = 7;

    /** A switch row is taller: it has a control in it rather than being one. */
    public static final int SWITCH_HEIGHT = 20;

    /** The actions row's height: Revert and Save, side by side at the book tab's foot. */
    public static final int ACTION_ROW = 20;

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    /**
     * Which panel the dock is showing.
     *
     * <p>An enum rather than a boolean, because "which tab" is a name with a label and a reason, and a
     * boolean would have the call sites reading {@code true} as one of them until someone decides which.
     *
     * <p>Each tab is about one target and nothing else. <b>Quest Book</b> is the book's own look: the
     * switches, the palette, the colours, the radius and the canvas — all the player's. <b>Chapter</b> is
     * the open chapter: its content and, above it, its own appearance, the same controls writing the
     * chapter's {@code theme} and {@code themePatch}. The target follows from the tab; there is no
     * switch that moves one tab's edits onto the other's file.
     */
    public enum Tab {
        BOOK("tenet.dev.tab.book"),
        CHAPTER("tenet.dev.tab.chapter");

        private final String label;

        Tab(String label) {
            this.label = label;
        }

        /** The key the tab strip shows. See {@code Labels.of} for where it is resolved. */
        public String label() {
            return label;
        }
    }

    /** The switches. Their labels are the state; the button is the change. */
    public static final String MOTION = "motion";
    public static final String SNAP = "snap";

    /**
     * Whether the sidebar's chapter rows draw a completion bar.
     *
     * <p>Beside the other general switches rather than among the theme's rows: it is a reading
     * preference about the book, not a colour, and it persists in the client's own file the way Snap
     * does.
     */
    public static final String PROGRESS = "progress";

    /**
     * The two switch values, as keys.
     *
     * <p>The player-facing screen's own pair, not a second copy: the chapter panel's toggles already draw
     * these two words, and a "On"/"Off" of the editor's own would be the same word translated twice --
     * which is how one of them ends up stale.
     */
    public static final String ON = "tenet.screen.on";
    public static final String OFF = "tenet.screen.off";

    /**
     * The book's identity: the pack's name and icon for the book itself, from {@code index.json}.
     *
     * <p>Not appearance: these write the pack's own file rather than any theme, which is why they sit
     * in their own fold at the top of the book tab and say "the pack's book" rather than "your look".
     */
    public static final String BOOK_SECTION = "section:book";
    public static final String BOOK_TITLE = "book:title";
    public static final String BOOK_ICON = "book:icon";
    public static final String BOOK_LOD = "book:lod";
    public static final String BOOK_STARTER = "book:starter";

    /**
     * The chapter tab's appearance fold: everything under it writes the chapter's own theme patch.
     *
     * <p>A fold rather than a second mode: the chapter tab already carries the chapter's content, and
     * the appearance sections are the same ones the book tab shows — see
     * {@link #appearanceRows}.
     */
    public static final String APPEARANCE_SECTION = "section:appearance";

    /**
     * The shape section: one row, the corner radius, and it is selected through the band like a colour.
     */
    public static final String SHAPE_SECTION = "section:shape";

    /**
     * The heading over Motion, Snap and Progress: how this client edits, rather than anything the pack
     * holds. Without it the three sat under the Book section's last field and read as the book's.
     */
    public static final String PREFS_SECTION = "section:prefs";

    /**
     * The radius's row key.
     *
     * <p>It was briefly selectable -- a click put the radius in the band, which then showed one line of
     * steppers. That was a second step for one number, and the report said so: the arrows belong in the
     * row, beside the number they move.
     */
    public static final String RADIUS = "shape:radius";

    /**
     * The canvas surface's controls: the pattern, whatever that pattern's own numbers are, and the two
     * rows that act on the lot.
     *
     * <p>Their own section under Shape, and steppers for the same reason the radius is one: the
     * arrows belong in the row, beside the value they move. The space row is the exception -- two
     * values, so the whole row toggles and no arrows are drawn.
     *
     * <p>The section folds like the others, and it is the longest one when an image is in it: pattern,
     * texture, fit, tile, opacity, the live strip and the copy are a wall on a panel that already
     * carries the palette and the colours. See {@link #canvasRows} for which rows a given background
     * gets.
     */
    public static final String CANVAS_SECTION = "section:canvas";
    public static final String CANVAS_PATTERN = "canvas:pattern";
    public static final String CANVAS_SPACING = "canvas:spacing";
    public static final String CANVAS_SPACE = "canvas:space";
    public static final String CANVAS_SIZE = "canvas:size";
    public static final String CANVAS_DENSITY = "canvas:density";
    public static final String CANVAS_DIRECTION = "canvas:direction";
    public static final String CANVAS_TILE = "canvas:tile";
    public static final String CANVAS_FIT = "canvas:fit";
    public static final String CANVAS_TEXTURE = "canvas:texture";
    public static final String CANVAS_OPACITY = "canvas:opacity";
    public static final String CANVAS_COPY = "canvas:copy";

    /**
     * The palette list: the one place a theme is chosen, and it is the author's.
     *
     * <p>A list of this kind sat here once and was removed — <i>"a theme is a set of colours, and the
     * colours are the section"</i> — and it is back because the reason it was redundant is gone: a row
     * carries swatches now, so the list shows what each palette <b>looks like</b> rather than repeating
     * bare names. It is also the only selector there is: choosing a theme can undo a pack's whole planned
     * look, which is the pack author's decision and not a player's, so it lives behind the edit
     * permission with the rest of this panel and the player-facing card does not carry it.
     */
    public static final String PALETTE_SECTION = "section:palette";
    public static final String COLOUR_SECTION = "section:colours";

    /**
     * The chapter tab's four sections, in the order the file reads: what the chapter is, its rules, its
     * group, and the quests it lists.
     *
     * <h2>Why the panel owns these keys rather than the chapter's own layout</h2>
     *
     * <p>Because a section key is a fact about <b>this panel</b> -- what folds, in which band, drawn with
     * which rule -- and the chapter's rows are this panel's rows now. The chapter's layout keeps the keys
     * that name a <i>field</i> (its own {@code title}, {@code aliases}, the group's prefixed ones), because
     * those are the paths a commit goes to; these four name nothing in the file, only a fold.
     */
    public static final String CHAPTER_IDENTITY = "h:identity";
    public static final String CHAPTER_RULES = "h:rules";
    public static final String CHAPTER_GROUP = "h:group";
    public static final String CHAPTER_QUESTS = "h:quests";

    /**
     * Whether a heading is one that folds — the predicate both the panel and the screen must read.
     *
     * <h2>Why this is a predicate and not a comment</h2>
     *
     * <p>"Pressable heading" was decided twice: the panel drew a {@code ▼} marker from the section's
     * open flag, and the screen gave a heading a widget from a hand-written list of three keys. The two
     * disagreed — the Canvas heading was drawn promising a fold and had no widget behind it, so no press
     * could fold it, and the Quest Book heading was the same. One predicate, used by the drawing and by
     * the widget pass and asserted to cover every section, is what makes the next heading unable to
     * repeat it.
     *
     * <p><b>Nine keys now, and the four chapter ones are the same rule one tab over.</b> A heading folds
     * when it is a section, whichever file the section is about; the panel draws the rule for all nine and
     * the screen gives all nine a widget, which is what {@code ToolsLayoutTest} asserts by name.
     */
    public static boolean folds(String key) {
        if (key != null && key.contains(".#")) {
            // One canvas element's own group headers: `element.<id>.#position` and its like. They are
            // built per element rather than listed here because the id is part of the key, and a table
            // of every element's sections would be a second list of elements to keep in step. The `#`
            // marks a fold rather than a field, which is the same convention `ElementPanelLayout` builds
            // them by — so this predicate and that builder cannot disagree about which keys are sections.
            return true;
        }
        return APPEARANCE_SECTION.equals(key) || BOOK_SECTION.equals(key)
                || CANVAS_SECTION.equals(key) || COLOUR_SECTION.equals(key)
                || PALETTE_SECTION.equals(key)
                || CHAPTER_IDENTITY.equals(key) || CHAPTER_RULES.equals(key)
                || CHAPTER_GROUP.equals(key) || CHAPTER_QUESTS.equals(key);
    }

    /**
     * The help a row offers on hover, or null for a row that says it itself.
     *
     * <h2>Why a table and not a field on the row</h2>
     *
     * <p>The same shape {@code QuestSettingsPanel.HELP} uses, and for the same reason: a row is
     * key, label and kind, and help is a property of the key. A table keeps it out of the record — the
     * rows are built in a dozen places and none of them should have to remember a sentence — and makes
     * "every control has help" a thing a test can assert rather than a thing a reader hopes.
     *
     * <p>Colour rows and group names are deliberately absent: a token's row is a swatch that shows the
     * colour it edits and the preview rings the part it paints, so a sentence would be a third
     * description of the same thing. Palette rows share one, because the list is one decision.
     */
    private static final Map<String, String> HELP = Map.ofEntries(
            Map.entry(MOTION, "tenet.dev.tools.help.motion"),
            Map.entry(SNAP, "tenet.dev.tools.help.snap"),
            Map.entry(PROGRESS, "tenet.dev.tools.help.progress"),
            Map.entry(PALETTE_SECTION, "tenet.dev.tools.help.palette"),
            Map.entry(SHAPE_SECTION, "tenet.dev.tools.help.shape"),
            Map.entry(PREFS_SECTION, "tenet.dev.tools.help.prefs"),
            Map.entry(RADIUS, "tenet.dev.tools.help.radius"),
            Map.entry(CANVAS_SECTION, "tenet.dev.tools.help.canvas"),
            Map.entry(CANVAS_PATTERN, "tenet.dev.tools.help.canvas_pattern"),
            Map.entry(CANVAS_TEXTURE, "tenet.dev.tools.help.canvas_texture"),
            Map.entry(CANVAS_FIT, "tenet.dev.tools.help.canvas_fit"),
            Map.entry(CANVAS_TILE, "tenet.dev.tools.help.canvas_tile"),
            Map.entry(CANVAS_SIZE, "tenet.dev.tools.help.canvas_size"),
            Map.entry(CANVAS_DENSITY, "tenet.dev.tools.help.canvas_density"),
            Map.entry(CANVAS_DIRECTION, "tenet.dev.tools.help.canvas_direction"),
            Map.entry(CANVAS_SPACING, "tenet.dev.tools.help.canvas_spacing"),
            Map.entry(CANVAS_SPACE, "tenet.dev.tools.help.canvas_space"),
            Map.entry(CANVAS_OPACITY, "tenet.dev.tools.help.canvas_opacity"),
            Map.entry(CANVAS_COPY, "tenet.dev.tools.help.canvas_copy"),
            Map.entry(COLOUR_SECTION, "tenet.dev.tools.help.colours"),
            Map.entry(BOOK_SECTION, "tenet.dev.tools.help.book"),
            Map.entry(BOOK_TITLE, "tenet.dev.tools.help.book_title"),
            Map.entry(BOOK_ICON, "tenet.dev.tools.help.book_icon"),
            Map.entry(BOOK_LOD, "tenet.dev.tools.help.book_lod"),
            Map.entry(BOOK_STARTER, "tenet.dev.tools.help.book_starter"),
            Map.entry(APPEARANCE_SECTION, "tenet.dev.tools.help.appearance"));

    /** The help a row key offers, or null. Palette rows share one; colour rows have none. */
    public static String help(String key) {
        if (key == null) {
            return null;
        }
        if (paletteId(key) != null) {
            return "tenet.dev.tools.help.palette_row";
        }
        if (tokenKey("canvasPattern").equals(key)) {
            return "tenet.dev.tools.help.canvas_ink";
        }
        return HELP.get(key);
    }

    /**
     * A row under a point: its key, and the help it offers.
     *
     * <p>The key travels with the help because the caller needs both — the help to draw and the row to
     * hang it on — and finding the row twice is how the two would come to disagree.
     */
    public record Hovered(String key, String help) {
    }

    /**
     * Which row is under a point, or null.
     *
     * <p>Through {@link #onScreen}, the same mapping the drawing uses, so the row a tooltip describes is
     * the row the pointer is over and not the one at the same offset in a list that has scrolled.
     */
    public static Hovered helpAt(List<Action> rows, Layout layout, Viewport view,
                                 int mouseX, int mouseY) {
        return helpAt(rows, layout, view, mouseX, mouseY, ToolsLayout::help);
    }

    /**
     * The same, with the sentences supplied by the caller.
     *
     * <h2>Why there are two help tables</h2>
     *
     * <p>Because the chapter tab's rows are a file's rows, and the sentences that explain a file's fields
     * live with the rows that name them ({@code ChapterPanelLayout.help}). Copying them into this class's
     * table would put every chapter sentence in a second place, and leave the class that owns them unable to
     * change one on its own. So the table is a parameter, and the one-table caller passes nothing.
     */
    public static Hovered helpAt(List<Action> rows, Layout layout, Viewport view,
                                 int mouseX, int mouseY,
                                 java.util.function.Function<String, String> help) {
        Objects.requireNonNull(help, "help");
        if (rows == null || layout == null || view == null) {
            return null;
        }
        for (Action row : rows) {
            Slot slot = layout.slot(row.key());
            if (slot == null) {
                continue;
            }
            String sentence = help.apply(row.key());
            if (sentence != null && onScreen(view, slot).contains(mouseX, mouseY)) {
                return new Hovered(row.key(), sentence);
            }
        }
        return null;
    }

    /** A colour's row key. */
    public static String tokenKey(String tokenId) {
        return "token:" + tokenId;
    }

    /** The colour a row key names, or null for a key that is not one. */
    public static String tokenId(String key) {
        return key != null && key.startsWith("token:") ? key.substring("token:".length()) : null;
    }

    /** A palette row's key. */
    public static String paletteKey(String paletteId) {
        return "palette:" + paletteId;
    }

    /** The palette a row key names, or null for a key that is not one. */
    public static String paletteId(String key) {
        return key != null && key.startsWith("palette:") ? key.substring("palette:".length()) : null;
    }

    /**
     * One palette a picker offers: the id that names it, and the label a row shows.
     *
     * <p>A pair rather than a bare name because the two differ — {@code high_contrast} is shown as "High
     * Contrast" — and because a record keeps this class game-free: the screen builds the list from
     * {@code Themes.everything()}, and a test builds one by hand.
     */
    public record Palette(String id, String label) {

        public Palette {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
        }
    }

    private ToolsLayout() {
    }

    // ------------------------------------------------------------------
    // The panel and its bands
    // ------------------------------------------------------------------

    /**
     * The drawer, and the fixed bands inside it.
     *
     * @param panel    the whole floating column
     * @param tabs     the tab strip: the Book panel and the Chapter panel
     * @param list     where the scrolling sections live
     * @param actions  Revert and Save (book tab only: a chapter's edits are the chapter file's own)
     */
    public record Frame(BookGeometry.Rect panel, BookGeometry.Rect tabs, BookGeometry.Rect list,
                        BookGeometry.Rect actions) {
    }

    /** The frame for the book tab. */
    public static Frame frame(BookGeometry.Rect rail) {
        return frame(rail, Tab.BOOK);
    }

    /**
     * The frame, from the rail the dock occupies, shaped by the tab it is for.
     *
     * <h2>The rail is the caller's, and it is the same rail a docked panel gets</h2>
     *
     * <p>{@code rail} is a {@code BookGeometry.panelRail(...)} -- the canvas's right edge, one
     * {@code PANEL_GAP} in, as tall as the canvas -- which is the rectangle every docked column is anchored
     * by. This class used to compute its own panel inside a rail that started below the author's pills,
     * which was true while the pills floated over this corner: the drawer and a column shared one column of
     * the arrangement but not one edge, so swapping between them moved the panel's top by the pill band.
     * The pills are in the canvas's other corner now, so the drawer takes the column's rail and the two
     * line up to the pixel.
     *
     * <h2>Four bands, and the list is what is left</h2>
     *
     * <p>Every band is placed from the panel's own rectangle, top to bottom, and the list takes the
     * remainder — one subtraction, so a band added here cannot overlap it. It used to be nine: the sample
     * card, the colour band with its swatch and channel steppers, and the chapter's identity ribbon all
     * left for the redesign, and what replaced them is not only "fewer rows" but "the controls live on the
     * rows". The one asymmetry left is the actions row, which is the book tab's alone: Revert and Save
     * write the player's own theme file, and a chapter's edits are the chapter file's own, undone with
     * the editor's undo.
     */
    public static Frame frame(BookGeometry.Rect rail, Tab tab) {
        // **The shared frame, with the one thing this dock decides for itself: whether it has a footer.**
        // Revert and Save are the Book tab's, and a chapter's edits are committed as they are made, so the
        // Chapter tab reserves a band for nothing -- which is why the height is an argument rather than the
        // card's constant. See `PanelLayout.frame`, which owns the three bands for every column now.
        PanelLayout.Frame bands = PanelLayout.frame(rail, false,
                tab == Tab.BOOK ? BookGeometry.MODAL_FOOTER_HEIGHT : 0);

        // The tab strip, **centred in the header**: that band is this panel's title band, and the strip is
        // what names it. Pinned to the top it read as a card's header with its title missing -- the band's
        // own height is the card's, because a panel that renamed it would be a panel with a second frame.
        BookGeometry.Rect header = bands.header();
        int tabsTop = header.y() + Math.max(0, (header.height() - TAB_HEIGHT) / 2);
        BookGeometry.Rect tabs = BookGeometry.Rect.at(header.x() + BookGeometry.MODAL_INSET, tabsTop,
                Math.max(0, header.width() - BookGeometry.MODAL_INSET * 2),
                Math.min(TAB_HEIGHT, header.height()));

        return new Frame(bands.rail(), tabs, bands.body(), bands.footer());
    }

    // (`empty` and `take` stood here: the two helpers the dock's own band arithmetic walked top to bottom,
    // clamping each band into the panel. They are gone with it -- `PanelLayout.frame` owns that walk for every
    // column now, and leaves this class the one thing it still decides, which is whether it has a footer at
    // all. A second arithmetic beside the shared one is what this round existed to delete.)


    // ------------------------------------------------------------------
    // The rows
    // ------------------------------------------------------------------

    /**
     * The tab strip's two buttons, side by side in the band above the status line.
     *
     * <p>Equal halves with the panel's own gap between them, because the two tabs are peers -- neither is
     * "the panel" and the other is "the other thing" -- and the strip is the band's whole width, so a
     * wider panel widens both tabs. It is the arrangement the drawer had before the redesign's short-lived
     * title menu, restored because a dropdown that hides one of two options is a worse control than the
     * two options themselves.
     */
    public static BookGeometry.Rect tabBook(BookGeometry.Rect tabs) {
        return BookGeometry.Rect.at(tabs.x(), tabs.y(),
                Math.max(0, (tabs.width() - GAP) / 2), tabs.height());
    }

    /** The Chapter half, in what the Book half leaves. */
    public static BookGeometry.Rect tabChapter(BookGeometry.Rect tabs) {
        BookGeometry.Rect book = tabBook(tabs);
        return BookGeometry.Rect.at(book.right() + GAP, tabs.y(),
                Math.max(0, tabs.right() - book.right() - GAP), tabs.height());
    }

    /**
     * One row of the list.
     *
     * <h2>The kinds, and what each one says about where the control is</h2>
     *
     * <p>A {@code switch} is a label with a small button in a strip at its right; a {@code row} is itself
     * the control, which is what sixteen themes and forty-three colours want, because a strip button per row
     * would be sixteen buttons all saying the same word; a {@code heading} is drawn, and in this panel it is
     * also pressable -- it folds its section.
     *
     * <p>The four numbered kinds are the redesign's: a {@code field} is a label and a value you can drag
     * or type ({@code ScrubField}); a {@code pair} is two of those sharing one line, with the second half
     * in {@link #right()} -- a field or a choice, whichever the pair needs; a {@code choice} is a label and
     * a word chosen from a list, opened by the triangle in its box ({@code ChoiceField}); and a {@code chip}
     * is a colour as an inline swatch whose press opens the picker. None of them is pressed by being
     * selected, which is what the old {@code ROW} meant for colours -- the redesign's whole point is that
     * the control is at the row rather than in a footer under it.
     *
     * <p><b>And a {@code value} is the one kind with no control at all</b>: a label and a fact the panel
     * itself draws, which is what the chapter tab needs for a file's description and for the quests the
     * chapter lists. It arrived with that tab, and it is the kind that makes "every row is a control" a
     * describable exception rather than an assumption: a reader of the dispatch below can see that one kind
     * is drawn and never pressed.
     */
    public record Action(String key, String label, String buttonLabel, Kind kind, Action right,
                         String value) {

        public enum Kind {
            /** A label with a button in the strip at its right. */
            SWITCH,
            /** The whole row is the control. */
            ROW,
            /** A section's name, which folds it. */
            HEADING,
            /** A label and a scrubbable number. */
            FIELD,
            /** Two halves on one line: this row, and {@link Action#right()}. */
            PAIR,
            /** A label and a value chosen from a short list. */
            CHOICE,
            /** A label and an inline colour chip. */
            CHIP,
            /**
             * A label and a text box: the chapter tab's fields, and the book's name.
             *
             * <p>The book's two text rows were built by key inside the screen's own loop, because a field
             * in this panel used to mean a <i>number</i> ({@code FIELD}, a {@code ScrubField}). A word is
             * not a number, so a word's row is a kind of its own -- and the chapter tab, which is four
             * text fields, is what made that worth saying rather than special-casing twice more.
             */
            TEXT,
            /**
             * A label and a button that opens something: the chapter's item pickers, which show the item
             * they name and open a card when pressed.
             *
             * <p>Distinct from {@link #SWITCH}, whose button states a condition and changes it in place,
             * and from {@link #ROW}, which <i>is</i> the control and so has no label of its own.
             */
            BUTTON,
            /**
             * A label and a value the panel draws, with no widget.
             *
             * <p>Nothing is placed for it, so a press it answers is one the <i>screen</i> hit-tests from
             * the row's own rectangle -- which the chapter tab's quest rows do, because their press is a
             * reorder drag. A widget there would take the press first and leave the drag nothing to grab,
             * which is why "pressable but widgetless" is this kind rather than {@link #ROW}.
             */
            VALUE
        }

        public static Action toggle(String key, String label, String buttonLabel) {
            return new Action(key, label, buttonLabel, Kind.SWITCH, null, null);
        }

        public static Action row(String key, String label) {
            return new Action(key, label, null, Kind.ROW, null, null);
        }

        public static Action heading(String key, String label) {
            return new Action(key, label, null, Kind.HEADING, null, null);
        }

        /** A numeric row: its label, and a field across the row's own width. */
        public static Action field(String key, String label) {
            return new Action(key, label, null, Kind.FIELD, null, null);
        }

        /**
         * A text row: its label, and the word the field is holding.
         *
         * <p>The value rides here for the same reason a read-only row's does -- it is a fact about a file,
         * and the alternative is every text field's key in a second table inside the screen.
         */
        public static Action text(String key, String label, String value) {
            return new Action(key, label, null, Kind.TEXT, null, value == null ? "" : value);
        }

        /** A row whose button opens something, labelled with what it currently names. */
        public static Action button(String key, String label, String value) {
            return new Action(key, label, null, Kind.BUTTON, null, value == null ? "" : value);
        }

        /**
         * A read-only row: its label, and the fact the panel draws beside it.
         *
         * <p>The value rides in the record rather than being looked up from the key, because the two rows
         * that want it are facts about a file -- a description's text, a quest's position and id -- and a
         * table of those would be a second copy of the file.
         */
        public static Action value(String key, String label, String value) {
            return new Action(key, label, null, Kind.VALUE, null, value == null ? "" : value);
        }

        /**
         * Two halves on one line, left first.
         *
         * <p>The left half carries the pair's own key, because a stack row is found by one key and the
         * widget placed there is one widget (see {@code ScrubPairField}); the second half is reached
         * through {@link #right()}. Both halves are ordinary single-row actions, so a pair of a field and
         * a choice is written from the same two factories a full-width row would use.
         */
        public static Action pair(Action left, Action right) {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            return new Action(left.key(), left.label(), null, Kind.PAIR, right, null);
        }

        /** A label and a value chosen from a list. The screen supplies the options. */
        public static Action choice(String key, String label) {
            return new Action(key, label, null, Kind.CHOICE, null, null);
        }

        /**
         * The same, with the value the file holds for it.
         *
         * <p>A choice's current value is normally the screen's to find ({@code choiceLabel}), because the
         * value is in the draft the screen is editing. The chapter tab's choices are the exception that
         * proves the shape: their vocabulary is the <i>file's</i> -- nine axes with their own names and
         * their own unset state -- so the layout that reads the file states the word, and the screen only
         * has to name it.
         */
        public static Action choice(String key, String label, String value) {
            return new Action(key, label, null, Kind.CHOICE, null, value == null ? "" : value);
        }

        /** A label and an inline colour chip. */
        public static Action chip(String key, String label) {
            return new Action(key, label, null, Kind.CHIP, null, null);
        }

        /**
         * The same, carrying the colour it draws.
         *
         * <p>A chip's colour is a theme token by default -- that is what the theme editor's swatches are -- but
         * an element's fill is a colour in a <i>file</i>, and the control an author already knows for a colour
         * is this one. The value is that colour in the same spelling the file holds, so the chip and the field
         * cannot disagree about it.
         */
        public static Action chip(String key, String label, String value) {
            return new Action(key, label, null, Kind.CHIP, null, value);
        }

        public boolean hasButton() {
            return kind == Kind.SWITCH && buttonLabel != null;
        }

        public boolean isControl() {
            return kind == Kind.ROW;
        }

        public boolean isHeading() {
            return kind == Kind.HEADING;
        }

        /** Whether the row is edited through a widget rather than by being pressed. */
        public boolean isField() {
            return kind == Kind.FIELD || kind == Kind.PAIR;
        }

        public boolean isChoice() {
            return kind == Kind.CHOICE;
        }

        public boolean isChip() {
            return kind == Kind.CHIP;
        }
    }

    /** How much of a field row's width its label keeps. */
    public static final int LABEL_ROOM = 44;

    /**
     * A colour chip's box: swatch, hex and alpha, against the row's right edge.
     *
     * <p>Here rather than in the drawing because the press that opens the picker is tested with it too: a
     * chip drawn in one place and hit-tested from another is the pair of descriptions this class exists to
     * prevent. The width is what the three texts need; on a narrow row the hex gives up its room rather
     * than the chip giving up its right edge, so a column of chips stays a column.
     */
    public static BookGeometry.Rect chip(Slot row) {
        Objects.requireNonNull(row, "row");
        int width = Math.min(132, Math.max(0, row.width() - LABEL_ROOM));
        return BookGeometry.Rect.at(row.right() - width, row.y(), width, row.height());
    }

    /**
     * The same chip, in the composition the row was built in.
     *
     * <h2>Why a chip needs to know about stacking at all</h2>
     *
     * <p>Because a stacked row is <b>two bands</b> -- a label's and its control's -- and {@link #chip} was
     * written for the one-band composition it started in. Handed the whole stacked row it drew a chip three
     * times the height of every other control in the list, with the label centred down it: the report was
     * *"colour thing is way too tall"*, and the arithmetic to blame was this rectangle being asked for the
     * row rather than for the band the chip belongs in.
     *
     * <p>It returns a rectangle rather than a slot because that is what {@link #chip} answers, and it is
     * x/y-relative, so the same call serves a row in content coordinates and the same row on screen. The
     * drawing and the press both ask <b>here</b>, which is the whole reason this class owns the derivation:
     * a chip painted on one band and hit-tested on another is the fault it exists to prevent.
     *
     * @param row  the row's own slot, in whichever space the caller is working in
     * @param mode how the list composed its rows. See {@link #stack(List, InspectLayout.Mode)}
     */
    public static BookGeometry.Rect chipOf(Slot row, InspectLayout.Mode mode) {
        return chip(controlBandOf(row, mode));
    }

    /**
     * Where a row's own control goes, in the composition that row was built in.
     *
     * <h2>One answer, for every control a stacked row moves</h2>
     *
     * <p>Side by side a row <i>is</i> its control's band -- the label keeps {@link #LABEL_ROOM} of the same
     * line -- so the row's own slot is the answer. Stacked, the label has a band of its own and the control
     * sits in the band under it, which is what {@link InspectLayout#controlBand} hands out. That is the same
     * call the widget placement makes, so a control the screen places and a control the panel draws cannot
     * land in different bands -- the fault the chip's own note records.
     *
     * <h2>Who asks</h2>
     *
     * <p>The chip, for the rectangle it paints in; and the chooser, for the field a menu opens under: a menu
     * anchored to the <i>row</i> rather than to the band would hang below a stacked field's label and over
     * the control under it.
     *
     * @param row  the row's own slot, in whichever space the caller is working in
     * @param mode how the list composed its rows
     */
    public static Slot controlBandOf(Slot row, InspectLayout.Mode mode) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(mode, "mode");
        return mode == InspectLayout.Mode.STACKED ? InspectLayout.controlBand(row) : row;
    }

    /** Between the two halves of a pair row, so their boxes do not touch. */
    public static final int PAIR_GAP = 6;

    /** The left half of a pair row. See {@link #pairRight}. */
    public static Slot pairLeft(Slot row) {
        Objects.requireNonNull(row, "row");
        int half = Math.max(0, (row.width() - PAIR_GAP) / 2);
        return new Slot(row.key(), row.x(), row.y(), half, row.height());
    }

    /** The right half, under the second action's key. The split the pair widget itself uses. */
    public static Slot pairRight(Slot row, Object key) {
        Slot left = pairLeft(row);
        int x = left.right() + PAIR_GAP;
        return new Slot(key, x, row.y(), Math.max(0, row.right() - x), row.height());
    }

    /**
     * Where one of a list's controls is, by the key it answers to.
     *
     * <h2>Why this is not {@code Layout.slot}</h2>
     *
     * <p>Because a pair row is <b>one</b> slot with two controls in it, and the layout only knows the
     * pair's own key — the left half's. The drawing has never had a problem with that: {@code ToolsPanel}
     * asks for the row and then splits it with {@link #pairLeft}/{@link #pairRight}, which is the same
     * arithmetic {@code ScrubPairField} places its two halves with. The press path was the half that
     * looked the row up by the <i>control's</i> key, found nothing, and returned before opening
     * anything — which is why the canvas's space chooser, the second half of the opacity row, could not
     * be switched from Graph to Screen at all.
     *
     * <p>So the split lives here, once, for both readers, and a key that names no control answers null
     * rather than a rectangle borrowed from its neighbour.
     *
     * @param layout the list the tab is showing, already placed
     * @param rows   the same list of actions that layout was built from
     * @param key    the control's key: a row's own, or a pair's second half
     */
    public static Slot controlSlot(Layout layout, List<Action> rows, String key) {
        if (layout == null || rows == null || key == null) {
            return null;
        }
        for (Action row : rows) {
            Slot slot = layout.slot(row.key());
            if (slot != null && key.equals(row.key())) {
                return slot;
            }
            if (slot != null && row.right() != null && key.equals(row.right().key())) {
                return pairRight(slot, key);
            }
        }
        return null;
    }

    /**
     * The book's own rows: the fold's heading, then the pack's name and icon while it is open.
     *
     * <p>Their own entry point rather than part of {@link #appearanceRows}: the appearance sections
     * write a theme, and these write the pack's {@code index.json} — the one place this panel edits a
     * file that is not the player's own look. Kept together here so the tab that shows them has one
     * list to build from, and so a reader of this class can see the whole of what a book row is.
     */
    public static List<Action> bookRows(boolean open) {
        return bookRows(open, false, false);
    }

    /**
     * The same, with the book's own LOD answer as the switch shows it: the pending edit
     * first, then the tree's — see the screen's book-value reader for why the row cannot
     * read the tree alone.
     */
    public static List<Action> bookRows(boolean open, boolean lodDisabled) {
        return bookRows(open, lodDisabled, false);
    }

    /**
     * The same, with the first-join book answer beside the LOD one: the pending edit
     * first, then the tree's, for the same reason.
     */
    public static List<Action> bookRows(boolean open, boolean lodDisabled, boolean starterBook) {
        List<Action> rows = new ArrayList<>();
        rows.add(Action.heading(BOOK_SECTION,
                (open ? "\u25bc " : "\u203a ") + "tenet.dev.tools.book"));
        if (open) {
            rows.add(Action.row(BOOK_TITLE, "tenet.dev.tools.book_title"));
            rows.add(Action.row(BOOK_ICON, "tenet.dev.tools.book_icon"));
            rows.add(Action.toggle(BOOK_LOD, "tenet.dev.tools.book_lod",
                    lodDisabled ? ON : OFF));
            rows.add(Action.toggle(BOOK_STARTER, "tenet.dev.tools.book_starter",
                    starterBook ? ON : OFF));
        }
        return List.copyOf(rows);
    }

    /**
     * Where a field's own control goes: the row's right-hand side, after the label's room.
     *
     * <p>The counterpart of {@link #strip} for a row that is a text field rather than a button: the
     * label is drawn by the panel in the room this keeps, and the field is placed in what is left.
     */
    public static Slot valueField(Slot row, int labelRoom) {
        Objects.requireNonNull(row, "row");
        int room = Math.min(labelRoom, row.width());
        return new Slot(row.key(), row.x() + room, row.y(), Math.max(0, row.width() - room), row.height());
    }

    // ------------------------------------------------------------------
    // The texture row's three controls
    // ------------------------------------------------------------------

    /**
     * The texture row is a field and two boxes: the file's own picture, the id, and a browse button.
     *
     * <h2>Why the row carries three controls where the book's field carries one</h2>
     *
     * <p>The book's name and icon are typed or picked with one control each, because one of them is a
     * name and the other a thing you can see in the button. A texture is both at once -- a long id that
     * is only readable when typed or pasted, and a picture that is only judgeable when seen -- so the
     * field keeps its place for the paste and the boxes carry what the id cannot say. The three are
     * derived here together, so the drawing and the hit test cannot disagree about which is where.
     */
    public static final int TEXTURE_THUMB = 14;

    /** The browse button's width, matching a stepper's arrows so the row's right edge stays a column. */
    public static final int TEXTURE_BROWSE = 16;

    /** The picture box, between the label and the field. */
    public static Slot textureThumb(Slot row) {
        Objects.requireNonNull(row, "row");
        int size = Math.min(TEXTURE_THUMB, Math.max(0, row.height()));
        return new Slot("thumb", row.x() + LABEL_ROOM, row.y() + (row.height() - size) / 2,
                size, size);
    }

    /** The browse button, against the row's right edge. */
    public static Slot textureBrowse(Slot row) {
        Objects.requireNonNull(row, "row");
        int right = row.right() - STRIP_INSET;
        int left = Math.max(row.x(), right - TEXTURE_BROWSE);
        return new Slot("browse", left, row.y(), Math.max(0, right - left), row.height());
    }

    /** The id's field, between the picture and the browse button. */
    public static Slot textureField(Slot row) {
        Slot thumb = textureThumb(row);
        Slot browse = textureBrowse(row);
        int x = thumb.right() + 3;
        return new Slot(row.key(), x, row.y(), Math.max(0, browse.x() - 3 - x), row.height());
    }

    /** Whether a press at a point is on the texture row's browse button. */
    public static boolean textureBrowseAt(Viewport view, Slot row, double mouseX, double mouseY) {
        return row != null && textureBrowse(onScreen(view, row)).contains((int) mouseX, (int) mouseY);
    }

    /**
     * The book tab's list: the three switches, then the appearance sections.
     *
     * @param motionOn    what the Motion switch says
     * @param snapOn      what the Snap switch says
     * @param progressOn  what the Progress switch says
     * @param coloursOpen whether the colour section is unfolded
     */
    public static List<Action> rows(boolean motionOn, boolean snapOn, boolean progressOn,
                                    boolean coloursOpen) {
        return rows(motionOn, snapOn, progressOn, coloursOpen, List.of(), true,
                CanvasBackground.NONE, true, null);
    }

    /**
     * The same, with the palette list where it belongs.
     *
     * <p>The list sits after the mode switches and before Shape: the switches are about how the panel
     * works, and the palette is the first decision about the content the sections below edit. A caller
     * with no themes to offer passes an empty list and gets no section at all, rather than a heading
     * over nothing.
     *
     * @param palette     the palettes a picker offers, in catalogue order
     * @param paletteOpen whether the palette section is unfolded
     */
    public static List<Action> rows(boolean motionOn, boolean snapOn, boolean progressOn,
                                    boolean coloursOpen, List<Palette> palette, boolean paletteOpen) {
        return rows(motionOn, snapOn, progressOn, coloursOpen, palette, paletteOpen,
                CanvasBackground.NONE, true, null);
    }

    /**
     * The same, with the panel's actual target behind the appearance rows.
     *
     * <p>The background and the copy label are the screen's to supply because they are the target's:
     * a chapter and the player's own look can carry different patterns, and the copy row's label says
     * whether it is waiting for a confirmation. See {@link #canvasRows}.
     */
    public static List<Action> rows(boolean motionOn, boolean snapOn, boolean progressOn,
                                    boolean coloursOpen, List<Palette> palette, boolean paletteOpen,
                                    CanvasBackground background, boolean canvasOpen, String copyLabel) {
        List<Action> rows = new ArrayList<>();
        rows.add(Action.heading(PREFS_SECTION, "tenet.dev.tools.prefs"));
        rows.add(Action.toggle(MOTION, "tenet.dev.tools.motion", motionOn ? ON : OFF));
        rows.add(Action.toggle(SNAP, "tenet.dev.tools.snap", snapOn ? ON : OFF));
        // The book's own switch, beside the rest: a reading preference like motion rather than an edit.
        rows.add(Action.toggle(PROGRESS, "tenet.dev.tools.progress", progressOn ? ON : OFF));
        // **The appearance block is Advanced, and this is the whole of the cut for this tab.** What is left
        // in Normal mode is the three switches above, which are about how this client behaves rather than
        // what the pack looks like -- and the appearance sections are four decisions about a look: the
        // palette, the corner radius, the canvas's surface and every colour token under it.
        //
        // One `if` around one call, rather than a key per section: the appearance rows are built by
        // `appearanceRows` and arrive here as one list, so the section boundary is this call. The Colours
        // section holds headings of its own (one per token group), which is the case a filter over heading
        // keys could not handle -- see `Advanced`.
        if (Advanced.on()) {
            rows.addAll(appearanceRows(palette, paletteOpen, coloursOpen, background, canvasOpen, copyLabel));
        }
        return List.copyOf(rows);
    }

    /**
     * The appearance sections, shared by both tabs.
     *
     * <p>Palette, Shape, Canvas and Colours, in the order they have always been: what look this is,
     * the one knob that is not a colour, the surface, and then the colours themselves. Both tabs show
     * exactly this; the difference between them is which theme the values are read from and written to,
     * which the screen decides, not the layout.
     */
    public static List<Action> appearanceRows(List<Palette> palette, boolean paletteOpen,
                                              boolean coloursOpen, CanvasBackground background,
                                              boolean canvasOpen, String copyLabel) {
        Objects.requireNonNull(palette, "palette");
        List<Action> rows = new ArrayList<>();

        if (!palette.isEmpty()) {
            rows.add(Action.heading(PALETTE_SECTION,
                    (paletteOpen ? "\u25bc " : "\u203a ") + "tenet.dev.tools.palette"));
            if (paletteOpen) {
                for (Palette option : palette) {
                    rows.add(Action.row(paletteKey(option.id()), option.label()));
                }
            }
        }

        // Shape before colours: the one knob that is not a colour, then the palette.
        rows.add(Action.heading(SHAPE_SECTION, "tenet.dev.tools.shape"));
        // A field row: the number is dragged or typed at the row, rather than stepped by arrows and
        // selected for a band. See `ScrubField`.
        rows.add(Action.field(RADIUS, "tenet.dev.tools.radius"));

        // The canvas's surface: what is drawn over the canvas colour. Under Shape because it is the
        // same kind of decision -- the look of the surface rather than one of its colours -- and the
        // colours section below can then stay a list of swatches.
        rows.addAll(canvasRows(background, canvasOpen, copyLabel));

        // The marker is a filled triangle and a single angle: the disclosure pair this font carries. The
        // empty triangles it started as (`\u25be`/`\u25b8`) are not in it and drew as boxes -- see
        // `BookGeometry.TOOLS_PILL_WIDTH` for how the set of available glyphs was measured.
        rows.add(Action.heading(COLOUR_SECTION,
                (coloursOpen ? "\u25bc " : "\u203a ") + "tenet.dev.tools.colours"));
        if (coloursOpen) {
            ThemeToken.Group last = null;
            for (ThemeToken token : ThemeToken.ALL) {
                if (token.id().equals("canvasPattern")
                        && background.kind() != CanvasBackground.Kind.IMAGE) {
                    // The canvas section's Ink row is this token's row whenever the canvas draws in
                    // ink; listing it again here would be one token with two rows -- and a layout
                    // cannot hold two rows under one key. An image uses the token only for the tint's
                    // alpha, which the Opacity row owns, so the colours list keeps the row for it --
                    // every token has exactly one row either way.
                    continue;
                }
                if (token.group() != last) {
                    // A heading, not a row: a group's name is drawn and does nothing, and a pressable row
                    // that selects nothing is worse than plain text.
                    rows.add(Action.heading("group:" + token.group().name(), token.group().label()));
                    last = token.group();
                }
                rows.add(Action.chip(tokenKey(token.id()), token.label()));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * The canvas section's rows: the pattern, whatever numbers that pattern's own kind carries, the
     * anchoring and ink every flat pattern shares, and the two rows that act on the lot -- the live
     * strip and the copy.
     *
     * <h2>One table, and it is exact</h2>
     *
     * <p>Which rows a background gets is a property of the background, not of the drawing: a dot grid
     * has no direction to flip, an image has no spacing to size, a covered image has no tile to size
     * and no anchor (it is one screen-fixed blit), while a tiled image pairs opacity with the anchor
     * like the flat patterns. The table is written once, here, so the panel that draws the rows and the screen that
     * presses them cannot disagree about which exist -- a row shown but not handled is a control that
     * lies, and one handled but not shown is a control nobody can reach.
     *
     * <p>The strip and the copy are always there, whatever the kind: the first is how the pattern is
     * judged and the second is how the chapters are made to match, and neither is about a number the
     * kind above it owns. The ink is a colour row, not a new key -- it is {@code canvasPattern}, and
     * the selection machinery already knows what to do with one.
     *
     * @param background the background in force for the panel's target
     * @param canvasOpen whether the section is unfolded; folded, it is the heading and nothing else
     * @param copyLabel  the label the copy row carries -- the caller passes the armed variant while
     *     the copy is armed, and null for the plain one
     */
    public static List<Action> canvasRows(CanvasBackground background, boolean canvasOpen,
                                          String copyLabel) {
        Objects.requireNonNull(background, "background");
        List<Action> rows = new ArrayList<>();
        rows.add(Action.heading(CANVAS_SECTION,
                (canvasOpen ? "\u25bc " : "\u203a ") + "tenet.dev.tools.canvas_section"));
        if (!canvasOpen) {
            return List.copyOf(rows);
        }

        rows.add(Action.choice(CANVAS_PATTERN, "tenet.dev.tools.canvas_pattern"));

        CanvasBackground.Kind kind = background.kind();
        if (kind == CanvasBackground.Kind.IMAGE) {
            // An image is the one kind whose look is a file: what to draw, how it meets the rectangle,
            // and -- only when it repeats -- the width one repeat is drawn at. A tile honours the
            // anchor like any other repeating pattern, so it pairs opacity with the space chooser;
            // a cover is one screen-fixed blit, so it keeps the full-row opacity and no anchor.
            rows.add(Action.row(CANVAS_TEXTURE, "tenet.dev.canvas.texture"));
            rows.add(Action.choice(CANVAS_FIT, "tenet.dev.canvas.fit"));
            if (background.image().fit() == CanvasBackground.Fit.TILE) {
                rows.add(Action.field(CANVAS_TILE, "tenet.dev.canvas.tile"));
            }
            // No ink row: an image's strength is the file's own alpha, and opacity scales that alpha.
            // The opacity takes the full row only when there is no anchor to pair it with.
            if (background.image().fit() == CanvasBackground.Fit.TILE) {
                rows.add(Action.pair(Action.field(CANVAS_OPACITY, "tenet.dev.canvas.opacity"),
                        Action.choice(CANVAS_SPACE, "tenet.dev.tools.canvas_space")));
            }
            else {
                rows.add(Action.field(CANVAS_OPACITY, "tenet.dev.canvas.opacity"));
            }
        }
        else {
            // The four procedural kinds share a mark size; the hatch has a direction and the speckle a
            // density, and `none` has nothing to size because it draws nothing. The anchoring and the
            // ink are the procedural half's own.
            if (kind == CanvasBackground.Kind.HATCH) {
                rows.add(Action.choice(CANVAS_DIRECTION, "tenet.dev.canvas.direction"));
            }
            if (kind == CanvasBackground.Kind.SPECKLE) {
                rows.add(Action.field(CANVAS_DENSITY, "tenet.dev.canvas.density"));
            }

            // The compact scalar lines the redesign asks for: one pair each. A half takes the whole row
            // when its partner is absent -- a size with nothing to space, an opacity with no anchor --
            // and a `none` background keeps spacing and opacity, which still apply to whatever pattern
            // is chosen next.
            Action size = kind == CanvasBackground.Kind.NONE
                    ? null : Action.field(CANVAS_SIZE, "tenet.dev.canvas.size");
            Action spacing = Action.field(CANVAS_SPACING, "tenet.dev.tools.canvas_spacing");
            rows.add(size == null ? spacing : Action.pair(size, spacing));

            rows.add(Action.pair(Action.field(CANVAS_OPACITY, "tenet.dev.canvas.opacity"),
                    Action.choice(CANVAS_SPACE, "tenet.dev.tools.canvas_space")));
            rows.add(Action.chip(tokenKey("canvasPattern"), "tenet.dev.canvas.ink"));
        }

        // The live strip is gone: the canvas itself is the preview now, and it follows the drag before
        // anything is written -- see the screen's preview draft. A second, smaller sample beside it was
        // a picture of the thing the author was already looking at.
        rows.add(Action.row(CANVAS_COPY, copyLabel == null || copyLabel.isEmpty()
                ? "tenet.dev.canvas.copy" : copyLabel));
        return List.copyOf(rows);
    }

    /**
     * The chapter tab's appearance part: the fold's heading, then the sections while it is open.
     *
     * <p>The heading is the whole part's fold, so an author who came for the chapter's content can put
     * the appearance away with one press -- and the sections under it are the same list the book tab
     * uses, so the two tabs cannot drift apart.
     */
    public static List<Action> chapterAppearanceRows(List<Palette> palette, boolean appearanceOpen,
                                                     boolean paletteOpen, boolean coloursOpen,
                                                     CanvasBackground background, boolean canvasOpen,
                                                     String copyLabel) {
        Objects.requireNonNull(palette, "palette");
        List<Action> rows = new ArrayList<>();
        rows.add(Action.heading(APPEARANCE_SECTION,
                (appearanceOpen ? "\u25bc " : "\u203a ") + "tenet.dev.tools.appearance"));
        if (appearanceOpen) {
            rows.addAll(appearanceRows(palette, paletteOpen, coloursOpen, background, canvasOpen,
                    copyLabel));
        }
        return List.copyOf(rows);
    }

    /** The list as an unbuilt stack. See {@code DevLayout.stack} for why the gaps go before each row. */
    public static Stack stack(List<Action> rows) {
        return stack(rows, InspectLayout.Mode.SIDE_BY_SIDE);
    }

    /**
     * The same stack, composed the way the tab asked for it.
     *
     * <h2>Why the mode reaches this far down</h2>
     *
     * <p>Because the two tabs of one panel are two widths of problem. The book tab's rows are switches,
     * chips and numbers at the dock's own width; the chapter tab's are a file's labelled fields, and its
     * longest label is longer than any strip this panel reserves -- which is why {@code ChapterPanelLayout}
     * shortened its words <i>and then</i> stacked them, in that order, and why the stacking is what has to
     * survive the move onto these rows. It is the kit's own idea and the kit's own numbers
     * ({@link InspectLayout.Mode}), so this reads that vocabulary rather than inventing a second one: a
     * stacked row gets the label's band and the control's band beneath it, which is what
     * {@link InspectLayout#labelBand} and {@link InspectLayout#controlBand} hand out to the drawing and to
     * whoever places the widget, so the two cannot disagree about where the line between them is.
     */
    public static Stack stack(List<Action> rows, InspectLayout.Mode mode) {
        Objects.requireNonNull(rows, "rows");
        boolean stacked = mode == InspectLayout.Mode.STACKED;

        Stack stack = Stack.stack();
        for (int i = 0; i < rows.size(); i++) {
            Action row = rows.get(i);
            if (i > 0) {
                // The stacked composition breathes more between its rows, because a stacked row is two
                // bands and the label above the next control would otherwise read as part of the control
                // above it. Both numbers are the inspector's, which is where the composition came from.
                stack.gap(row.isHeading()
                        ? (stacked ? InspectLayout.STACKED_SECTION_GAP : SECTION_GAP)
                        : (stacked ? InspectLayout.STACKED_ROW_GAP : ROW_GAP));
            }
            switch (row.kind()) {
                case HEADING -> stack.row(row.key(), HEADING_HEIGHT);
                case SWITCH -> {
                    if (stacked) {
                        // The button spans the control band instead of sitting in a strip: there is no
                        // strip in this composition, and a switch that kept one would be a 40-pixel button
                        // under a label written for the whole width.
                        stack.row(row.key(), InspectLayout.STACKED_ROW_HEIGHT);
                    }
                    else {
                        stack.row(row.key(), SWITCH_HEIGHT, stripRoom());
                    }
                }
                // Every other kind that carries a control *beside* its label: one row tall side by side, or
                // the label's band and the control's band beneath it when stacked. A pair is one widget in
                // either composition -- see `pairLeft`, which splits the row it is given.
                case FIELD, PAIR, CHOICE, CHIP, TEXT, BUTTON -> stack.row(row.key(),
                        stacked ? InspectLayout.STACKED_ROW_HEIGHT : ROW_HEIGHT);
                // And the two kinds that are one line in both compositions, for the same reason: a row that
                // *is* the control draws its own label, so there is no second band for it to stack into --
                // a label drawn above a widget that prints the same name is the duplicate-string fault
                // `drawRow`'s note records -- and a read-only row has no control at all.
                case ROW, VALUE -> stack.row(row.key(), ROW_HEIGHT);
            }
        }
        return stack;
    }

    public static Layout build(List<Action> rows, int width, Measure measure) {
        return build(rows, width, measure, InspectLayout.Mode.SIDE_BY_SIDE);
    }

    /** The same, composed the way the tab asked for it. See {@link #stack(List, InspectLayout.Mode)}. */
    public static Layout build(List<Action> rows, int width, Measure measure, InspectLayout.Mode mode) {
        Objects.requireNonNull(measure, "measure");
        return stack(rows, mode).build(Math.max(0, width), measure);
    }

    /** How wide a switch's button is, and its inset from the row's right edge. */
    public static final int STRIP_WIDTH = 40;
    public static final int STRIP_INSET = 2;

    /**
     * How wide a switch is, from {@code ArmatureSwitch}: a compile-time constant, so this stays game-free.
     *
     * <p>The same figure {@code PartyPanelLayout.SWITCH_WIDTH} states and for the same reason: the toolkit's
     * switch has a width of its own (<b>22</b>, narrower than a button on purpose -- "the state is the shape,
     * not a word"), and a row that placed it in a strip sized for a 40-pixel button stretched it. That is
     * what *"switches look horrible"* was: one panel-wide track with a knob in the corner.
     */
    public static final int SWITCH_WIDTH = 22;

    /**
     * Where a switch goes: its own width, against the column's right edge, in either composition.
     *
     * <h2>What this is for, and what it replaces</h2>
     *
     * <p>A switch is a state you read before you read its label ({@code ArmatureSwitch}'s own argument for
     * existing over a button), so it belongs at the end of its row, in the column the rest of the controls
     * share -- the shape {@code PartyPanelLayout.controlSlots} already gives the party's two settings. The
     * flag rows drew On/Off <i>buttons</i> instead, and the word is where that went wrong twice: a row whose
     * state was read from the wrong field said "Off" for ever, and a state that is a word has to be looked up
     * rather than seen.
     *
     * <p>Side by side the room is the gap the row's insets reserved ({@link #strip}, which fills it for a
     * 40-pixel button); stacked it is the control's own band. Either way the switch is the room's right end,
     * so a column of switches shares one right edge with every other control in the same list.
     *
     * @param row  the row's own slot, in whichever space the caller is working in
     * @param mode how the list composed its rows. See {@link #stack(List, InspectLayout.Mode)}
     */
    public static Slot switchSlot(Slot row, InspectLayout.Mode mode) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(mode, "mode");
        // The room the row reserved for a control of its own: the gap beside a side-by-side row, the band
        // under a stacked label. Then the switch takes that room's right end, so a column of switches shares
        // one right edge with a column of strip-width buttons without either knowing about the other.
        Slot home = mode == InspectLayout.Mode.STACKED ? InspectLayout.controlBand(row) : strip(row);
        int width = Math.min(SWITCH_WIDTH, Math.max(0, home.width()));
        return new Slot(row.key(), home.right() - width, home.y(), width, home.height());
    }

    private static Insets stripRoom() {
        return new Insets(0, 0, STRIP_WIDTH + STRIP_INSET * 2, 0);
    }

    /**
     * Where a switch's button goes: in the gap its row reserved, against the column's edge.
     *
     * <p>The same placement {@code DevLayout.strip} uses, and deliberately not the one
     * {@code PartyRoster.removeSlot} uses -- a {@code STRETCH} row is narrowed by its insets, so the room
     * is <i>outside</i> the slot.
     */
    public static Slot strip(Slot row) {
        Objects.requireNonNull(row, "row");
        int width = Math.min(STRIP_WIDTH, Math.max(0, row.width()));
        return new Slot(row.key(), row.right() + STRIP_INSET, row.y(), width, row.height());
    }

    /**
     * A slot where it will be drawn: the list's own coordinates put through the viewport it is drawn in.
     *
     * <p>The one mapping -- and now literally one, since the property inspector arrived with the same
     * need and the method moved to {@code InspectLayout}: a row's slot is in the list's space, its y a
     * distance down the content, not down the screen, and a caller that forgets places its controls
     * outside the panel entirely. The fault it exists to end was real here first: the radius stepper's
     * arrows were built at the list's own coordinates and landed outside the panel, and the report was
     * *"border radius stepper thing wasnt there"*.
     */
    public static Slot onScreen(Viewport view, Slot slot) {
        return dev.ellipog.armature.client.ui.inspect.InspectLayout.onScreen(view, slot);
    }

    /** Revert, in the footer band: the panels' own insets, and an ordinary control's height. */
    public static BookGeometry.Rect revert(BookGeometry.Rect footer) {
        return BookGeometry.Rect.at(footer.x() + BookGeometry.MODAL_INSET,
                footer.y() + BookGeometry.MODAL_FOOTER_GAP,
                Math.max(0, (footer.width() - BookGeometry.MODAL_INSET * 2 - GAP) / 2),
                Math.min(BookGeometry.OVERLAY_CONTROL_HEIGHT, footer.height()));
    }

    public static BookGeometry.Rect save(BookGeometry.Rect footer) {
        BookGeometry.Rect revert = revert(footer);
        return BookGeometry.Rect.at(revert.right() + GAP, revert.y(),
                Math.max(0, footer.right() - BookGeometry.MODAL_INSET - revert.right() - GAP),
                revert.height());
    }
}
