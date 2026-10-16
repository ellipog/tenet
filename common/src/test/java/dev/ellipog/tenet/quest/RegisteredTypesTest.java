package dev.ellipog.tenet.quest;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.condition.ConditionTypes;
import dev.ellipog.tenet.quest.condition.QuestCondition;
import dev.ellipog.tenet.quest.reward.RewardTypes;
import dev.ellipog.tenet.quest.task.TaskTypes;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every registered type, through the whole registration-to-file loop.
 *
 * <h2>Why the sweep matters more than a test per type</h2>
 *
 * <p>A type is wired by hand in four places: its record and codec, its registration (fields, icon,
 * behaviour, display, defaults), the dispatch codec's lazily-read type list, and the field names the
 * validator unions. A per-type test would confirm the type its author remembered to test, and the
 * thing that actually goes wrong is a type that was registered without one of the four. This asks the
 * registry itself, so a type added tomorrow is covered the day it registers.
 */
@DisplayName("every registered type")
class RegisteredTypesTest {

    @Test
    @DisplayName("a task type's default tree encodes, decodes, and names its own type")
    void taskDefaultsRoundTrip() {
        assertTrue(TaskTypes.count() >= 10, "the built-in catalogue is expected to be complete here");

        for (Identifier id : TaskTypes.ids()) {
            Optional<JsonObject> tree = TaskTypes.defaultTree(id);
            assertTrue(tree.isPresent(), id + " has no default tree, so the editor could never add it");
            assertTrue(tree.get().has("type"), id + "'s default tree carries no type field");

            QuestTask decoded = TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE, tree.get())
                    .getOrThrow(error -> new AssertionError(id + " default tree did not decode: " + error));
            assertEquals(id, decoded.type(), id + "'s default decoded as another type");
        }
    }

    @Test
    @DisplayName("a reward type's default tree encodes, decodes, and names its own type")
    void rewardDefaultsRoundTrip() {
        for (Identifier id : RewardTypes.ids()) {
            Optional<JsonObject> tree = RewardTypes.defaultTree(id);
            assertTrue(tree.isPresent(), id + " has no default tree, so the editor could never add it");

            QuestReward decoded = RewardTypes.dispatchCodec().parse(JsonOps.INSTANCE, tree.get())
                    .getOrThrow(error -> new AssertionError(id + " default tree did not decode: " + error));
            assertEquals(id, decoded.type(), id + "'s default decoded as another type");
        }
    }

    @Test
    @DisplayName("a condition type's default tree encodes, decodes, and names its own type")
    void conditionDefaultsRoundTrip() {
        assertTrue(ConditionTypes.count() >= 6, "the built-in catalogue is expected to be complete here");

        for (Identifier id : ConditionTypes.ids()) {
            Optional<JsonObject> tree = ConditionTypes.defaultTree(id);
            assertTrue(tree.isPresent(), id + " has no default tree, so a picker could never add it");
            assertTrue(tree.get().has("type"), id + "'s default tree carries no type field");

            QuestCondition decoded = ConditionTypes.dispatchCodec().parse(JsonOps.INSTANCE, tree.get())
                    .getOrThrow(error -> new AssertionError(id + " default tree did not decode: " + error));
            assertEquals(id, decoded.type(), id + "'s default decoded as another type");
        }
    }
}
