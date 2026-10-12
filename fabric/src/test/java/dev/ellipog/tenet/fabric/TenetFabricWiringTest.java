package dev.ellipog.tenet.fabric;

import dev.ellipog.tenet.inventory.FluidAccess;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Fabric entry shape, pinned without launching anything.
 *
 * <p>A wrong entry shape is a silent no-load: the loader never calls a class that does not
 * implement its initialiser, and nothing reports it. The adapter check is the same kind of
 * wiring proof — the class the entry point installs honours the seam it installs it under.
 * Neither needs registries or a world, which is what keeps this suite headless.
 */
@DisplayName("the Fabric entry wiring")
class TenetFabricWiringTest {

    @Test
    @DisplayName("the entry points implement the loader's initialisers")
    void entriesImplementInitialisers() {
        assertTrue(ModInitializer.class.isAssignableFrom(TenetFabric.class),
                "the common initialiser is what the loader constructs");
        assertTrue(ClientModInitializer.class.isAssignableFrom(TenetFabricClient.class),
                "and the client one is what it constructs on a client");
    }

    @Test
    @DisplayName("the fluid adapter honours the seam")
    void fluidsHonourSeam() {
        assertInstanceOf(FluidAccess.class, new FabricFluids(),
                "the installed implementation answers the seam's type");
    }
}
