package dev.ellipog.tenet.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.ellipog.tenet.quest.TreeRefresh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What one edit owes the tree: the reach every op and every field path maps to.
 *
 * <h2>Why this is a table of answers rather than a smoke test</h2>
 *
 * <p>Because the reach is a claim about what an edit <i>cannot</i> have moved, and a claim like that is
 * wrong silently: a COSMETIC edit that did move a player's progress produces a client showing a count
 * against the wrong row, with nothing on the server side to notice. So every kind is listed with its
 * answer, and the answer that matters most is the expensive one — {@code FULL} is owed only where a row
 * can have moved <b>position</b>, because positions are what stored progress is read by.
 *
 * <p>The switch in {@code EditorOps.reachOf} has no {@code default} arm, so a new kind of op fails to
 * compile until somebody classifies it. This file is the other half of that: the compiler says the list
 * is complete, and these cases say the answers are the ones they should be.
 *
 * <p>Nothing here needs a client or a file: a reach is a pure function of an op.
 */
@DisplayName("what an edit owes the tree")
class EditReachTest {

    private static final JsonPrimitive VALUE = new JsonPrimitive("anything");

    private static TreeRefresh.Touch reach(EditorOp op) {
        return EditorOps.reachOf(op);
    }

    private static TreeRefresh.Touch field(String path) {
        return reach(new EditorOp.SetField("quest", path, VALUE));
    }

    @Test
    @DisplayName("an edit that only moves what a screen draws owes no progress at all")
    void displayEditsAreCosmetic() {
        assertEquals(TreeRefresh.Touch.COSMETIC, reach(new EditorOp.Move("quest", 4, 9)),
                "the canvas drag, which is one op per tick");
        for (String path : List.of("x", "y", "size", "shape", "rotation", "iconScale", "showTitle",
                "icon", "title", "subtitle", "description", "invisible",
                "hideUntilDependenciesVisible", "hideUntilDependenciesComplete", "hideTextUntilComplete",
                "hideDetailsUntilStartable", "invisibleUntilTasks", "hideDependencyLines",
                "dependencyLines")) {
            assertEquals(TreeRefresh.Touch.COSMETIC, field(path), path + " is drawn and nothing else");
        }
        assertEquals(TreeRefresh.Touch.COSMETIC, reach(new EditorOp.SetChapter("title", VALUE)));
        assertEquals(TreeRefresh.Touch.COSMETIC,
                reach(new EditorOp.SetChapter("hideUntilDependenciesComplete", VALUE)),
                "the chapter's own hiding flag: what it changes is derived on the client from the tree");
        assertEquals(TreeRefresh.Touch.COSMETIC, reach(new EditorOp.SetGroup("collapsedByDefault", VALUE)));
        assertEquals(TreeRefresh.Touch.COSMETIC, reach(new EditorOp.SetIndex("bookTitle", VALUE)));
        assertEquals(TreeRefresh.Touch.COSMETIC,
                reach(new EditorOp.SetChapter("disableCanvasLod", VALUE)),
                "the chapter's own LOD answer: what it changes is derived on the client from the tree");
        assertEquals(TreeRefresh.Touch.COSMETIC,
                reach(new EditorOp.SetIndex("disableCanvasLod", VALUE)),
                "and the book's answer with it, for the same reason");
    }

    @Test
    @DisplayName("a path into a task or a reward is never display-only, even when it lands on a label")
    void aRowPathIsNeverDisplayOnly() {
        // The rule this pins: progress is stored against a row's *position*, so a task's title moving
        // into the cosmetic list would be one edit away from a wrong answer -- the classifier reads the
        // whole path rather than its last step for exactly this case.
        assertEquals(TreeRefresh.Touch.CONTENT, field("tasks.0.title"),
                "'title' alone is cosmetic; a title inside a task is a row, and rows are positional");
        assertEquals(TreeRefresh.Touch.CONTENT, field("rewards.2.icon"));
        assertEquals(TreeRefresh.Touch.CONTENT, field("tasks.0.item"));
        assertEquals(TreeRefresh.Touch.CONTENT, field("tasks.0.count"));
        assertEquals(TreeRefresh.Touch.CONTENT, field("tasks.0.conditions.1.stage"));
    }

    @Test
    @DisplayName("a row's own words are display-only: labels name nothing progress is stored against")
    void rowLabelsAreDisplayOnly() {
        // The one carve-out in the rule above, and it is narrow on purpose: exactly `label` and
        // `labelArg` as the third step, never a subtree. A label is the sentence rows draw — keyed
        // by an index no label edit moves — while counts, items and gates move resolved states and
        // keep the CONTENT answer below. Typing a label is the bursty edit this saves from a full
        // reload per tick; the save still validates the write, and a chapter that will not patch
        // falls back to the full reload, so a malformed path can never slip through here.
        for (String path : List.of("tasks.0.label", "tasks.0.labelArg", "rewards.2.label",
                "rewards.2.labelArg")) {
            assertEquals(TreeRefresh.Touch.COSMETIC, field(path), path + " moves words, not states");
        }
        // And the guardrails that keep the carve-out narrow.
        assertEquals(TreeRefresh.Touch.CONTENT, field("tasks.0.label.extra"),
                "deeper than a leaf is malformed, and malformed is CONTENT");
        assertEquals(TreeRefresh.Touch.CONTENT, field("tasks.label"),
                "without an index there is no row, so there is nothing display-only to name");
        assertEquals(TreeRefresh.Touch.CONTENT, field("tasks.0.labelArg.deeper.still"),
                "depth never smuggles a subtree into the cosmetic list");
    }

    @Test
    @DisplayName("a canvas element is decoration, so every edit to one is cosmetic")
    void elementEditsAreCosmetic() {
        // The one place this feature could cost a player something: a CONTENT touch re-resolves every
        // quest's state and sends a delta, and an element holds no progress at all. `SetElement` never
        // reaches `reachOfPath` -- it is classified directly -- so both halves are pinned here.
        assertEquals(TreeRefresh.Touch.COSMETIC, reach(new EditorOp.InsertElement(0, new JsonObject())));
        assertEquals(TreeRefresh.Touch.COSMETIC, reach(new EditorOp.RemoveElement("box")));
        assertEquals(TreeRefresh.Touch.COSMETIC, reach(new EditorOp.SetElement("box", "width", VALUE)));
        assertEquals(TreeRefresh.Touch.COSMETIC, reach(new EditorOp.SetChapter("elements", VALUE)),
                "and the whole list written at once is cosmetic too, which is the case the path rule covers");

        // And the rule stays one-directional: a path that reaches a row is content whatever else it names,
        // because the check for those runs first. Contrived as a path, exact as a precedence.
        assertEquals(TreeRefresh.Touch.CONTENT, field("tasks.0.elements"));
    }

    @Test
    @DisplayName("a quest link is a marker, so every edit to one is cosmetic")
    void linkEditsAreCosmetic() {
        // The one place this feature could cost a player something: a CONTENT touch re-resolves every
        // quest's state and sends a delta, and a link holds no progress at all. `SetLink` never
        // reaches `reachOfPath` -- it is classified directly -- so both halves are pinned here.
        assertEquals(TreeRefresh.Touch.COSMETIC, reach(new EditorOp.InsertLink(0, new JsonObject())));
        assertEquals(TreeRefresh.Touch.COSMETIC, reach(new EditorOp.RemoveLink("gate_hint")));
        assertEquals(TreeRefresh.Touch.COSMETIC, reach(new EditorOp.SetLink("gate_hint", "x", VALUE)));
        assertEquals(TreeRefresh.Touch.COSMETIC, reach(new EditorOp.SetChapter("links", VALUE)),
                "and the whole list written at once is cosmetic too, which is the case the path rule covers");

        // And the rule stays one-directional here as well.
        assertEquals(TreeRefresh.Touch.CONTENT, field("tasks.0.links"));
    }

    @Test
    @DisplayName("an edit that can move a resolved state, but no row's position, is a delta")
    void contentEditsAreDeltas() {
        assertEquals(TreeRefresh.Touch.CONTENT, field("dependsOn"),
                "a prerequisite is an edge, and the states behind it are recomputed per quest id");
        assertEquals(TreeRefresh.Touch.CONTENT, field("id"));
        assertEquals(TreeRefresh.Touch.CONTENT, field("aliases"));
        assertEquals(TreeRefresh.Touch.CONTENT, field("progressionMode"));
        assertEquals(TreeRefresh.Touch.CONTENT, field("prerequisiteMode"));
        assertEquals(TreeRefresh.Touch.CONTENT, field("requiresStage"),
                "a stage gate is read by the engine, unlike the flags that only decide what is drawn");
        assertEquals(TreeRefresh.Touch.CONTENT, reach(new EditorOp.SetChapter("progressionMode", VALUE)));
        assertEquals(TreeRefresh.Touch.CONTENT, reach(new EditorOp.SetChapter("dependsOn", VALUE)),
                "a chapter's own gate moves the states of every quest inside it");
        assertEquals(TreeRefresh.Touch.CONTENT, reach(new EditorOp.SetChapter("completesWhen", VALUE)),
                "and so does what finishes the chapter, since a dependency of it asks for completed");
        assertEquals(TreeRefresh.Touch.CONTENT, reach(new EditorOp.SetChapter("prerequisiteMode", VALUE)));
        assertEquals(TreeRefresh.Touch.CONTENT, reach(new EditorOp.SetChapter("minRequired", VALUE)));
        // Not cosmetic despite moving no stored state, and that is worth a case of its own: the two
        // chapter defaults are resolved per quest by `QuestSync` and sent on the **progress** channel, so a
        // COSMETIC touch would update the tree and leave every client drawing the old flags until something
        // unrelated moved. The delta is what re-sends them.
        assertEquals(TreeRefresh.Touch.CONTENT,
                reach(new EditorOp.SetChapter("defaultHideUntilDependenciesComplete", VALUE)));
        assertEquals(TreeRefresh.Touch.CONTENT,
                reach(new EditorOp.SetChapter("defaultHideUntilDependenciesVisible", VALUE)));
        assertEquals(TreeRefresh.Touch.CONTENT, reach(new EditorOp.SetIndex("stageOverrides", VALUE)));
    }

    @Test
    @DisplayName("a field this build has never heard of is a delta, not a full sync")
    void anUnknownFieldIsAContentEdit() {
        // Safe by construction rather than by being recognised: a delta recomputes every quest's
        // resolved state before sending, so a field whose meaning this class cannot bound is covered
        // anyway. The alternative -- guessing that an unknown field might be structural -- would charge
        // every player a full sync for a field that is almost always a label.
        assertEquals(TreeRefresh.Touch.CONTENT, field("somethingNobodyHasHeardOf"));
        assertEquals(TreeRefresh.Touch.CONTENT, field(""));
        assertEquals(TreeRefresh.Touch.CONTENT, field("tasks"));
    }

    @Test
    @DisplayName("ids moving is a delta: a quest that arrives, leaves or is copied")
    void idEditsAreDeltas() {
        assertEquals(TreeRefresh.Touch.CONTENT, reach(new EditorOp.Create(0, 0)));
        assertEquals(TreeRefresh.Touch.CONTENT, reach(new EditorOp.Duplicate("quest")));
        assertEquals(TreeRefresh.Touch.CONTENT, reach(new EditorOp.Paste(new JsonObject(), 0, 0)),
                "a pasted tree lands under a fresh id, so no existing row moves");
        assertEquals(TreeRefresh.Touch.CONTENT, reach(new EditorOp.Delete("quest")),
                "and a deleted id is named in the delta's removed list rather than inferred from absence");
    }

    @Test
    @DisplayName("a chapter or a group edit moves quests between chapters, not rows within them")
    void structuralEditsAreDeltas() {
        // Cheaper than a cautious reading would have it, and the reason is the same rule: what these
        // change is *which chapter* a quest is in, or which chapters exist -- never the position of a
        // task or a reward inside a quest.
        for (EditorOp op : List.of(
                new EditorOp.MoveChapter("chapter", "group", 0),
                new EditorOp.MoveGroup("group", 1),
                new EditorOp.CreateChapter("group", 0, "chapter", "Chapter"),
                new EditorOp.CreateGroup("group", "Group"),
                new EditorOp.RenameChapter("chapter", "new_chapter", "Chapter"),
                new EditorOp.RenameGroup("group", "new_group", "Group"),
                new EditorOp.DuplicateChapter("chapter", "copy", "Copy"),
                new EditorOp.DuplicateGroup("group", "copy", "Copy"),
                new EditorOp.DeleteChapter("chapter"),
                new EditorOp.DeleteGroup("group"))) {
            assertEquals(TreeRefresh.Touch.CONTENT, reach(op), op.getClass().getSimpleName());
        }
    }

    @Test
    @DisplayName("the full sync is owed by the four edits that can move a row, and by an undo")
    void onlyPositionMovesOweTheLot() {
        assertEquals(TreeRefresh.Touch.FULL, reach(new EditorOp.Insert("quest", "tasks", 0, new JsonObject())));
        assertEquals(TreeRefresh.Touch.FULL, reach(new EditorOp.Remove("quest", "tasks", 1)));
        assertEquals(TreeRefresh.Touch.FULL, reach(new EditorOp.MoveEntry("quest", "rewards", 0, 2)));
        assertEquals(TreeRefresh.Touch.FULL, reach(new EditorOp.Undo()),
                "a chapter's history is a snapshot per step, so a rollback can have moved anything");
        assertEquals(TreeRefresh.Touch.FULL, reach(new EditorOp.Redo()));
    }

    @Test
    @DisplayName("a gesture owes whatever its heaviest element owes")
    void aBatchTakesItsHeaviest() {
        assertEquals(TreeRefresh.Touch.COSMETIC, reach(new EditorOp.Batch(List.of(
                new EditorOp.Move("quest", 1, 1), new EditorOp.SetField("quest", "x", VALUE)))));

        assertEquals(TreeRefresh.Touch.FULL, reach(new EditorOp.Batch(List.of(
                new EditorOp.Move("quest", 1, 1),
                new EditorOp.Insert("quest", "tasks", 0, new JsonObject())))),
                "one insertion inside a gesture of nudges still owes the lot");

        assertEquals(TreeRefresh.Touch.NONE, reach(new EditorOp.Batch(List.of())),
                "a batch of nothing owes nothing, which is what lets it be coalesced harmlessly");
    }

    @Test
    @DisplayName("a targeted refresh names the chapter only chapter-scoped ops reach")
    void refreshChapterNamesChapterScopedOps() {
        assertEquals(java.util.Optional.of("first_steps"),
                EditorOps.refreshChapter(new EditorOp.Move("quest", 1, 1), "first_steps"));
        assertEquals(java.util.Optional.of("first_steps"),
                EditorOps.refreshChapter(new EditorOp.SetField("quest", "title", VALUE), "first_steps"));
        assertEquals(java.util.Optional.of("first_steps"),
                EditorOps.refreshChapter(new EditorOp.SetChapter("title", VALUE), "first_steps"));
        assertEquals(java.util.Optional.of("first_steps"),
                EditorOps.refreshChapter(new EditorOp.SetElement("box", "width", VALUE), "first_steps"));
        assertEquals(java.util.Optional.of("first_steps"),
                EditorOps.refreshChapter(new EditorOp.SetLink("gate", "x", VALUE), "first_steps"));
        assertEquals(java.util.Optional.of("first_steps"),
                EditorOps.refreshChapter(new EditorOp.Batch(List.of(
                        new EditorOp.Move("quest", 1, 1), new EditorOp.SetField("quest", "x", VALUE))),
                        "first_steps"),
                "a gesture of nudges re-reads its one chapter");

        assertEquals(java.util.Optional.empty(),
                EditorOps.refreshChapter(new EditorOp.SetGroup("icon", VALUE), "first_steps"),
                "a group is not read from any chapter");
        assertEquals(java.util.Optional.empty(),
                EditorOps.refreshChapter(new EditorOp.SetIndex("bookTitle", VALUE), "first_steps"),
                "and neither is the book");
        assertEquals(java.util.Optional.empty(),
                EditorOps.refreshChapter(new EditorOp.Batch(List.of(
                        new EditorOp.Move("quest", 1, 1),
                        new EditorOp.SetGroup("icon", VALUE))), "first_steps"),
                "one group write in the gesture means the whole tree");
        assertEquals(java.util.Optional.empty(),
                EditorOps.refreshChapter(new EditorOp.Move("quest", 1, 1), ""),
                "and a missing chapter names nothing to re-read");
        assertEquals(java.util.Optional.empty(),
                EditorOps.refreshChapter(new EditorOp.Move("quest", 1, 1), null));
    }
}
