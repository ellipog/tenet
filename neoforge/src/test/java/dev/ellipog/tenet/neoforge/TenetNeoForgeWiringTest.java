package dev.ellipog.tenet.neoforge;

import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.inventory.FluidAccess;
import dev.ellipog.tenet.inventory.InventoryAccess;

import net.neoforged.fml.common.Mod;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The NeoForge entry shape, pinned without launching anything.
 *
 * <p>Same reasoning as the Fabric twin's test: a missing or misnamed {@code @Mod} is a silent
 * no-load, and the installed adapters honouring their seams is the wiring the entry points
 * rely on. Neither needs registries or a world.
 */
@DisplayName("the NeoForge entry wiring")
class TenetNeoForgeWiringTest {

    @Test
    @DisplayName("the entry point carries this mod's marker")
    void entryCarriesMarker() {
        Mod mod = TenetNeoForge.class.getAnnotation(Mod.class);
        assertNotNull(mod, "without the marker the loader never constructs the entry");
        assertEquals(Constants.MOD_ID, mod.value(), "for this mod, not another");
    }

    @Test
    @DisplayName("the adapters honour their seams")
    void adaptersHonourSeams() {
        assertInstanceOf(FluidAccess.class, new NeoForgeFluids(),
                "the installed fluid implementation answers the seam's type");
        assertInstanceOf(InventoryAccess.class, new NeoForgeInventory(),
                "and so does the inventory implementation");
    }
}
