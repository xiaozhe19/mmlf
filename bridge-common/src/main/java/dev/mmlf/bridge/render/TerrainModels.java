package dev.mmlf.bridge.render;

import net.minecraft.client.resources.model.BakedModel;

/** The active loader installs its own terrain model implementation. */
public final class TerrainModels {
    private static volatile BakedModel model;
    private TerrainModels() {}
    public static void install(BakedModel value) { model=value; }
    public static BakedModel get() { return model; }
    public static boolean enabled() { return dev.mmlf.bridge.Experiments.TERRAIN && dev.mmlf.bridge.BridgeCore.isPrimary(); }
}
