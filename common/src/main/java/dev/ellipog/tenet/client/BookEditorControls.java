package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ArmatureTextArea;
import dev.ellipog.armature.client.ArmatureTextField;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.inspect.InspectField;
import dev.ellipog.armature.client.ui.inspect.InspectRow;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tenet.quest.EditorField;
import dev.ellipog.tenet.quest.EditorSources;
import dev.ellipog.tenet.client.dev.InlineEdit;
import dev.ellipog.tenet.client.dev.ItemPickerLayout;
import dev.ellipog.tenet.client.dev.Labels;
import dev.ellipog.tenet.client.dev.QuestSettingsLayout;
import dev.ellipog.tenet.client.dev.QuestSettingsPanel;
import dev.ellipog.tenet.client.dev.QuestPanelLayout;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The quest book's editor controls: what the form's controls do, and the editor's presses.
 *
 * <p>Moved out of {@link QuestBookScreen} as it stood, with the state only it reads; the screen
 * keeps the state it shares and reaches this through its {@code editorControls} field.
 */
final class BookEditorControls {

    private final QuestBookScreen book;

    BookEditorControls(QuestBookScreen book) {
        this.book = book;
    }

    /**
     * What the caret follow last saw: the field it was watching, its text, and where in it the caret was.
     * Three strings and an int, and they are the whole difference between following the caret and fighting
     * the wheel -- see {@link #followCaret}.
     */
    private String followedPath;
    private String followedValue;
    private int followedCaret = -1;
    /** What the row under the pointer says for itself: a type's description, or why it cannot be taken. */
    private String typeHint;

    // ------------------------------------------------------------------
    // What the form's controls do
    // ------------------------------------------------------------------

    /**
     * A stepper's press: the number moves by one, or by ten with shift held.
     *
     * <p>One op per press, so Ctrl+Z undoes one nudge -- which is what a nudge is. The base comes from
     * the tree the card draws, which carries the draft, so spamming the button accumulates instead of
     * sending the same value from a stale copy — see {@code FieldDraft}. Clamped at zero, because
     * every number in the format counts something and a negative count is a refusal the server
     * would report as a sentence rather than as an edit.
     */
    private void nudge(QuestBookScreen.EditTarget target, int direction) {
        JsonObject quest = book.replicaQuest();
        String questId = book.editTarget();
        if (quest == null || questId == null) {
            return;
        }
        JsonElement found = QuestPanelLayout.get(quest, target.path());
        int current = found != null && found.isJsonPrimitive() && found.getAsJsonPrimitive().isNumber()
                ? found.getAsInt() : 0;
        int step = direction * (shiftDown() ? 10 : 1);
        book.sendField(questId, target.path(), new JsonPrimitive(Math.max(0, current + step)));
    }

    /**
     * A choice's press: the value becomes the next option in the field's own ring, and round again.
     *
     * <p>One op per press, and a value the ring does not hold -- an addon's, or one this build removed --
     * is genuinely not lost by the first press, which is what this method's note claimed while the code
     * wrapped such a value to the first option. See {@link QuestBookScreen#nextOption}: a value the ring does not hold
     * is left where it is, and nothing is sent.
     */
    private void cycleChoice(QuestBookScreen.EditTarget target) {
        JsonObject quest = book.replicaQuest();
        if (quest == null || book.editTarget() == null || target.index() < 0) {
            return;
        }
        JsonObject entry = BookQuestEditor.entryAt(quest, target.member(), target.index());
        if (entry == null) {
            return;
        }
        EditorField field = BookQuestEditor.fieldAt(entry, target.member(), target.index(), target.path());
        if (field == null || field.options().isEmpty()) {
            return;
        }
        String next = QuestBookScreen.nextOption(field.options(), target.value());
        if (next.equals(target.value())) {
            return;
        }
        book.sendField(book.editTarget(), target.path(), new JsonPrimitive(next));
    }

    /**
     * A search field's press: the picker opens on the field's own source.
     *
     * <p>A source with nothing to list -- disconnected, or a registry the server has not sent -- still
     * opens, on an empty list with a box that takes a typed id. Refusing to open would leave the author
     * pressing a control that does nothing at all.
     *
     * <p><b>Except when the field's value is not an id.</b> An observation task whose mode is a block
     * entity takes an NBT filter, and there is no list of those: every row the picker could offer would
     * write a value the codec refuses, and the box cannot commit the filter either, so the mode was
     * uneditable outside the file. A field whose grammar is {@code FREE} gets the text box, which is the
     * control that can hold what it takes. See {@code EditorSources.Value}.
     */
    private void openSearchPick(QuestBookScreen.EditTarget target, double mouseX, double mouseY) {
        JsonObject quest = book.replicaQuest();
        JsonObject entry = quest == null || target.index() < 0
                ? null
                : BookQuestEditor.entryAt(quest, target.member(), target.index());
        EditorField field = entry == null
                ? null
                : BookQuestEditor.fieldAt(entry, target.member(), target.index(), target.path());
        String observeType = entry == null ? "" : QuestBookScreen.rawValue(entry, "observeType");
        if (field != null && field.source() != null
                && EditorSources.valueKind(field.source(), observeType) != EditorSources.Value.FREE) {
            book.itemPicker.openSearchPicker(target, field, observeType);
            return;
        }
        openInlineEditor(target, mouseX, mouseY);
    }

    /**
     * The position button: the corner becomes where the player is standing, and the dimension becomes the
     * one they are standing in.
     *
     * <p>Both, because a corner with no dimension is a corner in whichever world the reader happens to be
     * in -- filling only the numbers would hand an author a box in the wrong place that looks right.
     *
     * <p>Two ops rather than one: an op is one field, and a composite op would be a new shape on the wire
     * for one button. So Ctrl+Z twice, said here rather than pretended otherwise.
     */
    private void useMyPosition(QuestBookScreen.EditTarget target) {
        JsonObject quest = book.replicaQuest();
        if (quest == null || book.editTarget() == null || book.minecraft().player == null) {
            return;
        }
        net.minecraft.core.BlockPos pos = book.minecraft().player.blockPosition();
        JsonArray corner = new JsonArray();
        corner.add(pos.getX());
        corner.add(pos.getY());
        corner.add(pos.getZ());
        book.sendField(book.editTarget(), target.path(), corner);

        JsonObject entry = BookQuestEditor.entryAt(quest, target.member(), target.index());
        // The object that owns this position's siblings: the entry, or -- for a condition's field -- the
        // condition itself. Read from the same nesting rule `fieldAt` uses, so a nested position resolves
        // its dimension beside itself rather than writing the task's own field.
        String container = containerOf(target.path(), target.member(), target.index());
        JsonObject owner = BookQuestEditor.entryAt(quest, target.member(), target.index());
        JsonElement nested = container.equals(target.member() + "." + target.index())
                ? null : QuestPanelLayout.get(quest, container);
        JsonObject holder = nested != null && nested.isJsonObject() ? nested.getAsJsonObject() : owner;
        boolean hasDimension = holder != null
                && (container.contains(".conditions.")
                        ? QuestPanelLayout.conditionEditorFor(holder)
                        : QuestPanelLayout.editorFor(target.member(), holder)).stream()
                        .anyMatch(field -> field.path().equals("dimension"));
        if (hasDimension && book.minecraft().level != null) {
            book.sendField(book.editTarget(), container + ".dimension",
                    new JsonPrimitive(book.minecraft().level.dimension().identifier().toString()));
        }
    }

    /**
     * The object that owns a row's field: the entry, or the condition when the path is nested.
     *
     * <p>Mirrors {@code fieldAt}'s nesting rule exactly -- one level, {@code conditions.<index>} -- so the
     * two cannot come to disagree about where a condition's fields live.
     */
    private static String containerOf(String path, String member, int index) {
        String prefix = member + "." + index + ".";
        if (path.startsWith(prefix + "conditions.")) {
            String tail = path.substring((prefix + "conditions.").length());
            int dot = tail.indexOf('.');
            String conditionIndex = dot < 0 ? tail : tail.substring(0, dot);
            return prefix + "conditions." + conditionIndex;
        }
        return member + "." + index;
    }

    /** An add row: a faint box saying what pressing it does. */
    void drawAddRow(GuiRenderer r, Slot slot, String label, QuestBookScreen.EditAction action,
                            int mouseX, int mouseY) {
        if (slot == null) {
            return;
        }
        BookGeometry.Rect box = BookGeometry.Rect.at(slot.x(), slot.y() + 2,
                Math.max(120, r.textWidth(label) + 24), Math.max(8, slot.height() - 4));
        if (action == QuestBookScreen.EditAction.ADD_TASK) {
            // The intro's anchor for its task rung: filed where the row is measured, because this
            // box is the measured one — the registered target below is the same rectangle, so the
            // spotlight and the press cannot disagree about where "+ Add task" is.
            book.introTaskBox = box;
        }
        else if (action == QuestBookScreen.EditAction.ADD_REWARD) {
            book.introRewardBox = box;
        }
        QuestBookScreen.drawEditAffordance(r, box, box.contains(mouseX, mouseY));
        r.text(label, box.x() + 6, box.y() + (box.height() - 8) / 2,
                ArmatureTheme.faint());
        book.registerInto(book.editTargets, action, null, box, box.x() + 6,
                box.y() + (box.height() - 8) / 2, "", null, -1);
    }

    /** One prerequisite: its name (a jump to that quest), a locate icon, and a cross to remove it. */
    void drawDependencyRow(GuiRenderer r, Slot slot, String name, int mouseX, int mouseY) {
        if (slot == null) {
            return;
        }
        int y = slot.y() + 2;
        int height = Math.max(8, slot.height() - 4);
        BookGeometry.Rect locate = BookGeometry.Rect.at(slot.right() - 52, y, 18, height);
        BookGeometry.Rect nameBox = BookGeometry.Rect.at(slot.x(), y,
                Math.max(60, slot.width() - 74), height);
        boolean nameHover = nameBox.contains(mouseX, mouseY);
        if (nameHover) {
            // The name is a jump now, and the wash is the affordance: an edit box would say "type
            // here", which is what the add row underneath says.
            r.fill(nameBox.x() - 2, nameBox.y(), nameBox.right(), nameBox.bottom(),
                    ArmatureTheme.rowHover());
        }
        r.text(Measure.truncate(name, nameBox.width() - 8, book.textMeasure(r)), nameBox.x() + 2,
                y + (height - 8) / 2, ArmatureTheme.body());
        book.registerInto(book.editTargets, QuestBookScreen.EditAction.NAVIGATE_DEP, name, nameBox, nameBox.x() + 2,
                y + (height - 8) / 2, "", null, -1);

        QuestBookScreen.drawLocateIcon(r, locate.x() + locate.width() / 2, y + height / 2,
                locate.contains(mouseX, mouseY) ? ArmatureTheme.body() : ArmatureTheme.faint());
        book.registerInto(book.editTargets, QuestBookScreen.EditAction.LOCATE_DEP, name, locate, locate.x(), locate.y(),
                "", null, -1);

        BookGeometry.Rect remove = BookGeometry.Rect.at(slot.right() - 30, y, 26, height);
        QuestBookScreen.drawEditAffordance(r, remove, remove.contains(mouseX, mouseY));
        r.text("\u00d7", remove.x() + (remove.width() - r.textWidth("\u00d7")) / 2,
                y + (height - 8) / 2, ArmatureTheme.body());
        book.registerInto(book.editTargets, QuestBookScreen.EditAction.REMOVE_DEP, name, remove, remove.x(),
                y + (height - 8) / 2, "", null, -1);
    }

    /** The requires section's add row: type an id, or pick the quest on the canvas. */
    void drawDependencyAddRow(GuiRenderer r, Slot slot, int mouseX, int mouseY) {
        if (slot == null) {
            return;
        }
        int y = slot.y() + 2;
        int height = Math.max(8, slot.height() - 4);
        int x = slot.x();
        BookGeometry.Rect type = BookGeometry.Rect.at(x, y,
                Math.max(80, slot.width() - 70), height);
        QuestBookScreen.drawEditAffordance(r, type, type.contains(mouseX, mouseY));
        // The prompt is a placeholder for an empty field, and the field's text would print over it --
        // so while that field is open the prompt stands down like any other value being edited.
        if (!InlineEdit.replaces(InlineEdit.DEPENDENCY_ADD, book.editingPath)) {
            r.text("+ Add by id", type.x() + 6, type.y() + (type.height() - 8) / 2,
                    ArmatureTheme.faint());
        }
        book.registerInto(book.editTargets, QuestBookScreen.EditAction.ADD_DEP, null, type, type.x() + 6,
                type.y() + (type.height() - 8) / 2, "", null, -1);

        BookGeometry.Rect pick = BookGeometry.Rect.at(type.right() + 4, y, 60, height);
        QuestBookScreen.drawEditAffordance(r, pick, pick.contains(mouseX, mouseY));
        r.text("Pick", pick.x() + 6, pick.y() + (pick.height() - 8) / 2,
                ArmatureTheme.faint());
        book.registerInto(book.editTargets, QuestBookScreen.EditAction.PICK_DEP, null, pick, pick.x() + 6,
                pick.y() + (pick.height() - 8) / 2, "", null, -1);
    }

    /**
     * The settings page: the node's shape, size, placement and rules, over the card's body.
     *
     * <p>Everything but the three text fields is drawn and hit-tested by {@code QuestSettingsPanel} and
     * {@code QuestSettingsLayout}; the fields are widgets, placed by the page's own layout through the
     * same viewport the drawing uses, which is what keeps a scrolled field beside its label.
     *
     * <p>The viewport is re-applied every frame with the column's own rectangle, and that is the fix
     * rather than tidiness: placement ran once at build time against whatever the geometry was then,
     * while the drawing runs against the geometry now — so the widgets drift off the page, some of them
     * outside it entirely. One bounds, one apply, every frame, the same rule the body uses.
     */
    void drawSettings(GuiRenderer r, ClientQuestCache.Entry entry, int mouseX, int mouseY) {
        if (book.settingsLayout == null || entry == null) {
            return;
        }
        JsonObject quest = book.replicaQuest();
        dev.ellipog.tenet.client.dev.QuestSettingsLayout.Frame frame =
                dev.ellipog.tenet.client.dev.QuestSettingsLayout.Frame.of(book.overlayBodyRect());
        book.settingsView.viewport().bounds(frame.controls().x(), frame.controls().y(),
                frame.controls().width(), frame.controls().height());
        book.settingsView.apply(book.settingsLayout, frame.controls().width());
        dev.ellipog.tenet.client.dev.QuestSettingsPanel.draw(r, frame, book.settingsLayout, book.settingsRows,
                quest, book.questEditor.settingsViewOf(entry, quest, mouseX, mouseY), book.settingsView.viewport(),
                mouseX, mouseY);
        QuestBookScreen.drawBar(r, book.settingsView.bar(), mouseX, mouseY);
    }

    /** The description the card lays out: the tree's lines, or the open editor's own text. */
    List<String> shownDescription(ClientQuestCache.Entry entry, JsonObject quest) {
        if (book.inlineArea != null && "description".equals(book.editingPath)) {
            // The editor's own text, exactly as typed -- including the blank line the caret is on at the
            // end. It is the commit that trims (see `commitInlineText`), so a press of Enter at the end
            // shows while it is being typed and is gone once the edit finishes.
            return Prose.split(book.inlineArea.value());
        }
        // The ends of the prose are not content; see `Prose`. The entry's own description is trimmed
        // where it is parsed (`ClientQuestCache`), so both readings of this file agree about the ends.
        List<String> lines = Prose.trimmed(QuestPanelLayout.strings(quest, "description"));
        if (!lines.isEmpty()) {
            return lines;
        }
        return entry.descriptionText();
    }

    /**
     * A section's empty row, said in the words the reader uses.
     *
     * <p>One method for both surfaces because they show the same row: the layout reserves it either way
     * (see {@code OverlayLayout.stack}), so the reader draws it and the editor used to leave it blank --
     * a heading, a hole, an add row. That hole is the spacing in the report.
     */
    static void drawEmptyState(GuiRenderer r, Slot slot, String text) {
        if (slot != null) {
            // Centred in the row, like the text of a real row: the row is a row's height either way, so
            // text sitting on its top edge left the rest of it looking like a gap between the words and
            // whatever comes next -- the "random space" the empty quest was reported for.
            r.text(text, slot.x(), slot.y() + (slot.height() - 8) / 2, ArmatureTheme.faint());
        }
    }

    /** The prose's rectangle, for the editor's box: the paragraphs the layout placed, counted. */
    BookGeometry.Rect descriptionBox(int paragraphs, Layout layout, Viewport body) {
        // The frame is the text area's own box: the column opened out by the area's padding, and never
        // narrower than the widest paragraph the layout placed. See `OverlayLayout.proseFrame` for both
        // halves and the play reports behind them. The slots are `scrolled`, which does not cull: the frame
        // exists at every scroll position, because the field is placed on it and the field's *drawing* is
        // what gets cut off at the body's edge -- a frame that disappeared mid-scroll took the open
        // description with it.
        if (paragraphs == 0) {
            Slot slot = scrolled(layout, body, OverlayLayout.NO_DESCRIPTION);
            return slot == null ? BookGeometry.Rect.at(body.originX(), body.originY(), body.viewWidth(), 0)
                    : rect(OverlayLayout.proseFrame(slot, slot.bottom(), slot.right(), body.viewWidth()));
        }
        Slot first = scrolled(layout, body, OverlayLayout.proseKey(0));
        Slot last = scrolled(layout, body, OverlayLayout.proseKey(paragraphs - 1));
        if (first == null || last == null) {
            return BookGeometry.Rect.at(body.originX(), body.originY(), body.viewWidth(), 0);
        }
        return rect(OverlayLayout.proseFrame(first, last.bottom(), widestRight(paragraphs, layout, body),
                body.viewWidth()));
    }

    /**
     * A slot where it is on screen <b>whether or not any of it is</b>: {@code placed} without the cull.
     *
     * <p>For the description's frame, which has to exist at every scroll position. {@code placed} culls a
     * slot that is off the view, which is right for drawing a row that is not there and wrong for the box
     * the open field is placed on: a description taller than the body lost its frame the moment the card
     * scrolled, and while a field is open the card draws no prose -- so the text did not move, it vanished,
     * until the scroll was put back exactly or the field was clicked out of. The box follows the prose now,
     * and the *drawing* is cut by the body's clip instead, so an off-edge field is cut exactly where the
     * text under it is.
     */
    private static Slot scrolled(Layout layout, Viewport body, Object key) {
        Slot slot = layout.slot(key);
        if (slot == null) {
            return null;
        }
        return slot.moved(body.screenX(slot.x()) - slot.x(), body.screenY(slot.y()) - slot.y());
    }

    /**
     * The right edge of the widest paragraph the layout placed, so the frame can contain the prose even
     * when the column it was told about and the width the layout wrapped at disagree. Both are the body's
     * width, and the one drawn around must hold the one drawn inside.
     */
    private int widestRight(int paragraphs, Layout layout, Viewport body) {
        int right = 0;
        for (int i = 0; i < paragraphs; i++) {
            Slot slot = QuestBookScreen.placed(layout, body, OverlayLayout.proseKey(i));
            if (slot != null) {
                right = Math.max(right, slot.right());
            }
        }
        return right;
    }

    private static BookGeometry.Rect rect(Slot slot) {
        return BookGeometry.Rect.at(slot.x(), slot.y(), slot.width(), slot.height());
    }

    private static int arraySize(JsonObject quest, String member) {
        return quest.has(member) && quest.get(member).isJsonArray()
                ? quest.getAsJsonArray(member).size() : 0;
    }

    static JsonObject memberEntry(JsonObject quest, String member, int index) {
        JsonElement found = QuestPanelLayout.get(quest, member + "." + index);
        return found != null && found.isJsonObject() ? found.getAsJsonObject() : null;
    }

    static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    /** A task or reward type's registered icon, or paper for one this build does not know. */
    static ItemStack typeIcon(String member, String type) {
        net.minecraft.resources.Identifier id =
                net.minecraft.resources.Identifier.tryParse(type);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        dev.ellipog.tenet.quest.ItemRef icon = "rewards".equals(member)
                ? dev.ellipog.tenet.quest.reward.RewardTypes.iconOf(id)
                : dev.ellipog.tenet.quest.task.TaskTypes.iconOf(id);
        return icon.toStack();
    }

    /**
     * The hover description of a type in the picker: what it is for, the fields it will ask for, and the
     * id a file spells.
     *
     * <p>The fields line comes from the registry rather than the table, so a type the table does not name
     * still says what it takes -- and a type whose fields change says so without the table being touched.
     *
     * <p>The <b>author's</b> description, for the picker's buttons: field names and the id are what an
     * author edits, and the picker is a tool only edit mode reaches. A player's row hover is
     * {@link QuestPanelLayout#playerTooltip}, which shares none of these lines.
     */
    private static List<Component> typeTooltip(String member, String typeId) {
        List<Component> lines = new ArrayList<>();
        for (String line : QuestPanelLayout.typeTooltip(member, typeId)) {
            lines.add(Component.literal(line));
        }
        return List.copyOf(lines);
    }

    /**
     * Why a reward type cannot be an entry in a table, or empty when it can.
     *
     * <p>Empty for a picker that is not adding to a table: a quest's own reward list may hold a choice,
     * and blocking it there would take away the one place a choice belongs. The sentence itself comes
     * from the model, which is the same one the validator refuses the entry with.
     */
    private String tableTypeRefusal(String typeId) {
        if (book.overlay != PanelKind.TABLE_EDITOR) {
            return "";
        }
        net.minecraft.resources.Identifier id =
                net.minecraft.resources.Identifier.tryParse(typeId);
        return id == null ? "" : dev.ellipog.tenet.quest.reward.TableReward.entryRefusal(id);
    }

    /** The rows the open type picker shows: the condition types when that is what is open, else the member's. */
    private List<InspectRow> pickerRows() {
        if (book.pickingConditionFor != null) {
            return QuestPanelLayout.conditionTypeRows();
        }
        // The table editor's strip says "Add a reward" while this page is open, so the page has no first
        // line of its own: a heading row would either repeat that sentence or name the table, which the
        // chrome has not asked for and the eye reads first. Its list begins at its first group heading. A
        // quest's own card keeps the line, because its chrome is the quest's identity and a page cannot
        // rename the quest it is a guest of.
        return book.overlay == PanelKind.TABLE_EDITOR
                ? QuestPanelLayout.typeRowsNamedInChrome(book.pickingEntryType)
                : QuestPanelLayout.typeRows(book.pickingEntryType);
    }

    /**
     * The type catalogue as {@link TypePicker} takes it: a type, its group, and any refusal.
     *
     * <p>Read out of the panel's own rows rather than out of the registries a second time, because those
     * rows <i>are</i> the catalogue — the grouping, the order and the fallback group for an addon's type
     * all live in {@code QuestPanelLayout}, and a second walk of the registries here is how the two would
     * come to disagree about which types exist.
     *
     * <p>A group's name is the last heading seen before the row, and a heading is not an entry.
     */
    private List<dev.ellipog.tenet.client.dev.TypePicker.Entry> typeCatalogue() {
        List<dev.ellipog.tenet.client.dev.TypePicker.Entry> entries = new ArrayList<>();
        String group = "";
        for (InspectRow row : pickerRows()) {
            if (row.kind() == InspectRow.Kind.HEADING) {
                group = row.label();
                continue;
            }
            if (row.kind() != InspectRow.Kind.ACTION
                    || !row.key().startsWith(QuestPanelLayout.TYPE_PREFIX)) {
                continue;
            }
            String typeId = row.key().substring(QuestPanelLayout.TYPE_PREFIX.length());
            entries.add(new dev.ellipog.tenet.client.dev.TypePicker.Entry(typeId, row.label(), group,
                    tableTypeRefusal(typeId)));
        }
        entries.addAll(templateCatalogue());
        return List.copyOf(entries);
    }

    /**
     * The templates half of the type catalogue: saved setups and shipped presets for the list
     * being added to, under the fresh types.
     *
     * <p>Last because the catalogue is the stable list every author learns — its groups, order
     * and fallback row for an addon's type all live in {@code QuestPanelLayout} — and the shelf
     * is personal: appending keeps the learned list in place while the templates wait at the end.
     * A query is unaffected either way, since matches rank by quality rather than by position. A
     * template for a type this build does not know carries its reason as its refusal, so the row
     * teaches what is missing instead of silently doing nothing — the picker's own contract for
     * untakeable rows.
     */
    private List<dev.ellipog.tenet.client.dev.TypePicker.Entry> templateCatalogue() {
        var kind = book.pickingConditionFor != null
                ? dev.ellipog.tenet.client.dev.ComponentTemplate.Kind.CONDITION
                : "rewards".equals(book.pickingEntryType)
                        ? dev.ellipog.tenet.client.dev.ComponentTemplate.Kind.REWARD
                        : "tasks".equals(book.pickingEntryType)
                                ? dev.ellipog.tenet.client.dev.ComponentTemplate.Kind.TASK
                                : null;
        if (kind == null) {
            return List.of();
        }
        String group = dev.ellipog.tenet.client.dev.Labels.of("tenet.dev.template.group");
        List<dev.ellipog.tenet.client.dev.TypePicker.Entry> out = new ArrayList<>();
        for (dev.ellipog.tenet.client.dev.ComponentTemplate.Template template
                : dev.ellipog.tenet.client.dev.ComponentTemplates.all()) {
            if (template.kind() != kind) {
                continue;
            }
            String note = "";
            if (!QuestBookScreen.knownTemplateType(template)) {
                note = "Needs " + template.typeId();
            }
            else if (kind == dev.ellipog.tenet.client.dev.ComponentTemplate.Kind.REWARD) {
                note = tableTypeRefusal(template.typeId());
            }
            // The row's own id stays the shelf's (it is what the press resolves), while the row
            // shows the type a file would spell -- the same spelling every sibling row carries.
            out.add(new dev.ellipog.tenet.client.dev.TypePicker.Entry(
                    QuestBookScreen.TEMPLATE_ROW_PREFIX + template.id(), template.name(), group, note,
                    template.typeId()));
        }
        return out;
    }

    /**
     * The type page's own first line.
     *
     * <p>Always the card's own, because the page is always a page of a card now: a quest card's chrome is
     * the quest's identity so the page has to name the act, and the picker card a table opens has a header
     * of its own which says what the pick is for — so this is the line under it rather than a repeat of it.
     */
    private String typePageHeading() {
        return book.pickingConditionFor != null
                ? Labels.of("tenet.dev.panel.add_condition")
                : Labels.of("tasks".equals(book.pickingEntryType)
                        ? "tenet.dev.panel.add_a_task" : "tenet.dev.panel.add_a_reward");
    }

    /**
     * The type page's band: the overlay body, which is the same rectangle the item picker's list uses.
     *
     * <p>One band for both hosts now. It used to have a second case for the table editor's list band, from
     * when `+ Reward` drew a page *inside* that panel; the button opens the picker's own card instead, like
     * the `+ Item` beside it, so the page is drawn where the card says it is.
     */
    BookGeometry.Rect typePageBand() {
        return BookGeometry.Rect.at(book.overlayBody().originX(), book.overlayBody().originY(),
                book.overlayBody().viewWidth(), book.overlayBody().viewHeight());
    }

    /**
     * The type page: one search box, the catalogue, and the scrollbar.
     *
     * <p>Drawn by the same row renderer the item picker uses — see {@link BookItemPicker#drawPickerRows} — because the
     * two pages are the same act with different catalogues, and the report that asked for this round was
     * exactly that they did not read alike.
     */
    void drawTypePicker(GuiRenderer r, BookGeometry.Rect band, int mouseX, int mouseY) {
        typeHint = null;
        book.typeFrame = ItemPickerLayout.Frame.of(band);
        String query = book.typeSearch == null ? "" : book.typeSearch.value();
        if (!query.equals(book.typeQuery)) {
            // A keystroke is a new list: the selection goes back to nothing and the scroll to the top,
            // because an index into the old list does not name anything in this one.
            book.typeQuery = query;
            book.typeSelected = -1;
            book.typeBody.setScrollY(0);
        }
        book.typeRows = dev.ellipog.tenet.client.dev.TypePicker.compose(typeCatalogue(), query,
                typePageHeading());
        QuestBookScreen.bindList(book.typeBody, book.typeBar, book.typeFrame.list(), book.typeFrame.scrollbar(),
                ItemPickerLayout.contentHeight(book.typeRows), ItemPickerLayout.ROW_HEIGHT);
        int typeScroll = book.typeBody.scrollY();
        if (book.typeSelected >= 0) {
            book.typeSelected = ItemPickerLayout.clamp(book.typeRows, book.typeSelected);
        }

        // Clipped to the list, not to the page: a row scrolled up under the search box would otherwise
        // paint into it, and an item icon draws at its own depth, so it would show *through* the box.
        try (GuiRenderer.Scoped clip = r.clip(book.typeFrame.list().x(), book.typeFrame.list().y(),
                book.typeFrame.list().right(), book.typeFrame.list().bottom())) {
            if (book.typeRows.isEmpty()) {
                r.text("Type to search what this build can add, or scroll the list.",
                        book.typeFrame.list().x() + 4, book.typeFrame.list().y() + 4, ArmatureTheme.faint());
            }
            else {
                book.itemPicker.drawPickerRows(r, book.typeFrame, book.typeRows, typeScroll, book.typeSelected, mouseX, mouseY,
                        this::pickerIcon, row -> false);
            }
        }
        // The bar, outside the clip so it is the full height of the list, and in a strip of its own so it
        // is beside the rows rather than over the ids at their right.
        QuestBookScreen.drawBar(r, book.typeBar, mouseX, mouseY);
        // The row under the pointer says what it is, and says why when it cannot be taken. The buttons
        // these rows replaced carried this as a tooltip, and a drawn row has no tooltip: the hint band is
        // where this panel already answers "what is this?", and it is the same band for both hosts.
        int over = ItemPickerLayout.rowAt(book.typeRows, book.typeFrame, typeScroll, mouseY);
        if (over >= 0 && over < book.typeRows.size()) {
            ItemPickerLayout.Row row = book.typeRows.get(over);
            if (!row.id().isEmpty()) {
                String described = pickerTooltip(row.id()).stream()
                        .map(Component::getString)
                        .filter(line -> !line.isBlank())
                        .reduce((first, next) -> first + "  \u00b7  " + next)
                        .orElse("");
                typeHint = row.note().isEmpty() || described.isEmpty()
                        ? (row.note().isEmpty() ? described : row.note())
                        : row.note() + "  \u2014  " + described;
            }
        }
        book.bookRewards.drawTableHint(r, typeHint);
    }

    /**
     * One press on the type page: the row under the pointer, or nothing.
     *
     * <p>From the last frame's own rows, so the row that answers is the row that was drawn — and a row
     * that cannot be taken says why in the hint line rather than doing nothing at all.
     *
     * @return whether the press was the page's: a row, taken or refused. A press that hit no row is not
     *     the page's, which is what lets the caller treat it as a press outside the card
     */
    boolean pressTypePicker(double mouseX, double mouseY) {
        if (book.typeFrame == null) {
            return false;
        }
        int index = ItemPickerLayout.rowAt(book.typeRows, book.typeFrame, book.typeBody.scrollY(), mouseY);
        if (index < 0) {
            return false;
        }
        ItemPickerLayout.Row row = book.typeRows.get(index);
        if (!ItemPickerLayout.pickable(row)) {
            typeHint = row.note().isEmpty() ? typeHint : row.note();
            return true;
        }
        book.typeSelected = index;
        book.questEditor.pressTypeRow(row.id());
        return true;
    }

    /** The selected row's type, for Enter: the id of the row the keyboard is on, or null. */
    String selectedType() {
        ItemPickerLayout.Row row = book.typeSelected >= 0 && book.typeSelected < book.typeRows.size()
                ? book.typeRows.get(book.typeSelected) : null;
        return row == null || !ItemPickerLayout.pickable(row) ? null : row.id();
    }

    /** A picker row's icon: the registered type's, from whichever of the three registries it came. */
    private ItemStack pickerIcon(String typeId) {
        if (typeId.startsWith(QuestBookScreen.TEMPLATE_ROW_PREFIX)) {
            var template = dev.ellipog.tenet.client.dev.ComponentTemplates.byId(
                    typeId.substring(QuestBookScreen.TEMPLATE_ROW_PREFIX.length())).orElse(null);
            return template == null ? ItemStack.EMPTY : QuestBookScreen.templateIcon(template);
        }
        net.minecraft.resources.Identifier id =
                net.minecraft.resources.Identifier.tryParse(typeId);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        return book.pickingConditionFor != null
                ? dev.ellipog.tenet.quest.condition.ConditionTypes.iconOf(id).toStack()
                : typeIcon(book.pickingEntryType, typeId);
    }

    /** A picker row's hover: the author's description, from whichever registry the picker is over. */    private List<Component> pickerTooltip(String typeId) {
        if (typeId.startsWith(QuestBookScreen.TEMPLATE_ROW_PREFIX)) {
            var template = dev.ellipog.tenet.client.dev.ComponentTemplates.byId(
                    typeId.substring(QuestBookScreen.TEMPLATE_ROW_PREFIX.length())).orElse(null);
            if (template == null) {
                return List.of();
            }
            List<Component> out = new ArrayList<>();
            out.add(Component.literal(dev.ellipog.tenet.client.dev.ComponentTemplate.blurb(
                    template.kind(), template.typeId(), template.payload())));
            out.add(Component.literal(template.typeId()));
            return List.copyOf(out);
        }
        List<String> lines = book.pickingConditionFor != null
                ? QuestPanelLayout.conditionTypeTooltip(typeId)
                : QuestPanelLayout.typeTooltip(book.pickingEntryType, typeId);
        List<Component> out = new ArrayList<>();
        for (String line : lines) {
            out.add(Component.literal(line));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------
    // The editor's presses
    // ------------------------------------------------------------------

    /**
     * What a press on a marked piece does.
     *
     * <p>The pointer comes along because a press that opens an inline editor is also the press that says
     * <i>where</i> in the value it landed; the field places its caret from it. A second click to say where
     * you meant is not the gesture anybody makes.
     */
    void pressEditTarget(QuestBookScreen.EditTarget target, double mouseX, double mouseY) {
        JsonObject quest = book.replicaQuest();
        // **The armed entry removal is consumed by this press, whatever it turns out to be.** Read into a
        // local and cleared here rather than inside the two cases, because "any other press disarms" is the
        // rule the card's own Delete follows: a cross that asked and then survived a press somewhere else
        // would remove a row on one press, and the row it named is a position in a list that may have moved.
        String armedEntry = book.entryConfirmRemove;
        book.entryConfirmRemove = null;
        switch (target.action()) {
            case FLAG -> {
                if (quest != null && book.editTarget() != null) {
                    book.sendField(book.editTarget(), target.path(),
                            new JsonPrimitive(!QuestBookScreen.flagOn(quest, target.path())));
                }
            }
            case FIELD, RAW -> openInlineEditor(target, mouseX, mouseY);
            // An item is picked, not typed: the text field is still there (it is the picker's search
            // box, and a whole id in it commits), but it is no longer the whole of how a field is set.
            // (An icon override's cell registers this same action; the picker's commit writes the
            // whole object for a path ending in ".icon".)
            case ITEM -> book.openItemPicker(target, false);
            // The table control's three presses: the card opens the browser, the chip opens the editor,
            // and the cross clears the reference. All three are the card's, so they act on the reward
            // the open quest has at that path.
            case TABLE_OPEN -> book.bookRewards.openTableBrowser(target);
            case TABLE_EDIT -> book.bookRewards.openTableEditorFor(target);
            case TABLE_CLEAR -> book.bookRewards.clearTable(target);
            case ADD_TASK -> book.questEditor.openTypePicker("tasks");
            case ADD_REWARD -> book.questEditor.openTypePicker("rewards");
            case CONDITION_ADD -> book.questEditor.openConditionTypePicker(target.path());
            case CONDITION_REMOVE -> {
                // One press, and the condition is gone from the entry -- so it asks first, like every other
                // cross in the book. See `entryConfirmRemove` for why the arm is a path and why it dies with
                // the next press.
                if (!target.path().equals(armedEntry)) {
                    book.entryConfirmRemove = target.path();
                    book.status("Press again to remove this condition", true);
                    return;
                }
                book.questEditor.removeCondition(target.path());
            }
            // The form's own controls: a nudge, a cycle, and the button that fills a box from where the
            // player stands. Each is one press and one op -- see the methods for what each one sends.
            case STEP_UP -> nudge(target, 1);
            case STEP_DOWN -> nudge(target, -1);
            case CYCLE_CHOICE -> cycleChoice(target);
            case USE_POSITION -> useMyPosition(target);
            case SEARCH -> openSearchPick(target, mouseX, mouseY);
            case TOGGLE_ENTRY -> book.questEditor.toggleFold(target.member(), target.index());
            case ADD_DEP -> openInlineEditor(new QuestBookScreen.EditTarget(QuestBookScreen.EditAction.FIELD, InlineEdit.DEPENDENCY_ADD,
                    target.box(), target.textX(), target.textY(), "", null, -1), mouseX, mouseY);
            case PICK_DEP -> book.armDependencyPick();
            case REMOVE_DEP -> {
                // The same cross, on the same edge, as the canvas menu's "Delete dependency" -- which asks
                // twice. One of the two had to be wrong; this is the one that was.
                if (!target.path().equals(armedEntry)) {
                    book.entryConfirmRemove = target.path();
                    book.status("Press again to remove the requirement \"" + target.path() + "\"", true);
                    return;
                }
                if (quest != null && book.editTarget() != null) {
                    List<String> remaining = QuestPanelLayout.strings(quest, "dependsOn").stream()
                            .filter(each -> !each.equals(target.path())).toList();
                    book.sendField(book.editTarget(), "dependsOn", QuestBookScreen.stringArray(remaining));
                }
            }
            // A prerequisite's name and its locate icon are the card's own navigation: the same two
            // the reader's rows carry, so both modes of the card teach one behaviour.
            case NAVIGATE_DEP -> book.navigateToQuest(target.path());
            case LOCATE_DEP -> book.locateOnCanvas(target.path());
            case COPY_ENTRY -> book.questEditor.pressEntry("h:" + target.member() + "." + target.index(), true);
            case SAVE_ENTRY -> saveEntryAsTemplate(target.member(), target.index());
            case SAVE_CONDITION -> saveConditionAsTemplate(target.path(), target.member(), target.index());
            case REMOVE_ENTRY -> {
                // The same rule as the condition's cross one case up, and the same arm: a task or a reward
                // row's own removal asks before it takes the entry away.
                String entry = "h:" + target.member() + "." + target.index();
                if (!entry.equals(armedEntry)) {
                    book.entryConfirmRemove = entry;
                    book.status("Press again to remove this entry", true);
                    return;
                }
                book.questEditor.pressEntry(entry, false);
            }
            case DRAG_ENTRY -> {
                // The press claims the row; whether it becomes a drag is the threshold's, further on.
                // The press's own coordinates are recorded because the threshold compares against them,
                // and this press never reached the canvas branch that usually sets them.
                book.dragRowMember = target.member();
                book.dragRowQuest = book.editTarget();
                book.dragRowFrom = target.index();
                book.dragRowLive = false;
                book.dragRowPointerY = mouseY;
                book.pressX = mouseX;
                book.pressY = mouseY;
            }
        }
    }

    /**
     * Opens the one inline editor: a field for a value, a text area for prose or raw JSON.
     *
     * <p>{@code mouseX}/{@code mouseY} are the press that asked for it, and they are replayed into the
     * widget it just made -- see the end of this method.
     */
    void openInlineEditor(QuestBookScreen.EditTarget target, double mouseX, double mouseY) {
        book.closeInlineEditor();
        book.editingPath = target.path();
        boolean area = "description".equals(target.path()) || target.action() == QuestBookScreen.EditAction.RAW;
        BookGeometry.Rect box = inlineBox(target, area);
        if (area) {
            String initial = target.action() == QuestBookScreen.EditAction.RAW
                    ? rawText(target.path()) : target.value();
            book.inlineArea = new ArmatureTextArea(box.x(), box.y(), box.width(), box.height(), initial);
            book.inlineArea.onSubmit(text -> commitInlineText(target.path(), text));
            // No box: the ink and the pitch of the prose this replaces, so clicking in changes nothing
            // but the caret. The pitch is the overlay's own -- the layout below was built with
            // `OverlayLayout.LINE_HEIGHT` a line and `PARAGRAPH_GAP` between paragraphs, and the
            // drawing here has to land on the same lines or the paragraph moves on every click.
            book.inlineArea.colours(InlineEdit.ink(target.path()), InlineEdit.NO_BOX, InlineEdit.NO_BOX);
            book.inlineArea.advance(OverlayLayout.LINE_HEIGHT, OverlayLayout.PARAGRAPH_GAP);
            // A widget, not a renderable widget: it is drawn by `drawOpenEditor`, clipped to the body, and
            // drawn *once*. The renderable list is painted before the card and before the blur, clipped to
            // the book band rather than to the card -- so with the field's box following the prose past the
            // body's edge, that copy showed through as a blurred ghost over the world: *"when it flows out or
            // i scroll too far it goes into the background, yes blurred, but still there"*. As a child it
            // still takes the keyboard and the pointer; as a renderable it was a second copy nothing could
            // clip, which is also where the frozen slab two rounds ago came from.
            book.addWidget(book.inlineArea);
            book.setFocused(book.inlineArea);
        }
        else {
            book.inlineField = new ArmatureTextField(box.x(), box.y(), box.width(), box.height(),
                    target.value());
            book.inlineField.onSubmit(text -> commitInlineText(target.path(), text));
            // The same rule as the area, one line high: the reader's ink for this value, and no box.
            book.inlineField.colours(InlineEdit.ink(target.path()), InlineEdit.NO_BOX, InlineEdit.NO_BOX);
            // A child, not a renderable widget: see the note on the area above.
            book.addWidget(book.inlineField);
            book.setFocused(book.inlineField);
        }

        // **The click that opened the field is the click that places the caret.** Without this the value
        // was focused with the caret at its end, so the first thing the author saw after clicking a word
        // was the text scrolled to its other end -- and the click they had already made did not count.
        //
        // Replayed through the widget's own click handling rather than a second placement rule here: a
        // click on a character puts the caret at it, a click in the frame's padding goes to the nearest
        // end of the line, and a second click in the same place still marks the word under it -- three
        // behaviours, the widget's, from the press it was always meant to be. The target is inside the
        // widget's box in every case: the description's box *is* its frame, and a row's field is its
        // mark opened out by its padding.
        if (book.inlineArea != null) {
            book.inlineArea.mouseClicked(clickAsLeft(mouseX, mouseY), false);
        }
        else if (book.inlineField != null) {
            book.inlineField.mouseClicked(clickAsLeft(mouseX, mouseY), false);
        }
    }

    /**
     * The rectangle the open inline editor's widget occupies.
     *
     * <p><b>The area is its target's box, exactly.</b> The description's frame is built as the text
     * area's own rectangle -- the column opened out by {@link OverlayLayout#PROSE_PAD}, which is the text
     * area's own padding, and never narrower than the widest line the layout placed -- so the widget is
     * put at that rectangle and nothing else. That is what makes it impossible for the frame to be
     * narrower than the text inside it: there is one rectangle, and the border is drawn around the same
     * one the text is drawn in. The padding, the wrap width and the frame all come from that single
     * number ({@link ArmatureTextArea#PAD}); a second reading of any of them is what the two reports
     * behind this were.
     *
     * <p>The one-line widget is placed from the target's <b>text origin</b> instead: its box hugs the
     * whole piece and the value is inset inside it (four pixels into a row's number, past the icon for an
     * item, two for the title), so a field sized to the box drew its value to the left of where the value
     * was and every click nudged the text sideways. The widget draws its text at its own left edge plus
     * {@link ArmatureTextField#PAD}, and centres it in its own height, so the box that puts the text back
     * on the value's own origin is derived from that origin rather than from the box around it.
     *
     * <p>One function for the opening and for every frame after it, because the card scrolls under an
     * open editor and the description's box grows with the text: the widget has to be put back on its
     * target each frame, and a second copy of this arithmetic is how a field ends up pixels off the value
     * it is editing.
     */
    private BookGeometry.Rect inlineBox(QuestBookScreen.EditTarget target, boolean area) {
        if (area) {
            return target.box();
        }
        // A saved template's name: the hit box is the pencil chip, which is no width to type in,
        // so the editor opens over the name's own origin at a width fit for words, bounded by the
        // list it belongs to. The text lands back on the name it replaces.
        if (target.path() != null && target.path().startsWith(QuestBookScreen.TEMPLATE_ROW_PREFIX)) {
            BookGeometry.Rect region = inlineRegion(target.path());
            int height = Math.max(12, target.box().height() + 4);
            int width = Math.min(220, Math.max(60, region.width() - 8));
            return BookGeometry.Rect.at(target.textX() - ArmatureTextField.PAD,
                    target.textY() - (height - 8) / 2, width, height);
        }
        int height = Math.max(12, target.box().height() + 4);
        return BookGeometry.Rect.at(target.textX() - ArmatureTextField.PAD,
                target.textY() - (height - 8) / 2,
                target.box().width() + ArmatureTextField.PAD * 2,
                height);
    }

    /**
     * Scrolls the card so the open field's caret is in view -- <b>and only when the caret moved since the
     * last check</b>. That gate is the whole mechanism, not a detail: a tick with no caret change (a wheel
     * notch, a redraw, a blink) scrolls nothing, so the wheel keeps whatever position the author put it at,
     * while typing, an arrow, a click and opening a field all move the caret and are followed.
     *
     * <p>It follows the <b>caret</b>, not the field's box. The description's box is as tall as the whole
     * text, so a box-follow has nothing to aim at; the caret's own line is both smaller and what the author
     * is looking at. And a field that has been stood down -- scrolled out of the body -- is not followed at
     * all: {@code repositionInlineEditor} froze its rectangle when it hid it, and chasing that stale
     * rectangle was the other half of the scroll fight.
     */
    void followCaret() {
        net.minecraft.client.gui.components.AbstractWidget editor =
                book.inlineArea != null ? book.inlineArea : book.inlineField;
        if (editor == null || !editor.visible) {
            followedPath = null;
            return;
        }
        if (InlineEdit.inHeader(book.editingPath)) {
            // A header piece is not in the scroll: the title line does not move when the body scrolls, so
            // there is nothing to follow -- and following it would scroll the body *up* to chase a target
            // that sits above the body's top edge, which is a card that jumps when a title is clicked.
            followedPath = null;
            return;
        }
        String value = book.inlineArea != null ? book.inlineArea.value() : book.inlineField.value();
        int caret = book.inlineArea != null ? book.inlineArea.caret() : book.inlineField.caret();
        if (book.editingPath != null && book.editingPath.equals(followedPath)
                && caret == followedCaret && value.equals(followedValue)) {
            return;
        }
        followedPath = book.editingPath;
        followedValue = value;
        followedCaret = caret;

        Viewport body = book.overlayBody();
        int margin = 8;
        int top = editor.getY() + (book.inlineArea != null ? book.inlineArea.caretTop() : book.inlineField.caretTop());
        if (top < body.originY() + margin) {
            book.overlayView.scrollBy(top - body.originY() - margin);
        }
        else if (top + OverlayLayout.LINE_HEIGHT > body.viewBottom() - margin) {
            book.overlayView.scrollBy(top + OverlayLayout.LINE_HEIGHT - body.viewBottom() + margin);
        }
    }

    /**
     * The open editor's frame on screen, or null: the target it was opened over, which is the rectangle
     * the field is drawn in and so the rectangle a press has to land in to count as being on the field.
     *
     * <p>The frame is the field's own box for the description, and the value's mark for a one-line field
     * (whose widget is that mark opened out by its padding) -- so a press anywhere the author can see the
     * field is a press on it.
     */
    private BookGeometry.Rect openEditorFrame() {
        if (book.editingPath == null) {
            return null;
        }
        for (QuestBookScreen.EditTarget target : book.editTargets) {
            if (book.editingPath.equals(target.path())) {
                return target.box();
            }
        }
        return null;
    }

    /** Whether a press is on the field that is already open, rather than away from it. */
    boolean onOpenEditor(double mouseX, double mouseY) {
        BookGeometry.Rect frame = openEditorFrame();
        return frame != null && frame.contains(mouseX, mouseY);
    }

    /**
     * A press on the open field: the caret goes where the press landed, and nothing else happens. The
     * widget owns where that is -- a character boundary, the nearest end of the line, a word on the
     * second press of a double click -- so this hands the press to it rather than placing the caret from
     * a second rule here.
     */
    boolean moveOpenCaret(double mouseX, double mouseY) {
        if (book.inlineArea != null) {
            book.inlineArea.mouseClicked(clickAsLeft(mouseX, mouseY), false);
            return true;
        }
        if (book.inlineField != null) {
            book.inlineField.mouseClicked(clickAsLeft(mouseX, mouseY), false);
            return true;
        }
        return false;
    }

    /**
     * A press replayed as a left click, for the inline editors: the opening press is definitionally
     * left (it focuses for editing), so the replay carries the left button with no modifiers rather
     * than whatever the press that opened the editor happened to be.
     */
    private static net.minecraft.client.input.MouseButtonEvent clickAsLeft(double mouseX, double mouseY) {
        return new net.minecraft.client.input.MouseButtonEvent(mouseX, mouseY,
                new net.minecraft.client.input.MouseButtonInfo(
                        com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0));
    }

    /**
     * Whether either Shift is down right now, for the three call sites with no input event to ask:
     * two stepper presses and a weight nudge, all reading live state. Everywhere else the event's
     * own {@code hasShiftDown} is used instead, which reads the press's modifiers rather than the
     * moment.
     */
    static boolean shiftDown() {
        return InputConstants.isKeyDown(InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
    }

    /** Live Alt, for the call sites with no event: the same reason as {@link #shiftDown}. */
    static boolean altDown() {
        return InputConstants.isKeyDown(InputConstants.KEY_LALT)
                || InputConstants.isKeyDown(InputConstants.KEY_RALT);
    }

    /**
     * A press moved into the picker's shifted frame, keeping its button and modifiers: the picker
     * slides, so the coordinates it is hit-tested against are not the screen's.
     */
    static net.minecraft.client.input.MouseButtonEvent shiftedClick(double x, double y,
            net.minecraft.client.input.MouseButtonEvent event) {
        return new net.minecraft.client.input.MouseButtonEvent(x, y, event.buttonInfo());
    }

    /**
     * The region an inline editor lives in: the card's header band for the pieces the header carries,
     * the scrolling body for everything else.
     *
     * <p>Two regions, because the two halves of the card are two. The body scrolls under a fixed header,
     * and a field is kept alive and clipped against the one its target is drawn in. Asking the body about
     * a header target is what made the title and subtitle fields unopenable: their boxes sit above the
     * body's top edge, so from the frame the field opened it was judged off screen, stood down and
     * hidden -- clicking a title mark made the title disappear and nothing editable appear. See
     * {@link InlineEdit#inHeader}.
     */
    private BookGeometry.Rect inlineRegion(String path) {
        if (InlineEdit.inHeader(path)) {
            return BookGeometry.Rect.at(book.overlayLeft(), book.overlayTop(), book.overlayWidth(), QuestBookScreen.BODY_TOP);
        }
        // A saved template's name belongs to the Assets page's own list: the field is drawn over
        // the row and clipped to the band the rows are drawn in, scrolled rows and all.
        if (path != null && path.startsWith(QuestBookScreen.TEMPLATE_ROW_PREFIX) && book.assetsFrame != null) {
            BookGeometry.Rect band = book.assetsFrame.rows();
            return BookGeometry.Rect.at(band.x(), band.y(), band.right(), band.bottom());
        }
        // A table editor's field belongs to its row, and that is the region it is clipped to: the field's
        // box is a couple of pixels taller than the line it replaces, and those pixels are padding -- the
        // ink is centred on the line, so clipping to the row keeps every drawn pixel and stops the box
        // from reaching into the entry above it.
        BookGeometry.Rect row = tableRowRegion(path);
        if (row != null) {
            return row;
        }
        Viewport body = book.overlayBody();
        return BookGeometry.Rect.at(body.originX(), body.originY(), body.viewWidth(), body.viewHeight());
    }

    /**
     * The band the field at a table path belongs to: its row on screen, or null when the path is not a
     * table editor's.
     *
     * <p>Read from the same {@code rowRect} the drawing uses, so a field clipped to its row is clipped to
     * where that row actually is -- folds, scroll and all.
     */
    private BookGeometry.Rect tableRowRegion(String path) {
        if (book.overlay != PanelKind.TABLE_EDITOR || book.tableEditorFrame == null || path == null
                || !path.startsWith("entries.")) {
            return null;
        }
        int dot = path.indexOf('.', "entries.".length());
        String middle = path.substring("entries.".length(), dot < 0 ? path.length() : dot);
        int index;
        try {
            index = Integer.parseInt(middle.trim());
        }
        catch (NumberFormatException notARow) {
            return null;
        }
        int entries = book.bookRewards.tableModel().map(dev.ellipog.tenet.quest.loot.RewardTable::entryCount).orElse(0);
        if (index < 0 || index >= entries) {
            return null;
        }
        return dev.ellipog.tenet.client.dev.TableEditorLayout.rowRect(entries, book.tableEditorFrame,
                book.tableEditorBody.scrollY(), index, book.tableFolds());
    }

    /**
     * The open field, drawn above the card and cut off at the edges of the region it belongs to.
     *
     * <h2>The two things this does that a bare {@code inlineArea::render} did not</h2>
     *
     * <p><b>It respects being stood down.</b> {@code repositionInlineEditor} hides the field when its
     * target has been scrolled out of the body, and the widget pass honours that -- but this list is drawn
     * by hand, and a direct call to {@code render} ignores {@code visible} entirely. So a field the editor
     * had stood down was still painted, at the last rectangle it was ever given: select some text, scroll,
     * and the prose sat frozen in place while the card moved under it. That was the report (*"the text just
     * stays while the other stuff moves under it"*), and it is a bug that could only appear once a
     * description grew taller than the body -- that is when the target starts being culled mid-scroll.
     *
     * <p><b>It is clipped to the region.</b> The field is drawn outside the content's clip -- it has to be,
     * it is chrome over the card -- so with its box following the prose through a scroll, an unclipped
     * field would draw its text over the header and the footer bar as the prose passes them. Clipping is
     * what lets the box be the prose's real rectangle at every scroll position, which is what keeps the
     * text in the field on the text underneath it: one arithmetic, cut by one edge. And a header field is
     * clipped to the header band rather than to the body -- a title field cut at the body's top edge would
     * be a field nobody could see, which is the same fault the region above is about.
     */
    void drawOpenEditor(GuiRenderer r) {
        if (book.editingPath == null) {
            return;
        }
        BookGeometry.Rect region = inlineRegion(book.editingPath);
        try (GuiRenderer.Scoped clip = r.clip(region.x(), region.y(), region.right(), region.bottom())) {
            if (book.inlineArea != null && book.inlineArea.visible) {
                book.inlineArea.render(r);
            }
            if (book.inlineField != null && book.inlineField.visible) {
                book.inlineField.render(r);
            }
        }
    }

    /**
     * Moves the open editor back onto the target it replaces, and stands it down while that target is
     * not on screen at all.
     *
     * <p>The frame's own target list is the one description of where a piece is -- the same list the
     * press is matched against -- so a field that followed anything else would be a field drawn somewhere
     * a click would not land. A target that is not in the list was scrolled out of the body (or its box
     * had no size), and the widget is <b>hidden and deactivated</b> rather than left floating: it is
     * redrawn outside the body's clip, so an unclipped field is text over the header, and a focused field
     * that is not on screen must not be what the next keystroke lands on. It comes back -- value and
     * caret intact -- when its target scrolls into view.
     */
    void repositionInlineEditor() {
        if (book.editingPath == null) {
            return;
        }
        // The quest card's list, then the Assets panel's: a path lives in one or the other --
        // quest fields name quest pieces, template names name shelf rows -- so whichever list
        // holds it is where its box is read from. Without the second list a rename box would go
        // blind the frame after it opened, standing down while its row is still on screen.
        QuestBookScreen.EditTarget target = inlineTarget(book.editTargets);
        if (target == null) {
            target = inlineTarget(book.assetsTargets);
        }
        if (target == null) {
            if (book.inlineArea != null) {
                book.inlineArea.visible = false;
                book.inlineArea.active = false;
            }
            if (book.inlineField != null) {
                book.inlineField.visible = false;
                book.inlineField.active = false;
            }
            return;
        }
        {
            boolean area = book.inlineArea != null;
            BookGeometry.Rect box = inlineBox(target, area);
            // On screen is a question about the box and the region the piece belongs to, not about the
            // target existing: the box follows the prose through a scroll now, so the only thing that
            // stands the field down is the whole of it being off its view -- where typing would be
            // typing blind. The region is the header for a header piece and the body for the rest; asking
            // the body about the title is what stood that field down on the frame it opened.
            BookGeometry.Rect region = inlineRegion(book.editingPath);
            boolean onScreen = box.bottom() > region.y() && box.y() < region.bottom();
            if (area) {
                book.inlineArea.setX(box.x());
                book.inlineArea.setY(box.y());
                book.inlineArea.setWidth(box.width());
                book.inlineArea.setHeight(box.height());
                book.inlineArea.visible = onScreen;
                book.inlineArea.active = onScreen;
            }
            else {
                book.inlineField.setX(box.x());
                book.inlineField.setY(box.y());
                book.inlineField.setWidth(box.width());
                book.inlineField.setHeight(box.height());
                book.inlineField.visible = onScreen;
                book.inlineField.active = onScreen;
            }
        }
    }

    /**
     * The target the open editor replaces, by path: the first match wins, and a path the frame
     * never drew has none.
     */
    private QuestBookScreen.EditTarget inlineTarget(List<QuestBookScreen.EditTarget> targets) {
        for (QuestBookScreen.EditTarget target : targets) {
            if (book.editingPath.equals(target.path())) {
                return target;
            }
        }
        return null;
    }

    /** The compact JSON of one entry, for the raw editor. */
    private String rawText(String path) {
        JsonObject quest = book.replicaQuest();
        JsonElement found = quest == null ? null : QuestPanelLayout.get(quest, path);
        return found == null ? "{}" : found.toString();
    }

    /**
     * One typed value, as the table op that writes it.
     *
     * <p>The kind came from the control that opened the box, because the box hands back a string and the
     * file does not hold one: a count is a whole number, a weight may have a fraction, a switch is a
     * boolean, a command is a string and the raw row is whole JSON. Each branch is the format's own
     * answer for its kind, and the refusals are spoken rather than swallowed — a value silently not
     * written reads as a press that did nothing.
     */
    private void commitTableText(QuestBookScreen.TableEdit edit, String text) {
        String trimmed = text == null ? "" : text.trim();
        switch (edit.kind()) {
            case RAW -> {
                if (trimmed.isEmpty()) {
                    return;
                }
                try {
                    JsonElement parsed = com.google.gson.JsonParser.parseString(trimmed);
                    if (!parsed.isJsonObject()) {
                        book.status("An entry's raw text has to be a JSON object", true);
                        return;
                    }
                    book.bookRewards.sendTableValue(edit.path(), parsed);
                }
                catch (RuntimeException notJson) {
                    book.status("That is not JSON", true);
                }
            }
            case INT, DECIMAL -> {
                if (trimmed.isEmpty()) {
                    // An empty box removes the field, as everywhere else in this editor: for a number
                    // that means the type's own default, which is exactly what an absent field is.
                    book.bookRewards.sendTableValue(edit.path(), null);
                    return;
                }
                double value;
                try {
                    value = Double.parseDouble(trimmed);
                }
                catch (NumberFormatException notANumber) {
                    book.status("That is not a number", true);
                    return;
                }
                if (!Double.isFinite(value)) {
                    book.status("That is not a number", true);
                    return;
                }
                if (edit.kind() == dev.ellipog.tenet.client.dev.TableRowFields.Kind.DECIMAL) {
                    // A weight is the decimal field, and zero already means "always granted"
                    // (RewardTable.rollIndices reads weight <= 0 as a guarantee), so a negative weight is
                    // not a small weight -- it is a second spelling of zero. An author who typed one
                    // meant something the format does not have, and the roll would not do it.
                    if (value < 0) {
                        book.status("A weight cannot be negative - zero already means always granted", true);
                        return;
                    }
                    book.bookRewards.sendTableValue(edit.path(), new com.google.gson.JsonPrimitive(value));
                }
                else {
                    book.bookRewards.sendTableValue(edit.path(), new com.google.gson.JsonPrimitive((int) value));
                }
            }
            case FLAG -> book.bookRewards.sendTableValue(edit.path(),
                    new com.google.gson.JsonPrimitive(Boolean.parseBoolean(trimmed)));
            default -> {
                if (trimmed.isEmpty()) {
                    book.bookRewards.sendTableValue(edit.path(), null);
                    return;
                }
                book.bookRewards.sendTableValue(edit.path(), new com.google.gson.JsonPrimitive(trimmed));
            }
        }
    }

    /**
     * Commits one inline edit, by the path the piece is about.
     *
     * <p>Three shapes share the door, because they share the rule that a field commits as a whole:
     * prose is split back into the line array the format stores, a whole entry's raw text is parsed as
     * JSON (and refused out loud when it is not), and everything else goes through the typed field the
     * tree declares. An empty value removes the field, as everywhere else.
     */
    private void commitInlineText(String path, String text) {
        // A field the table editor opened: its value belongs to a table, and the quest op below would
        // write the path into the quest instead. One branch, before anything else looks at the path.
        if (book.tableEdit != null && book.tableEdit.path().equals(path)) {
            QuestBookScreen.TableEdit edit = book.tableEdit;
            book.tableEdit = null;
            book.closeInlineEditor();
            commitTableText(edit, text);
            book.rebuildWidgets();
            return;
        }
        // A saved template's new name: client-local, so it needs neither the quest below nor the
        // edit permission a file write wants. An empty name keeps the old one rather than shelving
        // a row with no words, and the id never changes.
        if (path != null && path.startsWith(QuestBookScreen.TEMPLATE_ROW_PREFIX)) {
            String id = path.substring(QuestBookScreen.TEMPLATE_ROW_PREFIX.length());
            String name = text == null ? "" : text.trim();
            book.closeInlineEditor();
            if (name.isEmpty()) {
                book.status("A template needs a name — keeping the old one", true);
                return;
            }
            if (dev.ellipog.tenet.client.dev.ComponentTemplates.rename(id, name)) {
                book.status("Renamed to \"" + name + "\"", false);
            }
            return;
        }
        String target = book.editTarget();
        book.closeInlineEditor();
        if (!book.mayEditNow() || target == null) {
            return;
        }
        if (InlineEdit.DEPENDENCY_ADD.equals(path)) {
            book.questEditor.addDependency(text == null ? "" : text.trim());
            return;
        }
        if ("description".equals(path)) {
            if (text == null || text.trim().isEmpty()) {
                book.sendField(target, "description", null);
                return;
            }
            // Split back into paragraphs and drop the blank ones at the ends: a blank line at the end of
            // the prose is where the caret was, not content. A blank line *between* two paragraphs is a
            // paragraph break and is written as one. See `Prose`.
            List<String> paragraphs = Prose.trimmed(Prose.split(text));
            book.sendField(target, "description", QuestBookScreen.stringArray(paragraphs));
            return;
        }
        if (path.matches("(tasks|rewards)\\.\\d+(\\.conditions\\.\\d+)?")) {
            if (text == null || text.trim().isEmpty()) {
                book.status("tenet.status.an_entry_cannot_be_empty", true);
                return;
            }
            try {
                JsonElement parsed = com.google.gson.JsonParser.parseString(text);
                book.sendField(target, path, parsed);
            }
            catch (RuntimeException malformed) {
                book.status("That is not JSON \u2014 " + malformed.getMessage(), true);
            }
            return;
        }
        // Words an entry answers to in lookups by tag: one field for the list, commas between
        // them, empties gone -- the card's half of the inspector's own comma rule in
        // `commitField`. A tag that breaks the pattern is the server's to report, which is the
        // whole reason this can be a text box rather than a picker.
        if (path.equals("tags") || path.matches("(tasks|rewards)\\.\\d+\\.tags")) {
            if (text == null || text.trim().isEmpty()) {
                book.sendField(target, path, null);
                return;
            }
            List<String> names = Arrays.stream(text.trim().split(","))
                    .map(String::trim).filter(name -> !name.isEmpty()).toList();
            book.sendField(target, path, QuestBookScreen.stringArray(names));
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            book.sendField(target, path, null);
            return;
        }
        InspectField<?> field = QuestPanelLayout.fieldFor(book.replicaQuest(), path);
        InspectField.Result<?> result = field.parse(text.trim());
        if (!result.ok()) {
            book.status(result.error(), true);
            book.rebuildWidgets();
            return;
        }
        book.sendField(target, path, BookQuestEditor.jsonOf(result.value()));
    }

    /**
     * Saves one entry of the open quest as a component template: the setup as it stands,
     * conditions included, onto this client's shelf.
     *
     * <p>What is saved is the tree the replica holds — pending inline edits included, because
     * the replica is what the overlay already carries — so what the author sees is what the
     * template keeps. The store copies and strips it; the quest is untouched, and one op is
     * never sent, so saving is not an undo step.
     */
    private void saveEntryAsTemplate(String member, int index) {
        JsonObject quest = book.replicaQuest();
        String questId = book.editTarget();
        if (quest == null || questId == null || member == null || index < 0) {
            return;
        }
        JsonElement found = QuestPanelLayout.get(quest, member + "." + index);
        if (found == null || !found.isJsonObject()) {
            return;
        }
        var kind = "rewards".equals(member)
                ? dev.ellipog.tenet.client.dev.ComponentTemplate.Kind.REWARD
                : dev.ellipog.tenet.client.dev.ComponentTemplate.Kind.TASK;
        JsonObject tree = found.getAsJsonObject();
        String type = tree.has("type") && tree.get("type").isJsonPrimitive()
                ? tree.get("type").getAsString()
                : "tenet:unknown";
        String name = dev.ellipog.tenet.client.dev.ComponentTemplate.blurb(kind, type, tree);
        var existing = dev.ellipog.tenet.client.dev.ComponentTemplates.duplicateOf(kind, type, tree);
        // An entry saved is an entry touched: a condition template applied from the shelf lands
        // here next, which is where the author already is.
        dev.ellipog.tenet.client.dev.ComponentTemplates.noteEntryContext(questId, member, index);
        if (existing.isPresent()) {
            // One setup, one row: pressing the star ten times lists it once rather than shelving
            // ten identical copies.
            book.status("Already on the shelf as \"" + existing.get().name() + "\"", false);
            return;
        }
        var saved = dev.ellipog.tenet.client.dev.ComponentTemplates.saveNew(kind, type, tree, name);
        book.status("Saved \"" + saved.name() + "\" — find it in Assets, under Templates", false);
    }

    /**
     * Saves one condition of one entry as a component template.
     *
     * @param path the condition's own path ({@code tasks.3.conditions.1}); {@code member} and
     *             {@code entry} name the entry it hangs under, for the landing context
     */
    private void saveConditionAsTemplate(String path, String member, int entry) {
        JsonObject quest = book.replicaQuest();
        String questId = book.editTarget();
        if (quest == null || questId == null || path == null || member == null || entry < 0) {
            return;
        }
        JsonElement found = QuestPanelLayout.get(quest, path);
        if (found == null || !found.isJsonObject()) {
            return;
        }
        JsonObject tree = found.getAsJsonObject();
        String type = tree.has("type") && tree.get("type").isJsonPrimitive()
                ? tree.get("type").getAsString()
                : "tenet:unknown";
        String name = dev.ellipog.tenet.client.dev.ComponentTemplate.blurb(
                dev.ellipog.tenet.client.dev.ComponentTemplate.Kind.CONDITION, type, tree);
        var existing = dev.ellipog.tenet.client.dev.ComponentTemplates.duplicateOf(
                dev.ellipog.tenet.client.dev.ComponentTemplate.Kind.CONDITION, type, tree);
        dev.ellipog.tenet.client.dev.ComponentTemplates.noteEntryContext(questId, member, entry);
        if (existing.isPresent()) {
            book.status("Already on the shelf as \"" + existing.get().name() + "\"", false);
            return;
        }
        var saved = dev.ellipog.tenet.client.dev.ComponentTemplates.saveNew(
                dev.ellipog.tenet.client.dev.ComponentTemplate.Kind.CONDITION, type, tree, name);
        book.status("Saved \"" + saved.name() + "\" — find it in Assets, under Templates", false);
    }
}
