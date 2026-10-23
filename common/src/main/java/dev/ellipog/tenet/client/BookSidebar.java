package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ArmatureButton;
import dev.ellipog.armature.client.ArmatureTextField;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.ui.Theme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.ScrollView;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tenet.editor.EditorOp;
import dev.ellipog.tenet.quest.ChapterNaming;
import dev.ellipog.tenet.client.dev.ChapterBar;
import dev.ellipog.tenet.client.dev.RewardBadge;
import dev.ellipog.tenet.client.dev.SidebarDrag;
import dev.ellipog.tenet.progress.QuestState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The quest book's sidebar: the outline and what a click on a row does, the tree drag, naming a
 * chapter or group in place or on the naming card, and the toolbar.
 *
 * <p>Moved out of {@link QuestBookScreen} as it stood, with the state only it reads; the screen
 * keeps the state it shares and reaches this through its {@code bookSidebar} field.
 */
final class BookSidebar {

    private final QuestBookScreen book;

    BookSidebar(QuestBookScreen book) {
        this.book = book;
    }

    private static boolean sidebarEditing;

    /** The cache the sidebar tooltips were last built from; its identity is the revision check. */
    private QuestBookScreen.ChapterCounts tooltippedCounts;

    ArmatureTextField namingTitle;
    private ArmatureTextField namingId;

    /** Set when a session starts, so the title field takes focus on the build that places it. */
    private boolean inlineFocusPending;

    /** The synthetic sidebar rows one inline session splices into the list. */
    private static final String INLINE_EDIT_KEY = SidebarLayout.INLINE_PREFIX + "edit";
    private static final String INLINE_ID_KEY = SidebarLayout.INLINE_PREFIX + "id";

    // ------------------------------------------------------------------
    // The sidebar's outline, and what a click on a row does
    // ------------------------------------------------------------------

    /**
     * The sidebar's outline: the group headings, the chapters under them, and which the player has open.
     *
     * <h2>Rebuilt when a tree arrives, and never when a screen merely reads one</h2>
     *
     * <p>Keyed on {@link ClientQuestCache#treeRevision()}, which moves when a tree arrives and when the
     * cache is cleared, and not when a screen reads one. So a resize, a scroll, a toggle and a redraw all
     * find the same outline — with the player's collapses still in it — and a reload builds a new one
     * whose groups are open or closed as their files say.
     *
     * <p>That is the whole reason the revision exists rather than this comparing the cache's entries.
     * Comparing contents would answer the same question a second way, and the two answers would disagree
     * about one real case: a reload that re-sent an identical tree. "A tree arrived" is a fact; "the tree
     * is different" is a judgement, and it is the wrong one for the authored defaults to hang on.
     *
     * <p>The outline is <b>static</b>, unlike the scroll view beside it, and the difference is what each
     * describes. A scroll view describes a window — it holds widgets, and a widget belongs to one screen.
     * An outline describes a questline: the same tree drawn twice should be one outline, or closing and
     * reopening the book would discard every collapse the player had made. {@link QuestBookScreen#forgetViewState()}
     * clears it on a disconnect, alongside the selection and the pan, because the collapses are a fact
     * about a server this client is no longer connected to.
     */
    static SidebarLayout sidebar() {
        if (sidebarStale()) {
            BookScreenState.sidebar = buildSidebar();
            BookScreenState.sidebarRevision = ClientQuestCache.treeRevision();
            BookScreenState.sidebarProgress = ClientQuestCache.progressRevision();
            sidebarEditing = BookCanvas.editingView;
            BookScreenState.sidebarText = ClientQuestCache.textRevision();
        }
        return BookScreenState.sidebar;
    }

    /**
     * Whether the outline the client holds is describing something other than what it now has.
     *
     * <h2>One predicate, because two callers have to agree</h2>
     *
     * <p>Both the builder above and the frame that rebuilds the sidebar's <b>widgets</b> ask this, and
     * they have to ask the same question: a widget list rebuilt on a different answer from the outline
     * it draws is a sidebar whose rows and whose click targets disagree. It was two comparisons once,
     * and they had already drifted — the builder watched the tree, the progress and the editing flag,
     * while the widget check watched only the tree, so a chapter appearing because its gate opened
     * rebuilt the outline and left the widgets behind it.
     *
     * <p>The four inputs, and each is a different reason for a row to change:
     *
     * <ul>
     *   <li>the <b>tree</b>: a reload can add, remove or rename a chapter, and can move one group to
     *       another heading.</li>
     *   <li>the <b>progress</b>: a chapter leaves the sidebar the moment the gate it was hiding behind
     *       is met, so which chapters are rows at all depends on it.</li>
     *   <li>the <b>editing flag</b>: an author sees every chapter, a reader only the revealed ones.</li>
     *   <li>the <b>language</b>: the rows carry chapter and group names, and a locale arriving changes
     *       those words without changing anything about which rows there are.</li>
     * </ul>
     */
    static boolean sidebarStale() {
        if (BookScreenState.sidebar == null
                || BookScreenState.sidebarRevision != ClientQuestCache.treeRevision()
                || sidebarEditing != BookCanvas.editingView
                || BookScreenState.sidebarText != ClientQuestCache.textRevision()) {
            return true;
        }
        long progress = ClientQuestCache.progressRevision();
        if (BookScreenState.sidebarProgress == progress) {
            return false;
        }
        for (SidebarLayout.ChapterRow row : BookScreenState.sidebarChapters) {
            if (row.locked() != chapterLocked(row.id()) || row.hidden() != BookContent.chapterHidden(row.id())) {
                return true;
            }
        }
        BookScreenState.sidebarProgress = progress;
        return false;
    }

    /**
     * Builds an outline from what the server sent.
     *
     * <h2>Two lists, and where each comes from</h2>
     *
     * <p>The headings come from {@link ClientQuestCache#groups()}, which the server sends explicitly —
     * so a group with no chapters is still a heading, and the rows say so. The chapters come from
     * {@link ClientQuestCache#chapters()} when the server sent that list, which every server since the
     * tree's version 3 does: it carries every chapter, <b>including one with no quests</b>, which is
     * what makes a newly created chapter visible instead of invisible until somebody writes a quest
     * into it.
     *
     * <p>When that list is empty — an older server — the chapters are derived from the quest entries
     * exactly as they always were, deduped by chapter id while keeping the order of first sighting. So
     * absence is a fallback rather than a special case, and the two sources are merged rather than
     * switched between: a quest naming a chapter the list somehow missed still produces a row, because
     * losing the row would lose the quests under it.
     *
     * <p>A server older than groups sends no headings at all, so every chapter arrives with an empty
     * group id — which {@link SidebarLayout} reads as "no group" and adds as a root. That draws exactly
     * the flat chapter list this screen had before, which is why there is no branch here for the old
     * case and nothing to notice at.
     */
    private static SidebarLayout buildSidebar() {
        List<SidebarLayout.Group> groups = new ArrayList<>();
        for (ClientQuestCache.GroupEntry group : ClientQuestCache.groups()) {
            groups.add(new SidebarLayout.Group(group.id(), group.titleText(), group.collapsedByDefault()));
        }

        Map<String, SidebarLayout.ChapterRow> chapters = new LinkedHashMap<>();
        for (ClientQuestCache.ChapterEntry chapter : ClientQuestCache.chapters()) {
            chapters.putIfAbsent(chapter.id(), new SidebarLayout.ChapterRow(
                    chapter.id(), chapter.titleText(), chapter.groupId(),
                    chapterLocked(chapter.id()), BookContent.chapterHidden(chapter.id())));
        }
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            chapters.putIfAbsent(entry.chapterId(), new SidebarLayout.ChapterRow(
                    entry.chapterId(), entry.chapterTitleText(), entry.chapterGroupId(),
                    chapterLocked(entry.chapterId()), BookContent.chapterHidden(entry.chapterId())));
        }

        BookScreenState.sidebarChapters = List.copyOf(chapters.values());
        SidebarLayout layout = SidebarLayout.of(groups, BookScreenState.sidebarChapters);
        // The player's own expansions, over the authored defaults: this rebuild is what used to throw
        // them away. And the memory is pruned to the groups that still exist here, so a renamed or
        // deleted group's key does not accumulate for the life of the session.
        layout.applyExpansion(BookScreenState.sidebarExpansion);
        BookScreenState.sidebarExpansion.keySet().retainAll(layout.groupKeys());
        // The group holding the open chapter starts open, whatever the author's default: a list whose
        // current row is folded away does not say where the reader is. A group the player closed
        // themselves stays closed.
        String openGroup = SidebarLayout.groupKey(groupOfChapter(QuestBookScreen.effectiveChapter()));
        if (!BookScreenState.sidebarExpansion.containsKey(openGroup) && layout.groupKeys().contains(openGroup)) {
            layout.applyExpansion(Map.of(openGroup, true));
        }
        return layout;
    }

    /**
     * One sidebar row's icon: the stack to draw, the id it was resolved from, the texture path
     * when the row wears a texture icon, and the atlas region when it wears a sprite.
     *
     * <p>The quartet, for the same reason {@link ClientQuestCache.Entry} keeps it: a stack that failed to
     * resolve with an id behind it is a <b>missing item</b>, which the row can say on hover, while an
     * empty id is simply no icon, which it cannot. One is a broken pack worth chasing and the other is
     * a chapter that never declared one. A texture draws through the button's blit rather than its
     * stack, so the id stays empty for one and no missing branch fires for a picture.
     */
    record SidebarIcon(ItemStack stack, String id, String texture, String sprite) {

        /** The button's texture, or null when the row wears an item, a sprite or nothing. */
        net.minecraft.resources.Identifier textureId() {
            if (texture == null || texture.isEmpty()) {
                return null;
            }
            return net.minecraft.resources.Identifier.tryParse(texture);
        }

        /** The button's atlas region, or null when the row wears an item, a texture or nothing. */
        net.minecraft.resources.Identifier spriteId() {
            if (sprite == null || sprite.isEmpty()) {
                return null;
            }
            return net.minecraft.resources.Identifier.tryParse(sprite);
        }
    }

    /**
     * The icon each sidebar row carries, keyed by the row's own key.
     *
     * <h2>Why a map beside the layout rather than a field on its rows</h2>
     *
     * <p>Because {@link SidebarLayout} is deliberately game-free -- it holds strings and numbers, and
     * its tests build rows without a client -- and an {@code ItemStack} on a row would end that. The key
     * is already the join between a row and everything the drawing needs, so the icons ride the same
     * join as a second map, resolved here where the cache and the registry are both in reach.
     *
     * <p>A group with no authored icon borrows the first chapter under it: the chapters are what the
     * group is made of, and a heading that shows something is easier to find than one that shows
     * nothing. "First" is the entries' declaration order, which is the order the sidebar draws them in.
     */
    static Map<String, SidebarIcon> sidebarIcons() {
        Map<String, SidebarIcon> icons = new LinkedHashMap<>();
        Map<String, SidebarIcon> firstChapter = new LinkedHashMap<>();
        // The explicit chapter list first, when the server sent one: it has an icon for every chapter,
        // including a chapter with no quests -- which is the one row that cannot borrow one from a quest.
        for (ClientQuestCache.ChapterEntry chapter : ClientQuestCache.chapters()) {
            SidebarIcon icon = new SidebarIcon(chapter.icon(), chapter.iconId(), chapter.textureIcon(),
                    chapter.spriteIcon());
            if (!chapter.groupId().isEmpty()) {
                firstChapter.putIfAbsent(chapter.groupId(), icon);
            }
            icons.putIfAbsent(SidebarLayout.chapterKey(chapter.id()), icon);
        }
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            SidebarIcon icon = new SidebarIcon(entry.chapterIcon(), entry.chapterIconId(),
                    entry.chapterTextureIcon(), entry.chapterSpriteIcon());
            if (!entry.chapterGroupId().isEmpty()) {
                firstChapter.putIfAbsent(entry.chapterGroupId(), icon);
            }
            icons.putIfAbsent(SidebarLayout.chapterKey(entry.chapterId()), icon);
        }
        for (ClientQuestCache.GroupEntry group : ClientQuestCache.groups()) {
            SidebarIcon authored =
                    new SidebarIcon(group.icon(), group.iconId(), group.textureIcon(),
                            group.spriteIcon());
            SidebarIcon shown = authored.id().isEmpty()
                    ? firstChapter.getOrDefault(group.id(), authored) : authored;
            icons.put(SidebarLayout.groupKey(group.id()), shown);
        }
        return icons;
    }

    /**
     * What a click on a sidebar row does.
     *
     * <p>A heading is toggled and a chapter is selected, and the two are told apart by the row's
     * <b>key</b> rather than by a flag on the button — see {@link SidebarLayout} on why a group and a
     * chapter share one namespace and are kept apart by a prefix.
     *
     * <p>A toggle that changed nothing does <b>not</b> rebuild, and that is the reason
     * {@link SidebarLayout#toggle} answers a boolean rather than being void. A heading with no chapters
     * under it is drawn without a chevron and is still clickable — the whole row is the target, which is
     * the point of chevroning the label rather than adding a second control beside it — so pressing it
     * is a real thing a player can do. Rebuilding every button for a press that meant nothing costs a
     * frame and can lose focus.
     */
    void pressSidebarRow(String key) {
        if (SidebarLayout.isGroupKey(key)) {
            if (sidebar().toggle(key)) {
                // Remembered, because the next tree -- which any structural edit produces -- rebuilds
                // this outline, and a group that shut itself after every drop is the report this answers.
                BookScreenState.sidebarExpansion.put(key, sidebar().isExpanded(key));
                book.rebuildWidgets();
            }
            return;
        }

        selectChapter(SidebarLayout.idOf(key));
        book.rebuildWidgets();
    }

    /**
     * Switches to a chapter, closing the selection with it.
     *
     * <p>The selection belongs to the chapter being left -- the primary the panel edits <i>and</i> the
     * multi-selection the canvas gestures act on, which is the half the first version of this forgot.
     * Leaving it open would show a quest that is not on screen, with a Submit button, for a chapter you
     * have walked away from; and the set was worse, because Delete, Ctrl+D, the drag and Copy read
     * {@link QuestBookScreen#selection()} rather than the canvas, so they acted on quests in the chapter just left.
     *
     * <p>Three paths change chapters -- the sidebar, a row's menu and the naming overlay -- and all
     * three ask here, so the rule cannot drift between them. The quest card's own target goes with the
     * selection, which is the same rule one field further out: it is the id the <i>card</i> is open on,
     * and {@link QuestBookScreen#editTarget} prefers it over the selection.
     */
    void selectChapter(String id) {
        if (BookContent.chapterHidden(id)) {
            // The sidebar has no row for a withheld chapter, so this is reached only by the paths that
            // name a chapter directly -- a dependency row's jump, the reward inbox, a rename landing. Each
            // of those is a way for a reader to arrive somewhere the book does not show, and the refusal
            // belongs here rather than in five callers.
            return;
        }
        boolean moved = !java.util.Objects.equals(id, BookScreenState.selectedChapter);
        BookScreenState.selectedChapter = id;
        BookScreenState.frameChapterActive = false;
        BookScreenState.selectedQuest = null;
        BookScreenState.multiSelection.clear();
        if (moved) {
            // The third place an id from the chapter being left survives. Only on a real move: a press on
            // the chapter already open is a re-select rather than a switch, and dropping the card's target
            // there would blank a panel still showing the right quest. The jump paths already ask this
            // question before calling -- see `revealNode`.
            book.bookState.overlayQuest = null;
        }
        BookScreenState.centred = false;
        // A drag's preview belongs to the chapter being left: it is not keyed, and a stale patch would be
        // composed over the next chapter's theme until that chapter's own first edit replaced it.
        BookScreenState.pendingChapterTheme = null;
    }

    /**
     * Scrolls the sidebar by a screen-space delta, snapped so a row is never left half past the top.
     *
     * <h2>Why the snapping is here rather than in the viewport</h2>
     *
     * <p>Because it is this list's decision and not the kit's. A viewport scrolls by pixels, because that
     * is what a pan-and-zoom canvas wants; a list of rows wants to stop with a row's top edge against the
     * list's top edge, and a list left a third of a row out of position reads as a drawing fault rather
     * than as a scroll. So every offset this produces is a whole multiple of
     * {@link SidebarLayout#pitch()}.
     *
     * <p>That pitch is derived from the same two numbers the rows are spaced with, which is the point:
     * a scroll rate written out at the call site drifts against the spacing by a couple of pixels a
     * notch, and the symptom arrives slowly — a row that ends up half under the header after a while,
     * with nothing in the code looking wrong.
     *
     * <p>A delta too small to move a whole row still moves one, in the direction asked for. The
     * alternative is a trackpad whose small deltas each round back to where they started, which reads as
     * the wheel being broken; one row per notch is what every other list does.
     */
    void scrollSidebar(int dy) {
        if (BookScreenState.sidebar == null || dy == 0) {
            return;
        }

        int pitch = SidebarLayout.pitch();
        int current = book.bookState.sidebarView.viewport().scrollY();
        int wanted = current + dy;
        int snapped = Math.round(wanted / (float) pitch) * pitch;
        if (snapped == current) {
            snapped = current + (dy > 0 ? pitch : -pitch);
        }
        book.bookState.sidebarView.scrollTo(snapped);
    }

    /** Whether a chapter's gate is unmet, so the row is drawn shut rather than removed. */
    private static boolean chapterLocked(String chapterId) {
        return chapterId != null && ClientQuestCache.chapterStateOf(chapterId) == QuestState.LOCKED;
    }

    /**
     * What a shut chapter is waiting for: the chapters by name, and how many of them are satisfied.
     *
     * <p>Read from the chapter's own rule and the server's states rather than derived from progress here:
     * the bar a dependency has to reach is {@link QuestState#bar}, which is the same answer the engine and
     * the quest canvas use, so a chapter gate and a quest dependency cannot disagree about what
     * "satisfied" means.
     *
     * <p>A dependency naming a chapter this client has no title for is named by its id, which is the
     * honest thing to draw: the author knows what it is, and a row that said nothing at all would look
     * like a chapter with no gate.
     */
    private static Component chapterGateLine(String chapterId) {
        ClientQuestCache.ChapterEntry chapter = BookContent.chapterEntryFor(chapterId);
        if (chapter == null || !chapter.waits()) {
            return null;
        }
        QuestState bar = QuestState.bar(chapter.prerequisiteMode());
        List<String> names = new ArrayList<>();
        int met = 0;
        for (String dependency : chapter.dependsOn()) {
            if (ClientQuestCache.chapterStateOf(dependency).isAtLeast(bar)) {
                met++;
            }
            ClientQuestCache.ChapterEntry target = BookContent.chapterEntryFor(dependency);
            names.add(target == null ? dependency : target.titleText());
        }
        return Component.translatable("tenet.screen.chapter_needs",
                String.join(", ", names), met, chapter.dependsOn().size());
    }

    /**
     * The insertion line: two pixels at the gap the pointer names, spanning the rows' own width.
     *
     * <p>Drawn inside the caller's clip and skipped when the gap is off it, so a drag past the list's
     * edge does not paint a line over a header or into the canvas.
     */
    // ------------------------------------------------------------------
    // The sidebar tree drag
    // ------------------------------------------------------------------

    /**
     * Reads a press that landed on a sidebar row, without consuming it.
     *
     * <p>Before the widget pass, because a row is a button and a button takes the press it is given: the
     * screen would never see it again, and a drag that cannot start is a click. Not consuming it is the
     * other half: if the pointer never travels the row is still clicked, and the button's own release is
     * what does that, exactly as before this gesture existed.
     */
    void rememberSidebarPress(double mouseX, double mouseY, int button) {
        // The row drag is armed for an author, and nothing takes it away any more: a panel used to make the
        // whole book inert, and now a rail covers nothing the sidebar was using.
        if (button != InputConstants.MOUSE_BUTTON_LEFT || !book.mayEditNow()
                || !book.sidebarViewport().containsScreen(mouseX, mouseY)) {
            return;
        }
        for (SidebarLayout.Row row : sidebar().rows()) {
            if (SidebarLayout.isInlineKey(row.key())) {
                // The edit in flight is not draggable: a drag is a row moving, and this row is
                // a field being typed in.
                continue;
            }
            BookGeometry.Rect rect = placedRect(row.key());
            if (rect != null && rect.contains(mouseX, mouseY)) {
                book.bookState.sidebarDragKey = row.key();
                book.bookState.sidebarDragGroup = row.group();
                book.bookState.sidebarDragLive = false;
                book.bookState.sidebarDragPointerY = mouseY;
                book.bookState.sidebarDragPressX = mouseX;
                book.bookState.sidebarDragPressY = mouseY;
                book.bookState.sidebarDrop = null;
                return;
            }
        }
    }

    /** The visible rows as the drop arithmetic wants them: key, kind, container and where they are. */
    List<SidebarDrag.Row> sidebarRows() {
        List<SidebarDrag.Row> rows = new ArrayList<>();
        String heading = "";
        for (SidebarLayout.Row row : sidebar().rows()) {
            if (SidebarLayout.isInlineKey(row.key())) {
                // Not a row the tree can be dropped on: the edit is not a destination.
                continue;
            }
            if (row.group()) {
                heading = row.key();
            }
            BookGeometry.Rect rect = placedRect(row.key());
            if (rect == null) {
                continue;
            }
            // The true container, from the layout rather than from a walk back to the nearest heading:
            // a chapter at depth 0 is a root chapter wherever it is drawn, and every group is drawn
            // before the root chapters -- so the nearest heading above one is not its parent, which is
            // what made a drop on the last group read as a no-op and send nothing.
            String container = row.group() || row.depth() == 0 ? "" : heading;
            rows.add(new SidebarDrag.Row(row.key(), row.group(), container, rect));
        }
        return rows;
    }

    /**
     * Where a row is on screen, from the view that placed it.
     *
     * <p>From the {@code ScrollView} rather than from the layout, because the rows scroll: the slot the
     * view holds is the one the widget was moved to this frame, which is the only rectangle a pointer
     * can be tested against. Null for a row scrolled out of view, which is a row nothing can be dropped
     * on — and it is absent from the list rather than clamped, so a drop at an edge lands where the
     * rows actually are.
     */
    BookGeometry.Rect placedRect(String key) {
        var slot = book.bookState.sidebarView.placedSlot(key);
        return slot == null ? null
                : BookGeometry.Rect.at(slot.x(), slot.y(), slot.width(), slot.height());
    }

    /**
     * Sends the op a released tree drag asked for.
     *
     * <p>Everything that could make the drop meaningless is decided here, once: a drop that changes
     * nothing sends nothing (the server would rewrite a manifest to say the same thing and broadcast
     * the tree for it), and a group only ever moves at the root — {@link SidebarDrag} refuses the
     * nesting, and this refuses to send one.
     */
    void commitSidebarDrop(String key, boolean group, SidebarDrag.Drop drop) {
        if (!book.mayEditNow() || drop == null || drop instanceof SidebarDrag.Drop.Nowhere) {
            return;
        }
        List<SidebarDrag.Row> rows = sidebarRows();
        if (SidebarDrag.isNoOp(rows, key, drop)) {
            return;
        }
        String id = SidebarLayout.idOf(key);
        if (group) {
            if (drop instanceof SidebarDrag.Drop.Insert insert) {
                book.send(new EditorOp.MoveGroup(id, insert.index()));
            }
            return;
        }
        switch (drop) {
            case SidebarDrag.Drop.Insert insert -> book.send(new EditorOp.MoveChapter(id,
                    SidebarLayout.isGroupKey(insert.containerKey())
                            ? SidebarLayout.idOf(insert.containerKey()) : "",
                    insert.index()));
            case SidebarDrag.Drop.Into into -> book.send(new EditorOp.MoveChapter(id,
                    SidebarLayout.idOf(into.groupKey()),
                    // Into a group takes the end of its list, which is what the highlight promises: the
                    // heading has no seam to aim at, and "somewhere in this group" is the answer.
                    SidebarDrag.childCount(rows, into.groupKey())));
            case SidebarDrag.Drop.Nowhere ignored -> {
            }
        }
    }

    /**
     * Draws what the drag is about to do: a seam line at an insert, a ring around a group it is over.
     *
     * <p>Inside the list's clip, after the widget pass, so it is above the rows it describes and cannot
     * reach the header. The two marks are the two answers, and they are different shapes on purpose: a
     * line says "between these", a ring says "inside this", and drawing both the same way would leave
     * the reader to guess which of the two places the row is going.
     */
    void drawSidebarDrag(GuiRenderer r) {
        if (!book.bookState.sidebarDragLive || book.bookState.sidebarDrop == null) {
            return;
        }
        List<SidebarDrag.Row> rows = sidebarRows();
        switch (book.bookState.sidebarDrop) {
            case SidebarDrag.Drop.Insert insert -> {
                int y = SidebarDrag.indicatorY(rows, insert);
                if (y < 0 || rows.isEmpty()) {
                    return;
                }
                BookGeometry.Rect first = rows.get(0).rect();
                r.fill(first.x() - 2, y - 1, first.right() + 2, y + 2, ArmatureTheme.hoverRing());
            }
            case SidebarDrag.Drop.Into into -> {
                for (SidebarDrag.Row row : rows) {
                    if (row.key().equals(into.groupKey())) {
                        BookGeometry.Rect rect = row.rect();
                        r.fill(rect.x() - 1, rect.y() - 1, rect.right() + 1, rect.bottom() + 1,
                                ArmatureTheme.selectedRing());
                        return;
                    }
                }
            }
            case SidebarDrag.Drop.Nowhere ignored -> {
            }
        }
    }

    /**
     * The "(3)" rewards counts, as suffixes on the chapter row buttons.
     *
     * <p>A suffix rather than paint over the label: the button truncates its title around the note,
     * so a long chapter ends before the count instead of under it — which is what the overlay this
     * replaces could not do, because it never moved the truncation the label was fitted with. The
     * count keeps the {@code inProgress} accent it has always worn, dimmed on the selected row the
     * way the overlay dimmed it; hover leaves it alone, which is one subtle wash lost for a whole
     * overlap cured. Groups get none of it: a heading is not a thing with rewards waiting.
     *
     * <p>On the waiting map rather than on a revision: progress, tree, marks and viewer each move
     * it, and the map is the one value all four arrive as — so a change in any of them that moves
     * no count touches no button. Called every tick; setting an unchanged suffix is still a write
     * the button does not need, which is what the comparison is for.
     */
    int readerFooterTaskIndex = -1;
    boolean readerFooterClaimable;

    void syncSidebarSuffixes() {
        SidebarLayout layout = sidebar();
        if (layout == null) {
            return;
        }
        QuestBookScreen.RewardCounts counts = book.rewardCounts();
        Map<String, Integer> waiting = counts.byChapter();
        String selectedChapter = QuestBookScreen.effectiveChapter();
        if (waiting.equals(book.bookState.sidebarWaiting)
                && java.util.Objects.equals(selectedChapter, book.bookState.sidebarSuffixChapter)) {
            return;
        }
        book.bookState.sidebarWaiting = waiting;
        book.bookState.sidebarSuffixChapter = selectedChapter;
        if (book.bookState.rewardsButton != null) {
            int total = counts.byQuest().size();
            book.bookState.rewardsButton.tooltip(Component.translatable(total == 0
                    ? "tenet.screen.rewards.none" : "tenet.screen.rewards.waiting", total));
        }
        for (SidebarLayout.Row row : layout.rows()) {
            if (row.group() || !(book.bookState.sidebarView.get(row.key()) instanceof ArmatureButton button)) {
                continue;
            }
            Integer count = waiting.get(row.id());
            if (count != null && count > 0) {
                int accent = QuestBookScreen.themeFor(row.id()).inProgress();
                button.suffix("(" + count + ")").suffixColour(
                        row.id().equals(selectedChapter) ? Colour.shade(accent, 0.35F) : accent);
            }
            else {
                button.suffix(null);
            }
        }
    }

    /**
     * The chapter rows' completion bars, along the bottom of each row.
     *
     * <p>The bar is a one-pixel hairline inside the row's own height, so the selection pill wraps it
     * and nothing about the list's layout moves. It is inset a pixel from each end — just enough to
     * stay inside a rounded pill's corners without reading as a floating chip — and it is drawn only
     * when something is done, because a track along every untouched chapter is a groove nobody asked
     * for.
     *
     * <p>The bar's fill is the chapter's {@code complete} and its track its {@code recessed} (the
     * token a progress bar's track is for). Groups get none of it: a heading is not a thing with a
     * completion. The "(3)" rewards count used to be drawn here too, at the row's right edge — it
     * lives on the row button as a suffix now, because a count drawn over the label cannot move the
     * truncation the label was fitted with, and a long title ran straight under it. See
     * {@link #syncSidebarSuffixes}.
     *
     * <p>The bar answers to {@link DevMode#progress()}, which a reader can turn off from the Tools
     * panel.
     */
    void drawSidebarChapterProgress(GuiRenderer r, int mouseX, int mouseY) {
        SidebarLayout layout = sidebar();
        if (layout == null) {
            return;
        }
        QuestBookScreen.ChapterCounts counts = book.chapterCounts();
        if (counts != tooltippedCounts) {
            refreshSidebarChapterTooltips(layout, counts);
            tooltippedCounts = counts;
        }

        // One batch for the whole list: a bar is two fills, and thirty rows drawn without this
        // would be sixty rounds of per-fill flushes for what is a decoration; see
        // `GuiRenderer.batched` for the submission argument the canvas makes at length.
        r.batched(() -> {
            for (SidebarLayout.Row row : layout.rows()) {
                if (row.group() || SidebarLayout.isInlineKey(row.key())) {
                    continue;
                }
                BookGeometry.Rect rect = placedRect(row.key());
                if (rect == null) {
                    // Scrolled out of the list: its rect is not placed, and drawing at a remembered one
                    // would paint a mark up through the header.
                    continue;
                }
                Theme theme = QuestBookScreen.themeFor(row.id());
                boolean selected = row.id().equals(QuestBookScreen.effectiveChapter());
                boolean hovered = rect.contains(mouseX, mouseY);
                if (DevMode.progress()) {
                    ChapterProgress progress = counts.byChapter().get(row.id());
                    float fraction = progress == null ? 0F : progress.fraction();
                    if (fraction > 0F) {
                        int fill = selected ? Colour.shade(theme.complete(), 0.35F)
                                : hovered ? Colour.shade(theme.complete(), 0.15F)
                                : theme.complete();
                        int track = selected ? Colour.shade(theme.recessed(), 0.35F)
                                : theme.recessed();
                        // One pixel of breathing room below: flush against the pill's own edge the
                        // hairline read as part of the row's border rather than as a gauge.
                        ChapterBar.draw(r, rect.x() + 1, rect.bottom() - ChapterBar.HEIGHT - 1,
                                rect.width() - 2, ChapterBar.HEIGHT, fraction, fill, track);
                    }
                }
            }
            // The batch exists for its fills, not its value; `batched` takes a supplier, so this is
            // the value it has to return.
            return null;
        });
    }

    /**
     * The chapter rows' hover text: how far through the chapter is.
     *
     * <h2>Rebuilt on a revision, not on a frame</h2>
     *
     * <p>Tooltip lines are components, and building thirty rows' worth every frame is allocation for
     * text that only changes when a quest does. The guard is the identity of the cache the lines are
     * built from: it is replaced exactly when the tree, the progress or the viewer moves, which is
     * exactly when the text can differ — so reference comparison is the revision check, and no second
     * revision counter can drift from the first.
     *
     * <p>The lines are what a player would otherwise open the chapter to learn: the fraction
     * ("14/20 quests complete") — the bar is two pixels, so the exact number stays one hover away —
     * and the missing-item note the row's icon would carry when its item id does not resolve here.
     * That last is repeated from {@code buildSidebarWidgets} deliberately — setting a tooltip replaces
     * whatever was there, so a refresh that dropped the missing-item line would quietly answer a
     * different question than the row does. The rewards waiting are not repeated: they are a "(3)" in
     * the row's own corner again, and one fact does not need two places to be read from.
     *
     * <p>The chapter's subtitle, when it names one, is the hover's second line: the row's label is
     * the title, so this is the one line under the name, resolved in the player's language like every
     * other player-facing text. A chapter with no quests carries no entry to read one from, so it
     * shows none — the same reading an older server gets, which sent no subtitle at all.
     */
    private void refreshSidebarChapterTooltips(SidebarLayout layout, QuestBookScreen.ChapterCounts counts) {
        Map<String, SidebarIcon> icons = sidebarIcons();
        for (SidebarLayout.Row row : layout.rows()) {
            if (row.group() || !(book.bookState.sidebarView.get(row.key()) instanceof ArmatureButton button)) {
                continue;
            }
            List<Component> lines = new ArrayList<>(3);
            SidebarIcon icon = icons.get(row.key());
            if (icon != null && icon.stack().isEmpty() && !icon.id().isEmpty()) {
                lines.add(Component.translatable("tenet.screen.missing_item_hint"));
            }
            String subtitle = ClientQuestCache.chapterSubtitleText(row.id());
            if (!subtitle.isEmpty()) {
                lines.add(Component.literal(subtitle));
            }
            // What a shut chapter is waiting for, and how far along that is. Only while it is shut: a
            // gate that is met explains nothing, and a permanent "Needs X" on an open chapter would read
            // as a warning about a chapter that is working.
            if (row.locked()) {
                Component gate = chapterGateLine(row.id());
                if (gate != null) {
                    lines.add(gate);
                }
            }
            ChapterProgress progress = counts.byChapter().get(row.id());
            if (progress == null || progress.isEmpty()) {
                lines.add(Component.translatable("tenet.screen.chapter_empty"));
            }
            else {
                lines.add(Component.translatable("tenet.screen.chapter_progress",
                        progress.done(), progress.total()));
            }
            button.tooltip(lines);
        }
    }

    /**
     * The reward badges: a small mark pinned to each node's outline while that quest has rewards this
     * player has not collected.
     *
     * <p>Drawn on the canvas and therefore inside the chapter's theme scope, so the badge is already
     * the chapter's own colour: a themed chapter's badges take its {@code inProgress}, not the
     * player's. The count comes from one walk per revision (see {@link QuestBookScreen#rewardCounts}), and the
     * badge's own art and anchoring are {@link RewardBadge}'s.
     */
    void drawRewardBadges(GuiRenderer r, List<BookCanvas.FrameNode> nodes, int dx, int dy,
                                  CanvasDetail detail) {
        Map<String, Integer> waiting = book.rewardCounts().byQuest();
        if (waiting.isEmpty()) {
            return;
        }
        if (!detail.badges()) {
            // A badge is a disc with a number in it, drawn against the node's own outline: at this zoom
            // the number is unreadable and the disc is larger than the node it sits on. See CanvasDetail.
            return;
        }
        int cLeft = book.canvasLeft();
        int cTop = book.canvasTop();
        int cRight = book.canvasRight();
        int cBottom = book.canvasBottom();
        for (BookCanvas.FrameNode node : nodes) {
            Integer count = waiting.get(node.id());
            if (count == null) {
                continue;
            }
            int nx = node.x() + dx;
            int ny = node.y() + dy;
            int size = node.size();
            if (nx >= cRight || nx + size <= cLeft || ny >= cBottom || ny + size <= cTop) {
                continue;
            }
            // The badge anchors itself to this node's own outline and shrinks with it; the ring is a
            // darker shade of the badge's own colour rather than a theme edge, because a grey outline
            // around a gold disc reads as two unrelated things.
            int fill = ArmatureTheme.inProgress();
            RewardBadge.draw(r, node.geometry(), nx, ny, size,
                    count, fill, Colour.shade(fill, -0.45F), ArmatureTheme.canvas());
        }
    }

    /**
     * The padlocks, one per locked node that lets it show, in their own pass after every node.
     *
     * <p>Each mark straddles its node's top-right corner — half in, half out — rather than sitting
     * inside it: see the call site for why the corner's inside is the item's territory. The gating
     * is the node's own — locked, rings tier, pack and quest flags — read here rather than carried,
     * so the pass and the node cannot disagree about which nodes wear one.
     */
    void drawLockMarks(GuiRenderer r, List<BookCanvas.FrameNode> nodes, int dx, int dy,
                               CanvasDetail detail) {
        if (!detail.rings()) {
            return;
        }
        int cLeft = book.canvasLeft();
        int cTop = book.canvasTop();
        int cRight = book.canvasRight();
        int cBottom = book.canvasBottom();
        boolean showLockIcons = ClientQuestCache.showLockIcons();
        for (BookCanvas.FrameNode node : nodes) {
            if (node.state() != QuestState.LOCKED) {
                continue;
            }
            if (!dev.ellipog.tenet.client.dev.QuestVisibility.drawsLockMark(showLockIcons,
                    node.hideLock())) {
                continue;
            }
            int nx = node.x() + dx;
            int ny = node.y() + dy;
            int size = node.size();
            if (nx >= cRight || nx + size <= cLeft || ny >= cBottom || ny + size <= cTop) {
                continue;
            }
            int box = Math.max(9, size / 4);
            drawLockMark(r, nx + size - box / 2, ny - box / 2, box);
        }
    }

    /**
     * Scrolls the sidebar while a drag is held near one of its edges.
     *
     * <p>One row's pitch a tick, which is the same step a wheel notch takes — the list moves the way it
     * moves under the wheel, so a reader who has scrolled this list before can predict this. The
     * margin is small: the top and bottom of the viewport are where the pointer goes when the row it is
     * carrying has somewhere further to be.
     */
    void autoScrollSidebar() {
        Viewport view = book.sidebarViewport();
        int margin = 12;
        int step = SidebarLayout.pitch();
        if (book.bookState.sidebarDragPointerY < view.originY() + margin) {
            book.bookState.sidebarView.scrollBy(-step);
        }
        else if (book.bookState.sidebarDragPointerY > view.viewBottom() - margin) {
            book.bookState.sidebarView.scrollBy(step);
        }
    }

    // ------------------------------------------------------------------
    // The naming card and the toolbar
    // ------------------------------------------------------------------

    /**
     * Starts an inline create: an editable row where the chapter or group will land, with focus.
     *
     * <p>The row is placed at the end of the named group's section — the selected chapter's group
     * for the toolbar button, the clicked group for its menu — so the author sees where the new
     * row lands rather than being told afterwards. The id is automatic from the title; the commit
     * sends the same op the card sent, appended to the group.
     */
    void startInlineCreate(String kind, String groupId) {
        if (!book.mayEditNow() || book.geometry().sidebarState().collapsed()) {
            return;
        }
        book.bookMenu.closeMenu();
        if (book.bookState.naming != null) {
            // One editor at a time: the card and the inline row would otherwise name two things
            // at once, with one keyboard between them.
            closeNaming();
        }
        book.bookState.inlineEdit = new QuestBookScreen.InlineNaming(kind, QuestBookScreen.NamingMode.CREATE, "",
                groupId == null ? "" : groupId, false);
        book.bookState.inlineTitle = null;
        book.bookState.inlineId = null;
        inlineFocusPending = true;
        book.rebuildWidgets();
        scrollInlineIntoView();
    }

    /**
     * Starts an inline rename: the row's own title in an editable row where the row stood.
     *
     * <p>Title-only: the id stays unless the author opens the disclosure, which is a second field
     * under the first. A title-only commit never validates the unchanged id, so it cannot report
     * the row's own name as taken.
     */
    void startInlineRename(String kind, String id) {
        if (!book.mayEditNow() || book.geometry().sidebarState().collapsed()) {
            return;
        }
        book.bookMenu.closeMenu();
        if (book.bookState.naming != null) {
            // One editor at a time: see startInlineCreate.
            closeNaming();
        }
        book.bookState.inlineEdit = new QuestBookScreen.InlineNaming(kind, QuestBookScreen.NamingMode.RENAME,
                id, "", false);
        book.bookState.inlineTitle = null;
        book.bookState.inlineId = null;
        inlineFocusPending = true;
        book.rebuildWidgets();
        scrollInlineIntoView();
    }

    /** Drops the inline session. The rebuild that follows is the caller's. */
    private void clearInline() {
        book.bookState.inlineEdit = null;
        book.bookState.inlineTitle = null;
        book.bookState.inlineId = null;
        inlineFocusPending = false;
    }

    /** Abandons the inline session with nothing written. */
    void cancelInline() {
        if (book.bookState.inlineEdit == null) {
            return;
        }
        clearInline();
        book.rebuildWidgets();
    }

    /** The ids the inline id is checked against: the live ones, less its own when renaming. */
    private List<String> inlineTakenIds() {
        if (book.bookState.inlineEdit == null) {
            return new ArrayList<>();
        }
        return takenIds(book.bookState.inlineEdit.kind(), book.bookState.inlineEdit.mode(),
                book.bookState.inlineEdit.targetId());
    }

    /**
     * The id a create would carry right now: the title slugged, deduped against the live ids, or
     * the numbered fallback when the title slugs to nothing.
     */
    private String inlineAutoId() {
        String title = book.bookState.inlineTitle == null ? "" : book.bookState.inlineTitle.value().trim();
        String base = ChapterNaming.slug(title);
        if (base.isEmpty()) {
            base = book.bookState.inlineEdit.kind().equals("group") ? "new_group" : "new_chapter";
        }
        return ChapterNaming.suggested(base, "", inlineTakenIds());
    }

    /** Sends the op the inline session is for, or says why it cannot. */
    void commitInline() {
        if (book.bookState.inlineEdit == null) {
            return;
        }
        String title = book.bookState.inlineTitle == null ? "" : book.bookState.inlineTitle.value().trim();
        String label = title.isEmpty() ? null : title;
        if (book.bookState.inlineEdit.mode() == QuestBookScreen.NamingMode.CREATE) {
            String id = inlineAutoId();
            String problem = ChapterNaming.problemWith(id, inlineTakenIds());
            if (problem != null) {
                book.status(problem, true);
                return;
            }
            if (book.bookState.inlineEdit.kind().equals("group")) {
                book.send(new EditorOp.CreateGroup(id, label));
            }
            else {
                book.send(new EditorOp.CreateChapter(book.bookState.inlineEdit.groupId(), Integer.MAX_VALUE,
                        id, label));
                selectChapter(id);
            }
        }
        else {
            String id = book.bookState.inlineEdit.targetId();
            if (book.bookState.inlineEdit.showId()) {
                id = book.bookState.inlineId == null ? "" : book.bookState.inlineId.value().trim();
                String problem = ChapterNaming.problemWith(id, inlineTakenIds());
                if (problem != null) {
                    book.status(problem, true);
                    return;
                }
            }
            if (book.bookState.inlineEdit.kind().equals("group")) {
                book.send(new EditorOp.RenameGroup(book.bookState.inlineEdit.targetId(), id, label));
            }
            else {
                book.send(new EditorOp.RenameChapter(book.bookState.inlineEdit.targetId(), id, label));
                selectChapter(id);
            }
        }
        clearInline();
        book.rebuildWidgets();
    }

    /**
     * The rows with the inline session spliced in: the title field's row where the new row will
     * land — or in place of the row being renamed — with the id field's row under it while the
     * disclosure is open, and the drawn status line under those.
     */
    List<SidebarLayout.Row> withInlineRows(SidebarLayout layout, List<SidebarLayout.Row> rows) {
        List<SidebarLayout.Row> out = new ArrayList<>(rows);
        if (book.bookState.inlineEdit.mode() == QuestBookScreen.NamingMode.RENAME) {
            String targetKey = book.bookState.inlineEdit.kind().equals("group")
                    ? SidebarLayout.groupKey(book.bookState.inlineEdit.targetId())
                    : SidebarLayout.chapterKey(book.bookState.inlineEdit.targetId());
            int at = layout.indexOfKey(targetKey);
            if (at < 0) {
                // The tree moved under the edit: draw the list as it is, and let the commit be
                // refused rather than aimed at a row that is gone.
                return rows;
            }
            int depth = out.get(at).depth();
            out.remove(at);
            out.add(at, inlineRow(INLINE_EDIT_KEY, depth));
            int next = at + 1;
            if (book.bookState.inlineEdit.showId()) {
                out.add(next++, inlineRow(INLINE_ID_KEY, depth));
            }
            out.add(next, inlineRow(QuestBookScreen.INLINE_STATUS_KEY, depth));
            return out;
        }
        int depth;
        int at;
        if (book.bookState.inlineEdit.kind().equals("group")) {
            // Groups live at the root, after everything: the end of the list.
            depth = 0;
            at = out.size();
        }
        else {
            depth = book.bookState.inlineEdit.groupId().isEmpty() ? 0 : 1;
            at = Math.max(0, Math.min(layout.endOfSection(book.bookState.inlineEdit.groupId()), out.size()));
        }
        out.add(at, inlineRow(INLINE_EDIT_KEY, depth));
        out.add(at + 1, inlineRow(QuestBookScreen.INLINE_STATUS_KEY, depth));
        return out;
    }

    /** One synthetic row: placed and indented like a real one, carrying no title of its own. */
    private static SidebarLayout.Row inlineRow(String key, int depth) {
        return new SidebarLayout.Row(key, "", "", depth, false, false, false);
    }

    /**
     * Places the inline session's field for a synthetic row: the title field, or the id field
     * while its disclosure is open. The status row carries no widget — the screen draws it.
     *
     * <p>Typed text survives a rebuild: the field is recreated from the live one's value rather
     * than from the session's defaults, so a tree broadcast mid-typing costs focus and not words.
     */
    void placeInlineField(SidebarLayout.Row row) {
        boolean idRow = INLINE_ID_KEY.equals(row.key());
        String initial;
        if (idRow) {
            initial = book.bookState.inlineId == null ? book.bookState.inlineEdit.targetId()
                    : book.bookState.inlineId.value();
        }
        else if (book.bookState.inlineTitle != null) {
            initial = book.bookState.inlineTitle.value();
        }
        else if (book.bookState.inlineEdit.mode() == QuestBookScreen.NamingMode.RENAME) {
            initial = existingTitle(book.bookState.inlineEdit.kind(), book.bookState.inlineEdit.targetId());
        }
        else {
            initial = "";
        }
        ArmatureTextField field = new ArmatureTextField(0, 0, 0, 0, initial);
        field.onSubmit(text -> {
        });
        if (!idRow) {
            // An empty box under the list said nothing about what it was waiting for.
            field.placeholder("group".equals(book.bookState.inlineEdit.kind()) ? "Group name" : "Chapter name");
        }
        book.addRenderableWidget(field);
        book.bookState.sidebarView.put(row.key(), field);
        book.bookState.sidebarRowWidgets.add(field);
        if (idRow) {
            book.bookState.inlineId = field;
        }
        else {
            book.bookState.inlineTitle = field;
            if (inlineFocusPending) {
                inlineFocusPending = false;
                book.setFocused(field);
            }
        }
    }

    /** Whether a press lands on one of the inline session's fields. */
    boolean pressOnInlineField(double mouseX, double mouseY) {
        // Registered and visible, not just placed: a field scrolled out of the list keeps its last
        // rectangle, and a field from a previous build is detached altogether. Either way a press
        // on the row now standing there is that row's, not the edit's.
        if (book.bookState.inlineTitle != null && book.children().contains(book.bookState.inlineTitle)
                && book.bookState.inlineTitle.visible
                && book.bookState.inlineTitle.isMouseOver(mouseX, mouseY)) {
            return true;
        }
        return book.bookState.inlineId != null && book.children().contains(book.bookState.inlineId)
                && book.bookState.inlineId.visible
                && book.bookState.inlineId.isMouseOver(mouseX, mouseY);
    }

    /** Whether a press lands on the inline session's drawn status line. */
    boolean inlineStatusHit(double mouseX, double mouseY) {
        BookGeometry.Rect field = placedRect(INLINE_EDIT_KEY);
        if (field == null) {
            return false;
        }
        BookGeometry.Rect status = BookGeometry.Rect.at(field.x(),
                field.bottom() + BookGeometry.SIDEBAR_ROW_GAP, field.width(),
                BookGeometry.SIDEBAR_ROW_HEIGHT);
        return status.contains(mouseX, mouseY);
    }

    /** Opens or shuts the rename's id disclosure. */
    void toggleInlineId() {
        if (book.bookState.inlineEdit == null
                || book.bookState.inlineEdit.mode() != QuestBookScreen.NamingMode.RENAME) {
            return;
        }
        book.bookState.inlineEdit = book.bookState.inlineEdit.withShowId(!book.bookState.inlineEdit.showId());
        book.bookState.inlineId = null;
        book.rebuildWidgets();
    }

    /**
     * The status line under the inline field: the file that will land on disk for a create, the
     * row's id for a rename, or the problem with either. Null when there is nothing to say.
     */
    private String inlineStatusText() {
        if (book.bookState.inlineEdit == null) {
            return null;
        }
        if (book.bookState.inlineEdit.mode() == QuestBookScreen.NamingMode.CREATE) {
            return folderPath(book.bookState.inlineEdit.kind(), book.bookState.inlineEdit.groupId(), inlineAutoId());
        }
        if (!book.bookState.inlineEdit.showId()) {
            return "id: " + book.bookState.inlineEdit.targetId() + " - click to edit";
        }
        String id = book.bookState.inlineId == null ? "" : book.bookState.inlineId.value().trim();
        String problem = ChapterNaming.problemWith(id, inlineTakenIds());
        if (problem != null) {
            return problem;
        }
        String group = book.bookState.inlineEdit.kind().equals("group") ? ""
                : groupOfChapter(book.bookState.inlineEdit.targetId());
        return folderPath(book.bookState.inlineEdit.kind(), group, id);
    }

    /** Whether the status line is a refusal rather than a path. */
    private boolean inlineStatusBlocked() {
        if (book.bookState.inlineEdit == null || book.bookState.inlineEdit.mode() != QuestBookScreen.NamingMode.RENAME
                || !book.bookState.inlineEdit.showId()) {
            return false;
        }
        String id = book.bookState.inlineId == null ? "" : book.bookState.inlineId.value().trim();
        return ChapterNaming.problemWith(id, inlineTakenIds()) != null;
    }

    /**
     * Draws the inline session's status line under its field, in the list's clip: the faint path
     * preview as the author types, or the id line a rename can expand.
     */
    void drawInlineStatus(GuiRenderer r) {
        if (book.bookState.inlineEdit == null) {
            return;
        }
        BookGeometry.Rect field = placedRect(INLINE_EDIT_KEY);
        if (field == null) {
            // Culled with its row: drawing at a remembered rectangle would paint the preview up
            // through the header.
            return;
        }
        String text = inlineStatusText();
        if (text == null || text.isEmpty()) {
            return;
        }
        int y = field.bottom() + BookGeometry.SIDEBAR_ROW_GAP;
        String shown = dev.ellipog.armature.client.ui.kit.Measure.truncate(text, field.width(),
                book.textMeasure(r));
        r.text(shown, field.x(), y,
                inlineStatusBlocked() ? ArmatureTheme.blocked() : ArmatureTheme.faint());
    }

    /** Scrolls the sidebar just far enough to show the inline edit. */
    private void scrollInlineIntoView() {
        if (book.bookState.sidebarView.layout() == null) {
            return;
        }
        var slot = book.bookState.sidebarView.layout().slot(INLINE_EDIT_KEY);
        if (slot == null) {
            return;
        }
        var viewport = book.bookState.sidebarView.viewport();
        int y = viewport.screenY(slot.y());
        int bottom = y + viewport.scaled(slot.height());
        int top = viewport.originY();
        int viewBottom = viewport.viewBottom();
        if (y < top) {
            book.bookState.sidebarView.scrollTo(viewport.scrollY() - (top - y));
        }
        else if (bottom > viewBottom) {
            book.bookState.sidebarView.scrollTo(viewport.scrollY() + (bottom - viewBottom));
        }
    }

    void openNaming(String kind, QuestBookScreen.NamingMode mode, String targetId, String groupId, int index) {
        book.bookMenu.closeMenu();
        // The card keeps duplicates only; an inline session would name a second thing at once.
        clearInline();
        book.bookState.naming = new QuestBookScreen.Naming(kind, mode, targetId, groupId, index);
        book.applyColumns(PanelStack.asRoot(book.columns(), PanelKind.NAMING));
        book.rebuildWidgets();
    }

    void closeNaming() {
        book.closeOverlay();
        // Deferred, not dropped: the exiting rail draws from this session for the length of its
        // slide — the same rule closeOverlay's own nullings follow. Nulling eagerly is a departed
        // card drawing with no session, which crashes the book in drawNamingOverlay the next frame.
        book.deferForLastDeparted(() -> book.bookState.naming = null, PanelKind.NAMING);
    }

    /** The two fields' rectangles: one derivation for the widgets and for the drawing. */
    private BookGeometry.Rect[] namingFieldRects() {
        BookGeometry.Rect card = book.surfaceCard(PanelKind.NAMING);
        int left = card.x() + 14;
        int width = Math.max(60, card.width() - 28);
        int top = card.y() + 44;
        return new BookGeometry.Rect[] {
                BookGeometry.Rect.at(left, top, width, 20),
                BookGeometry.Rect.at(left, top + 30, width, 20) };
    }

    private String namingKindWord() {
        return book.bookState.naming != null && book.bookState.naming.kind().equals("group") ? "group" : "chapter";
    }

    /** The title the thing being named already has, for the card to open on. */
    private String namingExistingTitle() {
        if (book.bookState.naming == null || book.bookState.naming.targetId() == null) {
            return "";
        }
        return existingTitle(book.bookState.naming.kind(), book.bookState.naming.targetId());
    }

    /** The title a chapter or group already has: what an inline rename opens its field on. */
    private static String existingTitle(String kind, String id) {
        if (id == null) {
            return "";
        }
        if (kind.equals("group")) {
            for (ClientQuestCache.GroupEntry group : ClientQuestCache.groups()) {
                if (group.id().equals(id)) {
                    return group.title();
                }
            }
            return "";
        }
        return BookContent.chapters().getOrDefault(id, "");
    }

    /**
     * Every id of the kind being named: what the card's collision check is against.
     *
     * <h2>What it is not, and why the rename case drops its own name</h2>
     *
     * <p>Ids only: the synced tree carries no aliases, so the card cannot see them — and an id that is
     * another chapter's alias is a clash the loader refuses, which is why the server asks the same question
     * again against a set it builds from the manifests. The server's answer is the one that decides.
     *
     * <p>A rename leaves the thing's own id out, because a list that contained it made the card open red on
     * the name it already had and refused a title-only rename through this card. Renaming to a name it used
     * to have is legal too, and the server's check is where that is known.
     */
    private List<String> namingTakenIds() {
        if (book.bookState.naming == null) {
            return new ArrayList<>();
        }
        return takenIds(book.bookState.naming.kind(), book.bookState.naming.mode(), book.bookState.naming.targetId());
    }

    /**
     * Every id of the kind being named: what an id check is against. Shared by the card and the
     * inline session, because two lists would drift — see {@link #namingTakenIds} for why the
     * rename case drops its own name.
     */
    private static List<String> takenIds(String kind, QuestBookScreen.NamingMode mode, String targetId) {
        List<String> taken = new ArrayList<>();
        if (kind.equals("group")) {
            for (ClientQuestCache.GroupEntry group : ClientQuestCache.groups()) {
                taken.add(group.id());
            }
        }
        else {
            taken.addAll(BookContent.chapters().keySet());
        }
        if (mode == QuestBookScreen.NamingMode.RENAME) {
            taken.remove(targetId);
        }
        return taken;
    }

    /** Where the thing being named will live, as a path an author can find. */
    private String namingFolderPath(String id) {
        if (book.bookState.naming == null || id.isBlank()) {
            return "";
        }
        String group = book.bookState.naming.mode() == QuestBookScreen.NamingMode.CREATE
                ? book.bookState.naming.groupId() : groupOfChapter(book.bookState.naming.targetId());
        return folderPath(book.bookState.naming.kind(), group, id);
    }

    /**
     * Where an id will live, as a path an author can find. Shared by the card and the inline
     * session's faint preview, which must name the file that lands on disk.
     */
    private static String folderPath(String kind, String group, String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        if (kind.equals("group")) {
            return id + "/group.json";
        }
        return group == null || group.isEmpty()
                ? id + "/chapter.json" : group + "/" + id + "/chapter.json";
    }

    /** The group a chapter is in, from the cache, or empty. */
    static String groupOfChapter(String chapterId) {
        if (chapterId == null) {
            return "";
        }
        for (ClientQuestCache.ChapterEntry chapter : ClientQuestCache.chapters()) {
            if (chapter.id().equals(chapterId)) {
                return chapter.groupId();
            }
        }
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            if (entry.chapterId().equals(chapterId)) {
                return entry.chapterGroupId();
            }
        }
        return "";
    }

    void buildNamingWidgets() {
        if (book.bookState.naming == null) {
            namingTitle = null;
            namingId = null;
            return;
        }
        BookGeometry.Rect[] fields = namingFieldRects();
        String title = book.bookState.naming.mode() == QuestBookScreen.NamingMode.CREATE ? "" : namingExistingTitle();
        String id = switch (book.bookState.naming.mode()) {
            case CREATE -> ChapterNaming.suggested(
                    book.bookState.naming.kind().equals("group") ? "new_group" : "new_chapter", "", namingTakenIds());
            case RENAME -> book.bookState.naming.targetId();
            case DUPLICATE -> ChapterNaming.suggested(book.bookState.naming.targetId(), "_copy", namingTakenIds());
        };
        namingTitle = new ArmatureTextField(fields[0].x(), fields[0].y(), fields[0].width(),
                fields[0].height(), title);
        namingId = new ArmatureTextField(fields[1].x(), fields[1].y(), fields[1].width(),
                fields[1].height(), id);
        // Enter and Escape are the screen's while this card is open -- see `keyPressed` -- so a blur
        // must commit nothing.
        namingTitle.onSubmit(text -> { });
        namingId.onSubmit(text -> { });
        book.addRenderableWidget(namingTitle);
        book.addRenderableWidget(namingId);
        // Nothing is recorded for the two fields, and the reason is the round's own: a rail is drawn
        // before the widget pass, so the pass paints them on top of it. The clip these two carried was
        // for a card that was painted *after* the pass -- see the settings page's note for what that
        // costs a rail, and why a second copy cannot help.
        Map<String, BookGeometry.Rect> controls = book.overlayControls(true);
        BookGeometry.Rect back = controls.get("back");
        if (back != null) {
            book.control(back, Component.translatable("tenet.screen.cancel"), this::closeNaming)
                    .ink(ArmatureButton.Ink.BODY);
        }
        BookGeometry.Rect primary = controls.get("submit");
        if (primary != null) {
            book.control(primary, Component.literal(switch (book.bookState.naming.mode()) {
                case CREATE -> "Create";
                case RENAME -> "Rename";
                case DUPLICATE -> "Duplicate";
            }), this::submitNaming).accent(true);
        }
        book.setFocused(namingId);
    }

    /** Sends the op the card is for, or says why it cannot. */
    void submitNaming() {
        if (book.bookState.naming == null || namingId == null) {
            return;
        }
        String id = namingId.value().trim();
        String title = namingTitle == null ? "" : namingTitle.value().trim();
        String problem = ChapterNaming.problemWith(id, namingTakenIds());
        if (problem != null) {
            book.status(problem, true);
            return;
        }
        String label = title.isEmpty() ? null : title;
        switch (book.bookState.naming.mode()) {
            case CREATE -> {
                if (book.bookState.naming.kind().equals("group")) {
                    book.send(new EditorOp.CreateGroup(id, label));
                }
                else {
                    book.send(new EditorOp.CreateChapter(book.bookState.naming.groupId(),
                            book.bookState.naming.index(), id, label));
                    selectChapter(id);
                }
            }
            case RENAME -> {
                if (book.bookState.naming.kind().equals("group")) {
                    book.send(new EditorOp.RenameGroup(book.bookState.naming.targetId(), id, label));
                }
                else {
                    book.send(new EditorOp.RenameChapter(book.bookState.naming.targetId(), id, label));
                    selectChapter(id);
                }
            }
            case DUPLICATE -> {
                if (book.bookState.naming.kind().equals("group")) {
                    book.send(new EditorOp.DuplicateGroup(book.bookState.naming.targetId(), id, label));
                }
                else {
                    book.send(new EditorOp.DuplicateChapter(book.bookState.naming.targetId(), id, label));
                    selectChapter(id);
                }
            }
        }
        closeNaming();
    }

    /** The card itself: heading, field labels, and the live verdict on the id. */
    void drawNamingOverlay(GuiRenderer r) {
        if (book.bookState.naming == null) {
            // No session, no card — the same guard buildNamingWidgets keeps. The close defers the
            // nulling so a departed rail still draws, but a draw without one must never dereference.
            return;
        }
        BookGeometry.Rect card = book.surfaceCard(PanelKind.NAMING);
        ArmatureTheme.panel(r, card.x(), card.y(), card.width(), card.height(),
                ArmatureTheme.panel(), ArmatureTheme.panelEdge());
        String heading = switch (book.bookState.naming.mode()) {
            case CREATE -> "New " + namingKindWord();
            case RENAME -> "Rename " + namingKindWord();
            case DUPLICATE -> "Duplicate " + namingKindWord();
        };
        r.text(heading, card.x() + 14, card.y() + 12, ArmatureTheme.title());
        BookGeometry.Rect[] fields = namingFieldRects();
        r.text("Title", fields[0].x(), fields[0].y() - 10, ArmatureTheme.body());
        r.text("Id - becomes the folder name", fields[1].x(), fields[1].y() - 10, ArmatureTheme.body());
        String id = namingId == null ? "" : namingId.value().trim();
        String problem = ChapterNaming.problemWith(id, namingTakenIds());
        r.text(problem == null ? namingFolderPath(id) : problem,
                fields[1].x(), fields[1].bottom() + 8,
                problem == null ? ArmatureTheme.faint() : ArmatureTheme.blocked());
    }

    /** The two add buttons above the list. Built wherever the sidebar is, drawn only for an author. */
    void buildSidebarToolbar() {
        if (book.geometry().sidebarState().collapsed()) {
            // No room on the strip for two half-width buttons: an author expands to add chapters,
            // which is one more reason the strip is a preview rather than a home.
            return;
        }
        Map<String, BookGeometry.Rect> toolbar = book.geometry().sidebarToolbar();
        BookGeometry.Rect addChapter = toolbar.get("addChapter");
        if (addChapter != null) {
            ArmatureButton button = book.control(addChapter, Component.translatable("tenet.screen.chapter"),
                    this::newChapterFromToolbar);
            button.ink(ArmatureButton.Ink.BODY).flat(true).tooltip(
                    Component.translatable("tenet.screen.add_a_chapter_to_the_selected_group"));
            button.visible = book.mayEditNow();
            book.bookState.sidebarRowWidgets.add(button);
        }
        BookGeometry.Rect addGroup = toolbar.get("addGroup");
        if (addGroup != null) {
            ArmatureButton button = book.control(addGroup, Component.translatable("tenet.screen.group"),
                    this::newGroupFromToolbar);
            button.ink(ArmatureButton.Ink.BODY).flat(true).tooltip(
                    Component.translatable("tenet.screen.add_a_group_at_the_end_of_the_list"));
            button.visible = book.mayEditNow();
            book.bookState.sidebarRowWidgets.add(button);
        }
    }

    void newChapterFromToolbar() {
        if (!book.mayEditNow()) {
            return;
        }
        // At the end of the selected chapter's group, where the inline row lands too: the button,
        // the tooltip and the new row finally agree about where "a chapter" goes.
        startInlineCreate("chapter", groupOfChapter(QuestBookScreen.effectiveChapter()));
    }

    void newGroupFromToolbar() {
        if (!book.mayEditNow()) {
            return;
        }
        startInlineCreate("group", "");
    }

    /**
     * A padlock straddling a node's top-right corner, marking a locked quest.
     *
     * <p>Drawn from fills rather than from a texture, because there is no lock sprite: an arched
     * shackle over a hollow body with a keyhole, in the title ink and transparent everywhere else,
     * so whatever is behind the mark shows through it. That is also why there is no backing chip:
     * a locked node already wears the dim wash, which is the dark the ink reads against. The
     * caller positions the box centred on the node's corner — half outside the panel, where no
     * icon can reach, and half in, which reads as pinned rather than floating. Sized with a
     * floor — below nine pixels the shackle's bars would be one pixel or none, which reads as
     * dirt on the node rather than as a mark. Skipped at the far zoom for the same reason the
     * rings are: the caller asks {@code detail} before calling.
     *
     * @param bx the box's left edge, already resolved against the node's corner
     * @param by the box's top edge, already resolved against the node's corner
     * @param box the box's edge, nine pixels or more
     */
    private static void drawLockMark(GuiRenderer r, int bx, int by, int box) {
        int ink = ArmatureTheme.title();
        int bar = Math.max(1, box / 7);
        // The shackle: an inverted U straddling the body's top edge — two legs joined by one bar,
        // hollow in the middle so the node reads through it.
        int bodyTop = by + (box * 2) / 5;
        int legLeft = bx + box / 4;
        int legRight = bx + (box * 3) / 4 - bar;
        int shackleBottom = bodyTop + bar;
        r.fill(legLeft, by, legLeft + bar, shackleBottom, ink);
        r.fill(legRight, by, legRight + bar, shackleBottom, ink);
        r.fill(legLeft, by, legRight + bar, by + bar, ink);
        // The body: a hollow box with its corners cut, so it reads round rather than square. The
        // shackle's feet land on its top edge, merging the two into one outline.
        int edge = bar;
        int bottom = by + box;
        int right = bx + box;
        r.fill(bx + 1, bodyTop, right - 1, bodyTop + edge, ink);
        r.fill(bx + 1, bottom - edge, right - 1, bottom, ink);
        r.fill(bx, bodyTop + 1, bx + edge, bottom - 1, ink);
        r.fill(right - edge, bodyTop + 1, right, bottom - 1, ink);
        // The keyhole: a dot over a short stem, centred in the body.
        int bodyH = bottom - bodyTop;
        int cx = bx + box / 2;
        int d = box >= 14 ? 2 : 1;
        int dx = cx - d / 2;
        int dy = bodyTop + bodyH / 3;
        r.fill(dx, dy, dx + d, dy + d, ink);
        int stemH = Math.max(1, bodyH / 4);
        r.fill(cx, dy + d, cx + 1, dy + d + stemH, ink);
    }
}
