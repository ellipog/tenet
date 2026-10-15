package dev.ellipog.tenet.inventory;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

/**
 * The installed fluid-container access, and silence when no loader has replaced it.
 *
 * <p>The same shape as {@link InventoryAccesses}: a loader hands its implementation over once at
 * startup, and everything else asks here. Uninstalled — a unit test, or vanilla, which has no
 * fluid containers but buckets — nothing is measured and nothing drains, which is exactly right
 * rather than a placeholder: the bucket path beside it needs no loader at all.
 */
public final class FluidAccesses {

    private static volatile FluidAccess installed;

    private FluidAccesses() {
    }

    /** Hands the loader's implementation over. Called once, from the loader's entry point. */
    public static void install(FluidAccess access) {
        installed = access;
    }

    /** The implementation in force. */
    public static FluidAccess current() {
        FluidAccess access = installed;
        return access == null ? Silent.INSTANCE : access;
    }

    /** Forgets the install, for a test that wants the default back. */
    public static void reset() {
        installed = null;
    }

    /** Vanilla: buckets are the only vessels, and the task counts those itself. */
    private enum Silent implements FluidAccess {
        INSTANCE;

        @Override
        public int storedOf(ServerPlayer player, Identifier fluid, Item bucketToSkip) {
            return 0;
        }

        @Override
        public int drainFrom(ServerPlayer player, Identifier fluid, int mb, Item bucketToSkip) {
            return 0;
        }
    }
}
