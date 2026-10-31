package dev.ellipog.tenet.mixin;

import dev.ellipog.tenet.client.WorldFreeze;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Holds the world still behind an open book: the two hooks {@link WorldFreeze} needs.
 *
 * <h2>Why two hooks, and why each may be missing</h2>
 *
 * <p>The game prepares a frame in two halves. {@code extract} copies the world into a render state, and
 * {@code render} draws that state. Skipping only the draw would still pay for the copy, which walks every
 * visible entity, block entity and particle; skipping only the copy would draw a stale state. So the copy
 * asks {@link WorldFreeze#extractLevel} first, and the draw call is wrapped so a frozen frame shows the
 * capture instead.
 *
 * <p>Both are {@code require = 0}. If another mod has replaced either call, the hook is simply absent and
 * the world is drawn every frame. The copy is never skipped before the draw hook has run once, so a
 * missing draw hook can never leave the screen showing a state nobody extracted.
 *
 * <p>Everything after the draw still runs on a frozen frame: the glowing outline blit, the post effects
 * and the menu blur all read the screen, and the capture is taken before them, so they produce the same
 * picture they did on the frame it was taken.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererFreezeMixin {

    @Shadow
    @Final
    private RenderTarget mainRenderTarget;

    @WrapWithCondition(
            method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extract(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V"),
            require = 0)
    private boolean tenet$extractLevel(LevelExtractor extractor, DeltaTracker deltaTracker, Camera camera,
            float worldPartialTicks) {
        return WorldFreeze.extractLevel();
    }

    @WrapOperation(
            method = "render()V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"),
            require = 0)
    private void tenet$renderLevel(GameRenderer renderer, Operation<Void> original) {
        if (WorldFreeze.drawCaptured(mainRenderTarget)) {
            return;
        }
        original.call(renderer);
        WorldFreeze.afterLevel(mainRenderTarget);
    }
}
