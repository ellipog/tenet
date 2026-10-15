package dev.ellipog.tenet.quest.reward;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.net.RewardToastPayload;
import dev.ellipog.tenet.quest.QuestReward;
import dev.ellipog.tenet.quest.QuestText;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Set;
import java.util.function.Function;

/**
 * Show the player a message.
 *
 * <pre>{@code { "type": "tenet:toast", "description": "The vault is open." } }</pre>
 *
 * <p>the legacy {@code toast} reward: claiming it pops a toast with the reward's description
 * (ATM10: 1 use, with an empty description). The description is a {@link QuestText} so a pack
 * shipping translations gets them; an empty description shows the generic toast sentence rather
 * than nothing, because a reward that grants silence reads as broken.
 *
 * <p>The grant is chat plus the book's own stack, for the same reason the overflow notice is both:
 * the HUD is not drawn behind an open screen, so the sentence has to reach the book as well as the
 * chat. A reward with {@code disableToast} shows nothing — the grant is the message, so quieting
 * it quiets everything.
 */
public record ToastReward(RewardCommon common, QuestText description) implements QuestReward {

    public static final Identifier TYPE = Identifier.fromNamespaceAndPath(Tenet.MOD_ID, "toast");

    public static final Set<String> FIELDS = Set.of("description");

    public static final MapCodec<ToastReward> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            RewardCommon.MAP_CODEC.forGetter(ToastReward::common),
            QuestText.CODEC.optionalFieldOf("description", QuestText.EMPTY).forGetter(ToastReward::description)
    ).apply(instance, ToastReward::new));

    @Override
    public Identifier type() {
        return TYPE;
    }

    public static final RewardBehaviour<ToastReward> BEHAVIOUR = (reward, context) -> {
        if (reward.common().disableToast()) {
            return;
        }
        QuestText description = reward.description();
        Component text = description.component();
        if (text.getString().isEmpty()) {
            description = QuestText.translatable("tenet.reward.toast.empty",
                    "A message with nothing in it");
            text = description.component();
        }
        context.player().sendSystemMessage(text);
        dev.ellipog.armature.api.net.ArmatureNetwork.sendToPlayer(context.player(),
                new RewardToastPayload(description.value(), description.translatable(),
                        description.fallback().orElse("")));
    };

    public static final Function<ToastReward, RewardDisplay> DISPLAY = reward -> {
        String text = reward.description().translatable()
                ? reward.description().fallback().orElse(reward.description().value())
                : reward.description().value();
        if (text.isEmpty()) {
            text = "…";
        }
        return RewardDisplay.ofTranslatableText("tenet.reward.toast.message", "Show a message: " + text,
                text, 1);
    };
}
