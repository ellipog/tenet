package dev.ellipog.tenet;

import dev.ellipog.armature.api.client.ArmatureClient;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The quest book.
 *
 * <p>Right-clicking opens the quest screen — but note what this class is <b>not</b> doing. It
 * is a common class: it runs on a dedicated server, where {@code net.minecraft.client} does
 * not exist. So it names no screen, no {@code Minecraft}, no {@code Screen}. It asks
 * {@link ArmatureClient} to open one <i>by id</i>, and the actual registry lives in a class
 * only a client ever loads.
 *
 * <p>The {@code isClientSide} guard is what keeps that call off the server. Without it, the
 * screen opener would be asked to open something that cannot exist there — harmless in this
 * particular case, which returns false, but the habit is what keeps a mod from crashing on a
 * server the first time something on this path really does need a client.
 */
public final class QuestBookItem extends Item {

    public QuestBookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            // Refused with the pack's sentence when the pack disabled its book, like every other
            // open. Read off the synced tree rather than through the screen — this class runs on a
            // dedicated server too, where no screen class may load. See
            // `QuestBookScreen.checkOpenAllowed` for the choke the client-only paths share.
            if (dev.ellipog.tenet.client.ClientQuestCache.guiDisabled()) {
                player.sendSystemMessage(
                        net.minecraft.network.chat.Component.translatable("tenet.screen.book_disabled"));
            }
            else {
                ArmatureClient.openScreen(Tenet.QUEST_BOOK_SCREEN);
            }
        }

        // sidedSuccess: the client is told the interaction succeeded so the swing animation
        // plays, and the server is told the same without duplicating the effect. Returning
        // SUCCESS on both sides is how you get an animation that fires twice.
        return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.CONSUME;
    }
}
