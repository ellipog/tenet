package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ArmatureTextField;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tenet.quest.CanvasElement;
import dev.ellipog.tenet.client.dev.ItemPicker;
import dev.ellipog.tenet.client.dev.ItemPickerLayout;
import dev.ellipog.tenet.client.dev.Labels;
import dev.ellipog.tenet.client.dev.TexturePicker;
import dev.ellipog.tenet.client.dev.ToolsPanel;
import java.util.List;

/**
 * The quest book's texture picker: the pack's PNGs, gathered once, and the card that chooses one for
 * the canvas's surface or for one canvas element.
 *
 * <p>Moved out of {@link QuestBookScreen} as it stood, with the state only it reads; the screen
 * keeps the state it shares and reaches this through its {@code texturePicker} field.
 */
final class BookTexturePicker {

    private final QuestBookScreen book;

    BookTexturePicker(QuestBookScreen book) {
        this.book = book;
    }

    /**
     * The texture picker's own state -- catalogue, query, list and scroll -- beside the item picker's
     * and sharing none of it.
     *
     * <h2>Why a second set of fields rather than reuse</h2>
     *
     * <p>The item picker's state describes a field of a quest, a chapter's identity or the book's icon;
     * the texture picker's describes a file choice over the pack's own resources. Folding them together
     * would make opening one picker depend on the other's target and leave a stale "current" id naming a
     * field the open list is not about -- the same reasoning that gave the picker an overlay of its own
     * rather than a page of the quest card. The card's <i>shape</i> is shared (see
     * {@code ItemPickerLayout.Frame}); the state is not.
     */
    private List<ItemPicker.Entry> textureCatalogue;
    private ArmatureTextField textureSearch;
    private String textureQuery = "";

    // ------------------------------------------------------------------
    // The texture picker
    // ------------------------------------------------------------------

    /**
     * The pack's own PNGs, gathered once and kept.
     *
     * <h2>Why the walk is done once and not per open</h2>
     *
     * <p>{@code listResources} walks every pack's index, and the answer is the client's file list --
     * which changes when a pack is reloaded, not between two presses of a row's browse button. The
     * catalogue is therefore gathered the first time a picker is opened and kept for the session; the
     * one moment it could go stale is a resource reload, and a picker that paid the whole walk per open
     * to be current for that is the worse trade. A manager that is not there yet answers an empty list
     * without caching it, so the first real open still gathers.
     */
    List<ItemPicker.Entry> textureCatalogue() {
        if (textureCatalogue == null) {
            if (book.minecraft() == null || book.minecraft().getResourceManager() == null) {
                return List.of();
            }
            textureCatalogue = TexturePicker.catalogue(book.minecraft().getResourceManager()
                    .listResources("textures", path -> path.getPath().endsWith(".png")).keySet());
        }
        return textureCatalogue;
    }

    /**
     * Opens the texture picker on the canvas image's Texture row.
     *
     * <p>A fresh list every time: the query, the scroll and the selection belong to the pick that opened
     * the card, and a search left from the last texture choice would filter a list the author did not
     * type into. The catalogue itself is the session's, see {@link #textureCatalogue}.
     */
    void openTexturePicker() {
        openTexturePickerFor(null);
    }

    /**
     * The same, choosing a file for one canvas element rather than for the canvas's own surface.
     *
     * <p>The target is a parameter of the opening rather than a field set beforehand, because a picker that
     * read a target left over from the last time would commit a file to whatever the author had open an hour
     * ago. Null is the canvas's own background, which is what every existing caller means.
     */
    void openTexturePickerFor(String elementId) {
        book.bookCanvas.texturePickElement = elementId;
        textureSearch = null;
        textureQuery = "";
        book.bookState.textureSelected = -1;
        book.bookState.textureBody.setScrollY(0);
        book.bookState.textureMatches = List.of();
        book.bookState.textureRows = List.of();
        book.bookState.textureFrame = null;
        textureCatalogue();
        rebuildTextureRows("");
        book.applyColumns(PanelStack.afterOpen(book.columns(), PanelKind.TEXTURE));
        book.rebuildWidgets();
    }

    /**
     * The list for a query: ranked once, when the box changes, rather than once a frame.
     *
     * <p>A pack's textures run to five figures and ranking is a walk over all of them, so ranking in the
     * drawing was a walk over the catalogue twice every frame while a query was being typed -- a stutter
     * in exactly the packs this picker exists for. The query is the only thing that can change the
     * answer, so it is the only thing that pays for it.
     */
    private void rebuildTextureRows(String query) {
        book.bookState.textureMatches = ItemPicker.rank(textureCatalogue(), query, ItemPicker.LIMIT);
        // The highlighted row is the file the target is drawn from: the canvas's own texture, or the element
        // the picker was opened for -- so a list opened from a picture marks that picture's file.
        book.bookState.textureRows = TexturePicker.rows(textureCatalogue(), pickerCurrentTexture(), query);
    }

    /** The file the picker's target is drawn from, or empty when it names none. */
    private String pickerCurrentTexture() {
        if (book.bookCanvas.texturePickElement == null) {
            return book.editedBackground().image().texture();
        }
        CanvasElement element = book.elementNow(book.bookCanvas.texturePickElement);
        if (!(element instanceof CanvasElement.Image image)
                || !(image.image() instanceof dev.ellipog.tenet.quest.ImageSource.Texture texture)) {
            return "";
        }
        return texture.file().toString();
    }

    /** Closes the texture picker without writing: the book comes back, the field keeps its value. */
    void closeTexturePicker() {
        // Through the rules first, so the exit is recorded before its content is owed: the arrangement
        // is what snapshots the rail, and the teardown below is what keeps the rows for the slide.
        // This picker is a root today -- the browse button lives in the dock's own panel -- and closing
        // a root closes both columns.
        book.applyColumns(PanelStack.afterClose(book.columns(), false));
        book.deferForLastDeparted(() -> {
            textureSearch = null;
            textureQuery = "";
            book.bookState.textureSelected = -1;
            book.bookState.textureBody.setScrollY(0);
            book.bookState.textureMatches = List.of();
            book.bookState.textureRows = List.of();
            book.bookState.textureFrame = null;
        });
        book.rebuildWidgets();
    }

    /**
     * The picker's search box: the one widget the card has, on the item picker's own terms.
     *
     * <p>Rebuilt with the value it already held, so a rebuild that is not a keystroke -- the world
     * behind the card ticking, a resize -- cannot clear what is being typed. Enter and Escape are the
     * screen's while the card is open (see {@code keyPressed}), so a blur commits nothing.
     */
    void buildTextureWidgets() {
        BookGeometry.Rect bodyRect = book.overlayBodyRect();
        ItemPickerLayout.Frame frame = ItemPickerLayout.Frame.of(bodyRect);
        String kept = textureSearch == null ? "" : textureSearch.value();
        textureSearch = new ArmatureTextField(frame.search().x(), frame.search().y(),
                frame.search().width(), frame.search().height(), kept);
        textureSearch.onSubmit(text -> { });
        book.addRenderableWidget(textureSearch);
        // The placeholder after the pass, and nothing else: see the item picker's own copy of this note.
        book.bookState.panelOverlays.add(r -> {
            if (textureSearch == null || !textureSearch.value().isEmpty()) {
                return;
            }
            try (GuiRenderer.Scoped clip = r.clip(bodyRect.x(), bodyRect.y(), bodyRect.right(),
                    bodyRect.bottom())) {
                // Off the box, for the rail's slide: see the item picker's own copy of this note.
                r.text(Labels.of("tenet.dev.texture.search"), textureSearch.getX() + 4,
                        textureSearch.getY() + (textureSearch.getHeight() - 8) / 2,
                        ArmatureTheme.faint());
            }
        });
        book.setFocused(textureSearch);
    }

    /**
     * The picker's card: the same panel, header strip and rule the item picker's overlay draws, with
     * the list's own title instead of a subject's icon and name.
     *
     * <p>Chrome like the item picker's card -- it is about the client's files rather than about the
     * chapter it was opened over -- so it is drawn outside every chapter scope by {@code drawModal}'s
     * own position, exactly as the item picker's card is.
     */
    void drawTextureOverlay(GuiRenderer r, int mouseX, int mouseY) {
        int left = book.overlayLeft();
        int top = book.overlayTop();
        int w = book.overlayWidth();
        int h = book.overlayHeight();

        BookFrame.drawCard(r, left, top, w, h, 46, false);

        // Both header lines stop short of the fold control when this column carries one. The second is the
        // one that noticed: the subject line is a texture's path, and a path is longer than any column.
        int headerRoom = Math.max(0, book.headerTextRoom(book.surfaceCard(book.bookState.surfaceKind)) - (left + 14));
        Measure headerMeasure = Measure.of(r::textWidth, r.lineHeight());
        r.text(Measure.truncate(Labels.of("tenet.dev.texture.title"), headerRoom, headerMeasure),
                left + 14, top + 12, ArmatureTheme.title());
        // The field's own value, under the title: the card is choosing a file, and the one it would
        // replace is the context the list is read against.
        String current = book.editedBackground().image().texture();
        r.text(Measure.truncate(
                        current.isEmpty() ? Labels.of("tenet.dev.canvas.pattern_none") : current,
                        headerRoom, headerMeasure),
                left + 14, top + 26, ArmatureTheme.faint());

        drawTexturePicker(r, book.overlayBody(), mouseX, mouseY);
    }

    /**
     * The texture picker's body: the search box above (a widget, placed by
     * {@link #buildTextureWidgets}), then the rows.
     *
     * <p>The item picker's drawing with one substitution: a texture row's picture is the file itself,
     * probed and drawn at its aspect by {@code ToolsPanel.drawTextureThumb}, where an item row draws its
     * stack's icon. Everything else -- the clip to the list, the skip of wholly-out rows, the heading's
     * faint line, the recessed selection, the hover wash and the missing rows' placeholder -- is the
     * same shape, so the two lists cannot disagree about what a row looks like.
     */
    private void drawTexturePicker(GuiRenderer r, Viewport body, int mouseX, int mouseY) {
        BookGeometry.Rect bodyRect = BookGeometry.Rect.at(body.originX(), body.originY(),
                body.viewWidth(), body.viewHeight());
        book.bookState.textureFrame = ItemPickerLayout.Frame.of(bodyRect);
        String query = textureSearch == null ? "" : textureSearch.value();
        if (!query.equals(textureQuery)) {
            // A keystroke is a new list: the selection goes back to its first row and the scroll to the
            // top, because an index into the old list names nothing in this one. The list itself is
            // rebuilt here and nowhere else -- see `rebuildTextureRows` for why the drawing must not.
            textureQuery = query;
            book.bookState.textureSelected = -1;
            book.bookState.textureBody.setScrollY(0);
            rebuildTextureRows(query);
        }
        QuestBookScreen.bindList(book.bookState.textureBody, book.bookState.textureBar,
                book.bookState.textureFrame.list(),
                book.bookState.textureFrame.scrollbar(),
                ItemPickerLayout.contentHeight(book.bookState.textureRows), ItemPickerLayout.ROW_HEIGHT);
        int textureScroll = book.bookState.textureBody.scrollY();
        if (book.bookState.textureSelected >= 0) {
            book.bookState.textureSelected = ItemPickerLayout.clamp(book.bookState.textureRows,
                    book.bookState.textureSelected);
        }

        // Clipped to the list, not the body: a row half scrolled under the search box belongs at the
        // box's edge, which is where the list starts -- the item picker's own note says the same.
        try (GuiRenderer.Scoped clip = r.clip(book.bookState.textureFrame.list().x(),
                book.bookState.textureFrame.list().y(),
                book.bookState.textureFrame.list().right(), book.bookState.textureFrame.list().bottom())) {
            if (book.bookState.textureRows.isEmpty()) {
                r.text(Labels.of("tenet.dev.texture.hint"), book.bookState.textureFrame.list().x() + 4,
                        book.bookState.textureFrame.list().y() + 4, ArmatureTheme.faint());
                return;
            }
            Measure measure = book.textMeasure(r);
            for (int i = 0; i < book.bookState.textureRows.size(); i++) {
                BookGeometry.Rect rect =
                        ItemPickerLayout.rowRect(book.bookState.textureRows, book.bookState.textureFrame,
                                textureScroll, i);
                // Off the list is not drawn -- the clip would cut a row the keyboard can still land on
                // and the wheel can bring back, and a half row at the edge is exactly what the clip is
                // for, so only the wholly-out ones are skipped.
                if (rect.bottom() <= book.bookState.textureFrame.list().y()
                        || rect.y() >= book.bookState.textureFrame.list().bottom()) {
                    continue;
                }
                ItemPickerLayout.Row row = book.bookState.textureRows.get(i);
                if (row.kind() == ItemPickerLayout.Kind.HEADING) {
                    r.text(row.label(), rect.x() + 2, rect.y() + (rect.height() - 8) / 2,
                            ArmatureTheme.faint());
                    continue;
                }
                if (i == book.bookState.textureSelected) {
                    r.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), ArmatureTheme.recessed());
                }
                else if (rect.contains(mouseX, mouseY)) {
                    QuestBookScreen.drawEditAffordance(r, rect, true);
                }
                int box = Math.max(8, rect.height() - 2);
                int textX = rect.x() + 4;
                boolean missing = row.kind() == ItemPickerLayout.Kind.MISSING;
                if (row.kind() == ItemPickerLayout.Kind.TEXTURE) {
                    // The picture in the row's own square, at its aspect: the one thing an id cannot
                    // say and the whole reason this picker exists rather than a second text field.
                    ToolsPanel.drawTextureThumb(r, new Slot(row.id(), rect.x() + 1, rect.y() + 1,
                            box, box), row.id(), book.bookState.textureSizes);
                    textX = rect.x() + 20;
                }
                else if (missing) {
                    // No file behind the id: a placeholder where the picture would be, and the note
                    // that says so. The id itself is the label, so it is kept and visible either way.
                    QuestBookScreen.drawItemPlaceholder(r, rect.x() + 1, rect.y() + 1, box);
                    textX = rect.x() + 20;
                }
                r.text(Measure.truncate(row.label(), rect.width() - (textX - rect.x()) - 30, measure),
                        textX, rect.y() + (rect.height() - 8) / 2,
                        missing ? ArmatureTheme.blocked() : ArmatureTheme.body());
                if (!row.secondary().isEmpty()) {
                    // The namespace, faint at the right: two packs both holding `gui/button` are told
                    // apart by this and by nothing else in the row.
                    r.text(row.secondary(), rect.right() - 4 - r.textWidth(row.secondary()),
                            rect.y() + (rect.height() - 8) / 2,
                            missing ? ArmatureTheme.blocked() : ArmatureTheme.faint());
                }
            }
        }
        // The list's bar, in the frame's own strip and outside the clip: the item picker's arrangement,
        // because the two pages are the same act with a different catalogue.
        QuestBookScreen.drawBar(r, book.bookState.textureBar, mouseX, mouseY);
    }

    /**
     * A press on a texture row: the file becomes the canvas image's texture, or the row is missing and
     * already the field's value.
     *
     * <p>Mirrors {@code pressPickerRow}: a row whose id is the current value is "keep it", closing with
     * no edit spent saying nothing. Every other row goes through the field's own commit, and the card
     * closes only when it wrote -- a refusal keeps the list open with the reason on the panel, so the
     * next press is a correction rather than a reopened picker.
     */
    void pressTextureRow(int index) {
        if (index < 0 || index >= book.bookState.textureRows.size()) {
            return;
        }
        ItemPickerLayout.Row row = book.bookState.textureRows.get(index);
        if (!ItemPickerLayout.pickable(row)) {
            return;
        }
        String current = book.editedBackground().image().texture();
        if (row.kind() == ItemPickerLayout.Kind.MISSING && row.id().equals(current)) {
            closeTexturePicker();
            return;
        }
        if (book.questEditor.commitCanvasTexture(row.id())) {
            closeTexturePicker();
        }
    }

    /**
     * Enter in the texture picker, on the item picker's two rules: a whole id typed into the box is the
     * answer outright, otherwise the selected row commits, and when neither exists the screen says so
     * rather than choosing whatever happened to sort first.
     */
    void commitFromTexturePicker() {
        String query = textureSearch == null ? "" : textureSearch.value();
        String exact = ItemPicker.exactId(query, book.bookState.textureMatches);
        if (exact != null) {
            if (book.questEditor.commitCanvasTexture(exact)) {
                closeTexturePicker();
            }
            return;
        }
        ItemPickerLayout.Row row = book.bookState.textureSelected >= 0
                && book.bookState.textureSelected < book.bookState.textureRows.size()
                ? book.bookState.textureRows.get(book.bookState.textureSelected) : null;
        if (row != null && ItemPickerLayout.pickable(row)) {
            pressTextureRow(book.bookState.textureSelected);
            return;
        }
        book.status(query.isBlank() ? "Type to search, or paste a texture id"
                : "No texture matches \"" + query + "\"", true);
    }
}
