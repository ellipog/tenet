package dev.ellipog.tenet.client;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The picture picker's pure half: which paths greet with tabs, and what each tab commits.
 *
 * <p>Game-free by construction — the paths a tab may open on and the objects a tab writes are
 * decided without a screen, a registry or a server, so a refactor of the drawing cannot silently
 * change what a press writes. The screen's own wiring (which rows open, where the tabs sit) is
 * review plus the in-game pass, like every picker before it.
 */
@DisplayName("picture picker: paths and payloads")
class PicturePickerTest {

    @Test
    @DisplayName("icon paths greet with tabs; item fields and nothing do not")
    void iconPaths() {
        assertTrue(QuestBookScreen.isIconPath("icon.item"), "the quest's own icon");
        assertTrue(QuestBookScreen.isIconPath("bookIcon"), "the book's icon");
        assertTrue(QuestBookScreen.isIconPath("tasks.0.icon"), "a task override");
        assertTrue(QuestBookScreen.isIconPath("rewards.2.icon"), "a reward override");
        assertFalse(QuestBookScreen.isIconPath("item"), "an item field");
        assertFalse(QuestBookScreen.isIconPath("tasks.0.item"), "an entry's item");
        assertFalse(QuestBookScreen.isIconPath("title"), "a word field");
        assertFalse(QuestBookScreen.isIconPath(null), "no path");
    }

    @Test
    @DisplayName("each tab writes its whole arm, and nothing else")
    void tabPayloads() {
        assertNull(BookItemPicker.iconObjectForKind(QuestBookScreen.PickerKind.ITEM, null),
                "clear is null, on every tab");

        JsonObject item = BookItemPicker.iconObjectForKind(QuestBookScreen.PickerKind.ITEM, "minecraft:torch");
        assertEquals("minecraft:torch", item.get("item").getAsString());
        assertEquals(1, item.size(), "one arm, never two: " + item);

        JsonObject texture = BookItemPicker.iconObjectForKind(QuestBookScreen.PickerKind.FILE,
                "my_pack:textures/gui/emblem.png");
        assertEquals("my_pack:textures/gui/emblem.png", texture.get("texture").getAsString());
        assertEquals(1, texture.size(), "one arm, never two: " + texture);

        JsonObject sprite = BookItemPicker.iconObjectForKind(QuestBookScreen.PickerKind.SPRITE,
                "occultism:block/chalk_glyph/0");
        assertEquals("occultism:block/chalk_glyph/0", sprite.get("sprite").getAsString());
        assertEquals(1, sprite.size(), "one arm, never two: " + sprite);

        JsonObject entity = BookItemPicker.iconObjectForKind(QuestBookScreen.PickerKind.ENTITY,
                "minecraft:creeper");
        assertEquals("minecraft:creeper", entity.get("entity").getAsString());
        assertEquals(1, entity.size(), "one arm, never two: " + entity);
    }

    @Test
    @DisplayName("the status names whichever arm the object carries")
    void statusNames() {
        assertEquals("", BookItemPicker.describeIcon(null));
        assertEquals("minecraft:torch",
                BookItemPicker.describeIcon(BookItemPicker.iconObjectForKind(
                        QuestBookScreen.PickerKind.ITEM, "minecraft:torch")));
        assertEquals("my_pack:textures/gui/emblem.png",
                BookItemPicker.describeIcon(BookItemPicker.iconObjectForKind(
                        QuestBookScreen.PickerKind.FILE, "my_pack:textures/gui/emblem.png")));
        assertEquals("occultism:block/chalk_glyph/0",
                BookItemPicker.describeIcon(BookItemPicker.iconObjectForKind(
                        QuestBookScreen.PickerKind.SPRITE, "occultism:block/chalk_glyph/0")));
        assertEquals("minecraft:creeper",
                BookItemPicker.describeIcon(BookItemPicker.iconObjectForKind(
                        QuestBookScreen.PickerKind.ENTITY, "minecraft:creeper")));
    }
}
