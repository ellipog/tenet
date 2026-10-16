package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A reward-level message for the book's own stack.
 *
 * <p>The server already says the same sentence in chat; this carries it into the book, where the
 * HUD (and the chat behind the screen) cannot be read. The same routing the overflow and summary
 * notices use, for the same reason — see {@code QuestNotifier} for the three sinks completions
 * choose between. This one needs only the book: chat covers the player outside it.
 *
 * <p>The text travels as a {@code QuestText} in three parts rather than as a {@link Component}:
 * a component codec touches the registries (hover events carry stacks), which unit tests cannot
 * encode without a bootstrapped game. The client rebuilds the component, so a translatable
 * description still resolves in the client's own language with the fallback the author shipped.
 *
 * @param value        the literal text, or the translation key when {@code translatable}
 * @param translatable whether {@code value} is a key to look up rather than text to show
 * @param fallback     the English text when {@code value} has no translation; empty for none
 */
public record RewardToastPayload(String value, boolean translatable, String fallback)
        implements CustomPacketPayload {

    public RewardToastPayload {
        value = value == null ? "" : value;
        fallback = fallback == null ? "" : fallback;
    }

    /** The component the book draws, resolved in the client's own language. */
    public Component message() {
        if (!translatable) {
            return Component.literal(value);
        }
        return fallback.isEmpty()
                ? Component.translatable(value)
                : Component.translatableWithFallback(value, fallback);
    }

    public static final CustomPacketPayload.Type<RewardToastPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("tenet", "reward_toast"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RewardToastPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, RewardToastPayload::value,
                    ByteBufCodecs.BOOL, RewardToastPayload::translatable,
                    ByteBufCodecs.STRING_UTF8, RewardToastPayload::fallback,
                    RewardToastPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
