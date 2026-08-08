package com.slopeconnector.surface.mixin;

import com.slopeconnector.hotfix.ArcTrimBlockEntity;
import com.slopeconnector.hotfix.client.ArcTrimRenderer;
import com.slopeconnector.surface.client.ConquestGrassTrimRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Removes Conquest multipart decorative grass from ArcTrim while keeping the source body material. */
@Mixin(value = ArcTrimRenderer.class, remap = false, priority = 3000)
public abstract class ConquestGrassTrimRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void slopeconnectorSurface$conquestGrassBodyOnly(ArcTrimBlockEntity entity, float tickDelta,
                                                              MatrixStack matrices,
                                                              VertexConsumerProvider consumers,
                                                              int light, int overlay,
                                                              CallbackInfo ci) {
        if (ConquestGrassTrimRenderer.renderIfSupported(entity, matrices, consumers, light, overlay)) {
            ci.cancel();
        }
    }
}
