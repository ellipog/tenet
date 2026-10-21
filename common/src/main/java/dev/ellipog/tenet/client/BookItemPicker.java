package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tenet.editor.EditorOp;
import dev.ellipog.tenet.quest.EditorField;
import dev.ellipog.tenet.quest.EditorSources;
import dev.ellipog.tenet.quest.EditorSpecs;
import dev.ellipog.tenet.client.dev.ItemPicker;
import dev.ellipog.tenet.client.dev.ItemPickerLayout;
import dev.ellipog.tenet.client.dev.Labels;
import dev.ellipog.tenet.client.dev.QuestPanelLayout;
import dev.ellipog.tenet.client.dev.SearchCatalogue;
import dev.ellipog.tenet.client.dev.TexturePicker;
import dev.ellipog.tenet.client.dev.ToolsPanel;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The quest book's item picker: the registry's items, ranked for a query, and the card that chooses
 * one for a quest's field, a chapter's icon or the book's.
 *
 * <p>Moved out of {@link QuestBookScreen} as it stood, with the state only it reads; the screen
 * keeps the state it shares and reaches this through its {@code itemPicker} field.
 */
final class BookItemPicker {

    private final QuestBookScreen book;

    BookItemPicker(QuestBookScreen book) {
        this.book = book;
    }

    /** How many rows a search field shows before anything is typed. */
    private static final int FIRST_ROWS = 10;

    // ------------------------------------------------------------------
    // The item picker
    // ------------------------------------------------------------------

    /**
     * Opens the picker on one field: the registry's items and what the player carries, under a search
     * box.
     *
     * <p>The current value is read from the replica rather than from the press's {@code EditTarget},
     * because the header icon's target carries no value (it opens from a mark with nothing under it) --
     * and the clear row's whole job is to say what there is to clear.
     */
    /**
     * Opens the picker on a field whose ids are a list rather than an item stack: a dimension, a biome, a
     * statistic, an advancement.
     *
     * <p>The same card and the same rows as the item picker -- what changes is the catalogue and where the
     * "current" row's idea of known comes from. There is no inventory group and nothing to clear: a
     * dimension has no NBT and no "carried" to offer.
     *
     * @param observeType the observation task's own mode, taken from the entry by the caller: the target
     *                    field lists blocks for a block observation and entity tags for an entity one, and
     *                    the list has to be gathered from the same reading the press was dispatched on
     */
    void openSearchPicker(QuestBookScreen.EditTarget target, EditorField field, String observeType) {
        book.closeInlineEditor();
        book.pickingEntryType = null;
        book.settingsOpen = false;
        book.pickerFromSettings = false;
        book.forgetPickSubject();
        book.pickTarget = QuestBookScreen.PickTarget.QUEST;
        book.draggingSlider = null;
        book.settingsDraft.clear();
        book.pickingItemPath = target.path();
        JsonObject quest = book.replicaQuest();
        JsonElement current = quest == null ? null : QuestPanelLayout.get(quest, target.path());
        book.pickingItemCurrent = current != null && current.isJsonPrimitive() ? current.getAsString() : "";
        book.pickingItemClearPath = null;
        book.pickerSource = field.source();
        book.pickerObserveType = observeType;
        book.pickerEntries = SearchCatalogue.list(field.source(), observeType);
        book.pickerInventory = List.of();
        book.pickerMatches = List.of();
        book.pickerRows = List.of();
        book.pickerFrame = null;
        book.pickerQuery = "";
        // A picker opens on an empty box. The box keeps its text across rebuilds -- that is what makes it
        // usable while typing -- but a query from the last field would filter this field's list, and the
        // first rows are what say what this one can hold.
        book.itemSearch = null;
        book.pickerSelected = -1;
        book.pickerBody.setScrollY(0);
        // A search pick is never an icon pick: no tabs, whatever the last pick was.
        book.pickerKind = QuestBookScreen.PickerKind.ITEM;
        placeArmedPick();
        book.rebuildWidgets();
    }

    /**
     * Whether the armed pick is a rail of its own rather than a page of the panel that opened it.
     *
     * <h2>One question with three readers</h2>
     *
     * <p>The panel's drawing (it must not draw the list as well), its footer label (Back leaves a page; Done
     * ends the panel) and the press and wheel dispatch (which rail a press belongs to). Three readers, one
     * answer, because the fault they share is one fault: a pick that is in two places at once.
     *
     * <p>It is spelled as a question about <b>the child slot holding a picker</b> rather than about the mode,
     * which is what it used to be: with one presentation the only way a pick is not a rail is when the rules
     * filed it as column 1 — a pick opened from the dock, with no panel for it to belong to. That one is
     * drawn as the panel it is, and this answers false for it.
     */
    boolean pickerAsColumn() {
        return book.overlay2 == PanelKind.PICKER;
    }

    /**
     * Puts an armed pick where the rules say it belongs.
     *
     * <h2>Why an armed pick is a rail at all</h2>
     *
     * <p>Because the arrangement's whole shape is an author working on a quest <b>with the list it opened
     * beside it</b>: the quest stays readable while the field is set, rather than the list taking the panel's
     * body and the fields going with it. A pick that is already a rail stays one — a second pick replaces
     * the child rather than growing a fourth rail — and a pick with no panel under it becomes column 1.
     */
    void placeArmedPick() {
        book.applyColumns(PanelStack.afterOpen(book.columns(), PanelKind.PICKER));
    }

    /**
     * Leaves the picker's card: the list closes and what it was opened over comes back.
     *
     * <p>Two lists arrive here — the item catalogue and a table's list of rewards — so the type page is
     * asked first: its own close knows the difference between a page of the quest card and a card a table
     * opened, and `closeItemPicker` knows it for the item picker's.
     */
    void closePickerOverlay() {
        if (book.typePageOpen()) {
            book.leaveTypePage();
            return;
        }
        book.closeItemPicker();
        book.rebuildWidgets();
    }

    /** A press on a file row: a texture sets the icon, the clear row removes it. */
    void pressFileRow(int index) {
        if (index < 0 || index >= book.textureRows.size()) {
            return;
        }
        ItemPickerLayout.Row row = book.textureRows.get(index);
        if (!ItemPickerLayout.pickable(row)) {
            return;
        }
        if (row.kind() == ItemPickerLayout.Kind.CLEAR) {
            commitPicker(null, 1);
            return;
        }
        commitFileText(row.id());
    }

    /**
     * A file id as an icon: committed when the pack holds it, refused with the reason when not.
     *
     * <p>The canvas picker's rule, for icons: a background must draw, and so must a node — a
     * texture nothing holds draws nothing on both, so the list stays open with the refusal
     * beside it rather than closing over the reason. The row stays, so the spelling can be
     * fixed rather than retyped.
     */
    private void commitFileText(String typed) {
        String raw = typed == null ? "" : typed.trim();
        if (raw.isEmpty()) {
            book.status(Labels.of("tenet.dev.texture.hint"), true);
            return;
        }
        if (book.texturePicker.textureCatalogue().stream().noneMatch(entry -> entry.id().equalsIgnoreCase(raw))) {
            book.status("No texture at " + raw, true);
            return;
        }
        commitIconObject(iconObjectForKind(QuestBookScreen.PickerKind.FILE, raw));
    }

    /**
     * The sprite tab's Enter: the box's own text becomes the icon.
     *
     * <p>Shape-checked before anything is sent: an unparseable id is a file the loader refuses,
     * and the box keeps the text so the spelling can be fixed. A parseable id nothing holds
     * commits anyway — the preview already shows the missing mark it will draw, which is the
     * consent a deliberate choice needs.
     */
    private void commitSpriteText(String typed) {
        String raw = typed == null ? "" : typed.trim();
        if (raw.isEmpty()) {
            book.status(Labels.of("tenet.dev.picker.sprite_hint"), true);
            return;
        }
        if (net.minecraft.resources.Identifier.tryParse(raw) == null) {
            book.status(Labels.of("tenet.dev.picker.sprite_bad"), true);
            return;
        }
        JsonObject icon = new JsonObject();
        icon.addProperty("sprite", raw);
        commitIconObject(icon);
    }

    /**
     * One icon object written to whatever the pick is for: the quest's field, the chapter's,
     * the group's, or the book's.
     *
     * <p>Whole objects, because the arms are exclusive and choosing one is what clears the
     * others: a leaf write would leave the old arm beside the new one, and the codec would
     * silently draw only the winner. The server clears siblings too, so an old client writing
     * leaves cannot corrupt a file either — but the draft here is what the preview follows
     * before the server answers, so this write is already whole.
     */
    private void commitIconObject(JsonObject icon) {
        if (book.pickTarget != null && book.pickTarget != QuestBookScreen.PickTarget.QUEST) {
            commitDockIcon(icon);
            return;
        }
        String quest = book.editTarget();
        String path = book.pickingItemPath;
        String clearPath = book.pickingItemClearPath;
        book.closeItemPicker();
        if (!book.mayEditNow() || quest == null || path == null) {
            book.rebuildWidgets();
            return;
        }
        if (icon == null) {
            if (clearPath == null) {
                book.status("tenet.status.that_field_cannot_be_cleared", true);
                book.rebuildWidgets();
                return;
            }
            book.sendField(quest, clearPath, null);
            book.status("tenet.status.cleared", false);
            book.rebuildWidgets();
            return;
        }
        String objectPath = null;
        if ("icon.item".equals(path)) {
            objectPath = "icon";
        }
        else if (("tasks".equals(memberOf(path)) || "rewards".equals(memberOf(path)))
                && path.endsWith(".icon")) {
            objectPath = path;
        }
        if (objectPath == null) {
            // Not an icon path with an icon kind: the tabs that lead here are hidden for such
            // picks, so this is unreachable — refused rather than written somewhere guessed.
            book.status("tenet.status.that_field_cannot_be_cleared", true);
            book.rebuildWidgets();
            return;
        }
        book.sendField(quest, objectPath, icon);
        book.status("Set to " + describeIcon(icon), false);
        book.rebuildWidgets();
    }

    /**
     * The dock's icon whole: the book's, the group's, or the chapter's file.
     *
     * <p>The item picker's own shape for these targets (see {@code commitDockPicker}), with the
     * object already built: the arms differ only in which key the object carries, and the files
     * they land in are the same three.
     */
    private void commitDockIcon(JsonObject icon) {
        String word = icon == null ? "" : describeIcon(icon);
        if (book.pickTarget == QuestBookScreen.PickTarget.BOOK) {
            book.closeItemPicker();
            if (!book.mayEditNow()) {
                book.rebuildWidgets();
                return;
            }
            book.bookInput.sendBookField("bookIcon", icon);
            book.status(icon == null ? "tenet.status.cleared" : "Set to " + word, false);
            book.rebuildWidgets();
            return;
        }
        boolean group = book.pickTarget == QuestBookScreen.PickTarget.GROUP;
        book.closeItemPicker();
        if (!book.mayEditNow()) {
            book.rebuildWidgets();
            return;
        }
        if (icon == null) {
            if (group) {
                book.send(new EditorOp.SetGroup("icon", null));
            }
            else {
                book.sendChapterField("icon", null);
            }
            book.status("tenet.status.cleared", false);
            book.rebuildWidgets();
            return;
        }
        if (group) {
            book.send(new EditorOp.SetGroup("icon", icon));
        }
        else {
            book.sendChapterField("icon", icon);
        }
        book.status("Set to " + word, false);
        book.rebuildWidgets();
    }

    /**
     * An icon object in one line, for the status: whichever arm it carries.
     *
     * <p>Pure, so the picker tests pin it: every commit path says the same sentence for the
     * same picture, and a whole object never reaches a status line as JSON.
     */
    static String describeIcon(JsonObject icon) {
        if (icon == null) {
            return "";
        }
        for (String arm : new String[] {"item", "texture", "sprite", "entity"}) {
            if (icon.has(arm) && icon.get(arm).isJsonPrimitive()) {
                return icon.get(arm).getAsString();
            }
        }
        return icon.toString();
    }

    /**
     * The whole icon object one tab's commit writes, or null for clear.
     *
     * <p>Pure, for the same reason as {@code describeIcon}: the four tabs' payloads are pinned
     * without a screen. An item pick keeps its components and is built by its own branch (a
     * sword is an id <i>and</i> its data); a picture is one key, always.
     */
    static JsonObject iconObjectForKind(QuestBookScreen.PickerKind kind, String id) {
        if (id == null) {
            return null;
        }
        JsonObject icon = new JsonObject();
        switch (kind) {
            case FILE -> icon.addProperty("texture", id);
            case SPRITE -> icon.addProperty("sprite", id);
            case ENTITY -> icon.addProperty("entity", id);
            case ITEM -> icon.addProperty("item", id);
        }
        return icon;
    }

    /** A press on a picker row: an item sets the field, the clear row removes it, a heading nothing. */
    void pressPickerRow(int index) {
        if (index < 0 || index >= book.pickerRows.size()) {
            return;
        }
        ItemPickerLayout.Row row = book.pickerRows.get(index);
        if (!ItemPickerLayout.pickable(row)) {
            return;
        }
        if (row.kind() == ItemPickerLayout.Kind.CLEAR) {
            commitPicker(null, 1);
            return;
        }
        if (row.kind() == ItemPickerLayout.Kind.MISSING && row.id().equals(book.pickingItemCurrent)) {
            // The field's own missing value, pressed: it is already what the field says, so this is
            // "keep it" -- the picker closes and no edit is spent saying nothing.
            book.closeItemPicker();
            book.rebuildWidgets();
            return;
        }
        // The row's own count, so a carried stack of 128 becomes 128 rather than one: this is the row
        // the number was printed from, and a caller that had to count it again would be a second answer.
        commitPicker(row.id(), row.count());
    }

    /**
     * Enter, which is two rules in one key.
     *
     * <p>A whole id typed into the box is the answer outright -- that is what makes the box a field and
     * not only a filter. Otherwise the selected row commits, and when there is no row to commit the
     * screen says so rather than setting the field to whatever happened to sort first.
     */
    void commitFromPicker() {
        String query = book.itemSearch == null ? "" : book.itemSearch.value();
        if (book.pickerTabsVisible() && book.pickerKind == QuestBookScreen.PickerKind.SPRITE) {
            // No rows to walk: the box's own text is the answer, shape-checked by the commit.
            commitSpriteText(query);
            return;
        }
        if (book.pickerTabsVisible() && book.pickerKind == QuestBookScreen.PickerKind.FILE) {
            // The keyboard's row first, then a typed id: the same rule the item tab reads by,
            // on this tab's own rows rather than the item list's.
            if (book.textureSelected >= 0 && book.textureSelected < book.textureRows.size()) {
                ItemPickerLayout.Row fileRow = book.textureRows.get(book.textureSelected);
                if (ItemPickerLayout.pickable(fileRow)) {
                    if (fileRow.kind() == ItemPickerLayout.Kind.CLEAR) {
                        commitPicker(null, 1);
                    }
                    else {
                        commitFileText(fileRow.id());
                    }
                    return;
                }
            }
            commitFileText(query);
            return;
        }
        String exact = ItemPicker.exactId(query, book.pickerMatches);
        if (exact != null) {
            // A whole id typed is one of the thing, the way a catalogue row is: there is no stack behind
            // a name somebody typed.
            commitPicker(exact, 1);
            return;
        }
        ItemPickerLayout.Row row = book.pickerSelected >= 0 && book.pickerSelected < book.pickerRows.size()
                ? book.pickerRows.get(book.pickerSelected) : null;
        if (row != null && (row.kind() == ItemPickerLayout.Kind.ITEM
                || row.kind() == ItemPickerLayout.Kind.MISSING)) {
            commitPicker(row.id(), row.count());
            return;
        }
        if (row != null && row.kind() == ItemPickerLayout.Kind.CLEAR) {
            commitPicker(null, 1);
            return;
        }
        book.status(query.isBlank() ? "Type to search, or pick something you carry"
                : "No item matches \"" + query + "\"", true);
    }

    /**
     * One edit: the field becomes this id, or the clear path is removed when there is one.
     *
     * <p>A clear with nothing legal to clear is refused before it reaches the wire -- the row is not
     * drawn for such a field, and this is the second gate in case anything ever draws one anyway.
     */
    void commitPicker(String id, int count) {
        if (book.pickTarget != null && book.pickTarget != QuestBookScreen.PickTarget.QUEST) {
            commitDockPicker(id);
            return;
        }
        // A pick made from the table editor: the item goes into the table rather than into a quest
        // field, and the item's own data goes with it -- the same "the sword I am holding" rule the
        // card's item fields have. The count too, when the entry being made is new: see `itemEntry`.
        if (book.tablePickPath != null) {
            commitTablePick(id, id == null ? "" : pickedDataOf(id), count);
            return;
        }
        if (book.pickerKind == QuestBookScreen.PickerKind.ENTITY && book.isIconPick()) {
            // An entity for a picture, from a row or a typed id alike: the whole object, never a
            // bare id — a string where the codec reads an object is a file that will not load.
            // Null is clear, through the same clear path the rows use.
            commitIconObject(id == null ? null : iconObjectForKind(QuestBookScreen.PickerKind.ENTITY, id));
            return;
        }
        String quest = book.editTarget();
        String path = book.pickingItemPath;
        String clearPath = book.pickingItemClearPath;
        String data = id == null ? "" : pickedDataOf(id);
        book.closeItemPicker();
        if (!book.mayEditNow() || quest == null || path == null) {
            book.rebuildWidgets();
            return;
        }
        if (id == null) {
            if (clearPath == null) {
                book.status("tenet.status.that_field_cannot_be_cleared", true);
                book.rebuildWidgets();
                return;
            }
            book.sendField(quest, clearPath, null);
            book.status("tenet.status.cleared", false);
            book.rebuildWidgets();
            return;
        }
        // A pick is id **and** custom data, written as one edit of the object that holds both: a
        // task's or a reward's entry, or the quest's icon object. Two ops would be two saves, and a
        // save between them is a field that briefly holds the new item with the old data.
        JsonElement components = data.isEmpty()
                ? null : com.google.gson.JsonParser.parseString(data);
        if (("tasks".equals(memberOf(path)) || "rewards".equals(memberOf(path)))
                && path.endsWith(".item")) {
            // The object that holds item and components together: the entry itself, or -- for a
            // condition's item field -- the condition, which is the same shape one level down. Written
            // as one op either way, for the reason above: two ops would be two saves, and a save between
            // them is a field holding the new item with the old data.
            String objectPath = path.contains(".conditions.")
                    ? path.substring(0, path.length() - ".item".length())
                    : memberOf(path) + "." + indexOf(path);
            JsonElement found = QuestPanelLayout.get(book.replicaQuest(), objectPath);
            JsonObject rebuilt = found != null && found.isJsonObject()
                    ? found.getAsJsonObject().deepCopy() : new JsonObject();
            rebuilt.addProperty("item", id);
            if (components == null) {
                rebuilt.remove("components");
            }
            else {
                rebuilt.add("components", components);
            }
            book.sendField(quest, objectPath, rebuilt);
        }
        else if ("icon.item".equals(path)) {
            // The item arm, merged over what is there but with the other arms dropped: a pick replaces
            // whatever the icon wore — a texture, sprite or entity choice does not linger beside the
            // new item, because the codec reads the item arm first and the validator would report the
            // leftover as unknown. The count is kept, because the picker chooses the item, not how many.
            JsonObject icon = new JsonObject();
            JsonElement existing = QuestPanelLayout.get(book.replicaQuest(), "icon");
            if (existing != null && existing.isJsonObject()) {
                icon = existing.getAsJsonObject().deepCopy();
            }
            icon.addProperty("item", id);
            icon.remove("texture");
            icon.remove("sprite");
            icon.remove("entity");
            if (components == null) {
                icon.remove("components");
            }
            else {
                icon.add("components", components);
            }
            book.sendField(quest, "icon", icon);
        }
        else if (path.endsWith(".icon")) {
            // A task's or a reward's picture override: the whole icon object in one op, like the
            // quest's own above. A pick replaces whatever arm the override wore, and the count is
            // always one — an override is a picture, not a stack.
            JsonObject icon = new JsonObject();
            icon.addProperty("item", id);
            if (components != null) {
                icon.add("components", components);
            }
            book.sendField(quest, path, icon);
        }
        else {
            book.sendField(quest, path, new JsonPrimitive(id));
        }
        book.status(data.isEmpty() ? "Set to " + id : "Set to " + id + " with its data", false);
        book.rebuildWidgets();
    }

    /**
     * The Chapter tab's pick: the chapter's icon becomes this id, or the icon object is removed.
     *
     * <p>The same shape as the quest icon's branch above -- the whole {@code icon} object is written in
     * one op, because item and components are one thing and two ops would leave a save between them
     * holding the new item with the old data -- but the op is {@code SetChapter} or {@code SetGroup},
     * and the object is built fresh rather than merged with what is there. Fresh is the honest shape for
     * an icon: the picker's choice is the item and its data, and carrying anything over from the old one
     * would be an edit that quietly kept half of a value the author just replaced.
     *
     * <p>The clear case sends null, which the model reads as "remove the field": an icon that is absent
     * is the default -- paper for a chapter, the first chapter's for a group -- not an empty item.
     */
    private void commitDockPicker(String id) {
        if (book.pickTarget == QuestBookScreen.PickTarget.BOOK) {
            // The book's icon is a plain id, not an item reference: there is no components member to
            // carry, so the id is the whole value and null clears it.
            book.closeItemPicker();
            if (!book.mayEditNow()) {
                book.rebuildWidgets();
                return;
            }
            book.bookInput.sendBookField("bookIcon", id == null ? null : new JsonPrimitive(id));
            book.status(id == null ? "tenet.status.cleared" : "Set to " + id, false);
            book.rebuildWidgets();
            return;
        }
        boolean group = book.pickTarget == QuestBookScreen.PickTarget.GROUP;
        String data = id == null ? "" : pickedDataOf(id);
        book.closeItemPicker();
        if (!book.mayEditNow()) {
            book.rebuildWidgets();
            return;
        }
        if (id == null) {
            // The whole object, not its `item` member: the icon union reads an arm by its key, so an
            // emptied member would be a file that will not load.
            if (group) {
                book.send(new EditorOp.SetGroup("icon", null));
            }
            else {
                book.sendChapterField("icon", null);
            }
            book.status("tenet.status.cleared", false);
            book.rebuildWidgets();
            return;
        }
        JsonElement components = data.isEmpty()
                ? null : com.google.gson.JsonParser.parseString(data);
        JsonObject icon = new JsonObject();
        icon.addProperty("item", id);
        if (components != null) {
            icon.add("components", components);
        }
        if (group) {
            book.send(new EditorOp.SetGroup("icon", icon));
        }
        else {
            book.sendChapterField("icon", icon);
        }
        book.status(data.isEmpty() ? "Set to " + id : "Set to " + id + " with its data", false);
        book.rebuildWidgets();
    }

    /** The member a dotted field path starts with, and the index it names -- for entry rebuilds. */
    private static String memberOf(String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? path : path.substring(0, dot);
    }

    private static int indexOf(String path) {
        String[] parts = path.split("\\.");
        if (parts.length < 2) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[1]);
        }
        catch (NumberFormatException notAnEntry) {
            return -1;
        }
    }

    /**
     * The picker's body: the search box above (a widget, placed by {@code buildQuestEditorWidgets}),
     * then the rows.
     *
     * <p>Every row is drawn and hit-tested from {@link ItemPickerLayout}'s own rectangles, and the
     * list the press walks is the list this drew -- stored, not recomputed, so a press cannot land on
     * a row one revision away from the one under the pointer.
     */
    void drawItemPicker(GuiRenderer r, Viewport body, int mouseX, int mouseY) {
        BookGeometry.Rect bodyRect = BookGeometry.Rect.at(body.originX(), body.originY(),
                body.viewWidth(), body.viewHeight());
        boolean tabs = book.pickerTabsVisible();
        if (tabs) {
            // The strip's own rows: under the search box (which filters, or on Sprites is, the
            // tab's content) and above the list, so the frame, the rows, the scroll and the bar
            // all follow without a second inset anywhere.
            int tabBottom = book.overlayBody().originY() + ItemPickerLayout.SEARCH_HEIGHT + 2
                    + QuestBookScreen.PICKER_TAB_STRIP + 1;
            bodyRect = BookGeometry.Rect.at(bodyRect.x(), tabBottom, bodyRect.width(),
                    Math.max(0, bodyRect.y() + bodyRect.height() - tabBottom));
        }
        if (tabs && (book.pickerKind == QuestBookScreen.PickerKind.FILE || book.pickerKind == QuestBookScreen.PickerKind.SPRITE)) {
            drawPictureTab(r, bodyRect, mouseX, mouseY);
            return;
        }
        book.pickerFrame = ItemPickerLayout.Frame.of(bodyRect);
        String query = book.itemSearch == null ? "" : book.itemSearch.value();
        if (!query.equals(book.pickerQuery)) {
            // A keystroke is a new list: the selection goes back to its first row and the scroll to the
            // top, because an index into the old list does not name anything in this one.
            book.pickerQuery = query;
            book.pickerSelected = -1;
            book.pickerBody.setScrollY(0);
        }
        book.pickerMatches = book.pickerSource != null && query.isBlank()
                // A search field opens on the first ten of its list rather than on nothing: the list is
                // what says what the field can hold, and a picker that shows an empty box until you type
                // reads as one that found nothing. Typing ranks exactly as it does for items.
                ? book.pickerEntries.stream().limit(FIRST_ROWS).toList()
                : ItemPicker.rank(book.pickerEntries, query, ItemPicker.LIMIT);
        // A typed `#tag` is a value a RegistryRef field takes, and a `#` is not a resource location: without
        // the flag the box refuses the spelling the field's own codec reads. See ItemPicker#missingCandidate.
        boolean tagsAllowed = book.pickerSource != null
                && EditorSources.valueKind(book.pickerSource, book.pickerObserveType)
                        == EditorSources.Value.ID_OR_TAG;
        String typedCandidate = ItemPicker.missingCandidate(query, book.pickerMatches, tagsAllowed);
        // What "the current value is real" means depends on what is being listed: an item is known when
        // the build has it, and a registry value is known when the field's own grammar takes it -- which
        // is why a `#minecraft:village` is no longer reported as a missing id by the picker listing it.
        boolean currentKnown = book.pickingItemCurrent.isEmpty()
                || (book.pickerSource != null
                        ? EditorSources.accepts(book.pickerSource, book.pickerObserveType, book.pickingItemCurrent)
                        : !QuestBookScreen.itemStack(book.pickingItemCurrent).isEmpty());
        book.pickerRows = ItemPickerLayout.compose(book.pickerInventory, book.pickerMatches,
                new ItemPickerLayout.Current(book.pickingItemCurrent, currentKnown,
                        book.pickingItemClearPath != null),
                typedCandidate, query,
                // The item picker's own rule for its results (null), and the search field's own words for
                // its list -- see ItemPickerLayout#compose. The entity tab names its own heading: the
                // source's name reads as one entity, and this list is all of them.
                book.pickerKind == QuestBookScreen.PickerKind.ENTITY ? Labels.of("tenet.dev.picker.entities")
                        : book.pickerSource == null ? null : EditorSpecs.label(book.pickerSource.name()));
        QuestBookScreen.bindList(book.pickerBody, book.pickerBar, book.pickerFrame.list(), book.pickerFrame.scrollbar(),
                ItemPickerLayout.contentHeight(book.pickerRows), ItemPickerLayout.ROW_HEIGHT);
        int pickerScroll = book.pickerBody.scrollY();
        // `-1` is "nothing chosen yet", and it stays that way until the author chooses. A selection
        // invented here would be Enter's answer on a picker nobody has touched -- and the first pickable
        // row is often Clear, so that answer would quietly empty the field. `clamp` re-lands a real
        // selection when the list changes size, and answers -1 when nothing is pickable, which is the
        // same honest nothing. See `commitFromPicker` for what Enter does with it.
        if (book.pickerSelected >= 0) {
            book.pickerSelected = ItemPickerLayout.clamp(book.pickerRows, book.pickerSelected);
        }

        // **Clipped to the list, not to the body.** A row half scrolled under the search box was
        // painting into it -- and an item icon draws at its own depth, so it showed *through* the
        // box's fill. A row's edge belongs at the box's edge, which is where the list starts.
        try (GuiRenderer.Scoped clip = r.clip(book.pickerFrame.list().x(), book.pickerFrame.list().y(),
                book.pickerFrame.list().right(), book.pickerFrame.list().bottom())) {
            if (book.pickerRows.isEmpty()) {
                r.text(book.pickerSource == null
                                ? "Type to search every item, or pick something you carry."
                                : "Type to search what this build has, or an id it does not -- "
                                        + "pressing the row that appears keeps it.",
                        book.pickerFrame.list().x() + 4, book.pickerFrame.list().y() + 4, ArmatureTheme.faint());
                return;
            }
            drawPickerRows(r, book.pickerFrame, book.pickerRows, pickerScroll, book.pickerSelected, mouseX, mouseY,
                    book.pickerKind == QuestBookScreen.PickerKind.ENTITY ? this::entityIconOf : this::itemIconOf,
                    row -> row.kind() == ItemPickerLayout.Kind.MISSING);
        }
        // The list's bar, in the strip the frame kept for it: outside the clip, so its groove is the
        // full height of the list rather than the part of it that happens to be on screen.
        QuestBookScreen.drawBar(r, book.pickerBar, mouseX, mouseY);
    }

    /** The sprite tab's clear row from the last frame's own drawing — the press contract. */
    BookGeometry.Rect spriteClearRect;

    /**
     * The Sprite tab: the box's own text drawn from the atlas, live.
     *
     * <p>No list, because the atlas is not catalogued client-side: the author types an id and
     * the preview shows exactly what the node will draw, missing mark and all. That preview is
     * the validation no server check can give — a region nothing holds announces itself here
     * rather than on the canvas later. Enter commits; the verdict line says what Enter will do.
     */
    private void drawSpriteTab(GuiRenderer r, BookGeometry.Rect bodyRect, int mouseX, int mouseY) {
        String value = book.itemSearch == null ? "" : book.itemSearch.value().trim();
        spriteClearRect = null;
        int x = bodyRect.x() + 4;
        int y = bodyRect.y() + 6;
        int box = 48;
        r.fill(x, y, x + box, y + box, ArmatureTheme.recessed());
        net.minecraft.resources.Identifier id =
                value.isEmpty() ? null : net.minecraft.resources.Identifier.tryParse(value);
        if (id != null) {
            r.sprite(id, x, y, box, box, 0xFFFFFFFF);
        }
        Measure measure = book.textMeasure(r);
        int textY = y + box + 6;
        if (value.isEmpty()) {
            r.text(Measure.truncate(Labels.of("tenet.dev.picker.sprite_hint"),
                    bodyRect.width() - 8, measure), x, textY, ArmatureTheme.faint());
        }
        else if (id == null) {
            r.text(Measure.truncate(Labels.of("tenet.dev.picker.sprite_bad"),
                    bodyRect.width() - 8, measure), x, textY, ArmatureTheme.blocked());
        }
        else {
            r.text(Measure.truncate(value, bodyRect.width() - 8, measure), x, textY,
                    ArmatureTheme.body());
            r.text(Measure.truncate(Labels.of("tenet.dev.picker.sprite_live"),
                    bodyRect.width() - 8, measure), x, textY + 10, ArmatureTheme.faint());
        }
        if (book.pickingItemClearPath != null) {
            // Clear lives here too, so a sprite author never has to visit Items to remove the
            // picture: the commit below already knows the clear path.
            BookGeometry.Rect clear = BookGeometry.Rect.at(x, textY + 26,
                    Math.max(60, r.textWidth(Labels.of("tenet.dev.picker.clear")) + 12), 14);
            spriteClearRect = clear;
            if (clear.contains(mouseX, mouseY)) {
                QuestBookScreen.drawEditAffordance(r, clear, true);
            }
            r.text(Labels.of("tenet.dev.picker.clear"), clear.x() + 6, clear.y() + 3,
                    ArmatureTheme.body());
        }
    }

    /** An item row's icon: the stack the id names, or nothing when the build cannot draw it. */
    private ItemStack itemIconOf(String id) {
        return QuestBookScreen.itemStack(id);
    }

    /** An entity row's icon: its spawn egg, or nothing when it names none. */
    private ItemStack entityIconOf(String id) {
        if (id == null || id.isEmpty()) {
            return ItemStack.EMPTY;
        }
        // The vanilla convention the cache's own egg lookup reads: an entity with no egg draws
        // no icon rather than a placeholder, because the id is valid and only the picture is
        // missing — the row a type with no icon gets, by the shared row contract.
        return QuestBookScreen.itemStack(id + "_spawn_egg");
    }

    /**
     * The File tab's list and the Sprite tab's preview: the picture tabs' bodies.
     *
     * <p>Files list the pack's textures through the texture picker's own catalogue and rows --
     * the same list the canvas picker draws, so the two cannot disagree about what a pack
     * holds. Sprites have no list (the atlas is not catalogued); the tab is a preview of the
     * box's own text, which is exactly what the node will draw, missing mark and all.
     */
    private void drawPictureTab(GuiRenderer r, BookGeometry.Rect bodyRect, int mouseX, int mouseY) {
        if (book.pickerKind == QuestBookScreen.PickerKind.SPRITE) {
            drawSpriteTab(r, bodyRect, mouseX, mouseY);
            return;
        }
        String query = book.itemSearch == null ? "" : book.itemSearch.value();
        if (!query.equals(book.pickerFileQuery)) {
            // A keystroke is a new list, like the item tab's: the selection goes back to nothing
            // pickable-by-position and the scroll to the top.
            book.pickerFileQuery = query;
            book.textureSelected = -1;
            book.textureBody.setScrollY(0);
        }
        book.textureMatches = ItemPicker.rank(book.texturePicker.textureCatalogue(), query, ItemPicker.LIMIT);
        List<ItemPickerLayout.Row> rows = new ArrayList<>(
                TexturePicker.rows(book.texturePicker.textureCatalogue(), "", query));
        if (book.pickingItemClearPath != null) {
            // Clear lives on every tab, not only Items: an author on Files must not have to leave
            // to remove the picture, and the commit below already knows the clear path.
            rows.add(0, ItemPickerLayout.Row.of(ItemPickerLayout.Kind.CLEAR, "",
                    Labels.of("tenet.dev.picker.clear"),
                    Labels.of("tenet.dev.picker.clear_detail")));
        }
        book.textureRows = List.copyOf(rows);
        book.textureFrame = ItemPickerLayout.Frame.of(bodyRect);
        QuestBookScreen.bindList(book.textureBody, book.textureBar, book.textureFrame.list(), book.textureFrame.scrollbar(),
                ItemPickerLayout.contentHeight(book.textureRows), ItemPickerLayout.ROW_HEIGHT);
        if (book.textureSelected >= 0) {
            book.textureSelected = ItemPickerLayout.clamp(book.textureRows, book.textureSelected);
        }
        try (GuiRenderer.Scoped clip = r.clip(book.textureFrame.list().x(), book.textureFrame.list().y(),
                book.textureFrame.list().right(), book.textureFrame.list().bottom())) {
            if (book.textureRows.isEmpty()) {
                r.text(Labels.of("tenet.dev.texture.hint"),
                        book.textureFrame.list().x() + 4, book.textureFrame.list().y() + 4,
                        ArmatureTheme.faint());
                return;
            }
            drawPickerRows(r, book.textureFrame, book.textureRows, book.textureBody.scrollY(), book.textureSelected,
                    mouseX, mouseY, this::itemIconOf,
                    row -> row.kind() == ItemPickerLayout.Kind.MISSING);
        }
        QuestBookScreen.drawBar(r, book.textureBar, mouseX, mouseY);
    }

    /**
     * One picker's rows, drawn: the renderer the item list and the type list share.
     *
     * <h2>Why this is one method</h2>
     *
     * <p>Because the two pages are the same act with different catalogues, and the report that asked for
     * the type page at all was that they did not read alike. Two loops would be two chances for one of
     * them to grow a hover wash the other does not have — the drift this screen has already paid for
     * twice, in the picker's placeholder and in the chip and the row that both edited one token.
     *
     * <p>The two things that differ are parameters rather than branches on the caller: {@code iconOf}
     * supplies the picture (an item's stack, a type's registered icon, nothing at all), and
     * {@code placeholderOf} says which rows have no picture *because the id does not resolve* — the item
     * picker's missing rows, which get the question-mark box — as opposed to a type that simply
     * registered no icon, which gets no box and the label where the icon would be.
     *
     * <p>A row is drawn blocked, and unpressable, when it is a clear row, a missing id, or a row that
     * carries a {@link ItemPickerLayout.Row#note()} — a type a table will not take.
     */
    void drawPickerRows(GuiRenderer r, ItemPickerLayout.Frame frame,
                                List<ItemPickerLayout.Row> rows, int scroll, int selected,
                                int mouseX, int mouseY,
                                java.util.function.Function<String, ItemStack> iconOf,
                                java.util.function.Predicate<ItemPickerLayout.Row> placeholderOf) {
        Measure measure = book.textMeasure(r);
        for (int i = 0; i < rows.size(); i++) {
            BookGeometry.Rect rect = ItemPickerLayout.rowRect(rows, frame, scroll, i);
            // Off the list is not drawn -- the clip would cut a row the keyboard can still land on
            // and the wheel can bring back, and a half row at the edge is exactly what the clip is
            // for, so only the wholly-out ones are skipped.
            if (rect.bottom() <= frame.list().y() || rect.y() >= frame.list().bottom()) {
                continue;
            }
            ItemPickerLayout.Row row = rows.get(i);
            if (row.kind() == ItemPickerLayout.Kind.HEADING) {
                r.text(row.label(), rect.x() + 2, rect.y() + (rect.height() - 8) / 2,
                        ArmatureTheme.faint());
                continue;
            }
            if (i == selected) {
                r.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), ArmatureTheme.recessed());
            }
            else if (rect.contains(mouseX, mouseY)) {
                QuestBookScreen.drawEditAffordance(r, rect, true);
            }
            int textX = rect.x() + 4;
            boolean placeholder = placeholderOf.test(row);
            if (placeholder) {
                // No item behind the id: a placeholder where the icon would be, and the note that
                // says so. The id itself is the label, so it is kept and visible either way.
                QuestBookScreen.drawItemPlaceholder(r, rect.x() + 1, rect.y() + 1, Math.max(8, rect.height() - 2));
                textX = rect.x() + 20;
            }
            else if (row.kind() == ItemPickerLayout.Kind.TEXTURE) {
                // A file's thumbnail in the row's own square, at its aspect: the one thing an id
                // cannot say. The texture picker's own loop draws the same branch; the two stay
                // one branch so a file row reads alike in both pickers.
                int box = Math.max(8, rect.height() - 2);
                ToolsPanel.drawTextureThumb(r, new Slot(row.id(), rect.x() + 1, rect.y() + 1,
                        box, box), row.id(), book.textureSizes);
                textX = rect.x() + 20;
            }
            else {
                ItemStack icon = row.id().isEmpty() ? ItemStack.EMPTY : iconOf.apply(row.id());
                if (!icon.isEmpty()) {
                    r.icon(icon, rect.x() + 1, rect.y() + 1, Math.max(8, rect.height() - 2));
                    textX = rect.x() + 20;
                }
            }
            boolean blocked = placeholder || row.kind() == ItemPickerLayout.Kind.CLEAR
                    || !row.note().isEmpty();
            // The label keeps what it needs and the id takes what is left, both truncated to one
            // derivation in the layout: a narrowed panel shrinks the id first, drops it when even a
            // sliver will not fit, and only then touches the words — so the two never overlap.
            ItemPickerLayout.TextRoom room = ItemPickerLayout.textRoom(row.label(), row.secondary(),
                    rect.width() - (textX - rect.x()) - 4, r::textWidth);
            r.text(Measure.truncate(row.label(), room.label(), measure),
                    textX, rect.y() + (rect.height() - 8) / 2,
                    blocked ? ArmatureTheme.blocked() : ArmatureTheme.body());
            if (room.secondary() > 0) {
                String shown = Measure.truncate(row.secondary(), room.secondary(), measure);
                r.text(shown, rect.right() - 4 - r.textWidth(shown),
                        rect.y() + (rect.height() - 8) / 2,
                        blocked ? ArmatureTheme.blocked() : ArmatureTheme.faint());
            }
        }
    }

    /** Every item the registry holds, in registry order -- the ranking decides what a query shows. */
    static List<ItemPicker.Entry> catalogue() {
        List<ItemPicker.Entry> entries = new ArrayList<>();
        var registry = net.minecraft.core.registries.BuiltInRegistries.ITEM;
        for (net.minecraft.resources.Identifier id : registry.keySet()) {
            net.minecraft.world.item.Item item =
                    registry.get(id).map(holder -> holder.value()).orElse(net.minecraft.world.item.Items.AIR);
            if (item == net.minecraft.world.item.Items.AIR) {
                continue;
            }
            entries.add(new ItemPicker.Entry(id.toString(), dev.ellipog.tenet.quest.ItemStacks.stackOf(item).getHoverName().getString(),
                    0));
        }
        return List.copyOf(entries);
    }

    /**
     * What the player carries, one entry per item with the counts added up.
     *
     * <p>Summed rather than first-stack-wins: two stacks of sixteen is thirty-two carried, and a picker
     * that said sixteen would be answering a question about a slot. Armour and the offhand are included
     * -- they are carried, and an author picking "what am I wearing" is a real thing to do.
     */
    static List<ItemPicker.Entry> carried() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return List.of();
        }
        var inventory = player.getInventory();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, String> data = new LinkedHashMap<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                    .toString();
            counts.merge(id, stack.getCount(), Integer::sum);
            labels.putIfAbsent(id, stack.getHoverName().getString());
            // The first stack's custom data wins a dedupe: two stacks of one item are one row, and the
            // row's data is the thing a pick would copy -- showing one and copying another would make
            // the list lie about what pressing it does.
            data.putIfAbsent(id, componentsText(stack));
        }
        List<ItemPicker.Entry> out = new ArrayList<>();
        for (Map.Entry<String, Integer> each : counts.entrySet()) {
            out.add(new ItemPicker.Entry(each.getKey(), labels.get(each.getKey()), each.getValue(),
                    data.get(each.getKey())));
        }
        return List.copyOf(out);
    }

    /**
     * A stack's component patch as the JSON text an entry carries, or empty when there is nothing
     * custom about it. The same codec and the same spelling the quest file uses.
     */
    private static String componentsText(ItemStack stack) {
        var patch = stack.getComponentsPatch();
        if (patch.isEmpty()) {
            return "";
        }
        return net.minecraft.core.component.DataComponentPatch.CODEC
                .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, patch)
                .result().map(Object::toString).orElse("");
    }

    /** The picked entry's custom data, from what the player carries. Unknown ids have none. */
    private String pickedDataOf(String id) {
        for (ItemPicker.Entry entry : book.pickerInventory) {
            if (entry.id().equals(id)) {
                return entry.data();
            }
        }
        return "";
    }

    /**
     * Whether a press is the Import popover's, and answers it: one of its two rows, or the press that
     * dismisses it.
     *
     * <p>Returns true for <b>every</b> press while it is open, and that is what makes it a claim. It used to
     * be reached only from {@code pressTableEditor}, which the panel arm calls only when a drawn target is
     * under the pointer -- so a press in the popover's own padding fell past both of them, started a pan,
     * and left the popover standing over a canvas that had moved. See {@link QuestBookScreen#pressDropdown}.
     *
     * <p>Left button only, which is the meaning the arm gave these rows before the gate existed: a
     * right-press dismisses rather than imports. Everything a row <i>does</i> -- the sentinel, the payload,
     * clearing {@code tableImportOpen} -- stays {@code pressTableEditorTarget}'s, so the gate and the panel
     * cannot come to two answers about one row.
     *
     * <p>{@code tableImportBox} is the rectangle the popover drew itself with, read from the last frame, the
     * same convention every drawn target in this screen follows: the gesture belongs to the frame the player
     * was looking at.
     */
    boolean pressImportPopover(double mouseX, double mouseY, int button) {
        BookGeometry.Rect box = book.tableImportBox;
        boolean onRow = false;
        if (button == InputConstants.MOUSE_BUTTON_LEFT && box != null && box.contains(mouseX, mouseY)) {
            for (QuestBookScreen.EditTarget target : book.editTargets) {
                boolean mine = target.action() == QuestBookScreen.EditAction.TABLE_IMPORT
                        || target.action() == QuestBookScreen.EditAction.TABLE_IMPORT_CHEST;
                if (mine && target.box().contains(mouseX, mouseY)) {
                    book.pressTableEditorTarget(target, mouseX, mouseY);
                    onRow = true;
                    break;
                }
            }
        }
        if (!onRow) {
            book.tableImportOpen = false;
        }
        return true;
    }

    /**
     * Where the drawing is told the pointer is when an overlay owns it: nowhere.
     *
     * <p>Every test the drawing makes against the pointer answers false for this pair -- a rectangle's
     * {@code contains}, a widget's {@code isMouseOver}, and the {@code isHovered} a widget works out in
     * {@code render} -- and the only drawing that *places* anything at the pointer is a tooltip, which is
     * reached through one of those tests and so is never reached at all.
     */
    static final int POINTER_NONE = -1;

    /** The picker's commit when the pick belongs to a table: an entry, an item field, or the icon. */
    private void commitTablePick(String id, String data, int count) {
        String path = book.tablePickPath;
        book.tablePickPath = null;
        if (book.tableAddress == null || path == null) {
            book.rebuildWidgets();
            return;
        }
        if (id == null) {
            // Only the picture override may be cleared: it is optional, so removing the whole object
            // restores the type's own picture. Every other item field here is required by its type, and
            // clearing one would corrupt the file — the defect the picker's clear row exists to prevent.
            if (path.endsWith(".icon")) {
                JsonObject fields = new JsonObject();
                fields.add(path, com.google.gson.JsonNull.INSTANCE);
                book.bookRewards.sendTableOp(new dev.ellipog.tenet.editor.TableOp.SetFields(book.tableAddress, fields));
                book.status("tenet.status.cleared", false);
            }
            else {
                book.status("tenet.status.that_field_cannot_be_cleared", true);
            }
            book.rebuildWidgets();
            return;
        }
        JsonElement components = data == null || data.isEmpty()
                ? null : com.google.gson.JsonParser.parseString(data);
        if (path.endsWith(".icon")) {
            // A reward's picture override: the whole icon object in one op, like the card's own entry
            // above. A pick replaces whatever arm the override wore.
            JsonObject icon = new JsonObject();
            icon.addProperty("item", id);
            if (components != null) {
                icon.add("components", components);
            }
            JsonObject fields = new JsonObject();
            fields.add(path, icon);
            book.bookRewards.sendTableOp(new dev.ellipog.tenet.editor.TableOp.SetFields(book.tableAddress, fields));
            book.status("Set to " + id, false);
        }
        else if (path.isEmpty()) {
            // A new entry, from the stack that was picked: an item reward, which is what "add this item
            // to the table" means before the author tunes it. The shape comes from the one factory rather
            // than being built here -- this hand-built copy was right while the type picker's was wrong --
            // and the count goes in with it, because the picker was showing it: picking a carried stack of
            // 128 iron wrote one iron, while dragging the same stack wrote 128.
            var model = book.bookRewards.tableModel();
            int at = model.map(dev.ellipog.tenet.quest.loot.RewardTable::entryCount).orElse(0);
            BookRewards.tableDraft.forgetList(QuestBookScreen.effectiveChapter(), book.bookRewards.tableOwnerKey(), "entries");
            book.bookRewards.sendTableOp(new dev.ellipog.tenet.editor.TableOp.Insert(book.tableAddress, at,
                    QuestPanelLayout.itemEntry(id, count, components)));
            book.status("Added " + (count > 1 ? count + "x " : "") + id, false);
        }
        else {
            // An item field: the id and its data as one edit, the same rule the card's item fields have
            // -- two ops would be two saves, and a save between them is an item with the old data.
            JsonObject fields = new JsonObject();
            fields.addProperty(path, id);
            int dot = path.lastIndexOf('.');
            String sibling = (dot < 0 ? "" : path.substring(0, dot + 1)) + "components";
            if (components == null) {
                fields.add(sibling, com.google.gson.JsonNull.INSTANCE);
            }
            else {
                fields.add(sibling, components);
            }
            book.bookRewards.sendTableOp(new dev.ellipog.tenet.editor.TableOp.SetFields(book.tableAddress, fields));
            book.status("Set to " + id, false);
        }
        book.closeTablePick();
    }
}
