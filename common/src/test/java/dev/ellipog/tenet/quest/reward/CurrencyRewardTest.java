package dev.ellipog.tenet.quest.reward;

import dev.ellipog.tenet.quest.QuestReward;
import net.minecraft.server.level.ServerPlayer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The currency reward: its provider hook, and what a grant does with one.
 *
 * <p>T28 of the migration work: the legacy format pays currency through an economy hook rather than
 * granting an item, and so does this. Each test leaves the hook as it found it — the provider is
 * one global, so a fake left behind would pay every later test's claims.
 */
@DisplayName("the currency reward")
class CurrencyRewardTest {

    private static CurrencyReward reward(int amount) {
        return new CurrencyReward(RewardCommon.DEFAULT, amount);
    }

    private static RewardContext context() {
        return new RewardContext(null, null, UUID.randomUUID(), "quest", "chapter", List.of(), null);
    }

    /** The behaviour the engine reads: the active provider, through the type's wrapper. */
    private static RewardBehaviour<QuestReward> behaviourOf(CurrencyReward reward) {
        return RewardTypes.behaviourOf(reward)
                .orElseThrow(() -> new AssertionError("tenet:currency is not a registered type"));
    }

    /** Runs {@code work} with this fake as the economy, then puts the hook back as it was. */
    private static void withProvider(CurrencyReward.CurrencyProvider fake, Runnable work) {
        Optional<CurrencyReward.CurrencyProvider> before =
                CurrencyReward.CurrencyProviders.provider();
        CurrencyReward.CurrencyProviders.setActive(fake);
        try {
            work.run();
        }
        finally {
            if (before.isPresent()) {
                CurrencyReward.CurrencyProviders.setActive(before.get());
            }
            else {
                CurrencyReward.CurrencyProviders.clear();
            }
        }
    }

    @Test
    @DisplayName("a registered provider is paid when the engine grants")
    void aProviderIsPaid() {
        List<Integer> paid = new java.util.ArrayList<>();
        CurrencyReward.CurrencyProvider fake = new CurrencyReward.CurrencyProvider() {
            @Override
            public String name() {
                return "test-bank";
            }

            @Override
            public void give(ServerPlayer player, int amount) {
                paid.add(amount);
            }
        };

        withProvider(fake, () -> assertDoesNotThrow(
                () -> behaviourOf(reward(50)).grant(reward(50), context())));
        assertEquals(List.of(50), paid, "the provider was paid the reward's amount");
    }

    @Test
    @DisplayName("with no provider a grant pays nothing and never crashes the claim")
    void noProviderPaysNothing() {
        Optional<CurrencyReward.CurrencyProvider> before =
                CurrencyReward.CurrencyProviders.provider();
        CurrencyReward.CurrencyProviders.clear();
        try {
            assertDoesNotThrow(() -> behaviourOf(reward(50)).grant(reward(50), context()));
        }
        finally {
            before.ifPresent(CurrencyReward.CurrencyProviders::setActive);
        }
        assertEquals(before.isPresent(),
                CurrencyReward.CurrencyProviders.provider().isPresent(),
                "the hook is as it was found");
    }

    @Test
    @DisplayName("a provider that throws grants nothing, and stays registered")
    void aThrowingProviderGrantsNothing() {
        CurrencyReward.CurrencyProvider bad = new CurrencyReward.CurrencyProvider() {
            @Override
            public String name() {
                return "test-broken-bank";
            }

            @Override
            public void give(ServerPlayer player, int amount) {
                throw new IllegalStateException("an economy bug");
            }
        };

        withProvider(bad, () -> {
            assertDoesNotThrow(() -> behaviourOf(reward(50)).grant(reward(50), context()));
            assertTrue(CurrencyReward.CurrencyProviders.provider().isPresent(),
                    "the provider stays registered: the next claim tries again rather than going quiet");
        });
    }

    @Test
    @DisplayName("a currency row names its amount")
    void aCurrencyRowNamesItsAmount() {
        RewardDisplay display = RewardTypes.displayOf(reward(50));
        assertEquals("tenet.reward.currency", display.label());
        assertEquals(50, display.count());
    }
}
