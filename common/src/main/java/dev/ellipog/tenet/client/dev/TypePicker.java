package dev.ellipog.tenet.client.dev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The type picker's catalogue: what can be added, in the order an author learns it, and what a query
 * makes of it.
 *
 * <h2>Why this is a class rather than the screen's own loop</h2>
 *
 * <p>For the reason {@link ItemPicker} and {@link TableBrowserLayout} are: the questions here are
 * answerable without a client — which group a type is listed under, what order two matches come in, what
 * a blank search box shows — and a screen cannot be asked any of them. The screen supplies the entries
 * (the registries and whether a table will take each one) and the drawing; this decides everything a
 * person could disagree with.
 *
 * <h2>Why the catalogue arrives as entries rather than as rows</h2>
 *
 * <p>Because the catalogue's *shape* — a heading, then the types under it — is the screen's, made of
 * {@code InspectRow}s that a panel draws, and this class must not know about that drawing. An
 * {@link Entry} is a type, its group, and the sentence a refusal carries; the rows this produces are
 * {@link ItemPickerLayout}'s, which is what makes the type list read like the item list.
 *
 * <h2>The three decisions</h2>
 *
 * <ul>
 *   <li><b>A blank box lists everything, in catalogue order.</b> That order is the tables' — the groups
 *       an author sees when they first open the page — and a list that reordered itself as you learn it
 *       would be a list you cannot learn. The item picker shows only what you carry until you type, and
 *       the difference is deliberate: there is no "carried" half of a list of types.</li>
 *   <li><b>A query ranks id before name and drops the group headings.</b> Matches are answers rather
 *       than a page, so the headings that make the page readable would only break the ranking up. This
 *       is the item picker's own rule, from {@link ItemPicker#rank}, reused rather than restated.</li>
 *   <li><b>A group's name is not a search term.</b> The alternative is a second ranking with a third
 *       thing to match, and what an author types here is a type's name or its id.</li>
 * </ul>
 */
public final class TypePicker {

    /**
     * One type that can be added.
     *
     * @param id     the row's own id: a registered type id, which is what a file spells
     * @param label  the name in the author's words; falls back to the id when a table names none
     * @param group  the heading it is listed under, or empty for a list with no headings
     * @param note   why it cannot be taken, or empty — see {@link ItemPickerLayout.Row#note()}
     * @param detail what the row shows at its right end: the id, unless the entry stands for
     *               something with its own spelling — a saved template shows its type, which is
     *               what a file would hold, rather than the shelf's own row id
     */
    public record Entry(String id, String label, String group, String note, String detail) {

        public Entry {
            Objects.requireNonNull(id, "id");
            label = label == null || label.isBlank() ? id : label;
            group = group == null ? "" : group;
            note = note == null ? "" : note;
            detail = detail == null || detail.isBlank() ? id : detail;
        }

        /** A type with nothing against it. */
        public Entry(String id, String label, String group) {
            this(id, label, group, "", null);
        }

        /** A type with a refusal. */
        public Entry(String id, String label, String group, String note) {
            this(id, label, group, note, null);
        }
    }

    private TypePicker() {
    }

    /**
     * The rows a type picker shows: the page's heading, then the catalogue, or the query's matches.
     *
     * @param entries the catalogue, in the order the table that names these types lists them
     * @param query   what is in the search box; blank lists the whole catalogue
     * @param heading the page's own first line, or null when the caller's chrome already says it —
     *                the card's does not, and the table editor's strip says "Add a reward" already
     */
    public static List<ItemPickerLayout.Row> compose(List<Entry> entries, String query, String heading) {
        List<ItemPickerLayout.Row> rows = new ArrayList<>();
        String search = query == null ? "" : query.trim();
        if (search.isEmpty()) {
            if (heading != null && !heading.isBlank()) {
                rows.add(ItemPickerLayout.Row.of(ItemPickerLayout.Kind.HEADING, "", heading, ""));
            }
            String current = null;
            for (Entry entry : entries) {
                if (!entry.group().equals(current)) {
                    current = entry.group();
                    if (!current.isBlank()) {
                        rows.add(ItemPickerLayout.Row.of(ItemPickerLayout.Kind.HEADING, "", current, ""));
                    }
                }
                rows.add(row(entry));
            }
            return List.copyOf(rows);
        }

        // The matches, best first, capped -- the ranking the item picker uses, over the same shape of
        // entry. The name travels as the label so a name match ranks; the id is the secondary.
        List<ItemPicker.Entry> catalogue = new ArrayList<>(entries.size());
        Map<String, Entry> byId = new LinkedHashMap<>();
        for (Entry entry : entries) {
            catalogue.add(new ItemPicker.Entry(entry.id(), entry.label(), 1));
            byId.putIfAbsent(entry.id(), entry);
        }
        for (ItemPicker.Entry match : ItemPicker.rank(catalogue, search, ItemPicker.LIMIT)) {
            Entry entry = byId.get(match.id());
            if (entry != null) {
                rows.add(row(entry));
            }
        }
        return List.copyOf(rows);
    }

    /** One catalogue entry as a row: name, the id at the right, and the refusal when there is one. */
    private static ItemPickerLayout.Row row(Entry entry) {
        return entry.note().isEmpty()
                ? ItemPickerLayout.Row.of(ItemPickerLayout.Kind.TYPE, entry.id(), entry.label(),
                        entry.detail())
                : ItemPickerLayout.Row.blocked(ItemPickerLayout.Kind.TYPE, entry.id(), entry.label(),
                        entry.detail(), entry.note());
    }
}
