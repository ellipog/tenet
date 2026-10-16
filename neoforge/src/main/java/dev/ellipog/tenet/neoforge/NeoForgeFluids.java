package dev.ellipog.tenet.neoforge;

import dev.ellipog.tenet.inventory.FluidAccess;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * NeoForge's fluid containers: tanks, capsules and canisters through the fluid transfer item
 * capability, over every slot the task loops read.
 *
 * <p>Bucket stacks are skipped by identity — the fluid task counts those itself, and a filled
 * bucket is both a bucket and a handler on this loader, so counting both would count one vessel
 * twice. The measurement reads every slot the vanilla loop reads, armour and offhand included:
 * insertion is clamped to the main inventory, but measurement has no such reason to look away.
 *
 * <p>The skip is identity on both loaders, and native millibuckets need no conversion — the one
 * unit-level difference (Fabric truncates droplets) is stated on
 * {@link dev.ellipog.tenet.inventory.FluidAccess}, where the contract both halves keep is written.
 *
 * <p>Handlers write through their slot access on commit, so the drain below commits rather than
 * writing container items back by hand — which is what the old {@code setItem(getContainer())}
 * did, and what the transaction replaced.
 */
public final class NeoForgeFluids implements FluidAccess {

    @Override
    public int storedOf(ServerPlayer player, Identifier fluid, Item bucketToSkip) {
        Fluid target = BuiltInRegistries.FLUID.get(fluid).map(holder -> holder.value()).orElse(null);
        if (target == null) {
            return 0;
        }
        long found = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.is(bucketToSkip)) {
                continue;
            }
            var handler = ItemAccess.forPlayerSlot(player, slot)
                    .getCapability(Capabilities.Fluid.ITEM);
            if (handler == null) {
                continue;
            }
            for (int tank = 0; tank < handler.size(); tank++) {
                FluidResource inTank = handler.getResource(tank);
                if (!inTank.isEmpty() && inTank.getFluid() == target) {
                    found += handler.getAmountAsLong(tank);
                }
            }
        }
        return (int) Math.min(found, Integer.MAX_VALUE);
    }

    @Override
    public int drainFrom(ServerPlayer player, Identifier fluid, int mb, Item bucketToSkip) {
        Fluid target = BuiltInRegistries.FLUID.get(fluid).map(holder -> holder.value()).orElse(null);
        if (target == null || mb <= 0) {
            return 0;
        }
        int remaining = mb;
        FluidResource wanted = FluidResource.of(target);
        var inventory = player.getInventory();
        // Opened on first successful extraction rather than up front, for the same reason as the
        // energy drain: a drain that finds nothing stored pays no transaction at all. Committed
        // once iff opened, so the all-or-nothing semantics are unchanged.
        Transaction transaction = null;
        try {
            for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty() || stack.is(bucketToSkip)) {
                    continue;
                }
                var handler = ItemAccess.forPlayerSlot(player, slot)
                        .getCapability(Capabilities.Fluid.ITEM);
                if (handler == null) {
                    continue;
                }
                // By exact resource rather than by amount: a tank holding another fluid must not
                // pay for this one, and amount-only draining takes whatever is on top.
                boolean touched = false;
                for (int tank = 0; tank < handler.size() && remaining > 0; tank++) {
                    FluidResource inTank = handler.getResource(tank);
                    if (inTank.isEmpty() || inTank.getFluid() != target) {
                        continue;
                    }
                    if (transaction == null) {
                        transaction = Transaction.openRoot();
                    }
                    remaining -= handler.extract(tank, wanted, remaining, transaction);
                    touched = true;
                }
                if (touched && remaining < 0) {
                    remaining = 0;
                }
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
        if (remaining != mb) {
            inventory.setChanged();
        }
        return mb - remaining;
    }
}
