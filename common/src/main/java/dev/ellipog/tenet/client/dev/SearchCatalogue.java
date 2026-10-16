package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.client.ClientServerLists;
import dev.ellipog.tenet.quest.EditorField;
import dev.ellipog.tenet.quest.EditorSources;
import dev.ellipog.tenet.quest.EditorSpecs;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * What a search field offers: the things the game actually has, resolved on the client.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Because a field that is an id -- a dimension, a biome, a statistic -- used to be a text box you had
 * to know the answer to. Everything here is a list the client already holds: the registries the game
 * ships statically, the datapack registries the server sends, the advancement tree, the custom
 * statistics, and the two lists the server sends because a client cannot build them.
 *
 * <h2>The one rule, which is in the table rather than in this class</h2>
 *
 * <p><b>A list may only be read from a registry the client is sent.</b> A registry the server does not
 * send answers <i>empty</i> rather than throwing, so reading one is not an error, it is a picker that
 * opens with no rows and no explanation -- which is exactly how the structure list shipped, because a
 * structure is worldgen data the server keeps to itself. {@link EditorSources#registryFor} is the table
 * that names the registry behind every source, and {@code EditorSourcesTest} asserts each of them against
 * 1.21.1's own sets. This class only gathers what that table points at.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It does not pretend to know more than it does. A datapack's dimensions and every structure are not on
 * the client -- one is level data and the other is a registry the server does not send -- so those two
 * lists come from the server, and a datapack dimension or structure added by a reload arrives on the next
 * join rather than mid-session. The dimension list falls back to the three every player knows before the
 * server has said; the structure list has no fallback, because a structure's id is the pack's to choose
 * and a list of vanilla ids would be a guess dressed as an answer. The picker's search box takes a typed
 * id either way, which is what keeps an empty list from being a dead end.
 *
 * <p>Labels come from the ids, prettified: a biome's own translated name is not reachable from a registry
 * key without resolving the biome's display, and an id a person can read ("The nether") is what a picker
 * row needs. The raw id is beside it as small print, where the picker puts it for every row.
 */
public final class SearchCatalogue {

    private SearchCatalogue() {
    }

    /** The three dimensions every player knows, in the order a file lists them. */
    private static final List<String> KNOWN_DIMENSIONS =
            List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");

    /**
     * The list one source offers, or an empty one when the client cannot see it -- disconnected, or a
     * registry the server has not sent yet.
     *
     * <p>A switch <b>expression</b> rather than a statement, deliberately. As a statement it compiled
     * while a new {@link EditorField.Source} silently listed nothing, which is the shape of the fault
     * this class shipped with; as an expression the compiler refuses to build until every source has an
     * arm.
     *
     * @param observeType the observation task's own mode, for the source that depends on it
     */
    public static List<ItemPicker.Entry> list(EditorField.Source source, String observeType) {
        Minecraft minecraft = Minecraft.getInstance();
        RegistryAccess access = minecraft.level == null ? null : minecraft.level.registryAccess();
        List<ItemPicker.Entry> out = new ArrayList<>(switch (source) {
            case DIMENSION -> dimensions(minecraft);
            // The server's own lists -- see ServerListsPayload for why neither can be read here.
            case STRUCTURE -> rows(ClientServerLists.structures());
            case ADVANCEMENT -> advancements(minecraft);
            case OBSERVATION_TARGET -> observation(observeType);
            case ITEM_TAG, ENTITY_TAG, BLOCK_TAG -> tags(EditorSources.registryFor(source, observeType), false);
            case EFFECT, ATTRIBUTE, FLUID, ENTITY, STAT, BLOCK ->
                    builtIn(EditorSources.registryFor(source, observeType));
            // The two datapack registries a real server does send, and the tags the tag packet binds to
            // them: read from the connection's own access, and a `#` because both fields are RegistryRefs.
            case BIOME -> registry(access, EditorSources.registryFor(source, observeType), true);
            case ENCHANTMENT -> registry(access, EditorSources.registryFor(source, observeType), false);
        });
        // Alphabetical by id, so a picker opened before anything is typed shows the same ten rows twice
        // running -- and so "the first ten" means the first ten anything an author would look for.
        out.sort(Comparator.comparing(ItemPicker.Entry::id));
        return List.copyOf(out);
    }

    /**
     * The dimensions: the server's own list when it has sent one, and the three every player knows when it
     * has not.
     *
     * <p>The server's list is the one that matters, and it is the only way a modded or datapack dimension
     * can appear: the client is never sent them, because a dimension is level data rather than a registry
     * entry -- see {@code ServerListsPayload}. The fallback covers the moment before it arrives, and it is
     * deliberately not a lie about the rest: the box takes a typed id whatever the list holds.
     */
    private static List<ItemPicker.Entry> dimensions(Minecraft minecraft) {
        List<ItemPicker.Entry> out = ClientServerLists.known()
                ? rows(ClientServerLists.dimensions())
                : rows(KNOWN_DIMENSIONS);
        addCurrentDimension(minecraft, out);
        return out;
    }

    /** The dimension the player is standing in, when the list does not already hold it. */
    private static void addCurrentDimension(Minecraft minecraft, List<ItemPicker.Entry> out) {
        if (minecraft.level == null) {
            return;
        }
        String here = minecraft.level.dimension().identifier().toString();
        boolean listed = out.stream().anyMatch(entry -> entry.id().equals(here));
        if (!listed) {
            out.add(entry(here));
        }
    }

    /**
     * An observation's target: whichever list the task's own mode names.
     *
     * <p>The mode decides which list is right -- an entity id in a block field is a task that can never be
     * satisfied -- so the picker asks the same question the engine does, and {@link EditorSources} is
     * where that question is answered. A tag mode's target is a tag id without the {@code #}, because the
     * task reads it with a resource location codec; a block entity's target is an NBT filter, which has no
     * registry at all and never reaches here -- see {@code EditorSources.Value#FREE}.
     */
    private static List<ItemPicker.Entry> observation(String observeType) {
        ResourceKey<? extends Registry<?>> key =
                EditorSources.registryFor(EditorField.Source.OBSERVATION_TARGET, observeType);
        if (key == null) {
            return List.of();
        }
        return EditorSources.isTagMode(observeType) ? tags(key, false) : builtIn(key);
    }

    /** A datapack registry the server sent, ids only -- and its tags where the field takes one. */
    private static List<ItemPicker.Entry> registry(RegistryAccess access,
                                                   ResourceKey<? extends Registry<?>> key,
                                                   boolean withTags) {
        if (access == null || key == null) {
            return List.of();
        }
        Registry<?> registry = access.lookup(key).orElse(null);
        return registry == null ? List.of() : builtIn(registry, withTags);
    }

    /** One of the game's own registries, by key: the same object the tag packet binds its tags to. */
    private static List<ItemPicker.Entry> builtIn(ResourceKey<? extends Registry<?>> key) {
        Registry<?> registry = key == null ? null
                : BuiltInRegistries.REGISTRY.get(key.identifier()).map(holder -> holder.value())
                        .orElse(null);
        return registry == null ? List.of() : builtIn(registry, false);
    }

    private static List<ItemPicker.Entry> builtIn(Registry<?> registry, boolean withTags) {
        List<ItemPicker.Entry> out = rows(registry.keySet());
        if (withTags) {
            tags(registry, out, true);
        }
        return out;
    }

    /** A built-in registry's tags, by key, as the field that consumes them spells one. */
    private static List<ItemPicker.Entry> tags(ResourceKey<? extends Registry<?>> key, boolean hash) {
        Registry<?> registry = key == null ? null
                : BuiltInRegistries.REGISTRY.get(key.identifier()).map(holder -> holder.value())
                        .orElse(null);
        if (registry == null) {
            return List.of();
        }
        List<ItemPicker.Entry> out = new ArrayList<>();
        tags(registry, out, hash);
        return out;
    }

    /**
     * A registry's tags, as the field that consumes them spells one.
     *
     * <p>{@code hash} because the format has two spellings and both are right where they are used: a
     * {@code RegistryRef} field -- a biome, a structure -- writes a tag as {@code #minecraft:is_forest},
     * while an item or entity tag field holds the plain id, because its codec is a resource location and a
     * {@code #} is not one.
     */
    private static void tags(Registry<?> registry, List<ItemPicker.Entry> out, boolean hash) {
        registry.getTags().forEach(tag ->
                out.add(entry((hash ? "#" : "") + tag.key().location())));
    }

    /** The client's own advancement tree: everything the server has told it about. */
    private static List<ItemPicker.Entry> advancements(Minecraft minecraft) {
        List<ItemPicker.Entry> out = new ArrayList<>();
        if (minecraft.getConnection() == null) {
            return out;
        }
        for (AdvancementNode node : minecraft.getConnection().getAdvancements().tree().nodes()) {
            AdvancementHolder holder = node.holder();
            String label = holder.value().display()
                    .map(display -> display.title().getString())
                    .orElseGet(() -> EditorSpecs.label(holder.id().getPath()));
            out.add(new ItemPicker.Entry(holder.id().toString(), label, 1, ""));
        }
        return out;
    }

    /** Ids as rows, for a registry's own key set. */
    private static List<ItemPicker.Entry> rows(Collection<Identifier> ids) {
        List<ItemPicker.Entry> out = new ArrayList<>();
        ids.forEach(id -> out.add(entry(id.toString())));
        return out;
    }

    /** Ids as rows, for a list the server sent -- already spelling its own tags. */
    private static List<ItemPicker.Entry> rows(List<String> ids) {
        List<ItemPicker.Entry> out = new ArrayList<>();
        ids.forEach(id -> out.add(entry(id)));
        return out;
    }

    /** One row: the id, and the name a person reads. */
    private static ItemPicker.Entry entry(String id) {
        Identifier location = id.startsWith("#")
                ? Identifier.tryParse(id.substring(1))
                : Identifier.tryParse(id);
        String label = location == null ? id : EditorSpecs.label(location.getPath());
        return new ItemPicker.Entry(id, label, 1, "");
    }
}
