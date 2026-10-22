package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.tenet.editor.EditorOp;
import dev.ellipog.tenet.net.EditorReplyPayload;
import dev.ellipog.tenet.net.TenetNetworking;
import dev.ellipog.tenet.client.ClientEditReplies;
import dev.ellipog.tenet.client.dev.ClientEditorClipboard;
import dev.ellipog.tenet.client.dev.MenuFlyout;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import dev.ellipog.armature.client.ArmatureTextField;
import dev.ellipog.armature.client.ui.inspect.InspectLayout;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.ScrollBar;
import dev.ellipog.armature.client.ui.kit.ScrollView;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tenet.client.ClientChapterReplica;
import dev.ellipog.tenet.quest.CanvasElement;
import dev.ellipog.tenet.quest.QuestLink;
import dev.ellipog.tenet.client.dev.ChapterPanelLayout;
import dev.ellipog.tenet.client.dev.ItemPickerLayout;
import dev.ellipog.tenet.client.dev.LineArt;
import dev.ellipog.tenet.quest.DependencyStyle;
import dev.ellipog.tenet.client.dev.QuestPanelLayout;
import dev.ellipog.tenet.client.dev.SidebarDrag;
import dev.ellipog.tenet.client.dev.ToolsLayout;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * The quest book's input: what a press, a drag, a release, the wheel and a key do once the screen
 * has routed them, and the multi-selection's whole-hand gestures.
 *
 * <p>Moved out of {@link QuestBookScreen} as it stood, with the state only it reads. The screen keeps
 * the overrides Minecraft calls ({@code mouseClicked}, {@code keyPressed} and the rest) and the order
 * they offer an event in, and reaches this through its {@code bookInput} field.
 */
final class BookInput {

    private final QuestBookScreen book;

    BookInput(QuestBookScreen book) {
        this.book = book;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /**
     * Begins a pan from a press that landed on a rail, which is the one tail every panel's press shares.
     *
     * <h2>Why this is a method rather than a block in the press dispatch</h2>
     *
     * <p>Because it is the answer to one question — <i>may the graph be moved from here?</i> — and the answer
     * has to be the same for every kind. It was a block inside one arm, so the kinds whose arms returned
     * early never reached it: the reader and the editor could be panned from, the settings page, the tables,
     * the pack's files and the rewards list could not, and the fault read as "some work, some do not".
     *
     * <p>It sets the pan up by hand rather than leaving it to the canvas branch, because that branch asks what
     * node is under the pointer — and the pointer is over a rail, so it would open whatever node the panel
     * happens to be covering. {@code panelPress} is the other half: it keeps the release from reading the same
     * press as a click on empty canvas, so a drag pans and a click on a panel does nothing rather than closing
     * the panel the reader was working in.
     *
     * @return whether a pan was started. False for a button that is not a pan's — the right button belongs to
     *         the edges and the menus, and a held right press on a panel is neither.
     */
    boolean startPanelPan(double mouseX, double mouseY, int button) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT && button != InputConstants.MOUSE_BUTTON_RIGHT) {
            return false;
        }
        book.dragging = true;
        book.panelPress = true;
        book.pressMoved = false;
        book.pressedNode = null;
        book.bookCanvas.pressedQuestLink = null;
        book.pressX = mouseX;
        book.pressY = mouseY;
        book.panContentX = book.viewport().contentX(mouseX);
        book.panContentY = book.viewport().contentY(mouseY);
        // A pan is the hand taking the camera: a glide still in flight must not fight it.
        book.glideQuest = null;
        return true;
    }

    /**
     * Whether one of the panel's own drawn targets is under the pointer.
     *
     * <p>Asked of the same list the press methods walk, so the two cannot disagree about what is on screen:
     * this is a membership test over {@code editTargets}, not a second derivation of where a row is. It is
     * what lets an arm answer "did I claim this press" without its press methods having to return anything —
     * and a press that hit no row has to reach {@link #startPanelPan} rather than being swallowed by the
     * panel it happened to be over.
     */
    boolean onEditTarget(double mouseX, double mouseY) {
        for (QuestBookScreen.EditTarget target : book.editTargets) {
            if (target.box().contains(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The same question for the pack's panel, whose rows are its own list rather than the editor's.
     *
     * <p>Two lists rather than one is deliberate and documented at the drawing site: the editor's list still
     * holds the marks the panel behind it drew, and a press inside the pack's panel must not be able to land
     * on one of those.
     */
    boolean onAssetsTarget(double mouseX, double mouseY) {
        for (QuestBookScreen.EditTarget target : book.bookAssets.assetsTargets) {
            if (target.box().contains(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Which submenu should be showing for a pointer at this position.
     *
     * <p>Over a menu row: that row's, if it has one. Anywhere inside the open submenu, in the bridge to
     * it, or on its parent row: unchanged. Anywhere else: shut. The middle case is the one the first
     * version lacked — it asked only "is the pointer on the parent row", so the panel disappeared the
     * moment the pointer left it, which made the destinations unreachable.
     */
    void updateSubmenuHover(double mouseX, double mouseY) {
        if (book.menu.isEmpty()) {
            book.submenuRow = -1;
            return;
        }
        int row = book.bookMenu.menuRowAt(mouseX, mouseY);
        if (row >= 0) {
            book.submenuRow = book.menu.get(row).submenu() ? row : -1;
            return;
        }
        if (book.submenuRow >= 0 && insideOpenSubmenu(mouseX, mouseY)) {
            return;
        }
        book.submenuRow = -1;
    }

    /** Whether the pointer is on the open flyout, or on the way to it. */
    private boolean insideOpenSubmenu(double mouseX, double mouseY) {
        if (book.submenuRow < 0) {
            return false;
        }
        MenuFlyout.Placed placed = book.bookMenu.submenuPlacement(book.submenuRow);
        if (placed.rows().isEmpty()) {
            return false;
        }
        if (placed.contains(mouseX, mouseY)) {
            return true;
        }
        BookGeometry.Rect bridge = submenuBridge(placed);
        return bridge != null && bridge.contains(mouseX, mouseY);
    }

    /**
     * The rectangle between the parent row and its panel.
     *
     * <p>Without it a pointer travelling diagonally crosses a sliver that is neither the row nor the
     * panel, and the submenu shuts under the hand — which is the same fault in a smaller place.
     */
    private BookGeometry.Rect submenuBridge(MenuFlyout.Placed placed) {
        if (placed.rows().isEmpty() || book.submenuRow < 0) {
            return null;
        }
        List<BookGeometry.Rect> main = book.bookMenu.menuRects();
        if (book.submenuRow >= main.size()) {
            return null;
        }
        List<BookGeometry.Rect> rects = placed.rows();
        BookGeometry.Rect parent = main.get(book.submenuRow);
        BookGeometry.Rect last = rects.get(rects.size() - 1);
        int left = Math.min(parent.x(), rects.get(0).x() - 2);
        int right = Math.max(parent.right(), last.right() + 2);
        int top = Math.min(parent.y(), rects.get(0).y() - 3);
        int bottom = Math.max(parent.bottom(), last.bottom() + 3);
        return BookGeometry.Rect.at(left, top, right - left, bottom - top);
    }

    /**
     * The editor's chords a non-text control has no meaning for: the undo pair, and the save report.
     *
     * <p>Deliberately not Delete, Ctrl+D, Ctrl+N, Ctrl+A or Ctrl+V: those are the keys a focused control
     * could be about to use for something of its own, and a screen that deleted a selection because a
     * number field had the keyboard would be the trap the guard above exists to prevent.
     */
    static boolean unclaimedEditorChord(int keyCode, boolean ctrl) {
        if (!ctrl) {
            return false;
        }
        return keyCode == InputConstants.KEY_Z || keyCode == InputConstants.KEY_Y || keyCode == InputConstants.KEY_S;
    }

    /** @return whether the key was one of the editor's, and was handled */
    boolean keysForEditor(net.minecraft.client.input.KeyEvent event) {
        int keyCode = event.key();
        boolean ctrl = event.hasControlDown();

        if (ctrl && keyCode == InputConstants.KEY_S) {
            // Nothing to save, and that is the design rather than an omission: every edit is an operation the
            // server validates and writes as it is made, so there is no unsaved state for this key to reach.
            // Said out loud rather than ignored, because Ctrl+S is the key a person presses when a panel full
            // of edits makes them nervous, and a key that does nothing reads as a key that failed.
            authorReport("Every edit is saved as it is made");
            return true;
        }
        if (ctrl && keyCode == InputConstants.KEY_Z) {
            // The table editor's undo is its own frame's: the file it is showing, and no further back
            // than the table the author opened. See `tableApplied` for why that floor exists. Shift is
            // the redo half here as well, which is the pair every editor has.
            if (book.overlay == PanelKind.TABLE_EDITOR) {
                if (event.hasShiftDown()) {
                    book.bookAssets.tableRedo();
                }
                else {
                    book.bookAssets.tableUndo();
                }
                return true;
            }
            if (event.hasShiftDown()) {
                redoEdit();
            }
            else {
                undoEdit();
            }
            return true;
        }
        if (ctrl && keyCode == InputConstants.KEY_Y) {
            if (book.overlay == PanelKind.TABLE_EDITOR) {
                book.bookAssets.tableRedo();
                return true;
            }
            redoEdit();
            return true;
        }
        // The shifted pair, read before the plain keys below: `Ctrl+N` is a new *quest*, and on the same
        // key the shift is the difference between "a node in this chapter" and "a chapter in this book".
        if (ctrl && event.hasShiftDown() && keyCode == InputConstants.KEY_N) {
            book.bookSidebar.newChapterFromToolbar();
            return true;
        }
        if (ctrl && event.hasShiftDown() && keyCode == InputConstants.KEY_G) {
            book.bookSidebar.newGroupFromToolbar();
            return true;
        }
        if (ctrl && keyCode == InputConstants.KEY_N) {
            createQuest();
            return true;
        }
        if (ctrl && keyCode == InputConstants.KEY_A && book.mayEditNow()) {
            selectAllInChapter();
            return true;
        }
        if (ctrl && keyCode == InputConstants.KEY_C) {
            copySelection();
            return true;
        }
        if (ctrl && keyCode == InputConstants.KEY_V && !ClientEditorClipboard.isEmpty()) {
            pasteClipboard();
            return true;
        }
        if (ctrl && keyCode == InputConstants.KEY_D && QuestBookScreen.selectedQuest != null) {
            // The whole selection duplicates, as one edit per chapter it spans: seventy quests are one
            // gesture, one history step and one save, and one Ctrl+Z takes them all back. See `sendBulk`.
            duplicateSelection(QuestBookScreen.selection());
            return true;
        }
        if ((keyCode == InputConstants.KEY_DELETE || keyCode == InputConstants.KEY_BACKSPACE)
                && QuestBookScreen.selectedQuest != null) {
            // **The first press asks, the second deletes.** This key used to be the one destructive
            // control in the book that acted on a single press — and the one that reaches furthest, since
            // Ctrl+A then Delete takes a whole chapter. `ArmedDelete` holds the rule, the ids that were
            // named and the chapter they were named in, so a second press deletes exactly what the
            // sentence described and nothing else.
            List<String> targets = QuestBookScreen.selection();
            switch (book.armedDelete.press(QuestBookScreen.effectiveChapter(), targets, Util.getMillis())) {
                case ArmedDelete.Armed ignored -> authorReport("Press Delete again to remove"
                        + BookPins.questDeleteNote(targets) + " - Ctrl+Z takes it back");
                case ArmedDelete.Confirmed confirmed -> deleteSelection(confirmed.ids());
                case ArmedDelete.Nothing ignored -> {
                }
            }
            return true;
        }

        // The canvas's keyboard focus, read last so every meaning above wins first: arrows walk the
        // nodes, Enter opens the focused one, Escape drops the focus. Gated on nothing else wanting
        // the keys -- no overlay, no focused widget -- because an arrow key inside a text field is a
        // caret move, and inside a card it belongs to the card. See CanvasFocus.
        if (book.overlay == PanelKind.NONE && book.getFocused() == null) {
            CanvasFocus.Direction direction = null;
            // An if-chain rather than a switch: the SDL constants are not compile-time constants.
            if (keyCode == InputConstants.KEY_LEFT) {
                direction = CanvasFocus.Direction.LEFT;
            }
            else if (keyCode == InputConstants.KEY_RIGHT) {
                direction = CanvasFocus.Direction.RIGHT;
            }
            else if (keyCode == InputConstants.KEY_UP) {
                direction = CanvasFocus.Direction.UP;
            }
            else if (keyCode == InputConstants.KEY_DOWN) {
                direction = CanvasFocus.Direction.DOWN;
            }
            if (direction != null) {
                book.canvasFocus.step(canvasNodes(), direction).ifPresent(this::narrateFocus);
                return true;
            }
            if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
                book.canvasFocus.focused().ifPresent(book.bookRewards::openOverlay);
                return true;
            }
            if (keyCode == InputConstants.KEY_ESCAPE && book.canvasFocus.focused().isPresent()) {
                book.canvasFocus.clear();
                return true;
            }
        }
        return false;
    }

    /**
     * The nodes the arrow keys may stand on: the chapter in view, as it is drawn.
     *
     * <p>{@code questsIn} already answers what is drawn — every quest for an author, the reader's own
     * visible set otherwise — and an {@code invisible} quest is excluded on top of that, because a
     * focus ring around nothing is worse than no focus.
     */
    private List<CanvasFocus.Node> canvasNodes() {
        String chapter = QuestBookScreen.effectiveChapter();
        if (chapter == null) {
            return List.of();
        }
        List<CanvasFocus.Node> nodes = new ArrayList<>();
        for (ClientQuestCache.Entry entry : book.questsIn(chapter)) {
            if (!entry.invisible()) {
                nodes.add(new CanvasFocus.Node(entry.id(), entry.x(), entry.y(), entry.size()));
            }
        }
        return List.copyOf(nodes);
    }

    /**
     * Says the focused node through the narrator, because a custom-drawn canvas is invisible to it.
     *
     * <p>Said on a step and not per frame: narration is a voice, and one that repeated the same
     * sentence sixty times a second would be a stutter. Title and state are enough to know which node
     * the keyboard is on and whether it is worth opening.
     */
    private void narrateFocus(String questId) {
        ClientQuestCache.Entry entry = ClientQuestCache.entry(questId);
        if (entry == null || book.minecraft() == null) {
            return;
        }
        // `say` is gone; the immediate system narration is its successor. Spoken at once rather
        // than queued, because a focus that moves twice in a second should narrate where it is, not
        // where it was.
        book.minecraft().getNarrator().saySystemNow(Component.translatable("tenet.narrate.quest",
                QuestBookScreen.titleOf(entry),
                QuestBookScreen.stateLabel(ClientQuestCache.stateOf(questId))));
    }

    /**
     * Adds a quest where the middle of the view is, which is where the author is looking.
     *
     * <p>The id is the server's to choose — it is the one that knows which names are taken — so this asks and
     * forgets: the reply carries the id, and {@code tick} selects it. Nothing is written here, and there is
     * nothing to save afterwards.
     */
    private void createQuest() {
        float x = book.viewport().contentX(book.canvasLeft() + (book.visibleCanvasRight() - book.canvasLeft()) / 2.0);
        float y = book.viewport().contentY(book.canvasTop() + (book.canvasBottom() - book.canvasTop()) / 2.0);
        // A new quest lands on the grid like a moved one: a position an author would have typed, by
        // the same rule, from the same switch — and free under Alt, like everywhere else.
        boolean on = snappingNow();
        book.send(new EditorOp.Create(Math.round(BookGeometry.snap(x, BookGeometry.SNAP_GRID, on)),
                Math.round(BookGeometry.snap(y, BookGeometry.SNAP_GRID, on))));
    }

    /**
     * The same, aimed at the chapter that owns the edit.
     *
     * <p>The chapter is a parameter because a selection can name quests in another chapter — a
     * multi-selection survives a chapter change — and a bulk op has to reach the editor that holds those
     * quests, not the one the author happens to be looking at.
     */
    void send(EditorOp op, String chapter) {
        // Recorded so the reply can be told apart from a table op's: the two travel on one payload and
        // answer in order, and a table's answer carries a table id in the field a quest's carries a
        // quest id. See the reply loop for what each kind does to the table panel. One marker per
        // payload: a gesture that becomes several payloads gets several answers, in order.
        //
        // **Every op in the book goes through here**, so this one guard is what keeps the queue and the
        // wire in step: a request the queue refused means this op is not sent, and the requests already in
        // flight stay matched to their own answers. The id minted here is what the reply echoes, so the
        // answer is matched to this op however many others are in flight. See `ClientEditReplies.noteSent`.
        ClientEditReplies.Request request = ClientEditReplies.noteSent("");
        if (request == null) {
            return;
        }
        // Empty rather than null when the book has no chapters yet. The payload's chapter is the
        // session a structural edit is recorded on, and "no session" is the empty string the server
        // reads the same way -- which is what lets an empty pack make its first chapter.
        TenetNetworking.sendEditorOp(chapter == null ? "" : chapter, op, request.id());
    }

    /**
     * A bulk gesture: every id in the selection, as <b>one</b> edit per chapter.
     *
     * <h2>Why the selection is not simply looped over</h2>
     *
     * <p>Because a gesture is not an operation. Looping sent one op per quest, and each op cost a history
     * snapshot and a save — so "duplicate these seventy" was seventy presses of Ctrl+Z to take back,
     * which is not an undo. One batch per chapter is one step per chapter, which is what the model can
     * honestly offer: the history lives in a chapter's editor.
     *
     * <p>A chapter whose share of the selection is a single quest is sent as that plain op rather than as
     * a one-element batch, because a create or a duplicate answers with the id it made — that is what
     * selects the new node on the canvas. See {@code EditorOps.batch}.
     */
    void sendBulk(List<String> ids, java.util.function.Function<String, EditorOp> opFor) {
        java.util.Map<String, List<String>> byChapter =
                dev.ellipog.tenet.client.dev.BatchPlan.byChapter(ids, id -> {
                    ClientQuestCache.Entry entry = book.entryFor(id);
                    return entry == null ? null : entry.chapterId();
                });
        for (java.util.Map.Entry<String, List<String>> chapter : byChapter.entrySet()) {
            List<EditorOp> ops = new ArrayList<>(chapter.getValue().size());
            for (String id : chapter.getValue()) {
                ops.add(opFor.apply(id));
            }
            send(dev.ellipog.tenet.editor.EditorOps.batch(ops), chapter.getKey());
        }
    }

    /**
     * Ctrl+Z: one step back, with this client's pending values dropped first.
     *
     * <p>The drafts go before the op goes out, for the reason {@code tableUndo} gives for the table's:
     * the server is about to put the file back, and a pending value still holding what was undone would
     * keep drawing it — a Ctrl+Z that looks like it did nothing.
     */
    private void undoEdit() {
        QuestBookScreen.fieldDraft.forgetChapter(QuestBookScreen.effectiveChapter());
        QuestBookScreen.elementDraft.clear(QuestBookScreen.effectiveChapter());
        QuestBookScreen.linkDraft.clear(QuestBookScreen.effectiveChapter());
        book.settingsDraft.clear();
        book.send(new EditorOp.Undo());
    }

    /** The same, forward. */
    private void redoEdit() {
        QuestBookScreen.fieldDraft.forgetChapter(QuestBookScreen.effectiveChapter());
        QuestBookScreen.elementDraft.clear(QuestBookScreen.effectiveChapter());
        QuestBookScreen.linkDraft.clear(QuestBookScreen.effectiveChapter());
        book.settingsDraft.clear();
        book.send(new EditorOp.Redo());
    }

    /**
     * The same, for an edit aimed at another chapter: a dependency pick taken in one chapter can be
     * for a quest in the chapter it was opened from, and the draft has to live under the chapter whose
     * replica will answer for it.
     */
    void sendField(String chapter, String quest, String path, JsonElement value) {
        if (quest == null) {
            return;
        }
        QuestBookScreen.fieldDraft.set(chapter, quest, path, value,
                ClientQuestCache.treeRevision(), Util.getMillis());
        // The draft is stamped whether or not the op goes: it is the *preview*, and a value the author
        // asked for should show even if the request could not be sent. What must not happen is an op
        // without its request, so the send is what a refused one stops.
        ClientEditReplies.Request request = ClientEditReplies.noteSent("");
        if (request == null) {
            return;
        }
        TenetNetworking.sendEditorOp(chapter == null ? "" : chapter,
                new EditorOp.SetField(quest, path, value), request.id());
    }

    /** The book's name as its field should show it: the pending edit first, then the tree's own. */
    String bookTitleValue() {
        // Raw rather than resolved: the field edits the file, and a translation written back would
        // replace the author's own words with somebody else's translation of them.
        return QuestBookScreen.fieldDraft.text(QuestBookScreen.effectiveChapter(),
                dev.ellipog.tenet.client.dev.FieldDraft.BOOK_OWNER, "bookTitle",
                ClientQuestCache.bookTitleRaw());
    }

    /** The book's LOD answer as its switch should show it: the pending edit first, then the tree's own. */
    boolean bookLodValue() {
        return QuestBookScreen.fieldDraft.flag(QuestBookScreen.effectiveChapter(),
                dev.ellipog.tenet.client.dev.FieldDraft.BOOK_OWNER, "disableCanvasLod",
                ClientQuestCache.bookLodDisabled());
    }

    /** The first-join book answer as its switch should show it: the pending edit first, then the tree's own. */
    boolean bookStarterValue() {
        return QuestBookScreen.fieldDraft.flag(QuestBookScreen.effectiveChapter(),
                dev.ellipog.tenet.client.dev.FieldDraft.BOOK_OWNER, "giveBookOnFirstJoin",
                ClientQuestCache.bookOnFirstJoin());
    }

    /** Commits the book's name. Blank clears it, so the client's own title comes back. */
    void commitBookTitle(String text) {
        sendBookField("bookTitle", text.isBlank() ? null : new JsonPrimitive(text));
        book.status(text.isBlank() ? "Book name cleared" : "Book name: " + text, false);
        book.rebuildWidgets();
    }

    /**
     * Writes one key of the pack's own book settings, through the same editor op every other edit uses.
     *
     * <p>The draft is scoped to the open chapter, which is the only scope the draft has; the value it
     * holds converges like any other, and expires if no copy confirms it. Unlike a chapter field, this
     * edit is not on the undo stack — the history is chapter- and group-shaped and the settings block
     * is not modelled — which the Book section's own docs say plainly.
     */
    void sendBookField(String key, JsonElement value) {
        if (!book.mayEditNow()) {
            book.status("Edit mode is off, so the book's settings were not written", true);
            return;
        }
        QuestBookScreen.fieldDraft.set(QuestBookScreen.effectiveChapter(),
                dev.ellipog.tenet.client.dev.FieldDraft.BOOK_OWNER,
                key, value, ClientQuestCache.treeRevision(), Util.getMillis());
        book.send(new EditorOp.SetIndex(key, value));
    }

    /**
     * Commits a finished drag.
     *
     * <p>The position is rounded to whole content units, which is the grid a quest file is authored on:
     * a node at x=37.4182 is a number nobody typed on purpose, and the canvas is drawn at a zoom where
     * the difference is invisible — so the file would carry noise that only shows up in a diff.
     */
    private void commitMove(String id, float x, float y) {
        commitMove(id, x, y, ClientQuestCache.treeRevision());
    }

    /** The same, at a revision the caller already read: a selection's nodes commit against one tree. */
    private void commitMove(String id, float x, float y, long revision) {
        if (!book.mayEditNow()) {
            return;
        }
        recordMove(id, x, y, revision);
        book.send(new EditorOp.Move(id, Math.round(x), Math.round(y)));
    }

    /**
     * Remembers where a node was moved to, without sending anything.
     *
     * <p>Split from the send because a <b>multi-node drag commits as one batch</b> and every node in it still
     * has to be recorded as pending: the canvas draws these positions until the server's tree agrees, so a
     * node that is moved but not recorded snaps back on the next frame while its neighbour does not.
     */
    private void recordMove(String id, float x, float y, long revision) {
        book.editors.moved(id, Math.round(x), Math.round(y), revision);
    }

    /**
     * Commits a whole drag as <b>one operation per chapter</b> rather than one per node.
     *
     * <h2>Why a gesture is not an operation</h2>
     *
     * <p>Dragging a selection of seventy quests used to send seventy ops, and each one cost a history snapshot
     * and a save, so taking the drag back was seventy presses of Ctrl+Z, which is not an undo. The history
     * lives in a chapter's editor, so one batch per chapter is the honest granularity: a selection spanning two
     * chapters is two steps, which is what the model can offer.
     *
     * <p>The moves are recorded here and the ops sent as a batch, in that order, so the positions the canvas
     * draws and the positions the server is asked for are the same list. Recording without sending would leave
     * the tree disagreeing for a round trip; sending without recording would leave the canvas showing the
     * server's old answer for the same round trip.
     *
     * <p>A chapter whose share of the drag is one node is sent as that plain op rather than as a one-element
     * batch, which is the rule {@code sendBulk} follows and for the same reason: a gesture with one node in
     * this chapter is one edit.
     */
    void commitMoveBatch(String draggedId, List<String> moved, float dragX, float dragY,
                                 long revision) {
        if (!book.mayEditNow()) {
            return;
        }
        // **The decision is `MoveCommit`'s; this method has only the two side effects left.** That split is
        // the whole point of the class: both faults this path has had were decisions — a position invented
        // for a node that had none, and a dragged node that never entered the list — and neither was visible
        // to a test because the decision and the write lived in the same place, on a screen no test builds.
        java.util.function.Function<String, String> chapterOf = id -> {
            ClientQuestCache.Entry entry = book.entryFor(id);
            return entry == null ? null : entry.chapterId();
        };

        // The selection's positions are the ones the canvas is drawing it at -- the pending values, guarded,
        // because `movedX` answers zero for an id it does not hold and that zero was the (0,0) fault. A node
        // with no pending position is not passed at all, so the planner has nothing to invent a coordinate
        // from.
        List<dev.ellipog.tenet.client.dev.MoveCommit.Move> selection = new ArrayList<>();
        for (String id : moved) {
            if (book.editors.hasMoved(id)) {
                selection.add(new dev.ellipog.tenet.client.dev.MoveCommit.Move(
                        id, (float) book.editors.movedX(id), (float) book.editors.movedY(id)));
            }
        }

        // Recorded and sent from the same grouping, so the canvas and the wire cannot disagree: recording
        // without sending leaves the tree wrong for a round trip, and sending without recording leaves the
        // canvas showing the server's old answer for the same round trip.
        for (dev.ellipog.tenet.client.dev.MoveCommit.Move move
                : dev.ellipog.tenet.client.dev.MoveCommit.carried(
                        draggedId, dragX, dragY, selection, chapterOf)) {
            recordMove(move.id(), move.x(), move.y(), revision);
        }
        for (dev.ellipog.tenet.client.dev.MoveCommit.Chapter chapter
                : dev.ellipog.tenet.client.dev.MoveCommit.plan(
                        draggedId, dragX, dragY, selection, chapterOf)) {
            send(chapter.op(), chapter.chapterId());
        }
    }

    // ------------------------------------------------------------------
    // The multi-selection's whole-hand gestures
    // ------------------------------------------------------------------

    /**
     * Adds one quest to the selection, which is what a shift-press on a node means.
     *
     * <p>Additive and idempotent: shift-clicking around a chapter collects nodes, and shift-clicking
     * one that is already selected changes nothing -- taking something out is Ctrl-click's job. The
     * first quest ever added becomes the primary, which is the one the property panel edits.
     */
    static void addToSelection(String id) {
        if (QuestBookScreen.selectedQuest == null) {
            QuestBookScreen.selectedQuest = id;
        }
        else if (!BookCanvas.isSelected(id)) {
            QuestBookScreen.multiSelection.add(id);
        }
    }

    /**
     * Turns one quest's selection on or off, which is what a ctrl-click on a node means.
     *
     * <p>The one gesture that subtracts a single node, and the reason the edge drag moved to the right
     * button. A primary that is toggled off hands its slot to an extra rather than leaving the panel
     * with no target, so the selection and the panel cannot disagree about what is being edited.
     */
    static void toggleSelection(String id) {
        if (QuestBookScreen.multiSelection.remove(id)) {
            return;
        }
        if (id.equals(QuestBookScreen.selectedQuest)) {
            QuestBookScreen.selectedQuest = QuestBookScreen.multiSelection.isEmpty() ? null
                    : QuestBookScreen.multiSelection.iterator().next();
            QuestBookScreen.multiSelection.remove(QuestBookScreen.selectedQuest);
            return;
        }
        if (QuestBookScreen.selectedQuest == null) {
            QuestBookScreen.selectedQuest = id;
        }
        else {
            QuestBookScreen.multiSelection.add(id);
        }
    }

    /**
     * Adds every node the marquee rectangle covers to the selection.
     *
     * <p>The rectangle is in screen coordinates and so are the nodes' hit rects, which is what makes
     * "inside" one question: the same space on both sides, and a zoom that happened during the drag
     * already accounted for. Shape-exact is the wrong strictness here -- a marquee is about regions, and
     * a node half-covered is a node the rectangle was reaching for -- so this is the bounding box, not
     * the shape's own span test.
     *
     * <p>Additive, and by definition of the gesture: the marquee only starts under shift, and the
     * modifier is read at the press rather than at the release, so a marquee that ends with shift let
     * go still adds rather than replaces. To replace, clear first -- a plain click on the canvas.
     * {@code addToSelection} gives the first covered node the primary slot when nothing was selected,
     * so a marquee onto an empty selection behaves like a marquee onto nothing at all.
     */
    void selectInsideMarquee() {
        int left = Math.min(book.marqueeX0, book.marqueeX1);
        int right = Math.max(book.marqueeX0, book.marqueeX1);
        int top = Math.min(book.marqueeY0, book.marqueeY1);
        int bottom = Math.max(book.marqueeY0, book.marqueeY1);
        // A click's worth of rectangle selects nothing: that press was a mis-aimed click, not a
        // marquee, and wiping the selection for it would be a gesture that punishes.
        if (right - left < QuestBookScreen.DRAG_THRESHOLD && bottom - top < QuestBookScreen.DRAG_THRESHOLD) {
            return;
        }
        for (ClientQuestCache.Entry entry : book.questsIn(QuestBookScreen.effectiveChapter())) {
            int x = book.bookCanvas.nodeScreenX(entry);
            int y = book.bookCanvas.nodeScreenY(entry);
            int size = book.bookCanvas.nodeSize(entry);
            if (x + size > left && x < right && y + size > top && y < bottom) {
                addToSelection(entry.id());
            }
        }
    }

    /**
     * Pastes the clipboard, where the author is looking.
     *
     * <p>One op per quest, staggered a node apart so a multi-paste is visibly several quests rather
     * than one drawn over another -- the same step a duplicate takes. The ids are the server's to
     * choose, which is what makes pasting into the same chapter twice come out with different names
     * instead of one quest written over another.
     */
    private void pasteClipboard() {
        pasteAt(book.viewport().contentX(book.canvasLeft()
                + (book.visibleCanvasRight() - book.canvasLeft()) / 2.0),
                book.viewport().contentY(book.canvasTop() + (book.canvasBottom() - book.canvasTop()) / 2.0));
    }

    /** Something changed in the editor that the canvas has to redraw. */
    private void movedByEditor() {
        book.rebuildWidgets();
    }

    /**
     * A sentence only an author can act on: said to a player who may edit, and to nobody else.
     *
     * <h2>Why this is a door rather than a flag at each call site</h2>
     *
     * <p>Because the facts are the pack's and the <i>acting</i> is not. The server used to send the pack's
     * faults to every player — its own note argued that an author who did not cause one still has to work
     * with the tree they describe — and a player who may not edit cannot work with the tree at all, so what
     * they got was a wall of file paths, line numbers and JSON paths with nothing they could do about any of
     * it. That is fixed at the sender as well, and this is the half that holds if a server over-sends.
     *
     * <p>{@link QuestBookScreen#mayEdit} rather than {@code mayEditNow}: an operator who is not in edit mode is still an
     * operator, and edit mode is a view of the editor rather than a permission.
     *
     * <p>Player-facing sentences do not come through here: a claim's summary, a reward that overflowed, a
     * choice's verdict, and the optimistic-mark backstop are all things the player did.
     */
    void authorToast(String message, boolean error) {
        if (book.questEditor.mayEdit()) {
            book.toast(message, error);
        }
    }

    /** The same, for a sentence rather than a complaint. */
    void authorReport(String message) {
        authorToast(message, false);
    }

    /**
     * The book's own messages, drawn over the book and under the tooltips.
     *
     * <p>Under the tooltips because a tooltip is what the pointer is asking for and a toast is what just
     * happened. Centred on the screen and standing on its bottom edge, where the eye already is when the
     * hand is on the canvas; growing upward, so the newest sentence sits where the eye is and the ones
     * being read move up rather than being pushed away.
     */
    void drawToasts(GuiRenderer r, long now) {
        List<ToastStack.Toast> visible = book.toasts.visible(now);
        if (visible.isEmpty()) {
            return;
        }
        // A quarter of the book, capped: the box has to be narrow enough not to read as a dialogue and wide
        // enough for a sentence. It was a quarter of the centred card, which was a quarter of the whole
        // window at a reader's size -- so this is the same number at the same size and an honest one at any
        // other, rather than a card the class no longer has. The two bounds are `ToastArt`'s, because the
        // HUD's notice element is the second caller and the two must not disagree about what a notice is.
        int boxWidth = Math.min(Math.max(ToastArt.MIN_WIDTH, book.geometry().panel().width() / 4),
                ToastArt.MAX_WIDTH);
        // Anchored to the window's bottom edge, newest where the eye is, growing upward -- and the drawing
        // itself is `ToastArt`'s, shared with the HUD's own stack. See that class for why. Full strength:
        // the book's stack has no dimmer, and the HUD passes its own.
        int firstTop = book.height - 10 - ToastArt.LINE - (visible.size() - 1) * (ToastArt.LINE + ToastArt.GAP);
        ToastArt.draw(r, book.textMeasure(r), visible, (book.width - boxWidth) / 2, firstTop, boxWidth,
                ClientAppearance.LOOK.motion(), now, 1.0);
    }

    /**
     * What an accepted table reply does to the panel: the frame's budget, and the conversions.
     *
     * <p>The budget moves only on an <b>accepted</b> reply, which is what makes it honest: a refused
     * edit changed nothing on disk, so counting it would let Ctrl+Z reach past the table the author
     * opened into the file's earlier history — an undo of somebody else's edit, off screen.
     *
     * <p>The two conversions move the frame rather than closing it, and they do it here rather than when
     * the op was sent because the id is the server's to choose: `Make inline` mints a handle, `Save as
     * preset` picks the name. Doing it now means the reload that follows finds a frame that already
     * points at the table that exists, instead of a handle that no longer does.
     */
    void applyTableReply(String kind, EditorReplyPayload reply) {
        if (!reply.ok()) {
            return;
        }
        switch (kind) {
            case "Undo" -> {
                book.tableApplied = Math.max(0, book.tableApplied - 1);
                book.tableUndone++;
            }
            case "Redo" -> {
                book.tableApplied++;
                book.tableUndone = Math.max(0, book.tableUndone - 1);
            }
            default -> {
                book.tableApplied++;
                book.tableUndone = 0;
            }
        }
        if (reply.questId().isEmpty() || book.tableAddress == null) {
            return;
        }
        // No conversion moves the frame any more: a table is a file, and the ops that made one from a
        // reward (`Inline`) or a reward's table from a file (`Extract`) are gone with inline tables.
    }

    /**
     * Whether the drag in progress began on the open field's own frame, so it extends the field's mark
     * rather than panning the canvas or moving a node. Set by the press the field took -- the same test
     * that places the caret -- and cleared when it is let go.
     */
    private boolean fieldDrag;

    /** The link the press landed on, opened when it is released on the same rectangle. */
    private QuestBookScreen.LinkRect pressedLink;

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
    private int panelDragStartWidth;
    private double panelDragStartX;
    private int sidebarDragStartWidth;
    private double sidebarDragStartX;

    /**
     * The element the press landed on, until the release decides what it meant.
     *
     * <p>Beside {@link QuestBookScreen#pressedNode} and read the same way, because an element is claimed by the same gesture:
     * a press that travelled is a pan, and one that did not is a click — which selects in edit mode and runs
     * the element's action in play mode. Only one of the two is ever set, because an element is only reached
     * when no node was under the pointer.
     */
    private String pressedElement;

    /** The field a label press claimed, between its press and its release. */
    private dev.ellipog.tenet.client.dev.ScrubField labelScrub;

    /** Whether the press being released was a right-press on the canvas, for the click-vs-drag split. */
    private boolean canvasRightPressed;
    private boolean bendDragLive;

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
        book.clearFrameSnapshot();
        book.questEditor.buildSidebarWidgets();
        book.questEditor.buildViewCluster();
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
        book.setSidebarCollapsed(!DevMode.isSidebarCollapsed());
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

    /** The same, from the box's own square and Ctrl+B; docked, there is no pin to toggle. */
    private void toggleSidebarPinned() {
        if (!DevMode.isSidebarFloating()) {
            return;
        }
        QuestBookScreen.SidebarPinAfter after = QuestBookScreen.pinAfterToggle(DevMode.isSidebarPinned(),
                DevMode.isSidebarCollapsed(), book.sidebarPeeking);
        setSidebarPinned(after.pinned());
        if (after.pinned() && DevMode.isSidebarCollapsed()) {
            // Promoting a sliver or its hover preview into a real open box. The preview's
            // rows are already built and no rail moves either way, so this is the sidebar's
            // own rebuild rather than the whole screen's: a full one hitches visibly on a
            // press whose picture barely changes, which reads as the pin lagging.
            book.beginSidebarSlide(); // from the drawn preview or sliver, before the triple lands
            book.sidebarPeeking = after.peeking();
            DevMode.setSidebarCollapsed(after.collapsed());
            book.sidebarBoxHovered = false;
            book.sidebarPeekLeaveStart = -1;
            book.sidebarDeparting = false;
            // An arrival, like every other: a pin mid-collapse cuts the departure short, and the
            // box rebuilds its rows live below — orphans filed for the interrupted wipe would draw
            // over them.
            book.sidebarDeparted.clear();
            book.sidebarDepartAnchor = null;
            rebuildSidebarAndCluster();
            book.cancelSidebarSlideIfStatic();
        }
        else {
            book.sidebarPeeking = after.peeking();
            if (DevMode.isSidebarCollapsed() != after.collapsed()) {
                book.setSidebarCollapsed(after.collapsed());
            }
        }
    }

    /**
     * Offers a press to one bar, and captures it if the bar takes it.
     *
     * <h2>Why every press goes through here</h2>
     *
     * <p>Because a press that a bar takes has to become <b>this screen's</b> gesture rather than the
     * bar's alone: the move, the release and the hold-repeat are routed from {@link QuestBookScreen#heldBar}, and a
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
        book.heldBar = bar;
        return true;
    }

    /**
     * The scroll view a pointer is over: the left column left of the split, the right column after.
     *
     * <p>The two columns scroll independently because they are different lists; a wheel aimed at the
     * roster must not move the settings out from under it. A pointer outside the card still lands on
     * one of them by this rule, which is the same thing the old single view did (clamped).
     */
    private ScrollView partyScrollAt(double mouseX) {
        BookGeometry.Rect body = book.partyBody();
        if (body == null || book.partyRightFace == null) {
            // One column of content: there is no split to aim at, and a bar drawn on the right half of a
            // panel with nothing in it would be a scrollbar for a view nobody can see.
            return book.partyLeftView;
        }
        int split = body.x() + PartyPanelLayout.leftWidth(body.width()) + PartyPanelLayout.COLUMN_GAP / 2;
        return mouseX < split ? book.partyLeftView : book.partyRightView;
    }

    /** A colour a picker is dragging: the same patch shape {@code writeThemeToken} writes. */
    private void previewToken(String token, int argb) {
        JsonObject patch = book.previewBase();
        if (patch == null) {
            return;
        }
        JsonObject colours = patch.has("colours") && patch.get("colours").isJsonObject()
                ? patch.getAsJsonObject("colours") : new JsonObject();
        colours.addProperty(token, String.format("#%08X", argb));
        patch.add("colours", colours);
        book.previewChapterPatch(patch);
    }

    // --- the chips and the picker

    /** Whether a press landed on a colour chip: the one rectangle a chip owns. */
    private boolean pressToolsChip(double mouseX, double mouseY) {
        for (ToolsLayout.Action row : book.activeRows()) {
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
                book.bookPins.openElementColourPopover(row, book.toolsView, chipMode(row.key()), chip);
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
        Layout active = book.activeLayout();
        if (active == null || row == null || !row.isChip()) {
            return null;
        }
        dev.ellipog.armature.client.ui.kit.Slot slot = active.slot(row.key());
        if (slot == null) {
            return null;
        }
        dev.ellipog.armature.client.ui.kit.Slot onScreen =
                ToolsLayout.onScreen(book.toolsView.viewport(), slot);
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
        return book.toolsTab == ToolsLayout.Tab.CHAPTER && ChapterPanelLayout.elementFieldOf(key) != null
                ? dev.ellipog.armature.client.ui.inspect.InspectLayout.Mode.STACKED
                : dev.ellipog.armature.client.ui.inspect.InspectLayout.Mode.SIDE_BY_SIDE;
    }

    /** A field's label, armed for a scrub: the box is the widget's, the label is the screen's. */
    private boolean armLabelScrub(double mouseX, double mouseY) {
        for (dev.ellipog.tenet.client.dev.ScrubField field : book.rowFields) {
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
        book.closeColourPopover();
        int argb = book.questEditor.editedTheme().colour(token);
        book.popoverChapter = QuestBookScreen.effectiveChapter();
        book.popoverChip = new QuestBookScreen.PopoverChip(row, book.toolsView, chipMode(row.key()));
        book.colourPopover.open(anchor, book.geometry().canvas(), BookQuestEditor.labelOfToken(token), argb,
                List.of(book.questEditor.editedTheme().colour("panel"), book.questEditor.editedTheme().colour("raised"),
                        book.questEditor.editedTheme().colour("title"), book.questEditor.editedTheme().colour("accent")),
                value -> previewToken(token, value), value -> book.questEditor.writeThemeToken(token, value));
        book.buildPopoverWidgets();
        book.filePopoverArrival();
        // Everything the picker now covers stops answering presses: see `refreshOverlayMute`.
        book.refreshOverlayMute();
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        // The intro's buttons first, and only they: a press on Skip, Next or the checkbox is the
        // tour's, while every other press — including the press on the + Chapter strip or the
        // canvas the tour is pointing at — falls through to the book untouched. Asked before the
        // blur below, because a press on the tour is not a press away from a field.
        if (IntroGuide.isActive() && book.bookIntro.pressIntroGuide(mouseX, mouseY, button)) {
            return true;
        }
        // A press anywhere is "no" to an armed delete, which is the rule the menu's own Delete states: any
        // other press disarms. A press that selects a node does not need to re-arm by hand -- the next
        // Delete key press asks about the selection it made.
        book.armedDelete.disarm();
        fieldDrag = false;
        pressedLink = null;
        // Every gesture starts by forgetting the last one's: a press that ended over a column must not make
        // the next release look like one.
        book.panelPress = false;
        // A new press ends a row drag that never got its release -- one gesture at a time, and the row
        // rectangles a stale drag would aim at belong to the frame it started in.
        book.dragRowFrom = -1;
        book.dragRowMember = null;
        book.dragRowQuest = null;
        book.dragRowLive = false;

        // Clicking off a text field finishes with it: the blur is the commit, and a field that keeps
        // the keyboard after the pointer has moved on is a field that eats the next key typed. Before
        // anything else, so the press that closes one editor can also open the next.
        //
        // "Off it" is asked of the field's **frame** as well as of the widget's own box: they are the
        // same rectangle for the description (its frame *is* its box), and asking the target is what
        // keeps a press on the border or a pixel out of the widget's own hit test from being read as a
        // press away from the field.
        if (button == InputConstants.MOUSE_BUTTON_LEFT && book.getFocused() instanceof net.minecraft.client.gui.components.AbstractWidget focused
                && !focused.isMouseOver(mouseX, mouseY) && !book.editorControls.onOpenEditor(mouseX, mouseY)) {
            book.setFocused(null);
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
        if (book.pressDropdown(mouseX, mouseY, button)) {
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
        if (button == InputConstants.MOUSE_BUTTON_LEFT && book.editorControls.onOpenEditor(mouseX,
                mouseY) && book.editorControls.moveOpenCaret(mouseX, mouseY)) {
            // And the drag that may follow this press belongs to the field: the same press, continued.
            fieldDrag = true;
            return true;
        }
        // Whether this press is the open colour picker's, wherever it floats. Rails float over the
        // canvas the picker is clamped to, so the picker can slide under one; the grip below and the
        // surface block past the widget pass are what keep that press the picker's.
        boolean onPicker = book.colourPopover.isOpen()
                && book.colourPopover.panel().contains(book.pickerPointerX(mouseX), mouseY);
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
        PanelKind grab = button == InputConstants.MOUSE_BUTTON_LEFT && !onPicker ? book.panelColumnAt(mouseX, mouseY) : null;
        if (grab != null && BookGeometry.panelHandle(book.panelRail(grab)).contains(mouseX, mouseY)) {
            BookGeometry.Rect rail = book.panelRail(grab);
            book.panelDragRail = rail;
            // Which rail the hand is on, so the drag below asks *its* kind what a legal width is and feeds
            // the answer back to the rail the hand is holding.
            book.panelDragKind = grab;
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
        if (button == InputConstants.MOUSE_BUTTON_LEFT && !onPicker && book.geometry().sidebarState().expanded()
                && book.sidebarSlideStart < 0
                && BookGeometry.sidebarHandle(book.geometry().sidebar()).contains(mouseX, mouseY)) {
            book.sidebarDragRail = book.geometry().sidebar();
            sidebarDragStartWidth = book.geometry().sidebarWidth();
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
        if (book.overlay != PanelKind.NONE || book.overlay2 != PanelKind.NONE || book.dockOpen) {
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
            PanelKind on = book.panelColumnAt(mouseX, mouseY);
            if (on != null) {
                book.surfaceKind = on;
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
            if (button == InputConstants.MOUSE_BUTTON_LEFT && on == PanelKind.QUEST && !book.mayEditNow()) {
                for (QuestBookScreen.LinkRect link : book.linkRects) {
                    if (link.box().contains(mouseX, mouseY)) {
                        pressedLink = link;
                        return true;
                    }
                }
            }
            // Whether this press is the armed Disband's, asked before the press is handled: a press on
            // anything else is a change of mind, and the label a player is looking at has to match what
            // the next press on Disband will do.
            boolean onDisband = book.disbandButton != null && book.disbandButton.isMouseOver(mouseX, mouseY);
            boolean onQuestDelete = book.questDeleteButton != null
                    && book.questDeleteButton.isMouseOver(mouseX, mouseY);
            if (book.superMouseClicked(event, doubleClick)) {
                // One of the panel's own controls took it.
                if (!onDisband) {
                    book.questEditor.disarmDisband();
                }
                // The armed Delete works like the armed Disband: any other press is a change of mind,
                // and the label on screen has to match what the next press on Delete will do.
                if (!onQuestDelete && book.confirmingDelete) {
                    book.confirmingDelete = false;
                    book.rebuildWidgets();
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
                book.colourPopover.mouseClicked(BookEditorControls.shiftedClick(book.pickerPointerX(mouseX), mouseY, event));
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
                ScrollBar panelBar = on == PanelKind.ASSETS ? book.bookAssets.assetsBar
                        : on == PanelKind.TABLE_BROWSER ? book.tableBrowserBar
                        : book.tableShowRoll ? book.rollBar : book.tableEditorBar;
                if (pressBar(panelBar, mouseX, mouseY)) {
                    return true;
                }
                // Whether a drawn target is under the pointer, asked of the same list the press methods walk
                // — so the two cannot disagree about what is on screen. It is what decides whether this arm
                // claims the press: the press methods themselves answer nothing, and a press that hit no row
                // must reach the tail rather than being swallowed by the panel it happened to be over.
                boolean onRow = on == PanelKind.ASSETS
                        ? book.bookInput.onAssetsTarget(mouseX, mouseY) : book.bookInput.onEditTarget(mouseX, mouseY);
                if (onRow) {
                    if (on == PanelKind.TABLE_BROWSER) {
                        book.bookRewards.pressTableBrowser(mouseX, mouseY);
                    }
                    else if (on == PanelKind.ASSETS) {
                        book.bookAssets.pressAssets(mouseX, mouseY);
                    }
                    else {
                        book.bookAssets.pressTableEditor(mouseX, mouseY);
                    }
                    return true;
                }
            }
            else if (on == PanelKind.CHOICE && button == InputConstants.MOUSE_BUTTON_LEFT) {
                // The rows are drawn by this screen -- see `drawChoiceRow` -- so the press is what answers.
                if (pressBar(book.choiceView.bar(), mouseX, mouseY)) {
                    return true;
                }
                for (int i = 0; i < book.choiceRowRects.size(); i++) {
                    if (book.choiceRowRects.get(i).contains(mouseX, mouseY)) {
                        if (i < book.choiceRows.size()) {
                            book.bookOverlay.pressChoiceRow(book.choiceRows.get(i).key());
                        }
                        return true;
                    }
                }
            }
            else if (on == PanelKind.REWARDS && button == InputConstants.MOUSE_BUTTON_LEFT) {
                // A header's body folds its rewards, and this is the press the strip's widget did not
                // take -- so a click on Claim Quest can never also fold the row it belongs to. The rows'
                // buttons are widgets and answer on release.
                if (book.bookRewards.toggleRewardRow(mouseX, mouseY)) {
                    return true;
                }
                // A reward's body claims it, exactly as its Claim button does -- the checkmark row's
                // rule, one menu over. Only a row whose button is live answers; anything else falls
                // through to the pan tail below, so a collected or gated row never claims or errors.
                if (book.bookRewards.pressRewardRow(mouseX, mouseY)) {
                    return true;
                }
                if (pressBar(book.rewardView.bar(), mouseX, mouseY)) {
                    return true;
                }
            }
            else if (on == PanelKind.TEXTURE && button == InputConstants.MOUSE_BUTTON_LEFT) {
                // The list's bar before the rows: it is the one control here whose target is a pixel wide
                // three times over, and a press aimed at it must not start a pan.
                if (pressBar(book.textureBar, mouseX, mouseY)) {
                    return true;
                }
                // From the last frame's own list, so a press lands on the row it was drawn under.
                int row = book.textureFrame == null ? -1
                        : ItemPickerLayout.rowAt(book.textureRows, book.textureFrame, book.textureBody.scrollY(), mouseY);
                if (row >= 0) {
                    book.texturePicker.pressTextureRow(row);
                    return true;
                }
            }
            else if (on == PanelKind.FILTER && button == InputConstants.MOUSE_BUTTON_LEFT) {
                // The accepted-items list's bar before its rows, then the rows from the last
                // frame's own drawing. A press that hits no row is not the panel's, so it reaches
                // the tail and the canvas stays pannable from over the list's gaps.
                if (pressBar(book.filterView.bar(), mouseX, mouseY)) {
                    return true;
                }
                if (book.bookSettings.onFilterListRow(mouseX, mouseY)) {
                    book.bookSettings.pressFilterListRow(mouseX, mouseY);
                    return true;
                }
            }
            else if ((on == PanelKind.QUEST || on == PanelKind.PICKER) && book.mayEditNow() && button == InputConstants.MOUSE_BUTTON_LEFT) {
                // The type page's bar, then its rows: it is the list on screen, and a press aimed at its
                // grip is the list's rather than the page's.
                if (book.typePageOpen()) {
                    if (pressBar(book.typeBar, mouseX, mouseY)) {
                        return true;
                    }
                    if (book.editorControls.pressTypePicker(mouseX, mouseY)) {
                        return true;
                    }
                }
                // The item picker's rows, from the last frame's own drawing -- and when the pick is a rail
                // of its own, only *that* rail takes it: a press in the panel's own rail belongs to the
                // panel, which is the whole point of having both on screen.
                else if (book.pickingItemPath != null && (on == PanelKind.PICKER || !book.itemPicker.pickerAsColumn())) {
                    // The kind tabs before the bar and the rows: a press on the strip switches lists,
                    // and the rows below are already the new tab's by the next frame. The arm's own
                    // rail rule applies — no stricter gate here, or the tabs would go dead exactly
                    // where the rows stay live.
                    if (book.pickerTabsVisible() && book.bookSettings.pressPickerTab(mouseX, mouseY)) {
                        return true;
                    }
                    // The sprite tab has no list rows: only its clear row answers, and anything
                    // else is not the picker's, for the same pannable-gaps reason as files.
                    if (book.pickerTabsVisible() && book.pickerKind == QuestBookScreen.PickerKind.SPRITE) {
                        if (book.itemPicker.spriteClearRect != null && book.itemPicker.spriteClearRect.contains(mouseX, mouseY)) {
                            book.itemPicker.commitPicker(null, 1);
                            return true;
                        }
                        return false;
                    }
                    // The file tab's bar and rows are the texture picker's own state, not the item
                    // list's: a press must walk what was drawn, like every list here. A press that
                    // hits neither is not the picker's, so it reaches the tail and the canvas stays
                    // pannable from over the list's gaps.
                    if (book.pickerTabsVisible() && book.pickerKind == QuestBookScreen.PickerKind.FILE) {
                        if (pressBar(book.textureBar, mouseX, mouseY)) {
                            return true;
                        }
                        int fileRow = book.textureFrame == null ? -1
                                : ItemPickerLayout.rowAt(book.textureRows, book.textureFrame,
                                        book.textureBody.scrollY(), mouseY);
                        if (fileRow >= 0) {
                            book.itemPicker.pressFileRow(fileRow);
                            return true;
                        }
                        return false;
                    }
                    if (pressBar(book.pickerBar, mouseX, mouseY)) {
                        return true;
                    }
                    int row = book.pickerFrame == null ? -1
                            : ItemPickerLayout.rowAt(book.pickerRows, book.pickerFrame, book.pickerBody.scrollY(), mouseY);
                    if (row >= 0) {
                        book.itemPicker.pressPickerRow(row);
                        return true;
                    }
                }
                // The settings page owns the body while it is open: a press on one of its rows is the
                // page's. Its bar is the page's own control, so it is asked before the page is. **And a
                // press that hits no row is not the page's** -- which is the report this arm was rewritten
                // for: `pressSettingsPage` answered nothing and the arm returned `true` anyway, so the page
                // swallowed every press on the panel and the canvas could not be panned from over it.
                else if (book.settingsOpen) {
                    if (pressBar(book.settingsView.bar(), mouseX, mouseY)) {
                        return true;
                    }
                    if (book.bookRewards.pressSettingsPage(mouseX, mouseY)) {
                        return true;
                    }
                }
                else {
                    // The panel's own body scroll: its bar was drawn here with no press branch at all, so
                    // the grip was a picture and its `draggingThumb` branch downstream was dead code.
                    if (pressBar(book.overlayView.bar(), mouseX, mouseY)) {
                        return true;
                    }
                    // The panel's own marked pieces, from the last frame's drawing -- one derivation for
                    // the mark and the press.
                    for (QuestBookScreen.EditTarget target : book.editTargets) {
                        if (target.box().contains(mouseX, mouseY)) {
                            // Shift on a prerequisite's name is the locate gesture, the same as the
                            // reader's rows: "do not open it, show me where it is". Scoped to this one
                            // target, so the steppers' shift meaning (ten at a time) is untouched.
                            if (target.action() == QuestBookScreen.EditAction.NAVIGATE_DEP && event.hasShiftDown()) {
                                book.locateOnCanvas(target.path());
                                return true;
                            }
                            book.editorControls.pressEditTarget(target, mouseX, mouseY);
                            // **The press that opens a field owns the drag that follows it.** Press at one end
                            // of the prose and drag to the other is how a description is selected -- and the
                            // description is not open before that press, so without this the opening press was
                            // the only one the field never got and the drag went to the canvas instead. A
                            // one-line value is nearly always dragged *after* being opened, which is why the
                            // same gesture already worked everywhere else.
                            fieldDrag = book.editingPath != null;
                            return true;
                        }
                    }
                }
            }
            else if (on == PanelKind.QUEST && !book.mayEditNow() && (button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_MIDDLE)) {
                // The reader's panel: a press on a task's or reward's row opens the chosen viewer on that
                // row's item (or tag) -- "how is this made", the direction the viewer pages do not cover.
                // From the last frame's own list, so the row that lights up is the row that answers.
                //
                // The body scroll before the rows: a press on the grip is the panel's. **Left only**, and
                // that is not a detail: this arm serves the middle button too -- a middle press on a
                // prerequisite row is the locate gesture -- so a bar that took any button would swallow the
                // middle click that was aimed past it.
                if (button == InputConstants.MOUSE_BUTTON_LEFT && pressBar(book.overlayView.bar(), mouseX, mouseY)) {
                    return true;
                }
                // A prerequisite row is the panel's own navigation: a plain press opens that quest, and its
                // locate icon -- or a middle-click or shift-click anywhere on the row -- takes the canvas to
                // the node instead.
                if (book.bookSettings.pressDependencyRow(mouseX, mouseY, button, event.hasShiftDown())) {
                    return true;
                }
                // The recipe-viewer rows answer the left press alone.
                if (button == InputConstants.MOUSE_BUTTON_LEFT && book.bookSettings.pressRowItem(mouseX, mouseY)) {
                    return true;
                }
                // A filter row previews what it accepts in the second column, after the recipe
                // rows: a filter row never carries a recipe target, so the two cannot claim one press.
                if (button == InputConstants.MOUSE_BUTTON_LEFT && book.bookSettings.pressFilterRow(mouseX, mouseY)) {
                    return true;
                }
                // A checkmark row hands in, after every other row press: it never carries a recipe
                // target either, and it is registered only while the hand-in is offered, so this
                // cannot steal a press something else answers.
                if (button == InputConstants.MOUSE_BUTTON_LEFT && book.bookSettings.pressCheckmarkRow(mouseX, mouseY)) {
                    return true;
                }
                // A claimable reward row collects, after the checkmark's: it carries no recipe
                // target while claimable, and it is registered only then, so this cannot steal
                // a press something else answers either.
                if (button == InputConstants.MOUSE_BUTTON_LEFT && book.bookSettings.pressRewardClaimRow(mouseX, mouseY)) {
                    return true;
                }
            }
            else if (on == PanelKind.SETTINGS && button == InputConstants.MOUSE_BUTTON_LEFT) {
                // The player's own panel: its one control is a widget and the pass has already offered it
                // the press, so what is left is the bar.
                if (pressBar(book.appearanceView.bar(), mouseX, mouseY)) {
                    return true;
                }
            }
            else if (on == PanelKind.PINNED && button == InputConstants.MOUSE_BUTTON_LEFT) {
                // The player's watchlist: the switch is a widget the pass has already offered, so what is
                // left is the bar and the rows the panel draws itself -- a row opens its quest, its Unpin
                // end lets go of it. Left only, like the element panel's: the canvas keeps the middle press.
                if (pressBar(book.pinnedView.bar(), mouseX, mouseY)) {
                    return true;
                }
                if (book.bookPins.onPinnedTarget(mouseX, mouseY)) {
                    book.bookPins.pressPinnedPanel(mouseX, mouseY);
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
                if (pressBar(book.bookCanvas.elementView.bar(), mouseX, mouseY)) {
                    return true;
                }
                if (book.bookPins.onElementTarget(mouseX, mouseY)) {
                    book.bookPins.pressElementPanel(mouseX, mouseY);
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
                if (book.bookInput.startPanelPan(mouseX, mouseY, button)) {
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
        if (button == InputConstants.MOUSE_BUTTON_RIGHT && book.mayEditNow() && book.bookMenu.openSidebarMenuAt(mouseX, mouseY)) {
            return true;
        }

        // The sidebar's own press, read before the widget pass rather than after it, because the rows
        // are widgets and a widget consumes the press it is given: a row that is about to be dragged
        // must be remembered here, or the press is a click and there is nothing left to drag. It is not
        // consumed -- a press that never travels is still the row's click, and `super` below is what
        // makes it one.
        book.bookSidebar.rememberSidebarPress(mouseX, mouseY, button);

        // The sidebar's inline edit: a press on its fields is the edit's, and a press anywhere
        // else abandons it with nothing written. Cancelled before the widget pass, so the pass
        // offers the press to the list the cancel rebuilt rather than the one it replaced. The
        // rename's status line is the exception: it toggles the id disclosure instead.
        if (book.inlineEdit != null && !book.bookSidebar.pressOnInlineField(mouseX, mouseY)) {
            if (book.inlineEdit.mode() == QuestBookScreen.NamingMode.RENAME && !book.inlineEdit.showId()
                    && book.bookSidebar.inlineStatusHit(mouseX, mouseY)) {
                book.bookSidebar.toggleInlineId();
                return true;
            }
            book.bookSidebar.cancelInline();
        }

        // Widgets first. A control that was clicked must keep the event.
        //
        // An armed "copy to all chapters" is undone by a press on anything else, the same rule the
        // party's Disband follows -- asked here, before the widget pass, because that pass may be the
        // confirm: a press on the armed row itself must not disarm it on the way in.
        boolean onCanvasCopy = book.canvasCopyButton != null
                && book.canvasCopyButton.isMouseOver(mouseX, mouseY);
        if (book.superMouseClicked(event, doubleClick)) {
            if (!onCanvasCopy) {
                book.questEditor.disarmCanvasCopy();
            }
            return true;
        }
        if (!onCanvasCopy) {
            book.questEditor.disarmCanvasCopy();
        }

        // The colour picker next: its own fields are widgets and were just offered the press, so what is
        // left is its tracks and swatches -- and a press outside, which closes it and is consumed, so the
        // click that dismisses the picker never also acts on what was behind it.
        if (book.colourPopover.isOpen()) {
            if (book.colourPopover.mouseClicked(BookEditorControls.shiftedClick(book.pickerPointerX(mouseX), mouseY, event))) {
                return true;
            }
            book.closeColourPopover();
            return true;
        }

        // The tools panel first, and it swallows everything inside its own rectangle: a press on the
        // panel is never a press on the canvas, or choosing a colour would pan the graph underneath it.
        if (book.questEditor.inTools(mouseX, mouseY)) {
            // The colour chips first: they are drawn by the panel and hit-tested here, from the same
            // rectangle the chip is painted with (`ToolsLayout.chip`), so the chip that is seen is the
            // chip that answers. A field's box is a widget and the widget pass already offered it the
            // press; its *label* is not, which is why the label scrub is armed here too.
            if (book.toolsAppearanceVisible()) {
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
            if (book.toolsAppearanceVisible()) {
                Layout active = book.activeLayout();
                if (ToolsLayout.textureBrowseAt(book.toolsView.viewport(),
                        active == null ? null : active.slot(ToolsLayout.CANVAS_TEXTURE), mouseX, mouseY)) {
                    book.texturePicker.openTexturePicker();
                    return true;
                }
            }
            // An element row in the Chapter tab is a *selection* rather than a drag: a decoration is not a
            // list to reorder, and the fields under the list follow what is chosen. Read from the row's own
            // rectangle because the row holds no widget -- the same arrangement the quest rows below have,
            // with a different consequence.
            if (book.toolsTab == ToolsLayout.Tab.CHAPTER && book.mayEditNow() && book.chapterLayout != null) {
                String element = book.questEditor.chapterElementRowAt(mouseX, mouseY);
                if (element != null) {
                    // Pressing the chosen one again lets it go, which is how every other selection in this
                    // screen behaves and the only way to put the fields away without leaving the tab.
                    QuestBookScreen.selectedElement = element.equals(QuestBookScreen.selectedElement) ? null : element;
                    book.rebuildWidgets();
                    return true;
                }
            }
            // A link row in the Chapter tab is a *selection* rather than a drag, for the reason an
            // element row is: a marker is not a list to reorder, and the fields under the list follow
            // what is chosen. Read from the row's own rectangle because the row holds no widget.
            if (book.toolsTab == ToolsLayout.Tab.CHAPTER && book.mayEditNow() && book.chapterLayout != null) {
                String link = book.questEditor.chapterLinkRowAt(mouseX, mouseY);
                if (link != null) {
                    // Pressing the chosen one again lets it go, which is how every other selection in this
                    // screen behaves and the only way to put the fields away without leaving the tab.
                    BookCanvas.selectedLink = link.equals(BookCanvas.selectedLink) ? null : link;
                    book.bookCanvas.linkConfirmingDelete = null;
                    book.rebuildWidgets();
                    return true;
                }
            }
            // A fault row in the Chapter tab is read and copied: the row is too narrow to hold
            // the whole `file:line:column: severity: message`, so the press puts the full line in
            // the chat (where there is room for it and a history of it) and the file on the
            // clipboard (which an external editor can open). Before the quest drag below: a
            // fault is not draggable, and a press on one must never start a reorder.
            if (book.toolsTab == ToolsLayout.Tab.CHAPTER && book.mayEditNow() && book.chapterLayout != null) {
                int problem = book.questEditor.chapterProblemRowAt(mouseX, mouseY);
                if (problem >= 0) {
                    book.questEditor.pressProblemRow(problem);
                    return true;
                }
            }
            // A quest row in the Chapter tab is a draggable thing: the press claims it, and the drag
            // that may follow reorders the chapter's own list. `pressX`/`pressY` are recorded here
            // because the threshold compares against them and this press never reaches the canvas
            // branch that usually sets them.
            if (book.toolsTab == ToolsLayout.Tab.CHAPTER && book.mayEditNow() && book.chapterLayout != null) {
                int row = book.rowDrag.chapterQuestRowAt(mouseX, mouseY);
                if (row >= 0) {
                    book.dragRowMember = "chapter";
                    book.dragRowQuest = null;
                    book.dragRowFrom = row;
                    book.dragRowLive = false;
                    book.dragRowPointerY = mouseY;
                    book.pressX = mouseX;
                    book.pressY = mouseY;
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
                pressBar(book.toolsView.bar(), mouseX, mouseY);
            }
            return true;
        }

        // A press landing mid-wipe is absorbed: the widgets are already at their target while the
        // surface travels, so anything it caught would act a fraction of a second before the list
        // has visibly arrived. The wipe is 160 milliseconds; the press waits for it. Either end
        // of the travel absorbs — the rectangle the wipe left from as well as the one it lands on.
        long sidebarSlideNow = net.minecraft.util.Util.getMillis();
        if (book.sidebarSlideStart >= 0 && book.sidebarSlideFromRect != null
                && book.sidebarSliding(sidebarSlideNow)
                && (book.sidebarSlideFromRect.contains(mouseX, mouseY)
                        || book.geometry().sidebar().contains(mouseX, mouseY))) {
            return true;
        }

        // A press on the slid-away sliver brings the list back: one press, no aiming at 20 pixels
        // of tab. Past the widget pass on purpose, so a row still wins its own press where rows
        // exist; what reaches here touched no row. Left and right both expand — a right press that
        // opened a menu for a sliver with no rows would be a menu about nothing.
        if ((button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT) && book.geometry().sidebarState().collapsed() && !book.sidebarPeeking
                && book.geometry().sidebar().contains(mouseX, mouseY)) {
            book.setSidebarCollapsed(false);
            return true;
        }

        // The box's pin dot, drawn chrome rather than a widget: filled while pinned, a ring while
        // not, and the press flips it. Past the widget pass because there is no widget to meet —
        // the rows live below the header band, so nothing up here answers first. Left button
        // only, like every grip: pinning is not a thing to hang off a right press. Peeked counts:
        // pinning the preview keeps it, now held open.
        if (button == InputConstants.MOUSE_BUTTON_LEFT && book.geometry().sidebarState().floating()
                && book.geometry().sidebarState().expanded()
                && book.geometry().sidebarBoxPin().contains(mouseX, mouseY)) {
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
        if (button == InputConstants.MOUSE_BUTTON_LEFT && pressBar(book.sidebarView.bar(), mouseX, mouseY)) {
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
        if ((button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_MIDDLE || button == InputConstants.MOUSE_BUTTON_RIGHT) && book.inCanvas(mouseX, mouseY)
                && !book.geometry().sidebar().contains(mouseX, mouseY)
                && (book.sidebarPeeking
                        || (DevMode.isSidebarFloating() && !DevMode.isSidebarCollapsed()
                                && !DevMode.isSidebarPinned()))) {
            if (book.sidebarPeeking) {
                book.setSidebarPeeking(false);
            }
            else {
                book.setSidebarCollapsed(true);
            }
            return true;
        }

        // A press on the floating list's empty padding is the list's, not the graph's: the rows,
        // the bar and the strip above already had their chance, so what reaches here touched none
        // of them — and the canvas underneath must not answer through the panel floating over it.
        // Without this a click between two rows would pan the graph under the list, and its release
        // would read as blank space and close the quest panel the reader was working in. A docked
        // list needs none of this: beside the canvas, its padding is not over anything.
        if (book.geometry().sidebarState().floating() && book.geometry().sidebarState().expanded()
                && book.geometry().sidebar().contains(mouseX, mouseY)) {
            return true;
        }

        // Left or middle on the canvas: begin a pan, or -- in developer mode -- pick a node up, or
        // stretch a marquee. Whether it becomes a pan or a click is decided by whether the pointer
        // moves, which is why nothing is selected yet -- and why a pan that happens to start on a node
        // does not change the selection.
        // Left, right or middle on the canvas. Left is the selection's and the drag's; middle pans;
        // right is the edge's. The modifiers are read here, at the press, and remembered -- one rule
        // for every gesture, so letting go of a key a moment early cannot change what a press meant.
        if ((button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_MIDDLE || button == InputConstants.MOUSE_BUTTON_RIGHT) && book.inCanvas(mouseX, mouseY)) {
            book.dragging = button != InputConstants.MOUSE_BUTTON_RIGHT;
            book.pressMoved = false;
            book.pressX = mouseX;
            book.pressY = mouseY;
            pressedShift = event.hasShiftDown();
            pressedCtrl = event.hasControlDown();
            pressedAlt = event.hasAltDown();
            book.panContentX = book.viewport().contentX(mouseX);
            book.panContentY = book.viewport().contentY(mouseY);
            // A pan is the hand taking the camera: a glide still in flight must not fight it.
            book.glideQuest = null;

            String chapter = QuestBookScreen.effectiveChapter();
            ClientQuestCache.Entry under = chapter == null ? null
                    : book.bookCanvas.nodeAt(mouseX, mouseY, book.questsIn(chapter));
            book.pressedNode = under == null ? null : under.id();
            // And the link under the pointer, when no node is: a node is drawn over a marker, so a press
            // that lands on both belongs to the node. A link claims the press the way a node does, and
            // what the press *means* is decided on release, the same gesture rule both have: hold to
            // pan, click to open the target.
            book.bookCanvas.pressedQuestLink = under == null ? book.bookCanvas.linkAtId(mouseX, mouseY) : null;
            // And the element under the pointer, when neither is: a marker is drawn over a decoration,
            // so a press that lands on both belongs to the marker, for the same reason as above.
            pressedElement = under == null && book.bookCanvas.pressedQuestLink == null ? book.bookCanvas.elementAt(mouseX, mouseY) : null;

            // A left press on a curve's handle is the handle's: claimed before the node and the pan, so
            // bending a line cannot also pick a node up or start a marquee.
            if (button == InputConstants.MOUSE_BUTTON_LEFT && book.mayEditNow()) {
                String[] bend = book.bookRewards.handleAt(mouseX, mouseY);
                if (bend != null) {
                    book.bendDragFrom = bend[0];
                    book.bendDragTo = bend[1];
                    book.bendDragKind = bend[2];
                    bendDragLive = false;
                    book.bendPreview = null;
                    book.anchorPreview = null;
                    book.handlePreview = null;
                    ClientQuestCache.Entry grabFrom = book.entryFor(bend[0]);
                    ClientQuestCache.Entry grabTo = book.entryFor(bend[1]);
                    if (grabFrom != null && grabTo != null) {
                        LineArt.Point fromCentre = new LineArt.Point(book.bookRewards.nodeCentreX(grabFrom),
                                book.bookRewards.nodeCentreY(grabFrom));
                        LineArt.Point toCentre = new LineArt.Point(book.bookRewards.nodeCentreX(grabTo),
                                book.bookRewards.nodeCentreY(grabTo));
                        DependencyStyle style = book.bookCanvas.styleFor(grabTo, bend[0]);
                        if (BookRewards.HANDLE_BEND.equals(bend[2])) {
                            bendDragGrab = LineArt.bendAt(fromCentre, toCentre, mouseX, mouseY)
                                    - style.bendOr(BookCanvas.DEFAULT_BEND);
                        }
                        else if (BookRewards.HANDLE_FROM_HANDLE.equals(bend[2])
                                || BookRewards.HANDLE_TO_HANDLE.equals(bend[2])) {
                            // Free 2D: the delta between the pointer and the control point, in the chord's
                            // own frame, so the drag lands where the hand is.
                            double[] chord = book.bookRewards.chordOf(grabFrom, grabTo);
                            LineArt.Point control = book.bookRewards.splitPointOf(bend, style,
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
                            LineArt.Point[] ends = book.bookCanvas.lineEnds(grabFrom, grabTo, style);
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
                book.pressedNode = null;
                book.bookCanvas.pressedQuestLink = null;
            }

            // Right claims the press for the edge gesture, on a node with an editor open. Anywhere else
            // it is remembered for the release, which decides between the canvas's menus: a right-press
            // that travels is an edge (or nothing, on empty canvas), and one that does not is a menu.
            if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                canvasRightPressed = true;
                if (under != null && book.mayEditNow()) {
                    book.edgeDragFrom = under.id();
                    book.edgeDragLive = false;
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
            if (button == InputConstants.MOUSE_BUTTON_LEFT && under != null && book.mayEditNow()) {
                if (book.pickingDependency) {
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
                book.dragNodeX = book.bookCanvas.nodeX(under);
                book.dragNodeY = book.bookCanvas.nodeY(under);
                book.draggedNode = under.id();
                nodeDragLive = false;
                // Where inside the node the pointer took hold, so the node does not jump to put its
                // corner under the pointer.
                book.dragGrabX = book.viewport().contentX(mouseX) - book.dragNodeX;
                book.dragGrabY = book.viewport().contentY(mouseY) - book.dragNodeY;
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
            if (button == InputConstants.MOUSE_BUTTON_LEFT && book.mayEditNow() && QuestBookScreen.selectedElement != null) {
                BookCanvas.ElementSlot slot = book.bookCanvas.elementSlot(QuestBookScreen.selectedElement);
                CanvasElementArt.Handle handle = slot == null ? null
                        : CanvasElementArt.handleAt(slot.element(), slot.box(), mouseX, mouseY);
                if (handle != null && slot != null) {
                    book.bookCanvas.elementDragging = QuestBookScreen.selectedElement;
                    book.bookCanvas.elementDragLive = false;
                    book.bookCanvas.elementDragBase = book.elementNow(QuestBookScreen.selectedElement);
                    book.bookCanvas.elementPreview = null;
                    book.bookCanvas.elementHandle = handle;
                    // **And the press is still the element's.** A grip sits on the element's own edge, which a
                    // hit test counts as outside it -- so `pressedElement` is null for a press here, and a click
                    // on a grip that never moved would fall through to the empty-canvas branch and *deselect*
                    // the very thing it landed on. Saying which element it is keeps the release's click honest.
                    //
                    // And it is not a link's: a grip is the selected element's own control, so a marker under
                    // it does not get the press -- the release opens the element's panel, as it always has.
                    pressedElement = QuestBookScreen.selectedElement;
                    book.bookCanvas.pressedQuestLink = null;
                    return true;
                }
            }

            // A left press on an element takes hold of it, exactly as one on a node does -- claimed on the
            // press so a drag can start from it, with click-versus-drag left to the release. Edit mode only:
            // in play mode a press on a pressable element is its click action and nothing else, and a
            // decoration that could be dragged by a player would be a decoration that moved under them.
            if (button == InputConstants.MOUSE_BUTTON_LEFT && book.mayEditNow() && pressedElement != null) {
                CanvasElement held = book.elementNow(pressedElement);
                if (held != null) {
                    book.bookCanvas.elementDragging = pressedElement;
                    book.bookCanvas.elementDragLive = false;
                    book.bookCanvas.elementDragBase = held;
                    book.bookCanvas.elementPreview = null;
                    // No grip: this is the body, so the gesture is a move.
                    book.bookCanvas.elementHandle = null;
                    // The same grab offset the node drag uses, and the same two fields: a press is one gesture
                    // whichever object it landed on, and only one of the three is ever claimed.
                    book.dragGrabX = book.viewport().contentX(mouseX) - CanvasElementArt.originX(held);
                    book.dragGrabY = book.viewport().contentY(mouseY) - CanvasElementArt.originY(held);
                    return true;
                }
            }

            // A left press on a link takes hold of it, exactly as one on a node or an element does --
            // claimed on the press so a drag can start from it, with click-versus-drag left to the release.
            // Edit mode only: in play mode a press on a marker is its shortcut and nothing else, and a
            // marker that could be dragged by a player would be a shortcut that moved under them. A link
            // has no grips, so the body is the whole of it and the gesture is always a move.
            if (button == InputConstants.MOUSE_BUTTON_LEFT && book.mayEditNow() && book.bookCanvas.pressedQuestLink != null) {
                QuestLink held = book.bookPins.linkNow(book.bookCanvas.pressedQuestLink);
                if (held != null) {
                    book.bookCanvas.linkDragging = book.bookCanvas.pressedQuestLink;
                    book.bookCanvas.linkDragLive = false;
                    book.bookCanvas.linkDragBase = held;
                    book.bookCanvas.linkPreview = null;
                    // The same grab offset the node drag uses: a press is one gesture whichever object it
                    // landed on, and only one of the three is ever claimed.
                    book.dragGrabX = book.viewport().contentX(mouseX) - held.x();
                    book.dragGrabY = book.viewport().contentY(mouseY) - held.y();
                    return true;
                }
            }

            // A shift-drag on the empty canvas stretches the additive marquee. Shift because the plain
            // left-drag on the canvas is the pan -- in both modes, which is the rule this round bought:
            // navigation does not change with a mode. Ctrl+A and shift-click already collect, so the
            // marquee is the third way to say "add these", and it says it with the same modifier.
            if (button == InputConstants.MOUSE_BUTTON_LEFT && under == null && pressedElement == null && book.mayEditNow() && pressedShift) {
                book.marqueeActive = true;
                book.marqueeX0 = (int) mouseX;
                book.marqueeY0 = (int) mouseY;
                book.marqueeX1 = book.marqueeX0;
                book.marqueeY1 = book.marqueeY0;
            }
            return true;
        }

        return false;
    }

    boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
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
        if (book.panelDragRail != null) {
            // **One override, for the kind the hand is on.** There were two fields -- column 1's and column
            // 2's -- and which one was written was decided by asking whether the grabbed kind was the first
            // column, so grabbing the dock's edge filed the width as the dock's and grabbing a picker's filed
            // it under a *column*. A width is a property of a kind: `railWidth` reads this only for
            // `panelDragKind`, and the release writes it back under that same kind.
            int wanted = Math.max(PanelStack.widthWhileDragging(panelDragStartWidth, panelDragStartX,
                    mouseX, book.panelDragKind), book.panelContentFloor(book.panelDragKind));
            if (wanted != book.panelLiveWidth) {
                book.panelLiveWidth = wanted;
                book.rebuildWidgets();
            }
            return true;
        }

        // A resize of the chapter list in flight, and the same contract as the rails' above: the
        // edge follows the pointer, and the chrome, the rows and the controls all follow it. The
        // width goes through the list's own clamp, so the edge stops where a list may not go; the
        // draw-time cap in the geometry keeps the window's remainder for the graph, and a drag back
        // to the default removes the stored entry on release rather than restating it.
        if (book.sidebarDragRail != null) {
            int wanted = BookGeometry.sidebarWidthWhileDragging(sidebarDragStartWidth,
                    sidebarDragStartX, mouseX);
            if (book.sidebarLiveWidth == null || wanted != book.sidebarLiveWidth) {
                book.sidebarLiveWidth = wanted;
                book.rebuildWidgets();
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
        if (book.colourPopover.mouseDragged(BookEditorControls.shiftedClick(book.pickerPointerX(mouseX), mouseY, event))) {
            return true;
        }
        // The curve handle's follow. The bow is recomputed from the pointer rather than accumulated, so
        // letting the hand drift back over the chord straightens the line again.
        if (book.bendDragFrom != null) {
            if (!bendDragLive) {
                if (Math.abs(mouseX - book.pressX) <= QuestBookScreen.DRAG_THRESHOLD
                        && Math.abs(mouseY - book.pressY) <= QuestBookScreen.DRAG_THRESHOLD) {
                    return true;
                }
                bendDragLive = true;
                book.pressMoved = true;
            }
            ClientQuestCache.Entry bendFrom = book.entryFor(book.bendDragFrom);
            ClientQuestCache.Entry bendTo = book.entryFor(book.bendDragTo);
            if (bendFrom != null && bendTo != null) {
                LineArt.Point fromCentre = new LineArt.Point(book.bookRewards.nodeCentreX(bendFrom),
                        book.bookRewards.nodeCentreY(bendFrom));
                LineArt.Point toCentre = new LineArt.Point(book.bookRewards.nodeCentreX(bendTo),
                        book.bookRewards.nodeCentreY(bendTo));
                if (BookRewards.HANDLE_BEND.equals(book.bendDragKind)) {
                    book.bendPreview = LineArt.limitBend(LineArt.bendAt(fromCentre, toCentre, mouseX, mouseY)
                            - bendDragGrab);
                }
                else if (BookRewards.HANDLE_FROM_HANDLE.equals(book.bendDragKind)
                        || BookRewards.HANDLE_TO_HANDLE.equals(book.bendDragKind)) {
                    // Free 2D, grab-relative, clamped so a control point cannot be flung off the graph.
                    double[] chord = book.bookRewards.chordOf(bendFrom, bendTo);
                    LineArt.Point origin = new LineArt.Point((int) chord[0], (int) chord[1]);
                    LineArt.Point other = new LineArt.Point((int) (chord[0] + chord[2] * chord[4]),
                            (int) (chord[1] + chord[3] * chord[4]));
                    java.util.List<Double> at = LineArt.chordFraction(origin, other, mouseX, mouseY);
                    book.handlePreview = java.util.List.of(
                            Math.max(-0.5, Math.min(1.5, at.get(0) - handleGrabAlong)),
                            Math.max(-0.9, Math.min(0.9, at.get(1) - handleGrabAcross)));
                }
                else {
                    // Free angle, around this end's own node, applied as the delta from the grab.
                    LineArt.Point centre = BookRewards.HANDLE_FROM.equals(book.bendDragKind) ? fromCentre : toCentre;
                    book.anchorPreview = LineArt.anchorAngle(centre, mouseX, mouseY) - anchorDragGrab;
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
                && (Math.abs(mouseX - book.pressX) > QuestBookScreen.DRAG_THRESHOLD || Math.abs(mouseY - book.pressY) > QuestBookScreen.DRAG_THRESHOLD)) {
            book.pressMoved = true;
        }

        // A slider's drag, before anything else: the press claimed the track, so the pan and the node
        // drag must not also answer it. The value follows the pointer and the preview with it; the
        // commit waits for the release, which is what keeps a drag to one operation rather than sixty.
        if (book.draggingSlider != null) {
            book.bookRewards.dragSliderTo(mouseX);
            return true;
        }

        // A drag that began on the open field extends its selection, and is consumed here: the press was
        // the field's (see `mouseClicked`), so the pan and the node drag must not also answer it. The
        // gesture came from play -- *"allow selecting as well, like dragging the cursor in text fields"*.
        if (fieldDrag) {
            if (book.inlineArea != null) {
                book.inlineArea.mouseDragged(BookEditorControls.shiftedClick(mouseX, mouseY, event), dragX, dragY);
            }
            else if (book.inlineField != null) {
                book.inlineField.mouseDragged(BookEditorControls.shiftedClick(mouseX, mouseY, event), dragX, dragY);
            }
            return true;
        }

        // The sidebar tree's drag, before the flat row drag because the two cannot both be live: a press
        // claims one list or the other, and the sidebar's rows are widgets while these lists are drawn.
        // The threshold separates a click from a drag exactly as it does everywhere else; the follow is
        // the pointer plus the drop it currently means, recomputed each frame so the mark the reader
        // sees is the drop they will get.
        if (book.sidebarDragKey != null) {
            if (!book.sidebarDragLive) {
                if (Math.abs(mouseX - book.sidebarDragPressX) <= QuestBookScreen.DRAG_THRESHOLD
                        && Math.abs(mouseY - book.sidebarDragPressY) <= QuestBookScreen.DRAG_THRESHOLD) {
                    return true;
                }
                book.sidebarDragLive = true;
                book.pressMoved = true;
            }
            book.sidebarDragPointerY = mouseY;
            book.sidebarDrop = SidebarDrag.target(book.bookSidebar.sidebarRows(), mouseY, book.sidebarDragKey, book.sidebarDragGroup);
            return true;
        }

        // The row drag, on the node drag's own phases as far as they apply: the press claimed the row,
        // the threshold separates a click from a drag, and the follow is just the pointer's y -- there
        // is no ghost row to carry, only the line the drop would land on. Consumed so the pan and the
        // node drag do not also answer a gesture that started on a row.
        if (book.dragRowFrom >= 0) {
            if (!book.dragRowLive) {
                if (Math.abs(mouseX - book.pressX) <= QuestBookScreen.DRAG_THRESHOLD
                        && Math.abs(mouseY - book.pressY) <= QuestBookScreen.DRAG_THRESHOLD) {
                    return true;
                }
                book.dragRowLive = true;
                book.pressMoved = true;
            }
            book.dragRowPointerY = mouseY;
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
        if (book.heldBar != null) {
            book.heldBar.dragTo(mouseY);
            return true;
        }

        if (book.edgeDragFrom != null) {
            // The edge gesture's own threshold, the same one the other drags use: a shift-click that
            // jitters is still a selection toggle, and only a travelled press is an edge.
            if (!book.edgeDragLive) {
                if (Math.abs(mouseX - book.pressX) <= QuestBookScreen.DRAG_THRESHOLD
                        && Math.abs(mouseY - book.pressY) <= QuestBookScreen.DRAG_THRESHOLD) {
                    return true;
                }
                book.edgeDragLive = true;
                book.pressMoved = true;
            }
            book.edgeDragX = mouseX;
            book.edgeDragY = mouseY;
            return true;
        }

        if (book.bookCanvas.elementDragging != null) {
            // Press, threshold, follow -- the node drag's three steps, because a decoration that twitched
            // under every click would read as jitter in exactly the same way a node would.
            if (!book.bookCanvas.elementDragLive) {
                if (Math.abs(mouseX - book.pressX) <= QuestBookScreen.DRAG_THRESHOLD
                        && Math.abs(mouseY - book.pressY) <= QuestBookScreen.DRAG_THRESHOLD) {
                    return true;
                }
                // A pinned picture refuses here rather than at the press, so a click still selects it
                // and only a gesture that would have moved it is turned away -- with the way out named.
                // Grips funnel through the same drag, so a resize and a rotate are refused with it.
                if (book.bookCanvas.elementDragBase instanceof CanvasElement.Image image && image.locked()) {
                    String pinned = book.bookCanvas.elementDragging;
                    book.bookCanvas.elementDragging = null;
                    book.bookCanvas.elementDragLive = false;
                    book.bookCanvas.elementDragBase = null;
                    book.bookCanvas.elementPreview = null;
                    book.status("\"" + pinned + "\" is pinned: switch off Pinned in its Position section"
                            + " to move it", false);
                    return true;
                }
                book.bookCanvas.elementDragLive = true;
                book.pressMoved = true;
            }
            // What the gesture has produced so far, built from the element the press took hold of rather than
            // from the cached one -- the cache already carries the last frame's preview, so building on it
            // would compound the change every frame. Committed on release rather than here: a gesture is one
            // edit, and a file write per mouse move would be a file write per mouse move. What is committed is
            // what was last drawn, because release takes this one field.
            book.bookCanvas.elementPreview = book.bookPins.draggedElement(mouseX, mouseY);
            return true;
        }

        if (book.bookCanvas.linkDragging != null) {
            // Press, threshold, follow -- the node drag's three steps, because a marker that twitched
            // under every click would read as jitter in exactly the same way a node would.
            if (!book.bookCanvas.linkDragLive) {
                if (Math.abs(mouseX - book.pressX) <= QuestBookScreen.DRAG_THRESHOLD
                        && Math.abs(mouseY - book.pressY) <= QuestBookScreen.DRAG_THRESHOLD) {
                    return true;
                }
                book.bookCanvas.linkDragLive = true;
                book.pressMoved = true;
            }
            // What the gesture has produced so far, built from the link the press took hold of rather than
            // from the cached one -- the cache already carries the last frame's preview, so building on it
            // would compound the change every frame. A move follows the pointer with the grab offset kept,
            // snapped when the switch says so and Alt is not held -- the free placement, read per event so
            // it can be pressed mid-drag. Committed on release rather than here: a gesture is one edit.
            QuestLink base = book.bookCanvas.linkDragBase;
            int x = base == null ? 0 : (int) Math.round(BookGeometry.snap(
                    book.viewport().contentX(mouseX) - book.dragGrabX, BookGeometry.SNAP_GRID, snappingNow()));
            int y = base == null ? 0 : (int) Math.round(BookGeometry.snap(
                    book.viewport().contentY(mouseY) - book.dragGrabY, BookGeometry.SNAP_GRID, snappingNow()));
            book.bookCanvas.linkPreview = base == null ? null : base.translated(x - base.x(), y - base.y());
            return true;
        }

        if (book.draggedNode != null) {
            // Press, threshold, follow. Until the pointer has travelled further than a click's jitter,
            // the press is still a possible click — and a node that twitched under every click would
            // read as a jitter nobody asked for. The same threshold the pan uses, so a click means the
            // same amount of movement whichever surface it landed on.
            if (!nodeDragLive) {
                if (Math.abs(mouseX - book.pressX) <= QuestBookScreen.DRAG_THRESHOLD
                        && Math.abs(mouseY - book.pressY) <= QuestBookScreen.DRAG_THRESHOLD) {
                    return true;
                }
                nodeDragLive = true;
                book.pressMoved = true;
                // A selected node carries its selection with it. Where they all started is captured
                // now, once, because a delta can only be taken against where things were.
                dragStarts.clear();
                dragStartX = book.dragNodeX;
                dragStartY = book.dragNodeY;
                if (BookCanvas.isSelected(book.draggedNode)) {
                    for (String id : QuestBookScreen.selection()) {
                        ClientQuestCache.Entry entry = book.entryFor(id);
                        if (entry != null && !id.equals(book.draggedNode)) {
                            dragStarts.put(id, new float[] {book.bookCanvas.nodeX(entry), book.bookCanvas.nodeY(entry)});
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
            book.dragNodeX = (float) BookGeometry.snap(book.viewport().contentX(mouseX) - book.dragGrabX,
                    BookGeometry.SNAP_GRID, snappingNow());
            book.dragNodeY = (float) BookGeometry.snap(book.viewport().contentY(mouseY) - book.dragGrabY,
                    BookGeometry.SNAP_GRID, snappingNow());
            // The rest of the selection moves by the same content delta, through the same pending
            // positions the canvas already reads -- so the lines and the labels move with them, and
            // release commits exactly what was drawn.
            float dx = book.dragNodeX - dragStartX;
            float dy = book.dragNodeY - dragStartY;
            if (!dragStarts.isEmpty()) {
                long revision = ClientQuestCache.treeRevision();
                for (Map.Entry<String, float[]> started : dragStarts.entrySet()) {
                    book.editors.moved(started.getKey(), started.getValue()[0] + dx,
                            started.getValue()[1] + dy, revision);
                }
            }
            return true;
        }

        if (book.marqueeActive) {
            book.pressMoved = true;
            book.marqueeX1 = (int) mouseX;
            book.marqueeY1 = (int) mouseY;
            return true;
        }

        if (book.dragging) {
            if (Math.abs(mouseX - book.pressX) > QuestBookScreen.DRAG_THRESHOLD || Math.abs(mouseY - book.pressY) > QuestBookScreen.DRAG_THRESHOLD) {
                book.pressMoved = true;
            }
            // The grabbed content point goes under the pointer, from the scale as it is *now*. Written
            // this way rather than as `panAtPress + (mouse - press)` for two reasons, and both of them
            // were faults: an offset captured at press is thrown away by anything else that moves the
            // view -- a zoom while the drag is held slid the canvas back to where the drag started, once
            // per wheel notch -- and the delta form accumulates a pixel per event unless it is anchored.
            // `Viewport.dragTo` is the one expression, and its test sweeps it against both.
            book.viewport().dragTo(mouseX, mouseY, book.panContentX, book.panContentY);
            return true;
        }
        return book.superMouseDragged(event, dragX, dragY);
    }

    boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
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
        if (book.panelDragRail != null) {
            int width = book.panelLiveWidth > 0 ? book.panelLiveWidth : book.panelDragRail.width();
            book.panelDragRail = null;
            book.panelLiveWidth = 0;
            DevMode.setPanelWidth(book.panelDragKind, width);
            book.panelDragKind = PanelKind.NONE;
            book.rebuildWidgets();
            return true;
        }
        // The list's edge let go: what the hand reached is what is remembered, through the same
        // clamp the drag used — and a width back at the default costs no entry, because the
        // file is a diff and the absence of the key already says "today's column".
        if (book.sidebarDragRail != null) {
            int width = book.sidebarLiveWidth != null ? book.sidebarLiveWidth : book.sidebarDragRail.width();
            book.sidebarDragRail = null;
            book.sidebarLiveWidth = null;
            DevMode.setSidebarWidth(width);
            book.rebuildWidgets();
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
        if (book.colourPopover.mouseReleased(BookEditorControls.shiftedClick(book.pickerPointerX(mouseX), mouseY, event))) {
            return true;
        }
        // A slider's release: the one commit for the whole drag. Read and cleared before anything else
        // looks at the gesture, because the drag is over the moment the button is up.
        if (book.draggingSlider != null) {
            book.bookRewards.releaseSlider();
            return true;
        }

        // Let go: the mark the drag made stays (the model keeps it until the next press or keystroke), and
        // the *gesture* ends -- a later drag belongs to whatever it starts on.
        fieldDrag = false;

        // The row drag's release, read and cleared before anything else looks at the gesture: the drop
        // is committed exactly where the line was last drawn, and a press that never travelled is a
        // click -- which, on a grip, is nothing at all.
        if (book.dragRowFrom >= 0) {
            int from = book.dragRowFrom;
            String member = book.dragRowMember;
            String quest = book.dragRowQuest;
            boolean live = book.dragRowLive;
            double pointerY = book.dragRowPointerY;
            book.dragRowFrom = -1;
            book.dragRowMember = null;
            book.dragRowQuest = null;
            book.dragRowLive = false;
            book.dragging = false;
            book.pressedNode = null;
            book.bookCanvas.pressedQuestLink = null;
            if (live) {
                book.rowDrag.commitRowDrop(member, quest, from, pointerY);
            }
            return true;
        }

        // The sidebar tree's release. Unlike the flat rows above, a press that never travelled has to
        // fall through to the widget pass: the row is a button, and a button's click is its release.
        // Only a live drag is swallowed here -- and swallowing it is what stops the same release firing
        // the row's press after it has just been dropped somewhere else.
        if (book.sidebarDragKey != null) {
            String key = book.sidebarDragKey;
            boolean group = book.sidebarDragGroup;
            boolean live = book.sidebarDragLive;
            SidebarDrag.Drop drop = book.sidebarDrop;
            book.sidebarDragKey = null;
            book.sidebarDragGroup = false;
            book.sidebarDragLive = false;
            book.sidebarDrop = null;
            if (live) {
                book.bookSidebar.commitSidebarDrop(key, group, drop);
                return true;
            }
        }

        // A link the press landed on, let go on the same rectangle: that is the click it was waiting for.
        QuestBookScreen.LinkRect link = pressedLink;
        pressedLink = null;
        if (button == InputConstants.MOUSE_BUTTON_LEFT && link != null) {
            if (link.box().contains(mouseX, mouseY)) {
                if (!link.url().isEmpty()) {
                    book.questEditor.openLink(link.url());
                }
                else if (!link.quest().isEmpty()) {
                    book.bookCanvas.openQuest(link.quest());
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
        if (book.heldBar != null) {
            book.heldBar.release();
            book.heldBar = null;
            return true;
        }

        // The curve handle's release: one op, or nothing at all if the press never travelled.
        if (book.bendDragFrom != null) {
            String bendFrom = book.bendDragFrom;
            String bendTo = book.bendDragTo;
            boolean bendLive = bendDragLive;
            Double bend = book.bendPreview;
            Double anchor = book.anchorPreview;
            java.util.List<Double> handle = book.handlePreview;
            String kind = book.bendDragKind;
            book.bendDragFrom = null;
            book.bendDragTo = null;
            book.bendDragKind = null;
            bendDragLive = false;
            book.bendPreview = null;
            book.anchorPreview = null;
            book.handlePreview = null;
            book.dragging = false;
            if (bendLive && kind != null) {
                if (BookRewards.HANDLE_BEND.equals(kind) && bend != null) {
                    book.bookRewards.setLineBend(bendFrom, bendTo, bend);
                }
                else if (handle != null) {
                    book.bookRewards.setLineHandle(bendFrom, bendTo, kind, handle);
                }
                else if (anchor != null) {
                    book.bookRewards.setLineAnchor(bendFrom, bendTo, kind, anchor);
                }
            }
            return true;
        }

        if (book.edgeDragFrom != null) {
            String from = book.edgeDragFrom;
            boolean live = book.edgeDragLive;
            book.edgeDragFrom = null;
            book.edgeDragLive = false;
            book.dragging = false;
            book.pressedNode = null;
            book.bookCanvas.pressedQuestLink = null;

            if (live) {
                // The edge lands on whatever node is under the release, and the arrow points the way
                // the drag went: from the prerequisite to the quest that now depends on it. Landing on
                // empty canvas cancels -- a gesture that half-lands is a gesture that did not happen.
                String chapter = QuestBookScreen.effectiveChapter();
                ClientQuestCache.Entry landing = chapter == null ? null
                        : book.bookCanvas.nodeAt(mouseX, mouseY, book.questsIn(chapter));
                if (landing != null && !landing.id().equals(from)) {
                    JsonObject quest = ClientChapterReplica.quest(chapter, landing.id());
                    if (quest != null) {
                        // Built on the draft, not the replica: an edge drag that lands while another
                        // prerequisite is still pending must keep it rather than overwrite it.
                        List<String> dependencies = new ArrayList<>(QuestBookScreen.fieldDraft.strings(chapter,
                                landing.id(), "dependsOn", QuestPanelLayout.strings(quest, "dependsOn")));
                        if (!dependencies.contains(from)) {
                            dependencies.add(from);
                            book.bookInput.sendField(chapter, landing.id(), "dependsOn",
                                    QuestBookScreen.stringArray(dependencies));
                        }
                        else {
                            book.status(landing.id() + " already depends on " + from, true);
                        }
                    }
                }
            }
            // A right-press that never travelled is a click, and the canvas's menus are what a
            // right-click is for: on the node it was over, or on the line, or on the empty canvas.
            if (!live && !book.pressMoved) {
                book.bookMenu.openCanvasMenuAt(mouseX, mouseY);
            }
            return true;
        }

        // A right-press on empty canvas never arms an edge, so its release arrives here: a menu for the
        // canvas if the pointer stayed put, and nothing at all if it travelled — a right-drag on empty
        // canvas is not a pan, and it is not a menu either.
        if (button == InputConstants.MOUSE_BUTTON_RIGHT && canvasRightPressed) {
            canvasRightPressed = false;
            if (!book.pressMoved) {
                book.bookMenu.openCanvasMenuAt(mouseX, mouseY);
            }
            return true;
        }

        if (book.bookCanvas.elementDragging != null) {
            String id = book.bookCanvas.elementDragging;
            boolean live = book.bookCanvas.elementDragLive;
            CanvasElement base = book.bookCanvas.elementDragBase;
            CanvasElement now = book.bookCanvas.elementPreview;
            book.bookCanvas.elementDragging = null;
            book.bookCanvas.elementDragLive = false;
            book.bookCanvas.elementDragBase = null;
            book.bookCanvas.elementPreview = null;
            book.bookCanvas.elementHandle = null;

            if (live && base != null && now != null) {
                // What the canvas showed when the pointer let go is what is committed: this is the drawn
                // element rather than a re-derivation that could disagree with it -- the same rule the node
                // drag states, and the reason the two locals above exist before the fields are cleared.
                book.bookPins.commitElementEdit(id, base, now);
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

        if (book.bookCanvas.linkDragging != null) {
            String id = book.bookCanvas.linkDragging;
            boolean live = book.bookCanvas.linkDragLive;
            QuestLink base = book.bookCanvas.linkDragBase;
            QuestLink now = book.bookCanvas.linkPreview;
            book.bookCanvas.linkDragging = null;
            book.bookCanvas.linkDragLive = false;
            book.bookCanvas.linkDragBase = null;
            book.bookCanvas.linkPreview = null;

            if (live && base != null && now != null) {
                // What the canvas showed when the pointer let go is what is committed: this is the drawn,
                // snapped position, not a re-derivation that could disagree with it -- the same rule the node
                // drag states. A gesture that moved is not a click, so the release tail must not read this as
                // one: `pressMoved` is already set by a live drag, and the tail's own guard reads it.
                book.bookPins.commitLinkEdit(id, base, now);
            }
            // **And nothing else here**, for the reason the element path gives: a press that did not travel
            // is the *click*, and the click is the release tail's. Deliberately no `return`: the tail runs,
            // which is where the click was always meant to be handled.
        }

        if (book.draggedNode != null) {
            String id = book.draggedNode;
            float x = book.dragNodeX;
            float y = book.dragNodeY;
            boolean live = nodeDragLive;
            boolean shift = pressedShift;
            book.draggedNode = null;
            nodeDragLive = false;
            book.dragging = false;
            book.pressedNode = null;
            book.bookCanvas.pressedQuestLink = null;

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
                book.bookInput.commitMoveBatch(id, new ArrayList<>(dragStarts.keySet()), x, y,
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
                    book.bookRewards.straightenNode(id);
                    return true;
                }
                BookCanvas.selectForCard(id);
                book.bookRewards.openOverlay(id);
            }
            return true;
        }

        if (book.marqueeActive) {
            book.marqueeActive = false;
            boolean moved = book.pressMoved;
            book.dragging = false;
            book.pressedNode = null;
            book.bookCanvas.pressedQuestLink = null;
            if (moved) {
                book.bookInput.selectInsideMarquee();
            }
            // A shift-click on the empty canvas adds nothing and takes nothing away: the marquee's
            // modifier means "add", and an empty marquee adds an empty set. Clearing is the plain
            // click's job, below.
            return true;
        }

        if (book.dragging) {
            book.dragging = false;

            // An element's own click, and it is two gestures in one place. In edit mode it selects, so a
            // decoration can be moved or deleted like anything else on the canvas; in play mode it does what
            // the element says -- open a quest, open a page -- which is the whole point of a click action.
            //
            // Before the node's branch rather than after it, because the two cannot both be under the pointer:
            // `pressedElement` is only set when no node was. And on release rather than on press, for the same
            // reason a node's is: a press that travelled is a pan, and a pan must not select or open anything.
            if (!book.pressMoved && pressedElement != null && button == InputConstants.MOUSE_BUTTON_LEFT) {
                if (book.mayEditNow()) {
                    // **One click does both jobs**: it selects the element -- which is what puts the ring and
                    // the grips on it -- and it opens the panel that edits it, exactly as a node click selects
                    // and opens a quest. Selecting first and opening second is the two-gesture read the node
                    // click already rejected; here it would be worse, because the fields are the only way to
                    // change a decoration at all.
                    //
                    // A press that travelled never reaches this branch, so a drag still opens nothing.
                    book.bookPins.openElementPanel(pressedElement);
                    QuestBookScreen.selectedQuest = null;
                    BookCanvas.selectedLink = null;
                    QuestBookScreen.multiSelection.clear();
                }
                else {
                    BookCanvas.ElementSlot slot = book.bookCanvas.elementSlot(pressedElement);
                    if (slot != null) {
                        book.bookCanvas.pressElement(slot.element());
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
            if (!book.pressMoved && book.bookCanvas.pressedQuestLink != null && button == InputConstants.MOUSE_BUTTON_LEFT) {
                BookCanvas.LinkSlot slot = book.bookCanvas.linkSlot(book.bookCanvas.pressedQuestLink);
                book.bookCanvas.pressedQuestLink = null;
                if (slot == null) {
                    return true;
                }
                if (book.pickingDependency) {
                    book.pickingDependency = false;
                    book.questEditor.addPickedDependency(slot.target().id());
                    return true;
                }
                // A target pick in flight does not land here: only a node click names a target, because
                // only quests resolve. A click on a marker while armed falls through to the selection
                // below, which is the gesture the author can see.
                if (book.mayEditNow()) {
                    // **One click selects**: the ring goes on the marker and the tab shows its fields --
                    // exactly as a node click selects and opens a quest, minus the card, because a link
                    // has no card to open. Selecting first and navigating second would fly the author to
                    // another chapter for trying to move a marker, which is the opposite of the gesture.
                    QuestBookScreen.selectedQuest = null;
                    QuestBookScreen.multiSelection.clear();
                    BookCanvas.selectedLink = BookCanvas.selectedLink != null
                            && BookCanvas.selectedLink.equals(slot.slot().link().id()) ? null : slot.slot().link()
                                    .id();
                    book.bookCanvas.linkConfirmingDelete = null;
                    book.rebuildWidgets();
                    return true;
                }
                book.navigateToQuest(slot.target().id());
                return true;
            }

            // A press that never moved is a click. Selecting on release rather than on press is what
            // makes "hold to pan" and "click to select" one gesture.
            if (!book.pressMoved && book.pressedNode != null && button == InputConstants.MOUSE_BUTTON_LEFT) {
                // A link-target pick in flight names this quest: only a node click lands one, because
                // only quests resolve -- a click on another marker would name a shortcut, and the file
                // wants the quest it stands in for.
                if (book.bookCanvas.pendingLinkPick != null) {
                    ClientQuestCache.Entry picked = book.entryFor(book.pressedNode);
                    book.pressedNode = null;
                    if (picked != null) {
                        book.bookPins.landLinkPick(picked);
                    }
                    else {
                        book.bookCanvas.pendingLinkPick = null;
                        book.rebuildWidgets();
                    }
                    return true;
                }
                // The dependency pick: armed by the panel's Add-from-canvas action, the next click
                // names the quest to depend on instead of opening one. Disarmed by Escape, and by
                // landing -- a pick that stayed armed would turn every later click into a dependency.
                if (book.pickingDependency) {
                    book.pickingDependency = false;
                    book.questEditor.addPickedDependency(book.pressedNode);
                    book.pressedNode = null;
                    return true;
                }

                QuestBookScreen.selectedQuest = book.pressedNode.equals(QuestBookScreen.selectedQuest) ? null : book.pressedNode;
                BookCanvas.selectedLink = null;
                book.bookCanvas.linkConfirmingDelete = null;
                QuestBookScreen.multiSelection.clear();

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
                book.bookRewards.openOverlay(book.pressedNode);
                return true;
            }
            if (!book.pressMoved && button == InputConstants.MOUSE_BUTTON_LEFT && !book.panelPress) {
                // A click on the empty canvas unselects everything -- the primary too, not only the
                // multi-selection: "press blank space to let go" is one gesture and not two. And the element
                // selection with it, because "everything" is what the sentence says: a decoration left
                // selected by a click on blank canvas would be a ring nobody could explain. And the link's,
                // for the same reason: a marker is a third kind of selection, and letting go means all three.
                QuestBookScreen.selectedQuest = null;
                QuestBookScreen.selectedElement = null;
                BookCanvas.selectedLink = null;
                book.bookCanvas.linkConfirmingDelete = null;
                QuestBookScreen.multiSelection.clear();

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
                if (book.overlay != PanelKind.NONE || book.overlay2 != PanelKind.NONE) {
                    book.closeOverlay();
                }
            }
            book.panelPress = false;
            book.pressedNode = null;
            book.bookCanvas.pressedQuestLink = null;
            return true;
        }
        return book.superMouseReleased(event);
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // The wheel closes an open menu, wherever it is. The canvas zooms and the lists scroll under the
        // pointer, so a menu left open would hang over a node or a row that has moved out from under it.
        if (!book.menu.isEmpty()) {
            book.bookMenu.closeMenu();
        }
        // **Which rail the wheel belongs to**, or null when the pointer is over the graph — in which case
        // the zoom at the foot of this method is what answers. One resolver, the same one the press and the
        // grip use, so the three cannot disagree about where a rail is or about how much of it has arrived.
        PanelKind wheel = book.panelColumnAt(mouseX, mouseY);
        if (wheel != null) {
            // The handlers below measure their rows against the four accessors, so this is the surface
            // they must answer for -- the same one assignment the drawing and the build make.
            book.surfaceKind = wheel;
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
            if (book.typePageOpen()) {
                if (book.typeFrame != null) {
                    book.typeBar.wheel(scrollY);
                }
                return true;
            }
            // The file tab scrolls the texture list's own bar: the wheel belongs to the list on
            // screen, and the item list behind this tab does not move.
            if (book.pickerTabsVisible() && book.pickerKind == QuestBookScreen.PickerKind.FILE) {
                if (book.textureFrame != null) {
                    book.textureBar.wheel(scrollY);
                }
                return true;
            }
            if (book.pickerFrame != null) {
                book.pickerBar.wheel(scrollY);
            }
            return true;
        }

        if (wheel == PanelKind.TEXTURE) {
            // The same for the texture picker's list: the wheel is its own, clamped where it lands, and
            // the dock behind the card does not move.
            if (book.textureFrame != null) {
                book.textureBar.wheel(scrollY);
            }
            return true;
        }

        if (wheel == PanelKind.CHOICE) {
            // The card is a list, so the wheel is the list's -- and absorbed whether or not there is
            // anywhere to go, the same as every other card's.
            book.choiceView.bar().wheel(scrollY);
            return true;
        }

        if (wheel == PanelKind.FILTER) {
            // The list's wheel is the list's, like every other card's: absorbed, so the quest
            // behind it does not scroll.
            book.filterView.bar().wheel(scrollY);
            return true;
        }

        if (wheel == PanelKind.REWARDS) {
            book.rewardView.bar().wheel(scrollY);
            return true;
        }

        // Over a rail the wheel scrolls it -- and does not zoom the canvas behind it, for the same reason a
        // press on it does not pan: it is a surface, not a hole.
        //
        // Asked of `wheel` rather than of `inTools`, which is the same answer by a longer road: `inTools` is
        // `panelColumnAt(...) == TOOLS`, so this was the one arm that resolved the rail twice.
        if (wheel == PanelKind.TOOLS) {
            book.toolsView.bar().wheel(scrollY);
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
            if (book.settingsOpen) {
                book.settingsView.bar().wheel(scrollY);
                return true;
            }
            // While a picker is open the wheel is the list's, not the card's: the card is not what is
            // on screen. Clamped where it lands, because a flick past the bottom should stop at the
            // bottom -- the same rule as the body's below, for the same reason.
            if (book.typePageOpen()) {
                if (book.typeFrame != null) {
                    book.typeBar.wheel(scrollY);
                }
                return true;
            }
            if (book.pickingItemPath != null && !book.itemPicker.pickerAsColumn()) {
                if (book.pickerFrame != null) {
                    book.pickerBar.wheel(scrollY);
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
            book.overlayView.bar().wheel(scrollY);
            return true;
        }

        // The player's own settings card, which had no branch at all: the wheel over it reached the
        // canvas and zoomed the graph behind the card. A modal that answers the pointer but not the
        // wheel is a modal with a hole in it, and this one has two -- see the naming card below.
        if (wheel == PanelKind.SETTINGS) {
            book.appearanceView.bar().wheel(scrollY);
            return true;
        }

        // The pinned list's own rows: a watchlist longer than its rail scrolls like every other list,
        // and the clamp is the viewport's, set from the same rows the drawing walks.
        if (wheel == PanelKind.PINNED) {
            book.pinnedView.bar().wheel(scrollY);
            return true;
        }

        // The element panel's own list: a form of twenty rows on a rail shorter than they are, so the wheel
        // belongs to the rows under the pointer -- and the clamp is the viewport's, set from the same rows the
        // drawing walks, so a short form cannot be scrolled past its end.
        if (wheel == PanelKind.ELEMENT) {
            book.bookCanvas.elementView.bar().wheel(scrollY);
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
            book.bookAssets.assetsBar.wheel(scrollY);
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
                && book.bookRewards.overTableList(mouseX, mouseY)) {
            if (wheel == PanelKind.TABLE_BROWSER) {
                if (book.tableBrowserFrame != null) {
                    book.tableBrowserBar.wheel(scrollY);
                }
            }
            else if (book.tableEditorFrame != null) {
                if (book.tableShowRoll) {
                    // The report's pitch is its own: twelve-pixel lines, not twenty-pixel rows.
                    book.rollBar.wheel(scrollY, QuestBookScreen.ROLL_PITCH);
                }
                else {
                    book.tableEditorBar.wheel(scrollY);
                }
            }
            return true;
        }

        // A wipe in flight absorbs the wheel over it: the rows are travelling and the viewport
        // already answers for its target, so a notch now would scroll a list that is not where it
        // is drawn. The wipe is 160 milliseconds; the wheel waits for it.
        long sidebarWheelNow = net.minecraft.util.Util.getMillis();
        if (book.sidebarDeparting && book.sidebarSliding(sidebarWheelNow)
                && book.sidebarWipeRect(sidebarWheelNow).contains(mouseX, mouseY)) {
            return true;
        }

        // The wheel over the slid-away sliver is neither the list's nor the canvas's: it carries
        // no rows, and zooming the graph through a sliver the pointer is hovering to peek would
        // move the map out from under the preview being opened.
        if (book.geometry().sidebarState().collapsed() && book.geometry().sidebar().contains(mouseX, mouseY)
                && !book.sidebarViewport().containsScreen(mouseX, mouseY)) {
            return true;
        }

        // The wheel over the sidebar scrolls the list, and over the canvas it still zooms. Routed by
        // region rather than by a modifier, because the two regions are visibly separate things and a
        // player pointing at one does not want the other: a list that zoomed the graph behind it, or a
        // canvas that scrolled a sidebar it is not over, is a control answering a question nobody asked.
        //
        // Checked before the canvas, and the two cannot both match -- the sidebar is to the left of the
        // canvas's own left edge -- so the order is for the reader rather than for the logic.
        if (book.sidebarViewport().containsScreen(mouseX, mouseY)) {
            // Through the bar's own notch counter and not its `wheel`, because this list snaps: a wheel
            // landing between two rows is answered by `scrollSidebar`, which moves the offset onto the
            // row the notch was aimed at. The fraction the counter keeps is what makes a trackpad's
            // small deltas add up to that notch instead of being truncated away.
            int notches = book.sidebarView.bar().notches(scrollY);
            if (notches != 0) {
                book.bookSidebar.scrollSidebar(-notches * SidebarLayout.pitch());
            }
            return true;
        }

        if (book.inCanvas(mouseX, mouseY)) {
            // Zoom, about the pointer. Not a scroll: a canvas that pans by dragging and also scrolls is
            // two controls for one idea, and the wheel is the one people reach for to zoom.
            book.bookCanvas.zoomAt(mouseX, mouseY, scrollY > 0 ? 1.15F : 1F / 1.15F);
            return true;
        }
        return book.superMouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
     * in {@link QuestBookScreen#partySearch} and re-focuses it, which is why the screen keeps that string at all: the
     * widget is not the storage.
     */
    boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        boolean handled = book.superCharTyped(event);
        if (!handled) {
            return false;
        }
        if (book.getFocused() == book.partySearchField && book.partySearchField != null) {
            book.partySearch = book.partySearchField.value();
            book.partySearchFocused = true;
            book.rebuildWidgets();
            return true;
        }
        if (book.getFocused() == book.partyCreateField && book.partyCreateField != null) {
            // No rebuild: the create field is the only thing its text affects, so there is nothing to
            // redraw until it is submitted.
            book.partyCreateName = book.partyCreateField.value();
            book.partyCreateFocused = true;
            return true;
        }
        return true;
    }

    boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        // The int the rest of this method reads: the SDL scancode side, matching the
        // `InputConstants.KEY_*` constants below (GLFW codes are not SDL scancodes).
        int keyCode = event.key();
        // **Any other key means "no".** An armed delete names a selection, and a key pressed between the
        // two is the author having moved on: the same "any other press disarms" rule the menus follow.
        // Delete and Backspace are the pair `ArmedDelete` owns, so they are left to answer for themselves.
        if (keyCode != InputConstants.KEY_DELETE && keyCode != InputConstants.KEY_BACKSPACE) {
            book.armedDelete.disarm();
        }
        // **The dropdown first, on the press's own rule.** A menu is the innermost thing on screen while it
        // is open, so Escape closes it and not what it was opened over -- which is what this line's own
        // comment has always said, and what it did not do: it stood below the table panels' Escape
        // branches, so with a menu opened over the table editor, Escape took the panel away and left the
        // menu standing over a card that no longer existed. See `pressDropdown` for the press's half of the
        // same rule, and `.utils/check_dropdowns.py` for the guard that holds the two halves together.
        if (!book.menu.isEmpty() && keyCode == InputConstants.KEY_ESCAPE) {
            book.bookMenu.closeMenu();
            return true;
        }
        // The table panels' own keys, read before anything else. Escape unwinds in the order the author
        // went in: the roll report first, then one table back, then the panel. A field left focused is
        // blurred by the rebuild that follows, and a blur is a commit -- so a half-typed title is kept
        // rather than dropped, which is the same rule the card's inline editor follows.
        if (book.overlay == PanelKind.TABLE_EDITOR && keyCode == InputConstants.KEY_ESCAPE) {
            // The innermost thing on screen answers first, in the order the author went in: the picker
            // page, then the roll report, then one table back, then the panel. The picker page had no
            // case here at all, so Escape closed the panel and left the picker armed -- which is how a
            // press on the type list ended up showing that list over a quest card.
            if (book.pickingEntryType != null) {
                book.pickingEntryType = null;
                book.rebuildWidgets();
                return true;
            }
            if (book.tableShowRoll) {
                book.tableShowRoll = false;
                book.tableImportOpen = false;
                return true;
            }
            book.bookRewards.popTableEditor();
            return true;
        }
        if (book.overlay == PanelKind.TABLE_BROWSER && keyCode == InputConstants.KEY_ESCAPE) {
            // Rebuild, for the same reason the outside press does: closing nulls the panel's fields
            // while its redraws and its footer buttons live until the next rebuild -- which left the
            // browser's own buttons drawn over the card and still taking presses, aimed at a reward that
            // had already been forgotten.
            book.bookRewards.closeTablePanelAndRebuild();
            return true;
        }
        if (book.overlay == PanelKind.ASSETS) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                // Escape and the footer's Done are one act, which is the rule every other card follows.
                book.closeOverlay();
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
                step = book.bookAssets.assetsBar.pageStep();
            }
            else if (keyCode == InputConstants.KEY_PAGEUP) {
                step = -book.bookAssets.assetsBar.pageStep();
            }
            if (step != 0) {
                book.bookAssets.assetsBody.setScrollY(book.bookAssets.assetsBody.scrollY() + step);
                return true;
            }
            return book.superKeyPressed(event);
        }
        // Ctrl+T opens the pack's panel: one chord per act, the way Ctrl+Z undoes and Ctrl+S reports that
        // there is nothing left to save. Only with nothing focused, so it cannot fire while a field is
        // being typed into — and never while previewing, where the Assets panel has no button and the
        // chord would open a surface the preview says is not there.
        if (book.overlay == PanelKind.NONE && keyCode == InputConstants.KEY_T && event.hasControlDown()
                && book.getFocused() == null && book.questEditor.mayEdit() && !book.bookCanvas.previewing()) {
            book.bookAssets.openAssets();
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
        if (book.getFocused() == null && !event.hasControlDown() && !event.hasAltDown()
                && keyCode == InputConstants.KEY_B && book.overlay != PanelKind.NAMING && !book.typePageOpen()
                && book.pickingItemPath == null && book.menu.isEmpty() && !book.colourPopover.isOpen()) {
            toggleSidebarCollapsed();
            return true;
        }
        if (book.getFocused() == null && event.hasControlDown() && !event.hasAltDown()
                && keyCode == InputConstants.KEY_B) {
            toggleSidebarPinned();
            return true;
        }
        if (book.overlay == PanelKind.TABLE_BROWSER && (keyCode == InputConstants.KEY_RETURN
                || keyCode == InputConstants.KEY_NUMPADENTER)) {
            // Enter on the browser takes the row the keyboard is on: the same rule the item picker's
            // Enter has, so the two lists are driven the same way.
            book.bookRewards.pressTableBrowserRow(book.tableSelected);
            return true;
        }
        // And the keys that *move* the keyboard's row. Without these the sentence above was a promise
        // nothing could keep: the selection starts at -1 and only a press changed it, so Enter was a key
        // that did nothing until the pointer had already done the work.
        if (book.overlay == PanelKind.TABLE_BROWSER
                && (keyCode == InputConstants.KEY_UP || keyCode == InputConstants.KEY_DOWN)) {
            book.bookRewards.stepTableBrowser(keyCode == InputConstants.KEY_DOWN ? 1 : -1);
            return true;
        }
        // The editor's undo and redo, read *before* the focus gate further down. `keysForEditor` is only
        // reached while no widget holds the keyboard, and this panel focuses a field the moment it opens
        // one: the title box is focused whenever the table has no title of its own, which is every table
        // an author has just made. So Ctrl+Z did nothing on exactly the table where a mistake is most
        // likely, and the field it went to has no undo of its own. These are the panel's keys, like its
        // Escape branches above, not the focused field's.
        if (book.overlay == PanelKind.TABLE_EDITOR && event.hasControlDown()) {
            if (keyCode == InputConstants.KEY_Z) {
                book.bookAssets.tableUndo();
                return true;
            }
            if (keyCode == InputConstants.KEY_Y) {
                book.bookAssets.tableRedo();
                return true;
            }
        }
        // The naming card's own keys, read before anything else: Enter is the button the card is for,        // and Escape abandons it without touching a file. Handing Enter to the field would blur it,
        // which is not what a form's Enter means.
        if (book.overlay == PanelKind.NAMING) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                book.bookSidebar.closeNaming();
                return true;
            }
            if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
                book.bookSidebar.submitNaming();
                return true;
            }
        }
        // The sidebar's inline edit, on the card's terms but focus-guarded: the card is modal, so
        // its Enter always means submit; the inline row is not, so its keys only answer while one
        // of its fields holds the keyboard. Anywhere else Enter still means what the focused
        // control says it means.
        if (book.inlineEdit != null && (book.getFocused() == book.inlineTitle || book.getFocused() == book.inlineId)) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                book.bookSidebar.cancelInline();
                return true;
            }
            if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
                book.bookSidebar.commitInline();
                return true;
            }
        }
        // F2 renames the selected chapter where it stands. Groups rename from their menu: they
        // are never the selection, so there is nothing for the key to name.
        if (keyCode == InputConstants.KEY_F2 && book.inlineEdit == null && book.mayEditNow()
                && QuestBookScreen.effectiveChapter() != null) {
            book.bookSidebar.startInlineRename("chapter", QuestBookScreen.effectiveChapter());
            return true;
        }
        // The item picker's keys, and they are read here rather than left to the search box: a field's
        // Enter and Escape *submit*, and while a picker is open neither means that -- Enter commits the
        // rule above, and Escape leaves the field as it was. Handing them to the box would set the
        // field to the text someone pressed Escape to abandon.
        // The type page's keys, on the item picker's terms and read first: the page is a list, so Enter
        // adds the selected row, the arrows walk it, and Escape leaves the page rather than the card
        // (`leaveCardPage` is what the footer's Back runs, so the key and the button are one act).
        if (book.typePageOpen()) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                book.questEditor.leaveTypePage();
                return true;
            }
            if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
                String chosen = book.editorControls.selectedType();
                if (chosen != null) {
                    book.questEditor.pressTypeRow(chosen);
                }
                return true;
            }
            if (keyCode == InputConstants.KEY_UP) {
                book.typeSelected = ItemPickerLayout.step(book.typeRows, book.typeSelected, -1);
                return true;
            }
            if (keyCode == InputConstants.KEY_DOWN) {
                book.typeSelected = ItemPickerLayout.step(book.typeRows, book.typeSelected, 1);
                return true;
            }
        }

        if (book.pickingItemPath != null) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                if (book.tablePickReturn) {
                    book.bookAssets.closeTablePick();
                    return true;
                }
                book.itemPicker.closeItemPicker();
                book.rebuildWidgets();
                return true;
            }
            if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
                book.itemPicker.commitFromPicker();
                return true;
            }
            if (keyCode == InputConstants.KEY_UP) {
                // The file tab walks its own rows: the keyboard's row is always the list on
                // screen, and the item list behind this tab is not.
                if (book.pickerTabsVisible() && book.pickerKind == QuestBookScreen.PickerKind.FILE) {
                    book.textureSelected = ItemPickerLayout.step(book.textureRows, book.textureSelected, -1);
                }
                else {
                    book.pickerSelected = ItemPickerLayout.step(book.pickerRows, book.pickerSelected, -1);
                }
                return true;
            }
            if (keyCode == InputConstants.KEY_DOWN) {
                if (book.pickerTabsVisible() && book.pickerKind == QuestBookScreen.PickerKind.FILE) {
                    book.textureSelected = ItemPickerLayout.step(book.textureRows, book.textureSelected, 1);
                }
                else {
                    book.pickerSelected = ItemPickerLayout.step(book.pickerRows, book.pickerSelected, 1);
                }
                return true;
            }
        }

        // The texture picker's keys, read on the item picker's terms and before the generic overlay
        // Escape below: Enter commits the typed id or the selected row, Escape leaves the field as it
        // was, and the arrows walk the list. Handing Enter or Escape to the search box would blur or
        // submit it, and neither is what a form's key means while a list is open.
        if (book.overlay == PanelKind.TEXTURE) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                book.texturePicker.closeTexturePicker();
                return true;
            }
            if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
                book.texturePicker.commitFromTexturePicker();
                return true;
            }
            if (keyCode == InputConstants.KEY_UP) {
                book.textureSelected = ItemPickerLayout.step(book.textureRows, book.textureSelected, -1);
                return true;
            }
            if (keyCode == InputConstants.KEY_DOWN) {
                book.textureSelected = ItemPickerLayout.step(book.textureRows, book.textureSelected, 1);
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
                && book.getFocused() instanceof net.minecraft.client.gui.components.AbstractWidget focused
                && focused.visible) {
            boolean handled = focused.keyPressed(event);
            if (!focused.isFocused()) {
                // The widget blurred itself, and the screen's own focus pointer would otherwise stay on
                // it: a widget that is not focused refuses `charTyped`, so the field read as dead until
                // it was clicked again. See the same cleanup after `super.keyPressed` below.
                book.setFocused(null);
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
        if (book.colourPopover.isOpen() && keyCode == InputConstants.KEY_ESCAPE) {
            book.closeColourPopover();
            book.rebuildWidgets();
            return true;
        }

        // And its arrows: the picker's keyboard is the only way to nudge a colour by one, and it acts on
        // the part the pointer is over -- read from the frame the picker was last drawn with, so the key
        // and the crosshair cannot disagree. Shift is the ten-step. Read before the canvas focus below,
        // which would otherwise walk the nodes while a picker is open over them.
        if (book.colourPopover.isOpen()
                && book.colourPopover.keyPressed(keyCode, event.hasShiftDown())) {
            return true;
        }

        // A pick is armed on the canvas with the card closed, so its Escape is answered here rather than
        // inside the overlay branch below: the author who armed it has no overlay to close, and before
        // this was outside the guard Escape fell through to the screen's own handler and closed the whole
        // book. The field comment said "Escape cancels" and the code could not reach the branch that did.
        if (book.pickingDependency && keyCode == InputConstants.KEY_ESCAPE) {
            book.pickingDependency = false;
            dev.ellipog.tenet.client.dev.DependencyPick pick = book.pendingPick;
            book.pendingPick = null;
            book.status("tenet.status.add_cancelled", false);
            if (pick != null) {
                book.questEditor.returnToEditedQuest(pick);
            }
            return true;
        }
        // And a link-target pick is cancelled the same way: Escape forgets the armed link, and the tab
        // goes back to showing its fields rather than waiting for a click that will never land.
        if (book.bookCanvas.pendingLinkPick != null && keyCode == InputConstants.KEY_ESCAPE) {
            book.bookCanvas.pendingLinkPick = null;
            book.status("tenet.status.add_cancelled", false);
            book.rebuildWidgets();
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
        if (keyCode == InputConstants.KEY_ESCAPE && book.getFocused() == null) {
            if (book.sidebarPeeking) {
                book.setSidebarPeeking(false);
                return true;
            }
            if (DevMode.isSidebarFloating() && !DevMode.isSidebarCollapsed()
                    && !DevMode.isSidebarPinned()) {
                book.setSidebarCollapsed(true);
                return true;
            }
        }
        if (keyCode == InputConstants.KEY_ESCAPE
                && (book.overlay != PanelKind.NONE || book.overlay2 != PanelKind.NONE || book.dockOpen)) {
            // **Outermost first.** With a child rail open, the first press peels it -- the thing the player
            // was in -- and only the next one closes the panel they opened. That is what "one press, one
            // step back" means with a rail beside a rail, and it goes through the same unwind the X and the
            // breadcrumb use, so a table editor's field is committed on the way out.
            if (book.overlay2 != PanelKind.NONE) {
                book.closePanelColumn(true);
                return true;
            }
            if (book.settingsOpen) {
                // Back to the panel, and the page's pending values go with it: a draft is the page's own
                // state, and one left behind would make the canvas draw a number the file does not have.
                book.settingsOpen = false;
                book.draggingSlider = null;
                book.settingsDraft.clear();
                book.rebuildWidgets();
                return true;
            }
            if (book.pickingEntryType != null) {
                book.pickingEntryType = null;
                book.rebuildWidgets();
                return true;
            }
            if (book.overlay == PanelKind.CHOICE) {
                // Escape leaves the question unanswered, which loses nothing -- see `closeChoice`. This
                // branch rather than `closeOverlay`, which does not clear the offer: the next tick would
                // open the same question straight back up.
                book.bookOverlay.closeChoice();
                return true;
            }
            if (book.overlay != PanelKind.NONE) {
                book.closeOverlay();
                return true;
            }
            // Only the dock is left, so this press is the dock's -- the same act as its pill and its X.
            book.pressAuthorPill();
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
        if (book.mayEditNow() && book.getFocused() == null && book.bookInput.keysForEditor(event)) {
            return true;
        }

        boolean handled = book.superKeyPressed(event);
        // A widget that ended its own edit (Enter in a text field blurs it and commits) leaves the
        // screen's focus pointer on it -- `AbstractWidget` and the container's focus are two flags, and
        // only the container's is what input is routed by. Clearing it here is what makes the *next*
        // keystroke reach the screen again instead of a field that refuses characters because it is no
        // longer focused.
        if (book.getFocused() instanceof net.minecraft.client.gui.components.AbstractWidget focused
                && !focused.isFocused()) {
            book.setFocused(null);
        }
        // **A focused control that did not want the key leaves the editor's own chords to the screen.**
        // A number field is focused by the very press that starts a drag, and it answers false for
        // Ctrl+Z -- so before this, Ctrl+Z after touching a number did nothing at all: not the field's
        // undo, not the chapter's. Only the undo pair and Ctrl+S, and only once the widget has refused
        // them by returning false: a focused field's Backspace is a character and its Ctrl+A is a
        // selection, so the destructive chords stay behind the no-focus guard above. A text field
        // consumes Z and Y itself and keeps that meaning -- see `ArmatureTextField`.
        if (!handled && book.getFocused() != null && book.mayEditNow() && BookInput.unclaimedEditorChord(keyCode,
                event.hasControlDown())
                && book.bookInput.keysForEditor(event)) {
            return true;
        }
        return handled;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

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

    /** Duplicates the whole selection: one history step per chapter it spans. */
    void duplicateSelection(List<String> ids) {
        book.bookInput.sendBulk(ids, EditorOp.Duplicate::new);
    }

    /** Deletes the whole selection: one history step per chapter it spans. */
    void deleteSelection(List<String> ids) {
        book.bookInput.sendBulk(ids, EditorOp.Delete::new);
    }

    // ------------------------------------------------------------------
    // The multi-selection's whole-hand gestures
    // ------------------------------------------------------------------

    /** Copies the selection's own trees to the session clipboard. */
    void copySelection() {
        List<String> ids = QuestBookScreen.selection();
        if (ids.isEmpty()) {
            return;
        }
        String chapter = QuestBookScreen.effectiveChapter();
        List<JsonObject> trees = new ArrayList<>();
        for (String id : ids) {
            JsonObject tree = ClientChapterReplica.quest(chapter, id);
            if (tree != null) {
                // What the author sees is what is copied: a pending edit is part of the selection's
                // value, and the clipboard deep-copies, so a later overlay cannot reach into it.
                trees.add(QuestBookScreen.fieldDraft.overlaid(chapter, id, tree));
            }
        }
        if (trees.isEmpty()) {
            book.status("tenet.status.nothing_to_copy_yet_the_chapter_s_copy_has_not_a", true);
            return;
        }
        ClientEditorClipboard.copy(trees);
        book.status("Copied " + trees.size() + (trees.size() == 1 ? " quest" : " quests"), false);
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
            book.send(new EditorOp.Paste(trees.get(i),
                    BookGeometry.snap(x + i * 48, BookGeometry.SNAP_GRID, on),
                    BookGeometry.snap(y, BookGeometry.SNAP_GRID, on)));
        }
        book.status("Pasting " + trees.size() + (trees.size() == 1 ? " quest" : " quests"), false);
    }

    /** Selects every quest in the chapter: the primary first, the rest in the chapter's own order. */
    void selectAllInChapter() {
        List<ClientQuestCache.Entry> quests = book.questsIn(QuestBookScreen.effectiveChapter());
        if (quests == null || quests.isEmpty()) {
            return;
        }
        QuestBookScreen.multiSelection.clear();
        QuestBookScreen.selectedQuest = quests.get(0).id();
        for (int i = 1; i < quests.size(); i++) {
            QuestBookScreen.multiSelection.add(quests.get(i).id());
        }
        book.status("Selected " + quests.size() + (quests.size() == 1 ? " quest" : " quests"), false);
    }
}
