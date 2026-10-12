package dev.ellipog.tenet.neoforge;

import dev.ellipog.tenet.inventory.FluidAccess;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

/**
 * NeoForge's fluid containers: tanks, capsules and canisters through the fluid-handler item
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
 */
public final class NeoForgeFluids implements FluidAccess {

    @Override
    public int storedOf(ServerPlayer player, ResourceLocation fluid, Item bucketToSkip) {
        Fluid target = BuiltInRegistries.FLUID.get(fluid);
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
            IFluidHandlerItem handler = stack.getCapability(Capabilities.FluidHandler.ITEM);
            if (handler == null) {
                continue;
            }
            for (int tank = 0; tank < handler.getTanks(); tank++) {
                FluidStack inTank = handler.getFluidInTank(tank);
                if (!inTank.isEmpty() && inTank.getFluid() == target) {
                    found += inTank.getAmount();
                }
            }
        }
        return (int) Math.min(found, Integer.MAX_VALUE);
    }

    @Override
    public int drainFrom(ServerPlayer player, ResourceLocation fluid, int mb, Item bucketToSkip) {
        Fluid target = BuiltInRegistries.FLUID.get(fluid);
        if (target == null || mb <= 0) {
            return 0;
        }
        int remaining = mb;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.is(bucketToSkip)) {
                continue;
            }
            IFluidHandlerItem handler = stack.getCapability(Capabilities.FluidHandler.ITEM);
            if (handler == null) {
                continue;
            }
            // By exact stack rather than by amount: a tank holding another fluid must not pay for
            // this one, and amount-only draining takes whatever is on top.
            FluidStack drained = handler.drain(new FluidStack(target, remaining),
                    IFluidHandler.FluidAction.EXECUTE);
            if (drained.isEmpty()) {
                continue;
            }
            remaining -= drained.getAmount();
            inventory.setItem(slot, handler.getContainer());
        }
        if (remaining != mb) {
            inventory.setChanged();
        }
        return mb - remaining;
    }
}
