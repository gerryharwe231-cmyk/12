#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).parents[1]

screen=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcDimensionScreenMixin.java').read_text()
dimension=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonDimensionMixin.java').read_text()
orientation=(ROOT/'src/main/java/com/slopeconnector/surface/orientation/PlacedOrientationService.java').read_text()
resolver=(ROOT/'src/main/java/com/slopeconnector/model/ModelStateResolver.java').read_text()
model=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelArcRenderer.java').read_text()
template=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelTemplateArcRenderer.java').read_text()
finder=(ROOT/'src/main/java/com/slopeconnector/model/ArcComponentFinder.java').read_text()
fallback=(ROOT/'src/main/java/com/slopeconnector/hotfix/client/UnifiedSurfaceArcRenderer.java').read_text()
renderer_mixin=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonRendererMixin.java').read_text()
guard=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonDegenerateGuardMixin.java').read_text()
config=(ROOT/'src/main/resources/slopeconnector_surface_refine.mixins.json').read_text()
wand=(ROOT/'src/main/java/com/slopeconnector/model/ModelRenderWandItem.java').read_text()

# Side-width row has the exact same 62 + 6 + 62 / 130-wide value layout as the original dimension row.
assert 'Text.literal("侧面 -")' in screen and '.dimensions(rightX, y, 62, 20)' in screen
assert 'Text.literal("侧面 +")' in screen and '.dimensions(rightX + 68, y, 62, 20)' in screen
assert '.dimensions(rightX, y + 28, 130, 20)' in screen
assert 'context.fill(this.width - 158, 164, this.width - 8, 222' in screen

# Pure-white template endpoints receive the same lateral/vertical tile layout immediately after arc generation.
for token in ('synchronizeTemplateEndpoints(world, startBlock, endBlock)',
              'mapping.lateralTiles()', 'mapping.verticalTiles()',
              'EndpointCompanionManager.sync(world, pos, layout'):
    assert token in dimension,token

# Stairs and any other already-horizontal-facing/axis block are immune to the optional view override.
# Slabs are deliberately not immune; their top/bottom type is preserved by their real BlockState.
assert 'state.getBlock() instanceof StairsBlock' in orientation
assert 'state.getBlock() instanceof SlabBlock) return false' in orientation
assert 'name.equals("facing")' in orientation and 'name.equals("axis")' in orientation

# Captured ordinary models are decoded in a fixed +X source reference, so their baked stair facing,
# stair half/shape and slab top/bottom are not canonicalized away a second time.
assert 'return ConnectionStateHelper.isSupported(state) ? longitudinalDirection(state) : Direction.EAST;' in resolver
assert 'Direction direction = ModelStateResolver.sourceReferenceDirection(state);' in model
assert 'MaterialStateCodec.write(state)' in wand
assert 'if (!ConnectionStateHelper.isSupported(captured)) return captured;' in resolver

# Static arcs do not rebuild the whole mesh every 20 ticks. Rebuild is revision/model based instead.
assert 'CACHE_TTL' not in model
assert 'cached.sourceModel() == currentModel' in model
assert 'cachedRevision == entity.getRenderRevision()' in model
assert 'CURVED_SOURCE_SLICE = 1.0 / 16.0' in model
assert 'STRAIGHT_SOURCE_SLICE = 1.0 / 4.0' in model
assert 'isNearStraight(moduleStart, moduleLength)' in model
assert 'CACHE_TTL' not in template
assert 'cachedRevision == entity.getRenderRevision()' in template
assert 'DISCOVERY_RADIUS = 2' in finder
assert 'DISCOVERY_RADIUS = 2' in fallback
# The old bundled renderer disabled frustum culling completely. The patch re-enables normal BE culling.
assert 'rendersOutsideBoundingBox' in renderer_mixin
assert 'cir.setReturnValue(false);' in renderer_mixin

# Catastrophic UP/DOWN same-line arcs are rejected before the old generator can allocate a near-singular mesh.
assert 'MIN_PLANAR_RUN = 0.125' in guard
assert 'Vec3d planar = raw.subtract(normal.multiply(normalDistance));' in guard
assert 'planarRun < MIN_PLANAR_RUN' in guard
assert 'face.getAxis() == Direction.Axis.Y' in guard
assert 'Math.abs(raw.y) < MIN_PLANAR_RUN' in guard
assert '当前面的连接平面内共线/重合' in guard
assert 'MAX_ENDPOINT_DISTANCE = 256.0' in guard
assert 'ArcRibbonDegenerateGuardMixin' in config

# Simple math sanity: a vertical pair projected onto UP/DOWN plane has zero run; a horizontal pair does not.
def planar(raw, normal):
    dot=sum(a*b for a,b in zip(raw,normal))
    p=tuple(a-dot*b for a,b in zip(raw,normal))
    return sum(v*v for v in p)**0.5
assert planar((0,12,0),(0,1,0)) == 0.0
assert planar((12,0,0),(0,1,0)) == 12.0
assert planar((0,0,12),(1,0,0)) == 12.0

print('0.9.29 endpoint layout, state priority, performance and degenerate-arc checks passed')
