package dev.ellipog.tenet.quest;

import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;

import java.util.Locale;

/**
 * A tag id as a player reads it: "Any Iron Ores" for {@code c:ores/iron}, "Any Planks" for
 * {@code minecraft:planks}.
 *
 * <h2>Where this is used, and why the raw id survives beside it</h2>
 *
 * <p>The item_tag task's display takes this as its subject, so the row reads "Hand in Any Iron Ores"
 * instead of "Hand in #Ores/iron": a tag id is the author's spelling, and parsing it is not a
 * player's job. The raw tag is not lost — it travels as the row's {@code tagId}, the book's hover
 * prints it, and the viewer adapters build their tag ingredients from it. The humanized label is for
 * reading; the id is for naming exactly what to hand in.
 *
 * <h2>The shape this follows, and what it refuses to guess</h2>
 *
 * <p>Convention tags name a category and a member — {@code c:ores/iron} is iron among ores — and the
 * label says the member first because English does ("Any Iron Ores"). A flat tag is just a name. The
 * namespace is dropped: it groups tags for authors, and a player reading "Any Planks" has no use for
 * "minecraft".
 *
 * <p>Nothing here pluralizes or translates: the words are the id's own, title-cased, and an author
 * who wants better words gives the quest a title. And an id that does not fit the shape at all — an
 * empty path, a stray slash — comes back as the raw id rather than as an exception: a display helper
 * is not a validator, and a row must not fail to draw over a malformed tag the server accepted.
 */
public final class TagLabels {

    private TagLabels() {
    }

    /** The label for a tag, or "" for no tag. */
    public static String humanize(TagKey<?> tag) {
        return tag == null ? "" : humanize(tag.location());
    }

    /**
     * The label for a tag's id: "Any &lt;Name&gt; &lt;Category&gt;" when the path has a category,
     * "Any &lt;Name&gt;" when it does not.
     */
    public static String humanize(Identifier id) {
        if (id == null) {
            return "";
        }
        String path = id.getPath();
        if (path.isEmpty()) {
            // An id with no path has no words to humanize; the raw id is the honest fallback.
            return "#" + id;
        }

        // The last slash splits member from category, so a deeper path keeps every word in the
        // category rather than dropping the middle one. A trailing slash leaves no member: read the
        // path as flat, because the word it does have is better than nothing.
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        String category = slash < 0 ? "" : path.substring(0, slash).replace('/', '_');
        if (name.isEmpty()) {
            name = category;
            category = "";
        }

        String title = titleCase(name);
        if (title.isEmpty()) {
            return "#" + id;
        }
        return category.isEmpty() ? "Any " + title : "Any " + title + " " + titleCase(category);
    }

    /** Underscores become spaces and every word is capitalised: "raw_materials" -> "Raw Materials". */
    private static String titleCase(String path) {
        StringBuilder out = new StringBuilder();
        for (String word : path.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.isEmpty() ? path : out.toString();
    }
}
