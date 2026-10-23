package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.ellipog.tenet.quest.QuestLayout;
import dev.ellipog.tenet.quest.QuestLink;
import dev.ellipog.tenet.quest.QuestShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One quest link's own rows: the form, shown in the Chapter tab.
 *
 * <h2>Why this is its own class rather than pages of the Chapter tab</h2>
 *
 * <p>Because a link is a thing with fields of its own — a target, a position, a shape and a size —
 * and the tab already has one form per kind it edits. The rows are keyed {@code link.<id>.<field>},
 * which is the convention the whole tab follows: {@code ChapterPanelLayout.linkFieldOf} splits it,
 * and the screen turns it into an {@code EditorOp.SetLink}. A link has no canvas panel, so unlike
 * the element form this one has a single caller — but the rows still live here rather than in the
 * tab, because a form is a thing with a help table, a value reader and a range table, and the tab
 * is a list of sections.
 *
 * <h2>A row is the control its kind asks for</h2>
 *
 * <p>The target is a text row, because a quest is picked by name here and the validator is what
 * says a name does not resolve — the same shape the chapter's own gate lists already use. The
 * coordinates and the size are scrubbable numbers, the shape is a chooser read from the model's
 * own enum, and the press rows are buttons: pick from the canvas, open the target, delete the
 * link. A link has no flags, so there are no switches.
 */
public final class LinkPanelLayout {

    /**
     * The field name of the link's "pick from the canvas" row.
     *
     * <p>Not a field of the file at all — it is a button — but it is spelled like one, which is what
     * lets the press routing be the same one rule: a key that starts with the link prefix and names
     * the link, and a field after it that the screen recognises. See {@code ElementPanelLayout}'s
     * own pick constant for the same arrangement on a picture's file.
     */
    public static final String PICK_TARGET = "pick";

    /** The field name of the link's "open the target" row. A button, like the pick. */
    public static final String JUMP_TARGET = "jump";

    /** The field name of the link's delete row. A button, armed twice like every delete here. */
    public static final String DELETE_LINK = "delete";

    /** The field name of the tab's "add a link" row. A button, carried by no link at all. */
    public static final String ADD_LINK = "add";

    private LinkPanelLayout() {
    }

    /** The rows for one link, from the link's own tree. */
    public static List<ToolsLayout.Action> rows(JsonObject link) {
        return rows(link, Set.of());
    }

    /**
     * The same, with folded sections put away.
     *
     * <p>The screen owns the set the way the Chapter tab keeps its own folds: this class only reads
     * it. A key in it is a heading whose block is not built.
     */
    public static List<ToolsLayout.Action> rows(JsonObject link, Set<String> folded) {
        List<ToolsLayout.Action> rows = new ArrayList<>();
        if (link == null) {
            return List.copyOf(rows);
        }
        String id = read(link, "id", "");
        String heading = ChapterPanelLayout.LINK_PREFIX + id;
        rows.add(ToolsLayout.Action.heading(heading, "Link \u2014 " + nameOf(link, id)));
        if (isFolded(folded, heading)) {
            return List.copyOf(rows);
        }

        section(rows, id, "target", "tenet.dev.link.section.target", folded, inner -> {
            text(inner, link, id, "quest", "tenet.dev.link.target");
            if (!Advanced.hidesLink("pick")) {
                inner.add(ToolsLayout.Action.action(key(id, PICK_TARGET), "tenet.dev.link.pick"));
            }
            if (!Advanced.hidesLink("jump")) {
                inner.add(ToolsLayout.Action.action(key(id, JUMP_TARGET), "tenet.dev.link.jump"));
            }
        });
        section(rows, id, "place", "tenet.dev.link.section.place", folded, inner -> {
            pairNumber(inner, link, id, "x", "tenet.dev.link.x", "y", "tenet.dev.link.y");
            choice(inner, link, id, "shape", "tenet.dev.link.shape");
            number(inner, link, id, "size", "tenet.dev.link.size");
        });
        section(rows, id, "danger", "tenet.dev.link.section.danger", folded, inner -> {
            if (!Advanced.hidesLink("delete")) {
                inner.add(ToolsLayout.Action.action(key(id, DELETE_LINK), "tenet.dev.link.delete"));
            }
        });
        return List.copyOf(rows);
    }

    /**
     * One group of rows under its own folding heading.
     *
     * <p>Built into a temporary list first, because a section whose every row is hidden at this depth
     * is omitted with its heading: a name over nothing is a control that promises fields it has not
     * got. That is the same rule the element form keeps, because these headings are built here
     * rather than at a builder with a gate around the block.
     */
    private static void section(List<ToolsLayout.Action> rows, String id, String name, String label,
                                Set<String> folded,
                                java.util.function.Consumer<List<ToolsLayout.Action>> fill) {
        List<ToolsLayout.Action> inner = new ArrayList<>();
        fill.accept(inner);
        if (inner.isEmpty()) {
            return;
        }
        String key = ChapterPanelLayout.LINK_PREFIX + id + ".#" + name;
        boolean shut = isFolded(folded, key);
        rows.add(ToolsLayout.Action.heading(key, (shut ? "\u203a " : "\u25bc ") + label));
        if (!shut) {
            rows.addAll(inner);
        }
    }

    /** Whether a heading's block is put away. A set nobody passed is nothing put away. */
    private static boolean isFolded(Set<String> folded, String key) {
        return folded != null && key != null && folded.contains(key);
    }

    /** What one of those values is called on screen: the file's own word, in the case a menu reads. */
    public static String valueName(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /**
     * The values one closed set accepts, in the order an author meets them, <b>in the spelling the
     * file uses</b>.
     *
     * <p>Read from the model's own enum rather than listed here, so a shape the model adds is offered
     * by the control without anybody remembering to add it twice — and the validator, which reads the
     * same enum, cannot then disagree with the menu. Lower-cased, because that is how the format
     * spells them.
     */
    public static List<String> valuesOf(String field) {
        if (!"shape".equals(field)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (QuestShape shape : QuestShape.values()) {
            out.add(shape.name().toLowerCase(java.util.Locale.ROOT));
        }
        return List.copyOf(out);
    }

    /**
     * How far one numeric field may go, and what its drag steps by.
     *
     * <p>The bounds are the model's own constants, so the control and the codec cannot drift: a
     * control that could write more than the codec keeps would write a number the author never sees
     * again. Positions are unclamped by the codec — a hand-edited file can put a marker anywhere —
     * so their range is the canvas the editor draws rather than a refusal.
     */
    public record Range(double min, double max, double step, String unit) {
    }

    /** The range for one numeric field, or null when the field is not one this form offers as a number. */
    public static Range rangeOf(String field) {
        return switch (field == null ? "" : field) {
            case "x", "y" -> new Range(-4096, 4096, 1, " px");
            case "size" -> new Range(QuestLayout.MIN_SIZE, QuestLayout.MAX_SIZE, 1, " px");
            default -> null;
        };
    }

    /** One link field as a text row. What stays text is what really is text: the target's name. */
    private static void text(List<ToolsLayout.Action> rows, JsonObject link, String id, String field,
                             String label) {
        if (Advanced.hidesLink(field)) {
            return;
        }
        rows.add(ToolsLayout.Action.text(key(id, field), label, valueOf(link, field)));
    }

    /** One link field as a scrubbable number. */
    private static void number(List<ToolsLayout.Action> rows, JsonObject link, String id, String field,
                               String label) {
        if (Advanced.hidesLink(field)) {
            return;
        }
        rows.add(ToolsLayout.Action.field(key(id, field), label));
    }

    /**
     * Two coordinates that belong together on one pair row.
     *
     * <p>One widget for the row rather than two stacked ones: an x without its y is half a position.
     * When one half is hidden at this depth the other stands alone as an ordinary number row, so the
     * shallow form stays a subset of the deep one rather than losing a field to the pairing.
     */
    private static void pairNumber(List<ToolsLayout.Action> rows, JsonObject link, String id,
                                   String leftField, String leftLabel,
                                   String rightField, String rightLabel) {
        boolean leftHidden = Advanced.hidesLink(leftField);
        boolean rightHidden = Advanced.hidesLink(rightField);
        if (leftHidden && rightHidden) {
            return;
        }
        if (leftHidden) {
            rows.add(ToolsLayout.Action.field(key(id, rightField), rightLabel));
            return;
        }
        if (rightHidden) {
            rows.add(ToolsLayout.Action.field(key(id, leftField), leftLabel));
            return;
        }
        rows.add(ToolsLayout.Action.pair(
                ToolsLayout.Action.field(key(id, leftField), leftLabel),
                ToolsLayout.Action.field(key(id, rightField), rightLabel)));
    }

    /** One link field as a value chosen from its own closed set. */
    private static void choice(List<ToolsLayout.Action> rows, JsonObject link, String id, String field,
                               String label) {
        if (Advanced.hidesLink(field)) {
            return;
        }
        rows.add(ToolsLayout.Action.choice(key(id, field), label));
    }

    /**
     * What a row's field holds, in the file's own spelling: the file's value, or the one the link's
     * codec supplies when the file says nothing.
     *
     * <p>Decoded rather than tabulated: {@link QuestLink#fromJson} applies the same codec the canvas,
     * the loader and the validator read, so a default that moves in the model moves here with it.
     * A row is built from this rather than from the file, because the canvas draws this.
     */
    public static String effectiveValueOf(JsonObject link, String field) {
        String held = valueOf(link, field);
        if (!held.isEmpty()) {
            return held;
        }
        return QuestLink.fromJson(link).map(model -> switch (field == null ? "" : field) {
            case "quest" -> model.quest().id();
            case "x" -> Integer.toString(model.x());
            case "y" -> Integer.toString(model.y());
            case "shape" -> model.shape().name().toLowerCase(java.util.Locale.ROOT);
            case "size" -> Integer.toString(model.size());
            default -> "";
        }).orElse("");
    }

    /** The same, as the number a scrubbable row starts its drag from. Unreadable text is zero. */
    public static double numberOf(JsonObject link, String field) {
        try {
            return Double.parseDouble(effectiveValueOf(link, field));
        }
        catch (NumberFormatException notANumber) {
            // A field this build's codec has no value for, or a link it refuses: the box shows zero,
            // which is where it was before this method existed and is the honest answer for a value
            // nobody can read.
            return 0;
        }
    }

    /** A row's key: the link, then the field. */
    private static String key(String id, String field) {
        return ChapterPanelLayout.LINK_PREFIX + id + "." + field;
    }

    /** What a link calls itself: its target's name when it has one, and its id when it has none. */
    private static String nameOf(JsonObject link, String id) {
        String quest = valueOf(link, "quest");
        return quest.isEmpty() ? id : quest;
    }

    /** A plain string of the link's own tree, or the fallback. The same read as {@link #valueOf}. */
    private static String read(JsonObject link, String field, String fallback) {
        String value = valueOf(link, field);
        return value.isEmpty() ? fallback : value;
    }

    /**
     * What the <b>file</b> says for one link field, through the panel's own dotted read.
     *
     * <p>Empty when the file does not carry the field, and that is the whole difference between this
     * and {@link #effectiveValueOf}: this is what the JSON holds, that one is what the link means.
     * Empty for anything that is not a primitive: an object has no single-line value.
     */
    public static String valueOf(JsonObject link, String field) {
        JsonElement value = QuestPanelLayout.get(link, field);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return "";
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isBoolean()
                ? (primitive.getAsBoolean() ? ToolsLayout.ON : ToolsLayout.OFF)
                : primitive.getAsString();
    }

    /**
     * The help a row's field offers on hover, as a language key, or null for a row that says it itself.
     *
     * <p>Keyed by field name alone rather than by the row's whole key, because the key carries the
     * link's id and the id has nothing to do with what the field means.
     */
    public static String help(String field) {
        return field == null ? null : HELP.get(field);
    }

    /**
     * What each field means, in one sentence a tooltip can carry.
     *
     * <p>A table rather than a field on the row, the shape the element form already uses: a row is
     * key, label and kind, and help is a property of the key.
     */
    private static final Map<String, String> HELP = Map.ofEntries(
            Map.entry("quest", "tenet.dev.link.help.target"),
            Map.entry("pick", "tenet.dev.link.help.pick"),
            Map.entry("jump", "tenet.dev.link.help.jump"),
            Map.entry("x", "tenet.dev.link.help.place"),
            Map.entry("y", "tenet.dev.link.help.place"),
            Map.entry("shape", "tenet.dev.link.help.shape"),
            Map.entry("size", "tenet.dev.link.help.size"),
            Map.entry("delete", "tenet.dev.link.help.delete"),
            Map.entry("#target", "tenet.dev.link.help.target"),
            Map.entry("#place", "tenet.dev.link.help.place"),
            Map.entry("#danger", "tenet.dev.link.help.delete"));
}
