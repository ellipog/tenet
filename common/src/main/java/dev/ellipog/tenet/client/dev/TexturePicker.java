package dev.ellipog.tenet.client.dev;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * The texture picker's rules: which of a pack's files are offered, what a row is called, and which
 * rows exist for an id the pack does not hold.
 *
 * <h2>Why the catalogue is an argument and the rules are here</h2>
 *
 * <p>The screen owns the resource manager -- which files the client actually has is the game's answer,
 * read from {@code listResources("textures", ...)} -- and this class decides everything about those
 * files a person could disagree with: which one is a picture, what a row shows, and how a query ranks
 * them. That is the same split {@link ItemPicker} makes for items, and it is what lets the rules be
 * asserted without a client: a test hands in resource locations and reads rows back.
 *
 * <h2>Why these are entries and not a second record</h2>
 *
 * <p>A texture row and an item row are the same shape to the list: an id, a label and a string of small
 * print. Reusing {@link ItemPicker.Entry} means the ranking, the cap and the typed-missing rule are the
 * item picker's own, already tested, rather than a parallel copy that would drift. The two meanings of
 * {@code data} differ -- an item's custom data there, the namespace here -- and each picker is the only
 * reader of its own, so nothing has to reconcile them.
 *
 * <h2>Why the label drops the directory and the extension</h2>
 *
 * <p>{@code textures/gui/button.png} is the id a file commit needs and a poor thing to read in a list:
 * every row would lead with the same word and end with the same four letters, which is exactly the part
 * that says nothing. The row shows {@code gui/button} and keeps the id in its own field, so a search
 * matches what the author reads while a pick still writes what the file needs.
 */
public final class TexturePicker {

    /** The only extension the list offers: a picker of textures that showed models and sounds is not one. */
    private static final String PNG = ".png";

    /** The directory {@code listResources} is asked for, and the part a row's label drops. */
    private static final String DIRECTORY = "textures/";

    private TexturePicker() {
    }

    /**
     * The pickable textures among some resource locations, sorted by id.
     *
     * <p>The sort is the id's natural order, not the manager's: the resource manager answers in whatever
     * order its packs hold, and a list that reshuffles between sessions is a list you cannot learn -- the
     * same rule {@link ItemPicker#rank} states for items.
     *
     * <p>Each entry's {@code label} is the path with the directory and the extension taken off, and its
     * {@code data} is the namespace, which the row draws faintly at its right: two packs both holding
     * {@code gui/button} are told apart by that and by nothing else in the row. The count is zero --
     * nothing here is carried.
     */
    public static List<ItemPicker.Entry> catalogue(Collection<Identifier> resources) {
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        List<ItemPicker.Entry> entries = new ArrayList<>();
        for (Identifier id : resources) {
            if (id == null) {
                continue;
            }
            String path = id.getPath();
            if (!path.endsWith(PNG)) {
                continue;
            }
            entries.add(new ItemPicker.Entry(id.toString(), labelOf(path), 0, id.getNamespace()));
        }
        entries.sort(Comparator.comparing(ItemPicker.Entry::id));
        return List.copyOf(entries);
    }

    /**
     * The rows a picker shows for a catalogue, a current value and a query.
     *
     * <p>Three sections, and each answers a question the other two do not. The current value's row
     * comes first when the pack no longer holds it -- a texture the pack lost, shown so the panel is
     * never silent about the id it carries and pickable so pressing it keeps it. Then the query's
     * matches under a heading, with a typed id the pack does not hold offered ahead of them: an author
     * who pastes an id has already said what they mean, and the row is where the press confirms it.
     *
     * <p>A blank query offers no matches and no heading: {@link ItemPicker#rank} answers nothing for an
     * empty box, and the empty list's sentence is the drawing's rather than a row's -- a row pretending
     * to be an empty state is a row the keyboard can land on. The current value's row is outside that,
     * because it describes the field rather than the search.
     */
    public static List<ItemPickerLayout.Row> rows(List<ItemPicker.Entry> catalogue, String current,
                                                  String query) {
        List<ItemPicker.Entry> entries = catalogue == null ? List.of() : catalogue;
        List<ItemPickerLayout.Row> rows = new ArrayList<>();

        String value = current == null ? "" : current.trim();
        if (!value.isEmpty() && !known(entries, value)) {
            rows.add(ItemPickerLayout.Row.of(ItemPickerLayout.Kind.MISSING, value, value,
                    Labels.of("tenet.dev.texture.missing")));
        }

        List<ItemPicker.Entry> matches = ItemPicker.rank(entries, query, ItemPicker.LIMIT);
        String typed = ItemPicker.missingCandidate(query, matches);
        if (!matches.isEmpty() || typed != null) {
            rows.add(ItemPickerLayout.Row.of(ItemPickerLayout.Kind.HEADING, "",
                    Labels.of("tenet.dev.texture.heading"), ""));
            if (typed != null) {
                // The same row shape the current value's missing row uses: the id is the label, so it
                // is visible, and the note says which kind of missing this is. A store row would have
                // nothing to draw for it, which is why it is not one.
                rows.add(ItemPickerLayout.Row.of(ItemPickerLayout.Kind.MISSING, typed, typed,
                        Labels.of("tenet.dev.texture.missing")));
            }
            for (ItemPicker.Entry entry : matches) {
                // A texture is not a stack: `of` gives it the count of one, which is the floor.
                rows.add(ItemPickerLayout.Row.of(ItemPickerLayout.Kind.TEXTURE, entry.id(),
                        entry.label(), entry.data()));
            }
        }
        return List.copyOf(rows);
    }

    /** Whether the catalogue holds an entry with this id, case-blind like every other id comparison. */
    private static boolean known(List<ItemPicker.Entry> catalogue, String id) {
        for (ItemPicker.Entry entry : catalogue) {
            if (entry.id().equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    /** A path as a row reads it: no {@code textures/} in front and no {@code .png} behind. */
    private static String labelOf(String path) {
        String label = path.startsWith(DIRECTORY) ? path.substring(DIRECTORY.length()) : path;
        return label.endsWith(PNG) ? label.substring(0, label.length() - PNG.length()) : label;
    }
}
