package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ArmatureSlider;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.inspect.InspectLayout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.ScrollView;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.tenet.editor.EditorOp;
import dev.ellipog.tenet.client.hud.PinnedQuests;
import dev.ellipog.tenet.quest.CanvasElement;
import dev.ellipog.tenet.quest.QuestLink;
import dev.ellipog.tenet.client.dev.ChapterPanelLayout;
import dev.ellipog.tenet.client.dev.Labels;
import dev.ellipog.tenet.client.dev.ToolsLayout;
import dev.ellipog.tenet.client.dev.ToolsPanel;
import dev.ellipog.tenet.progress.QuestState;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

/**
 * The quest book's pins: pinning a quest to the HUD from the book, and the pinned list.
 *
 * <p>Moved out of {@link QuestBookScreen} as it stood, with the state only it reads; the screen
 * keeps the state it shares and reaches this through its {@code bookPins} field.
 */
final class BookPins {

    private final QuestBookScreen book;

    BookPins(QuestBookScreen book) {
        this.book = book;
    }

    // ------------------------------------------------------------------
    // Pinning to the HUD
    // ------------------------------------------------------------------

    /**
     * One element with any pending field values applied.
     *
     * <p>Through the element's own JSON rather than field by field, and that is the point: the draft writes
     * paths like {@code width}, {@code image.texture} and {@code label.onImage}, and the element's JSON is
     * exactly the tree those paths address — so one overlay covers every arm, every nested object and every
     * field a later build adds, with no second list of field names to keep in step.
     */
    static CanvasElement overlaidElement(String chapter, CanvasElement element) {
        JsonObject tree = BookScreenState.fieldDraft.overlaid(chapter,
                QuestBookScreen.ELEMENT_OWNER_PREFIX + element.id(),
                CanvasElement.asJson(element));
        // A draft cannot make this unreadable -- the paths it writes are the fields this build reads -- but
        // the fallback is the element itself rather than a hole in the canvas.
        return CanvasElement.fromJson(tree).orElse(element);
    }

    /**
     * One link with any pending field values applied.
     *
     * <p>Through the link's own JSON rather than field by field, and that is the point: the draft writes
     * paths like {@code quest}, {@code x} and {@code shape}, and the link's JSON is exactly the tree
     * those paths address — so one overlay covers every field a later build adds, with no second list
     * of field names to keep in step.
     */
    static QuestLink overlaidLink(String chapter, QuestLink link) {
        JsonObject tree = BookScreenState.fieldDraft.overlaid(chapter, QuestBookScreen.LINK_OWNER_PREFIX + link.id(),
                QuestLink.asJson(link));
        // A draft cannot make this unreadable -- the paths it writes are the fields this build reads -- but
        // the fallback is the link itself rather than a hole in the canvas.
        return QuestLink.fromJson(tree).orElse(link);
    }

    /**
     * Where the element being carried is now: the element the press took hold of, with this gesture applied.
     *
     * <h2>Why this is built from the base and never from the cache</h2>
     *
     * <p>Because the cache carries the last frame's preview: a resize computed from the cached element would
     * take the box it drew and resize that, so every frame would compound the one before and the corner would
     * run away from the pointer. The base is captured once, at the press, and every frame is derived from it.
     *
     * <h2>The three gestures, and the one place they are told apart</h2>
     *
     * <p>A move follows the pointer with the grab offset kept, snapped when the switch says so and Alt is not
     * held — the free placement, read per event so it can be pressed mid-drag. A resize and a rotate read the
     * pointer's own position instead, because neither has a grab offset to keep: a corner <i>is</i> the
     * pointer's target, and an angle is a direction rather than a distance.
     */
    CanvasElement draggedElement(double mouseX, double mouseY) {
        if (book.bookCanvas.elementDragBase == null) {
            return null;
        }
        if (book.bookCanvas.elementHandle == null) {
            int x = (int) Math.round(BookGeometry.snap(book.viewport().contentX(mouseX) - book.bookState.dragGrabX,
                    BookGeometry.SNAP_GRID, BookInput.snappingNow()));
            int y = (int) Math.round(BookGeometry.snap(book.viewport().contentY(mouseY) - book.bookState.dragGrabY,
                    BookGeometry.SNAP_GRID, BookInput.snappingNow()));
            return book.bookCanvas.elementDragBase.translated(x - CanvasElementArt.originX(book.bookCanvas.elementDragBase),
                    y - CanvasElementArt.originY(book.bookCanvas.elementDragBase));
        }
        // A resize and a line's endpoint are not snapped: a grid is for placing things, and a box being sized
        // to the pixel is the point of dragging its corner. The rotate is not snapped either, because an angle
        // on an eight-pixel grid would jump in eleven-degree steps.
        int contentX = (int) Math.round(book.viewport().contentX(mouseX));
        int contentY = (int) Math.round(book.viewport().contentY(mouseY));
        return switch (book.bookCanvas.elementHandle) {
            case RESIZE_NW, RESIZE_NE, RESIZE_SW, RESIZE_SE ->
                    CanvasElementArt.resized(book.bookCanvas.elementDragBase, book.bookCanvas.elementHandle,
                            contentX, contentY);
            case END_FROM, END_TO ->
                    CanvasElementArt.endMoved(book.bookCanvas.elementDragBase, book.bookCanvas.elementHandle,
                            contentX, contentY);
            case ROTATE -> {
                int degrees = CanvasElementArt.rotationTo(book.bookCanvas.elementDragBase, contentX, contentY);
                yield book.bookCanvas.elementDragBase.withRotation(degrees);
            }
        };
    }

    /**
     * Commits one element gesture as one edit.
     *
     * <h2>Two writes for a box, four for a line, one for a rotate — and one batch around them</h2>
     *
     * <p>The fields come from {@link CanvasElementArt#geometry}, which diffs the element the gesture started
     * from against the one it produced. So the count is whatever really changed: a move writes two fields for a
     * box and four for a line, a resize writes two or four depending on which corner was grabbed, and a rotate
     * writes one. A batch is what makes any of those one history step — four separate ops would be four presses
     * of Ctrl+Z to undo one drag, which is the same fault the node drag's batching was introduced to fix.
     *
     * <p>An empty diff writes nothing at all, which is the case a click that happened to jitter produces: a
     * gesture that changed nothing must not cost a save and a history step.
     */
    void commitElementEdit(String id, CanvasElement from, CanvasElement to) {
        List<CanvasElementArt.Field> fields = CanvasElementArt.geometry(from, to);
        if (fields.isEmpty()) {
            return;
        }
        List<EditorOp> writes = new ArrayList<>(fields.size());
        for (CanvasElementArt.Field field : fields) {
            // The draft first, so the element stays where the hand put it until the tree agrees — which is
            // the whole of "a drag does not snap back when the pointer is released".
            book.questEditor.draftElementField(id, field.path(), new JsonPrimitive(field.value()));
            writes.add(new EditorOp.SetElement(id, field.path(), new JsonPrimitive(field.value())));
        }
        book.send(new EditorOp.Batch(List.copyOf(writes)));
    }

    /**
     * Commits one link drag as one edit.
     *
     * <p>Two writes when both coordinates moved, one when one did, none when neither did — and one batch
     * around them so one gesture is one history step. An empty diff writes nothing at all, which is the
     * case a click that happened to jitter produces: a gesture that changed nothing must not cost a save
     * and a history step.
     */
    void commitLinkEdit(String id, QuestLink from, QuestLink to) {
        List<EditorOp> writes = new ArrayList<>(2);
        if (from.x() != to.x()) {
            JsonElement x = new JsonPrimitive((long) to.x());
            draftLinkField(id, "x", x);
            writes.add(new EditorOp.SetLink(id, "x", x));
        }
        if (from.y() != to.y()) {
            JsonElement y = new JsonPrimitive((long) to.y());
            draftLinkField(id, "y", y);
            writes.add(new EditorOp.SetLink(id, "y", y));
        }
        if (writes.isEmpty()) {
            return;
        }
        // The draft first, so the link stays where the hand put it until the tree agrees — which is
        // the whole of "a drag does not snap back when the pointer is released".
        book.send(dev.ellipog.tenet.editor.EditorOps.batch(writes));
    }

    /**
     * Lands the pick: the quest that was clicked becomes the link's target.
     *
     * <p>Through the entry's own id rather than the string the canvas was showing: the click may have
     * landed on an alias in the sidebar, and the file wants the name the tree answers to. Any quest is
     * a legal target — the loader's own check reports a name that resolves to nothing, so the pick
     * needs no rules of its own.
     */
    void landLinkPick(ClientQuestCache.Entry target) {
        dev.ellipog.tenet.client.dev.LinkPick pick = book.bookCanvas.pendingLinkPick;
        book.bookCanvas.pendingLinkPick = null;
        if (pick == null || target == null) {
            return;
        }
        if (pick.creates()) {
            int[] at = book.bookCanvas.newLinkAt == null ? new int[] {0, 0} : book.bookCanvas.newLinkAt;
            book.bookCanvas.newLinkAt = null;
            book.questEditor.insertLink(pick.chapter(), at[0], at[1], target.id());
            book.status("New link to \"" + target.id() + "\"", false);
            return;
        }
        draftLinkFieldFor(pick.chapter(), pick.link(), "quest", new JsonPrimitive(target.id()));
        book.bookInput.send(new EditorOp.SetLink(pick.link(), "quest", new JsonPrimitive(target.id())), pick.chapter());
        book.status("The link now points at \"" + target.id() + "\"", false);
        book.rebuildWidgets();
    }

    /** One pending link-target value, aimed at the chapter that holds the link. */
    private void draftLinkFieldFor(String chapter, String link, String field, JsonElement value) {
        BookScreenState.fieldDraft.set(chapter, QuestBookScreen.LINK_OWNER_PREFIX + link, field, value,
                ClientQuestCache.treeRevision(), Util.getMillis());
    }

    /**
     * Puts the panel away, leaving the element selected.
     *
     * <p>Escape's own effect, and deliberately not a deselection: the ring stays, so the grips are still there
     * to drag and a second click brings the fields back. Letting go of the element is what a click on empty
     * canvas does, which is a different gesture saying a different thing.
     */
    private void closeElementPanel() {
        if (book.bookCanvas.elementPanelId == null) {
            return;
        }
        book.bookCanvas.elementPanelId = null;
        book.applyColumns(PanelStack.afterClose(book.columns(), false));
        book.rebuildWidgets();
    }

    // ------------------------------------------------------------------
    // The pinned list
    // ------------------------------------------------------------------

    /** The quest id a pin row key names, or null for a key that names none. */
    private static String pinnedIdOf(String key) {
        if (key == null || !key.startsWith("pinned:")) {
            return null;
        }
        String id = key.substring("pinned:".length());
        return id.isEmpty() ? null : id;
    }

    /**
     * One pin row's star end: a square against the row's own right edge.
     *
     * <p>The quest header's own pin control, in miniature: a filled star for a pinned quest, and the press
     * lets go of it. A square of the row's own height rather than a word's width, so the aim is the same
     * on every row and the title keeps what a label would have taken.
     */
    private static BookGeometry.Rect pinnedUnpinBox(BookGeometry.Rect row) {
        int side = Math.max(18, row.height());
        return BookGeometry.Rect.at(row.right() - side, row.y() + (row.height() - side) / 2, side,
                side);
    }

    /** The quest header's pin star, filled: every row here is pinned, so there is no hollow state. */
    private static final String PINNED_STAR = "\u2605";

    /**
     * The pinned list as a rail: its chrome, its rows, then the shared bar.
     *
     * <p>Drawn by hand from the layout rather than through {@code ToolsPanel}, because a pin row is two
     * presses wide: the row opens its quest and its star end lets go of it, and one widget per row
     * cannot be both. The heading is the panel's; the rows carry no widget at all.
     */
    void drawPinnedPanel(GuiRenderer r, int mouseX, int mouseY) {
        if (book.bookState.pinnedLayout == null) {
            return;
        }
        book.bookState.pinnedTargets.clear();
        int pins = dev.ellipog.tenet.client.hud.PinnedQuests.pinned().size();
        book.bookRewards.drawModalCardChrome(r, Labels.of("tenet.screen.pinned.title"),
                pins == 0 ? "Nothing pinned yet" : pins == 1 ? "1 quest on your HUD" : pins + " quests on your HUD",
                mouseX, mouseY, QuestBookScreen.EditAction.PINNED_CLOSE, book.bookState.pinnedTargets);
        Measure measure = book.textMeasure(r);
        int starWidth = r.textWidth(PINNED_STAR);
        try (GuiRenderer.Scoped clip = r.clip(book.overlayBodyRect().x(), book.overlayBodyRect().y(),
                book.overlayBodyRect().right(), book.overlayBodyRect().bottom())) {
            for (ToolsLayout.Action row : book.bookState.pinnedRows) {
                dev.ellipog.armature.client.ui.kit.Slot slot = book.bookState.pinnedLayout.slot(row.key());
                if (slot == null) {
                    continue;
                }
                dev.ellipog.armature.client.ui.kit.Slot onScreen =
                        ToolsLayout.onScreen(book.bookState.pinnedView.viewport(), slot);
                BookGeometry.Rect box = BookGeometry.Rect.at(onScreen.x(), onScreen.y(),
                        onScreen.width(), onScreen.height());
                switch (row.kind()) {
                    case HEADING -> r.text(Measure.truncate(Labels.of(row.label()), box.width(), measure),
                            box.x(), box.y() + (box.height() - r.lineHeight()) / 2,
                            ArmatureTheme.title());
                    case VALUE -> {
                        if (PINNED_EMPTY.equals(row.key())) {
                            r.text(Measure.truncate(row.value(), box.width(), measure),
                                    box.x(), box.y() + (box.height() - r.lineHeight()) / 2,
                                    ArmatureTheme.faint());
                            continue;
                        }
                        String id = pinnedIdOf(row.key());
                        boolean hidden = id != null && pinnedHidden(id);
                        int ink = hidden ? ArmatureTheme.faint() : ArmatureTheme.body();
                        BookGeometry.Rect unpinBox = pinnedUnpinBox(box);
                        // The quest's own icon, where every book row puts one: the room is kept whether or
                        // not the stack resolved, so a row never shifts under the pointer reading it.
                        int textX = box.x() + 2 + QuestBookScreen.ROW_ICON + 5;
                        ItemStack pinIcon = id == null ? ItemStack.EMPTY
                                : book.bookState.pinnedIcons.getOrDefault(id, ItemStack.EMPTY);
                        if (!pinIcon.isEmpty()) {
                            r.icon(pinIcon, box.x() + 2,
                                    box.y() + (box.height() - QuestBookScreen.ROW_ICON) / 2, QuestBookScreen.ROW_ICON);
                        }
                        int titleRoom = Math.max(0, unpinBox.x() - 6 - textX);
                        String title = Measure.truncate(row.label(), titleRoom, measure);
                        r.text(title, textX, box.y() + (box.height() - r.lineHeight()) / 2, ink);
                        if (!row.value().isEmpty()) {
                            // Right-aligned, but capped by the title's drawn end: a chapter that started
                            // where the title has not finished would write through it, and the two long
                            // together are exactly when that happens.
                            int noteRoom = Math.max(0,
                                    unpinBox.x() - 6 - (textX + measure.width(title) + 6));
                            if (noteRoom > 0) {
                                String note = row.value();
                                int noteWidth = r.textWidth(note);
                                int noteX = Math.max(textX + measure.width(title) + 6,
                                        unpinBox.x() - 6 - noteWidth);
                                r.text(Measure.truncate(note, Math.max(0, unpinBox.x() - 6 - noteX),
                                                measure),
                                        noteX, box.y() + (box.height() - r.lineHeight()) / 2,
                                        ArmatureTheme.faint());
                            }
                        }
                        // The header's own pin star: filled, because every row here is pinned, in the
                        // accent ink the header wears it in. A wash and a brighter star on hover, so the
                        // press lands where the ring says it will.
                        boolean hot = unpinBox.contains(mouseX, mouseY);
                        if (hot) {
                            r.fill(unpinBox.x(), unpinBox.y(), unpinBox.right(), unpinBox.bottom(),
                                    ArmatureTheme.rowHover());
                            book.bookState.rowTooltips.add(new QuestBookScreen.RowTooltip(
                                    new dev.ellipog.armature.client.ui.kit.Slot(row.key(),
                                            unpinBox.x(), unpinBox.y(), unpinBox.width(),
                                            unpinBox.height()),
                                    List.of(Labels.of("tenet.screen.pinned.unpin"), row.label())));
                        }
                        r.text(PINNED_STAR,
                                unpinBox.x() + (unpinBox.width() - starWidth) / 2,
                                unpinBox.y() + (unpinBox.height() - r.lineHeight()) / 2,
                                hot ? ArmatureTheme.title()
                                        : ArmatureTheme.current().colour("accent"));
                    }
                    default -> {
                    }
                }
            }
        }
        QuestBookScreen.drawBar(r, book.bookState.pinnedView.bar(), mouseX, mouseY);
    }

    /**
     * Whether one pin hides on the HUD right now: finished and collected, with the auto-hide on.
     *
     * <p>The same question the HUD asks, so a row the world hides reads as hidden here too: its title
     * draws faint, which is what tells a finished pin from one still being watched.
     */
    private boolean pinnedHidden(String id) {
        if (!dev.ellipog.tenet.client.hud.PinnedQuests.hideClaimed()) {
            return false;
        }
        java.util.UUID self = book.bookRewards.selfId();
        return self != null && ClientQuestCache.stateOf(id) == dev.ellipog.tenet.progress.QuestState.COMPLETED
                && !ClientQuestCache.canClaimFor(self, id);
    }

    /** The pin row under the pointer: its quest id, and whether the star end was hit. */
    private record PinnedHit(String questId, boolean unpin) {
    }

    /** One pin row under the pointer, or null. From the layout the drawing walked. */
    private PinnedHit pinnedRowAt(double mouseX, double mouseY) {
        if (book.bookState.pinnedLayout == null) {
            return null;
        }
        for (ToolsLayout.Action row : book.bookState.pinnedRows) {
            if (row.kind() != ToolsLayout.Action.Kind.VALUE) {
                continue;
            }
            String id = pinnedIdOf(row.key());
            if (id == null) {
                continue;
            }
            dev.ellipog.armature.client.ui.kit.Slot slot = book.bookState.pinnedLayout.slot(row.key());
            if (slot == null) {
                continue;
            }
            dev.ellipog.armature.client.ui.kit.Slot onScreen =
                    ToolsLayout.onScreen(book.bookState.pinnedView.viewport(), slot);
            // The same mapping the drawing uses, so the row a press lands on is the row drawn there
            // and not the one at the same offset in a list that has scrolled.
            BookGeometry.Rect box = BookGeometry.Rect.at(onScreen.x(), onScreen.y(),
                    onScreen.width(), onScreen.height());
            if (!box.contains(mouseX, mouseY)) {
                continue;
            }
            // The same square the drawing centers its star in: one derivation for both halves.
            return new PinnedHit(id, pinnedUnpinBox(box).contains(mouseX, mouseY));
        }
        return null;
    }

    /** Whether one of the pinned panel's own drawn targets is under it. */
    boolean onPinnedTarget(double mouseX, double mouseY) {
        for (QuestBookScreen.EditTarget target : book.bookState.pinnedTargets) {
            if (target.box().contains(mouseX, mouseY)) {
                return true;
            }
        }
        return pinnedRowAt(mouseX, mouseY) != null;
    }

    /** What a press on one of them does: out, off the list, or over to the quest. */
    void pressPinnedPanel(double mouseX, double mouseY) {
        for (QuestBookScreen.EditTarget target : book.bookState.pinnedTargets) {
            if (target.box().contains(mouseX, mouseY)) {
                switch (target.action()) {
                    case PINNED_CLOSE -> book.closeOverlay();
                    default -> {
                    }
                }
                return;
            }
        }
        PinnedHit hit = pinnedRowAt(mouseX, mouseY);
        if (hit == null) {
            return;
        }
        if (hit.unpin()) {
            dev.ellipog.tenet.client.hud.PinnedQuests.unpin(hit.questId());
            book.rebuildWidgets();
            return;
        }
        // Open it, then bring the canvas to it: the dependency row's own order, because the camera's
        // target is computed from the view as it stands and opening a column does not move the canvas.
        book.navigateToQuest(hit.questId());
        book.bookAssets.revealNode(hit.questId());
    }

    // A slider belongs on the fields where a value is worth seeing move -- an angle, an alpha, a scale -- and
    // the quest settings page already has one. It is **not** a widget: that page draws a track and hit-tests the
    // drag itself, committing on release (`draggingSlider`), and `ArmatureSlider` is the appearance panel's own
    // single control with no release hook at all -- so wiring it here would have written an op per pixel of
    // drag. Porting the drawn slider is the next piece of this, and until then every number is a scrub, which
    // is a control that works.

    /**
     * The element panel as a rail: its chrome, its preview, its rows, then the shared bar.
     *
     * <p>The chrome names the element — type and name, the way the form's old title row did — so the
     * scroll carries the sections without repeating the title inside them. The preview strip under it
     * shows the thing itself at a glance: a swatch of what it looks like, and one line of what it is
     * (id, draw order, and whether it is a draft or gated). The footer the chrome reserves holds the
     * element's own actions, built beside the rows.
     */
    void drawElementPanel(GuiRenderer r, int mouseX, int mouseY) {
        if (book.bookCanvas.elementLayout == null || book.bookCanvas.elementPanelId == null) {
            return;
        }
        CanvasElement element = book.elementNow(book.bookCanvas.elementPanelId);
        if (element == null) {
            // The subject went away under an open picker too: its anchor rows address nothing, so the
            // picker goes with the panel rather than floating over whatever is there instead.
            book.closeColourPopover();
            return;
        }
        // And it belongs to the chip it was opened from: a row scrolled out of this list would leave the
        // picker anchored to a chip that is no longer drawn. The dock runs the same check over its own
        // list; an anchor that belongs there is none of this panel's business.
        if (book.bookState.colourPopover.isOpen() && book.bookState.popoverChip != null
                && book.bookState.popoverChip.view() == book.bookCanvas.elementView
                && !book.chipOnScreen(book.bookCanvas.elementView)) {
            book.closeColourPopover();
        }
        book.bookCanvas.elementTargets.clear();
        // The panel's own surface, title and close chip -- the same chrome the pack's panel and the table
        // browser wear. The first version of this drew its rows on bare canvas: no background, no name and no
        // way out but Escape, which read as a list floating over the graph rather than as a panel.
        book.bookRewards.drawModalCardChrome(r, elementTitle(element), mouseX, mouseY,
                QuestBookScreen.EditAction.ELEMENT_CLOSE,
                book.bookCanvas.elementTargets);
        drawElementPreview(r, element, book.overlayBodyRect());
        ToolsPanel.drawRows(r, book.overlayBodyRect(), book.bookCanvas.elementView.viewport(),
                book.bookCanvas.elementLayout, book.bookCanvas.elementRows,
                new ToolsPanel.State(book.questEditor.editedTheme(), book.editedBackground(),
                        book.questEditor.currentPalette()), mouseX, mouseY,
                InspectLayout.Mode.STACKED);
        // The row under the pointer, like the dock's: the sentences live with the rows that name them
        // (`ElementPanelLayout.help`), and the box is collected for the tooltip layer so the body's own
        // clip cannot cut it off at the list's edge.
        ToolsLayout.Hovered hovered = ToolsLayout.helpAt(book.bookCanvas.elementRows, book.bookCanvas.elementLayout,
                book.bookCanvas.elementView.viewport(), mouseX, mouseY, this::elementHelp);
        if (hovered != null && book.bookCanvas.elementLayout.slot(hovered.key()) != null) {
            book.bookState.rowTooltips.add(new QuestBookScreen.RowTooltip(
                    ToolsLayout.onScreen(book.bookCanvas.elementView.viewport(),
                            book.bookCanvas.elementLayout.slot(hovered.key())),
                    List.of(Labels.of(hovered.help()))));
        }
        QuestBookScreen.drawBar(r, book.bookCanvas.elementView.bar(), mouseX, mouseY);
    }

    /** The card's title: what the element is, and what it calls itself. */
    private static String elementTitle(CanvasElement element) {
        JsonObject tree = CanvasElement.asJson(element);
        String id = tree.has("id") && tree.get("id").isJsonPrimitive()
                ? tree.get("id").getAsString() : "";
        // The canvas menu's nouns ("Add box here"), not the file's: the header read "rect" and "text" in
        // lower case, the format's spelling, beside a menu that had called the same things a box and text.
        String kind = switch (element) {
            case CanvasElement.Image ignored -> "Image";
            case CanvasElement.Rect ignored -> "Box";
            case CanvasElement.Text ignored -> "Text";
            case CanvasElement.Line ignored -> "Line";
            case CanvasElement.Unknown ignored -> element.type();
        };
        return kind + " \u2014 " + ChapterPanelLayout.elementName(tree, id);
    }

    /** The help one of the panel's rows offers, or null for a row that says it itself. */
    private String elementHelp(String key) {
        String[] field = ChapterPanelLayout.elementFieldOf(key);
        return field == null ? null
                : dev.ellipog.tenet.client.dev.ElementPanelLayout.help(field[1]);
    }

    /**
     * What the element looks like and what it is, in the strip above the form.
     *
     * <p>Drawn, like every other preview in this editor, from the drafted element — so a colour or a
     * word shows here in the frame it is changed. A picture shows its file's own thumbnail when it has
     * one (a sprite shows the empty recess: the atlas is not catalogued on this side, so there is no
     * picture to offer); a box its fill edged with its border; words their own text; a line its own
     * thickness. Beside it, one line: the id, the draw order, and whether it is a draft or waiting on
     * a quest.
     */
    private void drawElementPreview(GuiRenderer r, CanvasElement element, BookGeometry.Rect body) {
        int box = BookRewards.ELEMENT_PREVIEW_HEIGHT - 10;
        int x = body.x() + 6;
        int y = body.y() + 5;
        Measure measure = book.textMeasure(r);
        switch (element) {
            case CanvasElement.Image image -> {
                JsonObject tree = CanvasElement.asJson(element);
                JsonElement file = tree.has("image") && tree.get("image").isJsonObject()
                        ? tree.getAsJsonObject("image").get("texture") : null;
                String texture = file != null && file.isJsonPrimitive() ? file.getAsString() : "";
                ToolsPanel.drawTextureThumb(r, new Slot("elementPreview", x, y, box, box), texture,
                        null);
            }
            case CanvasElement.Rect rect -> {
                r.fill(x, y, x + box, y + box, ArmatureTheme.recessed());
                // The toolkit's own outline for the border, with the fill inset inside it: four hand-rolled
                // fills is how one edge comes out wrong, and this is the call that exists so none are.
                ArmatureTheme.outline(r, x, y, box, box, rect.borderColor());
                r.fill(x + 1, y + 1, x + box - 1, y + box - 1, rect.fillColor());
            }
            case CanvasElement.Text text -> {
                r.fill(x, y, x + box, y + box, ArmatureTheme.recessed());
                // A sample in the text's own ink rather than its words: the words are the header's, and
                // in a box this small they came out as "T..." -- a clipped word saying nothing.
                String sample = "Aa";
                r.text(sample, x + (box - r.textWidth(sample)) / 2, y + (box - r.lineHeight()) / 2,
                        text.color());
            }
            case CanvasElement.Line line -> {
                r.fill(x, y, x + box, y + box, ArmatureTheme.recessed());
                int thick = Math.max(1, Math.min(box / 2, line.width()));
                r.fill(x + 2, y + (box - thick) / 2, x + box - 2, y + (box + thick) / 2,
                        line.color());
            }
            case CanvasElement.Unknown ignored -> {
                r.fill(x, y, x + box, y + box, ArmatureTheme.recessed());
                r.text("?", x + (box - r.textWidth("?")) / 2, y + (box - r.lineHeight()) / 2,
                        ArmatureTheme.faint());
            }
        }
        StringBuilder meta = new StringBuilder(element.id());
        meta.append(" \u00b7 order ").append(element.order());
        if (element.dev()) {
            meta.append(" \u00b7 ").append(Labels.of("tenet.dev.element.badge_draft"));
        }
        element.requires().ifPresent(
                quest -> meta.append(" \u00b7 ").append(Labels.of("tenet.dev.element.badge_after", quest)));
        r.text(Measure.truncate(meta.toString(), Math.max(0, body.right() - x - box - 10), measure),
                x + box + 6, y + (box - r.lineHeight()) / 2, ArmatureTheme.faint());
    }

    /** Whether one of the element panel's own drawn targets -- or one of its colour chips -- is under it. */
    boolean onElementTarget(double mouseX, double mouseY) {
        for (QuestBookScreen.EditTarget target : book.bookCanvas.elementTargets) {
            if (target.box().contains(mouseX, mouseY)) {
                return true;
            }
        }
        return elementChipAt(mouseX, mouseY) != null;
    }

    /** What a press on one of them does. */
    void pressElementPanel(double mouseX, double mouseY) {
        for (QuestBookScreen.EditTarget target : book.bookCanvas.elementTargets) {
            if (target.box().contains(mouseX, mouseY)) {
                switch (target.action()) {
                    case ELEMENT_CLOSE -> book.closeOverlay();
                    default -> {
                    }
                }
                return;
            }
        }
        // A colour chip is drawn by the panel and registers nothing, so its press is read from the same
        // rectangle the drawing used -- the rule every drawn control in this screen follows.
        ToolsLayout.Action chip = elementChipAt(mouseX, mouseY);
        if (chip != null) {
            openElementColourPopover(chip, book.bookCanvas.elementView, InspectLayout.Mode.STACKED,
                    elementChipRect(chip));
        }
    }

    /** The colour chip row under the pointer, or null. */
    private ToolsLayout.Action elementChipAt(double mouseX, double mouseY) {
        for (ToolsLayout.Action row : book.bookCanvas.elementRows) {
            if (!row.isChip()) {
                continue;
            }
            BookGeometry.Rect chip = elementChipRect(row);
            if (chip != null && chip.contains(mouseX, mouseY)) {
                return row;
            }
        }
        return null;
    }

    /** One row's chip rectangle, from the same layout the drawing walks and the band its rows are in. */
    private BookGeometry.Rect elementChipRect(ToolsLayout.Action row) {
        if (book.bookCanvas.elementLayout == null || row == null) {
            return null;
        }
        dev.ellipog.armature.client.ui.kit.Slot slot = book.bookCanvas.elementLayout.slot(row.key());
        if (slot == null) {
            return null;
        }
        return ToolsLayout.chipOf(ToolsLayout.onScreen(book.bookCanvas.elementView.viewport(), slot),
                dev.ellipog.armature.client.ui.inspect.InspectLayout.Mode.STACKED);
    }

    /**
     * The colour popover, choosing a colour for one element's field.
     *
     * <p>The same popover the theme editor opens, with the same hex fields, swatches and preview -- pointed at
     * a field in a file rather than at a token in a theme. Both write through their own door and the popover
     * does not know which: it is handed a colour to start from and two callbacks.
     *
     * <p><b>The row, the list it was pressed in and that list's composition are all arguments</b>, because
     * the same element row can be drawn twice at once -- the element panel's copy and the drawer's Chapter tab
     * -- and the picker has to know which of them it is anchored to a frame later. See {@link PopoverChip}.
     *
     * @param row    the chip's own row: the element and the field it names are what is being edited
     * @param view   the list the press landed in
     * @param mode   how that list composed its rows, for the anchor's arithmetic
     * @param anchor the chip's rectangle, as the press tested it
     */
    void openElementColourPopover(ToolsLayout.Action row,
                                          dev.ellipog.armature.client.ui.kit.ScrollView view,
                                          dev.ellipog.armature.client.ui.inspect.InspectLayout.Mode mode,
                                          BookGeometry.Rect anchor) {
        String[] named = row == null ? null : ChapterPanelLayout.elementFieldOf(row.key());
        if (anchor == null || named == null) {
            return;
        }
        String element = named[0];
        String field = named[1];
        book.closeColourPopover();
        CanvasElement now = book.elementNow(element);
        // **The effective colour, and the same one the chip beside it is drawn with.** A field the file does
        // not carry is the codec's default -- an untinted picture's tint is white -- so seeding the picker with
        // transparent (`Argb.NONE`) for an absent tint would have opened it on a colour the picture is not
        // drawn with, and the first commit would have written that disagreement into the file. One reader for
        // both, so the chip and the picker cannot show two colours for one field.
        String held = now == null ? ""
                : dev.ellipog.tenet.client.dev.ElementPanelLayout.effectiveValueOf(
                        CanvasElement.asJson(now), field);
        int argb = dev.ellipog.tenet.quest.Argb.parseHex(held).orElse(dev.ellipog.tenet.quest.Argb.NONE);
        book.bookState.popoverChapter = QuestBookScreen.effectiveChapter();
        book.bookState.popoverChip = new QuestBookScreen.PopoverChip(row, view, mode);
        book.bookState.colourPopover.open(anchor, book.geometry().canvas(), field, argb,
                List.of(dev.ellipog.tenet.quest.Argb.WHITE, dev.ellipog.tenet.quest.Argb.NONE,
                        book.questEditor.editedTheme().colour("panel"),
                                book.questEditor.editedTheme().colour("accent")),
                value -> book.questEditor.draftElementField(element, field,
                        new JsonPrimitive(dev.ellipog.tenet.quest.Argb.toHex(value))),
                value -> {
                    JsonElement written = new JsonPrimitive(dev.ellipog.tenet.quest.Argb.toHex(value));
                    book.questEditor.draftElementField(element, field, written);
                    book.send(new EditorOp.SetElement(element, field, written));
                    // **Rebuilt, because this chip carries its own colour.** A theme token's chip re-resolves
                    // its colour from the theme every frame, so the Book tab's swatch follows a drag with no
                    // help; an element's chip draws the string its *row* was built with, and a row is not
                    // rebuilt by the draft. Without this the canvas showed the new colour and the chip beside
                    // it went on showing the old hex -- a picture and its own control disagreeing.
                    //
                    // On the commit rather than on the preview: a rebuild per drag frame would replace the
                    // fields the popover is being dragged over, and the canvas already previews the colour.
                    book.rebuildWidgets();
                });
        book.buildPopoverWidgets();
        book.filePopoverArrival();
        book.refreshOverlayMute();
    }

    // ------------------------------------------------------------------
    // Pinning to the HUD
    // ------------------------------------------------------------------

    /** "(3 quests, 1 dependent)", counted the same way the sidebar's delete note is. */
    static String questDeleteNote(List<String> targets) {
        Set<String> ids = new HashSet<>(targets);
        int dependents = 0;
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            if (ids.contains(entry.id())) {
                continue;
            }
            for (String dependency : QuestBookScreen.dependenciesOf(entry)) {
                if (ids.contains(dependency)) {
                    dependents++;
                    break;
                }
            }
        }
        return BookMenu.note(targets.size(), "quest", dependents, "dependent");
    }

    /**
     * Makes a tree's id unique against what this chapter has, and reads it back as an element.
     *
     * <p>Null when the tree does not decode, which is the honest refusal: an element this build cannot read
     * is not one it can draw optimistically, so it goes to the server and appears when the tree does.
     */
    CanvasElement placeElement(com.google.gson.JsonObject tree) {
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (CanvasElement element : elementsNow()) {
            taken.add(element.id());
        }
        String wanted = tree.has("id") && tree.get("id").isJsonPrimitive()
                ? tree.get("id").getAsString() : "element";
        tree.addProperty("id", dev.ellipog.tenet.client.dev.ElementDefaults.uniqueId(wanted, taken));
        return CanvasElement.fromJson(tree).orElse(null);
    }

    /**
     * Where a new element goes in the chapter's list: the end.
     *
     * <p>The end rather than the front, because the list is drawn in the order it is written when two elements
     * share an {@code order} — so a decoration added last draws over one added first, which is what "I have
     * just put this here" means. An author who wants it underneath writes an {@code order}.
     *
     * <p>Counted through {@link #elementsNow}, so two elements added in a row get successive positions rather
     * than both landing on the tree's length — which is the same reason the whole feature reads that view.
     */
    int elementInsertIndex() {
        return elementsNow().size();
    }

    /**
     * The chapter's canvas elements as this client currently believes them to be, in draw order.
     *
     * <h2>The one read the whole feature goes through</h2>
     *
     * <p>Three layers, in this order: the <b>tree</b> — what the server last sent; the <b>shape draft</b>,
     * which adds an element that has been asked for and drops one that has been asked away; and the
     * <b>field draft</b>, which amends each element's own JSON with the values the author has given it. The
     * result is sorted by the model's own {@link CanvasElement#inDrawOrder}, so the preview and the real list
     * cannot disagree about what is on top.
     *
     * <p><b>Everything reads this and nothing reads the tree directly:</b> the stamp, the boxes, the gates,
     * the grips, the hit tests and the panel's rows. That is what makes an edit appear in the frame it is
     * made — and it is the whole of "no rubberband", because there is no second path that could still be
     * showing the old value.
     */
    List<CanvasElement> elementsNow() {
        String chapter = QuestBookScreen.effectiveChapter();
        if (chapter == null) {
            return List.of();
        }
        List<CanvasElement> shaped = BookScreenState.elementDraft.apply(chapter, ClientQuestCache.elements(chapter),
                Util.getMillis());
        List<CanvasElement> out = new ArrayList<>(shaped.size());
        for (CanvasElement element : shaped) {
            out.add(BookPins.overlaidElement(chapter, element));
        }
        return CanvasElement.inDrawOrder(out);
    }

    /**
     * The chapter's links as this client believes them to be: the tree, the shape draft, and the
     * pending field values, in that order.
     *
     * <p><b>Everything reads this and nothing reads the tree directly:</b> the stamp, the boxes, the
     * hit tests and the panel's rows. That is what makes an edit appear in the frame it is made --
     * and it is the whole of "no rubberband", because there is no second path that could still be
     * showing the old value. The element form's own note says the same for decorations, and for the
     * same reason.
     */
    List<QuestLink> linksNow() {
        String chapter = QuestBookScreen.effectiveChapter();
        if (chapter == null) {
            return List.of();
        }
        List<QuestLink> shaped = BookScreenState.linkDraft.apply(chapter, ClientQuestCache.links(chapter),
                Util.getMillis());
        List<QuestLink> out = new ArrayList<>(shaped.size());
        for (QuestLink link : shaped) {
            out.add(BookPins.overlaidLink(chapter, link));
        }
        return List.copyOf(out);
    }

    /**
     * The link with this id as this client believes it to be, or null.
     *
     * <p>Not the cached slot's link: that one carries a gesture's live preview, so a gesture that started
     * from it would compound its own last frame. This is the settled belief — the tree plus the drafts — which
     * is what a new gesture takes hold of and what a commit is diffed against.
     */
    QuestLink linkNow(String id) {
        if (id == null) {
            return null;
        }
        for (QuestLink link : linksNow()) {
            if (link.id().equals(id)) {
                return link;
            }
        }
        return null;
    }

    /**
     * Removes one element from the chapter's file.
     *
     * <p>And lets go of it, because a selection that outlives its subject is a ring around nothing — the same
     * reason a deleted quest clears {@code selectedQuest}.
     *
     * <p>The removal is <b>drafted</b> before it is sent, so the element and its ring leave the canvas on the
     * press rather than a round trip later, and its pending field values are forgotten with it: a value for an
     * element that no longer exists would be applied to whatever the next element with that id turns out to be.
     */
    void deleteElement(String id) {
        BookScreenState.elementDraft.remove(QuestBookScreen.effectiveChapter(), id, Util.getMillis());
        BookScreenState.fieldDraft.forgetOwner(QuestBookScreen.effectiveChapter(),
                QuestBookScreen.ELEMENT_OWNER_PREFIX + id);
        book.send(new EditorOp.RemoveElement(id));
        if (id.equals(BookScreenState.selectedElement)) {
            BookScreenState.selectedElement = null;
        }
    }

    /**
     * Writes one pending value for a link, which is what makes the edit visible before the tree arrives.
     *
     * <p>Before the send, not after, and unconditional: the draft is the <i>preview</i>, so a value the author
     * asked for shows even if the request could not go out — and the backstop in {@code FieldDraft} is what
     * stops a value the server never accepted from being believed forever. See {@code draftElementField} for
     * the same argument on a decoration.
     */
    void draftLinkField(String link, String field, JsonElement value) {
        BookScreenState.fieldDraft.set(QuestBookScreen.effectiveChapter(),
                QuestBookScreen.LINK_OWNER_PREFIX + link, field, value,
                ClientQuestCache.treeRevision(), Util.getMillis());
    }

    /**
     * Opens the panel for one element — what a click on the canvas does.
     *
     * <p>Nothing happens for an id this client cannot see, which is the honest answer for a press that
     * arrived about an element the tree has since dropped: a panel about nothing would draw its heading and
     * no fields, and every press in it would go nowhere.
     */
    void openElementPanel(String id) {
        if (id == null || book.elementNow(id) == null) {
            return;
        }
        book.bookCanvas.elementPanelId = id;
        BookScreenState.selectedElement = id;
        book.applyColumns(PanelStack.afterOpen(book.columns(), PanelKind.ELEMENT));
        book.rebuildWidgets();
    }

    // ------------------------------------------------------------------
    // The pinned list
    // ------------------------------------------------------------------

    /** The empty list's one row. */
    static final String PINNED_EMPTY = "pinned:empty";
}
