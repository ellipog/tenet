package dev.ellipog.tenet.client.dev;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The texture picker's rules: which files a catalogue holds, what a row reads, and which rows exist
 * for a value the pack does not.
 *
 * <h2>What is asked here, and what cannot be</h2>
 *
 * <p>The screen owns the resource manager -- which files the client actually has is the game's answer
 * -- and {@code TexturePicker} owns everything about those files a person could disagree with: which
 * one is a picture, the label a row shows, the order, and the two kinds of missing row. All of it is
 * answerable with resource locations and lists of rows, which is the whole reason the rules live in a
 * class with no screen in it.
 */
@DisplayName("the texture picker's rules")
class TexturePickerTest {

    private static final Identifier BUTTON =
            Identifier.parse("minecraft:textures/gui/button.png");
    private static final Identifier WIDGETS =
            Identifier.parse("minecraft:textures/gui/widgets.png");
    private static final Identifier PACK_BUTTON =
            Identifier.parse("somepack:textures/gui/button.png");
    private static final Identifier MODEL =
            Identifier.parse("minecraft:models/block/stone.json");

    private static List<ItemPicker.Entry> catalogue() {
        return TexturePicker.catalogue(List.of(WIDGETS, MODEL, PACK_BUTTON, BUTTON));
    }

    @Test
    @DisplayName("the catalogue keeps the PNGs, drops everything else, and sorts by id")
    void onlyPicturesAreOffered() {
        List<ItemPicker.Entry> catalogue = catalogue();

        assertEquals(List.of("minecraft:textures/gui/button.png",
                        "minecraft:textures/gui/widgets.png",
                        "somepack:textures/gui/button.png"),
                catalogue.stream().map(ItemPicker.Entry::id).toList(),
                "only the PNGs, in id order -- not the order the manager happened to answer in");

        ItemPicker.Entry button = catalogue.get(0);
        assertEquals("gui/button", button.label(),
                "the row shows the path without the directory or the extension, because every row "
                        + "would otherwise lead and end with the parts that say nothing");
        assertEquals("minecraft", button.data(),
                "the namespace travels as the row's small print: `somepack` and `minecraft` both hold "
                        + "`gui/button`, and this is what tells the two rows apart");
        assertEquals(0, button.count(), "nothing here is carried");
    }

    @Test
    @DisplayName("a blank catalogue is no rows at all")
    void anEmptyCatalogueIsEmpty() {
        assertTrue(TexturePicker.catalogue(List.of()).isEmpty());
        assertTrue(TexturePicker.catalogue(null).isEmpty());
        assertTrue(TexturePicker.rows(List.of(), "", "").isEmpty(),
                "no current value, no query -- the empty state's sentence is the drawing's");
    }

    @Test
    @DisplayName("the current value the pack no longer holds gets a kept, marked row")
    void theMissingCurrentValueIsShown() {
        List<ItemPickerLayout.Row> rows = TexturePicker.rows(catalogue(),
                "gone:textures/gui/lost.png", "");

        assertEquals(1, rows.size(), "the current row, and nothing else for a blank query");
        ItemPickerLayout.Row row = rows.get(0);
        assertEquals(ItemPickerLayout.Kind.MISSING, row.kind());
        assertEquals("gone:textures/gui/lost.png", row.id(), "the id travels, so the press can keep it");
        assertEquals("gone:textures/gui/lost.png", row.label(), "and it is the label, so it is visible");
        assertEquals("Not in this pack", row.secondary(), "with the note that says which missing this is");
    }

    @Test
    @DisplayName("a current value the pack holds gets no row of its own")
    void aKnownCurrentValueIsNotRepeated() {
        assertTrue(TexturePicker.rows(catalogue(), "minecraft:textures/gui/button.png", "").isEmpty(),
                "the field's own value is not a section of the list when the pack has it");
    }

    @Test
    @DisplayName("a query's matches sit under a heading, best first, with the namespace beside each")
    void matchesAreHeadedAndNamespaced() {
        List<ItemPickerLayout.Row> rows = TexturePicker.rows(catalogue(), "", "button");

        assertEquals(3, rows.size(), "a heading and the two files whose path contains the query");
        assertEquals(ItemPickerLayout.Kind.HEADING, rows.get(0).kind());
        assertEquals("Textures", rows.get(0).label());

        assertEquals(ItemPickerLayout.Kind.TEXTURE, rows.get(1).kind());
        assertEquals("minecraft:textures/gui/button.png", rows.get(1).id());
        assertEquals("gui/button", rows.get(1).label());
        assertEquals("minecraft", rows.get(1).secondary(), "the namespace is the row's small print");
        assertTrue(ItemPickerLayout.pickable(rows.get(1)),
                "a texture row is pressable like every row that is not a heading");

        assertEquals(ItemPickerLayout.Kind.TEXTURE, rows.get(2).kind());
        assertEquals("somepack:textures/gui/button.png", rows.get(2).id(),
                "inside a rank the id decides, so the catalogue's order is the same for every pack");
    }

    @Test
    @DisplayName("a typed id the pack does not hold is offered ahead of the matches")
    void aTypedMissingIdIsOffered() {
        List<ItemPickerLayout.Row> rows = TexturePicker.rows(catalogue(), "",
                "somepack:textures/gui/missing.png");

        assertEquals(2, rows.size());
        assertEquals(ItemPickerLayout.Kind.HEADING, rows.get(0).kind());
        assertEquals(ItemPickerLayout.Kind.MISSING, rows.get(1).kind());
        assertEquals("somepack:textures/gui/missing.png", rows.get(1).id());
        assertEquals("Not in this pack", rows.get(1).secondary(),
                "the press confirms the id; the commit is what checks the file really exists");

        // A bare word is a search, not an id, and a sentence is not an id either: neither gets a row.
        assertTrue(TexturePicker.rows(catalogue(), "", "gui").stream()
                        .noneMatch(row -> row.kind() == ItemPickerLayout.Kind.MISSING),
                "only the file rows and their heading, because 'gui' names nothing to keep");
        assertTrue(TexturePicker.rows(catalogue(), "", "not an id").isEmpty(),
                "a query that matches nothing and parses as nothing offers nothing");
    }
}
