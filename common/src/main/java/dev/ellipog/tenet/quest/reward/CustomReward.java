package dev.ellipog.tenet.quest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.QuestReward;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A reward whose effect is somebody else's code.
 *
 * <pre>{@code { "type": "tenet:custom", "id": "my_pack:unlock_the_vault" } }</pre>
 *
 * <p>The id is required, as a custom task's is: a reward has no position to fall back on that an
 * author could name, and an anonymous handler is one nobody can point at in a message. (A custom
 * <i>task</i> cannot do without one either -- its handler is what measures it, and nothing in the engine
 * can advance a task from outside -- so both halves of a quest name their handler.) Java addons register
 * through {@link CustomRewards#register}; KubeJS scripts configure the same registry through the
 * integration, which is how a pack gets a reward no reward type anticipated.
 */
public record CustomReward(RewardCommon common, String id) implements QuestReward {

    public static final Identifier TYPE = Identifier.fromNamespaceAndPath(Tenet.MOD_ID, "custom");

    public static final Set<String> FIELDS = Set.of("id");

    public static final MapCodec<CustomReward> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            RewardCommon.MAP_CODEC.forGetter(CustomReward::common),
            Codec.STRING.fieldOf("id").forGetter(CustomReward::id)
    ).apply(instance, CustomReward::new));

    @Override
    public Identifier type() {
        return TYPE;
    }

    public static final RewardBehaviour<CustomReward> BEHAVIOUR = (reward, context) -> {
        Optional<CustomRewards.Handler> handler = CustomRewards.handler(reward.id());
        if (handler.isEmpty()) {
            // Not an error: a pack may ship a custom reward whose handler mod is not installed, and
            // the quest still completes. The message says exactly that so nobody hunts a broken file.
            Constants.LOG.warn("custom reward \"{}\" has no handler registered (a mod or script "
                    + "that provides it is not loaded); it granted nothing", reward.id());
            return;
        }
        // A handler that throws grants nothing, and the claim continues: the alternative is a script
        // error crashing the claim -- or the tick that replays it -- which is what happened the first
        // time a script called a record accessor as a property. Grants are rare, so every throw logs.
        try {
            handler.get().grant(context.player(), context);
        }
        catch (RuntimeException | Error thrown) {
            Constants.LOG.warn("custom reward \"{}\" threw while being granted; it granted nothing"
                    + " ({})", reward.id(), thrown.toString());
        }
    };

    public static final Function<CustomReward, RewardDisplay> DISPLAY = reward ->
            RewardDisplay.ofTranslatableText("tenet.reward.custom",
                    "Custom reward: " + reward.id(), reward.id(), 1);

    /** The id-keyed handlers a custom reward resolves against. */
    public static final class CustomRewards {

        private CustomRewards() {
        }

        /** What a custom reward does when it is granted. */
        @FunctionalInterface
        public interface Handler {
            void grant(ServerPlayer player, RewardContext context);
        }

        private static final Map<String, Handler> HANDLERS = new ConcurrentHashMap<>();

        /** Registers a handler. Called from an addon's construction or a script's init. */
        public static void register(String id, Handler handler) {
            HANDLERS.put(java.util.Objects.requireNonNull(id, "id"),
                    java.util.Objects.requireNonNull(handler, "handler"));
        }

        /** The handler an id names, or empty when nothing provides it. */
        public static Optional<Handler> handler(String id) {
            return Optional.ofNullable(HANDLERS.get(id));
        }

        /** Every registered id, for diagnostics. */
        public static Set<String> ids() {
            return Set.copyOf(HANDLERS.keySet());
        }

        /**
         * Forgets every handler.
         *
         * <p>Called before a script reload, so a handler whose script was deleted or renamed does not
         * outlive it -- registration replaces by id, and without this the old one would go on being
         * granted for the rest of the session.
         */
        public static void clear() {
            HANDLERS.clear();
        }
    }
}
