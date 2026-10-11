package dev.ellipog.tenet.fabric;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.tenet.QuestBook;
import dev.ellipog.tenet.QuestBookTabs;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.inventory.FluidAccesses;
import dev.ellipog.tenet.inventory.InventoryAccesses;
import dev.ellipog.tenet.inventory.VanillaInventory;
import dev.ellipog.tenet.net.TenetNetworking;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;

/**
 * Fabric entry point.
 *
 * <p>Four lines, and the order of them is the only interesting part:
 *
 * <ol>
 *   <li>{@code Tenet.init()} declares the payloads. It has to come first — installation reads the
 *       declarations and registers them, so anything declared afterwards is never seen.</li>
 *   <li>{@code ArmatureNetwork.install(...)} hands the loader over, which registers every declared
 *       payload's <b>format</b> on both sides.</li>
 *   <li>{@code FabricNetworking.registerReceivers()} registers the <b>server-side receivers</b>. Done
 *       here rather than in the client initialiser because a client with an integrated server is a
 *       server, and it receives the same packets a dedicated one would.</li>
 *   <li>The quest book's creative-tab entries, one per tab in {@link QuestBookTabs}. Here rather
 *       than in the client initialiser because both entry points call {@code Tenet.init()} on a
 *       client, and registering twice would list the book twice.</li>
 * </ol>
 *
 * <p>Runs on client and server both. Nothing here may touch a client class —
 * {@link TenetFabricClient} exists for that, and it is a separate entry point precisely so the
 * server never loads it.
 */
public final class TenetFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // Declares the payloads. Must precede the install below.
        Tenet.init();

        ArmatureNetwork.install(new FabricNetworking());

        // Fabric has no item-handler capability, so the vanilla semantics are the whole truth and the
        // common implementation is installed explicitly rather than left to the default -- the two
        // loaders then read the same, and the seam is visible at both entry points.
        InventoryAccesses.install(VanillaInventory.INSTANCE);

        // Fluid containers through the Transfer API.
        FluidAccesses.install(new FabricFluids());

        // Stored energy is Armature's seam (api.energy, installed by its entry points) -- Tenet
        // only reads it, so there is nothing to install here.

        // The quest book in the creative inventory. Read at event time rather than captured now:
        // the tab event fires after registration, and the null guard keeps a failed registration
        // from breaking tab population. Once per tab in QuestBookTabs, and only in this
        // initialiser — the client one runs too on a client, and a second registration would
        // list the book twice.
        for (ResourceKey<CreativeModeTab> tab : QuestBookTabs.TABS) {
            ItemGroupEvents.modifyEntriesEvent(tab).register(entries -> {
                if (QuestBook.ITEM != null) {
                    entries.accept(QuestBook.ITEM);
                }
            });
        }

        // The server half of Fabric's two-part registration. The client half is in TenetFabricClient.
        TenetFabric.registerServerReceivers();
    }

    /**
     * A separate method so the client initialiser can reason about it — and so that a reader can see,
     * without hunting, that server receivers are registered from the common initialiser on purpose.
     */
    static void registerServerReceivers() {
        FabricNetworking.registerReceivers();
        dev.ellipog.tenet.Constants.LOG.info("Tenet: Fabric networking ready ({} payload(s))",
                TenetNetworking.declaredCount());
    }
}
