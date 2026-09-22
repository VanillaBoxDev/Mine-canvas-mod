package org.sawiq.minecanvas.fabric.client.video;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.sawiq.minecanvas.fabric.client.config.MineCanvasClientConfig;
import org.sawiq.minecanvas.fabric.client.state.ScreenState;

public final class VideoScreenRenderer {

    private static final double EPS = 0.01; // насколько “над блоком” рисуем

    private VideoScreenRenderer() {}

    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(VideoScreenRenderer::onAfterEntities);
    }

    private static void onAfterEntities(WorldRenderContext ctx) {
        if (!MineCanvasClientConfig.get().renderVideo) return;

        PoseStack matrices = ctx.matrices();
        if (matrices == null) return;

        Minecraft client = Minecraft.getInstance();
        Vec3 cam = ctx.worldState().cameraRenderState.pos;
        MultiBufferSource consumers = ctx.consumers();

        matrices.pushPose();
        matrices.translate(-cam.x, -cam.y, -cam.z);

        for (VideoScreen screen : VideoScreenManager.all()) {
            ScreenState st = screen.state();
            if (!VideoScreenManager.isCompatibleWithCurrentWorld(st, client)) continue;
            screen.renderPlayback();
            if (!screen.hasTexture()) continue;
            drawScreen(consumers, matrices, cam, st, screen.textureId());
            if (screen.showsPauseOverlay()) drawPauseOverlay(consumers, matrices, cam, st);
        }

        matrices.popPose();
    }

    private static void drawScreen(MultiBufferSource consumers,
                                   PoseStack matrices,
                                   Vec3 cam,
                                   ScreenState s,
                                   Identifier textureId) {

        int minX = s.minX(), maxX = s.maxX();
        int minY = s.minY(), maxY = s.maxY();
        int minZ = s.minZ(), maxZ = s.maxZ();

        int overlay = OverlayTexture.NO_OVERLAY;
        int light = LightTexture.FULL_BRIGHT;

        VertexConsumer vc = consumers.getBuffer(RenderTypes.entityCutoutNoCullZOffset(textureId));
        PoseStack.Pose pose = matrices.last();
            if (s.axis() == 0) { // XY, Z фиксирован
                double zPlane = minZ + 0.5;
                boolean frontIsNegative = cam.z < zPlane;
                double z = frontIsNegative ? (minZ - EPS) : ((maxZ + 1.0) + EPS);

                double x1 = minX,     y1 = minY;
                double x2 = maxX + 1, y2 = minY;
                double x3 = maxX + 1, y3 = maxY + 1;
                double x4 = minX,     y4 = maxY + 1;

                float nx = 0, ny = 0, nz = (float) (frontIsNegative ? -1.0 : +1.0);

                quadTwoSidedNoMirrorU(vc, pose, frontIsNegative,
                        x2, y1, z,  x1, y2, z,  x4, y3, z,  x3, y4, z,
                        overlay, light, nx, ny, nz);

            } else if (s.axis() == 1) { // XZ, Y фиксирован
                double yPlane = minY + 0.5;
                boolean frontIsNegative = cam.y < yPlane;
                double y = frontIsNegative ? (minY - EPS) : ((maxY + 1.0) + EPS);

                double x1 = minX,     z1 = minZ;
                double x2 = maxX + 1, z2 = minZ;
                double x3 = maxX + 1, z3 = maxZ + 1;
                double x4 = minX,     z4 = maxZ + 1;

                float nx = 0, ny = (float) (frontIsNegative ? -1.0 : +1.0), nz = 0;

                quadTwoSidedNoMirrorU(vc, pose, frontIsNegative,
                        x1, y, z1,  x2, y, z2,  x3, y, z3,  x4, y, z4,
                        overlay, light, nx, ny, nz);

            } else { // axis == 2, YZ, X фиксирован
                double xPlane = minX + 0.5;
                boolean frontIsNegative = cam.x < xPlane;
                double x = frontIsNegative ? (minX - EPS) : ((maxX + 1.0) + EPS);

                double y1 = minY,     z1 = minZ;
                double y2 = minY,     z2 = maxZ + 1;
                double y3 = maxY + 1, z3 = maxZ + 1;
                double y4 = maxY + 1, z4 = minZ;

                float nx = (float) (frontIsNegative ? -1.0 : +1.0), ny = 0, nz = 0;

                quadTwoSidedNoMirrorU(vc, pose, frontIsNegative,
                        x, y1, z1,  x, y2, z2,  x, y3, z3,  x, y4, z4,
                        overlay, light, nx, ny, nz);
            }
    }

    private static void drawPauseOverlay(MultiBufferSource consumers,
                                          PoseStack matrices,
                                          Vec3 cam,
                                          ScreenState s) {
        Identifier textureId = ScreenOverlayTextures.textureId();
        if (textureId == null) return;
        double half = Math.min(s.blocksW(), s.blocksH()) * 0.13;
        double cx = (s.minX() + s.maxX() + 1.0) * 0.5;
        double cy = (s.minY() + s.maxY() + 1.0) * 0.5;
        double cz = (s.minZ() + s.maxZ() + 1.0) * 0.5;
        int overlay = OverlayTexture.NO_OVERLAY;
        int light = LightTexture.FULL_BRIGHT;

        VertexConsumer vc = consumers.getBuffer(RenderTypes.entityCutoutNoCullZOffset(textureId));
        PoseStack.Pose pose = matrices.last();
            if (s.axis() == 0) {
                boolean frontIsNegative = cam.z < s.minZ() + 0.5;
                double z = frontIsNegative ? s.minZ() - 2 * EPS : s.maxZ() + 1.0 + 2 * EPS;
                float nz = (float) (frontIsNegative ? -1.0 : 1.0);
                quadTwoSidedNoMirrorU(vc, pose, frontIsNegative,
                        cx + half, cy - half, z, cx - half, cy - half, z,
                        cx - half, cy + half, z, cx + half, cy + half, z,
                        overlay, light, 0, 0, nz);
            } else if (s.axis() == 1) {
                boolean frontIsNegative = cam.y < s.minY() + 0.5;
                double y = frontIsNegative ? s.minY() - 2 * EPS : s.maxY() + 1.0 + 2 * EPS;
                float ny = (float) (frontIsNegative ? -1.0 : 1.0);
                quadTwoSidedNoMirrorU(vc, pose, frontIsNegative,
                        cx - half, y, cz - half, cx + half, y, cz - half,
                        cx + half, y, cz + half, cx - half, y, cz + half,
                        overlay, light, 0, ny, 0);
            } else {
                boolean frontIsNegative = cam.x < s.minX() + 0.5;
                double x = frontIsNegative ? s.minX() - 2 * EPS : s.maxX() + 1.0 + 2 * EPS;
                float nx = (float) (frontIsNegative ? -1.0 : 1.0);
                quadTwoSidedNoMirrorU(vc, pose, frontIsNegative,
                        x, cy - half, cz - half, x, cy - half, cz + half,
                        x, cy + half, cz + half, x, cy + half, cz - half,
                        overlay, light, nx, 0, 0);
            }
    }

    private static void quadTwoSidedNoMirrorU(VertexConsumer vc, PoseStack.Pose e,
                                              boolean flipUFront,
                                              double x1, double y1, double z1,
                                              double x2, double y2, double z2,
                                              double x3, double y3, double z3,
                                              double x4, double y4, double z4,
                                              int overlay, int light,
                                               float nx, float ny, float nz) {
        quadTwoSidedNoMirrorU(vc, e, flipUFront, x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4,
            overlay, light, nx, ny, nz, 0xFFFFFFFF);
    }

    private static void quadTwoSidedNoMirrorU(VertexConsumer vc, PoseStack.Pose e,
                                               boolean flipUFront,
                                               double x1, double y1, double z1,
                                               double x2, double y2, double z2,
                                               double x3, double y3, double z3,
                                               double x4, double y4, double z4,
                                               int overlay, int light,
                                               float nx, float ny, float nz, int color) {

        if (!flipUFront) {
            v(vc, e, x1, y1, z1, 0, 1, overlay, light, nx, ny, nz, color);
            v(vc, e, x2, y2, z2, 1, 1, overlay, light, nx, ny, nz, color);
            v(vc, e, x3, y3, z3, 1, 0, overlay, light, nx, ny, nz, color);
            v(vc, e, x4, y4, z4, 0, 0, overlay, light, nx, ny, nz, color);

            v(vc, e, x1, y1, z1, 1, 1, overlay, light, -nx, -ny, -nz, color);
            v(vc, e, x2, y2, z2, 0, 1, overlay, light, -nx, -ny, -nz, color);
            v(vc, e, x3, y3, z3, 0, 0, overlay, light, -nx, -ny, -nz, color);
            v(vc, e, x4, y4, z4, 1, 0, overlay, light, -nx, -ny, -nz, color);
            return;
        }

        v(vc, e, x1, y1, z1, 1, 1, overlay, light, nx, ny, nz, color);
        v(vc, e, x2, y2, z2, 0, 1, overlay, light, nx, ny, nz, color);
        v(vc, e, x3, y3, z3, 0, 0, overlay, light, nx, ny, nz, color);
        v(vc, e, x4, y4, z4, 1, 0, overlay, light, nx, ny, nz, color);

        v(vc, e, x1, y1, z1, 0, 1, overlay, light, -nx, -ny, -nz, color);
        v(vc, e, x2, y2, z2, 1, 1, overlay, light, -nx, -ny, -nz, color);
        v(vc, e, x3, y3, z3, 1, 0, overlay, light, -nx, -ny, -nz, color);
        v(vc, e, x4, y4, z4, 0, 0, overlay, light, -nx, -ny, -nz, color);
    }

    private static void v(VertexConsumer vc,
                          PoseStack.Pose entry,
                          double x, double y, double z,
                          float u, float v,
                          int overlay, int light,
                           float nx, float ny, float nz, int color) {

        Vector3f p = new Vector3f((float) x, (float) y, (float) z);
        entry.pose().transformPosition(p);

        Vector3f n = new Vector3f(nx, ny, nz);
        entry.normal().transform(n);
        n.normalize();

        vc.addVertex(p.x, p.y, p.z, color, u, v, overlay, light, n.x, n.y, n.z);
    }
}
