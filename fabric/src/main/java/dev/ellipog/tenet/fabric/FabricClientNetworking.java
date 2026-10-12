package dev.ellipog.tenet.fabric;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.tenet.Constants;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The client-only half of Fabric's networking.
 *
 * <p>A separate class so that {@link FabricNetworking} and {@link TenetFabric} — both loaded on a
 * dedicated server — never mention {@code ClientPlayNetworking}. Naming a client class in a class the
 * server loads is harmless until it is reached, and then the failure is a {@code NoClassDefFoundError}
 * naming a class nobody wrote. This split prevents it structurally rather than by being careful, which
 * is the same arrangement the rest of this project uses for client-only code.
 */
public final class FabricClientNetworking {

    private FabricClientNetworking() {
    }

    static void sendToServer(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }

    /**
     * Registers the client-side receivers for every payload that arrives at a client.
     *
     * <p>Called from the client initialiser. Registered once, globally, rather than per screen — a
     * progress sync arriving while the quest book is closed still needs to update the cache, because
     * the alternative is opening the book on stale data and waiting for a packet that has already
     * come and gone.
     */
    public static void registerClientReceivers() {
        for (ArmatureNetwork.Registration<?> registration : ArmatureNetwork.registrations()) {
            if (!registration.direction().reachesClient()) {
                continue;
            }
            registerClientReceiver(registration);
        }
    }

    private static <T extends CustomPacketPayload> void registerClientReceiver(
            ArmatureNetwork.Registration<T> registration) {
        ClientPlayNetworking.registerGlobalReceiver(registration.type(),
                (payload, context) -> {
                    // context.client() is the Minecraft instance, for a handler that needs to touch
                    // game state. These handlers only touch a cache, so it goes unused -- and the
                    // cache is written on this thread, which is where the screen reads it.
                    registration.onClient().accept(payload);
                });
        Constants.LOG.debug("registered client receiver for {}", registration.type().id());
    }
}
