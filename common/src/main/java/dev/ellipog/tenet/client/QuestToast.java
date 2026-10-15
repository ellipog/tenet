package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiGraphicsRenderer;
import dev.ellipog.armature.client.ui.kit.Measure;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The notice that reaches a player who is not looking at the book.
 *
 * <h2>Why not a SystemToast</h2>
 *
 * <p>Vanilla's is text on vanilla's own background, which reads as a system message; a quest has an
 * icon, a name and a palette, and the notice is the one place the mod speaks outside its own screen.
 * So this is a real {@link Toast}, drawn through the toolkit's renderer seam — a panel in Tenet's
 * theme, the subject's own icon, the label and the title — which costs one entry on the seam list
 * (see {@code check_seam.py}) and no new network message: everything it shows already reached the
 * client on the tree and progress payloads.
 *
 * <h2>It does not animate, deliberately</h2>
 *
 * <p>Vanilla toasts do not fade either, and a notice whose whole job is to be readable by someone
 * mid-task is the last place to add movement — so there is nothing here for the Motion switch to
 * disable, which is the switch honoured by construction.
 *
 * <h2>The token, and why there is one per kind</h2>
 *
 * <p>{@code tenet:quest/<id>}, {@code tenet:task/<id>/<index>} or {@code tenet:chapter/<id>}, so the
 * notifier can ask whether <i>this</i> notice is already being announced ({@code ToastManager.getToast})
 * and a repeatable quest finished twice in quick succession is told once rather than queued twice. Three
 * kinds rather than one because a quest completing and a task of it completing are two different things
 * that happen about a moment apart: one token for both would swallow the second as a duplicate of the
 * first, which is the one fault this token exists to prevent.
 *
 * <h2>The label is the caller's</h2>
 *
 * <p>A task's notice and a quest's are the same control saying different things, so the word above the
 * title travels with the notice rather than being a constant here — a constant would have labelled a
 * completed task "Quest complete".
 */
public final class QuestToast implements Toast {

    /** The icon's box, in pixels. Fits the 32-pixel toast with four either side. */
    private static final int ICON_BOX = 20;

    private final String token;
    private final ItemStack icon;
    /** The texture path when the subject wears a texture icon, and empty otherwise. */
    private final String texture;
    /** The atlas region when the subject wears a sprite icon, and empty otherwise. */
    private final String sprite;
    private final Component label;
    private final Component title;
    private long bornAt = -1L;
    private long lastSeenAt = -1L;

    public QuestToast(String token, ItemStack icon, Component label, Component title) {
        this(token, icon, "", "", label, title);
    }

    public QuestToast(String token, ItemStack icon, String texture, Component label, Component title) {
        this(token, icon, texture, "", label, title);
    }

    public QuestToast(String token, ItemStack icon, String texture, String sprite, Component label,
                      Component title) {
        this.token = token;
        this.icon = icon;
        this.texture = texture == null ? "" : texture;
        this.sprite = sprite == null ? "" : sprite;
        this.label = label;
        this.title = title;
    }

    /** The identity of a quest's notice, for {@code getToast}'s dedupe and for this class's own. */
    public static String tokenFor(String questId) {
        return "tenet:quest/" + questId;
    }

    /** The same for one task of one quest: a different moment, so a different notice. */
    public static String tokenForTask(String questId, int taskIndex) {
        return "tenet:task/" + questId + "/" + taskIndex;
    }

    /** And for a chapter, which is not a quest and has an id of its own. */
    public static String tokenForChapter(String chapterId) {
        return "tenet:chapter/" + chapterId;
    }

    @Override
    public Object getToken() {
        return token;
    }

    @Override
    public Visibility getWantedVisibility() {
        // SHOW until the notice has been visible a full lifetime: the draw stamps both ends, so a
        // toast behind two others does not spend its life waiting in the queue.
        if (bornAt < 0L || lastSeenAt < 0L) {
            return Visibility.SHOW;
        }
        return lastSeenAt - bornAt >= lifetimeMillis() ? Visibility.HIDE : Visibility.SHOW;
    }

    @Override
    public void update(net.minecraft.client.gui.components.toasts.ToastManager manager, long now) {
        // Nothing to advance: the lifetime is measured in draws, stamped in `extractRenderState`
        // below, and the manager only asks `getWantedVisibility`. Present because the interface
        // requires it, empty because the state already moves where it should.
    }

    private static long lifetimeMillis() {
        return (long) (ToastStack.LIFETIME_MILLIS
                * net.minecraft.client.Minecraft.getInstance().gui.toastManager()
                        .getNotificationDisplayTimeMultiplier());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long now) {
        if (bornAt < 0L) {
            bornAt = now;
        }
        lastSeenAt = now;

        GuiGraphicsRenderer r = new GuiGraphicsRenderer(graphics);
        try (ArmatureTheme.Scope ignored = ArmatureTheme.scope(ClientAppearance.LOOK.main())) {
            ArmatureTheme.panel(r, 0, 0, width(), height());
            net.minecraft.resources.Identifier textureId =
                    texture.isEmpty() ? null : net.minecraft.resources.Identifier.tryParse(texture);
            net.minecraft.resources.Identifier spriteId =
                    sprite.isEmpty() ? null : net.minecraft.resources.Identifier.tryParse(sprite);
            if (textureId != null) {
                r.texture(textureId, 5, (height() - ICON_BOX) / 2, ICON_BOX, ICON_BOX);
            }
            else if (spriteId != null) {
                r.sprite(spriteId, 5, (height() - ICON_BOX) / 2, ICON_BOX, ICON_BOX, 0xFFFFFFFF);
            }
            else {
                r.icon(icon, 5, (height() - ICON_BOX) / 2, ICON_BOX);
            }

            int textX = 5 + ICON_BOX + 6;
            int room = width() - textX - 6;
            Measure measure = Measure.of(r::textWidth, r.lineHeight());
            r.text(Measure.truncate(label.getString(), room, measure), textX, 6,
                    ArmatureTheme.faint());
            r.text(Measure.truncate(title.getString(), room, measure), textX, 6 + r.lineHeight() + 1,
                    ArmatureTheme.title());
        }
    }

    @Override
    public int width() {
        return 160;
    }

    @Override
    public int height() {
        return 32;
    }
}
