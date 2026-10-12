package dev.ellipog.tenet.neoforge;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.armature.api.net.NetworkBackend;
import dev.ellipog.tenet.Constants;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;

/**
 * Armature's networking, on NeoForge.
 *
 * <h2>Registration is deferred here, and that is the point of the SPI</h2>
 *
 * <p>NeoForge will not accept a payload registration during mod construction — it wants them inside
 * {@link RegisterPayloadHandlersEvent}, which fires later. So {@link #register} cannot do the work
 * itself; it records the registration and {@link #onRegisterPayloads} performs it when the event
 * arrives.
 *
 * <p>That is exactly the difference the platform layer exists to hide. Fabric registers immediately
 * and NeoForge cannot, and a mod that had to know which would be a mod with two versions of its setup
 * code. Here the declaration is written once and each loader takes it at the moment it accepts one.
 *
 * <h2>Both directions in one call</h2>
 *
 * <p>Unlike Fabric, NeoForge's registrar takes the format <i>and</i> the handler together, with the
 * direction chosen by which method is called. So {@code Registration.direction()} maps onto a method
 * name rather than onto two separate registrations — a smaller translation than Fabric's.
 */
public final class NeoForgeNetworking implements NetworkBackend {

    /**
     * Declared payloads, waiting for the event that can register them.
     *
     * <p>Static because the registration and the event are reached from different places — one from
     * mod construction, one from the event bus — and there is one of each per JVM.
     */
    private static final List<ArmatureNetwork.Registration<?>> QUEUED = new ArrayList<>();

    @Override
    public <T extends CustomPacketPayload> void register(ArmatureNetwork.Registration<T> registration) {
        // Nothing to do yet: the registrar does not exist until the event fires, and NeoForge rejects
        // a registration at the wrong time rather than ignoring it. Held for onRegisterPayloads.
        QUEUED.add(registration);
        Constants.LOG.debug("queued payload {} ({}) for NeoForge registration",
                registration.type().id(), registration.direction());
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        // The trailing vararg is "and these too", which exists so several payloads can share one
        // packet. Tenet sends one at a time, and the compiler supplies the empty array.
        PacketDistributor.sendToPlayer(player, payload);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    // ------------------------------------------------------------------
    // The event
    // ------------------------------------------------------------------

    /**
     * Registers everything queued. Called from the mod bus during startup.
     *
     * <p>Fires on both sides — a client and a dedicated server each run it — which is what makes one
     * registration serve both directions. The handler for a payload travelling to the client only ever
     * runs on a client, and vice versa, so no side check is needed inside them.
     */
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        // The version string is part of the handshake: two sides with different versions of a payload
        // are refused rather than allowed to mis-parse each other's bytes. Tenet's payloads are
        // versioned together, so one string for all of them.
        PayloadRegistrar registrar = event.registrar("1");

        for (ArmatureNetwork.Registration<?> registration : QUEUED) {
            registerOne(registrar, registration);
        }

        Constants.LOG.info("registered {} payload(s) with NeoForge", QUEUED.size());
    }

    private static <T extends CustomPacketPayload> void registerOne(PayloadRegistrar registrar,
                                                                   ArmatureNetwork.Registration<T> registration) {
        switch (registration.direction()) {
            case TO_CLIENT -> registrar.playToClient(registration.type(), registration.codec(),
                    (payload, context) -> registration.onClient().accept(payload));

            case TO_SERVER -> registrar.playToServer(registration.type(), registration.codec(),
                    (payload, context) -> {
                        // The context's player is a Player; on this side it is always a ServerPlayer,
                        // because playToServer only delivers to a server. The check is here rather
                        // than a cast so that a surprise is a dropped packet with a log line instead
                        // of a ClassCastException inside the network thread.
                        if (context.player() instanceof ServerPlayer sender) {
                            registration.onServer().accept(payload, sender);
                        }
                    });

            case BIDIRECTIONAL -> registrar.playBidirectional(registration.type(), registration.codec(),
                    (payload, context) -> {
                        if (context.player() instanceof ServerPlayer sender) {
                            registration.onServer().accept(payload, sender);
                        }
                        else if (registration.onClient() != null) {
                            registration.onClient().accept(payload);
                        }
                    });
        }
    }

    /**
     * Package-private, not private: the entry point constructs one to hand to
     * {@code ArmatureNetwork.install}.
     */
    NeoForgeNetworking() {
    }
}
