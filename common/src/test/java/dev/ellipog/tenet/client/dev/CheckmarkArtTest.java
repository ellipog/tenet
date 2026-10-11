package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.client.CheckmarkArt;
import dev.ellipog.tenet.client.ClientQuestCache;
import dev.ellipog.tenet.client.viewer.MinecraftTestBootstrap;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The checkmark state box: empty while todo, checked once done.
 *
 * <p>the legacy {@code checkmark_task_inactive} and {@code checkmark_task_active} under Tenet's
 * own roof — two files that always resolve, rather than atlas ids that may not. Each test draws
 * through the dev recorder, which keeps whole-file blits apart from items, so a box drawn as an
 * item would fail here rather than on the canvas.
 */
@DisplayName("checkmark state box")
class CheckmarkArtTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.boot();
    }

    private static ClientQuestCache.TaskEntry checkmark(String texture, String sprite, ItemStack picture) {
        return new ClientQuestCache.TaskEntry(ItemStack.EMPTY, ItemStack.EMPTY, 1,
                false, false, false, false, "tenet:checkmark", "", "", "", "", "", "", 0, "",
                List.of(), false, texture, sprite, picture, "");
    }

    private static ClientQuestCache.TaskEntry itemTask() {
        return new ClientQuestCache.TaskEntry(ItemStack.EMPTY, ItemStack.EMPTY, 1,
                false, false, false, false, "tenet:item", "", "", "", "minecraft:stone", "", "", 0,
                "", List.of(), false, "", "", ItemStack.EMPTY, "");
    }

    @Test
    @DisplayName("a bare checkmark wears the box; anything with a picture does not")
    void wearsBox() {
        assertTrue(CheckmarkArt.wearsBox(checkmark("", "", ItemStack.EMPTY)),
                "a bare checkmark has no other picture to wear");
        assertFalse(CheckmarkArt.wearsBox(checkmark("my_pack:textures/gui/emblem.png", "", ItemStack.EMPTY)),
                "an author texture wins");
        assertFalse(CheckmarkArt.wearsBox(checkmark("", "minecraft:block/sculk", ItemStack.EMPTY)),
                "an author sprite wins");
        assertFalse(CheckmarkArt.wearsBox(itemTask()), "an item task is not a checkmark");
        assertFalse(CheckmarkArt.wearsBox(null), "no task wears nothing");
    }

    @Test
    @DisplayName("todo draws the empty box, done the checked one")
    void states() {
        assertEquals("tenet:textures/gui/checkmark_unchecked.png",
                CheckmarkArt.forState(false).toString());
        assertEquals("tenet:textures/gui/checkmark_checked.png",
                CheckmarkArt.forState(true).toString());

        RecordingRenderer todo = new RecordingRenderer();
        CheckmarkArt.draw(todo, 0, 0, 16, false);
        assertEquals(1, todo.textures().size(), "one blit: " + todo.describe());
        assertEquals("tenet:textures/gui/checkmark_unchecked.png",
                todo.textures().get(0).texture().toString());

        RecordingRenderer done = new RecordingRenderer();
        CheckmarkArt.draw(done, 0, 0, 16, true);
        assertEquals("tenet:textures/gui/checkmark_checked.png",
                done.textures().get(0).texture().toString());
    }
}
