package com.slopeconnector.surface.mixin;

import com.slopeconnector.SlopeConnectorMod;
import com.slopeconnector.hotfix.ArcRibbonGenerator;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rejects geometrically degenerate two/three-point requests before the bundled generator allocates
 * samples/prisms.  In particular, when the selected face is UP/DOWN and both endpoints lie on one
 * vertical line, their projection into the current connection plane has zero length.  Letting that
 * reach the old circle construction can produce an enormous/near-singular arc and thousands of
 * render prisms, which is the source of the local FPS collapse reported by the user.
 */
@Mixin(value = ArcRibbonGenerator.class, remap = false)
public abstract class ArcRibbonDegenerateGuardMixin {
    private static final double MIN_PLANAR_RUN = 0.125;
    private static final double MAX_ENDPOINT_DISTANCE = 256.0;

    @Inject(method = "generate", at = @At("HEAD"), cancellable = true, remap = false)
    private static void slopeconnectorSurface$rejectDegenerateArc(
            World world, BlockPos startBlock, BlockPos controlBlock, BlockPos endBlock,
            BlockState source, SlopeConnectorMod.PlayerSettings settings,
            CallbackInfoReturnable<ArcRibbonGenerator.Result> cir) {
        if (startBlock == null || endBlock == null || settings == null) return;
        Direction face = settings.face == null ? Direction.UP : settings.face;
        Vec3d normal = new Vec3d(face.getOffsetX(), face.getOffsetY(), face.getOffsetZ());
        Vec3d raw = Vec3d.ofCenter(endBlock).subtract(Vec3d.ofCenter(startBlock));
        double endpointDistance = raw.length();
        if (!Double.isFinite(endpointDistance) || endpointDistance < 0.05) {
            cir.setReturnValue(error("两个端点距离太短，无法生成稳定圆弧"));
            return;
        }
        if (endpointDistance > MAX_ENDPOINT_DISTANCE) {
            cir.setReturnValue(error("两个端点距离超过 256 格，为避免生成过量弧段已拒绝"));
            return;
        }
        double normalDistance = raw.dotProduct(normal);

        // For an UP/DOWN face, two same-height blocks on one horizontal block-axis line are the
        // exact counterpart of the cardinal-face degeneracy the original generator already rejects.
        // The bundled two-point helper otherwise interprets them as a huge vertical sag/arch (the
        // pathological cap shown by the user), allocating a large mesh and tanking FPS nearby.
        if (face.getAxis() == Direction.Axis.Y
                && Math.abs(raw.y) < MIN_PLANAR_RUN
                && (Math.abs(raw.x) < MIN_PLANAR_RUN || Math.abs(raw.z) < MIN_PLANAR_RUN)) {
            cir.setReturnValue(error("上下方向下两个端点处于同高度的水平直线上，无法形成稳定圆弧；请改变端点位置或切换当前面"));
            return;
        }

        Vec3d planar = raw.subtract(normal.multiply(normalDistance));
        double planarRun = planar.length();
        if (!Double.isFinite(planarRun) || planarRun < MIN_PLANAR_RUN) {
            cir.setReturnValue(error("两个端点在当前面的连接平面内共线/重合，无法形成稳定圆弧；请改变端点位置或切换当前面"));
            return;
        }

        // THREE mode needs a real second degree of freedom as well.  A control point that projects
        // onto the same endpoint line cannot define a stable circle and must not be allowed to fall
        // through to a near-infinite radius solution.
        if (settings.arcPointMode == SlopeConnectorMod.ArcPointMode.THREE && controlBlock != null) {
            Vec3d control = Vec3d.ofCenter(controlBlock).subtract(Vec3d.ofCenter(startBlock));
            Vec3d controlPlanar = control.subtract(normal.multiply(control.dotProduct(normal)));
            if (!Double.isFinite(controlPlanar.length()) || controlPlanar.length() < 0.05) {
                cir.setReturnValue(error("三点模式控制点在当前连接平面内退化，无法确定稳定圆弧"));
            }
        }
    }

    private static ArcRibbonGenerator.Result error(String message) {
        return new ArcRibbonGenerator.Result(0, 0, 0, 0, message);
    }
}
