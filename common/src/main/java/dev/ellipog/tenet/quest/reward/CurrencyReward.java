package dev.ellipog.tenet.quest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.QuestReward;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Give the player currency — whatever the installed currency mod calls money.
 *
 * <pre>{@code { "type": "tenet:currency", "amount": 50 } }</pre>
 *
 * <p>The legacy {@code currency} reward, which pays through FTBLibrary's currency hook rather
 * than granting an item: the amount is the only field, and the coin it names belongs to whichever
 * currency mod is installed. Tenet does the same through {@link CurrencyProviders}: a currency mod
 * (or a script) registers its paying half, and this reward calls it. With nothing registered the
 * grant warns once in the log and pays nothing — a pack whose economy mod is missing keeps its
 * quests, the way a pack whose custom handler is missing keeps its own.
 */
public record CurrencyReward(RewardCommon common, int amount) implements QuestReward {

    public static final Identifier TYPE = Identifier.fromNamespaceAndPath(Tenet.MOD_ID, "currency");

    public static final Set<String> FIELDS = Set.of("amount");

    public static final MapCodec<CurrencyReward> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            RewardCommon.MAP_CODEC.forGetter(CurrencyReward::common),
            Codec.intRange(1, 1_000_000).fieldOf("amount").forGetter(CurrencyReward::amount)
    ).apply(instance, CurrencyReward::new));

    @Override
    public Identifier type() {
        return TYPE;
    }

    public static final RewardBehaviour<CurrencyReward> BEHAVIOUR = (reward, context) -> {
        Optional<CurrencyProvider> provider = CurrencyProviders.provider();
        if (provider.isEmpty()) {
            // Not an error: a pack may pay in a currency whose mod is not installed, and the
            // quest still completes. The message says exactly that so nobody hunts a broken file.
            Constants.LOG.warn("currency reward for {} has no provider registered (a mod or "
                    + "script that pays currency is not loaded); it granted nothing", reward.amount());
            return;
        }
        // A provider that throws grants nothing, and the claim continues: the alternative is an
        // economy bug crashing the claim, and grants are rare enough that every throw logs.
        try {
            provider.get().give(context.player(), reward.amount());
        }
        catch (RuntimeException | Error thrown) {
            Constants.LOG.warn("currency provider \"{}\" threw while granting {}; it granted "
                    + "nothing ({})", provider.get().name(), reward.amount(), thrown.toString());
        }
    };

    public static final Function<CurrencyReward, RewardDisplay> DISPLAY = reward ->
            RewardDisplay.ofTranslatableText("tenet.reward.currency", reward.amount() + " coins",
                    String.valueOf(reward.amount()), reward.amount());

    /**
     * The paying half a currency mod provides.
     *
     * <p>The hook covers quest payouts only: giving. Whatever wider economy a currency mod runs —
     * balances, prices, shops — stays on its own API, which is where a pack already reads it. A
     * quest never takes currency, so there is nothing here to take with.
     */
    public interface CurrencyProvider {

        /** A brief name for logs and diagnostics: whose money this is. */
        String name();

        /** Pays the player. Runs on the server thread, inside the claim that granted it. */
        void give(ServerPlayer player, int amount);
    }

    /**
     * The one active currency provider.
     *
     * <p>One, because the legacy format pays through one hook too: a quest names an amount, and the coin
     * it names belongs to the economy the pack installed. A second registration replaces the
     * first with a warning, for the same reason two economies cannot both own one payout.
     */
    public static final class CurrencyProviders {

        private CurrencyProviders() {
        }

        private static volatile CurrencyProvider ACTIVE;

        /**
         * Offers this provider as the pack's economy. Called from a currency mod's construction
         * or a script's init.
         */
        public static void setActive(CurrencyProvider provider) {
            CurrencyProvider previous = ACTIVE;
            if (previous != null) {
                Constants.LOG.warn("overriding the currency provider: {} -> {}",
                        previous.name(), provider.name());
            }
            ACTIVE = java.util.Objects.requireNonNull(provider, "provider");
        }

        /** The pack's economy, or empty when no currency mod is loaded. */
        public static Optional<CurrencyProvider> provider() {
            return Optional.ofNullable(ACTIVE);
        }

        /**
         * Forgets the provider.
         *
         * <p>Called before a script reload, so an economy whose script was deleted does not go on
         * being paid for the rest of the session.
         */
        public static void clear() {
            ACTIVE = null;
        }
    }
}
