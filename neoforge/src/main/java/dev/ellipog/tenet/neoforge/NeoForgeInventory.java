package dev.ellipog.tenet.neoforge;

import dev.ellipog.tenet.inventory.InventoryAccess;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.RangedWrapper;

import java.util.List;

/**
 * NeoForge's inventory access: the platform's own transfer, over the main inventory only.
 *
 * <h2>Why the wrapper is ranged, and why that is not a detail</h2>
 *
 * <p>{@code InvWrapper} over a {@code PlayerInventory} presents all 41 slots — 36 main, 4 armor, 1
 * offhand — and {@code ItemHandlerHelper.insertItemStacked} would happily use an empty armor slot as
 * capacity. Vanilla's automatic insertion never does that (see {@code InventorySpace} for the rules,
 * read from the artefact), so an unwrapped handler would make the simulation promise space that the
 * game would refuse, which is exactly the failure this class exists to prevent. The range is
 * therefore clamped to the 36 main slots.
 *
 * <p><b>One deliberate difference from vanilla, stated rather than hidden.</b> Vanilla also merges
 * into an offhand slot that already holds a matching stack; this implementation does not, because a
 * ranged handler cannot express "merge here, never place here". The two loaders can therefore differ
 * in one corner — a full main inventory with a part-filled offhand — where NeoForge refuses a reward
 * that Fabric would merge. Refusing is the safe direction, and both methods here agree with each
 * other, which is what the strict claim path actually needs. The merge side is pinned by
 * {@code VanillaInventoryTest} in {@code :common}; this range is what pins the refusal side.
 */
public final class NeoForgeInventory implements InventoryAccess {

    /** The main inventory and hotbar: the only slots an empty stack may be placed into. */
    private static final int MAIN_SLOTS = 36;

    @Override
    public boolean fitsAll(ServerPlayer player, List<ItemStack> stacks) {
        IItemHandler main = main(player);
        // A mutable copy of the handler: ItemHandlerHelper has no simulate-and-reserve, so the batch
        // is simulated by really inserting into a copy — the platform's own rules, twice removed.
        ItemStackHandler simulation = new ItemStackHandler(main.getSlots());
        for (int slot = 0; slot < main.getSlots(); slot++) {
            simulation.setStackInSlot(slot, main.getStackInSlot(slot));
        }
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            if (!ItemHandlerHelper.insertItemStacked(simulation, stack, false).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack insert(ServerPlayer player, ItemStack stack) {
        return ItemHandlerHelper.insertItemStacked(main(player), stack, false);
    }

    /** The player's main inventory as an item handler. */
    private static IItemHandler main(ServerPlayer player) {
        return new RangedWrapper(new InvWrapper(player.getInventory()), 0, MAIN_SLOTS);
    }
}
