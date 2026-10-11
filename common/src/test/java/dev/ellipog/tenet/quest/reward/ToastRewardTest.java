package dev.ellipog.tenet.quest.reward;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.ellipog.tenet.quest.QuestReward;
import dev.ellipog.tenet.quest.QuestText;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The toast reward and the command reward's success line.
 *
 * <p>T18 of the migration work: the legacy {@code toast} reward shows its description when
 * claimed, and {@code feedback_message} on a command reward shows when the command runs. Both
 * travel as a {@link QuestText} so a pack shipping translations gets them.
 */
@DisplayName("the toast reward and the command feedback line")
class ToastRewardTest {

    private static QuestReward reward(String text) {
        return RewardTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .getOrThrow(error -> new AssertionError(text + " did not decode: " + error));
    }

    @Test
    @DisplayName("a toast reward decodes its description, defaulting to empty")
    void toastDescriptionDecodes() {
        var parsed = assertInstanceOf(ToastReward.class,
                reward("{\"type\": \"tenet:toast\", \"description\": \"The vault is open.\"}"));
        assertEquals("The vault is open.", parsed.description().value());

        var empty = assertInstanceOf(ToastReward.class,
                reward("{\"type\": \"tenet:toast\"}"));
        assertEquals(QuestText.EMPTY, empty.description(),
                "absent means empty, which the grant reads as the generic sentence");
    }

    @Test
    @DisplayName("a toast description accepts the translation-key shape")
    void toastDescriptionTranslates() {
        var parsed = assertInstanceOf(ToastReward.class,
                reward("{\"type\": \"tenet:toast\","
                        + " \"description\": {\"translate\": \"pack.toast.vault\","
                        + " \"fallback\": \"The vault is open.\"}}"));
        assertTrue(parsed.description().translatable());
        assertEquals("The vault is open.", parsed.description().fallback().orElseThrow());
    }

    @Test
    @DisplayName("a toast row names its message")
    void toastRowNamesItsMessage() {
        var parsed = assertInstanceOf(ToastReward.class,
                reward("{\"type\": \"tenet:toast\", \"description\": \"The vault is open.\"}"));
        RewardDisplay display = RewardTypes.displayOf(parsed);
        assertEquals("tenet.reward.toast.message", display.label());
        assertEquals("The vault is open.", display.labelArg());
    }

    @Test
    @DisplayName("a command reward decodes its feedback line, defaulting to absent")
    void commandFeedbackDecodes() {
        var parsed = assertInstanceOf(CommandReward.class,
                reward("{\"type\": \"tenet:command\", \"command\": \"say hello\","
                        + " \"feedbackMessage\": \"Well done.\"}"));
        assertEquals("Well done.", parsed.feedbackMessage().orElseThrow().value());

        var silent = assertInstanceOf(CommandReward.class,
                reward("{\"type\": \"tenet:command\", \"command\": \"say hello\"}"));
        assertEquals(Optional.empty(), silent.feedbackMessage(),
                "absent means nothing extra is shown");
    }

    @Test
    @DisplayName("old command files without a feedback line still read")
    void oldCommandFilesStillRead() {
        var parsed = assertInstanceOf(CommandReward.class,
                reward("{\"type\": \"tenet:command\", \"command\": \"say hello\","
                        + " \"permissionLevel\": 2, \"silent\": true}"));
        assertEquals(Optional.empty(), parsed.feedbackMessage());
        assertTrue(parsed.silent());
    }
}
