package dev.ellipog.tenet.neoforge;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The client-only half of NeoForge's networking.
 *
 * <p>A separate class so that {@link NeoForgeNetworking} — loaded on a dedicated server — never
 * mentions {@code ClientPacketDistributor}. Naming a client class in a class the server loads is
 * harmless until it is reached, and then the failure is a {@code NoClassDefFoundError} naming a
 * class nobody wrote. This split prevents it structurally rather than by being careful, which is
 * the same arrangement the rest of this project uses for client-only code (see Fabric's
 * {@code FabricClientNetworking}).
 *
 * <p>On 26.x the client send moved out of {@code PacketDistributor} into this client distributor;
 * the server send stayed where it was. So this holder is the only place that names the client
 * class, and the common backend delegates through it.
 */
public final class NeoForgeClientNetworking {

    private NeoForgeClientNetworking() {
    }

    static void sendToServer(CustomPacketPayload payload) {
        ClientPacketDistributor.sendToServer(payload);
    }
}
