package dev.ellipog.tenet.fabric;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.armature.api.net.NetworkBackend;
import dev.ellipog.tenet.Constants;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * The server half of Fabric's networking.
 *
 * <h2>Fabric registers in two parts, and the split matters</h2>
 *
 * <p>Half of registering a payload is about the <b>format</b> — which codec reads it — and both sides
 * need that, because both serialise. The other half is about the <b>receiver</b>, and Fabric wants
 * that registered separately and per side: {@code ServerPlayNetworking} for packets arriving at a
 * server, {@code ClientPlayNetworking} for packets arriving at a client.
 *
 * <p>So {@link #register} does the format half, and it runs from the <i>common</i> initialiser so it
 * happens on both sides. {@link #registerReceivers()} does the server-receiver half and also runs
 * from the common initialiser — a client with an integrated server is a server too, so it needs those
 * receivers registered. The client-receiver half is in {@link FabricClientNetworking}, which only a
 * client loads.
 *
 * <p>NeoForge takes both halves in one call. That difference is the entire reason
 * {@link NetworkBackend} exists.
 */
public final class FabricNetworking implements NetworkBackend {

    /**
     * Package-private, not private: the entry point constructs one to hand to
     * {@code ArmatureNetwork.install}. Deliberately not a singleton — the backend holds no state
     * beyond what it forwards, so there is nothing for a singleton to protect, and the installed
     * instance is not a client or a server, it is whatever the loader asked for.
     */
    FabricNetworking() {
    }

    @Override
    public <T extends CustomPacketPayload> void register(ArmatureNetwork.Registration<T> registration) {
        // The format half, on both sides. A payload registered only on the server could be sent and
        // never serialised by the client, which surfaces as a disconnect rather than an error.
        if (registration.direction().reachesClient()) {
            PayloadTypeRegistry.playS2C().register(registration.type(), registration.codec());
        }
        if (registration.direction().reachesServer()) {
            PayloadTypeRegistry.playC2S().register(registration.type(), registration.codec());
        }
        Constants.LOG.debug("registered payload format {} ({})",
                registration.type().id(), registration.direction());
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        // Delegated, because ClientPlayNetworking is a client-only class and this one is loaded on
        // both sides. Sending to the server from a dedicated server cannot happen, and routing
        // through a client-only holder is what keeps the server from ever loading it.
        FabricClientNetworking.sendToServer(payload);
    }

    // ------------------------------------------------------------------
    // Receivers
    // ------------------------------------------------------------------

    /**
     * Registers the server-side receivers for every payload that arrives at a server.
     *
     * <p>Called from the common initialiser, because a client with an integrated server needs them
     * too — the integrated server receives the same packets a dedicated one would.
     *
     * <p>{@code registerGlobalReceiver} rather than {@code registerReceiver}: the per-connection
     * variant exists for state that belongs to one player's session, and these handlers want nothing
     * of the sort. The global form also survives a respawn, which the per-connection form does not.
     */
    public static void registerReceivers() {
        for (ArmatureNetwork.Registration<?> registration : ArmatureNetwork.registrations()) {
            if (!registration.direction().reachesServer()) {
                continue;
            }
            registerServerReceiver(registration);
        }
    }

    private static <T extends CustomPacketPayload> void registerServerReceiver(
            ArmatureNetwork.Registration<T> registration) {
        ServerPlayNetworking.registerGlobalReceiver(registration.type(),
                // The payload handler's second parameter is a context holding the player, the
                // response sender and the client's response sender -- everything needed to answer.
                // Tenet answers by pushing a progress sync separately, so only the player is used.
                (payload, context) -> registration.onServer().accept(payload, context.player()));
    }
}
