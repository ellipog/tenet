package dev.ellipog.tenet.fabric;

import dev.ellipog.tenet.inventory.FluidAccess;

import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.item.PlayerInventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

/**
 * Fabric's fluid containers: tanks, capsules and canisters through the Transfer API, over the
 * player's inventory slots.
 *
 * <p>Bucket stacks are skipped by identity — the fluid task counts those itself, and a filled
 * bucket is both a bucket and a transfer storage, so counting both would count one vessel twice.
 * Amounts travel the wire the API speaks (droplets, {@value FluidConstants#BUCKET} to the
 * bucket) and are converted to millibuckets at the edges, truncating sub-millibucket fractions
 * the task's own unit cannot name.
 *
 * <p>The slots are the Transfer storage's — main, armour and offhand, the same ground NeoForge's
 * capability loop walks — and the skip is identity on both loaders. The contract both halves keep
 * is written on {@link dev.ellipog.tenet.inventory.FluidAccess}; the one unit-level difference
 * (truncation here, native millibuckets there) is stated there rather than hidden here.
 */
public final class FabricFluids implements FluidAccess {

    @Override
    public int storedOf(ServerPlayer player, Identifier fluid, Item bucketToSkip) {
        Fluid target = BuiltInRegistries.FLUID.get(fluid).map(holder -> holder.value()).orElse(null);
        if (target == null) {
            return 0;
        }
        long drops = 0;
        PlayerInventoryStorage inventory = PlayerInventoryStorage.of(player);
        try (Transaction transaction = Transaction.openOuter()) {
            for (SingleSlotStorage<ItemVariant> slot : inventory.getSlots()) {
                ItemVariant contained = slot.getResource();
                if (contained.isBlank() || contained.getItem() == bucketToSkip) {
                    continue;
                }
                Storage<FluidVariant> storage = ContainerItemContext.ofPlayerSlot(player, slot)
                        .find(FluidStorage.ITEM);
                if (storage == null) {
                    continue;
                }
                for (StorageView<FluidVariant> view : storage) {
                    FluidVariant variant = view.getResource();
                    if (!variant.isBlank() && variant.getFluid() == target) {
                        drops += view.getAmount();
                    }
                }
            }
        }
        return (int) Math.min(drops * 1000 / FluidConstants.BUCKET, Integer.MAX_VALUE);
    }

    @Override
    public int drainFrom(ServerPlayer player, Identifier fluid, int mb, Item bucketToSkip) {
        Fluid target = BuiltInRegistries.FLUID.get(fluid).map(holder -> holder.value()).orElse(null);
        if (target == null || mb <= 0) {
            return 0;
        }
        long remaining = (long) mb * FluidConstants.BUCKET / 1000;
        PlayerInventoryStorage inventory = PlayerInventoryStorage.of(player);
        try (Transaction transaction = Transaction.openOuter()) {
            for (SingleSlotStorage<ItemVariant> slot : inventory.getSlots()) {
                if (remaining <= 0) {
                    break;
                }
                ItemVariant contained = slot.getResource();
                if (contained.isBlank() || contained.getItem() == bucketToSkip) {
                    continue;
                }
                Storage<FluidVariant> storage = ContainerItemContext.ofPlayerSlot(player, slot)
                        .find(FluidStorage.ITEM);
                if (storage == null) {
                    continue;
                }
                remaining -= storage.extract(FluidVariant.of(target), remaining, transaction);
            }
            transaction.commit();
        }
        long drained = (long) mb * FluidConstants.BUCKET / 1000 - remaining;
        return (int) Math.min(drained * 1000 / FluidConstants.BUCKET, Integer.MAX_VALUE);
    }
}
