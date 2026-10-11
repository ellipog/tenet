package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ellipog.armature.client.ui.CanvasBackground;
import dev.ellipog.tenet.client.BookGeometry;
import dev.ellipog.tenet.client.DevMode;
import dev.ellipog.tenet.quest.EditorField;
import dev.ellipog.tenet.quest.condition.ConditionTypes;
import dev.ellipog.tenet.quest.reward.RewardTypes;
import dev.ellipog.tenet.quest.task.TaskTypes;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's depth: what Normal hides, and that it hides exactly that.
 *
 * <h2>Why this file exists, and what it is guarding against</h2>
 *
 * <p>Because the depth is a flag read from two directions and only one of them is obvious. A section gate
 * reads {@code if (Advanced.on())} -- add the section -- while every predicate about hiding reads the other
 * way, and a version of this class whose predicates were inverted passed four hundred tests: every test of a
 * <i>model</i> asserts the complete form and asks for full depth, so nothing in the suite was asking what
 * Normal mode actually loses. It drew every field and dropped every section, which is not a depth at all.
 * The assertions below are the ones that notice, and the first of them is the direction itself.
 *
 * <h2>The four things asserted</h2>
 *
 * <ol>
 *   <li><b>The direction.</b> Normal hides a marked field and a marked row; Advanced shows both; a name no
 *       build knows is shown at either depth.</li>
 *   <li><b>Both ways of the table.</b> Every key the class marks is a key some surface really produces --
 *       so a renamed section or a dropped field fails here rather than quietly hiding nothing -- and every
 *       registered type keeps at least one editable field at the shallow depth, so no type becomes a form
 *       an author cannot fill in.</li>
 *   <li><b>Normal is a subset of Advanced</b>, in order, for each row model: nothing Normal hides comes
 *       back in a different place, and nothing Advanced has is missing from it.</li>
 *   <li><b>No heading is left over nothing</b>, which is the shape of the gate that is easy to get wrong:
 *       a section gated away whole cannot leave its name behind.</li>
 * </ol>
 */
@DisplayName("the editor's depth")
class AdvancedTest {

    /**
     * Full depth to begin with, because most of what this file asserts is a <b>comparison</b>: the deep list,
     * read first, is what the shallow one is measured against. A test that read it while the flag happened to
     * be shallow would compare a list with itself, and the only thing that would notice is the assertion
     * saying the depth hides nothing -- which is exactly how this was found, on the first run, five times
     * over. Each test that wants the shallow side turns it off explicitly, which is also the truthful way to
     * read a test whose subject is a difference between two depths.
     */
    @BeforeEach
    void atFullDepth() {
        DevMode.setAdvanced(true);
    }

    @AfterEach
    void forgetTheDepth() {
        // A static flag: a test that leaves it set changes the next class to run.
        DevMode.reset();
    }

    /** A quest with one of everything the settings page draws, so the rows all exist. */
    private static JsonObject quest() {
        return JsonParser.parseString("""
                {
                  "id": "smelt_iron",
                  "title": "Smelt Iron",
                  "subtitle": "Any furnace will do",
                  "x": 120, "y": 64,
                  "shape": "circle", "size": 32, "rotation": 15, "iconScale": 1.25,
                  "showTitle": true, "invisible": false, "invisibleUntilTasks": 2,
                  "hideUntilDependenciesComplete": "chapter",
                  "hideUntilDependenciesVisible": "chapter",
                  "hideDependencyLines": false, "hideTextUntilComplete": false,
                  "hideDetailsUntilStartable": false,
                  "repeatable": false, "repeatCooldownTicks": 1200, "sequentialTasks": false,
                  "autoClaim": "",
                  "prerequisiteMode": "all", "minRequired": 1, "maxCompletableDependents": 0,
                  "exclusiveGroup": "", "aliases": ["iron"],
                  "dependsOn": ["mine_twigs"],
                  "icon": { "item": "minecraft:iron_ingot" }
                }
                """).getAsJsonObject();
    }

    /**
     * A chapter with the fields the tab edits, so its rules section has rows to lose.
     *
     * <p>And with two canvas elements, because the elements section is gated on the chapter having any: a
     * fixture without them would leave the section out of the heading lists, and the two assertions about the
     * chapter tab's sections would go on passing while saying nothing about the new one. An image and a line,
     * because those two arms between them carry every field the depth marks.
     */
    private static JsonObject chapter() {
        return JsonParser.parseString("""
                {
                  "id": "first_light",
                  "title": "First Light",
                  "subtitle": "",
                  "icon": { "item": "minecraft:torch" },
                  "aliases": [],
                  "progressionMode": "flexible",
                  "defaultConsumeItems": true,
                  "defaultPrerequisiteMode": "all",
                  "autoClaim": "enabled",
                  "prerequisiteMode": "all",
                  "minRequired": 1,
                  "dependsOn": [],
                  "completesWhen": [],
                  "hideUntilDependenciesComplete": false,
                  "defaultHideUntilDependenciesComplete": true,
                  "defaultHideUntilDependenciesVisible": false,
                  "dependencyStyle": { "form": "chamfered", "arrowHead": "triangle" },
                  "quests": ["first_steps.json"],
                  "elements": [
                    { "type": "image", "id": "logo", "x": 16, "y": 16, "width": 64, "height": 64,
                      "image": { "sprite": "minecraft:block/stone" } },
                    { "type": "rect", "id": "frame", "x": 0, "y": 0, "width": 200, "height": 120 },
                    { "type": "text", "id": "caption", "x": 8, "y": 8, "text": "Chapter One" },
                    { "type": "line", "id": "rule", "x1": 0, "y1": 96, "x2": 128, "y2": 96, "width": 2 }
                  ]
                }
                """).getAsJsonObject();
    }

    // ------------------------------------------------------------------
    // 1. The direction
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Normal hides a marked field and a marked row; Advanced shows them both")
    void theDirectionOfTheFlag() {
        DevMode.setAdvanced(false);
        assertFalse(Advanced.on(), "Advanced is what the flag turns on");
        assertTrue(Advanced.hidesField("nbtFilter"), "Normal hides a refinement");
        assertTrue(Advanced.hidesField("permissionLevel"));
        assertTrue(Advanced.hidesRow("rotation"), "and a settings row that is not a whole section");
        assertFalse(Advanced.showsSection("tables"), "and the pack's tables");

        DevMode.setAdvanced(true);
        assertTrue(Advanced.on());
        assertFalse(Advanced.hidesField("nbtFilter"), "Advanced hides nothing");
        assertFalse(Advanced.hidesField("permissionLevel"));
        assertFalse(Advanced.hidesRow("rotation"));
        assertTrue(Advanced.showsSection("tables"), "and shows every section");

        // And a name this build does not know is shown at either depth: an addon's field is basic the day
        // it registers, so marking one Advanced is a request rather than a default.
        for (boolean depth : new boolean[] {false, true}) {
            DevMode.setAdvanced(depth);
            assertFalse(Advanced.hidesField("some_addon_field"), "an unknown field is shown");
            assertFalse(Advanced.hidesRow("some_addon_row"), "and so is an unknown row");
            assertTrue(Advanced.showsSection("some_addon_section"));
            assertFalse(Advanced.hidesField(""), "and a control writing no field at all is never hidden");
            assertFalse(Advanced.hidesField(null), "nor is a null path a hidden one");
        }
    }

    // ------------------------------------------------------------------
    // 2. Both ways of the table
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every key the depth marks is a key a form really has")
    void noMarkedKeyIsStale() {
        // The direction that catches a rename: a field marked "nbtFilter" on a type that no longer declares
        // it is a key that hides nothing, and nothing else in the suite would say so.
        Set<String> declared = new TreeSet<>();
        for (ResourceLocation id : TaskTypes.ids()) {
            for (EditorField field : TaskTypes.editorOf(id)) {
                declared.add(field.path());
            }
        }
        for (ResourceLocation id : RewardTypes.ids()) {
            for (EditorField field : RewardTypes.editorOf(id)) {
                declared.add(field.path());
            }
        }
        for (ResourceLocation id : ConditionTypes.ids()) {
            for (EditorField field : ConditionTypes.editorOf(id)) {
                declared.add(field.path());
            }
        }
        assertFalse(declared.isEmpty(), "the registries must not be empty for this to mean anything");

        for (String marked : Advanced.fieldKeys()) {
            assertTrue(declared.contains(marked),
                    "the depth marks \"" + marked + "\" and no registered form declares it, so hiding it "
                            + "hides nothing. Declared fields: " + declared);
        }

        // And the settings page's own row keys, against the page's own list at full depth.
        Set<String> rowKeys = new TreeSet<>();
        for (QuestSettingsLayout.Row row : settingsRows()) {
            rowKeys.add(row.key());
        }
        for (String marked : Advanced.rowKeys()) {
            assertTrue(rowKeys.contains(marked),
                    "the depth marks the row \"" + marked + "\" and the settings page has no such row. "
                            + "Its rows: " + rowKeys);
        }

        // And the Assets sections, against the enum itself: a section renamed in the enum would otherwise
        // stop hiding while this table went on claiming it did.
        Set<String> sections = new TreeSet<>();
        for (AssetsLayout.Section section : AssetsLayout.Section.values()) {
            sections.add(section.name().toLowerCase(java.util.Locale.ROOT));
        }
        for (String marked : Advanced.hiddenSections()) {
            assertTrue(sections.contains(marked),
                    "the depth marks the Assets section \"" + marked + "\" and the enum has no such value");
        }

        // And the canvas elements' own vocabulary, against the rows the panel really builds: a key here that
        // no arm produces is a mark against nothing, and the arm that lost a row would otherwise be silent.
        DevMode.setAdvanced(true);
        Set<String> elementRows = new TreeSet<>();
        for (String key : elementFields()) {
            String[] field = ChapterPanelLayout.elementFieldOf(key);
            if (field != null) {
                elementRows.add(field[1]);
            }
        }
        assertFalse(elementRows.isEmpty(), "the fixture must produce element rows for this to mean anything");
        for (String marked : Advanced.elementKeys()) {
            assertTrue(elementRows.contains(marked),
                    "the depth marks the element field \"" + marked + "\" and no arm builds a row for it, so "
                            + "hiding it hides nothing. The rows the fixture produced: " + elementRows);
        }

        // And the quest links' own vocabulary, against the rows the form really builds, for the same
        // reason: a key here that no row produces is a mark against nothing.
        Set<String> linkRows = new TreeSet<>();
        for (ToolsLayout.Action row : LinkPanelLayout.rows(linkFixture(), Set.of())) {
            String[] field = ChapterPanelLayout.linkFieldOf(row.key());
            if (field != null) {
                linkRows.add(field[1]);
            }
            if (row.right() != null) {
                String[] half = ChapterPanelLayout.linkFieldOf(row.right().key());
                if (half != null) {
                    linkRows.add(half[1]);
                }
            }
        }
        assertFalse(linkRows.isEmpty(), "the fixture must produce link rows for this to mean anything");
        for (String marked : Advanced.linkKeys()) {
            assertTrue(linkRows.contains(marked),
                    "the depth marks the link field \"" + marked + "\" and the form builds no row for it, so "
                            + "hiding it hides nothing. The rows the fixture produced: " + linkRows);
        }
    }

    @Test
    @DisplayName("no registered type becomes a form with nothing to fill in at the shallow depth")
    void everyTypeKeepsAFieldInNormalMode() {
        // The floor under the cut: a type whose every field is a refinement would be a task an author could
        // add and then not describe. One field is enough -- `checkmark`'s only field is its button's text,
        // and an addon's are basic by default -- and this is the assertion that says so rather than a note
        // that hopes so.
        DevMode.setAdvanced(false);

        List<String> empty = new ArrayList<>();
        for (boolean tasks : new boolean[] {true, false}) {
            for (ResourceLocation id : tasks ? TaskTypes.ids() : RewardTypes.ids()) {
                List<EditorField> form = tasks ? TaskTypes.editorOf(id) : RewardTypes.editorOf(id);
                assertFalse(form.isEmpty(), id + " has no form at all, which is a different fault");
                if (Advanced.fields(form).isEmpty()) {
                    empty.add(id.toString());
                }
            }
        }
        for (ResourceLocation id : ConditionTypes.ids()) {
            List<EditorField> form = ConditionTypes.editorOf(id);
            assertFalse(form.isEmpty(), id + " has no form at all");
            if (Advanced.fields(form).isEmpty()) {
                empty.add(id.toString());
            }
        }
        assertTrue(empty.isEmpty(),
                "these types ask for nothing at the shallow depth, so an author could not fill them in: "
                        + empty);
    }

    // ------------------------------------------------------------------
    // 3. Normal is a subset of Advanced, in order
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the shallow page is the deep page less the rows it hides, in the same order")
    void theSettingsPageIsASubset() {
        List<QuestSettingsLayout.Row> deep = settingsRows();
        DevMode.setAdvanced(false);
        List<QuestSettingsLayout.Row> shallow = settingsRows();
        DevMode.setAdvanced(true);

        assertTrue(deep.size() > shallow.size(),
                "the depth hides nothing at all, which means the flag is not being read: " + deep.size());
        assertTrue(deep.stream().map(QuestSettingsLayout.Row::key).toList()
                        .containsAll(shallow.stream().map(QuestSettingsLayout.Row::key).toList()),
                "a shallow row is not in the deep list, so it came from somewhere else");
        for (String hidden : Advanced.rowKeys()) {
            assertFalse(shallow.stream().anyMatch(row -> row.key().equals(hidden)),
                    hidden + " is still on the shallow page");
        }
    }

    @Test
    @DisplayName("the chapter tab loses its rules section and keeps everything else")
    void theChapterTabIsASubset() {
        List<ToolsLayout.Action> deep = chapterRows();
        DevMode.setAdvanced(false);
        List<ToolsLayout.Action> shallow = chapterRows();
        DevMode.setAdvanced(true);

        assertEquals(List.of("h:identity", "h:group", "h:elements", "h:links", "h:quests"), headings(shallow),
                "the shallow chapter tab is identity, the group, its elements, its links and its quests");
        assertEquals(List.of("h:identity", "h:rules", "h:group", "h:elements", "h:links", "h:quests"), headings(deep),
                "and the deep one has the rules between them");
    }

    @Test
    @DisplayName("an element keeps what it is and loses how it is drawn")
    void theElementFieldsAreASubset() {
        // The fourth vocabulary, and the reason it is a fourth: `rotation` is both a picture's angle and a
        // quest settings row, so a shared set would hide one control when it hid the other.
        DevMode.setAdvanced(true);
        List<String> deep = elementFields();
        DevMode.setAdvanced(false);
        List<String> shallow = elementFields();
        DevMode.setAdvanced(true);

        assertFalse(deep.isEmpty(), "the fixture has to have an element selected for this to mean anything");
        assertTrue(deep.containsAll(shallow), "the shallow rows are a subset of the deep ones: " + shallow);
        assertTrue(deep.size() > shallow.size(), "and the depth hides something");

        // What an element *is* survives: its box, its picture, its colour, its words, which way a line points.
        //
        // `shadow` is in this list rather than the one below, and it is the one field here that could be
        // argued either way. It stays basic because it is what makes a label readable over a picture: hiding
        // it would mean an author in Normal mode could not put words on an image they had just placed.
        for (String kept : List.of("element.logo.width", "element.logo.height", "element.logo.image.texture",
                "element.logo.image.sprite", "element.frame.fillColor", "element.frame.borderColor",
                "element.caption.text", "element.caption.color", "element.caption.shadow",
                "element.rule.x1", "element.rule.x2", "element.rule.color", "element.rule.arrowhead")) {
            assertTrue(shallow.contains(kept), kept + " is what the element is, so Normal keeps it");
        }
        // And what qualifies it goes.
        for (String hidden : List.of("element.logo.rotation", "element.logo.corner", "element.logo.tint",
                "element.logo.alpha", "element.logo.title", "element.logo.click.type",
                "element.logo.click.data", "element.logo.order", "element.logo.dev",
                "element.logo.requires", "element.frame.borderWidth", "element.rule.order")) {
            assertTrue(deep.contains(hidden), hidden + " is missing at full depth, so nothing hides it");
            assertFalse(shallow.contains(hidden), hidden + " is a refinement, so Normal hides it");
        }
    }

    @Test
    @DisplayName("the dock's Book tab is three switches at the shallow depth, and the appearance block deep")
    void theBookTabIsASubset() {
        List<ToolsLayout.Action> deep = bookRows();
        DevMode.setAdvanced(false);
        List<ToolsLayout.Action> shallow = bookRows();
        DevMode.setAdvanced(true);

        assertEquals(List.of("motion", "snap", "progress"),
                shallow.stream().map(ToolsLayout.Action::key).toList(),
                "the shallow Book tab is the three switches and nothing else");
        // And the pack's own name and icon are not gated: they write the questline's `index.json`, which is
        // the book's identity rather than its look, and an author naming their book in Normal mode is the
        // whole point of the shallow view being usable.
        assertEquals(List.of("section:book", "book:title", "book:icon", "book:lod"),
                ToolsLayout.bookRows(true).stream().map(ToolsLayout.Action::key).toList(),
                "the pack's rows are not the depth's business");
        assertTrue(deep.size() > shallow.size(), "and the deep one has the appearance block too");
        for (String section : List.of("section:palette", "section:shape", "section:canvas",
                "section:colours")) {
            assertTrue(deep.stream().anyMatch(row -> section.equals(row.key())),
                    section + " is missing from the deep Book tab");
            assertFalse(shallow.stream().anyMatch(row -> section.equals(row.key())),
                    section + " is still in the shallow Book tab");
        }
    }

    @Test
    @DisplayName("the Assets panel shows two sections at the shallow depth, and presses land on a drawn one")
    void theAssetsSectionsAreASubset() {
        DevMode.setAdvanced(true);
        assertEquals(
                List.of(AssetsLayout.Section.TABLES, AssetsLayout.Section.QUESTS,
                        AssetsLayout.Section.TEMPLATES),
                List.of(AssetsLayout.shown()), "all three sections at full depth");

        DevMode.setAdvanced(false);
        assertEquals(List.of(AssetsLayout.Section.QUESTS, AssetsLayout.Section.TEMPLATES),
                List.of(AssetsLayout.shown()),
                "and the quest files and the template shelf at the shallow one: tables are the"
                        + " half an author meets late, templates are everyday reuse");
        // The pairing that matters: `sectionAt` maps a point to a section *by index* into this list, so a
        // drawn list and a pressed list that disagreed would put every press on the row above.
        AssetsLayout.Frame frame = AssetsLayout.Frame.of(BookGeometry.Rect.at(0, 0, 400, 260));
        BookGeometry.Rect first = frame.tab(0);
        assertEquals(AssetsLayout.Section.QUESTS,
                AssetsLayout.sectionAt(frame, first.x() + 2, first.y() + 2),
                "the first drawn tab is the section a press on it names");
        // And the coercion, for the two places a section arrives from somewhere that knows no depth: the
        // remembered name in the client's file, and the command that opens a table's editor.
        assertEquals(AssetsLayout.Section.QUESTS, AssetsLayout.shownOrFirst(AssetsLayout.Section.TABLES));
        assertEquals(AssetsLayout.Section.QUESTS, AssetsLayout.shownOrFirst(null));
    }

    @Test
    @DisplayName("a form loses its refinements and keeps what the entry means")
    void theEntryFormIsASubset() {
        List<EditorField> deep = QuestPanelLayout.editorFor("tasks", killTaskTree());
        DevMode.setAdvanced(false);
        List<EditorField> shallow = QuestPanelLayout.editorFor("tasks", killTaskTree());
        DevMode.setAdvanced(true);

        Set<String> deepNames = new TreeSet<>();
        for (EditorField field : deep) {
            deepNames.add(field.path());
        }
        assertEquals(Set.of("entity", "entityTypeTag", "value", "customName", "nbtFilter", "optional",
                        "disableToast", "title", "icon", "tags", "autoSubmitTicks"), deepNames,
                "the kill task's own fields, plus the six every task carries");
        assertEquals(Set.of("entity", "value", "optional", "disableToast", "title", "icon", "tags"),
                names(shallow),
                "and the shallow form keeps what the task counts, not how it counts it");
        assertNotEquals(deep.size(), shallow.size(), "the depth changed nothing about this form");
    }

    // ------------------------------------------------------------------
    // 4. No heading is left over nothing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("no section's name is left standing over the rows it lost")
    void noHeadingIsLeftOverNothing() {
        DevMode.setAdvanced(false);
        List<String> keys = settingsRows().stream().map(QuestSettingsLayout.Row::key).toList();
        for (int i = 0; i < keys.size(); i++) {
            if (!keys.get(i).startsWith("h:")) {
                continue;
            }
            assertTrue(i + 1 < keys.size(),
                    keys.get(i) + " is the last row on the shallow page, so its name is over nothing");
            assertFalse(keys.get(i + 1).startsWith("h:"),
                    keys.get(i) + " is followed by another heading, so a section is empty");
        }

        // And the same question for the chapter tab, whose rules section is gated away whole.
        List<ToolsLayout.Action> rows = chapterRows();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).kind() != ToolsLayout.Action.Kind.HEADING) {
                continue;
            }
            assertTrue(i + 1 < rows.size(),
                    rows.get(i).key() + " is the last row on the shallow chapter tab");
            assertNotEquals(ToolsLayout.Action.Kind.HEADING, rows.get(i + 1).kind(),
                    rows.get(i).key() + " is followed by another heading");
        }
    }

    // ------------------------------------------------------------------
    // The fixtures this file reads
    // ------------------------------------------------------------------

    /** The settings page's rows at the depth the flag is at, which is the whole point of asking here. */
    private static List<QuestSettingsLayout.Row> settingsRows() {
        return QuestSettingsLayout.rows(quest(), id -> id, 0);
    }

    /** The Chapter tab's rows, with the group's section present: the group is a case of its own. */
    private static List<ToolsLayout.Action> chapterRows() {
        return ChapterPanelLayout.rows(chapter(),
                new ChapterPanelLayout.GroupInfo("first_light", "First Light", "minecraft:torch", false),
                Set.of());
    }

    /**
     * Every element field row at the depth the flag is at, over <b>all four arms</b>.
     *
     * <p>Union over each element selected in turn, because the fields under the list belong to the selected
     * one: a fixture that selected a single element would report three arms as having no rows at all, and the
     * check that every marked key is a key a form really has would fail on the arm it did not select — which
     * is exactly what it did the first time this ran.
     */
    private static List<String> elementFields() {
        List<String> keys = new ArrayList<>();
        for (String id : List.of("logo", "frame", "caption", "rule")) {
            for (ToolsLayout.Action row : ChapterPanelLayout.rows(chapter(),
                    new ChapterPanelLayout.GroupInfo("first_light", "First Light", "minecraft:torch", false),
                    Set.of(), null, ChapterPanelLayout.Problems.NONE, id)) {
                if (row.key().startsWith(ChapterPanelLayout.ELEMENT_PREFIX)) {
                    keys.add(row.key());
                    // A pair's own key is its left half's; the right half is a field the form offers too.
                    if (row.kind() == ToolsLayout.Action.Kind.PAIR && row.right() != null) {
                        keys.add(row.right().key());
                    }
                }
            }
        }
        return keys;
    }

    /** One link's tree, for the form the depth cut is checked against. */
    private static JsonObject linkFixture() {
        return JsonParser.parseString(
                "{ \"id\": \"gate_hint\", \"quest\": \"the_deep\", \"x\": 336, \"y\": -64 }")
                .getAsJsonObject();
    }

    /**
     * The Book tab's rows, with one palette to offer and the canvas's default surface.
     *
     * <p>One rather than none, and that is the fixture rather than a detail: `appearanceRows` adds no
     * palette section when there are no palettes to list -- a heading over nothing would be the fault it is
     * avoiding -- so a test that passed an empty list would be testing a Book tab the themes screen cannot
     * produce, and would report the palette section as missing at full depth.
     */
    private static List<ToolsLayout.Action> bookRows() {
        return ToolsLayout.rows(false, true, true, true,
                List.of(new ToolsLayout.Palette("high_contrast", "High Contrast")), true,
                CanvasBackground.NONE, true, null);
    }

    /** The heading keys of a tools row list, in order. */
    private static List<String> headings(List<ToolsLayout.Action> rows) {
        List<String> out = new ArrayList<>();
        for (ToolsLayout.Action row : rows) {
            if (row.kind() == ToolsLayout.Action.Kind.HEADING) {
                out.add(row.key());
            }
        }
        return out;
    }

    /** One form's field paths, as a set. */
    private static Set<String> names(List<EditorField> fields) {
        Set<String> out = new TreeSet<>();
        for (EditorField field : fields) {
            out.add(field.path());
        }
        return out;
    }

    /** A kill task, which is the type with the most refinements of the built-ins. */
    private static JsonObject killTaskTree() {
        return JsonParser.parseString("""
                { "type": "tenet:kill", "entity": "minecraft:zombie", "value": 3 }
                """).getAsJsonObject();
    }
}
