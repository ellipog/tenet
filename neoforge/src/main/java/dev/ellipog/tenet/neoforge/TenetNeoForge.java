package dev.ellipog.tenet.neoforge;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.QuestBook;
import dev.ellipog.tenet.QuestBookTabs;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.inventory.FluidAccesses;
import dev.ellipog.tenet.inventory.InventoryAccesses;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * NeoForge entry point.
 *
 * <p>Four lines, and the third is the one that differs from Fabric: NeoForge will not take a payload
 * registration during construction, so {@link #onRegisterPayloads} is registered as a listener and
 * does the work later. That deferral is the whole reason Armature's networking is a declaration rather
 * than a call. The fourth — the creative-tab entries — is deferred the same way, by the event.
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

        // The quest book in the creative inventory. Only here, never in the client entry point:
        // this constructor runs on the client too, so one listener covers the client tabs, and a
        // second would list the book twice.
        modEventBus.addListener(BuildCreativeModeTabContentsEvent.class, TenetNeoForge::addToCreativeTabs);
    }

    /**
     * Adds the quest book to its tabs, and to nothing else.
     *
     * <p>Read at event time rather than captured at construction: registration is deferred on
     * NeoForge, so {@link QuestBook#ITEM} is still null while this constructor runs and only
     * exists by the time tabs populate. The null guard keeps a failed registration from
     * breaking tab population.
     */
    private static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (!QuestBookTabs.shouldInclude(event.getTabKey())) {
            return;
        }
        if (QuestBook.ITEM != null) {
            event.accept(() -> QuestBook.ITEM);
        }
    }
}
