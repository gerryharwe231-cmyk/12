package com.slopeconnector.surface.client;

import com.slopeconnector.hotfix.ArcTrimBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * ArcTrim renderer for Conquest grass-bearing full blocks.
 *
 * <p>Conquest grass layers are multipart baked models: a solid grass/dirt body plus several
 * no-collision grass_block_ext / grass_block_ext2 decorations.  Auto-trim only needs the solid body.
 * Rendering the whole multipart model (or choosing a random no-cull quad as the material hint)
 * changes the cut cell's appearance and keeps decorative grass floating above it.  This renderer
 * intentionally samples only directional/cull-face quads from the source BakedModel.  The body
 * sprite and tint are preserved, while every no-collision multipart decoration is excluded.</p>
 */
public final class ConquestGrassTrimRenderer {
    private static final float CUT_RECESS = 0.0015f;
    /** 1.5.2 blockstates whose multipart JSON actually references grass_block_ext/ext2. */
    private static final Set<String> DECORATED_GRASS = Set.of(
            "clover_covered_grass", "clover_covered_grass_layer", "grass_block_layer",
            "grass_covered_limestone", "taiga_grass", "taiga_grass_layer");
    private static final Map<ArcTrimBlockEntity, CachedMesh> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ConquestGrassTrimRenderer() {}

    public static boolean renderIfSupported(ArcTrimBlockEntity entity, MatrixStack matrices,
                                            VertexConsumerProvider consumers,
                                            int fallbackLight, int overlay) {
        if (entity == null || entity.getWorld() == null) return false;
        BlockState source = entity.getSourceState();
        if (!isConquestGrassBody(source)) return false;

        MinecraftClient client = MinecraftClient.getInstance();
        BakedModel model = client.getBlockRenderManager().getModel(source);
        int modelIdentity = System.identityHashCode(model);
        CachedMesh mesh = CACHE.get(entity);
        if (mesh == null || mesh.revision != entity.getRenderRevision()
                || !mesh.state.equals(source) || mesh.modelIdentity != modelIdentity) {
            mesh = compile(entity, source, model, modelIdentity);
            CACHE.put(entity, mesh);
        }
        if (mesh.faces.isEmpty()) return false;

        RenderLayer layer = RenderLayers.getBlockLayer(source);
        VertexConsumer consumer = consumers.getBuffer(layer);
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f position = entry.getPositionMatrix();
        Matrix3f normalMatrix = entry.getNormalMatrix();
        BlockPos pos = entity.getPos();

        for (Face face : mesh.faces) {
            int tint = 0xFFFFFF;
            if (face.tintIndex >= 0) {
                int sampled = client.getBlockColors().getColor(source, entity.getWorld(), pos, face.tintIndex);
                if (sampled != -1) tint = sampled & 0xFFFFFF;
            }
            int light = maxPacked(fallbackLight,
                    WorldRenderer.getLightmapCoordinates(entity.getWorld(), source, pos));
            int red = (tint >> 16) & 255;
            int green = (tint >> 8) & 255;
            int blue = tint & 255;
            emitTriangle(consumer, position, normalMatrix, face, light, overlay, red, green, blue);
        }
        return true;
    }

    private static CachedMesh compile(ArcTrimBlockEntity entity, BlockState source,
                                      BakedModel model, int modelIdentity) {
        List<Face> faces = new ArrayList<>();
        long seed = source.getRenderingSeed(entity.getPos());
        for (ArcTrimBlockEntity.Triangle triangle : entity.getTriangles()) {
            float[] xyz = triangle.xyz().clone();
            if (xyz.length < 9) continue;
            Vector3f normal = normal(xyz);
            if (normal == null) continue;
            if (triangle.cutFace()) {
                for (int i = 0; i < 3; i++) {
                    xyz[i * 3] -= normal.x * CUT_RECESS;
                    xyz[i * 3 + 1] -= normal.y * CUT_RECESS;
                    xyz[i * 3 + 2] -= normal.z * CUT_RECESS;
                }
            }
            Direction direction = dominant(normal);
            Material material = material(model, source, direction, seed);
            faces.add(new Face(xyz, normal, direction, material.sprite, material.tintIndex));
        }
        return new CachedMesh(entity.getRenderRevision(), source, modelIdentity, List.copyOf(faces));
    }

    /** Directional/cull-face quads contain the solid body; null-face quads contain decorative grass. */
    private static Material material(BakedModel model, BlockState state, Direction direction, long seed) {
        List<BakedQuad> quads = model.getQuads(state, direction, Random.create(seed + direction.ordinal()));
        BakedQuad best = null;
        double bestArea = -1.0;
        for (BakedQuad quad : quads) {
            double area = quadArea(quad);
            if (area > bestArea) {
                bestArea = area;
                best = quad;
            }
        }
        if (best != null) {
            return new Material(best.getSprite(), best.hasColor() ? best.getColorIndex() : -1);
        }
        // A weighted model can occasionally omit a directional quad.  Particle texture remains the
        // same grass body texture; keep grass tint on every non-bottom face instead of falling grey.
        return new Material(model.getParticleSprite(), direction == Direction.DOWN ? -1 : 0);
    }

    private static boolean isConquestGrassBody(BlockState state) {
        if (state == null || state.isAir()) return false;
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        if (!"conquest".equals(id.getNamespace())) return false;
        return DECORATED_GRASS.contains(id.getPath());
    }

    private static void emitTriangle(VertexConsumer consumer, Matrix4f position, Matrix3f normalMatrix,
                                     Face face, int light, int overlay,
                                     int red, int green, int blue) {
        float[] xyz = face.xyz;
        float[][] uv = new float[3][2];
        for (int i = 0; i < 3; i++) {
            float x = xyz[i * 3], y = xyz[i * 3 + 1], z = xyz[i * 3 + 2];
            float u0, v0;
            switch (face.direction.getAxis()) {
                case Y -> { u0 = x; v0 = z; }
                case X -> { u0 = z; v0 = 1.0f - y; }
                default -> { u0 = x; v0 = 1.0f - y; }
            }
            u0 = clamp01(u0);
            v0 = clamp01(v0);
            uv[i][0] = face.sprite.getMinU() + (face.sprite.getMaxU() - face.sprite.getMinU()) * u0;
            uv[i][1] = face.sprite.getMinV() + (face.sprite.getMaxV() - face.sprite.getMinV()) * v0;
        }
        for (int i = 0; i < 4; i++) {
            int sourceIndex = Math.min(i, 2);
            consumer.vertex(position, xyz[sourceIndex * 3], xyz[sourceIndex * 3 + 1], xyz[sourceIndex * 3 + 2])
                    .color(red, green, blue, 255)
                    .texture(uv[sourceIndex][0], uv[sourceIndex][1])
                    .overlay(overlay)
                    .light(light)
                    .normal(normalMatrix, face.normal.x, face.normal.y, face.normal.z)
                    .next();
        }
    }

    private static Vector3f normal(float[] xyz) {
        Vector3f a = new Vector3f(xyz[0], xyz[1], xyz[2]);
        Vector3f b = new Vector3f(xyz[3], xyz[4], xyz[5]);
        Vector3f c = new Vector3f(xyz[6], xyz[7], xyz[8]);
        Vector3f normal = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
        if (normal.lengthSquared() < 1.0E-10f) return null;
        return normal.normalize();
    }

    private static Direction dominant(Vector3f normal) {
        float ax = Math.abs(normal.x), ay = Math.abs(normal.y), az = Math.abs(normal.z);
        if (ay >= ax && ay >= az) return normal.y >= 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return normal.x >= 0 ? Direction.EAST : Direction.WEST;
        return normal.z >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static double quadArea(BakedQuad quad) {
        int[] data = quad.getVertexData();
        int stride = data.length / 4;
        if (stride < 3) return 0.0;
        Vector3f[] p = new Vector3f[4];
        for (int i = 0; i < 4; i++) {
            int base = i * stride;
            p[i] = new Vector3f(Float.intBitsToFloat(data[base]),
                    Float.intBitsToFloat(data[base + 1]), Float.intBitsToFloat(data[base + 2]));
        }
        return triangleArea(p[0], p[1], p[2]) + triangleArea(p[0], p[2], p[3]);
    }

    private static double triangleArea(Vector3f a, Vector3f b, Vector3f c) {
        return new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a)).length() * 0.5;
    }

    private static int maxPacked(int first, int second) {
        int block = Math.max(LightmapTextureManager.getBlockLightCoordinates(first),
                LightmapTextureManager.getBlockLightCoordinates(second));
        int sky = Math.max(LightmapTextureManager.getSkyLightCoordinates(first),
                LightmapTextureManager.getSkyLightCoordinates(second));
        return LightmapTextureManager.pack(block, sky);
    }

    private static float clamp01(float value) { return Math.max(0.0f, Math.min(1.0f, value)); }

    private record Material(Sprite sprite, int tintIndex) {}
    private record Face(float[] xyz, Vector3f normal, Direction direction, Sprite sprite, int tintIndex) {}
    private record CachedMesh(int revision, BlockState state, int modelIdentity, List<Face> faces) {}
}
