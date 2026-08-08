#!/usr/bin/env python3
from pathlib import Path
import json, zipfile

ROOT=Path(__file__).parents[1]
manager=(ROOT/'src/main/java/com/slopeconnector/model/EndpointCompanionManager.java').read_text()
entity=(ROOT/'src/main/java/com/slopeconnector/model/ModelBlockEntity.java').read_text()
block=(ROOT/'src/main/java/com/slopeconnector/model/ModelBlock.java').read_text()
endpoint=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelBlockRenderer.java').read_text()
wand=(ROOT/'src/main/java/com/slopeconnector/model/ModelRenderWandItem.java').read_text()
dimension=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonDimensionMixin.java').read_text()
trim=(ROOT/'src/main/java/com/slopeconnector/surface/client/ConquestGrassTrimRenderer.java').read_text()
mixin=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ConquestGrassTrimRendererMixin.java').read_text()
config=json.loads((ROOT/'src/main/resources/slopeconnector_surface_refine.mixins.json').read_text())

# Widened endpoint side tiles are real ModelBlocks.  Only the lateral row is physicalized to bound BE count.
for token in ['MAX_PHYSICAL_LATERAL_TILES = 32','world.setBlockState(pos, ModelSystemMod.MODEL_BLOCK.getDefaultState(), 2)',
              'model.setEndpointGrid(rootPos, companion','model.setSeamLayout(tileLayout, 1',
              'Only the lateral/"side width" axis is physicalized']:
    assert token in manager,token
assert 'EndpointCompanionManager.sync' in dimension
assert 'EndpointCompanionManager.sync' in wand
# Pure-white template endpoints already use the endpoint BER, so their lighting/culling path matches
# later captured endpoints while the physical ModelBlocks provide the real collision cells.
assert 'Blocks.WHITE_CONCRETE.getDefaultState()' in dimension
assert 'captured, true);' in dimension

# Grid identity/replaced terrain is persisted, so shrinking/removing can restore what was there before.
for token in ['EndpointGridMember','EndpointGridCompanion','EndpointGridRootX','EndpointReplacedState']:
    assert token in entity,token
assert 'trim.getSourceState()' in manager
assert 'removeCompanions' in manager

# Native white endpoint companions and custom skinned companions both cull internal touching faces.
assert 'isSideInvisible' in block
assert 'stateFrom.getBlock() == this' in block
assert 'EndpointCompanionManager.isSameGridNeighbor' in endpoint
# Root removal cleans companions, preventing orphan block entities.
assert 'onStateReplaced' in block and 'EndpointCompanionManager.removeCompanions(world, pos)' in block

# Performance: physical side companions are bounded/static, endpoint source quads are decoded once
# per BlockState/model identity, and Conquest trim geometry is cached by render revision.
endpoint_renderer=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelBlockRenderer.java').read_text()
assert 'SOURCE_CACHE = new ConcurrentHashMap' in endpoint_renderer
assert 'cached.model() == model' in endpoint_renderer
assert 'MAX_PHYSICAL_LATERAL_TILES = 32' in manager
assert 'CachedMesh' in trim and 'entity.getRenderRevision()' in trim

# Conquest grass ArcTrim is rendered from directional body quads only.  No null-face multipart
# grass_block_ext decorations are queried, but original sprite and tint are preserved.
for token in ['"conquest".equals(id.getNamespace())','DECORATED_GRASS.contains(id.getPath())',
              'model.getQuads(state, direction','best.getSprite()','best.getColorIndex()',
              'client.getBlockColors().getColor(source','CachedMesh']:
    assert token in trim,token
assert 'model.getQuads(state, null' not in trim
assert 'ArcTrimRenderer.class' in mixin and 'renderIfSupported' in mixin
assert 'ConquestGrassTrimRendererMixin' in config['client']

# Resource audit fixture from the uploaded Conquest jar: grass block layers are multipart and their
# decorative grass models are separate from the solid body model.  This is the compatibility case
# the renderer intentionally separates.
jar=Path('/mnt/data/ConquestReforged-fabric-1.20.1-1.5.2(1).jar')
if jar.exists():
    with zipfile.ZipFile(jar) as z:
        text=z.read('assets/conquest/blockstates/grass_block_layer.json').decode()
        body=z.read('assets/conquest/models/block/grass_block_height16.json').decode()
    assert 'grass_block_ext' in text and 'grass_block_ext2' in text
    assert 'grass_block_height16' in text
    assert 'minecraft:block/grass_block_top' in body
    assert '"tintindex": 0' in body

print('0.9.30 physical endpoint collision / Conquest grass trim compatibility checks passed')
