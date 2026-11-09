package dev.mmlf.bridge;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.mmlf.bridge.render.GeometryLayer;
import dev.mmlf.bridge.render.RenderChannel;
import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.RemoteItemRef;
import dev.mmlf.common.RenderDynamicCodec;
import java.io.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * The submitter's items wherever the render host draws an item: in either hand (first and third person), dropped, in
 * an item frame, worn. On the render host such a stack is only a labelled proxy (paper), so drawing it would show
 * paper. Instead the render host asks the owner for the item's mesh per display context; the owner renders its real
 * stack with its own item renderer at the identity transform (item model space, the context's model transform
 * included) and publishes the batches; the render host then draws that mesh itself, in every pass, with its own
 * transform, light and overlay at that moment. Only the mesh crosses runtimes, so held items follow the hand without
 * delay and get the render host's shaders and shadows like any of its own items. GUI icons are handled separately.
 */
public final class ProxyItems {
    private ProxyItems() {}
    private static final String REQUESTS="item_requests", MESHES="item_meshes";
    private static final long UNUSED_NANOS=3_000_000_000L;
    private record Request(String key,String token,String payload,String context,boolean leftHand) {}

    // Render host.
    private static final Map<String,Request> requests=new LinkedHashMap<>();
    private static final Map<String,Long> lastUse=new HashMap<>();
    private static final Map<String,GeometryLayer.Resolved> meshes=new HashMap<>();
    private static final Map<String,List<RenderDynamicCodec.Batch>> pending=new HashMap<>();
    private static final RenderChannel.Cursor meshCursor=new RenderChannel.Cursor();
    private static String publishedRequests="";
    private static long lastRequestPublish;
    // Owner.
    private static final RenderChannel.Cursor requestCursor=new RenderChannel.Cursor();
    private static List<Request> wanted=List.of();
    private static byte[] lastMeshes;

    /** Render host, {@code ItemRendererMixin}: whether this draw of a proxy stack is replaced by the owner's mesh. */
    public static boolean drawnByOwner(ItemStack stack,ItemDisplayContext context,boolean leftHand,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        if(context==ItemDisplayContext.GUI || context==ItemDisplayContext.NONE) return false;
        if(!GeometryLayer.ownerSubmitting()) return false;
        var reference=RemoteItemRef.parse(RemoteItems.held(stack)).orElse(null);
        if(reference==null || !reference.owner().equals(GeometryLayer.submitter())) return false;
        String key=reference.token()+"|"+context.name()+"|"+leftHand;
        requests.computeIfAbsent(key,k->new Request(k,reference.token().toString(),String.valueOf(RemoteItems.payload(stack)),context.name(),leftHand));
        lastUse.put(key,System.nanoTime());
        var mesh=meshes.get(key);
        // Until the mesh arrives (a frame or two after the item first appears) nothing is drawn rather than paper.
        if(mesh!=null) GeometryLayer.drawResolved(mesh,pose.last(),buffers,light,overlay);
        return true;
    }

    /** Render host, start of each world frame: publishes which meshes it needs and takes the newest ones. */
    public static void hostFrame(Minecraft mc) {
        long now=System.nanoTime();
        for(var it=requests.keySet().iterator();it.hasNext();) {
            String key=it.next(); Long used=lastUse.get(key);
            if(used==null || now-used>UNUSED_NANOS) { it.remove(); lastUse.remove(key); meshes.remove(key); pending.remove(key); }
        }
        String signature=String.join(",",requests.keySet());
        if(!signature.equals(publishedRequests) || now-lastRequestPublish>1_000_000_000L) {
            try {
                var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes);
                out.writeInt(requests.size());
                for(Request r:requests.values()) { out.writeUTF(r.key()); out.writeUTF(r.token()); out.writeUTF(r.payload()); out.writeUTF(r.context()); out.writeBoolean(r.leftHand()); }
                out.flush();
                RenderChannel.publish(REQUESTS,bytes.toByteArray());
                publishedRequests=signature; lastRequestPublish=now;
            } catch(IOException e) { MmlfLog.sampled("items","item_requests_failed",5000,Map.of("exception",e.toString())); }
        }
        try {
            byte[] data=RenderChannel.poll(GeometryLayer.submitter(),MESHES,meshCursor,RenderDynamicCodec.MAX_BYTES+65536);
            if(data!=null) {
                var in=new DataInputStream(new ByteArrayInputStream(data)); int n=in.readInt();
                if(n<0 || n>256) throw new IOException("invalid item mesh count");
                for(int i=0;i<n;i++) {
                    String key=in.readUTF(); byte[] frame=in.readNBytes(in.readInt());
                    if(requests.containsKey(key)) pending.put(key,RenderDynamicCodec.decode(frame).batches());
                }
            }
        } catch(IOException | RuntimeException e) { MmlfLog.sampled("items","item_meshes_receive_failed",5000,Map.of("exception",e.toString())); }
        // A mesh whose texture has not arrived yet is retried each frame.
        for(var it=pending.entrySet().iterator();it.hasNext();) {
            var entry=it.next(); var resolved=GeometryLayer.resolveBatches(entry.getValue());
            if(resolved!=null) { meshes.put(entry.getKey(),resolved); it.remove(); }
        }
    }

    /** Render host scene reset. */
    public static void reset() { requests.clear(); lastUse.clear(); meshes.clear(); pending.clear(); meshCursor.reset(); publishedRequests=""; }

    /** Owner, each frame inside its dynamic capture: renders the requested items' meshes. */
    public static void ownerFrame(Minecraft mc,long generation) {
        try {
            byte[] data=RenderChannel.poll(BridgeCore.primaryId(),REQUESTS,requestCursor,1024*1024);
            if(data!=null) {
                var in=new DataInputStream(new ByteArrayInputStream(data)); int n=in.readInt();
                if(n<0 || n>256) throw new IOException("invalid item request count");
                var list=new ArrayList<Request>(n);
                for(int i=0;i<n;i++) list.add(new Request(in.readUTF(),in.readUTF(),in.readUTF(),in.readUTF(),in.readBoolean()));
                wanted=list;
            }
        } catch(IOException | RuntimeException e) { MmlfLog.sampled("items","item_requests_receive_failed",5000,Map.of("exception",e.toString())); }
        if(wanted.isEmpty() && lastMeshes==null) return;
        var itemRenderer=mc.getItemRenderer();
        try {
            var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes);
            var entries=new ArrayList<byte[]>(); var keys=new ArrayList<String>();
            for(Request r:wanted) {
                ItemStack stack=RemoteItems.resolveOwned(UUID.fromString(r.token()),r.payload().equals("null")?null:r.payload(),mc.level.registryAccess());
                if(stack==null) continue;
                ItemDisplayContext context;
                try { context=ItemDisplayContext.valueOf(r.context()); } catch(IllegalArgumentException e) { continue; }
                var batches=dev.mmlf.bridge.render.DynamicCapture.recordBatches(mc,source->
                    itemRenderer.render(stack,context,r.leftHand(),new PoseStack(),source,LightTexture.FULL_BRIGHT,OverlayTexture.NO_OVERLAY,itemRenderer.getModel(stack,mc.level,null,0)));
                keys.add(r.key());
                entries.add(RenderDynamicCodec.encode(new RenderDynamicCodec.Frame(BridgeCore.runtimeId(),mc.level.dimension().location().toString(),generation,0,0,0,0,0,batches)));
            }
            out.writeInt(entries.size());
            for(int i=0;i<entries.size();i++) { out.writeUTF(keys.get(i)); out.writeInt(entries.get(i).length); out.write(entries.get(i)); }
            out.flush();
            byte[] encoded=bytes.toByteArray();
            // Most items look the same every frame; publish only when something changed (animated items, new requests).
            if(lastMeshes==null || !Arrays.equals(encoded,lastMeshes)) { RenderChannel.publish(MESHES,encoded); lastMeshes=encoded; }
            if(wanted.isEmpty()) lastMeshes=null;
        } catch(IOException | RuntimeException | LinkageError e) { MmlfLog.sampled("items","item_meshes_failed",5000,Map.of("exception",e.toString())); }
    }
}
