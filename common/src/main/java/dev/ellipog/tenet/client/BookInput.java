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
        for (QuestBookScreen.EditTarget target : book.assetsTargets) {
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
                    book.tableRedo();
                }
                else {
                    book.tableUndo();
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
                book.tableRedo();
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
            book.selectAllInChapter();
            return true;
        }
        if (ctrl && keyCode == InputConstants.KEY_C) {
            book.copySelection();
            return true;
        }
        if (ctrl && keyCode == InputConstants.KEY_V && !ClientEditorClipboard.isEmpty()) {
            pasteClipboard();
            return true;
        }
        if (ctrl && keyCode == InputConstants.KEY_D && QuestBookScreen.selectedQuest != null) {
            // The whole selection duplicates, as one edit per chapter it spans: seventy quests are one
            // gesture, one history step and one save, and one Ctrl+Z takes them all back. See `sendBulk`.
            book.duplicateSelection(QuestBookScreen.selection());
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
                        + QuestBookScreen.questDeleteNote(targets) + " - Ctrl+Z takes it back");
                case ArmedDelete.Confirmed confirmed -> book.deleteSelection(confirmed.ids());
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
        book.minecraft().getNarrator().saySystemNow(Component.translatable("tenet.narrate.quest", QuestBookScreen.titleOf(entry),
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
        boolean on = QuestBookScreen.snappingNow();
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
        QuestBookScreen.fieldDraft.set(QuestBookScreen.effectiveChapter(), dev.ellipog.tenet.client.dev.FieldDraft.BOOK_OWNER,
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
        else if (!QuestBookScreen.isSelected(id)) {
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
            QuestBookScreen.selectedQuest = QuestBookScreen.multiSelection.isEmpty() ? null : QuestBookScreen.multiSelection.iterator().next();
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
            int x = book.nodeScreenX(entry);
            int y = book.nodeScreenY(entry);
            int size = book.nodeSize(entry);
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
        book.pasteAt(book.viewport().contentX(book.canvasLeft() + (book.visibleCanvasRight() - book.canvasLeft()) / 2.0),
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
        if (book.mayEdit()) {
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
}
