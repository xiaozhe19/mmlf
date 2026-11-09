package dev.mmlf.bridge.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;

/**
 * Client-only stand-in through which the render host draws the submitted content. It is an ordinary entity of this
 * runtime's world, so every renderer and shader mod that draws entities draws it, in each of its passes (the main
 * pass, a shader pack's shadow pass, outline passes), with that pass's own transforms and frustum. It never reaches
 * a server: markers have no network representation, and this one exists only in the client level.
 */
public final class RemoteProxy extends Marker {
    private static final int ID=-0x4d4d4c46;
    private RemoteProxy(ClientLevel level) { super(EntityType.MARKER,level); setId(ID); }

    private static RemoteProxy current;
    private static Renderer renderer;

    /** Render thread, once per frame on the render host: keeps one proxy at the camera of the current level. */
    static void place(Minecraft mc,boolean wanted) {
        ClientLevel level=mc.level;
        if(!wanted || level==null) { removeCurrent(); return; }
        // Next to the camera: renderers that cull entities by visible sections always keep the camera's section.
        var at=mc.gameRenderer.getMainCamera().getBlockPosition();
        if(current==null || current.level()!=level || current.isRemoved()) {
            removeCurrent();
            current=new RemoteProxy(level);
            moveTo(current,at);
            level.addEntity(current);
        } else moveTo(current,at);
    }
    /** Without interpolation: the renderer converts world coordinates relative to exactly this position. */
    private static void moveTo(RemoteProxy proxy,net.minecraft.core.BlockPos at) {
        proxy.setPos(at.getX()+0.5,at.getY()+0.5,at.getZ()+0.5);
        proxy.xo=proxy.xOld=proxy.getX(); proxy.yo=proxy.yOld=proxy.getY(); proxy.zo=proxy.zOld=proxy.getZ();
    }
    static void removeCurrent() {
        if(current==null) return;
        if(current.level() instanceof ClientLevel level && !current.isRemoved()) level.removeEntity(current.getId(),RemovalReason.DISCARDED);
        current=null;
    }

    /** {@code EntityRenderDispatcherMixin}: the renderer for proxies, created on first use. */
    public static EntityRenderer<?> renderer(Entity entity) {
        if(!(entity instanceof RemoteProxy)) return null;
        if(renderer==null) {
            var mc=Minecraft.getInstance();
            renderer=new Renderer(new EntityRendererProvider.Context(mc.getEntityRenderDispatcher(),mc.getItemRenderer(),mc.getBlockRenderer(),
                mc.getEntityRenderDispatcher().getItemInHandRenderer(),mc.getResourceManager(),mc.getEntityModels(),mc.font));
        }
        return renderer;
    }

    static final class Renderer extends EntityRenderer<RemoteProxy> {
        /** The frustum of the pass about to draw the proxy; each pass asks {@link #shouldRender} right before drawing. */
        private Frustum frustum;
        Renderer(EntityRendererProvider.Context context) { super(context); shadowRadius=0; shadowStrength=0; }
        @Override public boolean shouldRender(RemoteProxy entity,Frustum frustum,double x,double y,double z) { this.frustum=frustum; return true; }
        @Override public void render(RemoteProxy entity,float yaw,float partial,PoseStack poseStack,MultiBufferSource buffers,int light) {
            GeometryLayer.drawProxy(entity.getX(),entity.getY(),entity.getZ(),frustum,poseStack,buffers);
        }
        @Override public ResourceLocation getTextureLocation(RemoteProxy entity) { return TextureAtlas.LOCATION_BLOCKS; }
    }
}
