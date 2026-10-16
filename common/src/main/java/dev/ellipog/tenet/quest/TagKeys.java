package dev.ellipog.tenet.quest;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tag keys by registry and id, built once and kept.
 *
 * <p>{@code TagKey.create} is a small allocation with validation attached, and the call sites
 * that need one are the hottest in the mod: the tick's inventory scans ask per slot per member,
 * the death and break events ask per task per event, and the conditions ask per member per task.
 * None of those answers can move — a tag key names a registry and an id, and neither changes
 * without a restart — so each distinct pair is built once.
 *
 * <p>Bounded by construction rather than by a cap: the pairs are the pack's own tag ids across a
 * handful of registries, so the table cannot grow past what the files name. Concurrent because
 * deaths, breaks and ticks share it.
 */
public final class TagKeys {

    private TagKeys() {
    }

    private static final Map<ResourceKey<?>, Map<Identifier, TagKey<?>>> KEYS =
            new ConcurrentHashMap<>();

    /**
     * The tag key for this registry and id, remembered.
     *
     * <p>Equal in every way to {@code TagKey.create(registry, id)} — including the failure for a
     * null id, which stays a loud error rather than a quiet miss.
     */
    @SuppressWarnings("unchecked")
    public static <T> TagKey<T> of(ResourceKey<? extends Registry<T>> registry, Identifier id) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(id, "id");
        Map<Identifier, TagKey<?>> byId =
                KEYS.computeIfAbsent(registry, missing -> new ConcurrentHashMap<>());
        return (TagKey<T>) byId.computeIfAbsent(id, missing -> TagKey.create(registry, missing));
    }
}
