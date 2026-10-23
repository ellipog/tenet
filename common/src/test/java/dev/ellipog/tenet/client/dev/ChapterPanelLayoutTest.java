package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ellipog.tenet.client.DevMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Chapter tab's rows and header, from a chapter's own tree.
 *
 * <h2>What is asked here, and what cannot be</h2>
 *
 * <p>The things the redesigned tab promises: every section in order, the row keys still the dotted
 * paths the commits go to, the shortened labels actually short, the header reading the chapter's own
 * name, and the icon exposed as the object the panel resolves. All of it is answerable with a parsed
 * JSON object and no client -- which is why the rows are built by a game-free class and the drawing
 * is not.
 *
 * <h2>And why every test here runs at full depth</h2>
 *
 * <p>Because they assert the <b>complete</b> model: the Rules section is one of them, and Normal mode
 * gates it away -- a set of keys could not, since a chapter's rules are keyed by the same field names the
 * quest's own rules use. So the depth is turned on for the class, which is the shape a test of a model
 * takes when part of the model is behind a preference; what Normal hides, and that it hides it, is
 * {@code AdvancedTest}'s question rather than this file's.
 */
@DisplayName("the chapter panel's rows")
class ChapterPanelLayoutTest {

    @BeforeEach
    void atFullDepth() {
        DevMode.setAdvanced(true);
    }

    @AfterEach
    void forgetTheDepth() {
        // A static flag: a test that leaves it set changes the next class to run.
        DevMode.reset();
    }


    /** A chapter with one of everything the panel edits. */
    private static JsonObject chapter() {
        return JsonParser.parseString("""
                {
                  "id": "first_steps",
                  "title": "First Steps",
                  "subtitle": "Five quests, no tricks",
                  "description": ["One.", "Two.", "Three."],
                  "icon": { "item": "minecraft:crafting_table" },
                  "aliases": ["start", "the_beginning"],
                  "progressionMode": "linear",
                  "defaultConsumeItems": true,
                  "defaultPrerequisiteMode": "one_completed",
                  "quests": ["punch_a_tree.json", "make_a_table.json", "stone_tools"]
                }
                """).getAsJsonObject();
    }

    private static ToolsLayout.Action row(List<ToolsLayout.Action> rows, String key) {
        return rows.stream().filter(candidate -> candidate.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no row keyed " + key + " in " + rows.size() + " rows"));
    }

    @Test
    @DisplayName("the pack's faults come first, carry the count, and cannot be folded away")
    void thePacksFaultsComeFirst() {
        // **The one thing on this tab that is not this chapter.** A chapter's copy can have arrived
        // perfectly and the pack still be broken -- a dangling dependency, a cycle between files, an id in
        // two chapters -- and those are the faults no single file can show. The report used to be a toast
        // that scrolled away, so an author who missed it went on working on a pack whose quests were
        // quietly absent from the tree.
        ChapterPanelLayout.Problems problems = new ChapterPanelLayout.Problems(3, List.of(
                "a.json:4:9: error: no quest with id or alias \"gone\" exists",
                "b.json:1:1: error: circular dependency: p -> q -> p"));
        List<ToolsLayout.Action> rows = ChapterPanelLayout.rows(chapter(), null, Set.of(), null, problems);

        assertEquals(ChapterPanelLayout.PROBLEMS, rows.get(0).key(),
                "above Identity, because it is about the pack rather than about this chapter");
        assertTrue(rows.get(0).isHeading(), "a heading, so the panel draws it as one");
        // The label is resolved in the layout rather than at the draw site, because a heading's label is
        // drawn through `Labels.of` with **no values** and a `%s` left in it would be drawn as one. So what
        // is asserted is the count having reached the drawn string, and the label not being the bare key --
        // both of which hold whether or not a language is installed, which the English sentence alone would
        // not: a test that read "Problems (3)" would pass or fail on what ran before it in the JVM.
        assertTrue(rows.get(0).label().contains("3"),
                "the count reached the label: " + rows.get(0).label());
        assertFalse(rows.get(0).label().equals("tenet.dev.chapter.problems"),
                "and the label is not the bare key, which is what a no-values resolution would have drawn"
                        + " if the layout had left the placeholder in it: " + rows.get(0).label());

        // And it does not fold. A folded fault is a hidden one, which is the whole of what it exists to
        // stop -- the arrangement `SHAPE_SECTION` already has. One predicate decides both the rule the
        // panel draws and the widget the screen builds, so this assertion covers both.
        assertFalse(ToolsLayout.folds(ChapterPanelLayout.PROBLEMS), "a fault is not a category to put away");

        // The lines, then how many the payload could not carry -- the count travels separately from the
        // list precisely so a cut-short report says what it is not showing.
        assertEquals(List.of(ChapterPanelLayout.VALUE_PREFIX + "problem:0",
                        ChapterPanelLayout.VALUE_PREFIX + "problem:1",
                        ChapterPanelLayout.VALUE_PREFIX + "problems:more"),
                rows.stream().map(ToolsLayout.Action::key)
                        .filter(key -> key.startsWith(ChapterPanelLayout.VALUE_PREFIX + "problem"))
                        .toList());
        assertEquals("b.json:1:1: error: circular dependency: p -> q -> p",
                row(rows, ChapterPanelLayout.VALUE_PREFIX + "problem:1").value(),
                "the server's own sentence, not a rewording of it");
    }

    @Test
    @DisplayName("nothing wrong, and nothing heard, are both no section at all")
    void noProblemsIsNoSection() {
        // The two states draw the same, deliberately: the panel has nothing to say in either case, and a
        // heading over an empty list would be a line about a load the player was never told about.
        assertFalse(ChapterPanelLayout.rows(chapter(), Set.of()).stream()
                        .anyMatch(row -> row.key().equals(ChapterPanelLayout.PROBLEMS)),
                "the rows built without a report carry no such heading");
        assertFalse(ChapterPanelLayout.rows(chapter(), null, Set.of(), null, ChapterPanelLayout.Problems.NONE)
                        .stream().anyMatch(row -> row.key().equals(ChapterPanelLayout.PROBLEMS)),
                "and neither does an explicit empty one");
        // And a report whose lines were all cut still shows: the count is the fault, not the list.
        assertTrue(ChapterPanelLayout.rows(chapter(), null, Set.of(), null,
                        new ChapterPanelLayout.Problems(4, List.of())).stream()
                        .anyMatch(row -> row.key().equals(ChapterPanelLayout.PROBLEMS)),
                "a count with no lines is still a broken pack");
    }

    @Test
    @DisplayName("the problems heading names its two gestures")
    void theProblemsHeadingExplainsItself() {
        // The heading's press prints the report to the chat rather than folding anything away,
        // and a fault's press copies its file — neither is discoverable from a truncated row,
        // so the heading carries the sentence the rows cannot.
        assertEquals("tenet.dev.chapter.help.problems",
                ChapterPanelLayout.help(ChapterPanelLayout.PROBLEMS));
    }

    @Test
    @DisplayName("the sections are there, in order, and every key is the path it commits to")
    void sectionsInOrder() {
        List<ToolsLayout.Action> rows = ChapterPanelLayout.rows(chapter(), Set.of());

        assertEquals(List.of(ChapterPanelLayout.IDENTITY, ChapterPanelLayout.RULES,
                        ChapterPanelLayout.LINKS, ChapterPanelLayout.QUESTS),
                rows.stream().filter(ToolsLayout.Action::isHeading).map(ToolsLayout.Action::key).toList(),
                "one heading per section, in the order an author reads them");
        assertEquals(List.of("title", "subtitle", ChapterPanelLayout.ICON,
                        ChapterPanelLayout.VALUE_PREFIX + "description", "aliases", "tags",
                        "progressionMode", "defaultConsumeItems", "defaultFlexibleProgress",
                        "defaultMinWidth", "defaultSequentialTasks", "autofocus",
                        "defaultPrerequisiteMode", "autoClaim",
                        "prerequisiteMode", "minRequired", "dependsOn", "completesWhen",
                        "hideUntilDependenciesComplete", "alwaysInvisible",
                        "disableCanvasLod",
                        "defaultHideUntilDependenciesComplete",
                        "defaultHideUntilDependenciesVisible",
                        "dependencyStyle.form", "dependencyStyle.arrowHead", "dependencyStyle.arrowPlace",
                        "dependencyStyle.arrowDensity", "dependencyStyle.dash", "dependencyStyle.weight",
                        dev.ellipog.tenet.client.dev.LinkPanelLayout.ADD_LINK),
                rows.stream().filter(row -> !row.isHeading()
                                && !row.key().startsWith(ChapterPanelLayout.VALUE_PREFIX + "quest:"))
                        .map(ToolsLayout.Action::key).toList(),
                "the paths the ops go to, under the headings that own them");
    }

    @Test
    @DisplayName("the labels are short, because the dock is narrow and that is what truncated them")
    void labelsAreShort() {
        // The report: in a 180-pixel panel, side-by-side rows spent 96 pixels on the control and the
        // label truncated -- "Default Prerequisite Mode" became "Default Prereq...". The mode is the
        // main fix; these names are the other half, and a name that grows back to a sentence undoes it.
        List<ToolsLayout.Action> rows = ChapterPanelLayout.rows(chapter(), Set.of());

        assertEquals("tenet.dev.chapter.progression", row(rows, "progressionMode").label());
        assertEquals("tenet.dev.chapter.prerequisite", row(rows, "defaultPrerequisiteMode").label());
        assertEquals("tenet.dev.chapter.auto_claim", row(rows, "autoClaim").label());
        assertEquals("tenet.dev.chapter.gate_mode", row(rows, "prerequisiteMode").label());
        assertEquals("tenet.dev.chapter.gate_min", row(rows, "minRequired").label());
        assertEquals("tenet.dev.chapter.depends_on", row(rows, "dependsOn").label());
        assertEquals("tenet.dev.chapter.completes_when", row(rows, "completesWhen").label());
        assertEquals("tenet.dev.chapter.hide_until_deps",
                row(rows, "hideUntilDependenciesComplete").label());
        assertEquals("tenet.dev.chapter.lod_off", row(rows, "disableCanvasLod").label());
        assertEquals("tenet.dev.chapter.aliases", row(rows, "aliases").label());
        assertEquals("tenet.dev.chapter.default_hide_deps_complete",
                row(rows, "defaultHideUntilDependenciesComplete").label());
        assertEquals("tenet.dev.chapter.default_hide_deps_visible",
                row(rows, "defaultHideUntilDependenciesVisible").label());
        assertEquals("tenet.dev.chapter.line_form", row(rows, "dependencyStyle.form").label());
        assertEquals("tenet.dev.chapter.line_head", row(rows, "dependencyStyle.arrowHead").label());
        assertEquals("tenet.dev.chapter.line_place", row(rows, "dependencyStyle.arrowPlace").label());
        assertEquals("tenet.dev.chapter.line_density", row(rows, "dependencyStyle.arrowDensity").label());
        assertEquals("tenet.dev.chapter.line_pattern", row(rows, "dependencyStyle.dash").label());
        assertEquals("tenet.dev.chapter.line_weight", row(rows, "dependencyStyle.weight").label());
    }

    @Test
    @DisplayName("a switch row names the setting, and its button carries the state")
    void theToggleNamesTheSettingAndTheButtonSaysItsState() {
        // The tools panel's own convention, which the chapter's two flags follow now: the row's *label* is
        // the name of the setting -- it does not change when the flag does -- and the button beside it says
        // on or off. The label used to be the state ("Consume items · on"), which meant the name of the
        // flag was nowhere on screen and a rebuild was the only way the row could be read.
        List<ToolsLayout.Action> on = ChapterPanelLayout.rows(chapter(), Set.of());
        assertEquals("tenet.dev.chapter.consume", row(on, "defaultConsumeItems").label());
        assertEquals(ToolsLayout.ON, row(on, "defaultConsumeItems").buttonLabel());

        JsonObject off = chapter();
        off.addProperty("defaultConsumeItems", false);
        ToolsLayout.Action offRow = row(ChapterPanelLayout.rows(off, Set.of()), "defaultConsumeItems");
        assertEquals("tenet.dev.chapter.consume", offRow.label(), "the name is the name either way");
        assertEquals(ToolsLayout.OFF, offRow.buttonLabel(), "and the state is the button's");
    }

    // ------------------------------------------------------------------
    // The cycling rows
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a cycling row carries the value in force, and an absent axis reads as unset")
    void theChoiceRowsCarryTheirValues() {
        List<ToolsLayout.Action> rows = ChapterPanelLayout.rows(chapter(), Set.of());
        assertEquals("linear", row(rows, "progressionMode").value());
        assertEquals("one_completed", row(rows, "defaultPrerequisiteMode").value());
        assertEquals("", row(rows, "dependencyStyle.form").value(),
                "a chapter with no dependencyStyle is unset on every axis");

        JsonObject styled = chapter();
        styled.add("dependencyStyle", JsonParser.parseString(
                "{\"form\":\"curved\",\"bend\":0.4}").getAsJsonObject());
        assertEquals("curved", row(ChapterPanelLayout.rows(styled, Set.of()),
                "dependencyStyle.form").value());
        assertEquals("", row(ChapterPanelLayout.rows(styled, Set.of()),
                "dependencyStyle.arrowHead").value(),
                "an axis the object does not name is still unset");
    }

    @Test
    @DisplayName("a legacy arrows value still reads on the arrow rows it used to mean, and editing clears it")
    void aLegacyArrowsValueIsReadAndRetired() {
        // Chapters written before the axes were split carry one `arrows` value that meant the glyph and
        // the placement at once. The picker shows what it means rather than "Default", and the first
        // edit that speaks the current vocabulary retires the old spelling -- otherwise a new axis
        // returned to Default would fall back to the legacy value the author thought they replaced.
        JsonObject legacy = chapter();
        legacy.add("dependencyStyle",
                JsonParser.parseString("{\"arrows\":\"many\"}").getAsJsonObject());

        List<ToolsLayout.Action> rows = ChapterPanelLayout.rows(legacy, Set.of());
        assertEquals("chevron", row(rows, "dependencyStyle.arrowHead").value());
        assertEquals("stream", row(rows, "dependencyStyle.arrowPlace").value());
        assertEquals("", row(rows, "dependencyStyle.arrowDensity").value(),
                "the legacy stream's spacing has no density name, so this row stays unset");

        ChapterPanelLayout.Edit edit =
                ChapterPanelLayout.choiceEdit(legacy, ChapterPanelLayout.LINE_ARROW_HEAD, "triangle");
        assertEquals(Set.of("arrowHead"), edit.value().getAsJsonObject().keySet(),
                "the edit writes the new axis and removes the legacy one: "
                        + edit.value().getAsJsonObject().keySet());
    }

    @Test
    @DisplayName("a chooser row offers the closed set, the unset state included, in one list")
    void thePickerOffersTheWholeSet() {
        // The row is the drawer's own chooser now, so there is no step to assert and no wrap to get wrong:
        // the menu offers `choiceValues` in this order and the author picks from it. That is the whole of
        // what the arrows used to make a puzzle of -- a value four positions away needed four presses, and
        // the order was the only thing that said where the presses were going.
        assertEquals(List.of("", "flexible", "linear"),
                ChapterPanelLayout.choiceValues(ChapterPanelLayout.PROGRESSION));
        assertEquals("Default (pack setting)",
                ChapterPanelLayout.choiceLabel(ChapterPanelLayout.AUTO_CLAIM, ""),
                "and the unset state names what it defers to, like every other row");
        assertEquals("curved", ChapterPanelLayout.choiceLabel(ChapterPanelLayout.LINE_FORM, "curved"));
        for (ChapterPanelLayout.Choice choice : ChapterPanelLayout.CHOICES) {
            List<String> values = ChapterPanelLayout.choiceValues(choice);
            assertTrue(values.get(0).isEmpty(),
                    () -> "the unset state is not the menu's first entry: " + choice.key());
            assertTrue(ChapterPanelLayout.choiceLabel(choice, "")
                            .contains(choice.fallback().replace('_', ' ')),
                    () -> "the unset entry does not name the value it defers to: " + choice.key()
                            + " says " + choice.fallback() + " and draws "
                            + ChapterPanelLayout.choiceLabel(choice, ""));
            assertTrue(ChapterPanelLayout.isChoiceKey(choice.key()),
                    () -> "a row in the list that the rows do not treat as a choice: " + choice.key());
        }
    }

    @Test
    @DisplayName("the unset state is labelled with the fallback it means, not a bare Default")
    void theUnsetStateNamesItsFallback() {
        assertEquals("Default (all completed)",
                ChapterPanelLayout.choiceLabel(ChapterPanelLayout.PREREQUISITE, ""));
        assertEquals("Default (all completed)",
                ChapterPanelLayout.choiceLabel(ChapterPanelLayout.GATE_MODE, ""),
                "the gate's own mode falls back to the reading that asks the most");
        assertEquals("Default (chamfered)",
                ChapterPanelLayout.choiceLabel(ChapterPanelLayout.LINE_FORM, ""));
        assertEquals("dashed",
                ChapterPanelLayout.choiceLabel(ChapterPanelLayout.LINE_DASH, "dashed"));
    }

    // ------------------------------------------------------------------
    // The chapter's own gate
    // ------------------------------------------------------------------

    /** A chapter that waits on another, finishes on a quest, and hides until then. */
    private static JsonObject gated() {
        return JsonParser.parseString("""
                {
                  "id": "second_steps",
                  "title": "Second Steps",
                  "prerequisiteMode": "one_started",
                  "minRequired": 1,
                  "dependsOn": ["first_steps", "prologue"],
                  "completesWhen": ["the_festival"],
                  "hideUntilDependenciesComplete": true,
                  "defaultHideUntilDependenciesComplete": true,
                  "defaultHideUntilDependenciesVisible": true,
                  "quests": []
                }
                """).getAsJsonObject();
    }

    @Test
    @DisplayName("the gate rows show the file's own values, and a chapter that says nothing shows nothing")
    void theGateRowsReadTheFile() {
        // Every row here prints what the *file* says rather than the codec's default, which is the rule
        // the rest of this panel follows: a row that printed the default would make "unset" and "set to
        // the default" look alike, and the first is what an author resets to.
        List<ToolsLayout.Action> rows = ChapterPanelLayout.rows(gated(), Set.of());

        assertEquals("one_started", row(rows, "prerequisiteMode").value());
        assertEquals("1", row(rows, "minRequired").value());
        assertEquals("first_steps, prologue", row(rows, "dependsOn").value(),
                "the list reads as a comma list, the shape the aliases row above it uses");
        assertEquals("the_festival", row(rows, "completesWhen").value());
        assertEquals(ToolsLayout.ON, row(rows, "hideUntilDependenciesComplete").buttonLabel(),
                "a switch row's state is its button's, like every other flag on this tab");

        List<ToolsLayout.Action> plain = ChapterPanelLayout.rows(chapter(), Set.of());
        assertEquals("", row(plain, "prerequisiteMode").value(), "nothing said, nothing shown");
        assertEquals("", row(plain, "minRequired").value());
        assertEquals("", row(plain, "dependsOn").value());
        assertEquals("", row(plain, "completesWhen").value());
        assertEquals(ToolsLayout.OFF, row(plain, "hideUntilDependenciesComplete").buttonLabel(),
                "and shown-until-open is what a chapter that says nothing does");
        assertEquals(ToolsLayout.ON, row(rows, "defaultHideUntilDependenciesComplete").buttonLabel(),
                "the quests' own default is a switch too, and it reads the file");
        assertEquals(ToolsLayout.ON, row(rows, "defaultHideUntilDependenciesVisible").buttonLabel());
        assertEquals(ToolsLayout.OFF, row(plain, "defaultHideUntilDependenciesComplete").buttonLabel(),
                "a chapter that says nothing hides nothing: the pre-existing behaviour, unchanged");
        assertEquals(ToolsLayout.OFF, row(plain, "defaultHideUntilDependenciesVisible").buttonLabel());
    }

    @Test
    @DisplayName("the always-invisible switch and the tags row read the file")
    void alwaysInvisibleAndTagsReadTheFile() {
        List<ToolsLayout.Action> rows = ChapterPanelLayout.rows(JsonParser.parseString("""
                {
                  "id": "second_steps",
                  "title": "Second Steps",
                  "alwaysInvisible": true,
                  "tags": ["village", "early"],
                  "quests": []
                }
                """).getAsJsonObject(), Set.of());

        assertEquals(ToolsLayout.ON, row(rows, "alwaysInvisible").buttonLabel(),
                "a switch row's state is its button's, like every other flag on this tab");
        assertEquals("village, early", row(rows, "tags").value(),
                "the list reads as a comma list, the shape the aliases row above it uses");

        List<ToolsLayout.Action> plain = ChapterPanelLayout.rows(chapter(), Set.of());
        assertEquals(ToolsLayout.OFF, row(plain, "alwaysInvisible").buttonLabel(),
                "shown is what a chapter that says nothing does");
        assertEquals("", row(plain, "tags").value(), "nothing said, nothing shown");
    }

    @Test
    @DisplayName("the LOD switch reads the file, and silence reads as tiered")
    void lodSwitchReadsTheFile() {
        List<ToolsLayout.Action> rows = ChapterPanelLayout.rows(JsonParser.parseString("""
                {
                  "id": "second_steps",
                  "title": "Second Steps",
                  "disableCanvasLod": true,
                  "quests": []
                }
                """).getAsJsonObject(), Set.of());

        assertEquals(ToolsLayout.ON, row(rows, "disableCanvasLod").buttonLabel(),
                "a switch row's state is its button's, like every other flag on this tab");

        List<ToolsLayout.Action> plain = ChapterPanelLayout.rows(chapter(), Set.of());
        assertEquals(ToolsLayout.OFF, row(plain, "disableCanvasLod").buttonLabel(),
                "tiered is what a chapter that says nothing draws");
    }

    @Test
    @DisplayName("a gate rule writes itself, and the unset choice removes the field")
    void theGateRuleWritesItself() {
        // The same write every other plain rule on this tab makes -- the value, or null for unset -- which
        // is what keeps the chooser's own edit path from needing a branch per row. The two list fields are
        // text rows and go through the screen's comma-list commit, which is asserted where that lives.
        ChapterPanelLayout.Edit set = ChapterPanelLayout.choiceEdit(gated(),
                ChapterPanelLayout.GATE_MODE, "all_completed");
        assertEquals("prerequisiteMode", set.path());
        assertEquals("all_completed", set.value().getAsString());

        ChapterPanelLayout.Edit clear = ChapterPanelLayout.choiceEdit(gated(),
                ChapterPanelLayout.GATE_MODE, "");
        assertEquals("prerequisiteMode", clear.path());
        assertEquals(null, clear.value(), "unset removes the field rather than writing the default");
    }

    @Test
    @DisplayName("a rule writes itself, and the unset choice removes the field")
    void aRuleWritesItself() {
        ChapterPanelLayout.Edit toDefault =
                ChapterPanelLayout.choiceEdit(chapter(), ChapterPanelLayout.PROGRESSION, "");
        assertEquals("progressionMode", toDefault.path());
        assertNull(toDefault.value(), "the unset choice removes the field rather than writing a default");

        ChapterPanelLayout.Edit linear =
                ChapterPanelLayout.choiceEdit(chapter(), ChapterPanelLayout.PROGRESSION, "linear");
        assertEquals("linear", linear.value().getAsString());
    }

    @Test
    @DisplayName("a line axis rewrites dependencyStyle and keeps every axis and the bend it did not touch")
    void aLineAxisRewritesTheObject() {
        JsonObject styled = chapter();
        styled.add("dependencyStyle", JsonParser.parseString(
                "{\"form\":\"curved\",\"bend\":0.4}").getAsJsonObject());

        ChapterPanelLayout.Edit arrows =
                ChapterPanelLayout.choiceEdit(styled, ChapterPanelLayout.LINE_ARROW_HEAD, "triangle");
        assertEquals("dependencyStyle", arrows.path());
        assertEquals(Set.of("form", "bend", "arrowHead"), arrows.value().getAsJsonObject().keySet());
        assertEquals("curved", arrows.value().getAsJsonObject().get("form").getAsString(),
                "an axis the step did not touch keeps the file's own value");
        assertEquals(0.4, arrows.value().getAsJsonObject().get("bend").getAsDouble(),
                "and the numeric bend, which has no picker, survives");

        ChapterPanelLayout.Edit removed =
                ChapterPanelLayout.choiceEdit(styled, ChapterPanelLayout.LINE_FORM, "");
        assertEquals(Set.of("bend"), removed.value().getAsJsonObject().keySet());
    }

    @Test
    @DisplayName("clearing the last axis drops dependencyStyle entirely")
    void theLastAxisDropsTheObject() {
        JsonObject styled = chapter();
        styled.add("dependencyStyle",
                JsonParser.parseString("{\"form\":\"curved\"}").getAsJsonObject());

        ChapterPanelLayout.Edit edit =
                ChapterPanelLayout.choiceEdit(styled, ChapterPanelLayout.LINE_FORM, "");
        assertEquals("dependencyStyle", edit.path());
        assertNull(edit.value(), "an empty style is a key that pins nothing");
    }

    @Test
    @DisplayName("the description is counted in paragraphs, and says it is edited in the file")
    void theDescriptionIsCounted() {
        // The count is substituted into the key at build time, so the test asserts the sentence the
        // player reads -- `TestLanguage` installs the mod's own file, which is where the words live.
        assertEquals("3 paragraphs \u00b7 edited in the file",
                row(ChapterPanelLayout.rows(chapter(), Set.of()),
                        ChapterPanelLayout.VALUE_PREFIX + "description").value());

        JsonObject one = chapter();
        one.add("description", JsonParser.parseString("[\"Only one.\"]"));
        assertEquals("1 paragraph \u00b7 edited in the file",
                row(ChapterPanelLayout.rows(one, Set.of()),
                        ChapterPanelLayout.VALUE_PREFIX + "description").value());

        JsonObject none = chapter();
        none.remove("description");
        assertEquals("None \u00b7 edited in the file",
                row(ChapterPanelLayout.rows(none, Set.of()),
                        ChapterPanelLayout.VALUE_PREFIX + "description").value());
    }

    @Test
    @DisplayName("the quest list is in the authored order, numbered, and its ids lose the .json")
    void theQuestListIsInOrder() {
        List<ToolsLayout.Action> rows = ChapterPanelLayout.rows(chapter(), Set.of());

        List<String> quests = rows.stream()
                .filter(row -> row.key().startsWith(ChapterPanelLayout.VALUE_PREFIX + "quest:"))
                .map(row -> row.label() + " " + row.value())
                .toList();
        assertEquals(List.of("1. punch_a_tree", "2. make_a_table", "3. stone_tools"), quests,
                "the order is the chapter's own, and it is the progression in a linear chapter");
    }

    @Test
    @DisplayName("folding a section takes its rows and leaves its heading")
    void foldingLeavesTheHeading() {
        List<ToolsLayout.Action> folded = ChapterPanelLayout.rows(chapter(), Set.of(ChapterPanelLayout.RULES));

        assertTrue(folded.stream().anyMatch(row -> row.key().equals(ChapterPanelLayout.RULES)),
                "the folded section's heading is still drawn, so it can be unfolded");
        assertFalse(folded.stream().anyMatch(row -> row.key().equals("progressionMode")),
                "and its rows are gone rather than hidden behind a flag");
    }

    @Test
    @DisplayName("the header reads the chapter's name, and is empty for a copy that has not arrived")
    void theHeaderReadsTheChapter() {
        ChapterPanelLayout.Header header = ChapterPanelLayout.header(chapter());
        assertEquals("First Steps", header.title());
        assertEquals("Five quests, no tricks", header.subtitle());

        ChapterPanelLayout.Header missing = ChapterPanelLayout.header(null);
        assertEquals("", missing.title());
        assertEquals("", missing.subtitle(), "an empty header, not a trip, is the answer for no copy");
    }

    @Test
    @DisplayName("without edit mode the tab names the state, and the instruction names the button")
    void notEditing() {
        List<ToolsLayout.Action> rows = ChapterPanelLayout.notEditing();

        assertEquals(1, rows.size(), "one line, and nothing to press: " + rows);
        assertEquals(ToolsLayout.Action.Kind.VALUE, rows.get(0).kind(),
                "a value row, so the screen builds no widget that could look live and do nothing");
        assertEquals("tenet.dev.chapter.chapter", rows.get(0).label(),
                "the row's label is a key; the screen resolves it");
        assertEquals("Press Edit to change it", rows.get(0).value(),
                "the value is resolved where it is built, so the test reads the sentence");
    }

    @Test
    @DisplayName("the pack's problems are listed without edit mode, where the reload's toast points")
    void notEditingListsProblems() {
        List<ToolsLayout.Action> rows = ChapterPanelLayout.notEditing(
                new ChapterPanelLayout.Problems(1, List.of("a.json:1:1: error: dangling")));

        assertEquals(3, rows.size(), "the line, the heading and the fault: " + rows);
        assertEquals(ChapterPanelLayout.PROBLEMS, rows.get(1).key());
        assertEquals("a.json:1:1: error: dangling", rows.get(2).value());
    }

    @Test
    @DisplayName("the two empty states are different facts, and neither is blank")
    void emptyStatesAreDistinct() {
        // Not editing: nothing was ever asked for, and the tab says what to do about it.
        String notEditing = ChapterPanelLayout.notEditing().get(0).value();
        // In edit mode with no copy: the request is in flight, or the server said no.
        List<ToolsLayout.Action> waiting = ChapterPanelLayout.rows(new JsonObject(), Set.of());
        assertEquals(1, waiting.size());
        String missing = waiting.get(0).value();

        assertTrue(missing.contains("has not arrived"), missing);
        assertNotEquals(missing, notEditing,
                "a state the player can fix is not a state the server owes them");
        assertFalse(notEditing.isBlank());
        assertFalse(missing.isBlank());
    }

    @Test
    @DisplayName("a refusal is the missing-copy line with the server's own words after it")
    void refusalsAreShown() {
        String refused = ChapterPanelLayout.rows(new JsonObject(), null, Set.of(),
                "no chapter called \"x\"").get(0).value();

        assertTrue(refused.contains("has not arrived"), refused);
        assertTrue(refused.contains("no chapter called \"x\""),
                "the server's own reason has to survive into the line: " + refused);
    }

    @Test
    @DisplayName("the icon is exposed as the file's own object, and an absent icon is an empty one")
    void theIconIsExposed() {
        assertEquals("minecraft:crafting_table",
                ChapterPanelLayout.icon(chapter()).get("item").getAsString());

        JsonObject none = chapter();
        none.remove("icon");
        assertTrue(ChapterPanelLayout.icon(none).isEmpty(), "no icon is an empty object, not a null");
        assertTrue(ChapterPanelLayout.icon(null).isEmpty());
    }

    // ------------------------------------------------------------------
    // The chapter's group
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the group is its own section at the end, with every row keyed to the group's file")
    void theGroupSectionIsItsOwn() {
        // The report this is the answer to: a group's row showed a chapter's icon, and there was nowhere
        // to give the group one of its own. The section is last -- the chapter is the panel, the group is
        // the shelf it sits on -- and each key carries the prefix that sends the commit to group.json
        // rather than to the chapter's file.
        ChapterPanelLayout.GroupInfo group =
                new ChapterPanelLayout.GroupInfo("getting_started", "Getting Started",
                        "minecraft:anvil", true);
        List<ToolsLayout.Action> rows = ChapterPanelLayout.rows(chapter(), group, Set.of());

        assertEquals(List.of(ChapterPanelLayout.IDENTITY, ChapterPanelLayout.RULES,
                        ChapterPanelLayout.GROUP, ChapterPanelLayout.LINKS, ChapterPanelLayout.QUESTS),
                rows.stream().filter(ToolsLayout.Action::isHeading).map(ToolsLayout.Action::key).toList(),
                "the group's section sits between the rules and the quest list, ahead of the scroll");
        assertEquals(List.of("group.title", "group.icon.item", "group.collapsedByDefault"),
                rows.stream()
                        .filter(row -> row.key().startsWith(ChapterPanelLayout.GROUP_PREFIX))
                        .map(ToolsLayout.Action::key).toList(),
                "title, icon and the collapsed flag, all pointed at the group's own file");
        assertEquals("Getting Started", row(rows, "group.title").value());
        assertEquals("minecraft:anvil", row(rows, "group.icon.item").value(),
                "the authored icon, which is what tells the row apart from the sidebar's fallback");
        assertEquals("tenet.dev.chapter.collapsed", row(rows, "group.collapsedByDefault").label());
        assertEquals(ToolsLayout.ON, row(rows, "group.collapsedByDefault").buttonLabel(),
                "and the flag itself is the button's, as every switch in this drawer is");
    }

    @Test
    @DisplayName("a chapter with no group gets no Group section at all")
    void noGroupMeansNoSection() {
        // An ungrouped chapter, or a server that does not describe groups: `rows(chapter, folded)` is
        // that case, and it is the flat book this panel has always drawn.
        assertFalse(ChapterPanelLayout.rows(chapter(), Set.of()).stream()
                        .anyMatch(row -> row.key().startsWith(ChapterPanelLayout.GROUP_PREFIX)),
                "no group rows without a group");

        ChapterPanelLayout.GroupInfo inherited =
                new ChapterPanelLayout.GroupInfo("getting_started", "Getting Started", "", false);
        assertEquals("", row(ChapterPanelLayout.rows(chapter(), inherited, Set.of()),
                        "group.icon.item").value(),
                "a group with no icon of its own says so with an empty value, not the chapter's");
        assertEquals("tenet.dev.chapter.collapsed",
                row(ChapterPanelLayout.rows(chapter(), inherited, Set.of()),
                "group.collapsedByDefault").label());
        assertEquals(ToolsLayout.OFF,
                row(ChapterPanelLayout.rows(chapter(), inherited, Set.of()),
                "group.collapsedByDefault").buttonLabel(),
                "an inherited group that starts open says so on the button");
    }

    @Test
    @DisplayName("the icon row names whichever arm the chapter wears, not just items")
    void iconRowNamesEveryArm() {
        assertEquals("minecraft:crafting_table",
                row(ChapterPanelLayout.rows(chapter(), Set.of()), ChapterPanelLayout.ICON).value(),
                "the item arm, as before");
        assertEquals("my_pack:textures/gui/emblem.png",
                row(ChapterPanelLayout.rows(withIcon("\"texture\": \"my_pack:textures/gui/emblem.png\""),
                        Set.of()), ChapterPanelLayout.ICON).value(),
                "a texture chapter no longer reads as icon-less");
        assertEquals("my_pack:block/glyph",
                row(ChapterPanelLayout.rows(withIcon("\"sprite\": \"my_pack:block/glyph\""),
                        Set.of()), ChapterPanelLayout.ICON).value(),
                "and neither does a sprite one");
        assertEquals("minecraft:creeper",
                row(ChapterPanelLayout.rows(withIcon("\"entity\": \"minecraft:creeper\""),
                        Set.of()), ChapterPanelLayout.ICON).value(),
                "an entity chapter names the mob, drawn as its egg elsewhere");
    }

    /** The fixture chapter wearing one raw icon arm instead of its item. */
    private static JsonObject withIcon(String arm) {
        JsonObject icon = JsonParser.parseString("{ " + arm + " }").getAsJsonObject();
        JsonObject copy = chapter().deepCopy();
        copy.add("icon", icon);
        return copy;
    }
}
