package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * One component template: a task, reward or condition setup an author saved or was shipped,
 * ready to be applied to a quest as a copy.
 *
 * <h2>Why a record of JSON rather than a decoded task</h2>
 *
 * <p>Because the library must list templates for types this build does not know — an addon
 * installed when the template was saved and missing now, or a preset naming a type from a
 * newer Tenet. Decoding eagerly would make those rows unloadable, and an unloadable row is
 * exactly what the Assets panel refuses to be (see its "Not loaded" list: refused files are
 * listed, not dropped). The payload stays {@link JsonObject} until the moment it is applied,
 * when the editor op validates it like any hand-written edit. A row whose type is unknown
 * draws its prettified id and disables Apply with the reason, and nothing throws.
 *
 * <h2>Copies, never links</h2>
 *
 * <p>{@link #sanitizedCopy(JsonObject)} deep-copies and strips identity keys, so applying a
 * template can never share an id with its source. The store hands out copies; the source
 * stays unreachable behind them, the way {@code ClientEditorClipboard} keeps pasted quests
 * from reaching back into their source.
 */
public final class ComponentTemplate {

    /** What a template holds: a task for a quest's list, a reward for it, or a condition for an entry. */
    public enum Kind {
        TASK,
        REWARD,
        CONDITION
    }

    /** Where a template came from: shipped with the mod, or saved by this client. */
    public enum Source {
        BUILT_IN,
        USER
    }

    /** Ids the mod ships live under this prefix, which user templates may not claim. */
    public static final String BUILT_IN_PREFIX = "built_in/";

    /** Keys that name an identity rather than a setup. Stripped on every copy out of the store. */
    private static final Set<String> IDENTITY_KEYS = Set.of("id", "uuid");

    private ComponentTemplate() {
    }

    /**
     * One template.
     *
     * @param id         the store key: {@code built_in/...} for shipped ones, a slug for saved ones
     * @param name       the words the row wears; plain text, never a lang key
     * @param kind       which list the payload belongs on
     * @param typeId     the component type, e.g. {@code tenet:item}; may name an unknown type
     * @param payload    the component tree, as the file would hold it
     * @param source     shipped or saved
     * @param lastUsedAt when Apply last landed one, epoch millis, or 0 for never
     * @param useCount   how many Applies have landed, for a future "most used" sort
     * @param createdAt  when the template was saved or shipped, epoch millis
     */
    public record Template(String id, String name, Kind kind, String typeId, JsonObject payload,
                           Source source, long lastUsedAt, long useCount, long createdAt) {

        public Template {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(typeId, "typeId");
            Objects.requireNonNull(source, "source");
            name = name == null || name.isBlank() ? prettify(typeId) : name;
            payload = payload == null ? new JsonObject() : (JsonObject) payload.deepCopy();
            lastUsedAt = Math.max(0, lastUsedAt);
            useCount = Math.max(0, useCount);
            createdAt = Math.max(0, createdAt);
        }

        /** Whether this template was shipped with the mod: listed, applied, never renamed or deleted. */
        public boolean builtIn() {
            return source == Source.BUILT_IN;
        }

        /** A copy of the payload with identity keys stripped: what Apply inserts. */
        public JsonObject freshCopy() {
            return sanitizedCopy(payload);
        }
    }

    /**
     * A payload safe to insert: deep-copied so later edits of the quest cannot reach back into
     * the template, with identity keys removed so the copy never shares an id with its source.
     */
    public static JsonObject sanitizedCopy(JsonObject tree) {
        JsonObject copy = tree == null ? new JsonObject() : (JsonObject) tree.deepCopy();
        for (String key : IDENTITY_KEYS) {
            copy.remove(key);
        }
        return copy;
    }

    /**
     * A store key from a display name: lower-case letters, digits and underscores, the same rule
     * a quest id follows, so a template id reads like every other id in the format.
     */
    public static String slug(String name) {
        String base = name == null ? "" : name.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return base.isEmpty() ? "template" : base;
    }

    /**
     * A key no template in {@code taken} holds, preferring the one asked for.
     *
     * <p>The ladder is {@code ElementDefaults}' on purpose — {@code box}, {@code box_2} — so an
     * id minted anywhere in the editor reads like one minted anywhere else.
     */
    public static String uniqueId(String wanted, Set<String> taken) {
        String base = wanted == null || wanted.isBlank() ? "template" : wanted;
        if (taken == null || !taken.contains(base)) {
            return base;
        }
        for (int n = 2; n < 1000; n++) {
            String candidate = base + "_" + n;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        return base + "_" + System.currentTimeMillis();
    }

    /**
     * The row's second line: what this template asks for or gives, in plain words.
     *
     * <p>Read off the raw JSON rather than decoded through the registries, so an unknown type
     * still gets a sentence instead of an exception. Approximations for the list only — the
     * editor form stays the source of truth — which is why every arm falls back to the
     * prettified type id rather than guessing at fields it cannot see.
     */
    public static String blurb(Kind kind, String typeId, JsonObject payload) {
        if (kind == null) {
            return "";
        }
        JsonObject tree = payload == null ? new JsonObject() : payload;
        return switch (kind) {
            case TASK -> taskBlurb(typeId, tree);
            case REWARD -> rewardBlurb(typeId, tree);
            case CONDITION -> conditionBlurb(typeId, tree);
        };
    }

    private static String taskBlurb(String typeId, JsonObject tree) {
        String path = pathOf(typeId);
        return switch (path) {
            case "item" -> {
                boolean consume = flag(tree, "consumeItems");
                yield (consume ? "Hand in " : "Gather ") + count(tree, "count", 1) + " × " + item(tree);
            }
            case "item_tag" -> "Gather " + count(tree, "count", 1) + " × #" + text(tree, "tag", "tagged");
            case "kill" -> "Defeat " + count(tree, "value", 1) + " × " + prettify(text(tree, "entity", "foes"));
            case "break" -> "Break " + count(tree, "value", 1) + " × " + prettify(text(tree, "block",
                    text(tree, "blockTag", "blocks")));
            case "checkmark" -> {
                String title = text(tree, "title", "");
                yield title.isEmpty() ? "Tick the box" : title;
            }
            case "advancement" -> "Earn " + text(tree, "advancement", "an advancement");
            case "dimension" -> "Visit " + prettify(text(tree, "dimension", "a dimension"));
            case "biome" -> "Visit " + text(tree, "biome", "a biome");
            case "structure" -> "Find " + text(tree, "structure", "a structure");
            case "location" -> "Stand at the marked place";
            case "stat" -> "Raise " + prettify(text(tree, "stat", "a statistic"));
            case "stage" -> "Hold stage " + text(tree, "stage", "?");
            case "xp" -> "Hand in " + count(tree, "value", 1) + " XP";
            case "fluid" -> "Hand in " + count(tree, "amount", 1) + " mB of " + text(tree, "fluid", "fluid");
            case "energy" -> "Hand in " + count(tree, "value", 1) + " FE";
            case "filter" -> "Carry matching items";
            case "observation" -> "Observe " + text(tree, "toObserve", "the target");
            default -> prettify(typeId);
        };
    }

    private static String rewardBlurb(String typeId, JsonObject tree) {
        String path = pathOf(typeId);
        return switch (path) {
            case "item" -> "Gives " + itemCount(tree) + " × " + item(tree);
            case "xp" -> {
                int levels = intField(tree, "levels", 0);
                yield levels > 0 ? "Gives " + levels + " levels" : "Gives " + intField(tree, "amount", 1) + " XP";
            }
            case "command" -> "Runs " + truncate(text(tree, "command", "a command"), 28);
            case "advancement" -> "Grants " + text(tree, "advancement", "an advancement");
            case "stage" -> "Grants stage " + text(tree, "stage", "?");
            case "currency" -> "Pays " + count(tree, "amount", 1);
            case "random", "loot", "all_table", "choice" -> "Rolls a reward table";
            case "toast" -> "Shows a toast";
            case "custom" -> "Custom reward";
            default -> prettify(typeId);
        };
    }

    private static String conditionBlurb(String typeId, JsonObject tree) {
        String path = pathOf(typeId);
        return switch (path) {
            case "item" -> "Needs " + count(tree, "count", 1) + " × " + item(tree);
            case "item_tag" -> "Needs #" + text(tree, "tag", "tagged");
            case "score" -> "Scores " + text(tree, "objective", "?") + " ≥ " + text(tree, "min", "1");
            case "advancement" -> "Requires " + text(tree, "advancement", "an advancement");
            case "stage" -> "Requires stage " + text(tree, "stage", "?");
            case "party_size" -> "Needs " + count(tree, "min", 2) + " online";
            default -> "If " + prettify(typeId).toLowerCase(Locale.ROOT);
        };
    }

    /** The item a tree is about: a plain id or an object with one, prettified for the row. */
    private static String item(JsonObject tree) {
        JsonElement item = tree.get("item");
        if (item == null || item.isJsonNull()) {
            return "items";
        }
        if (item.isJsonPrimitive()) {
            return prettify(item.getAsString());
        }
        if (item.isJsonObject() && item.getAsJsonObject().has("item")) {
            return prettify(item.getAsJsonObject().get("item").getAsString());
        }
        return "items";
    }

    /** An item reward's count: the item object's own, or the tree's, defaulting to one. */
    private static String itemCount(JsonObject tree) {
        JsonElement item = tree.get("item");
        if (item != null && item.isJsonObject() && item.getAsJsonObject().has("count")) {
            try {
                return String.valueOf(Math.max(1, item.getAsJsonObject().get("count").getAsInt()));
            }
            catch (RuntimeException ignored) {
                return "1";
            }
        }
        return String.valueOf(count(tree, "count", 1));
    }

    private static int count(JsonObject tree, String field, int fallback) {
        return intField(tree, field, fallback);
    }

    private static int intField(JsonObject tree, String field, int fallback) {
        try {
            return tree.has(field) && tree.get(field).isJsonPrimitive()
                    ? Math.max(1, tree.get(field).getAsInt())
                    : fallback;
        }
        catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String text(JsonObject tree, String field, String fallback) {
        try {
            return tree.has(field) && tree.get(field).isJsonPrimitive()
                    ? tree.get(field).getAsString()
                    : fallback;
        }
        catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean flag(JsonObject tree, String field) {
        try {
            return tree.has(field) && tree.get(field).isJsonPrimitive() && tree.get(field).getAsBoolean();
        }
        catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String pathOf(String typeId) {
        if (typeId == null) {
            return "";
        }
        int slash = typeId.lastIndexOf(':');
        String path = slash >= 0 ? typeId.substring(slash + 1) : typeId;
        int dot = path.lastIndexOf('.');
        return (dot >= 0 ? path.substring(dot + 1) : path).toLowerCase(Locale.ROOT);
    }

    /**
     * An id as words: {@code minecraft:oak_log} reads "Oak log".
     *
     * <p>The same prettifying the pickers do for registry rows, inlined because this class is
     * game-free and the pickers' helper lives behind the client.
     */
    public static String prettify(String id) {
        if (id == null || id.isBlank()) {
            return "?";
        }
        String path = pathOf(id).replace('_', ' ').strip();
        if (path.isEmpty()) {
            return id;
        }
        // A tag keeps its marker: "#minecraft:is_forest" is a tag, and dropping the marker would
        // teach that a tag and an id spell the same.
        String marker = id.strip().startsWith("#") ? "#" : "";
        return marker + Character.toUpperCase(path.charAt(0)) + path.substring(1);
    }

    private static String truncate(String text, int width) {
        if (text == null) {
            return "";
        }
        return text.length() <= width ? text : text.substring(0, Math.max(0, width - 1)) + "…";
    }
}
