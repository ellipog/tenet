package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tenet.editor.EditorOp;
import dev.ellipog.tenet.client.ClientChapterReplica;
import dev.ellipog.tenet.client.dev.ChapterPanelLayout;
import dev.ellipog.tenet.client.dev.QuestPanelLayout;
import dev.ellipog.tenet.client.dev.RowDrag;
import dev.ellipog.tenet.client.dev.ToolsLayout;
import dev.ellipog.tenet.client.dev.ToolsPanel;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * The quest book's row drag: carrying one row of a panel's list (a quest's entries, or a chapter's
 * quests) to a new position, and committing the move as one edit.
 *
 * <p>Moved out of {@link QuestBookScreen} as it stood, with the state only it reads; the screen
 * keeps the state it shares and reaches this through its {@code rowDrag} field.
 */
final class BookRowDrag {

    private final QuestBookScreen book;

    BookRowDrag(QuestBookScreen book) {
        this.book = book;
    }

    // ------------------------------------------------------------------
    // The row drag
    // ------------------------------------------------------------------

    /**
     * Lets a dragged row go: one op, aimed at the gap the line was last drawn on.
     *
     * <p>Three lists, two ops. A task or a reward moves by {@link EditorOp.MoveEntry}, which the model
     * has carried and refused-bounds since before any of this was draggable. The chapter's quest list
     * is not any quest's file -- it is the chapter manifest's own {@code "quests"} array, which only
     * {@link EditorOp.SetChapter} reaches -- so the drop says the whole reordered list rather than an
     * index move, which is a bigger message and the same one edit.
     *
     * <p>A drop onto the row's own place sends nothing: "that edit would change nothing" as a refusal
     * is the server's way of saying it, and there is no reason to spend a round trip on it.
     */
    void commitRowDrop(String member, String quest, int from, double pointerY) {
        if (!book.mayEditNow() || member == null || from < 0) {
            return;
        }
        List<BookGeometry.Rect> rows = book.dragRowSlots.get(member);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        int gap = RowDrag.gapAt(rows, pointerY);
        if (gap < 0) {
            return;
        }
        int to = RowDrag.finalIndex(from, gap);
        if (to == from) {
            return;
        }
        if ("chapter".equals(member)) {
            // The pending order, not the replica's: two reorders inside the replica window would
            // otherwise compute the second from the first's stale order and drop it.
            JsonObject chapter = QuestBookScreen.fieldDraft.overlaid(QuestBookScreen.effectiveChapter(),
                    dev.ellipog.tenet.client.dev.FieldDraft.CHAPTER_OWNER,
                    ClientChapterReplica.chapterTree(QuestBookScreen.effectiveChapter()));
            List<String> names = new ArrayList<>(QuestPanelLayout.strings(chapter, "quests"));
            if (from >= names.size() || to >= names.size() || to < 0) {
                return;
            }
            names.add(to, names.remove(from));
            book.sendChapterField("quests", QuestBookScreen.stringArray(names));
        }
        else {
            if (quest == null) {
                return;
            }
            // The entries are about to shift, so a pending value indexed inside the list no longer
            // names the entry the author was editing.
            QuestBookScreen.fieldDraft.forgetList(QuestBookScreen.effectiveChapter(), quest, member);
            book.send(new EditorOp.MoveEntry(quest, member, from, to));
        }
        book.status("Moved to position " + (to + 1), false);
    }

    /**
     * The chapter-tab row a pointer is over, as an index into the manifest's quest order, or -1.
     *
     * <p>The rows are drawn by {@code ToolsPanel} from {@code chapterLayout}; this is the same mapping the
     * drawing uses, so what is pressed is what is seen, scrolled or not.
     */
    int chapterQuestRowAt(double mouseX, double mouseY) {
        if (book.chapterLayout == null) {
            return -1;
        }
        Viewport view = book.toolsView.viewport();
        int index = 0;
        for (ToolsLayout.Action row : book.chapterRows) {
            if (!row.key().startsWith(ChapterPanelLayout.VALUE_PREFIX + "quest:")) {
                continue;
            }
            Slot slot = book.chapterLayout.slot(row.key());
            if (slot != null && ToolsLayout.onScreen(view, slot).contains(mouseX, mouseY)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    /** The same rows as rectangles, in order -- the seams the drag's gap arithmetic counts. */
    List<BookGeometry.Rect> chapterQuestRowRects() {
        List<BookGeometry.Rect> out = new ArrayList<>();
        if (book.chapterLayout == null) {
            return List.copyOf(out);
        }
        Viewport view = book.toolsView.viewport();
        for (ToolsLayout.Action row : book.chapterRows) {
            if (!row.key().startsWith(ChapterPanelLayout.VALUE_PREFIX + "quest:")) {
                continue;
            }
            Slot slot = book.chapterLayout.slot(row.key());
            if (slot == null) {
                continue;
            }
            Slot onScreen = ToolsLayout.onScreen(view, slot);
            out.add(BookGeometry.Rect.at(onScreen.x(), onScreen.y(), onScreen.width(),
                    onScreen.height()));
        }
        return List.copyOf(out);
    }
}
