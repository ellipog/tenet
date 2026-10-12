package dev.ellipog.tenet.inventory;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

/**
 * The fluid carried in containers — tanks, capsules, canisters — beyond plain buckets.
 *
 * <p>Buckets are deliberately not this: the fluid task counts its fluid's bucket item itself
 * (a thousand millibuckets a bucket, empties handed back), which is vanilla behaviour no loader
 * may vary. This seam is everything else that holds fluid, read through the loader's own
 * machinery — NeoForge's fluid-handler capability, Fabric's Transfer API — because vanilla has
 * no container API to read it with.
 *
 * <p>The bucket item travels as a parameter so the two halves cannot count it twice: a filled
 * bucket is both a bucket and, on some loaders, a fluid handler. The loader skips stacks of
 * exactly that item and measures everything else.
 *
 * <h2>The parity contract both loaders honor</h2>
 *
 * <p>Millibuckets at the seam, truncated not rounded: a loader whose wire speaks finer units
 * (Fabric's droplets) converts at the edges and drops sub-millibucket fractions, while a loader
 * whose wire is already millibuckets (NeoForge) has nothing to drop. Either way a vessel holding
 * less than a millibucket reads as empty, on every loader.
 *
 * <p>Every slot the inventory reports is measured — main, armour and offhand alike — and buckets
 * are skipped by identity (an item travels as the skip, never a tag) on both loaders. What the
 * loaders genuinely do differently is only the machinery between those two promises.
 */
public interface FluidAccess {

    /**
     * Millibuckets of this fluid in carried containers, bucket stacks excluded.
     *
     * <p>Pure read: measures, changes nothing, and never throws for an ordinary stack.
     */
    int storedOf(ServerPlayer player, ResourceLocation fluid, Item bucketToSkip);

    /**
     * Drains up to {@code mb} millibuckets of this fluid from carried containers, bucket stacks
     * excluded, answering how much was actually drained.
     *
     * <p>Transformed containers stay where they are: an emptied container lands back in its own
     * slot, the way a drained bucket would if buckets went through here. They do not — see the
     * interface note — so there is no empty-bucket overflow to place or drop.
     */
    int drainFrom(ServerPlayer player, ResourceLocation fluid, int mb, Item bucketToSkip);
}
