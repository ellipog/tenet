package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.client.BookGeometry;

import java.util.List;

/**
 * Where the Assets panel's pieces are: the title, the tab strip, the page, the footer.
 *
 * <h2>Why this is a class rather than the screen's own sums</h2>
 *
 * <p>For the reason {@link TableEditorLayout} and {@link ChapterPanelLayout} are: every question worth
 * being wrong about here is arithmetic on integers — which tab a press is on, where a list's rows
 * start, how tall a page's content is, which row a scroll position shows — and arithmetic on integers is
 * what this project's tests can hold without a client. The screen draws what this says and asks it what a
 * press means, so the rectangle a row is <i>drawn</i> at and the rectangle it is <i>hit-tested</i> at are
 * one derivation. That rule is written down because its absence is this panel's whole family of bugs.
 *
 * <h2>The shape</h2>
 *
 * <pre>
 *   +------------------------------------------+
 *   | Assets                                   |   title
 *   +------------------------------------------+
 *   | Tables   | Quests   | Templates           |   tabs
 *   +------------------------------------------+
 *   | Items                                    |   page
 *   | Glowstone Dust x4                        |
 *   | ...                                      |#
 *   +------------------------------------------+
 *   |                          New table  Done |   footer
 *   +------------------------------------------+
 * </pre>
 *
 * <p>The tabs are a strip rather than a column because the column taxed every page for the sake of
 * two words: seventy-six pixels plus the gap, off every row's width, so a row's own detail kept
 * meeting its controls. A strip costs one line of height and gives the page the whole width back.
 * The tabs divide the strip equally among the sections this depth shows — two halves in Normal,
 * three thirds at full depth — so a tab never moves when the list under it does. The page's
 * scrollbar is inside the page's right edge, which is the one strip the rows may not be drawn in.
 */
public final class AssetsLayout {

    /**
     * The panel's own chrome, above everything: what this panel is.
     *
     * <p>The card chrome's own number rather than one of mine. I wrote twenty here, then read the chrome
     * this has to line up with: {@code drawModalCardChrome} fills a strip of {@code CARD_HEADER} and draws
     * the title in its middle, so a different number would have put this panel's content a few pixels into
     * the strip it is not allowed to use — the sort of fault that reads as "the first row is slightly
     * wrong" and gets blamed on the row.
     */
    public static final int TITLE_HEIGHT = TableEditorLayout.CARD_HEADER;

    /** The air between the card's edge and everything inside it: the card's own inset, for its reason. */
    public static final int PAD = BookGeometry.MODAL_INSET;

    /** The band the footer's buttons sit in: the card's own footer, where the chrome draws its rule. */
    public static final int FOOTER_HEIGHT = BookGeometry.MODAL_FOOTER_HEIGHT;

    /** One clickable tab in the strip: the same sixteen pixels a section row always was. */
    public static final int TAB_HEIGHT = 16;

    /** The air between the tab strip and the page, so the tabs read as controls above a list. */
    public static final int TAB_PAGE_GAP = 4;

    /** One row of a page: an entry, in both pages. */
    public static final int ROW_HEIGHT = 18;

    /**
     * A row's icon, where it has one — a table's or a quest's own.
     *
     * <p>The size {@link TableEditorLayout#ICON} already uses for a row's icon, so the two panels' rows
     * read as the same list. It fits inside {@link #ROW_HEIGHT} with a pixel either side, which is why
     * adding icons moved no other number.
     */
    public static final int ICON = 16;

    /** Where a row's text starts when it has an icon: the icon, its gap, and the row's own inset. */
    public static final int ICON_TEXT_INSET = ICON + 4;

    /** One row control's width — the × or the pencil. */
    public static final int CHIP = 13;

    /**
     * The copy chip's width: a word rather than a glyph. At {@link #CHIP} the word overflowed its box
     * into the gap and the cross, and the row read "dice Copy×".
     */
    public static final int WORD_CHIP = 28;

    /** The air between the row's two controls, so "Copy" and "×" read as two things. */
    public static final int CHIP_GAP = 4;

    /**
     * What a row's two controls take at its right end, and therefore what its detail must leave free.
     *
     * <p>Here rather than in the drawing because it is a number two places need: the chips are placed from it
     * and the detail is shifted left by it. The first version had the chips at the row's right edge and the
     * detail right-aligned under them, which drew "Copy" over "dice" — one number, two owners, and the fault
     * that follows from that every time.
     */
    public static final int CHIPS = WORD_CHIP + CHIP_GAP + CHIP;

    /**
     * The air between a row's detail and those controls.
     *
     * <p>The first version had four pixels of it, which at a row's own scale read as the id and the word
     * "Copy" being one string — the screenshot said "diceCopy×". The detail is reference and the controls are
     * controls; six pixels plus the chip gap is what says so.
     */
    public static final int DETAIL_GAP = 6;

    /** A page's own group heading — a chapter on the Quests page, "not loaded" on the Tables page. */
    public static final int HEADING_HEIGHT = 14;

    /** The scrollbar's strip, inside the page's right edge. Rows are never drawn in it. */
    public static final int SCROLLBAR = 5;

    /** The panel's sections, in the order they are listed. */
    public enum Section {
        /** The pack's reward tables, and the files that did not load. */
        TABLES,
        /** Every quest file, grouped by chapter. */
        QUESTS,
        /** The component template library: shipped presets and saved setups. */
        TEMPLATES
    }

    /**
     * The sections this depth shows, in the order they are listed.
     *
     * <h2>Why the drawing and the hit test both have to come through here</h2>
     *
     * <p>Because {@link #sectionAt} answers a section by <b>index into this list</b>
     * ({@code frame.tab(i)} is where tab {@code i} is drawn), so a drawn list and a pressed list that
     * disagreed would put every press on the tab left of the one under the pointer -- and the failure
     * reads as a mouse that selects the wrong page rather than as a list that was filtered twice.
     *
     * <p>Normal mode hides the tables, and nothing else; the quest files are the half of this panel every
     * author uses. See {@code Advanced} for the cut and why it is stated there rather than here.
     */
    public static Section[] shown() {
        Section[] all = Section.values();
        int count = 0;
        for (Section section : all) {
            if (Advanced.showsSection(section.name())) {
                count++;
            }
        }
        if (count == all.length) {
            return all;
        }
        Section[] out = new Section[count];
        int at = 0;
        for (Section section : all) {
            if (Advanced.showsSection(section.name())) {
                out[at++] = section;
            }
        }
        return out;
    }

    /**
     * The same section, or the first one this depth shows.
     *
     * <p>For the two places the panel's section arrives from somewhere else -- the file it remembers, and
     * the command that opens the editor on a table -- neither of which knows what the depth is. The answer
     * is coerced for the <i>view</i> and never written back: what the author chose is theirs, and an
     * Advanced client that remembers "tables" must still find it there after a detour through Normal.
     */
    public static Section shownOrFirst(Section section) {
        if (section != null && Advanced.showsSection(section.name())) {
            return section;
        }
        Section[] shown = shown();
        return shown.length == 0 ? Section.QUESTS : shown[0];
    }

    /**
     * What a press on a page is looking at.
     *
     * <p>A page is a flat list of two kinds of line, and the two are different heights, so "which line is
     * at this y" is arithmetic rather than a division. Every page is built as this list, which is what lets
     * one hit test serve all three.
     */
    public enum Kind {
        /** A group's name: not pressable, and taller than nothing but shorter than a row. */
        HEADING,
        /** One pressable row. */
        ROW
    }

    /** The frame's bands, all inside the card it was given. */
    public record Frame(BookGeometry.Rect title, BookGeometry.Rect tabs, BookGeometry.Rect page,
                        BookGeometry.Rect footer) {

        /**
         * The bands, from the card's whole rectangle.
         *
         * <p>The card rather than a body inside it, unlike {@link TableEditorLayout}: that panel has a
         * breadcrumb, a header and a toolbar above its list, and this one has a title and then content.
         * Deriving a body here only to derive these from it would be an indirection with no reader.
         */
        public static Frame of(BookGeometry.Rect card) {
            BookGeometry.Rect title = BookGeometry.Rect.at(card.x(), card.y(), card.width(),
                    Math.min(TITLE_HEIGHT, card.height()));
            int top = title.bottom() + PAD;
            int width = Math.max(0, card.width() - 2 * PAD);
            BookGeometry.Rect tabs = BookGeometry.Rect.at(card.x() + PAD, top, width,
                    Math.max(0, Math.min(TAB_HEIGHT, Math.max(0, card.bottom() - FOOTER_HEIGHT - top))));
            int pageTop = tabs.bottom() + TAB_PAGE_GAP;
            int bottom = Math.max(pageTop, card.bottom() - FOOTER_HEIGHT);
            BookGeometry.Rect page = BookGeometry.Rect.at(card.x() + PAD, pageTop, width,
                    Math.max(0, bottom - pageTop));
            BookGeometry.Rect footer = BookGeometry.Rect.at(card.x(), card.bottom() - FOOTER_HEIGHT,
                    card.width(), Math.min(FOOTER_HEIGHT, card.height()));
            return new Frame(title, tabs, page, footer);
        }

        /**
         * One tab, an equal share of the strip among the sections this depth shows.
         *
         * <p>Equal shares rather than text widths, so a tab never moves when the list under it does
         * and the drawing and the hit test divide by the same number. The last tab takes the
         * remainder, so no pixel of the strip belongs to no tab.
         */
        public BookGeometry.Rect tab(int index) {
            int count = Math.max(1, shown().length);
            int each = tabs.width() / count;
            int x = tabs.x() + index * each;
            int width = index == count - 1 ? Math.max(0, tabs.right() - x) : each;
            int height = Math.max(0, Math.min(TAB_HEIGHT, tabs.height()));
            return BookGeometry.Rect.at(x, tabs.y(), Math.max(0, width), height);
        }

        /** The band a page's lines are drawn in and clipped to: the page, less the scrollbar's strip. */
        public BookGeometry.Rect rows() {
            return BookGeometry.Rect.at(page.x(), page.y(), Math.max(0, page.width() - SCROLLBAR),
                    page.height());
        }

        /** The scrollbar's strip, inside the page's right edge. */
        public BookGeometry.Rect scrollbar() {
            return BookGeometry.Rect.at(page.right() - SCROLLBAR, page.y(), SCROLLBAR, page.height());
        }
    }

    /**
     * Which tab a point is on, or null.
     *
     * <p>Null rather than a default, because the caller has to distinguish "a press on a tab" from "a
     * press on the page" — and a method that answered {@code TABLES} for a point over the page would make
     * every row press change the section.
     */
    public static Section sectionAt(Frame frame, int mouseX, int mouseY) {
        if (!frame.tabs().contains(mouseX, mouseY)) {
            return null;
        }
        // `shown()` rather than `values()`, and that is the whole of what the depth needs here: a tab the
        // panel does not draw must not be a tab a press can land on. See `shown`.
        Section[] all = shown();
        for (int i = 0; i < all.length; i++) {
            BookGeometry.Rect tab = frame.tab(i);
            if (tab.contains(mouseX, mouseY)) {
                return all[i];
            }
        }
        return null;
    }

    /** How tall one line is, by kind. */
    public static int lineHeight(Kind kind) {
        return kind == Kind.HEADING ? HEADING_HEIGHT : ROW_HEIGHT;
    }

    /** The y a line starts at, relative to the page's first line. */
    public static int lineTop(List<Kind> lines, int index) {
        int y = 0;
        for (int i = 0; i < index && i < lines.size(); i++) {
            y += lineHeight(lines.get(i));
        }
        return y;
    }

    /** How tall a page's content is: the sum of its lines. */
    public static int contentHeight(List<Kind> lines) {
        return lineTop(lines, lines.size());
    }

    /**
     * The line at a page-relative y, or -1 for a y past the content.
     *
     * <p>Page-relative means the caller has already taken the scroll off, which is the same rule
     * {@link TableEditorLayout#dropIndexAt} follows: one place decides what a coordinate means, and it is
     * the place that knows the scroll.
     */
    public static int lineAt(List<Kind> lines, int y) {
        if (y < 0) {
            return -1;
        }
        int top = 0;
        for (int i = 0; i < lines.size(); i++) {
            int next = top + lineHeight(lines.get(i));
            if (y < next) {
                return i;
            }
            top = next;
        }
        return -1;
    }

    /**
     * The footer's two buttons: what the page offers at the left, the way out at the right.
     *
     * <h2>Why this is here and was nowhere</h2>
     *
     * <p>These were two rectangles written in the screen, at the call site, beside the two calls that
     * drew them — the one piece of this panel that did not come from this class. The fault that found
     * was not a wrong rectangle: it was that a control drawn from an expression nobody else could ask
     * about is a control no test can hold, and the panel's own press list never learned the two existed.
     * So the pair is derived here, both of them, from the band they sit in.
     *
     * <p>Widths are capped at half the free room so the two can never overlap on a squeezed card, and
     * the cap only binds when it has to: on a card of any normal width each button keeps its own full
     * width, which is what the panel has always drawn.
     */
    public record Footer(BookGeometry.Rect newTable, BookGeometry.Rect done) {

        public static Footer of(Frame frame) {
            BookGeometry.Rect footer = frame.footer();
            int height = Math.max(0, Math.min(FOOTER_BUTTON_HEIGHT, footer.height() - PAD));
            int y = footer.bottom() - PAD - height;
            int room = Math.max(0, footer.width() - PAD * 2);
            int each = Math.max(0, (room - FOOTER_GAP) / 2);
            int left = Math.max(0, Math.min(NEW_TABLE_WIDTH, each));
            int right = Math.max(0, Math.min(DONE_WIDTH, each));
            return new Footer(BookGeometry.Rect.at(footer.x() + PAD, y, left, height),
                    BookGeometry.Rect.at(footer.right() - PAD - right, y, right, height));
        }
    }

    /**
     * A footer button's height: the book's own control height, which is what the modal footer is sized
     * for.
     *
     * <p>It was fourteen, this panel's own size, and that was the picture's report: beside the Rewards
     * card's twenty-pixel Claim all and Done, this footer's Done read as a smaller, lesser control on a
     * card with the same chrome.
     */
    public static final int FOOTER_BUTTON_HEIGHT = BookGeometry.OVERLAY_CONTROL_HEIGHT;

    /**
     * The page's own action, at the footer's left: "New table" on Tables, the sort order on
     * Templates.
     *
     * <p>Sized for the longest of those — "Sort: Recent" — so no page's action truncates on a
     * card of any normal width. The cap in {@link Footer#of} still binds first on a squeezed
     * card, where truncating is the degradation rather than an overlap.
     */
    public static final int NEW_TABLE_WIDTH = 104;

    /** "Done" — the way out, at the footer's right, as wide as every other card's. */
    public static final int DONE_WIDTH = 88;

    /** The least air between the two, when the card is too narrow to give each its full width. */
    public static final int FOOTER_GAP = 6;

    private AssetsLayout() {
    }
}
