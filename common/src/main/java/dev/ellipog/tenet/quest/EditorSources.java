package dev.ellipog.tenet.quest;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * What each {@link EditorField.Source} is: the registry it reads, and the grammar its values are written in.
 *
 * <h2>Why this is a separate class from the catalogue</h2>
 *
 * <p>Because these are facts about the <i>format</i>, and the catalogue is a fact about a running client.
 * {@code SearchCatalogue} needs {@code Minecraft} to gather a list; this needs nothing at runtime, so
 * every question here is a unit test rather than something only a launched game can answer. That matters
 * more than tidiness for one of them: the rule below is the one the structure picker broke, and a rule
 * whose only enforcement is a running game is a rule that gets broken again.
 *
 * <h2>The rule</h2>
 *
 * <p><b>A list may only be read from a registry the client is sent.</b> 1.21.1 sends a client eleven
 * datapack registries -- the biomes, the enchantments, the dimension types, the trim materials and their
 * neighbours -- and it builds the rest of its static ones at startup. Everything else a datapack defines
 * is the server's: a structure, a structure set, a placed feature, a level stem. Reading one of those from
 * the client's registry access does not throw. It answers an <i>empty</i> registry, so the picker opens
 * with no rows and no explanation, which is how {@code STRUCTURE} shipped. {@link #registryFor} names the
 * registry for every source, {@code null} for the ones no client registry answers, and the test beside
 * this class checks each answer against 1.21.1's own sets.
 *
 * <p>Like {@link EditorField} and {@link EditorSpecs}, nothing here mentions a client type: a task or a
 * reward type lives on both sides and carries its editor with it.
 */
public final class EditorSources {

    private EditorSources() {
    }

    /**
     * The client registry a source's list comes from, or null when no client registry answers it.
     *
     * <p>Null covers three different things, and the test beside this class treats them alike because the
     * rule it checks is about the registries that are <i>not</i> null: the two lists the server sends
     * ({@code DIMENSION}, {@code STRUCTURE}), the advancement tree the client is sent as a tree rather
     * than as a registry, and {@code OBSERVATION_TARGET}'s NBT mode, whose value is not an id at all.
     *
     * @param observeType the observation task's own mode, for the one source that depends on it
     */
    public static ResourceKey<? extends Registry<?>> registryFor(EditorField.Source source,
                                                                 String observeType) {
        return switch (source) {
            case DIMENSION, STRUCTURE, ADVANCEMENT -> null;
            case BIOME -> Registries.BIOME;
            case ENCHANTMENT -> Registries.ENCHANTMENT;
            // The custom statistics are exactly what a stat task reads -- `Stats.CUSTOM` -- and their keys
            // are the ids a file writes. Block and item statistics are not listed: the task resolves
            // through CUSTOM only, so offering the others would be offering what the engine cannot read.
            case STAT -> Registries.CUSTOM_STAT;
            case EFFECT -> Registries.MOB_EFFECT;
            case ATTRIBUTE -> Registries.ATTRIBUTE;
            case FLUID -> Registries.FLUID;
            case ENTITY -> Registries.ENTITY_TYPE;
            case ITEM_TAG -> Registries.ITEM;
            case ENTITY_TAG -> Registries.ENTITY_TYPE;
            case OBSERVATION_TARGET -> observationRegistry(observeType);
        };
    }

    /**
     * The registry an observation mode's target names.
     *
     * <p>Four modes name a block: a block, a tag of them, one block's state -- a block plus its
     * properties, so the block list is the honest one -- and one block entity, which is a block standing
     * in the world. The fifth is a registry of its own, and the last two name an entity.
     *
     * <p>{@code block_entity_type} has its own registry rather than the block one, and that is not a
     * detail: the engine compares what {@code BuiltInRegistries.BLOCK_ENTITY_TYPE} answers, so a block id
     * there is a value that can never match -- the fault this table exists to make impossible.
     */
    private static ResourceKey<? extends Registry<?>> observationRegistry(String observeType) {
        return switch (wire(observeType)) {
            case "entity_type", "entity_type_tag" -> Registries.ENTITY_TYPE;
            case "block_entity_type" -> Registries.BLOCK_ENTITY_TYPE;
            case "block_entity" -> null;
            default -> Registries.BLOCK;
        };
    }

    /**
     * Whether the mode's target is a tag of the things rather than one of them.
     *
     * <p>A named question rather than a suffix test, because the spelling is the legacy format's rather than this mod's
     * and a renamed mode should be a compile-time argument here rather than a silently false suffix.
     */
    public static boolean isTagMode(String observeType) {
        return "block_tag".equals(wire(observeType))
                || "entity_type_tag".equals(wire(observeType));
    }

    /** What a source's values look like, which is what decides how the field is edited. */
    public enum Value {
        /** A resource id, and nothing else. */
        ID,
        /**
         * An id, or a {@code #tag} of them.
         *
         * <p>Exactly the {@code RegistryRef} fields, which is where the format carries two spellings for
         * one field: {@code minecraft:plains} or {@code #minecraft:is_forest}.
         */
        ID_OR_TAG,
        /**
         * Not an id at all: the observation task's NBT filter, which is SNBT.
         *
         * <p>No list can answer it, so no picker should offer one -- a block id in a filter that expects
         * {@code {Items:[…]}} is a value that can never match, and the id would be refused by the codec
         * that reads it back.
         */
        FREE
    }

    /** The grammar a source's field is written in. See {@link Value}. */
    public static Value valueKind(EditorField.Source source, String observeType) {
        return switch (source) {
            case BIOME, STRUCTURE -> Value.ID_OR_TAG;
            case OBSERVATION_TARGET -> "block_entity".equals(wire(observeType)) ? Value.FREE : Value.ID;
            default -> Value.ID;
        };
    }

    /**
     * Whether the field's own grammar takes this text -- what "the value resolves" means for a picker.
     *
     * <p>Asked about the value a field already holds, because a picker that decides "known" by
     * {@code ResourceLocation.tryParse} alone reports a valid {@code #minecraft:village} as <i>missing</i>;
     * and asked about what an author has typed, so the row that keeps a typed value is offered only when
     * the field could actually hold it.
     */
    public static boolean accepts(EditorField.Source source, String observeType, String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return switch (valueKind(source, observeType)) {
            case ID -> ResourceLocation.tryParse(value) != null;
            // The `#` is the whole of the difference, and a ResourceLocation cannot hold one.
            case ID_OR_TAG -> ResourceLocation.tryParse(
                    value.startsWith("#") ? value.substring(1) : value) != null;
            case FREE -> !value.isBlank();
        };
    }

    /** A mode's wire name, with an absent one as the empty string -- the shape the codec defaults to. */
    private static String wire(String observeType) {
        return observeType == null ? "" : observeType;
    }
}
