package dev.ellipog.tenet.client;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Motion;
import dev.ellipog.tenet.editor.EditorOp;
import dev.ellipog.tenet.client.hud.PinnedQuests;
import dev.ellipog.tenet.quest.CanvasElement;
import dev.ellipog.tenet.client.dev.ClientEditorClipboard;
import dev.ellipog.tenet.client.dev.Labels;
import dev.ellipog.tenet.client.dev.MenuFlyout;
import dev.ellipog.tenet.client.dev.MenuFlyoutArt;
import dev.ellipog.tenet.client.dev.MenuPlacement;
import dev.ellipog.tenet.quest.DependencyStyle;
import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.net.ResetQuestPayload;
import dev.ellipog.tenet.net.ResetChapterPayload;
import dev.ellipog.tenet.net.ResetAllPayload;
import dev.ellipog.tenet.net.CompleteQuestPayload;
import dev.ellipog.tenet.net.SubmitTaskPayload;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The quest book's right-click menus: the sidebar's and the canvas's, their rows and flyouts, where
 * they open, and what a press on one does.
 *
 * <p>Moved out of {@link QuestBookScreen} as it stood, with the state only it reads; the screen
 * keeps the state it shares and reaches this through its {@code bookMenu} field. The screen-wide
 * gates that hand an open menu the first press and the pointer ({@code pressDropdown},
 * {@code overlayOwnsPointer}) stay on the screen, beside the input they order.
 */
final class BookMenu {

    private final QuestBookScreen book;

    BookMenu(QuestBookScreen book) {
        this.book = book;
    }

    /** The chapter a "Move to" submenu would move, or null when the menu has no such row. */
    private String menuChapter;

    /**
     * Whether the menu's Delete is armed: the first press asks, the second deletes.
     *
     * <p>The same two-press rule the quest card's Delete already follows, and for the same reason: it is
     * the one row whose mistake cannot be undone by pressing something else. Disarming is what
     * {@link #closeMenu} does, so every other press, Escape and clicking away all mean "no".
     */
    private boolean menuDeleteArmed;

    /**
     * Whether the menu's progress reset is armed: the first press asks, the second clears.
     *
     * <p>Beside {@code menuDeleteArmed} rather than sharing it, because the two mistakes are
     * different ones: arming a chapter reset must not re-label the Delete row, and arming a delete
     * must not re-label the reset. A reset clears progress rather than files, so only the wide
     * rungs — a chapter, the whole book — ask at all; a single quest resets on one press, which is
     * the speed the playtesting loop exists for. Cleared with the delete arm in
     * {@link #closeMenu}, so anything that means "no" means it for both.
     */
    private boolean menuResetArmed;

    /**
     * What a canvas menu's row was about: a quest, or a line between two.
     *
     * <p>A line's subject is the pair (dependency, dependent) — the same key its override is stored
     * under — and the two are kept apart from the sidebar's chapter subject because a menu knows which
     * kind it is and must not have to guess from which field happens to be set.
     */
    private String canvasMenuQuest;
    private String canvasMenuFrom;
    private String canvasMenuTo;

    /**
     * The element the open canvas menu is about, or null.
     *
     * <p>Beside the three above for the same reason they exist: an armed delete rebuilds the menu in place, and
     * a menu that had to find its own subject again would be one that could arm a delete for one element and
     * then remove another.
     */
    private String canvasMenuElement;

    /**
     * How far below its rest the menu still sits: three pixels on the frame it opens, none once settled.
     *
     * <p>Read by {@link #menuRects}, which is the one derivation the drawing, the press and the flyout
     * placement all share — so the menu, its hover and its submenu arrive as one thing climbing into
     * place rather than three things disagreeing for a tenth of a second. With motion off the theme's
     * own duration is zero and this is zero with it: a menu that is simply there.
     */
    private int menuRise() {
        if (book.menu.isEmpty() || book.menuOpenedAt < 0) {
            return 0;
        }
        float arrived =
                PanelLayout.revealed(net.minecraft.util.Util.getMillis() - book.menuOpenedAt, QuestBookScreen.MENU_RISE_MILLIS);
        return Math.round(QuestBookScreen.MENU_RISE_PX * (1F - arrived));
    }

    // ------------------------------------------------------------------
    // The sidebar's menu, and the rows every menu is built from
    // ------------------------------------------------------------------

    /**
     * One line of a right-click menu.
     *
     * @param action  what a press runs, or null for a row that is not pressable (a flyout parent, or
     *                the "…" row a capped submenu ends with)
     * @param submenu whether hovering this row opens the flyout beside it
     */
    record MenuItem(String label, Runnable action, boolean danger, List<MenuFlyout.Row> children) {

        static MenuItem of(String label, Runnable action) {
            return new MenuItem(label, action, false, List.of());
        }

        static MenuItem destructive(String label, Runnable action) {
            return new MenuItem(label, action, true, List.of());
        }

        /** A row that opens the flyout beside the menu. Its rows are built with it, not looked up. */
        static MenuItem parent(String label, List<MenuFlyout.Row> children) {
            return new MenuItem(label, null, false, List.copyOf(children));
        }

        boolean submenu() {
            return !children.isEmpty();
        }
    }

    /** The rows of a menu row's flyout, for a caller holding only the row's index. */
    private List<MenuFlyout.Row> childrenOf(int row) {
        return row >= 0 && row < book.menu.size() ? book.menu.get(row).children() : List.of();
    }
    static final int MENU_WIDTH = 150;

    /** Opens the menu for whatever the pointer is on: a chapter, a group, or the empty list. */
    boolean openSidebarMenuAt(double mouseX, double mouseY) {
        if (!book.sidebarViewport().containsScreen(mouseX, mouseY)) {
            return false;
        }
        for (SidebarLayout.Row row : BookSidebar.sidebar().rows()) {
            if (SidebarLayout.isInlineKey(row.key())) {
                // The edit answers its own presses: the field takes focus, the status line
                // toggles the id disclosure, and anything else cancels it first (see mouseClicked).
                continue;
            }
            BookGeometry.Rect rect = book.bookSidebar.placedRect(row.key());
            if (rect == null || !rect.contains(mouseX, mouseY)) {
                continue;
            }
            book.openMenu(row.group() ? groupMenu(row.id()) : chapterMenu(row.id()));
            // Only a chapter menu has a "Move to" submenu, so only then is there a chapter to move.
            menuChapter = row.group() ? null : row.id();
            book.menuX = (int) mouseX;
            book.menuY = (int) mouseY;
            return true;
        }
        book.openMenu(sidebarEmptyMenu());
        menuChapter = null;
        book.menuX = (int) mouseX;
        book.menuY = (int) mouseY;
        return true;
    }

    /**
     * What the sidebar's empty list offers besides new chapters and groups: clearing the whole
     * book's progress for playtesting.
     *
     * <p>Built by the caller rather than folded into the list above, because this row arms and the
     * other two do not: a menu built armed has one job and a menu built unarmed has the other, the
     * same rule the chapter menu's Delete follows.
     */
    private List<MenuItem> sidebarEmptyMenu() {
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuItem.of("New chapter",
                () -> book.bookSidebar.startInlineCreate("chapter", "")));
        items.add(MenuItem.of("New group",
                () -> book.bookSidebar.startInlineCreate("group", "")));
        boolean resetArmed = menuResetArmed;
        items.add(MenuItem.destructive(
                resetArmed ? "Really reset everything?" : "Reset whole book progress",
                resetArmed
                        ? this::resetAll
                        : () -> armReset(() -> book.openMenu(sidebarEmptyMenu()), null)));
        return items;
    }

    private List<MenuItem> chapterMenu(String id) {
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuItem.of("Open", () -> {
            book.bookSidebar.selectChapter(id);
            book.rebuildWidgets();
        }));
        items.add(MenuItem.of("Rename",
                () -> book.bookSidebar.startInlineRename("chapter", id)));
        items.add(MenuItem.of("Duplicate...",
                () -> book.bookSidebar.openNaming("chapter", QuestBookScreen.NamingMode.DUPLICATE, id, "", 0)));
        // One row, and the groups appear beside it on hover: a flat list of every destination is what
        // made this menu taller than the window, and a menu whose last entries are clipped away is a
        // destination that cannot be reached at all.
        items.add(MenuItem.parent("Move to \u203a", moveToItems(id)));
        // The label and the action are chosen together, from the arming state this menu was built in.
        // The first version tested the flag inside the action instead -- and the press that runs an
        // action closes the menu first, which clears the flag, so "Really delete?" re-armed forever and
        // never deleted. A menu built armed has one job; a menu built unarmed has the other.
        boolean armed = menuDeleteArmed;
        items.add(MenuItem.destructive(
                armed ? "Really delete?" + chapterDeleteNote(id) : "Delete",
                armed
                        ? () -> book.send(new EditorOp.DeleteChapter(id))
                        : () -> armDelete(() -> book.openMenu(chapterMenu(id)), id)));
        // Playtesting the chapter: clearing its progress so reveals and states can be checked again.
        // Armed like the delete above but on its own flag, so the two mistakes stay different rows.
        boolean resetArmed = menuResetArmed;
        items.add(MenuItem.destructive(
                resetArmed ? "Really reset chapter?" + chapterResetNote(id) : "Reset chapter progress",
                resetArmed
                        ? () -> resetChapter(id)
                        : () -> armReset(() -> book.openMenu(chapterMenu(id)), id)));
        return items;
    }

    private List<MenuItem> groupMenu(String id) {
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuItem.of("New chapter here",
                () -> book.bookSidebar.startInlineCreate("chapter", id)));
        items.add(MenuItem.of("Rename",
                () -> book.bookSidebar.startInlineRename("group", id)));
        items.add(MenuItem.of("Duplicate...",
                () -> book.bookSidebar.openNaming("group", QuestBookScreen.NamingMode.DUPLICATE, id, "", 0)));
        boolean armed = menuDeleteArmed;
        items.add(MenuItem.destructive(
                armed ? "Really delete?" + groupDeleteNote(id) : "Delete",
                armed
                        ? () -> book.send(new EditorOp.DeleteGroup(id))
                        : () -> armDelete(() -> book.openMenu(groupMenu(id)), null)));
        return items;
    }

    /**
     * Arms the menu's Delete and re-labels it in place.
     *
     * <p>The menu is closed by the press before its action runs — that is how every other row behaves —
     * so arming puts a rebuilt menu back where the old one was, anchored at the same point. The chapter
     * is re-established too, or the "Move to" submenu would come back empty on a rebuilt chapter menu.
     */
    private void armDelete(Runnable rebuild, String chapterId) {
        menuDeleteArmed = true;
        menuChapter = chapterId;
        rebuild.run();
    }

    /**
     * Arms the menu's progress reset and re-labels it in place.
     *
     * <p>The same mechanics as {@link #armDelete}: the press closes the menu before its action
     * runs, so arming puts a rebuilt menu back where the old one was, anchored at the same point.
     * A separate flag, because arming a reset must not re-label the Delete row beside it.
     */
    private void armReset(Runnable rebuild, String chapterId) {
        menuResetArmed = true;
        menuChapter = chapterId;
        rebuild.run();
    }

    /** How many quests the chapter holds, for the reset confirm. */
    private static String chapterResetNote(String chapterId) {
        int quests = 0;
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            if (entry.chapterId().equals(chapterId)) {
                quests++;
            }
        }
        return quests == 1 ? " (1 quest)" : " (" + quests + " quests)";
    }

    /** How many quests the chapter holds, and how many elsewhere depend on them. */
    private static String chapterDeleteNote(String chapterId) {
        Set<String> ids = new HashSet<>();
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            if (entry.chapterId().equals(chapterId)) {
                ids.add(entry.id());
            }
        }
        int dependents = 0;
        if (!ids.isEmpty()) {
            for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
                if (entry.chapterId().equals(chapterId)) {
                    continue;
                }
                for (String dependency : QuestBookScreen.dependenciesOf(entry)) {
                    if (ids.contains(dependency)) {
                        dependents++;
                        break;
                    }
                }
            }
        }
        return note(ids.size(), "quest", dependents, "dependent");
    }

    /** How many chapters a group holds, and how many quests they hold between them. */
    private static String groupDeleteNote(String groupId) {
        Set<String> chapters = new HashSet<>();
        for (ClientQuestCache.ChapterEntry chapter : ClientQuestCache.chapters()) {
            if (chapter.groupId().equals(groupId)) {
                chapters.add(chapter.id());
            }
        }
        int quests = 0;
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            if (entry.chapterGroupId().equals(groupId)) {
                quests++;
                chapters.add(entry.chapterId());
            }
        }
        return note(chapters.size(), "chapter", quests, "quest");
    }

    /** "(3 quests, 1 dependent)", or empty when there is nothing to warn about. */
    static String note(int first, String firstWord, int second, String secondWord) {
        if (first == 0 && second == 0) {
            return "";
        }
        StringBuilder note = new StringBuilder(" (");
        if (first > 0) {
            note.append(first).append(' ').append(firstWord).append(first == 1 ? "" : "s");
        }
        if (second > 0) {
            if (first > 0) {
                note.append(", ");
            }
            note.append(second).append(' ').append(secondWord).append(second == 1 ? "" : "s");
        }
        return note.append(')').toString();
    }

    /**
     * The destinations for one chapter: no group, then every other group.
     *
     * <p>Longer than fits is handled here rather than at the drawing: the list is cut to the rows the
     * panel can show and a final unpressable "…" says the rest exist, so what is drawn is always exactly
     * what can be pressed and the panel never runs past the bottom of the book.
     */
    private List<MenuFlyout.Row> moveToItems(String id) {
        List<MenuFlyout.Row> items = new ArrayList<>();
        String current = BookSidebar.groupOfChapter(id);
        if (!current.isEmpty()) {
            items.add(MenuFlyout.text(Labels.of("tenet.dev.menu.no_group"),
                    () -> book.send(new EditorOp.MoveChapter(id, "", Integer.MAX_VALUE))));
        }
        for (ClientQuestCache.GroupEntry entry : ClientQuestCache.groups()) {
            if (entry.id().equals(current)) {
                continue;
            }
            items.add(MenuFlyout.text(Labels.of("tenet.dev.menu.move_to", entry.titleText()),
                    () -> book.send(new EditorOp.MoveChapter(id, entry.id(), Integer.MAX_VALUE))));
        }
        int room = Math.max(0, (book.panelRect().height() - 16) / QuestBookScreen.MENU_ROW_HEIGHT - 2);
        if (items.size() > room && room >= 2) {
            List<MenuFlyout.Row> cut = new ArrayList<>(items.subList(0, room - 1));
            cut.add(MenuFlyout.text("\u2026", null));
            return cut;
        }
        return items;
    }

    void closeMenu() {
        book.menu = List.of();
        menuChapter = null;
        book.submenuRow = -1;
        menuDeleteArmed = false;
        menuResetArmed = false;
        canvasMenuQuest = null;
        canvasMenuFrom = null;
        canvasMenuTo = null;
        book.menuOpenedAt = -1L;
    }

    /**
     * Every item's rectangle, from the one derivation the drawing and the press both read.
     *
     * <p>The rise included, so a menu climbing into place is hit where it is drawn: a press landing
     * mid-arrival answers the row under the pointer rather than the row three pixels above it.
     */
    List<BookGeometry.Rect> menuRects() {
        List<BookGeometry.Rect> rects = new ArrayList<>();
        int x = menuLeft();
        int y = menuTop() + menuRise();
        for (int i = 0; i < book.menu.size(); i++) {
            rects.add(BookGeometry.Rect.at(x, y + i * QuestBookScreen.MENU_ROW_HEIGHT, MENU_WIDTH, QuestBookScreen.MENU_ROW_HEIGHT));
        }
        return rects;
    }

    /**
     * The open flyout, placed: one derivation the drawing, the presses and the hover's bridge all read.
     *
     * <p>Beside the menu when there is room and on its other side when there is not, and its top pulled
     * up until it fits above the book's bottom edge. The top is also held below the chapter list's top,
     * because the flyout is drawn inside that clip band and the preview rows are taller than any text
     * submenu was — a tall panel anchored near the header is the one thing that could be eaten by it.
     */
    MenuFlyout.Placed submenuPlacement(int parentIndex) {
        List<MenuFlyout.Row> rows = childrenOf(parentIndex);
        if (parentIndex < 0 || parentIndex >= book.menu.size() || rows.isEmpty()) {
            return MenuFlyout.place(0, 0, List.of());
        }
        BookGeometry.Rect main = menuRects().get(parentIndex);
        int width = MenuFlyout.width(rows);
        int height = MenuFlyout.height(rows);
        int x = MenuPlacement.submenuX(menuPlacement(), width, 4);
        int top = Math.max(book.panelRect().y() + 4, book.geometry().chapterListTop() + 4);
        int y = Math.max(top, Math.min(main.y(), book.panelRect().bottom() - 4 - height));
        return MenuFlyout.place(x, y, rows);
    }

    /**
     * Where the menu goes, and which side its flyout opens on.
     *
     * <p>The pointer decides, and the window bounds it — one rule for the sidebar's menus and the
     * canvas's, in {@link MenuPlacement}, because the first version measured x from the sidebar column's
     * right edge and so put every canvas menu beside the sidebar whatever the click was near.
     */
    private MenuPlacement.Placed menuPlacement() {
        BookGeometry.Rect window = book.panelRect();
        int flyoutWidth = book.submenuRow >= 0 ? MenuFlyout.width(childrenOf(book.submenuRow)) : MENU_WIDTH;
        return MenuPlacement.place(book.menuX, book.menuY, MENU_WIDTH, book.menu.size() * QuestBookScreen.MENU_ROW_HEIGHT + 6,
                flyoutWidth, window.x() + 4, window.y() + 4, window.right() - 4, window.bottom() - 4, 8);
    }

    private int menuLeft() {
        return menuPlacement().x();
    }

    private int menuTop() {
        return menuPlacement().y();
    }

    /** Which menu row the pointer is on, or -1. */
    int menuRowAt(double mouseX, double mouseY) {
        List<BookGeometry.Rect> rects = menuRects();
        for (int i = 0; i < rects.size(); i++) {
            if (rects.get(i).contains(mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    /** Whether a press is the menu's: a row runs, a flyout tunes or chooses, anything else just closes. */
    boolean pressMenu(double mouseX, double mouseY) {
        if (book.menu.isEmpty()) {
            return false;
        }
        int parent = book.submenuRow;
        if (parent >= 0) {
            MenuFlyout.Placed placed = submenuPlacement(parent);
            MenuFlyout.Hit hit = MenuFlyout.hit(placed, mouseX, mouseY);
            if (hit instanceof MenuFlyout.Hit.Choose choose) {
                closeMenu();
                choose.run().run();
                return true;
            }
            if (hit instanceof MenuFlyout.Hit.Tune tune) {
                tune.run().run();
                // The rows are rebuilt in place, with the flyout still open: the selection, the reset
                // chips and the rows another axis switches off all read the style the press just wrote,
                // and rebuilding is what keeps the panel showing the line as it now is. The pointer is
                // still on the same parent row, so `submenuRow` survives -- `closeMenu` is not called.
                if (canvasMenuFrom != null && canvasMenuTo != null) {
                    book.openMenu(lineMenuItems(canvasMenuFrom, canvasMenuTo));
                }
                return true;
            }
            if (hit != null) {
                return true;  // on the flyout, on nothing pressable
            }
        }
        int row = menuRowAt(mouseX, mouseY);
        if (row >= 0) {
            MenuItem item = book.menu.get(row);
            if (item.submenu()) {
                // The flyout is hover-driven and already open under this pointer; a press on its parent
                // chooses nothing, and closing here would make the row impossible to use at all.
                return true;
            }
            closeMenu();
            item.action().run();
            return true;
        }
        closeMenu();
        return true;
    }

    /** The menu, drawn after the widget pass so it can overhang the list it describes. */
    /**
     * How much ink the menu has: nothing on the frame it opens, full once settled.
     *
     * <p>Beside the rise rather than instead of it: the climb says where the menu came from and the
     * fade says it arrived. One envelope for the main panel and its rows — the flyout beside it keeps
     * rise-only, because its preview inks cannot take alpha without a second ink path. Motion off is
     * full ink at once, like every other arrival.
     */
    private float menuFade() {
        if (book.menu.isEmpty() || book.menuOpenedAt < 0) {
            return 1F;
        }
        return PanelLayout.revealed(net.minecraft.util.Util.getMillis() - book.menuOpenedAt, QuestBookScreen.MENU_RISE_MILLIS);
    }

    void drawMenu(GuiRenderer r, int mouseX, int mouseY) {
        if (book.menu.isEmpty()) {
            return;
        }
        float fade = menuFade();
        List<BookGeometry.Rect> rects = menuRects();
        BookGeometry.Rect first = rects.get(0);
        int bottom = rects.get(rects.size() - 1).bottom();
        ArmatureTheme.panel(r, first.x() - 2, first.y() - 3, MENU_WIDTH + 4,
                bottom - first.y() + 6, Colour.alphaOf(ArmatureTheme.raised(), fade),
                Colour.alphaOf(ArmatureTheme.panelEdge(), fade));
        for (int i = 0; i < rects.size(); i++) {
            drawMenuItem(r, rects.get(i), book.menu.get(i), mouseX, mouseY, fade);
        }

        int parent = book.submenuRow;
        if (parent < 0) {
            return;
        }
        MenuFlyout.Placed placed = submenuPlacement(parent);
        if (placed.rows().isEmpty()) {
            return;
        }
        ArmatureTheme.panel(r, placed.panel().x(), placed.panel().y(), placed.panel().width(),
                placed.panel().height(), ArmatureTheme.raised(), ArmatureTheme.panelEdge());
        MenuFlyoutArt.draw(r, placed, mouseX, mouseY);
    }

    /** One row of either panel: its hover wash, and its label in the ink its meaning asks for. */
    private void drawMenuItem(GuiRenderer r, BookGeometry.Rect rect, MenuItem item,
                              int mouseX, int mouseY, float fade) {
        boolean pressable = item.action() != null || item.submenu();
        if (pressable && rect.contains(mouseX, mouseY)) {
            r.fill(rect.x(), rect.y(), rect.right(), rect.bottom(),
                    Colour.alphaOf(ArmatureTheme.rowHover(), fade));
        }
        // A submenu parent is a live row, not a disabled one: it opened the panel beside it. Only a row
        // that can do nothing at all -- the "…" that says the rest did not fit -- is drawn faint.
        int colour = item.submenu() ? ArmatureTheme.body()
                : item.action() == null ? ArmatureTheme.faint()
                : item.danger() ? ArmatureTheme.blocked() : ArmatureTheme.body();
        r.text(QuestBookScreen.fitLabel(r, item.label()), rect.x() + 4, rect.y() + (QuestBookScreen.MENU_ROW_HEIGHT - 8) / 2,
                Colour.alphaOf(colour, fade));
    }



    // ------------------------------------------------------------------
    // The canvas's menus
    // ------------------------------------------------------------------

    /**
     * Opens the canvas's menu at the pointer: for a quest, for a line, or for the empty canvas.
     *
     * <p>Node first, then line, then empty — the same priority as the press, so what a click opens is
     * what it looked like it was on. Edit mode owns every entry that edits; a reader gets the two entries
     * that do not -- open the quest, and pin it -- because pinning is the reader's own action on a quest
     * and a menu that only exists in edit mode is one a player can never open. Lines, elements and empty
     * canvas stay edit-only: there is nothing there a reader can do.
     */
    boolean openCanvasMenuAt(double mouseX, double mouseY) {
        String chapter = QuestBookScreen.effectiveChapter();
        if (chapter == null) {
            return false;
        }
        List<ClientQuestCache.Entry> quests = book.questsIn(chapter);
        ClientQuestCache.Entry node = book.nodeAt(mouseX, mouseY, quests);
        if (!book.mayEditNow()) {
            if (node == null) {
                return false;
            }
            // The node under the pointer and nothing else: selection is an edit-mode concept, and acting
            // on a set the reader never made would be the menu pinning something they were not pointing
            // at -- the same reason the edit path re-selects first.
            book.openMenu(readerNodeMenuItems(node.id()));
            book.menuX = (int) mouseX;
            book.menuY = (int) mouseY;
            book.submenuRow = -1;
            menuDeleteArmed = false;
            menuResetArmed = false;
            return true;
        }
        canvasMenuQuest = null;
        canvasMenuFrom = null;
        canvasMenuTo = null;
        canvasMenuElement = null;
        if (node != null) {
            // A right-click on a node outside the selection makes it the subject: acting on a set the
            // pointer is not on would be the menu editing something the author was not pointing at.
            if (!QuestBookScreen.isSelected(node.id())) {
                QuestBookScreen.selectedQuest = node.id();
                QuestBookScreen.multiSelection.clear();
            }
            canvasMenuQuest = node.id();
            book.openMenu(nodeMenuItems(node.id()));
        }
        else {
            List<String> edge = book.bookRewards.edgeAt(mouseX, mouseY, quests);
            if (edge != null) {
                canvasMenuFrom = edge.get(0);
                canvasMenuTo = edge.get(1);
                book.openMenu(lineMenuItems(edge.get(0), edge.get(1)));
            }
            else {
                // And then an element, before the empty canvas: an element is drawn over the canvas, so a
                // right-click that lands on one is a right-click on it -- the same priority the press and the
                // hover use, and the reason a decoration can be duplicated or removed from where it sits.
                String element = book.elementAt(mouseX, mouseY);
                if (element != null) {
                    QuestBookScreen.selectedElement = element;
                    QuestBookScreen.selectedQuest = null;
                    QuestBookScreen.multiSelection.clear();
                    canvasMenuElement = element;
                    book.openMenu(elementMenuItems(element));
                }
                else {
                    book.openMenu(emptyCanvasItems(mouseX, mouseY));
                }
            }
        }
        book.menuX = (int) mouseX;
        book.menuY = (int) mouseY;
        book.submenuRow = -1;
        menuDeleteArmed = false;
        menuResetArmed = false;
        return true;
    }

    /** What a quest node offers. A multi-selection is acted on as one, and the labels say so. */
    private List<MenuItem> nodeMenuItems(String id) {
        List<String> targets = QuestBookScreen.selection().contains(id) ? QuestBookScreen.selection() : List.of(id);
        String many = targets.size() > 1 ? " " + targets.size() + " quests" : "";
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuItem.of("Open", () -> book.bookRewards.openOverlay(id)));
        items.addAll(pinItems(targets, many));
        items.add(MenuItem.of("Duplicate" + many, () -> book.duplicateSelection(targets)));
        items.add(MenuItem.of("Copy" + many, book::copySelection));
        // Playtesting, not editing: clearing progress so reveals and states can be checked again,
        // without leaving the book for a command. One press, like the command it sends — progress
        // is replayable, so unlike Delete this is not the row whose mistake cannot be undone.
        items.add(MenuItem.of("Reset progress" + many, () -> resetQuests(targets)));
        // Playtesting in the other direction: reaching a late quest without earning the way
        // there. Same trust shape as Reset above — one packet per quest, the server resolving
        // each id and re-checking the permission — and the same no-arm press, because completing
        // is replayable too: one Reset press undoes it. Rewards land as waiting-to-claim, never
        // handed over, which is the command's own rule for the same press.
        items.add(MenuItem.of("Complete" + many, () -> completeQuests(targets)));
        if (targets.size() == 1) {
            items.add(MenuItem.of("Complete with dependencies\u2026",
                    () -> completeQuestWithDependencies(id)));
        }
        if (targets.size() == 1) {
            items.add(MenuItem.of("Add dependency\u2026", () -> {
                QuestBookScreen.selectedQuest = id;
                QuestBookScreen.multiSelection.clear();
                book.armDependencyPick();
            }));
        }
        boolean armed = menuDeleteArmed;
        items.add(MenuItem.destructive(
                armed ? "Really delete?" + QuestBookScreen.questDeleteNote(targets) : "Delete" + many,
                armed
                        ? () -> {
                            book.deleteSelection(targets);
                            QuestBookScreen.selectedQuest = null;
                            QuestBookScreen.multiSelection.clear();
                        }
                        : () -> {
                            menuDeleteArmed = true;
                            canvasMenuQuest = id;
                            book.openMenu(nodeMenuItems(id));
                        }));
        return items;
    }

    // ------------------------------------------------------------------
    // The menus' pin rows: what a menu offers for pinning to the HUD
    // ------------------------------------------------------------------

    /**
     * What a quest node offers a reader: open it, or pin it.
     *
     * <p>Two rows and never more: Duplicate, Copy, dependencies and Delete are edits, and an edit in a
     * reader's menu would be a promise the game must then refuse -- the server re-checks the permission
     * before anything lands, so the refusal would arrive one round trip later, wearing the menu's own
     * words. Pinning is here because it writes the reader's own file, which needs no permission at all.
     */
    private List<MenuItem> readerNodeMenuItems(String id) {
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuItem.of("Open", () -> book.bookRewards.openOverlay(id)));
        items.addAll(pinItems(List.of(id), ""));
        return items;
    }

    /**
     * The node menu's pinning rows: one, or none.
     *
     * <h2>Why the rows change rather than one row that toggles</h2>
     *
     * <p>Because "pin" and "unpin" are two different acts and a single row whose label flipped would be one
     * control that does the opposite of what a player last read. It is the same rule the fold control follows
     * -- two labels, because it is two acts -- and it costs a row that is simply absent when it means
     * nothing.
     *
     * <p>Labels are literals, like every other row in this menu: it is English-only throughout, and one
     * translatable row among eight would be a promise the other seven do not keep.
     */
    private List<MenuItem> pinItems(List<String> targets, String many) {
        boolean allPinned = true;
        for (String target : targets) {
            allPinned &= PinnedQuests.isPinned(target);
        }

        List<MenuItem> items = new ArrayList<>();
        if (!allPinned) {
            items.add(MenuItem.of("Pin" + many + " to HUD", () -> pinAll(targets)));
        }
        else {
            items.add(MenuItem.of("Unpin" + many + " from HUD", () -> unpinAll(targets)));
        }
        return items;
    }

    /** Pins every target, and says so when the panel is full rather than dropping one of somebody's pins. */
    private void pinAll(List<String> targets) {
        boolean refused = false;
        for (String target : targets) {
            if (!PinnedQuests.pin(target)) {
                refused = true;
            }
        }
        if (refused) {
            book.toast(Component.translatable("tenet.screen.hud_pins_full", PinnedQuests.MAX_PINS).getString(),
                    true);
        }
    }

    private void unpinAll(List<String> targets) {
        for (String target : targets) {
            PinnedQuests.unpin(target);
        }
    }

    /** What the empty canvas offers: make one here, paste here, take the chapter. */
    private List<MenuItem> emptyCanvasItems(double mouseX, double mouseY) {
        double x = BookGeometry.snap(book.viewport().contentX(mouseX), BookGeometry.SNAP_GRID, QuestBookScreen.snappingNow());
        double y = BookGeometry.snap(book.viewport().contentY(mouseY), BookGeometry.SNAP_GRID, QuestBookScreen.snappingNow());
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuItem.of("New quest here",
                () -> book.send(new EditorOp.Create(Math.round(x), Math.round(y)))));
        // The four kinds of element, offered where the author has just pointed: an element needs a position,
        // and a right-click on empty canvas is a position. Flat rows rather than a submenu, because a submenu
        // in this screen is a flyout of previews -- a panel for choosing among variants of one thing -- and
        // these four are four different things rather than four ways to draw one.
        for (String[] kind : ELEMENT_KINDS) {
            items.add(MenuItem.of("Add " + kind[1] + " here", () -> addElement(kind[0], x, y)));
        }
        if (!ClientEditorClipboard.isEmpty()) {
            items.add(MenuItem.of("Paste here", () -> book.pasteAt(x, y)));
        }
        items.add(MenuItem.of("Select all in chapter", book::selectAllInChapter));
        return items;
    }

    /** What the four kinds are called in a menu, and the type name each writes. */
    private static final List<String[]> ELEMENT_KINDS = List.of(
            new String[] {CanvasElement.TYPE_IMAGE, "image"},
            new String[] {CanvasElement.TYPE_TEXT, "text"},
            new String[] {CanvasElement.TYPE_RECT, "box"},
            new String[] {CanvasElement.TYPE_LINE, "line"});

    /**
     * Inserts one new element of a type at a canvas position.
     *
     * <p>The tree is built here rather than sent as a position and a type, because the server's job is to
     * place what it is given and choose an id -- see {@link EditorOp.InsertElement} for why the tree travels
     * whole. What the tree says about the element's <i>appearance</i> is {@code ElementDefaults}' business,
     * including the one decision that matters: a new element is visible, where the codec's own default for a
     * colour is a no-op.
     *
     * <p><b>The id is chosen here too, and that is what makes the insert optimistic.</b> The server prefers
     * the id it is given and only suffixes it when the chapter has one already, so a client that checks the
     * same thing is right almost always -- and {@code ElementDraft} believes the element until the tree
     * either confirms it or the backstop runs out.
     */
    private void addElement(String type, double x, double y) {
        com.google.gson.JsonObject tree = dev.ellipog.tenet.client.dev.ElementDefaults.tree(type,
                (int) Math.round(x), (int) Math.round(y));
        if (tree == null) {
            return;
        }
        int index = book.elementInsertIndex();
        CanvasElement made = book.placeElement(tree.deepCopy());
        if (made == null) {
            return;
        }
        QuestBookScreen.elementDraft.insert(QuestBookScreen.effectiveChapter(), made, index, Util.getMillis());
        book.send(new EditorOp.InsertElement(index, tree));
    }

    /** What a canvas element offers: a copy of it, and its removal. */
    private List<MenuItem> elementMenuItems(String id) {
        QuestBookScreen.ElementSlot slot = book.elementSlot(id);
        List<MenuItem> items = new ArrayList<>();
        if (slot != null) {
            items.add(MenuItem.of("Duplicate \"" + elementLabel(slot.element()) + "\"",
                    () -> duplicateElement(slot.element())));
        }
        boolean armed = menuDeleteArmed;
        items.add(MenuItem.destructive(
                armed ? "Really delete?" : "Delete element",
                armed
                        ? () -> book.deleteElement(id)
                        : () -> {
                            // The two-press arm every destructive control in this editor has, and here it
                            // carries the weight it does everywhere else: an element owns no file, so the
                            // undo history is the whole of the recovery -- see `RemoveElement`.
                            menuDeleteArmed = true;
                            canvasMenuElement = id;
                            book.openMenu(elementMenuItems(id));
                        }));
        return items;
    }

    /** A short name for an element in a menu row: its own label, or its type when it has no words. */
    private static String elementLabel(CanvasElement element) {
        return switch (element) {
            case CanvasElement.Text text -> text.text().value();
            case CanvasElement.Image image -> image.title().map(title -> title.value()).orElse(element.id());
            default -> element.id();
        };
    }

    /** A copy of one element, under a fresh id this client chooses so the copy appears at once. */
    private void duplicateElement(CanvasElement element) {
        com.google.gson.JsonObject tree = CanvasElement.asJson(element);
        int index = book.elementInsertIndex();
        CanvasElement made = book.placeElement(tree);
        if (made == null) {
            return;
        }
        QuestBookScreen.elementDraft.insert(QuestBookScreen.effectiveChapter(), made, index, Util.getMillis());
        book.send(new EditorOp.InsertElement(index, tree));
    }

    /**
     * What a dependency line offers: its form, its arrows, its pattern, its weight, and its removal.
     *
     * <p>Each flyout is one panel of previews: a route is recognised by seeing it, and the three arrow
     * axes share one flyout — one level of flyout, never two — so tuning a combination is a single visit
     * rather than a hover-chain that closes when the hand strays a pixel. A cell press keeps the panel
     * open and rebuilds it in place; the way out is the pointer leaving, Escape, or a choice like
     * "Join handles" that closes with the click.
     */
    private List<MenuItem> lineMenuItems(String from, String to) {
        ClientQuestCache.Entry dependent = book.entryFor(to);
        DependencyStyle now = dependent == null ? DependencyStyle.UNSET : QuestBookScreen.lineStyle(dependent, from);
        String suffix = " \u2192 " + to;
        List<MenuItem> items = new ArrayList<>();
        // The four style flyouts are Advanced's, and they are the plainest case of the cut in the whole
        // editor: a route, its arrow heads, its pattern and its weight are four answers to "how exactly
        // should this line be drawn", where the line's existence is the basic fact. What Normal keeps is
        // the row below this -- removing the dependency -- so a line the author cannot restyle is still a
        // line they can take away.
        if (dev.ellipog.tenet.client.dev.Advanced.on()) {
            items.add(MenuItem.parent("Form \u203a", formRows(from, to, now)));
            items.add(MenuItem.parent("Arrows \u203a", arrowRows(from, to, now)));
            items.add(MenuItem.parent("Line \u203a", patternRows(from, to, now)));
            items.add(MenuItem.parent("Weight \u203a", weightRows(from, to, now)));
        }
        boolean armed = menuDeleteArmed;
        items.add(MenuItem.destructive(
                armed ? "Really delete?" + suffix : "Delete dependency" + suffix,
                armed
                        ? () -> book.bookRewards.removeDependency(to, from)
                        : () -> {
                            menuDeleteArmed = true;
                            canvasMenuFrom = from;
                            canvasMenuTo = to;
                            book.openMenu(lineMenuItems(from, to));
                        }));
        return items;
    }

    /** The Form flyout: a preview per route, the handle toggle, and the axis reset. */
    private List<MenuFlyout.Row> formRows(String from, String to, DependencyStyle now) {
        List<MenuFlyout.Cell> cells = new ArrayList<>();
        for (DependencyStyle.Form form : DependencyStyle.Form.values()) {
            cells.add(new MenuFlyout.Cell(BookRewards.formLabel(form),
                    () -> book.bookRewards.setLineAxis(from, to, "form", form.wire()),
                    now.formOr(DependencyStyle.Form.CHAMFERED) == form,
                    new MenuFlyout.Preview.Form(form)));
        }
        List<MenuFlyout.Row> rows = new ArrayList<>();
        rows.add(new MenuFlyout.Row.Cells(Labels.of("tenet.dev.line.form"), cells,
                    reset(from, to, "form"), true));
        if (QuestBookScreen.isSplit(now)) {
            rows.add(MenuFlyout.text(Labels.of("tenet.dev.line.join_handles"),
                    () -> book.bookRewards.joinHandles(from, to)));
        }
        else if (now.formOr(DependencyStyle.Form.CHAMFERED) == DependencyStyle.Form.CURVED) {
            // Only a curve: its two control points reproduce the bow exactly, so the offer is made only
            // where it keeps the shape.
            rows.add(MenuFlyout.text(Labels.of("tenet.dev.line.split_handles"),
                    () -> book.bookRewards.splitHandles(from, to, now)));
        }
        return rows;
    }

    /**
     * The Arrows flyout: one row per arrow axis, in one panel.
     *
     * <p>The old menu kept the glyph and its placement in one axis and offered a text list; the split
     * axes live together here rather than in nested flyouts. A row whose meaning another axis has
     * switched off — placement and density under a blunt end, density when the heads are not a stream —
     * is drawn faint rather than hidden, so the panel does not change shape under the pointer.
     */
    private List<MenuFlyout.Row> arrowRows(String from, String to, DependencyStyle now) {
        DependencyStyle.ArrowHead head = now.headOr(DependencyStyle.ArrowHead.CHEVRON);
        DependencyStyle.ArrowPlace place = now.placeOr(DependencyStyle.ArrowPlace.TARGET);
        List<MenuFlyout.Cell> heads = new ArrayList<>();
        for (DependencyStyle.ArrowHead value : DependencyStyle.ArrowHead.values()) {
            heads.add(new MenuFlyout.Cell(BookRewards.headLabel(value),
                    () -> book.bookRewards.setLineAxis(from, to, "arrowHead", value.wire()), head == value,
                    new MenuFlyout.Preview.Head(value)));
        }
        List<MenuFlyout.Cell> places = new ArrayList<>();
        for (DependencyStyle.ArrowPlace value : DependencyStyle.ArrowPlace.values()) {
            places.add(new MenuFlyout.Cell(BookRewards.placeLabel(value),
                    () -> book.bookRewards.setLineAxis(from, to, "arrowPlace", value.wire()), place == value,
                    new MenuFlyout.Preview.Place(value)));
        }
        List<MenuFlyout.Cell> densities = new ArrayList<>();
        for (DependencyStyle.ArrowDensity value : DependencyStyle.ArrowDensity.values()) {
            densities.add(new MenuFlyout.Cell(BookRewards.densityLabel(value),
                    () -> book.bookRewards.setLineAxis(from, to, "arrowDensity", value.wire()),
                    now.densityOr(DependencyStyle.ArrowDensity.MEDIUM) == value,
                    new MenuFlyout.Preview.Density(value)));
        }
        List<MenuFlyout.Row> rows = new ArrayList<>();
        rows.add(new MenuFlyout.Row.Cells(Labels.of("tenet.dev.line.head"), heads,
                    reset(from, to, "arrowHead"), true));
        rows.add(new MenuFlyout.Row.Cells(Labels.of("tenet.dev.line.place"), places,
                    reset(from, to, "arrowPlace"),
                head != DependencyStyle.ArrowHead.NONE));
        rows.add(new MenuFlyout.Row.Cells(Labels.of("tenet.dev.line.density"), densities,
                    reset(from, to, "arrowDensity"),
                head != DependencyStyle.ArrowHead.NONE && place == DependencyStyle.ArrowPlace.STREAM));
        return rows;
    }

    /** The Line flyout: a preview per pattern. */
    private List<MenuFlyout.Row> patternRows(String from, String to, DependencyStyle now) {
        DependencyStyle.Dash current = now.dashOr(DependencyStyle.Dash.SOLID);
        List<MenuFlyout.Cell> cells = new ArrayList<>();
        for (DependencyStyle.Dash pattern : DependencyStyle.Dash.values()) {
            cells.add(new MenuFlyout.Cell(BookRewards.patternLabel(pattern),
                    () -> book.bookRewards.setLineAxis(from, to, "dash", pattern.wire()), current == pattern,
                    new MenuFlyout.Preview.Pattern(pattern)));
        }
        return List.of(new MenuFlyout.Row.Cells(Labels.of("tenet.dev.line.pattern"), cells,
                    reset(from, to, "dash"), true));
    }

    /** The Weight flyout: a preview per thickness, faint while the pattern ignores the weight axis. */
    private List<MenuFlyout.Row> weightRows(String from, String to, DependencyStyle now) {
        DependencyStyle.Weight current = now.weightOr(DependencyStyle.Weight.THIN);
        List<MenuFlyout.Cell> cells = new ArrayList<>();
        for (DependencyStyle.Weight weight : DependencyStyle.Weight.values()) {
            cells.add(new MenuFlyout.Cell(BookRewards.weightLabel(weight),
                    () -> book.bookRewards.setLineAxis(from, to, "weight", weight.wire()), current == weight,
                    new MenuFlyout.Preview.Weight(weight)));
        }
        return List.of(new MenuFlyout.Row.Cells(Labels.of("tenet.dev.line.weight"), cells,
                    reset(from, to, "weight"),
                now.dashOr(DependencyStyle.Dash.SOLID) != DependencyStyle.Dash.DOUBLE));
    }

    /** The reset chip's action, or null when the line already stands on the chapter's default. */
    private Runnable reset(String from, String to, String axis) {
        return overrides(from, to, axis) ? () -> book.bookRewards.clearLineAxis(from, to, axis) : null;
    }

    /**
     * Whether this line's own override names the axis.
     *
     * <p>The legacy arrows axis stands in for all three current arrow axes: a line written before the
     * split genuinely overrides its arrows, and a reset offered on it must clear the old spelling too,
     * or clearing one new axis would leave the old value in force.
     */
    private boolean overrides(String from, String to, String axis) {
        JsonObject entry = lineEntry(from, to);
        return entry.has(axis)
                || (DependencyStyle.isArrowAxis(axis)
                && entry.has(DependencyStyle.LEGACY_ARROWS_FIELD));
    }

    /** One line's override object, or an empty one when it has none. */
    private JsonObject lineEntry(String from, String to) {
        ClientQuestCache.Entry dependent = book.entryFor(to);
        if (dependent == null) {
            return new JsonObject();
        }
        JsonObject lines = BookRewards.dependencyLinesOf(dependent);
        return lines.has(from) && lines.get(from).isJsonObject()
                ? lines.getAsJsonObject(from) : new JsonObject();
    }

    /**
     * Asks the server to clear one or more quests' progress, for playtesting.
     *
     * <p>One packet per quest rather than one carrying a list: the server resolves each id against
     * its own index, so a client naming a list would be a client deciding what it clears, while a
     * client naming one quest at a time can only ask for quests it could have pressed a button
     * for. No pending mark — a reset needs no idempotency visual, because pressing it twice
     * clears the same thing rather than granting it twice.
     */
    private void resetQuests(List<String> questIds) {
        for (String questId : questIds) {
            ArmatureNetwork.sendToServer(new ResetQuestPayload(questId));
            Constants.LOG.debug("asked the server to clear the progress of {}", questId);
        }
    }

    /**
     * Asks the server to mark quests complete, for playtesting.
     *
     * <p>One packet per quest rather than one carrying a list, on Reset's terms: the server
     * resolves each id against its own index, so a client naming one quest at a time can only
     * ask for quests it could have pressed a row for. The press names the quest and nothing
     * else — no progress crosses — so there is no new client-assertable path past
     * {@code SubmitTaskPayload}'s rule.
     */
    private void completeQuests(List<String> questIds) {
        for (String questId : questIds) {
            ArmatureNetwork.sendToServer(new CompleteQuestPayload(questId, false));
            Constants.LOG.debug("asked the server to complete {}", questId);
        }
    }

    /**
     * Asks the server to mark one quest complete with everything it waits on, for playtesting.
     *
     * <p>The chain is the server's to walk: the flag asks for the command's
     * {@code with-dependencies} sweep, and the guards still refuse locked and gated quests with
     * the command's own messages rather than stopping the sweep.
     */
    private void completeQuestWithDependencies(String questId) {
        ArmatureNetwork.sendToServer(new CompleteQuestPayload(questId, true));
        Constants.LOG.debug("asked the server to complete {} with its dependencies", questId);
    }

    /**
     * Asks the server to clear one chapter's progress, for playtesting.
     *
     * <p>The chapter travels as a scope, like the claim menu's banner: which of its quests are
     * held stays the server's to decide.
     */
    private void resetChapter(String chapterId) {
        ArmatureNetwork.sendToServer(new ResetChapterPayload(chapterId));
        Constants.LOG.debug("asked the server to clear the progress of chapter {}", chapterId);
    }

    /** Asks the server to clear the whole book's progress, for playtesting. */
    private void resetAll() {
        ArmatureNetwork.sendToServer(new ResetAllPayload());
        Constants.LOG.debug("asked the server to clear the whole book's progress");
    }
}
