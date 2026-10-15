package dev.ellipog.tenet.client.dev;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The item picker's rules: what a query offers, in what order, and when the typed text is the answer.
 *
 * <h2>Why the rules are here and the registry is not</h2>
 *
 * <p>The screen supplies the entries -- the registry's items with their display names, and what the
 * player is carrying -- and this class decides everything a person could disagree with: the ranking,
 * the cap, and what Enter does. That split is what makes the rules testable without a client, which is
 * the same reason every other panel's arithmetic lives in a class like this one.
 *
 * <p>An entry's {@code count} is how many the player carries; zero means the list it came from is not
 * the inventory's. Nothing here reads a count -- the drawing says it -- but it travels with the entry
 * so the two sections cannot be built with different ideas of what a row is.
 */
public final class ItemPicker {

    /**
     * How many matches a query may offer.
     *
     * <p>Not a limit on what can be picked -- a typed exact id always commits -- but on what is drawn:
     * a one-letter query matches most of the registry, and a list nobody scrolls to the end of is a
     * list that may as well not be one. The ranking means the cap keeps the best of them.
     */
    public static final int LIMIT = 100;

    /**
     * One pickable item: its id, what it is called, how many the player carries, and its custom data.
     *
     * <p>{@code data} is the component patch as JSON text, opaque here on purpose: the registry's
     * entries have none, a carried stack has whatever it was crafted or renamed with, and this class
     * neither reads nor ranks it. The screen writes it into the field beside the id -- which is the
     * whole of "pick the sword you are holding, not a sword".
     */
    public record Entry(String id, String label, int count, String data) {

        public Entry {
            Objects.requireNonNull(id, "id");
            label = label == null ? "" : label;
            data = data == null ? "" : data;
        }

        /** An entry with nothing custom about it. */
        public Entry(String id, String label, int count) {
            this(id, label, count, "");
        }
    }

    /**
     * The entries a query offers, best first, capped at {@code limit}.
     *
     * <h2>The ranking, and why each rank is where it is</h2>
     *
     * <p>Exact id first (someone pasted a whole id), then the id starting with the query, then the id
     * containing it, then the display name -- prefix before containment, because "oak" meaning Oak
     * Planks should not be preceded by every item with "oak" in the middle of its name. The namespace
     * is matched too but never required: {@code "oak"} matches {@code minecraft:oak_log} through the
     * path half, which is how a person types.
     *
     * <p>Inside a rank the id decides, so two players whose registries hold the same items in
     * different orders are offered the same list -- a list that reshuffles between sessions is a list
     * you cannot learn.
     *
     * <p>A blank query offers nothing at all: the empty box shows the inventory instead, and an empty
     * box that listed the whole registry would be a worse inventory.
     */
    public static List<Entry> rank(List<Entry> entries, String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty() || limit <= 0 || entries.isEmpty()) {
            return List.of();
        }
        List<Ranked> hits = new ArrayList<>();
        for (Entry entry : entries) {
            int rank = rankOf(entry, q);
            if (rank >= 0) {
                hits.add(new Ranked(rank, entry));
            }
        }
        hits.sort(Comparator.comparingInt(Ranked::rank).thenComparing(hit -> hit.entry().id()));
        List<Entry> out = new ArrayList<>();
        for (Ranked hit : hits) {
            if (out.size() >= limit) {
                break;
            }
            out.add(hit.entry());
        }
        return List.copyOf(out);
    }

    /**
     * The path a field's clear row removes, or null when the field may not be cleared at all.
     *
     * <p>The icon is the one optional item field: when the whole {@code "icon"} object is absent the
     * format's default applies, so clearing removes <b>that</b> -- deleting only {@code "icon.item"}
     * would leave {@code {"icon": {}}}, which the codec refuses. And a task's or a reward's item is
     * required by its type, so there is nothing a clear row could do there but corrupt the file: the
     * defect this method exists to make unreachable. The row is simply not offered.
     *
     * <p>A task's or a reward's picture <i>override</i> is optional like the quest's own icon, so its
     * clear row removes the whole override object it names.
     */
    public static String clearPath(String fieldPath) {
        if ("icon.item".equals(fieldPath) || "icon.texture".equals(fieldPath)
                || "icon.sprite".equals(fieldPath) || "icon.entity".equals(fieldPath)) {
            return "icon";
        }
        if (fieldPath != null && fieldPath.endsWith(".icon")) {
            return fieldPath;
        }
        return null;
    }

    /**
     * The typed text as an id worth offering even though nothing matches it, or null.
     *
     * <p>"Allow items the build does not have" has to include writing one on purpose: an author
     * building a pack before the mod is installed types the id and needs it kept. The test is only
     * that it parses as a resource location and names nothing already offered -- a typo the author
     * confirms by pressing the row, which is exactly the consent a deliberate missing id needs.
     */
    public static String missingCandidate(String query, List<Entry> matches) {
        return missingCandidate(query, matches, false);
    }

    /**
     * The same, for a field whose values may be a {@code #tag}.
     *
     * <p>A {@code RegistryRef} field -- a biome, a structure -- writes a tag as {@code #minecraft:village},
     * and a resource location cannot hold a {@code #}. So without this the box refuses a tag the field
     * takes, which is a value the author can only set by editing the file. The flag is the caller's
     * because only the caller knows which spelling its field's codec reads: an item tag field's codec is
     * a bare resource location, and a {@code #} there is still not an id.
     */
    public static String missingCandidate(String query, List<Entry> matches, boolean allowTag) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String typed = query.trim();
        boolean tag = allowTag && typed.startsWith("#");
        String id = tag ? typed.substring(1) : typed;
        if (id.indexOf(':') <= 0) {
            // A bare word is a search, not an id: "oak" should list the oak things, not offer to write
            // an id nobody typed. A deliberate missing item always names its namespace.
            return null;
        }
        if (net.minecraft.resources.Identifier.tryParse(id) == null) {
            return null;
        }
        for (Entry entry : matches) {
            if (entry.id().equalsIgnoreCase(typed)) {
                return null;
            }
        }
        return typed;
    }

    /**
     * The id the typed text itself names, or null when it names nothing in the list.
     *
     * <p>This is what makes the box a field as well as a search: an author who knows the id types it
     * and presses Enter without waiting for the list. It is deliberately not a fallback to "the first
     * match" -- Enter on a half-typed word committing whatever happened to sort first is a way to set
     * a field to an item nobody chose.
     */
    public static String exactId(String query, List<Entry> matches) {
        if (query == null || query.isBlank() || matches == null) {
            return null;
        }
        String q = query.trim();
        for (Entry entry : matches) {
            if (entry.id().equalsIgnoreCase(q)) {
                return entry.id();
            }
        }
        return null;
    }

    private record Ranked(int rank, Entry entry) {
    }

    /** The best rank any of an entry's strings gives the query, or -1 for no match. */
    private static int rankOf(Entry entry, String q) {
        String id = entry.id().toLowerCase(Locale.ROOT);
        int colon = id.indexOf(':');
        String path = colon < 0 ? id : id.substring(colon + 1);
        String label = entry.label().toLowerCase(Locale.ROOT);
        if (id.equals(q) || path.equals(q)) {
            return 0;
        }
        if (id.startsWith(q) || path.startsWith(q)) {
            return 1;
        }
        if (id.contains(q) || path.contains(q)) {
            return 2;
        }
        if (label.startsWith(q)) {
            return 3;
        }
        if (label.contains(q)) {
            return 4;
        }
        return -1;
    }

    private ItemPicker() {
    }
}
