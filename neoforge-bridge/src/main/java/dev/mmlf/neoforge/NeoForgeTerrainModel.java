package dev.mmlf.neoforge;

import dev.mmlf.bridge.render.TerrainMeshes;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

/** NeoForge passes the block position through model data to select its remote quads. */
public final class NeoForgeTerrainModel implements BakedModel {
    private static final ModelProperty<BlockPos> POSITION=new ModelProperty<>();
    @Override public ModelData getModelData(BlockAndTintGetter world,BlockPos pos,BlockState state,ModelData data) { return data.derive().with(POSITION,pos.immutable()).build(); }
    private static String pass(RenderType type) {
        if(type==RenderType.solid()) return "solid";
        if(type==RenderType.cutoutMipped()) return "cutout_mipped";
        if(type==RenderType.cutout()) return "cutout";
        if(type==RenderType.translucent()) return "translucent";
        if(type==RenderType.tripwire()) return "tripwire";
        return "";
    }
    @Override public ChunkRenderTypeSet getRenderTypes(BlockState state,RandomSource random,ModelData data) {
        var pos=data.get(POSITION); if(pos==null) return ChunkRenderTypeSet.none();
        var layers=new ArrayList<RenderType>();
        for(String pass:TerrainMeshes.at(pos).keySet()) switch(pass) {
            case "solid" -> layers.add(RenderType.solid());
            case "cutout_mipped" -> layers.add(RenderType.cutoutMipped());
            case "cutout" -> layers.add(RenderType.cutout());
            case "translucent" -> layers.add(RenderType.translucent());
            case "tripwire" -> layers.add(RenderType.tripwire());
        }
        return ChunkRenderTypeSet.of(layers);
    }
    @Override public List<BakedQuad> getQuads(BlockState state,Direction side,RandomSource random,ModelData data,RenderType type) {
        var pos=data.get(POSITION); return side!=null || pos==null || type==null ? List.of() : TerrainMeshes.at(pos).getOrDefault(pass(type),List.of());
    }
    @Override public List<BakedQuad> getQuads(BlockState state,Direction side,RandomSource random) { return List.of(); }
    @Override public boolean useAmbientOcclusion() { return false; }
    @Override public boolean isGui3d() { return false; }
    @Override public boolean usesBlockLight() { return true; }
    @Override public boolean isCustomRenderer() { return false; }
    @Override public TextureAtlasSprite getParticleIcon() { return Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS).getSprite(net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation()); }
    @Override public ItemOverrides getOverrides() { return ItemOverrides.EMPTY; }
}
