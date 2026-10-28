package dev.mmlf.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

/**
 * Opt-in primary self-test of hosted screens, run by {@link DemoScene} after its other steps. It uses the same paths as a
 * player: a routed use opens the owner's screen, and input is given to the primary's {@link RemoteGuiScreen}. The
 * remote side's blocks are mirrored like the other self-test targets. Results are printed; owner-side effects are
 * logged by the owner ({@code MMLF TEST SCREEN}, {@code MMLF TEST BOX}) and checked from its log.
 */
final class GuiSelfTest {
    /** Block x on the +x side, block y, and what the test does there. */
    private record Target(String name,int x,int y,String kind) {}
    private static final Target[] TARGETS={
        new Target("screen",1,100,"screen"), new Target("container",2,100,"container"), new Target("thirdparty",4,101,"thirdparty")};
    private static final int STEP=240;
    private static int stepStart=-1, opened=-1, closedAt=-1, baseline;
    private static boolean requested, acted, verified, done;
    private static String failure;
    private GuiSelfTest() {}

    static void tick(Minecraft mc,int t,int side) {
        if(done) return;
        int index=t/STEP, local=t%STEP;
        if(index>=TARGETS.length) {
            int after=t-TARGETS.length*STEP;
            if(after<=280) survival(mc,after,side); else { done=true; System.out.println("MMLF GUI SELFTEST complete"); }
            return;
        }
        Target target=TARGETS[index];
        if(local==0) { stepStart=t; opened=-1; closedAt=-1; requested=false; acted=false; verified=false; failure=null; }
        int blockX=side>0?target.x():-target.x();
        if(local==1) {
            double eyeY=101.62, dy=target.y()+0.5-eyeY, dz=1.5;
            float pitch=(float)Math.toDegrees(Math.atan2(-dy,dz));
            var server=mc.getSingleplayerServer();
            server.execute(()->server.getPlayerList().getPlayers().getFirst().connection.teleport(blockX+0.5,100,-0.5,180,pitch));
        }
        if(local==30) { requested=BridgeCore.requestInteraction(); baseline=count(mc); }
        if(local>30 && opened<0 && GuiClient.showing() && mc.screen instanceof RemoteGuiScreen) { opened=local; System.out.println("MMLF GUI SELFTEST name="+target.name()+" opened tick="+local); }
        if(local==150 && opened<0) { failure="not_opened"; report(target); return; }
        if(opened<0 || !(mc.screen instanceof RemoteGuiScreen screen)) {
            // The returned inventory reaches the client a few ticks after the close.
            if(opened>=0 && closedAt<0 && !GuiClient.open()) closedAt=local;
            if(closedAt>=0 && local==closedAt+20) report(target);
            return;
        }
        int since=local-opened;
        int w=screen.width,h=screen.height;
        if(since==10) Screenshot.grab(mc.gameDirectory,"mmlf-gui-"+target.name()+".png",mc.getMainRenderTarget(),m->System.out.println("MMLF screenshot "+m.getString()));
        if(since==15 && !acted) {
            acted=true;
            switch(target.kind()) {
                case "screen" -> {
                    click(screen,w/2.0,h/2.0-30);
                    for(char c:"mmlf".toCharArray()) screen.charTyped(c,0);
                    click(screen,w/2.0,h/2.0);
                    screen.mouseScrolled(w/2.0,h/2.0,0,1);
                }
                case "container" -> {
                    // Shift-click the box's first slot (cobblestone x16) into the player's inventory.
                    double left=(w-176)/2.0, top=(h-132)/2.0;
                    screen.keyPressed(GLFW.GLFW_KEY_LEFT_SHIFT,42,GLFW.GLFW_MOD_SHIFT);
                    click(screen,left+16,top+26);
                    screen.keyReleased(GLFW.GLFW_KEY_LEFT_SHIFT,42,0);
                }
                default -> {
                    for(char c:"cobble".toCharArray()) screen.charTyped(c,0);
                    screen.mouseScrolled(w/2.0,h/2.0,0,-1);
                }
            }
        }
        if(since==50) Screenshot.grab(mc.gameDirectory,"mmlf-gui-"+target.name()+"-after.png",mc.getMainRenderTarget(),m->System.out.println("MMLF screenshot "+m.getString()));
        if(since==55 && "container".equals(target.kind())) {
            int moved=count(mc)-baseline;
            System.out.println("MMLF GUI SELFTEST name=container leased_move cobblestone_delta="+moved+" passed="+(moved==16));
            if(moved!=16) failure="lease_update_missing";
        }
        if(since==60) { screen.keyPressed(GLFW.GLFW_KEY_ESCAPE,1,0); screen.keyReleased(GLFW.GLFW_KEY_ESCAPE,1,0); }
        if(since==140 && closedAt<0) { failure="not_closed"; report(target); }
    }
    /**
     * Survival round trip with a remote item: place one of eight remote stoves on the floor (the stack must drop to
     * seven), then hold attack on it until it breaks and its drop comes back (eight again).
     */
    private static int placed=-1;
    private static void survival(Minecraft mc,int local,int side) {
        var server=mc.getSingleplayerServer();
        String item=side>0?"farmersdelight:stove":"toms_storage:inventory_connector";
        if(local==0) {
            ItemStack proxy=RemoteCatalog.proxyFor(item);
            if(proxy==null) { System.out.println("MMLF SURVIVAL SELFTEST item="+item+" reason=no_catalog passed=false"); done=true; return; }
            server.execute(()->{
                var player=server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                player.getInventory().setItem(0,proxy.copyWithCount(8)); player.getInventory().selected=0;
                player.connection.teleport(0.5,100,1.5,0,70);
                player.inventoryMenu.broadcastChanges();
            });
            mc.player.getInventory().selected=0;
        }
        if(local==40) System.out.println("MMLF SURVIVAL SELFTEST place request="+BridgeCore.requestInteraction()+" before="+count(mc,0));
        if(local==80) { placed=count(mc,0); System.out.println("MMLF SURVIVAL SELFTEST placed count="+placed+" passed="+(placed==7)); }
        // Break the owner's demo block (no tool needed) by holding attack; its drop must come back to this inventory.
        int demoX=side>0?1:-1; String demo=side>0?"mmlf_neoforge_test:demo_block":"mmlf_fabric_test:demo_block";
        if(local==90) {
            baseline=countId(mc,demo);
            server.execute(()->server.getPlayerList().getPlayers().getFirst().connection.teleport(demoX+0.5,100,2.5,180,36.7f));
        }
        BridgeCore.selfTestAttacking=local>=110 && local<=210;
        if(local>=110 && local<=210 && local%3==0) BridgeCore.requestInteraction("attack");
        if(local==211) BridgeCore.attackReleased();
        // Walk onto the broken block, where its drop lies, as a player would to pick it up.
        if(local==215) server.execute(()->server.getPlayerList().getPlayers().getFirst().connection.teleport(demoX+0.5,100,0.5,180,30));
        if(local==260) {
            int gained=countId(mc,demo)-baseline;
            System.out.println("MMLF SURVIVAL SELFTEST broken demo_drop_gained="+gained+" passed="+(gained==1));
            server.execute(()->server.getPlayerList().getPlayers().getFirst().setGameMode(net.minecraft.world.level.GameType.CREATIVE));
        }
    }
    private static int countId(Minecraft mc,String id) {
        int n=0; var inventory=mc.player.getInventory();
        for(int i=0;i<inventory.getContainerSize();i++) { var d=RemoteItems.display(inventory.getItem(i)); if(d!=null && id.equals(d.get("id"))) n+=inventory.getItem(i).getCount(); }
        return n;
    }
    private static int count(Minecraft mc,int slot) { return mc.player==null?-1:mc.player.getInventory().getItem(slot).getCount(); }
    private static void click(RemoteGuiScreen screen,double x,double y) {
        screen.mouseMoved(x,y); screen.mouseClicked(x,y,GLFW.GLFW_MOUSE_BUTTON_LEFT); screen.mouseReleased(x,y,GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    private static int count(Minecraft mc) {
        if(mc.player==null) return -1;
        int n=0; var inventory=mc.player.getInventory();
        for(int i=0;i<inventory.getContainerSize();i++) if(inventory.getItem(i).is(Items.COBBLESTONE)) n+=inventory.getItem(i).getCount();
        return n;
    }
    private static void report(Target target) {
        if(verified) return; verified=true;
        String extra="";
        if("container".equals(target.kind())) {
            int moved=count(Minecraft.getInstance())-baseline;
            extra=" cobblestone_after_close="+moved;
            if(failure==null && moved!=16) failure="inventory_not_returned";
        }
        System.out.println("MMLF GUI SELFTEST name="+target.name()+" requested="+requested+" opened="+(opened>=0)+" closed="+(closedAt>=0)+extra
            +" reason="+(failure==null?"none":failure)+" passed="+(failure==null && opened>=0 && closedAt>=0));
    }
}
