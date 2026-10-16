package dev.ellipog.tenet.quest;

import dev.ellipog.tenet.quest.condition.ConditionTypes;
import dev.ellipog.tenet.quest.reward.RewardCommon;
import dev.ellipog.tenet.quest.reward.RewardTypes;
import dev.ellipog.tenet.quest.task.TaskTypes;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor forms: that every registered type has one, that it draws every field the type has, and that
 * the fields which need knowledge offer it.
 *
 * <h2>Why this is a test rather than a convention</h2>
 *
 * <p>Because the fault it catches is silent. A type that grows a field and does not grow its form looks
 * fine -- the field is simply not on the card, and an author wonders where it went; a form that names a
 * field the codec does not take looks fine too, until the edit it sends is refused. Both are one
 * assertion here, over every registered type, so the next type cannot ship with either.
 *
 * <p>The same place is where the other promise lives: a field that is an id the game can list -- a
 * dimension, a biome, an advancement, a statistic -- must be a search rather than a text box. That is the
 * rule the request was about, written down where it can fail a build.
 */
@DisplayName("editor forms")
class EditorSpecsTest {

    /**
     * The fields that are ids, and the controls that are allowed to edit them.
     *
     * <p>A text box is deliberately not among the allowed kinds for any of them: an id you must know by
     * heart is not an editor, and every one of these is a list the client can show.
     */
    private static final Map<String, Set<EditorField.Kind>> IDS_MUST_BE_LISTED = Map.ofEntries(
            Map.entry("item", Set.of(EditorField.Kind.ITEM)),
            Map.entry("icon", Set.of(EditorField.Kind.ITEM, EditorField.Kind.ICON)),
            Map.entry("dimension", Set.of(EditorField.Kind.SEARCH)),
            Map.entry("biome", Set.of(EditorField.Kind.SEARCH)),
            Map.entry("structure", Set.of(EditorField.Kind.SEARCH)),
            Map.entry("advancement", Set.of(EditorField.Kind.SEARCH)),
            Map.entry("stat", Set.of(EditorField.Kind.SEARCH)),
            Map.entry("fluid", Set.of(EditorField.Kind.SEARCH)),
            Map.entry("entity", Set.of(EditorField.Kind.SEARCH)),
            Map.entry("toObserve", Set.of(EditorField.Kind.SEARCH)),
            Map.entry("table", Set.of(EditorField.Kind.TABLE)),
            Map.entry("tag", Set.of(EditorField.Kind.TAG)),
            Map.entry("entityTypeTag", Set.of(EditorField.Kind.TAG)));

    @Test
    @DisplayName("every task type's form draws every field it has, and nothing it does not")
    void taskFormsCoverTheirFields() {
        for (Identifier id : TaskTypes.ids()) {
            Set<String> paths = paths(TaskTypes.editorOf(id));
            Set<String> declared = TaskTypes.fieldsOf(id);

            // The registered set is the type's own fields plus the six every task has, so the form is
            // asked to draw all of them -- with `conditions` exempt, because a list of objects is not a
            // cell and the entry card draws it as its own section (see the note on the line below). The
            // other five ride on the form through COMMON_EDITOR and must be drawn here.
            assertTrue(paths.containsAll(minus(declared, Set.of("conditions"))),
                    id + " declares " + new TreeSet<>(minus(minus(declared, Set.of("conditions")), paths))
                            + ", which its form does not draw");
            // `conditions` is exempt from the flat form on purpose: a list of objects is not a cell,
            // and the entry card draws it as its own section -- see ConditionFormLayout. Exempting it
            // here rather than inventing a control is the honest half of that split; the condition
            // family's own forms are covered by conditionFormsCoverTheirFields below.
            assertTrue(paths.containsAll(minus(TaskCommon.FIELDS, Set.of("conditions"))),
                    id + " has no control for "
                            + new TreeSet<>(minus(minus(TaskCommon.FIELDS, Set.of("conditions")), paths))
                            + "; every task has these");
            Set<String> invented = minus(paths, union(declared, TaskCommon.FIELDS));
            assertTrue(invented.isEmpty(),
                    id + " draws " + invented + ", which its codec does not take");
        }
    }

    @Test
    @DisplayName("every reward type's form draws every field it owns, base mechanics included")
    void rewardFormsCoverTheirFields() {
        for (Identifier id : RewardTypes.ids()) {
            Set<String> paths = paths(RewardTypes.editorOf(id));
            Set<String> declared = RewardTypes.fieldsOf(id);

            // Everything the type itself declares, and everything RewardCommon contributes except `team`,
            // which is an optional boolean rather than a switch -- see RewardTypes.COMMON_EDITOR.
            assertTrue(paths.containsAll(minus(declared, RewardCommon.FIELDS)),
                    id + " declares " + new TreeSet<>(minus(minus(declared, RewardCommon.FIELDS), paths))
                            + ", which its form does not draw");
            assertTrue(paths.containsAll(
                            Set.of("auto", "excludeFromClaimAll", "ignoreRewardBlocking", "disableToast",
                                    "title", "icon")),
                    id + " has no control for the base mechanics");
            Set<String> undrawn = minus(declared, paths);
            // `team` is the panel's; `conditions` is the entry card's own section, as on the task side.
            assertTrue(undrawn.isEmpty() || undrawn.equals(Set.of("team"))
                            || undrawn.equals(Set.of("team", "conditions"))
                            || undrawn.equals(Set.of("conditions")),
                    id + " draws no control for "
                            + new TreeSet<>(minus(undrawn, Set.of("team", "conditions")))
                            + "; only `team` and `conditions` are exempt");
        }
    }

    @Test
    @DisplayName("every condition type's form draws every field it has, and nothing it does not")
    void conditionFormsCoverTheirFields() {
        for (Identifier id : ConditionTypes.ids()) {
            Set<String> paths = paths(ConditionTypes.editorOf(id));
            Set<String> declared = ConditionTypes.fieldsOf(id);

            assertTrue(paths.containsAll(declared),
                    id + " declares " + new TreeSet<>(minus(declared, paths))
                            + ", which its form does not draw");
            Set<String> invented = minus(paths, declared);
            assertTrue(invented.isEmpty(),
                    id + " draws " + invented + ", which its codec does not take");
        }
    }

    @Test
    @DisplayName("a field that is an id the game can list is never a text box")
    void idsAreSearchedRatherThanTyped() {
        for (Identifier id : TaskTypes.ids()) {
            checkIdsAreListed(id, TaskTypes.editorOf(id));
        }
        for (Identifier id : RewardTypes.ids()) {
            checkIdsAreListed(id, RewardTypes.editorOf(id));
        }
        for (Identifier id : ConditionTypes.ids()) {
            checkIdsAreListed(id, ConditionTypes.editorOf(id));
        }
    }

    private static void checkIdsAreListed(Identifier id, List<EditorField> form) {
        for (EditorField field : form) {
            Set<EditorField.Kind> allowed = IDS_MUST_BE_LISTED.get(last(field.path()));
            if (allowed != null) {
                assertTrue(allowed.contains(field.kind()),
                        id + "'s `" + field.path() + "` is edited as " + field.kind()
                                + ", and a field that is an id belongs in " + allowed);
            }
        }
    }

    @Test
    @DisplayName("a number says what it counts")
    void numbersCarryTheirUnits() {
        for (Identifier id : TaskTypes.ids()) {
            assertUnits(id, TaskTypes.editorOf(id));
        }
        for (Identifier id : RewardTypes.ids()) {
            assertUnits(id, RewardTypes.editorOf(id));
        }
        for (Identifier id : ConditionTypes.ids()) {
            assertUnits(id, ConditionTypes.editorOf(id));
        }
    }

    private static void assertUnits(Identifier id, List<EditorField> form) {
        for (EditorField field : form) {
            if (field.path().equals("count") || field.path().equals("amount")) {
                assertFalse(field.unit().isEmpty(),
                        id + "'s `" + field.path() + "` is a bare number, and a bare number "
                                + "makes the author read its neighbours to know what it counts");
            }
        }
    }

    @Test
    @DisplayName("every field of every type says what it does")
    void everyFieldExplainsItself() {
        // The rule the labels' hover rests on. A field with no sentence is a label of one or two words
        // and nothing else -- and a form of switches and steppers reads as a set of guesses without it.
        for (Identifier id : TaskTypes.ids()) {
            assertHints(id, TaskTypes.editorOf(id));
        }
        for (Identifier id : RewardTypes.ids()) {
            assertHints(id, RewardTypes.editorOf(id));
        }
        for (Identifier id : ConditionTypes.ids()) {
            assertHints(id, ConditionTypes.editorOf(id));
        }
    }

    private static void assertHints(Identifier id, List<EditorField> form) {
        for (EditorField field : form) {
            assertFalse(field.hint().isBlank(),
                    id + "'s `" + field.path() + "` has no hint, so hovering its label says nothing "
                            + "about what the field does");
        }
    }

    @Test
    @DisplayName("a type with no form gets one derived from its names, in a stable order")
    void derivationIsStableAndSensible() {
        List<EditorField> derived = EditorSpecs.derive(
                new LinkedHashSet<>(List.of("dimension", "consumeItems", "count", "wobble")));
        assertEquals(List.of("consumeItems", "count", "dimension", "wobble"),
                derived.stream().map(EditorField::path).toList(),
                "sorted, so two openings of the same type agree");

        assertEquals(EditorField.Kind.FLAG, kind(derived, "consumeItems"));
        assertEquals(EditorField.Kind.NUMBER, kind(derived, "count"));
        assertEquals(EditorField.Source.DIMENSION,
                derived.stream().filter(field -> field.path().equals("dimension")).findFirst()
                        .orElseThrow().source(),
                "a name the game can list is searched even when nobody declared the form");
        assertEquals(EditorField.Kind.TEXT, kind(derived, "wobble"),
                "and a name nothing is known about is still editable");

        assertEquals("Only from crafting", EditorSpecs.label("onlyFromCrafting"));
        assertEquals("Block tag", EditorSpecs.label("block_tag"));
        assertEquals("Count", EditorSpecs.label("count"));
        // An enum constant's name is not a codec field's, and it has no lower-case letter to mark where
        // one word ends: the picker's heading is `EditorSpecs.label(pickerSource.name())`, so without this
        // a structure picker was headed "STRUCTURE".
        assertEquals("Structure", EditorSpecs.label("STRUCTURE"));
        assertEquals("Item tag", EditorSpecs.label("ITEM_TAG"));
        assertEquals("Observation target", EditorSpecs.label("OBSERVATION_TARGET"));
    }

    private static EditorField.Kind kind(List<EditorField> fields, String path) {
        return fields.stream().filter(field -> field.path().equals(path)).findFirst()
                .orElseThrow(() -> new AssertionError("no field " + path)).kind();
    }

    private static Set<String> paths(List<EditorField> fields) {
        Set<String> out = new TreeSet<>();
        for (EditorField field : fields) {
            // The codec field names, not the boxes: an item also covers its components, and a triple
            // names the array its boxes index. See EditorField#fieldNames.
            out.addAll(field.fieldNames());
        }
        return out;
    }

    /** The last segment of a path: {@code position.0} is about {@code position}. */
    private static String last(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    private static Set<String> minus(Set<String> from, Set<String> remove) {
        Set<String> out = new TreeSet<>(from);
        out.removeAll(remove);
        return out;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.addAll(b);
        return out;
    }
}
