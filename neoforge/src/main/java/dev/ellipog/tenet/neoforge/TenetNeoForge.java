package dev.ellipog.tenet.neoforge;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.inventory.FluidAccesses;
import dev.ellipog.tenet.inventory.InventoryAccesses;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * NeoForge entry point.
 *
 * <p>Three lines, and the third is the one that differs from Fabric: NeoForge will not take a payload
 * registration during construction, so {@link #onRegisterPayloads} is registered as a listener and
 * does the work later. That deferral is the whole reason Armature's networking is a declaration rather
 * than a call.
 */
@Mod(Constants.MOD_ID)
public final class TenetNeoForge {

    public TenetNeoForge(IEventBus modEventBus) {
        // Declares the payloads. Must precede the install below, which records them for the event.
        Tenet.init();

        ArmatureNetwork.install(new NeoForgeNetworking());

        // The item-handler transfer, so a strict claim's "does this fit?" is answered by the same
        // machinery that will perform the insert. See NeoForgeInventory for the range it clamps to.
        InventoryAccesses.install(new NeoForgeInventory());

        // The fluid-handler item capability, so fluid tasks read carried containers as well as
        // buckets. See NeoForgeFluids for the bucket it skips. Stored energy is Armature's seam
        // (api.energy, installed by its entry point) -- Tenet only reads it.
        FluidAccesses.install(new NeoForgeFluids());

        // The event is on the mod bus, and fires on both sides -- so one registration serves both
        // directions. See NeoForgeNetworking.onRegisterPayloads.
        modEventBus.addListener(RegisterPayloadHandlersEvent.class, NeoForgeNetworking::onRegisterPayloads);
    }
}
