package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.render.GuiRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * A checkmark task's two pictures: the empty box it wears while todo, and the checked box once
 * done — the legacy {@code checkmark_task_inactive} and {@code checkmark_task_active} under
 * Tenet's own roof.
 *
 * <p>Files rather than fills, for the reason the legacy own pair are files: a check is artwork, and
 * fills-drawn boxes at twelve pixels read as lint. Both live beside the mod's other pictures
 * under {@code assets/tenet/textures/gui/}, drawn stretched into whatever box the row offers.
 * One draw rule for the book's rows, the HUD's pins and the notices, so the three cannot
 * disagree about what done looks like.
 */
public final class CheckmarkArt {

    /** The todo box, by its own path. */
    public static final ResourceLocation UNCHECKED = ResourceLocation.fromNamespaceAndPath("tenet",
            "textures/gui/checkmark_unchecked.png");

    /** The done box, by its own path. */
    public static final ResourceLocation CHECKED = ResourceLocation.fromNamespaceAndPath("tenet",
            "textures/gui/checkmark_checked.png");

    private CheckmarkArt() {
    }

    /** The box this state wears. */
    public static ResourceLocation forState(boolean done) {
        return done ? CHECKED : UNCHECKED;
    }

    /** The box, stretched into the row's own square. */
    public static void draw(GuiRenderer r, int x, int y, int box, boolean done) {
        r.texture(forState(done), x, y, box, box);
    }

    /**
     * Whether this task wears the state box: a checkmark with no author picture.
     *
     * <p>An override wins when present — an item, a texture, a sprite or an egg travels in its
     * own field, and any of them is the author saying what this row wears. A bare checkmark has
     * none of those, so the box is the only picture it can wear.
     */
    public static boolean wearsBox(ClientQuestCache.TaskEntry task) {
        return task != null && "tenet:checkmark".equals(task.type())
                && task.textureIcon().isEmpty() && task.spriteIcon().isEmpty()
                && task.picture().isEmpty();
    }
}
