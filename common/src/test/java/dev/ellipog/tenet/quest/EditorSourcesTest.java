package dev.ellipog.tenet.quest;

import dev.ellipog.tenet.quest.task.ObservationTask;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which registry each {@link EditorField.Source} reads, and whether a real client has it.
 *
 * <h2>The rule, and why it is a check rather than a sentence</h2>
 *
 * <p><b>A list may only be read from a registry the client is sent.</b> The structure picker broke it and
 * nobody noticed for a release, because breaking it does not fail anything: a registry a client was never
 * sent answers an <i>empty</i> one, so the picker simply opened with no rows. A rule whose only enforcement
 * is a running game is a rule that gets broken again, so the rule is checked here against 1.21.1's own
 * sets rather than against a list of ids somebody typed:
 *
 * <ul>
 *   <li>the registries the game builds at startup: {@code BuiltInRegistries.REGISTRY};</li>
 *   <li>the datapack registries a server sends a joining client:
 *       {@code RegistrySynchronization.NETWORKABLE_REGISTRIES}, which is
 *       {@code RegistryDataLoader.SYNCHRONIZED_REGISTRIES} -- in 1.21.1, the biomes, chat types, trim
 *       patterns and materials, wolf and painting variants, dimension types, damage types, banner
 *       patterns, enchantments and jukebox songs, and <b>nothing else</b>.</li>
 * </ul>
 *
 * <p>Everything a datapack defines beyond that list -- a structure, a structure set, a placed feature, a
 * level stem -- is the server's, so a source that names one must be a source the server's own list
 * answers, and {@link EditorSources#registryFor} answers {@code null} for exactly those. The predicate is
 * decisive rather than approximate: no datapack registry appears in {@code BuiltInRegistries.REGISTRY} at
 * all, not even as an empty stub, so pointing {@code STRUCTURE} back at {@code Registries.STRUCTURE} fails
 * this file immediately. That re-pointing is the calibration.
 *
 * <h2>Why it can run without a client</h2>
 *
 * <p>Because the table is in {@link EditorSources}, which names no client type -- the same promise
 * {@link EditorField} and {@link EditorSpecs} make. {@code SearchCatalogue} needs a running game to
 * gather a list; none of these questions does.
 */
@DisplayName("editor sources")
class EditorSourcesTest {

    /**
     * The observation modes, from the type's own enum rather than from a copy of the wire names.
     *
     * <p>From the enum deliberately: the table switches on those strings, and a mode renamed in one place
     * and not the other is a fault this arrangement cannot have.
     *
     * <p>A method rather than a static field, because a static field would be read while the test class is
     * being initialised — before {@code @BeforeAll} has bootstrapped the game's registries, which
     * {@link ObservationTask} then reaches for.
     */
    private static List<String> modes() {
        List<String> out = new ArrayList<>();
        for (ObservationTask.ObserveType mode : ObservationTask.ObserveType.values()) {
            out.add(mode.wire());
        }
        out.add("");
        // The empty string is what a file that omits `observeType` decodes to, and the table reads it as
        // the default mode rather than as an unknown one.
        return List.copyOf(out);
    }

    @BeforeAll
    static void boot() {
        MinecraftTestBootstrap.boot();
    }

    @Test
    @DisplayName("every registry a source names is one a real client has")
    void everySourceReadsARegistryTheClientHas() {
        List<String> checked = new ArrayList<>();
        for (EditorField.Source source : EditorField.Source.values()) {
            for (String mode : modes()) {
                ResourceKey<? extends Registry<?>> key = EditorSources.registryFor(source, mode);
                if (key == null) {
                    // Not a client registry at all: the server's two lists, the advancement tree, and the
                    // NBT mode. See the table's own note on why those three are one answer.
                    continue;
                }
                checked.add(entry(source, key));
                boolean builtIn = BuiltInRegistries.REGISTRY.keySet().contains(key.identifier());
                boolean sent = net.minecraft.core.RegistrySynchronization.isNetworkable(key);
                assertTrue(builtIn || sent,
                        source + " (mode \"" + mode + "\") reads " + key.identifier() + ", and a client is "
                                + "sent neither: it is not one of the registries the game builds at "
                                + "startup, and 1.21.1 does not send it. The picker would open with no "
                                + "rows and say nothing about why. Either send the list from the server or "
                                + "read a registry the client has.");
            }
        }
        // A table that answered null for everything would satisfy the loop above, so it is not evidence
        // on its own. These are the sources whose registries are the point of the rule -- named through
        // the keys themselves rather than through their spelling, so this stays a statement about *which*
        // registry rather than about what it is called. A biome's key really is
        // `minecraft:worldgen/biome`, which the first run of this test got wrong by guessing.
        assertFalse(checked.isEmpty(), "no source reads a client registry at all");
        assertTrue(checked.contains(entry(EditorField.Source.BIOME, Registries.BIOME)),
                checked.toString());
        assertTrue(checked.contains(entry(EditorField.Source.ENCHANTMENT, Registries.ENCHANTMENT)),
                checked.toString());
        assertTrue(checked.contains(entry(EditorField.Source.STAT, Registries.CUSTOM_STAT)),
                checked.toString());
        assertTrue(checked.contains(entry(EditorField.Source.OBSERVATION_TARGET,
                        Registries.BLOCK_ENTITY_TYPE)),
                "the observation target's block-entity-type mode reads the registry the engine compares "
                        + "against, and a client has it: " + checked);
    }

    /** One line of the sweep, so the assertion and the evidence are spelled the same way. */
    private static String entry(EditorField.Source source, ResourceKey<? extends Registry<?>> key) {
        return source.name() + " -> " + key.identifier();
    }

    @Test
    @DisplayName("an observation's target reads the registry its own mode names")
    void theObservationTargetFollowsItsMode() {
        // The mode decides the list, because the mode decides what the engine compares against. Each of
        // these is a value that can never match if the list is the wrong one -- an entity id offered for a
        // block, or a block where a block *entity type* is compared.
        assertEquals(Registries.BLOCK, mode("block"));
        assertEquals(Registries.BLOCK, mode("block_tag"));
        assertEquals(Registries.BLOCK, mode("block_state"));
        assertEquals(Registries.BLOCK_ENTITY_TYPE, mode("block_entity_type"));
        assertEquals(Registries.ENTITY_TYPE, mode("entity_type"));
        assertEquals(Registries.ENTITY_TYPE, mode("entity_type_tag"));
        // An NBT filter is not an id, so no registry answers it: see EditorSources.Value#FREE.
        assertNull(mode("block_entity"), "a block entity's target is SNBT, which no registry holds");

        // And the two that take a tag are the two whose target is a tag: the flag the catalogue asks for
        // rather than a suffix test on the wire name.
        assertTrue(EditorSources.isTagMode("block_tag"));
        assertTrue(EditorSources.isTagMode("entity_type_tag"));
        for (String other : List.of("block", "block_state", "block_entity", "block_entity_type",
                "entity_type", "")) {
            assertFalse(EditorSources.isTagMode(other), other + " does not name a tag");
        }
    }

    private static ResourceKey<? extends Registry<?>> mode(String observeType) {
        return EditorSources.registryFor(EditorField.Source.OBSERVATION_TARGET, observeType);
    }

    @Test
    @DisplayName("a value is read in the grammar the field's own codec takes")
    void theValueGrammarIsTheFieldsOwn() {
        // The two RegistryRef fields: the format carries an id and a `#tag` in one field, and a
        // Identifier cannot hold the `#`. This is the grammar that decides whether a picker reports
        // its own valid value as missing.
        assertEquals(EditorSources.Value.ID_OR_TAG,
                EditorSources.valueKind(EditorField.Source.BIOME, ""));
        assertEquals(EditorSources.Value.ID_OR_TAG,
                EditorSources.valueKind(EditorField.Source.STRUCTURE, ""));
        assertTrue(EditorSources.accepts(EditorField.Source.BIOME, "", "minecraft:plains"));
        assertTrue(EditorSources.accepts(EditorField.Source.BIOME, "", "#minecraft:is_forest"));
        assertTrue(EditorSources.accepts(EditorField.Source.STRUCTURE, "", "#minecraft:village"));
        // An id-only field still refuses the tag spelling: a `#` is not a resource location, which is what
        // keeps a deliberate typo out of a file whose codec would refuse it.
        assertFalse(EditorSources.accepts(EditorField.Source.DIMENSION, "", "#minecraft:overworld"));
        assertFalse(EditorSources.accepts(EditorField.Source.STAT, "", "#minecraft:logs"));
        assertFalse(EditorSources.accepts(EditorField.Source.ENTITY, "", "#minecraft:zombies"));
        // An item tag field holds the plain id, because its codec is the plain one.
        assertEquals(EditorSources.Value.ID, EditorSources.valueKind(EditorField.Source.ITEM_TAG, ""));
        assertTrue(EditorSources.accepts(EditorField.Source.ITEM_TAG, "", "minecraft:logs"));
        assertFalse(EditorSources.accepts(EditorField.Source.ITEM_TAG, "", "#minecraft:logs"));
        // The NBT mode is the one that is not an id at all, and it takes what an id cannot.
        assertEquals(EditorSources.Value.FREE,
                EditorSources.valueKind(EditorField.Source.OBSERVATION_TARGET, "block_entity"));
        assertTrue(EditorSources.accepts(EditorField.Source.OBSERVATION_TARGET, "block_entity",
                "{Items:[{Slot:13b,id:\"minecraft:paper\",count:1b}]}"));
        assertFalse(EditorSources.accepts(EditorField.Source.OBSERVATION_TARGET, "block",
                "{Items:[]}"));
        // Nothing at all is not a value, for any grammar: the empty field is the picker's "no value yet".
        assertFalse(EditorSources.accepts(EditorField.Source.BIOME, "", ""));
        assertFalse(EditorSources.accepts(EditorField.Source.OBSERVATION_TARGET, "block_entity", ""));
    }

    @Test
    @DisplayName("the sources whose list is not a client registry are the ones the server sends")
    void theServerListedSourcesAreTheOnesWithNoRegistry() {
        // Named so that a source added later cannot quietly land on the wrong side of the line: a source
        // whose list comes from somewhere other than a client registry has to be one of these five
        // reasons, and each of the other two server-sent sources is asserted to have no registry.
        for (EditorField.Source source : EditorField.Source.values()) {
            if (source == EditorField.Source.DIMENSION || source == EditorField.Source.STRUCTURE
                    || source == EditorField.Source.ADVANCEMENT) {
                assertNull(EditorSources.registryFor(source, ""),
                        source + " is answered by the server or the client's own tree, not by a registry");
            }
        }
        assertNotNull(EditorSources.registryFor(EditorField.Source.BIOME, ""),
                "a biome is sent to every client, so the picker reads it directly");
    }
}
