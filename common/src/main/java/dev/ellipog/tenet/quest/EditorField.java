package dev.ellipog.tenet.quest;

import java.util.List;

/**
 * One field of a task or a reward, as the in-game editor draws it.
 *
 * <h2>Why this is data rather than a screen</h2>
 *
 * <p>The editor used to describe an entry as a flat list of boxes: a name, a fixed width, four kinds. Every
 * type got the same treatment, so a dimension id, a statistic and an item id were all text fields of
 * roughly the same shape, and the fields that most needed to explain themselves -- an id you must know by
 * heart -- explained nothing. A number was a bare box with no unit, and a flag was a box that read
 * "Consume off".
 *
 * <p>A field now says what it <i>is</i> and how it should be edited, the type declares its own fields, and
 * the screen draws whatever it is handed. That is what makes a new task type a new <b>form</b> rather than
 * a new branch in the screen -- for this mod's types and for an addon's alike, which is the whole point:
 * the registration <i>is</i> the tailoring.
 *
 * <h2>Server-safe by construction</h2>
 *
 * <p>{@link Kind#SEARCH} names a list the screen should offer, not a class it should instantiate, and
 * {@link Source} is a short list of things a datapack can have. Nothing here mentions a client type, so a
 * task or reward type -- which lives on both sides -- can carry its editor without dragging the screen
 * into the server.
 *
 * <p>{@code path} is the field's path in the entry, which is what an edit commits to: {@code "count"}, or
 * {@code "position"} for a triple whose elements are {@code position.0} and so on.
 *
 * @param path    the field's path in the entry, as the tree spells it
 * @param label   the name the form shows beside the control
 * @param kind    how it is edited
 * @param unit    what a number counts -- {@code "×"}, {@code "blocks"}, {@code "mB"} -- or empty
 * @param hint    a sentence for a field whose meaning its label does not carry, or empty
 * @param options the allowed values of a {@link Kind#CHOICE}, in the order they cycle
 * @param source  which list a {@link Kind#SEARCH} searches
 */
public record EditorField(String path, String label, Kind kind, String unit, String hint,
                          List<String> options, Source source) {

    /** How a field is edited. Each one is a control the form knows how to draw and press. */
    public enum Kind {
        /** An item, chosen in the item picker: its icon, its name, and the id as small print. */
        ITEM,
        /**
         * An icon object ({@code item}, {@code texture} or {@code entity}), chosen in the item
         * picker for the item arm.
         *
         * <p>Unlike {@link #ITEM}, edits only its own field: the picker's commit writes the whole
         * object in one op, so there is no sibling {@code components} field beside it. The
         * components travel <i>inside</i> the object the pick writes, which is why
         * {@link #fieldNames} names only this path for this kind.
         */
        ICON,
        /** A tag, chosen from the tag list the datapack declares. */
        TAG,
        /** A whole number, with a stepper and its unit beside it. */
        NUMBER,
        /** A switch: a chip that lights up, rather than a box that reads "off". */
        FLAG,
        /** Free text, for the few fields that are genuinely the author's words. */
        TEXT,
        /** An id, chosen from a searchable list of what the game actually has. */
        SEARCH,
        /** One of a short list of values, cycled by pressing it. */
        CHOICE,
        /** A coordinate: three numbers, with a press that fills them from where the player stands. */
        POSITION,
        /** A side length: three numbers, each a count of blocks. */
        SIZE,
        /**
         * A reward table: a card that names one, opens a browser to pick it, and opens an editor to
         * change it — the one control that replaces the two JSON boxes a table reward used to carry.
         *
         * <p>Its own kind rather than a search box, because a table is not an id you type: it is an
         * asset with a name, an icon and a roll, and the whole point of the editor is that an author
         * never has to know its file name.
         */
        TABLE
    }

    /** A list a {@link Kind#SEARCH} can search. Every one is resolvable on the client alone. */
    public enum Source {
        DIMENSION,
        BIOME,
        STRUCTURE,
        ADVANCEMENT,
        STAT,
        FLUID,
        ENTITY,
        /** An item id or an entity id, whichever the neighbouring {@code observeType} asks for. */
        OBSERVATION_TARGET,
        ITEM_TAG,
        ENTITY_TAG,
        BLOCK,
        BLOCK_TAG,
        ENCHANTMENT,
        EFFECT,
        ATTRIBUTE
    }

    public EditorField {
        options = options == null ? List.of() : List.copyOf(options);
        unit = unit == null ? "" : unit;
        hint = hint == null ? "" : hint;
    }

    /** An item, with the picker. */
    public static EditorField item(String path, String label) {
        return new EditorField(path, label, Kind.ITEM, "", "", null, null);
    }

    /** An icon object, with the picker for its item arm. */
    public static EditorField icon(String path, String label) {
        return new EditorField(path, label, Kind.ICON, "", "", null, null);
    }

    /** An item tag, chosen from the tags the datapack declares. */
    public static EditorField tag(String path, String label) {
        return tag(path, label, Source.ITEM_TAG);
    }

    /**
     * A tag of something else -- an entity tag, say.
     *
     * <p>The source is named rather than assumed because a tag field's list is the thing it tags: an
     * entity tag offered a list of item tags would be a picker that cannot answer its own field.
     */
    public static EditorField tag(String path, String label, Source source) {
        return new EditorField(path, label, Kind.TAG, "", "", null, source);
    }

    /** A whole number and what it counts. */
    public static EditorField number(String path, String label, String unit) {
        return new EditorField(path, label, Kind.NUMBER, unit, "", null, null);
    }

    /** A switch. */
    public static EditorField flag(String path, String label) {
        return new EditorField(path, label, Kind.FLAG, "", "", null, null);
    }

    /** Free text, with a sentence saying what belongs in it. */
    public static EditorField text(String path, String label, String hint) {
        return new EditorField(path, label, Kind.TEXT, "", hint, null, null);
    }

    /**
     * The same field, with the sentence its label's hover shows.
     *
     * <p>A wither rather than another parameter on every factory: a spec reads
     * {@code EditorField.flag("consumeItems", "Consume").hint("…")}, and the fields that need no
     * explanation -- none of the built-in ones do; see the editor-form test -- can leave it off without a
     * placeholder.
     */
    public EditorField hint(String text) {
        return new EditorField(path, label, kind, unit, text, options, source);
    }

    /** An id, searched rather than typed. */
    public static EditorField search(String path, String label, Source source) {
        return new EditorField(path, label, Kind.SEARCH, "", "", null, source);
    }

    /** One of a short list, cycled by pressing. */
    public static EditorField choice(String path, String label, String... options) {
        return new EditorField(path, label, Kind.CHOICE, "", "", List.of(options), null);
    }

    /** A coordinate, with the press that fills it from the player's own feet. */
    public static EditorField position(String path, String label) {
        return new EditorField(path, label, Kind.POSITION, "blocks", "", null, null);
    }

    /** A box's side lengths, in blocks. */
    public static EditorField size(String path, String label) {
        return new EditorField(path, label, Kind.SIZE, "blocks", "", null, null);
    }

    /**
     * A reward table, chosen in the browser and edited in place.
     *
     * <p>The path is the reward's own {@code table} field; the control also owns the {@code inline}
     * beside it, the way an item control owns the {@code components} beside its item — the two are one
     * choice ("this reward rolls <i>this</i> table"), and drawing them as two boxes is what made an
     * author pick one and then wonder why the other one won.
     */
    public static EditorField table(String path, String label) {
        return new EditorField(path, label, Kind.TABLE, "", "", null, null);
    }

    /** Whether this field is drawn as three numbers rather than one control. */
    public boolean isTriple() {
        return kind == Kind.POSITION || kind == Kind.SIZE;
    }

    /** The element path of a triple's axis: {@code position.0}, {@code size.2}. */
    public String axis(int index) {
        return path + "." + index;
    }

    /**
     * The sibling path an item's data components live at: {@code components} beside {@code item}, and
     * {@code icon.components} beside {@code icon.item}.
     *
     * <p>Not a guess: it is {@link ItemRef#MAP_CODEC}'s own shape, which writes an item, its count and its
     * components flat wherever it is embedded. The item control is where all of it is chosen -- the picker
     * copies the component patch of the stack the author picked, which is what makes "the sword I am
     * holding" possible -- so a form covers the sibling even though it draws no box for it.
     */
    public String componentsPath() {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "components" : path.substring(0, dot + 1) + "components";
    }

    /**
     * The codec field names this one control edits.
     *
     * <p>Usually the one, and deliberately not always. An item control also edits the {@code components}
     * beside it -- the picker copies the component patch of the stack the author picked, which is what
     * makes "the sword I am holding" possible. A triple is the other way round: three boxes over one
     * field, an array, so it names the array and its boxes are the elements; see {@link #axis}.
     *
     * <p>The editor-form test compares these against the codec's own field names, so a control that edits
     * something the codec does not take -- or a declared field nothing edits -- fails the build rather
     * than shipping as an edit that gets refused.
     */
    public List<String> fieldNames() {
        if (isTriple()) {
            return List.of(path);
        }
        if (kind == Kind.ITEM) {
            return List.of(path, componentsPath());
        }
        // A table control edits the reference and the inline table beside it: one choice, two fields,
        // exactly as an item control covers the components beside its item.
        return kind == Kind.TABLE ? List.of(path, "inline") : List.of(path);
    }
}
