package dev.ellipog.tenet.neoforge;

import dev.ellipog.tenet.inventory.InventoryAccess;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.util.List;

/**
 * NeoForge's inventory access: the platform's own transfer, over the main inventory only.
 *
 * <h2>Why the range is main-only, and why that is not a detail</h2>
 *
 * <p>Slot handlers for all 41 slots would happily use an empty armor slot as capacity.
 * Vanilla's automatic insertion never does that (see {@code InventorySpace} for the rules,
 * read from the artefact), so an unranged loop would make the simulation promise space that
 * the game would refuse, which is exactly the failure this class exists to prevent. The range
 * is therefore clamped to the 36 main slots, exactly as the old ranged wrapper was.
 *
 * <p><b>One deliberate difference from vanilla, stated rather than hidden.</b> Vanilla also merges
 * into an offhand slot that already holds a matching stack; this implementation does not, because
 * a ranged loop cannot express "merge here, never place here". The two loaders can therefore differ
 * in one corner — a full main inventory with a part-filled offhand — where NeoForge refuses a reward
 * that Fabric would merge. Refusing is the safe direction, and both methods here agree with each
 * other, which is what the strict claim path actually needs. The merge side is pinned by
 * {@code VanillaInventoryTest} in {@code :common}; this range is what pins the refusal side.
 *
 * <p>Fits are simulated by really inserting into a transaction that is never committed — the
 * platform's own rules, twice removed — which is what the old copy-of-a-handler simulation did
 * by hand.
 */
public final class NeoForgeInventory implements InventoryAccess {

    /** The main inventory and hotbar: the only slots an empty stack may be placed into. */
    private static final int MAIN_SLOTS = 36;

    @Override
    public boolean fitsAll(ServerPlayer player, List<ItemStack> stacks) {
        Transaction transaction = Transaction.openRoot();
        try {
            for (ItemStack stack : stacks) {
                if (stack.isEmpty()) {
                    continue;
                }
                int remaining = stack.getCount();
                ItemResource resource = ItemResource.of(stack);
                for (int slot = 0; slot < MAIN_SLOTS && remaining > 0; slot++) {
                    var handler = ItemAccess.forPlayerSlot(player, slot)
                            .getCapability(Capabilities.Item.ITEM);
                    if (handler == null) {
                        continue;
                    }
                    remaining -= handler.insert(resource, remaining, transaction);
                }
                if (remaining > 0) {
                    return false;
                }
            }
            return true;
        }
        finally {
            // Never committed: the inserts above were a simulation, and closing aborts them.
            transaction.close();
        }
    }

    @Override
    public ItemStack insert(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return stack;
        }
        int remaining = stack.getCount();
        ItemResource resource = ItemResource.of(stack);
        Transaction transaction = null;
        try {
            for (int slot = 0; slot < MAIN_SLOTS && remaining > 0; slot++) {
                var handler = ItemAccess.forPlayerSlot(player, slot)
                        .getCapability(Capabilities.Item.ITEM);
                if (handler == null) {
                    continue;
                }
                if (transaction == null) {
                    transaction = Transaction.openRoot();
                }
                remaining -= handler.insert(resource, remaining, transaction);
            }
            if (transaction != null) {
                transaction.commit();
            }
        }
        finally {
            if (transaction != null) {
                transaction.close();
            }
        }
        if (remaining <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack leftover = stack.copy();
        leftover.setCount(remaining);
        return leftover;
    }
}
