package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.api.data.JsonWrite;
import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.client.dev.ComponentTemplate.Kind;
import dev.ellipog.tenet.client.dev.ComponentTemplate.Source;
import dev.ellipog.tenet.client.dev.ComponentTemplate.Template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The component template library: the shipped presets plus what this client saved, in memory
 * and in one file.
 *
 * <h2>Why client-local rather than server-synced</h2>
 *
 * <p>Templates are author tooling, not pack content: the pack never names them, the server
 * never reads them, and what Apply sends is an ordinary validated edit — an {@code Insert}
 * for a task or reward, a rebuilt conditions array for a condition — through the same ops a
 * hand-made edit takes. Syncing the library would need new payloads, a new refresh scope and
 * a multiplayer conflict story, all so a second author could see the first's scratch shelf.
 * A client-local file is instant, offline and conflict-free, and sharing one is copying its
 * JSON — the format's own lingua franca.
 *
 * <h2>Why the sort lives next door</h2>
 *
 * <p>The order is a hint about where the author was, like the Assets section itself, so it
 * is persisted by {@code ClientWorking} beside it rather than here. This store sorts on
 * demand: {@link #sorted(Sort)} answers the library in an order, and the screen splits it
 * under its two headings.
 *
 * <h2>What the entry context is</h2>
 *
 * <p>Condition templates need an entry to land on, and entries are not selectable — so the
 * store remembers the last one the author touched (saved, added or picked a condition for),
 * session-only and never written. An Apply with a live context lands there; one without is
 * refused out loud with the way to the entry's own picker, which always works. A hint, not
 * state: {@link #entryContext()} is verified against the open quest before every use.
 */
public final class ComponentTemplates {

    /** The file, beside {@code working.json}. */
    public static final String FILE_NAME = "component_templates.json";

    /** The orders the Templates page offers, as the file spells them. */
    public enum Sort {
        NAME,
        TYPE,
        RECENT;

        /** One of the three, or the default: a typo opens a panel rather than a blank one. */
        public static Sort known(String candidate) {
            if (candidate == null) {
                return RECENT;
            }
            return switch (candidate.strip().toLowerCase(Locale.ROOT)) {
                case "name" -> NAME;
                case "type" -> TYPE;
                default -> RECENT;
            };
        }

        /** The spelling the file holds. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The last entry the author touched, for a condition Apply to land on. Session-only. */
    public record EntryContext(String questId, String member, int index) {
    }

    private static final List<Template> user = new ArrayList<>();
    private static volatile EntryContext entryContext;
    private static volatile Path file;

    private ComponentTemplates() {
    }

    /** Every template: the shipped presets first, then what this client saved, in store order. */
    public static List<Template> all() {
        List<Template> out = new ArrayList<>(ComponentTemplatePresets.builtIns());
        synchronized (user) {
            out.addAll(user);
        }
        return List.copyOf(out);
    }

    /** The shipped presets. */
    public static List<Template> builtIns() {
        return ComponentTemplatePresets.builtIns();
    }

    /** What this client saved, in the order it was saved. */
    public static List<Template> saved() {
        synchronized (user) {
            return List.copyOf(user);
        }
    }

    /** One template by id, shipped or saved. */
    public static Optional<Template> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (Template template : all()) {
            if (template.id().equals(id)) {
                return Optional.of(template);
            }
        }
        return Optional.empty();
    }

    /**
     * The library in an order.
     *
     * <p>Recent first means recently applied first, with never-applied after — a shelf that
     * tidies itself around what the author actually reaches for. Name orders by display name
     * in the client's locale; type groups by kind, then type, then name.
     */
    public static List<Template> sorted(Sort sort) {
        List<Template> out = new ArrayList<>(all());
        Collator words = Collator.getInstance();
        Comparator<Template> order = switch (sort == null ? Sort.RECENT : sort) {
            case NAME -> Comparator.comparing(Template::name, words)
                    .thenComparing(Template::typeId)
                    .thenComparing(Template::id);
            case TYPE -> Comparator.comparing(Template::kind)
                    .thenComparing(Template::typeId)
                    .thenComparing(Template::name, words);
            case RECENT -> (first, second) -> {
                if (first.lastUsedAt() != second.lastUsedAt()) {
                    // Used sorts before never, and later before earlier: a zero is older than
                    // any real timestamp, so never-used sinks without a special case.
                    return Long.compare(second.lastUsedAt(), first.lastUsedAt());
                }
                if (first.useCount() != second.useCount()) {
                    return Long.compare(second.useCount(), first.useCount());
                }
                return words.compare(first.name(), second.name());
            };
        };
        out.sort(order);
        return List.copyOf(out);
    }

    /**
     * Saves a component setup as a template and writes the file.
     *
     * @param kind     which list the payload belongs on
     * @param typeId   the component type, e.g. {@code tenet:item}
     * @param payload  the setup, as the quest holds it; copied, never kept
     * @param nameHint the words the row should wear; blank falls back to the blurb
     * @return the saved template
     */
    public static Template saveNew(Kind kind, String typeId, JsonObject payload, String nameHint) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(typeId, "typeId");
        JsonObject tree = ComponentTemplate.sanitizedCopy(payload);
        if (!tree.has("type")) {
            tree.addProperty("type", typeId);
        }
        String name = nameHint == null || nameHint.isBlank()
                ? ComponentTemplate.blurb(kind, typeId, tree)
                : nameHint.strip();
        Set<String> taken = new HashSet<>();
        for (Template template : all()) {
            taken.add(template.id());
        }
        String id = ComponentTemplate.uniqueId(ComponentTemplate.slug(name), taken);
        long now = System.currentTimeMillis();
        Template saved = new Template(id, name, kind, typeId, tree, Source.USER, now, 0, now);
        synchronized (user) {
            user.add(saved);
        }
        save();
        return saved;
    }

    /**
     * Deletes a saved template and writes the file.
     *
     * @return whether anything was deleted: shipped templates and unknown ids refuse quietly
     */
    public static boolean delete(String id) {
        if (id == null) {
            return false;
        }
        synchronized (user) {
            for (int i = 0; i < user.size(); i++) {
                if (user.get(i).id().equals(id)) {
                    user.remove(i);
                    save();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Renames a saved template and writes the file. The id never changes: it is the store key,
     * and a rename is new words for the same setup rather than a new setup.
     *
     * @return whether anything was renamed: shipped templates, unknown ids and blank names
     *         refuse quietly
     */
    public static boolean rename(String id, String name) {
        if (id == null || name == null || name.isBlank()) {
            return false;
        }
        synchronized (user) {
            for (int i = 0; i < user.size(); i++) {
                Template template = user.get(i);
                if (template.id().equals(id)) {
                    user.set(i, new Template(template.id(), name.strip(), template.kind(),
                            template.typeId(), template.payload(), template.source(),
                            template.lastUsedAt(), template.useCount(), template.createdAt()));
                    save();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A template holding this exact setup, shipped or saved, if one is already on the shelf.
     *
     * <p>Setup means kind, type and payload — the name is words the row wears, and two rows with
     * different words for the same setup are still the same setup. Compared after the same
     * normalisation saving applies, so a tree missing its {@code type} still matches the row it
     * would have become. For the star that would otherwise shelve one setup per press: pressing
     * it ten times lists it once.
     */
    public static Optional<Template> duplicateOf(Kind kind, String typeId, JsonObject payload) {
        if (kind == null || typeId == null) {
            return Optional.empty();
        }
        JsonObject tree = ComponentTemplate.sanitizedCopy(payload);
        if (!tree.has("type")) {
            tree.addProperty("type", typeId);
        }
        for (Template template : all()) {
            if (template.kind() == kind && template.typeId().equals(typeId)
                    && template.payload().equals(tree)) {
                return Optional.of(template);
            }
        }
        return Optional.empty();
    }

    /** Records a landing: the template sorts as recently used from now on. Writes the file. */
    public static void markUsed(String id) {
        if (id == null) {
            return;
        }
        synchronized (user) {
            for (int i = 0; i < user.size(); i++) {
                Template template = user.get(i);
                if (template.id().equals(id)) {
                    long now = System.currentTimeMillis();
                    user.set(i, new Template(template.id(), template.name(), template.kind(),
                            template.typeId(), template.payload(), template.source(), now,
                            template.useCount() + 1, template.createdAt()));
                    save();
                    return;
                }
            }
        }
        // Shipped templates sort by their own recency too, but there is nowhere to write it:
        // the jar is read-only, and a sidecar of use counts is bookkeeping nobody asked to
        // back up. Built-ins keep their catalogue order, which the author can already learn.
    }

    /** Remembers the entry the author touched, for a condition Apply to land on. */
    public static void noteEntryContext(String questId, String member, int index) {
        if (questId == null || member == null || index < 0) {
            return;
        }
        entryContext = new EntryContext(questId, member, index);
    }

    /** The remembered entry, or empty when the author has touched none this session. */
    public static Optional<EntryContext> entryContext() {
        return Optional.ofNullable(entryContext);
    }

    /** Forgets the remembered entry. For leaving a world, where another server's quests are not this one's to land on. */
    public static void clearEntryContext() {
        entryContext = null;
    }

    /** Forgets the templates and the file. For tests. */
    public static void reset() {
        synchronized (user) {
            user.clear();
        }
        entryContext = null;
        file = null;
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /** Reads the library from the platform's config directory. Called once by each loader's client. */
    public static void loadFromConfig() {
        Path path = null;
        try {
            path = ArmatureApi.platform().configDir(dev.ellipog.tenet.Constants.MOD_ID).resolve(FILE_NAME);
        }
        catch (RuntimeException e) {
            Constants.LOG.warn("the platform layer was not ready, so the template library"
                    + " was not read.", e);
        }
        if (path != null) {
            load(path);
        }
    }

    /**
     * Reads the library from one file.
     *
     * <p>A missing file is a first run and gets no message; a file that is there and cannot be
     * read keeps nothing half-set, and an entry that is not an object — or names no kind, type
     * or payload — is skipped with the file's name, so one bad save never costs the shelf.
     */
    public static void load(Path path) {
        file = path;
        synchronized (user) {
            user.clear();
        }
        entryContext = null;
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) {
                throw new IllegalStateException("the library is not an object");
            }
            JsonElement entries = root.getAsJsonObject().get("templates");
            if (entries == null || entries.isJsonNull()) {
                return;
            }
            if (!entries.isJsonArray()) {
                throw new IllegalStateException("\"templates\" is not an array");
            }
            int skipped = 0;
            for (JsonElement element : entries.getAsJsonArray()) {
                Template template = readTemplate(element);
                if (template == null) {
                    skipped++;
                    continue;
                }
                synchronized (user) {
                    user.add(template);
                }
            }
            if (skipped > 0) {
                Constants.LOG.warn("{} held {} template(s) that could not be read, so they"
                        + " were skipped rather than dropping the shelf.", path, skipped);
            }
        }
        catch (IOException | RuntimeException e) {
            synchronized (user) {
                user.clear();
            }
            Constants.LOG.warn("{} could not be read, so no saved templates are listed."
                    + " Deleting the file will stop this message.", path, e);
        }
    }

    /** The file this client reads and writes, or null before {@link #loadFromConfig} has run. */
    public static Path file() {
        return file;
    }

    /** The saved templates as the file holds them. Answer given rather than the default so a test can assert the format. */
    public static String write() {
        JsonObject root = new JsonObject();
        JsonArray entries = new JsonArray();
        synchronized (user) {
            for (Template template : user) {
                entries.add(writeTemplate(template));
            }
        }
        root.add("templates", entries);
        return root.toString();
    }

    private static Template readTemplate(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject json = element.getAsJsonObject();
        if (!json.has("kind") || !json.get("kind").isJsonPrimitive()
                || !json.has("type") || !json.get("type").isJsonPrimitive()
                || !json.has("payload") || !json.get("payload").isJsonObject()) {
            return null;
        }
        Kind kind;
        try {
            kind = Kind.valueOf(json.get("kind").getAsString().strip().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException unknown) {
            return null;
        }
        String id = json.has("id") && json.get("id").isJsonPrimitive()
                ? json.get("id").getAsString()
                : "";
        if (id.isBlank() || id.startsWith(ComponentTemplate.BUILT_IN_PREFIX)) {
            // The jar's prefix is the jar's: a user file claiming it is either a hand edit or a
            // copy from another shelf, and either way honouring it would shadow a shipped row.
            return null;
        }
        String name = json.has("name") && json.get("name").isJsonPrimitive()
                ? json.get("name").getAsString()
                : "";
        long lastUsedAt = longField(json, "lastUsedAt");
        long useCount = longField(json, "useCount");
        long createdAt = longField(json, "createdAt");
        return new Template(id.strip(), name, kind, json.get("type").getAsString(),
                json.getAsJsonObject("payload"), Source.USER, lastUsedAt, useCount, createdAt);
    }

    private static long longField(JsonObject json, String field) {
        try {
            return json.has(field) && json.get(field).isJsonPrimitive()
                    ? Math.max(0, json.get(field).getAsLong())
                    : 0;
        }
        catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static JsonObject writeTemplate(Template template) {
        JsonObject json = new JsonObject();
        json.addProperty("id", template.id());
        json.addProperty("name", template.name());
        json.addProperty("kind", template.kind().name());
        json.addProperty("type", template.typeId());
        json.add("payload", ComponentTemplate.sanitizedCopy(template.payload()));
        json.addProperty("lastUsedAt", template.lastUsedAt());
        json.addProperty("useCount", template.useCount());
        json.addProperty("createdAt", template.createdAt());
        return json;
    }

    private static void save() {
        if (file == null) {
            // No path means no platform: in a test, or in a client whose config directory could
            // not be resolved. The values still work for this session, which is the useful half.
            return;
        }
        try {
            JsonWrite.atomically(file, write());
        }
        catch (IOException e) {
            Constants.LOG.warn("the template library could not be written to {}", file, e);
        }
    }
}
