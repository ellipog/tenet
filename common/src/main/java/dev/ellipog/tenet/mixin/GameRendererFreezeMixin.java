package dev.ellipog.tenet.mixin;

import dev.ellipog.tenet.client.WorldFreeze;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Holds the world still behind an open book: the one hook {@link WorldFreeze} needs.
 *
 * <p>The frame's call that draws the world is wrapped, so a frozen frame copies the capture into the screen
 * instead, and a frame drawn normally is handed over afterwards to be kept. Only the call in
 * {@code render}: the world icon's screenshot draws the world through the same method, and it must always
 * see the real thing.
 *
 * <p>{@code require = 0}. If another mod has replaced the call, the hook is simply absent and the world is
 * drawn every frame.
 *
 * <p>Everything after the draw still runs on a frozen frame: the glowing outline blit, the post effects
 * and the menu blur all read the screen, and the capture is taken before them, so they produce the same
 * picture they did on the frame it was taken.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererFreezeMixin {

    @WrapOperation(
            method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"),
            require = 0)
    private void tenet$renderLevel(GameRenderer renderer, DeltaTracker deltaTracker, Operation<Void> original) {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (WorldFreeze.drawCaptured(main)) {
            return;
        }
        original.call(renderer, deltaTracker);
        WorldFreeze.afterLevel(main);
    }
}
