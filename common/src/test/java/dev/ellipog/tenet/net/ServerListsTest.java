package dev.ellipog.tenet.net;

import dev.ellipog.tenet.quest.MinecraftTestBootstrap;

import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.levelgen.structure.Structure;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The structure list the server sends, against the datapack's own structures.
 *
 * <h2>Why this needs the real datapack</h2>
 *
 * <p>Because the fault it is here to prevent was a list that resolved to nothing, and a test written
 * against a hand-built registry would have agreed with the hand-built registry while the game's own
 * answered empty. So the registries are loaded the way vanilla loads them: the vanilla pack, the worldgen
 * layer, {@code RegistryDataLoader.WORLDGEN_REGISTRIES} — the three lines of {@code WorldLoader.loadLayer}
 * that stand between a pack on disk and a server's worldgen registry access. No world, no server, no
 * player: what is under test is the gathering, so nothing else is asked for.
 *
 * <p>The ids are then checked in both directions: the vanilla facts a person can name
 * ({@code minecraft:village_plains}, {@code minecraft:stronghold}, the {@code #minecraft:village} tag),
 * and the property that the list <b>is</b> the registry's own keys plus its tags — which is what makes a
 * hardcoded vanilla list impossible to ship in place of the real one.
 *
 * <p>The assertions name specific ids rather than a count on purpose. A count pinned to one Minecraft
 * version stops meaning anything the moment the version moves, and this file's subject is not how many
 * structures 1.21.1 has; it is that whatever the server has is what the client is offered.
 */
@DisplayName("server lists")
class ServerListsTest {

    private static RegistryAccess worldgen;
    private static Registry<Structure> structures;

    /**
     * The vanilla worldgen registries, loaded the way {@code WorldLoader} loads them.
     *
     * <p>The three calls below are its own sequence: static tags first, then the world
     * registries with the static lookups as fallback (structure files name biomes and blocks
     * from other registries, and without the fallbacks the load fails), then the tag bind over
     * the result. Tags live in a second set of files the entry load does not bind. No world, no
     * server, no player: what is under test is the gathering, so nothing else is asked for. The
     * vanilla pack needs no closing: it is in-jar resources, not files.
     */
    @BeforeAll
    static void loadVanillaWorldgen() {
        MinecraftTestBootstrap.boot();
        // The pack's full resources, not the manager view: `asResourceManager` exposes none of
        // the datapack entries, and the load comes back with empty registries that fail
        // validation. This is the same vanilla pack the old test wrapped, unwrapped.
        try (var packs = new net.minecraft.server.packs.resources.MultiPackResourceManager(
                net.minecraft.server.packs.PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource().fullResources()))) {
            LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
            var staticTags = net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(
                    packs, layers.getLayer(RegistryLayer.STATIC));
            var lookups = net.minecraft.tags.TagLoader.buildUpdatedLookups(
                    layers.getAccessForLoading(RegistryLayer.WORLD), staticTags);
            worldgen = RegistryDataLoader.load(packs, lookups, RegistryDataLoader.WORLD_REGISTRIES,
                    Util.backgroundExecutor()).join();
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(packs, worldgen)
                    .forEach(pending -> pending.apply());
        }
        structures = worldgen.registries()
                .filter(entry -> entry.key().equals(Registries.STRUCTURE))
                .findFirst()
                .map(entry -> (Registry<Structure>) entry.value())
                .orElseThrow(() -> new AssertionError("the load put vanilla structures nowhere"));
    }

    @Test
    @DisplayName("the structures a server would send are the datapack's own, key for key and tag for tag")
    void theStructureListIsTheRegistriesOwn() {
        List<String> sent = TenetNetworking.structureIds(worldgen);

        Set<String> expected = new TreeSet<>();
        structures.keySet().forEach(id -> expected.add(id.toString()));
        structures.getTags().forEach(tag -> expected.add("#" + tag.key().location()));

        assertEquals(List.copyOf(expected), sent,
                "the list the server sends is not the registry it read: a structure the pack has would be "
                        + "missing from every picker, or a value offered that the engine cannot resolve");
        assertFalse(sent.isEmpty(), "the vanilla datapack defines structures, so this cannot be empty");
    }

    @Test
    @DisplayName("the vanilla structures and their tag are in it, in the spelling a file uses")
    void theVanillaFactsAreThere() {
        List<String> sent = TenetNetworking.structureIds(worldgen);

        // Named because they are the ones a person would look for: the structure task's own default, the
        // one every pack knows, and the tag that stands for the five villages.
        assertTrue(sent.contains("minecraft:village_plains"), "the vanilla plains village is missing");
        assertTrue(sent.contains("minecraft:stronghold"), "the vanilla stronghold is missing");
        assertTrue(sent.contains("#minecraft:village"),
                "a structure tag is a value the field takes, so it has to be offered: `structure` is a "
                        + "RegistryRef and `#minecraft:village` is how five villages are named at once");

        // Every entry is a spelling a file can hold: an id, or a `#` tag. A list that carried a name, a
        // label or a translated string would look right in a picker and write a value the codec refuses.
        for (String id : sent) {
            String location = id.startsWith("#") ? id.substring(1) : id;
            assertNotNull(Identifier.tryParse(location),
                    id + " is neither an id nor a #tag, and the field's codec takes nothing else");
        }
    }

    @Test
    @DisplayName("the ids and the tags are both in the one sorted list")
    void idsAndTagsAreOneList() {
        List<String> sent = TenetNetworking.structureIds(worldgen);

        assertTrue(sent.containsAll(structures.keySet().stream().map(Object::toString).toList()),
                "every structure id has to be offered");
        assertFalse(structures.getTags().toList().isEmpty(),
                "the vanilla datapack defines structure tags, so the tag half of this test is real");
        for (var tag : structures.getTags().toList()) {
            assertTrue(sent.contains("#" + tag.key().location()), tag + " is missing from the tag list");
        }
        // Sorted, so a picker's first ten rows are the same ten twice running.
        assertEquals(sent.stream().sorted().toList(), sent, "the list is not sorted");
    }
}
