package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ellipog.tenet.client.BookGeometry;
import dev.ellipog.tenet.client.DevMode;
import dev.ellipog.tenet.quest.EditorField;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The entry form: its height, its columns, and the promise that nothing overlaps anything.
 *
 * <h2>What is asked here</h2>
 *
 * <p>The faults this replaced were all geometric, and all visible: an icon drawn taller than its slot,
 * boxes of every width in a row that lined up with nothing, a label inside the box it labelled. So the
 * assertions are geometric too -- the badge's icon is inside the badge, a label never reaches into its
 * control, two controls never overlap, and the height the card is told equals the height the drawing
 * uses. All of it answerable with a parsed JSON object and no client, which is why the form is derived by
 * a game-free class and the drawing is not.
 *
 * <h2>And why every test here runs at full depth</h2>
 *
 * <p>Because the form is asserted as a <b>whole</b>: the label column fits the longest label it has, the
 * cell count is the fields it declares, and the condition picker's row is part of the height. Two of those
 * depend on the depth directly -- Normal mode drops the fields that say "how exactly" and the add-condition
 * row -- so the class turns Advanced on, and what Normal drops is asserted in {@code AdvancedTest} instead.
 */
@DisplayName("the entry form")
class EntryFormLayoutTest {

    @BeforeEach
    void atFullDepth() {
        DevMode.setAdvanced(true);
    }

    @AfterEach
    void forgetTheDepth() {
        // A static flag: a test that leaves it set changes the next class to run.
        DevMode.reset();
    }

    private static JsonObject itemTask() {
        return JsonParser.parseString("""
                {
                  "type": "tenet:item",
                  "item": "minecraft:cobblestone",
                  "count": 8,
                  "consumeItems": false,
                  "match": "strict",
                  "onlyFromCrafting": false
                }
                """).getAsJsonObject();
    }

    private static JsonObject locationTask() {
        return JsonParser.parseString("""
                {
                  "type": "tenet:location",
                  "dimension": "minecraft:overworld",
                  "position": [10, 64, -20],
                  "size": [8, 8, 8]
                }
                """).getAsJsonObject();
    }

    private static JsonObject unknownType() {
        return JsonParser.parseString("""
                { "type": "addon:mystery", "custom": true }
                """).getAsJsonObject();
    }

    /** A task with one condition, whose own form has fields to lay out under the task's. */
    private static JsonObject conditionedTask() {
        return JsonParser.parseString("""
                {
                  "type": "tenet:checkmark",
                  "title": "done",
                  "conditions": [ { "type": "tenet:stage", "stage": "my_pack:marked" } ]
                }
                """).getAsJsonObject();
    }

    /** A task with two conditions, the second a type this build does not know. */
    private static JsonObject mixedConditions() {
        return JsonParser.parseString("""
                {
                  "type": "tenet:checkmark",
                  "title": "done",
                  "conditions": [
                    { "type": "tenet:stage", "stage": "my_pack:marked" },
                    { "type": "addon:custom_gate", "whatever": true }
                  ]
                }
                """).getAsJsonObject();
    }

    private static BookGeometry.Rect slot(int width) {
        return BookGeometry.Rect.at(0, 0, width, 100);
    }

    private static EntryFormLayout.Form form(String member, JsonObject entry, int width) {
        return EntryFormLayout.form(member, entry, slot(width));
    }

    @Test
    @DisplayName("the badge is a name and a whole icon, and the icon is inside its own line")
    void badgeHoldsItsIcon() {
        EntryFormLayout.Form form = form("tasks", itemTask(), 600);

        assertEquals("Item", QuestPanelLayout.typeName("tasks", "tenet:item"),
                "the badge shows the type's own name, not the id a file spells");
        assertEquals(EntryFormLayout.ICON_BOX, form.icon().height(),
                "the icon's box is the icon's size -- it used to be sixteen pixels in a fourteen-pixel slot");
        assertTrue(form.icon().bottom() <= form.badge().bottom(),
                "the icon stands inside the badge's line rather than spilling into the next");
        assertTrue(form.name().x() > form.icon().right(),
                "the name starts after the icon, not on top of it");
        assertTrue(form.copy().x() > form.name().x(),
                "and the corner controls are past the name");
    }

    @Test
    @DisplayName("the corner reads Copy, star, fold, cross: the harmless presses first, the removal last")
    void cornerOrderKeepsRemovalLast() {
        EntryFormLayout.Form form = form("tasks", itemTask(), 600);

        assertEquals(EntryFormLayout.COPY_WIDTH, form.copy().width());
        assertEquals(EntryFormLayout.SAVE_WIDTH, form.save().width(), "the template star is a one-glyph chip");
        assertTrue(form.copy().right() <= form.save().x(), "Copy is left of the star");
        assertTrue(form.save().right() <= form.fold().x(), "the star is left of the fold");
        assertTrue(form.fold().right() <= form.remove().x(), "and the cross stays last at the edge");
        assertTrue(form.remove().right() <= 600, "inside the row");

        EntryFormLayout.ConditionRow row = form("tasks", conditionedTask(), 600).conditions().get(0);
        assertEquals(EntryFormLayout.SAVE_WIDTH, row.save().width());
        assertTrue(row.save().right() <= row.remove().x(), "a condition's star is left of its cross too");
        assertTrue(row.remove().x() >= row.name().right(), "and both sit past the condition's name");
    }

    @Test
    @DisplayName("a field is a label in the label column and a control beside it, never over it")
    void labelsStayInTheirColumn() {
        for (int width : new int[] {320, 480, 600, 900}) {
            EntryFormLayout.Form form = form("tasks", itemTask(), width);
            int labelWidth = form.cells().get(0).label().width();
            for (EntryFormLayout.Cell cell : form.cells()) {
                assertEquals(labelWidth, cell.label().width(),
                        "one label column per form, at " + width);
                assertTrue(cell.pressTarget().x() >= cell.label().right(),
                        "a control starts at or after its label's edge, at " + width + ": " + cell.field().path());
            }
        }
    }

    @Test
    @DisplayName("the label column fits the longest label, so nothing is truncated")
    void theLabelColumnFitsItsLongestLabel() {
        // The fault this catches: "Any dime…" and "Checked …", from a fixed 58-pixel column that fitted
        // "Item" and cut everything longer.
        for (JsonObject entry : List.of(itemTask(), locationTask())) {
            for (int width : new int[] {480, 600, 900}) {
                EntryFormLayout.Form form = form("tasks", entry, width);
                int widest = form.cells().stream().mapToInt(cell -> cell.field().label().length()).max()
                        .orElse(0) * EntryFormLayout.CHAR_WIDTH;
                int column = form.cells().get(0).label().width();
                assertTrue(column >= Math.min(widest, EntryFormLayout.MAX_LABEL_WIDTH),
                        "the column is " + column + " and its longest label wants " + widest
                                + " in " + entry.get("type"));
            }
        }
    }

    @Test
    @DisplayName("a stepper is the same three boxes wherever it sits")
    void steppersAreOneWidth() {
        // The other fault from the same screenshot: `[− 8 × +]` on one line and `[−        +]` stretched
        // across a half-line below it, because the value box was whatever was left of the cell.
        EntryFormLayout.Form form = form("tasks", itemTask(), 900);
        List<EntryFormLayout.Cell> steppers = form.cells().stream()
                .filter(EntryFormLayout.Cell::isNumber).toList();
        assertTrue(steppers.size() >= 2, "the item task has steppers to compare");
        for (EntryFormLayout.Cell cell : steppers) {
            assertEquals(EntryFormLayout.STEPPER_VALUE_WIDTH, cell.value().width(),
                    cell.field().path() + " sizes its own value box");
            assertEquals(EntryFormLayout.BUTTON, cell.minus().width());
            assertEquals(EntryFormLayout.BUTTON, cell.plus().width());
            assertEquals(cell.minus().right() + EntryFormLayout.GAP, cell.value().x(),
                    "the value box follows its minus");
            assertEquals(cell.value().right() + EntryFormLayout.GAP, cell.plus().x(),
                    "and its plus follows the value");
        }
    }

    @Test
    @DisplayName("no two controls overlap, at any width")
    void nothingOverlaps() {
        for (String member : List.of("tasks", "rewards")) {
            for (JsonObject entry : List.of(itemTask(), locationTask(), unknownType(), conditionedTask(),
                    mixedConditions())) {
                for (int width = 200; width <= 900; width += 7) {
                    List<BookGeometry.Rect> boxes = boxes(form(member, entry, width));
                    for (int i = 0; i < boxes.size(); i++) {
                        for (int j = i + 1; j < boxes.size(); j++) {
                            assertFalse(boxes.get(i).intersects(boxes.get(j)),
                                    "two controls overlap at " + width + ": " + boxes.get(i) + " and "
                                            + boxes.get(j) + " in " + entry.get("type"));
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("the height the card is told is the height the form draws")
    void heightIsTheDrawing() {
        EntryFormLayout.Form form = form("tasks", itemTask(), 600);
        // The badge's line, then one line per packed line of fields, then the row that opens the
        // condition picker -- and the card's own arithmetic turns that into pixels, so a form and the
        // rows below it cannot disagree.
        // The packer puts as many narrow controls on a line as fit, so the common settings -- the three
        // short switches every reward carries -- share one line rather than stranding the third: that is
        // what makes them read as a settings row instead of chips floating at the right.
        assertEquals(8, 1 + distinctLineCount(form.cells()),
                "badge, item, count+consume, the two matches, title, picture, tags, the four switches");
        assertEquals(1 + distinctLineCount(form.cells()) + 1, form.lines(),
                "and the condition picker's row, which every open entry carries");
        assertEquals(EntryFormLayout.lines("tasks", itemTask(), 600), form.lines(),
                "the layout's own count and the card's input are one derivation");
        assertEquals(24, form("tasks", unknownType(), 600).height(),
                "a one-line entry is still the 24 pixels it always was");
    }

    @Test
    @DisplayName("a condition's rows are indented under the entry, and counted in its height")
    void conditionsLayOutUnderTheEntry() {
        EntryFormLayout.Form form = form("tasks", conditionedTask(), 600);
        assertEquals(1, form.conditions().size(), "the fixture's one condition");
        EntryFormLayout.ConditionRow row = form.conditions().get(0);
        assertTrue(row.icon().x() > form.cells().get(0).label().x(),
                "the condition is indented inside the entry's own content");
        assertFalse(row.cells().isEmpty(), "a known condition type draws its own form");
        assertEquals(EntryFormLayout.REMOVE_WIDTH, row.remove().width(),
                "and carries the cross that removes it");
        assertTrue(row.remove().x() >= row.name().right(),
                "the cross sits past the condition's name, on the badge's line");

        int expected = 1 + distinctLineCount(form.cells())
                + form.conditions().stream().mapToInt(EntryFormLayout.ConditionRow::lines).sum() + 1;
        assertEquals(expected, form.lines(),
                "the height is the badge, the fields, the conditions and the picker's row");
        assertEquals(EntryFormLayout.lines("tasks", conditionedTask(), 600), form.lines(),
                "the card's own count and the layout are one derivation, conditions included");

        // Two conditions, the second a type this build does not know: still a row, still a line, and no
        // cells -- the raw-JSON fallback's trigger, the same refusal an unknown entry gets.
        EntryFormLayout.Form mixed = form("tasks", mixedConditions(), 600);
        assertEquals(2, mixed.conditions().size());
        assertFalse(mixed.conditions().get(0).cells().isEmpty());
        assertTrue(mixed.conditions().get(1).cells().isEmpty(), "an unknown condition type has no form");
        assertEquals(1, mixed.conditions().get(1).lines(), "but it is still a line of its own");
    }

    @Test
    @DisplayName("every field of the type is drawn, and each one's kind is the type's own")
    void everyFieldIsDrawn() {
        EntryFormLayout.Form form = form("tasks", itemTask(), 600);
        List<String> paths = form.cells().stream().map(cell -> cell.field().path()).toList();
        assertEquals(List.of("item", "count", "consumeItems", "match", "onlyFromCrafting", "manualOnly",
                "optional", "disableToast", "title", "icon", "tags", "autoSubmitTicks"), paths,
                "the type's fields, then the settings every task has");

        assertEquals(EditorField.Kind.ITEM, form.cells().get(0).field().kind());
        assertEquals(EditorField.Kind.NUMBER, form.cells().get(1).field().kind());
        assertTrue(form.cells().get(1).isNumber(), "a count has a stepper");
        assertEquals(EditorField.Kind.FLAG, form.cells().get(2).field().kind());

        // And a triple is three boxes rather than one, with the three element paths they commit to.
        EntryFormLayout.Cell position = form("tasks", locationTask(), 600).cells().stream()
                .filter(cell -> cell.field().path().equals("position")).findFirst().orElseThrow();
        assertEquals(3, position.axes().size());
        assertEquals("position.0", position.field().axis(0));
        assertEquals("position.2", position.field().axis(2));
        assertFalse(position.action().width() <= 0, "a position carries the press that fills it");
    }

    @Test
    @DisplayName("an unknown type still gets a badge and a grip -- it is a row, just not a form")
    void unknownTypesStillLayOut() {
        EntryFormLayout.Form form = form("tasks", unknownType(), 600);
        assertTrue(form.icon().width() > 0, "the badge's icon box is there");
        assertTrue(form.grip().width() > 0, "and the row can still be dragged");
        assertEquals(1, form.lines(), "one badge line, and no form under it");
    }

    @Test
    @DisplayName("a folded entry is its badge line: one line, no fields, and still 24 pixels")
    void foldedIsOneLine() {
        EntryFormLayout.Form folded = EntryFormLayout.form("tasks", itemTask(), slot(600), true);

        assertEquals(1, folded.lines(), "folding leaves the badge");
        assertTrue(folded.cells().isEmpty(), "and no fields to draw or press");
        assertEquals(24, folded.height(), "which is the one-line height every row has always had");
        assertTrue(folded.copy().width() > 0 && folded.save().width() > 0 && folded.fold().width() > 0
                        && folded.remove().width() > 0,
                "the corner's four controls survive folding: the way back must not go with the fields");
        assertTrue(folded.grip().height() > 0, "and the row can still be dragged");
        assertEquals(EntryFormLayout.lines("tasks", itemTask(), 600, true), folded.lines(),
                "the card's own count and the layout are one derivation, folded or not");
        assertTrue(folded.name().width() > 0, "the badge's line has room for the summary");
    }

    /** Every box a press could land on, for the overlap sweep. */
    private static List<BookGeometry.Rect> boxes(EntryFormLayout.Form form) {
        List<BookGeometry.Rect> out = new ArrayList<>();
        out.add(form.copy());
        out.add(form.save());
        out.add(form.remove());
        out.add(form.grip());
        addCells(out, form.cells());
        for (EntryFormLayout.ConditionRow row : form.conditions()) {
            out.add(row.icon());
            out.add(row.save());
            out.add(row.remove());
            addCells(out, row.cells());
        }
        if (form.addCondition().width() > 0) {
            out.add(form.addCondition());
        }
        return out;
    }

    private static void addCells(List<BookGeometry.Rect> out, List<EntryFormLayout.Cell> cells) {
        for (EntryFormLayout.Cell cell : cells) {
            if (cell.isTriple()) {
                out.addAll(cell.axes());
            }
            else {
                out.add(cell.pressTarget());
            }
            if (cell.isNumber()) {
                out.add(cell.minus());
                out.add(cell.plus());
            }
            if (cell.action().width() > 0) {
                out.add(cell.action());
            }
        }
    }

    private static int distinctLineCount(List<EntryFormLayout.Cell> cells) {
        return (int) cells.stream().map(cell -> cell.label().y()).distinct().count();
    }
}
