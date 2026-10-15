package dev.ellipog.tenet.client.dev;

import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestReward;
import dev.ellipog.tenet.quest.reward.RewardAutoClaim;
import dev.ellipog.tenet.quest.reward.RewardCommon;
import dev.ellipog.tenet.quest.reward.RewardTypes;

import net.minecraft.resources.Identifier;

import dev.ellipog.tenet.client.DevMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a folded entry offers, for every reward type this build registers.
 *
 * <h2>The fault this file exists for, and why it walks the registry</h2>
 *
 * <p>An entry's fold had two cases: an item, and a nested table. Every other type — experience,
 * command, advancement, stage, custom — folded onto eighteen pixels of nothing, so those fields could
 * not be set from the table editor at all. The fields existed, the loader read them and the grant obeyed
 * them; only the panel could not reach them, and nothing failed loudly, which is why it survived.
 *
 * <p>A hand-written switch per type is what the panel wanted — each type's fields are genuinely
 * different, and a generic form drew an item's count in the same shape as a command's permission level.
 * The price of hand-writing it is that the next field added to a type is a field somebody has to
 * remember to draw. So these tests do not check a list of controls against a written-out expectation:
 * they walk {@link RewardTypes} and ask whether every field it declares has somewhere to be edited.
 * That is the drift guard the hand-written switch needs.
 */
@DisplayName("a folded entry's own fields")
class TableRowFieldsTest {

    /**
     * Full depth, because this file's two coverage tests are about the <b>whole</b> fold.
     *
     * <p>{@code everyDeclaredFieldIsReachable} walks the registry and asserts that every field a reward
     * declares has a control that writes it -- which is a statement about the type's form, not about a
     * depth. Normal mode hides the refinements ({@code randomBonus}, {@code permissionLevel} and the rest;
     * see {@code Advanced}), so the walk runs at full depth, and what Normal hides is asserted in
     * {@code AdvancedTest} instead.
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

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    /**
     * The fields no fold draws, each for a reason of its own.
     *
     * <p>Deliberate omissions rather than gaps, and named here so the walk below cannot quietly grow one:
     */
    private static final Set<String> NOT_A_CONTROL = Set.of(
            // Written by the dispatch codec, not by a person.
            "type",
            // A reward's inline table body: the panels edit tables that live in files, and an inline one
            // is shown on the card and replaced from the browser.
            "inline",
            // A tri-state whose third value is "the questline's default", which a two-state switch cannot
            // express. The panel's own field list is where it is set -- the same reason the reward
            // registry's own form leaves it out.
            "team",
            // Refused on a table entry by the validator: a roll hands entries out, so a gate here would
            // be validated and then ignored.
            "conditions",
            // The item picker owns the data of the stack it picked.
            "components",
            // Which table an entry rolls. A named one is chosen in the browser rather than typed, and the
            // fold's own control is the chip that opens it.
            "table",
            // Words a reward answers to in lookups by tag. A fold's text box writes a string where the
            // format holds a list, which is the corruption the inspector's own rows refuse by the same
            // rule -- so a table entry's tags are file-only, and a quest's own reward sets them on
            // its card, whose commit splits the comma list.
            "tags");

    /** One instance of a registered type: its own defaults, encoded and decoded back. */
    private static QuestReward instance(Identifier id) {
        var tree = RewardTypes.defaultTree(id);
        assertTrue(tree.isPresent(), id + " has no default tree, so it could not be added to a table");
        return RewardTypes.dispatchCodec().parse(JsonOps.INSTANCE, tree.get()).getOrThrow();
    }

    /** A reward type this build has no form for: what an addon registers. */
    private record AddonReward() implements QuestReward {

        @Override
        public Identifier type() {
            return Identifier.fromNamespaceAndPath("addon", "thing");
        }

        @Override
        public RewardCommon common() {
            return RewardCommon.DEFAULT;
        }
    }

    private static List<TableRowFields.Control> controls(QuestReward reward) {
        return TableRowFields.lines(reward).stream()
                .flatMap(line -> line.controls().stream())
                .toList();
    }

    private static Set<String> drawnFields(QuestReward reward) {
        return controls(reward).stream()
                .map(TableRowFields.Control::field)
                .filter(field -> !field.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("every registered type draws a form rather than an empty band")
    void everyRegisteredTypeHasControls() {
        Set<Identifier> ids = RewardTypes.ids();
        assertFalse(ids.isEmpty(), "the registry must not be empty for this to mean anything");

        for (Identifier id : ids) {
            List<TableRowFields.Line> lines = TableRowFields.lines(instance(id));

            assertFalse(lines.isEmpty(), id + " folds onto nothing, which is the fault this replaced");
            assertEquals(lines.size(), TableRowFields.lineCount(instance(id)),
                    id + ": the line count the layout is given must be the lines the drawing produces");
            int total = 0;
            for (TableRowFields.Line line : lines) {
                assertFalse(line.controls().isEmpty(), id + " has an empty line: a gap in the band");
                assertTrue(line.controls().size() <= 2,
                        id + " packs " + line.controls().size() + " controls onto one line");
                total += line.controls().size();
            }
            // The six base mechanics ride on every type, so no form can be smaller than their lines --
            // which is also what makes an addon's type reachable: the switches at least are always there.
            assertTrue(total >= 6, id + " draws only " + total + " control(s), so its own fields are lost");
        }
    }

    @Test
    @DisplayName("every field a type declares has a control that writes it")
    void everyDeclaredFieldIsReachable() {
        for (Identifier id : RewardTypes.ids()) {
            Set<String> drawn = drawnFields(instance(id));

            for (String field : RewardTypes.fieldsOf(id)) {
                if (NOT_A_CONTROL.contains(field)) {
                    continue;
                }
                assertTrue(drawn.contains(field), id + " declares \"" + field
                        + "\" and no fold offers it, so the table editor cannot set it. Drawn: " + drawn);
            }
        }
    }

    @Test
    @DisplayName("no fold offers a field its type does not have")
    void noControlWritesAFieldTheTypeLacks() {
        // The other direction. A control naming a field the codec does not know is a control that writes
        // an unknown member, which the validator then refuses at the author's next save -- from a press
        // that looked like it worked.
        for (Identifier id : RewardTypes.ids()) {
            Set<String> declared = RewardTypes.fieldsOf(id);
            for (String field : drawnFields(instance(id))) {
                assertTrue(declared.contains(field),
                        id + " offers \"" + field + "\", which its codec does not declare. Declared: "
                                + declared);
            }
        }
    }

    @Test
    @DisplayName("a type this build has no form for gets the raw row rather than nothing")
    void anUnknownTypeFallsBackToItsJSON() {
        List<TableRowFields.Line> lines = TableRowFields.lines(new AddonReward());

        // The raw row, and then the base mechanics like every other type: an addon's reward still says
        // when it is given and whether Claim all may take it, and those six switches are the panel's
        // own knowledge rather than the type's.
        assertEquals(5, lines.size(), "the raw row, then the mechanics on four lines");
        assertEquals(1, lines.get(0).controls().size());
        assertEquals(TableRowFields.Kind.RAW, lines.get(0).controls().get(0).kind(),
                "an addon's type is editable the day it registers, or the panel is a wall to it");

        List<TableRowFields.Control> all = controls(new AddonReward());
        assertEquals(7, all.size(), "the raw row and the six base mechanics");
        for (String field : List.of("auto", "excludeFromClaimAll", "ignoreRewardBlocking", "disableToast",
                "title", "icon")) {
            assertTrue(all.stream().anyMatch(control -> field.equals(control.field())),
                    "an addon's reward must still be able to say what " + field + " is");
        }
    }

    @Test
    @DisplayName("the auto cycle offers the words the format accepts, in order")
    void theAutoCycleMatchesTheFormat() {
        // Two descriptions of one ring would drift: the card draws this field as a cycling choice whose
        // options come from the registry, and the fold cycles its own list. So the two are compared.
        var auto = RewardTypes.editorOf(Identifier.fromNamespaceAndPath("tenet", "item")).stream()
                .filter(field -> "auto".equals(field.path()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the reward form no longer declares `auto`"));

        assertEquals(auto.options(), TableRowFields.AUTO_VALUES,
                "the fold's cycle and the registry's own options must be one ring");
    }

    @Test
    @DisplayName("every word a file can carry is a word the cycle can reach")
    void theAutoCycleReachesEveryWordTheFormatAccepts() {
        // The fault this pins: the ring was three of the five words, hand-written, so a reward carrying
        // `no_toast` -- which the shipped `the_scarecrows_blessing` does -- drew as a chip the cycle could
        // not name, and a press on it answered indexOf(-1). The field came back as `default`, and a reward
        // the author had set to give itself away silently started announcing itself. So: reachability, and
        // the reading half of it -- a word the ring writes has to be one the loader reads back.
        for (RewardAutoClaim mode : RewardAutoClaim.values()) {
            String word = mode.name().toLowerCase(java.util.Locale.ROOT);

            assertTrue(TableRowFields.AUTO_VALUES.contains(word),
                    "the cycle cannot name `" + word + "`, so a press on it would rewrite the field");
            assertEquals(java.util.Optional.of(mode), RewardAutoClaim.byName(word),
                    "`" + word + "` is in the ring but not in the vocabulary the loader reads");
        }
        assertEquals(RewardAutoClaim.values().length, TableRowFields.AUTO_VALUES.size(),
                "and the ring holds all five, not three of them");
        assertEquals("default", TableRowFields.AUTO_VALUES.get(0),
                "the ring starts at the absent value, so a blank field steps to a word that means something");
    }

    @Test
    @DisplayName("a cycling control with no value says the word that absence means")
    void anAbsentChoiceReadsAsItsDefault() {
        // An `auto` that declares nothing is not a missing value: it is the field saying "default", the
        // word the questline's own setting is applied to. Drawn as an empty box with an options mark it read
        // as a broken control — a blank box beside "Levels" and beside "Quiet" — which is how it came back
        // from a screenshot. The word comes from the ring, so it cannot drift from what the cycle offers.
        String absent = TableRowFields.choiceText("");
        String ringFirst = TableRowFields.AUTO_VALUES.get(0);

        assertTrue(absent.toLowerCase(java.util.Locale.ROOT).contains(ringFirst),
                "absence shows the ring's first word, prettified: " + absent);
        assertEquals(absent, TableRowFields.choiceText(null), "and no value at all reads the same");
        assertEquals(dev.ellipog.tenet.quest.EditorSpecs.label("enabled"),
                TableRowFields.choiceText("enabled"), "a declared word is shown as itself");
        assertEquals(dev.ellipog.tenet.quest.EditorSpecs.label("disabled"),
                TableRowFields.choiceText("disabled"));
    }

    @Test
    @DisplayName("a wide control takes its own line, and two narrow ones share one")
    void controlsArePackedByWidth() {
        // An item's id is a wide control, so it cannot share: half a fold line is not enough to read an
        // id in. The count beside it is a stepper, which can.
        QuestReward item = instance(Identifier.fromNamespaceAndPath("tenet", "item"));
        List<TableRowFields.Line> lines = TableRowFields.lines(item);

        TableRowFields.Line first = lines.get(0);
        assertEquals(1, first.controls().size());
        assertEquals(TableRowFields.Kind.ITEM, first.controls().get(0).kind(),
                "the item's id box is a line of its own");

        // And every line that holds two holds two controls that may share: no line mixes a wide control
        // in with a neighbour.
        for (TableRowFields.Line line : lines) {
            if (line.controls().size() == 2) {
                for (TableRowFields.Control control : line.controls()) {
                    assertTrue(switch (control.kind()) {
                        case FLAG, CHOICE, INT, DECIMAL -> true;
                        case ITEM, TEXT, TABLE_OPEN, RAW -> false;
                    }, control.field() + " is a wide control sharing a line");
                }
            }
        }
    }

    @Test
    @DisplayName("the six base mechanics are on every type, in the registry's own words")
    void baseMechanicsRideOnEveryType() {
        for (Identifier id : RewardTypes.ids()) {
            Set<String> drawn = drawnFields(instance(id));

            assertTrue(drawn.contains("auto"), id + " cannot say when it is given");
            assertTrue(drawn.contains("excludeFromClaimAll"), id + " cannot be kept out of Claim all");
            assertTrue(drawn.contains("ignoreRewardBlocking"), id + " cannot be exempted from blocking");
            assertTrue(drawn.contains("disableToast"), id + " cannot be quieted");
            assertTrue(drawn.contains("title"), id + " cannot be renamed");
            assertTrue(drawn.contains("icon"), id + " cannot be re-pictured");
        }
    }
}
