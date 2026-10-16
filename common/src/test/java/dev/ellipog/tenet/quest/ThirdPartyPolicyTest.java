package dev.ellipog.tenet.quest;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.Problems;
import dev.ellipog.tenet.quest.reward.RewardTypes;
import dev.ellipog.tenet.quest.reward.UnknownReward;
import dev.ellipog.tenet.quest.task.TaskTypes;
import dev.ellipog.tenet.quest.task.UnknownTask;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The policy for task and reward types no build here registers.
 *
 * <h2>What the policy is</h2>
 *
 * <p>An unregistered type decodes to an {@link UnknownTask} or {@link UnknownReward} placeholder
 * that keeps the id, the validator reports it as a <b>warning</b> rather than refusing the file,
 * the task can never satisfy and offers no button, and the reward is never auto-granted — so an
 * automatic path leaves it for the claim rather than marking it collected unpaid. The quest still
 * loads, its siblings still work, and the row says which mod is missing. The mechanism is pinned
 * generically by {@code LegacyPayloadCompatibilityTest}; what is pinned here is that the ids real
 * packs use take that path: {@code eternalcurrencies:currency}, anything under
 * {@code questsadditions:}, {@code quest_loot}, and the crate types a loot table's
 * {@code loot_crate} sub-object would have named.
 *
 * <p>None of those is registered here, and the first assertion in each test says so outright: a
 * future registration would silently change what the test means, from "unknown, kept and
 * reported" to "known, evaluated".
 */
@DisplayName("third-party task and reward types are kept and reported, never refused")
class ThirdPartyPolicyTest {

    /** Type ids real packs use that no build here registers. */
    private static final List<String> THIRD_PARTY_REWARDS = List.of(
            "eternalcurrencies:currency",
            "quest_loot");

    /** Likewise on the task side. */
    private static final List<String> THIRD_PARTY_TASKS = List.of(
            "questsadditions:task",
            "quest_loot");

    private static QuestTask task(String text) {
        DataResult<QuestTask> result =
                TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text));
        return result.result().orElseThrow(() -> new AssertionError(text + " did not decode: "
                + result.error().map(DataResult.Error::message).orElse("no message")));
    }

    private static QuestReward reward(String text) {
        DataResult<QuestReward> result =
                RewardTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text));
        return result.result().orElseThrow(() -> new AssertionError(text + " did not decode: "
                + result.error().map(DataResult.Error::message).orElse("no message")));
    }

    private static Quest quest(String text) {
        DataResult<Quest> result =
                Quest.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(text));
        return result.result().orElseThrow(() -> new AssertionError(text + " did not decode: "
                + result.error().map(DataResult.Error::message).orElse("no message")));
    }

    private static Problems validateQuest(String text) {
        Problems problems = new Problems();
        QuestValidator.validateQuestDocument(Fixtures.document("one.json", text), problems);
        return problems;
    }

    private static DataProblem problemMentioning(Problems problems, String text) {
        return problems.all().stream()
                .filter(problem -> problem.message().contains(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "expected a problem mentioning '" + text + "', got:\n" + problems.all()));
    }

    @Test
    @DisplayName("third-party reward ids are not registered here, so each takes the Unknown path")
    void thirdPartyRewardIdsAreUnknown() {
        for (String id : THIRD_PARTY_REWARDS) {
            assertFalse(RewardTypes.ids().contains(Identifier.parse(id)),
                    id + " is registered now; this test pins the unregistered path");
        }
    }

    @Test
    @DisplayName("third-party task ids are not registered here either")
    void thirdPartyTaskIdsAreUnknown() {
        for (String id : THIRD_PARTY_TASKS) {
            assertFalse(TaskTypes.ids().contains(Identifier.parse(id)),
                    id + " is registered now; this test pins the unregistered path");
        }
    }

    @Test
    @DisplayName("eternalcurrencies:currency decodes to a placeholder that keeps the id")
    void eternalCurrencyDecodesToAPlaceholder() {
        UnknownReward unknown = assertInstanceOf(UnknownReward.class,
                reward("{\"type\": \"eternalcurrencies:currency\", \"amount\": 50}"));

        assertEquals(Identifier.parse("eternalcurrencies:currency"), unknown.type(),
                "the id survives, which is what tells the author which economy is missing");
        assertFalse(unknown.autoGrantable(),
                "an automatic path must leave it for the claim rather than marking it collected"
                        + " while granting nothing");
    }

    @Test
    @DisplayName("a questsadditions task is never satisfied and offers no button")
    void questsAdditionsTaskIsNeverSatisfied() {
        UnknownTask unknown = assertInstanceOf(UnknownTask.class,
                task("{\"type\": \"questsadditions:task\"}"));

        var behaviour = TaskTypes.behaviourOf(unknown)
                .orElseThrow(() -> new AssertionError("an unknown task must answer with a"
                        + " behaviour rather than leaving the engine to its fallback"));
        assertEquals(1, behaviour.required(unknown), "one, so the row reads 'not done'");
        assertEquals(0, behaviour.current(unknown, null),
                "zero always: the quest sits visibly incomplete instead of silently completing");
        assertFalse(behaviour.canSubmitByHand(unknown, false),
                "a Submit button on a task nothing can evaluate is a control that does nothing");
    }

    @Test
    @DisplayName("the validator reports third-party types as warnings, and the file still loads")
    void thirdPartyTypesAreWarnings() {
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "tasks": [{"type": "questsadditions:task"},
                           {"type": "tenet:checkmark", "title": "Did it"}],
                 "rewards": [{"type": "eternalcurrencies:currency", "amount": 50},
                             {"type": "quest_loot"}]}""");

        assertEquals(DataProblem.Severity.WARNING,
                problemMentioning(problems, "unknown quest task type").severity());
        assertEquals(DataProblem.Severity.WARNING,
                problemMentioning(problems, "unknown quest reward type").severity());
        assertFalse(problems.hasErrors(),
                "nothing about third-party types may be fatal, or one missing mod costs the"
                        + " whole file: " + problems.all());
    }

    @Test
    @DisplayName("a quest holding third-party types still decodes, siblings intact")
    void questWithThirdPartyTypesStillDecodes() {
        Quest parsed = quest("""
                {"id": "one", "title": "One",
                 "tasks": [{"type": "questsadditions:task"},
                           {"type": "tenet:checkmark", "title": "Did it"}],
                 "rewards": [{"type": "eternalcurrencies:currency", "amount": 50}]}""");

        assertEquals(2, parsed.tasks().size(), "both tasks survive the unknown one");
        assertInstanceOf(UnknownTask.class, parsed.tasks().get(0));
        assertEquals(1, parsed.rewards().size());
        assertInstanceOf(UnknownReward.class, parsed.rewards().get(0));
    }
}
