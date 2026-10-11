package dev.ellipog.tenet.quest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The editor form a type gets when it has not declared one.
 *
 * <h2>Why there is a fallback at all</h2>
 *
 * <p>Because a registered type must never be a wall of text boxes. An addon that ships a codec and no
 * editor would otherwise get the same generic box per field, and the fields that want an item, a
 * dimension or a switch would be typed by hand -- which is the state this area was dug out of. The
 * fallback reads the names the type already declares and picks a control for each, so an addon's type is
 * usable the day it registers; declaring {@code editor()} is how it becomes <i>good</i>.
 *
 * <p>It is a guess made from the type's own words, and only that: {@code consumeItems} reads as a switch,
 * {@code dimension} as a search. Every built-in type declares its form outright, and the test beside this
 * class asserts that field for field -- so the guesses below are a floor for addons rather than the thing
 * this mod's own UI rests on.
 */
public final class EditorSpecs {

    private EditorSpecs() {
    }

    /**
     * One field per name, in a stable order, with a control chosen from the name.
     *
     * <p>Sorted, because the input is a {@link Set} and a set's order is not one -- and a form whose rows
     * shuffle between two openings is a form nobody can aim at.
     */
    public static List<EditorField> derive(Set<String> names) {
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String::compareTo);
        List<EditorField> out = new ArrayList<>();
        for (String name : sorted) {
            out.add(guess(name));
        }
        return List.copyOf(out);
    }

    /** The control a name suggests, and the label it is shown under. */
    public static EditorField guess(String path) {
        String label = label(path);

        // The names that are ids in every quest file ever written. A box you must already know the id for
        // is not an editor, it is a quiz -- and these are the ones the game can list for you.
        if (path.equals("dimension")) {
            return EditorField.search(path, label, EditorField.Source.DIMENSION);
        }
        if (path.equals("biome")) {
            return EditorField.search(path, label, EditorField.Source.BIOME);
        }
        if (path.equals("structure")) {
            return EditorField.search(path, label, EditorField.Source.STRUCTURE);
        }
        if (path.equals("advancement")) {
            return EditorField.search(path, label, EditorField.Source.ADVANCEMENT);
        }
        if (path.equals("stat")) {
            return EditorField.search(path, label, EditorField.Source.STAT);
        }
        if (path.equals("fluid")) {
            return EditorField.search(path, label, EditorField.Source.FLUID);
        }
        if (path.equals("entity") || path.equals("entityType")) {
            return EditorField.search(path, label, EditorField.Source.ENTITY);
        }
        if (path.equals("block")) {
            return EditorField.search(path, label, EditorField.Source.BLOCK);
        }
        if (path.equals("blockTag")) {
            return EditorField.tag(path, label, EditorField.Source.BLOCK_TAG);
        }
        if (path.equals("toObserve")) {
            return EditorField.search(path, label, EditorField.Source.OBSERVATION_TARGET);
        }
        if (path.equals("enchantment")) {
            return EditorField.search(path, label, EditorField.Source.ENCHANTMENT);
        }
        if (path.equals("effect")) {
            return EditorField.search(path, label, EditorField.Source.EFFECT);
        }
        if (path.equals("attribute")) {
            return EditorField.search(path, label, EditorField.Source.ATTRIBUTE);
        }
        if (path.equals("item") || path.equals("icon")) {
            return EditorField.item(path, label);
        }
        if (path.equals("table")) {
            // An addon's reward that names a table: the browser and the editor, not a box an author
            // has to know a file name for.
            return EditorField.table(path, label);
        }
        if (path.equals("position")) {
            return EditorField.position(path, label);
        }
        if (path.equals("size")) {
            return EditorField.size(path, label);
        }
        if (path.endsWith("Tag")) {
            return EditorField.tag(path, label);
        }
        if (looksBoolean(path)) {
            return EditorField.flag(path, label);
        }
        if (looksNumeric(path)) {
            return EditorField.number(path, label, unitFor(path));
        }
        return EditorField.text(path, label, "");
    }

    /**
     * Whether a name reads as a switch.
     *
     * <p>The prefixes are the ones a boolean is spelled with -- {@code ignoreDimension}, {@code silent},
     * {@code onlyFromCrafting} -- and the suffixes catch the flags written as nouns: {@code levels},
     * {@code points}, {@code consumeItems}. Case matters: {@code levels} is the XP task's switch while
     * {@code permissionLevel} is a number, and that difference is real rather than an oversight.
     */
    private static boolean looksBoolean(String path) {
        // `optional` on its own: the one boolean flag spelled as a single lowercase word, so no
        // prefix or suffix below catches it. Task and quest forms both carry it.
        if (path.equals("optional")) {
            return true;
        }        for (String prefix : List.of("is", "has", "ignore", "only", "silent", "consume", "show", "repeatable",
                "invisible", "sequential", "enable", "disable", "allow", "use")) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        for (String suffix : List.of("Items", "Enabled", "Only", "Silent", "Levels", "Points", "Team",
                "Optional", "Auto", "Allowed", "Progress")) {
            if (path.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a name reads as a number. */
    private static boolean looksNumeric(String path) {
        if (path.equals("x") || path.equals("y") || path.equals("z")) {
            return true;
        }
        for (String suffix : List.of("Count", "count", "Amount", "amount", "Value", "value", "Ticks", "ticks",
                "Size", "Level", "Weight", "Radius", "Distance", "Range", "Chance", "Score", "Time")) {
            if (path.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** What a number counts, for the names common enough to know. Empty means "just a number". */
    private static String unitFor(String path) {
        return switch (path) {
            case "count" -> "\u00d7";
            case "timer", "cooldownTicks", "repeatCooldownTicks" -> "ticks";
            case "permissionLevel" -> "level";
            case "randomBonus" -> "extra";
            case "size", "distance", "radius", "range" -> "blocks";
            case "chance" -> "%";
            default -> "";
        };
    }

    /**
     * A name a person would use, from the codec's own spelling: {@code onlyFromCrafting} becomes
     * "Only from crafting".
     *
     * <p>Sentence case rather than title case on the later words, because these are labels beside controls
     * rather than headings: "Only from crafting" reads as a phrase, "Only From Crafting" reads as a form
     * from a spreadsheet.
     */
    public static String label(String path) {
        // A name with no lower-case letter in it anywhere is an enum constant's name -- `STRUCTURE`,
        // `ITEM_TAG`, `OBSERVATION_TARGET` -- rather than a codec field's, and its word breaks are not in
        // the string: there is no lower-case letter to mark where one word ends. Lower-casing it first is
        // what turns a picker's heading from "STRUCTURE" into "Structure". A codec's own spelling always
        // carries a lower-case letter or is already lower case, so it is read exactly as it was.
        String name = allUpperCase(path) ? path.toLowerCase(Locale.ROOT) : path;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_') {
                out.append(' ');
            }
            else if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(name.charAt(i - 1))) {
                out.append(' ').append(Character.toLowerCase(c));
            }
            else {
                out.append(c);
            }
        }
        String text = out.toString().trim();
        return text.isEmpty() ? path
                : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Whether a name carries no lower-case letter at all. See {@link #label}. */
    private static boolean allUpperCase(String path) {
        for (int i = 0; i < path.length(); i++) {
            if (Character.isLowerCase(path.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
