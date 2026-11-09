package dev.mmlf.fabric;

import dev.mmlf.bridge.render.TerrainMeshes;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.BlendMode;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Fabric renderer API preserves each remote quad's terrain layer. */
public final class FabricTerrainModel implements BakedModel {
    @Override public boolean isVanillaAdapter() { return false; }
    @Override public void emitBlockQuads(BlockAndTintGetter view,BlockState state,BlockPos pos,Supplier<RandomSource> random,RenderContext context) {
        var renderer=RendererAccess.INSTANCE.getRenderer(); if(renderer==null) return;
        for(var pass:TerrainMeshes.at(pos).entrySet()) {
            BlendMode blend=switch(pass.getKey()) {
                case "solid" -> BlendMode.SOLID;
                case "cutout_mipped" -> BlendMode.CUTOUT_MIPPED;
                case "cutout" -> BlendMode.CUTOUT;
                default -> BlendMode.TRANSLUCENT;
            };
            var material=renderer.materialFinder().blendMode(blend).disableDiffuse(true).ambientOcclusion(TriState.FALSE).find();
            var emitter=context.getEmitter();
            for(BakedQuad quad:pass.getValue()) emitter.fromVanilla(quad,material,null).emit();
        }
    }
    @Override public List<BakedQuad> getQuads(BlockState state,Direction side,RandomSource random) { return List.of(); }
    @Override public boolean useAmbientOcclusion() { return false; }
    @Override public boolean isGui3d() { return false; }
    @Override public boolean usesBlockLight() { return true; }
    @Override public boolean isCustomRenderer() { return false; }
    @Override public TextureAtlasSprite getParticleIcon() { return Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS).getSprite(net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation()); }
    @Override public ItemTransforms getTransforms() { return ItemTransforms.NO_TRANSFORMS; }
    @Override public ItemOverrides getOverrides() { return ItemOverrides.EMPTY; }
}
