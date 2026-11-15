package dev.mmlf.bridge.scene;

import dev.mmlf.bridge.collab.SharedPlayer;
import dev.mmlf.bridge.transport.SessionLink;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;

import java.nio.file.Files;

/** Opt-in demo regression test for state changes made by queued server work between ticks. */
public final class SharedPlayerSelfTest {
    private static final boolean ENABLED = "true".equals(System.getenv("MMLF_SHARED_PLAYER_TEST"));
    private static final int TIMEOUT = 200;
    private static final int SHORTENED_DURATION = 1140;
    private static final int EXTENDED_DURATION = 1190;
    private static int readyTicks;
    private static int startedAt;
    private static int stableTicks;
    private static boolean queued;
    private static boolean mirrored;
    private static volatile boolean primaryPassed;
    private static boolean serverDone;
    private static int clientTicks;
    private static boolean clientDone;

    private SharedPlayerSelfTest() {}

    static boolean enabled() { return ENABLED; }

    public static void serverTick(MinecraftServer server) {
        if (!ENABLED || serverDone || !SessionLink.connected()) return;
        var session = SessionLink.session();
        var players = server.getPlayerList().getPlayers();
        if (session == null || !DemoScene.settled(session) || !Files.isRegularFile(session.root().resolve("self-test")) || players.isEmpty()) return;
        ServerPlayer player = players.getFirst();
        if (SessionLink.isPrimary()) primaryTick(server, player);
        else backgroundTick(server, player);
    }

    private static void primaryTick(MinecraftServer server, ServerPlayer player) {
        if (startedAt == 0) {
            if (!SessionLink.peers().anyWorldReady() || ++readyTicks < 80) return;
            server.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(false, server);
            server.setDifficulty(Difficulty.NORMAL, true);
            player.setGameMode(GameType.SURVIVAL);
            player.setHealth(16);
            player.getFoodData().setFoodLevel(12);
            player.getFoodData().setSaturation(2);
            player.getFoodData().setExhaustion(0);
            player.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, 1200));
            player.addEffect(new MobEffectInstance(MobEffects.LUCK, 1200));
            startedAt = server.getTickCount();
            return;
        }
        int luckDuration = luckDuration(player);
        // Let subsequent snapshots settle and fail if the writeback repeats or bounces between runtimes.
        boolean expected = player.getHealth() == 13 && player.getFoodData().getFoodLevel() == 15
            && player.getFoodData().getSaturationLevel() == 5 && player.hasEffect(MobEffects.WATER_BREATHING)
            && !player.hasEffect(MobEffects.DIG_SPEED) && luckDuration > SHORTENED_DURATION
            && luckDuration <= EXTENDED_DURATION;
        stableTicks = expected ? stableTicks + 1 : 0;
        primaryPassed = stableTicks >= 20;
        if (!primaryPassed && server.getTickCount() - startedAt < TIMEOUT) return;
        System.out.println("MMLF SHARED PLAYER SELFTEST phase=writeback health=" + player.getHealth()
            + " food=" + player.getFoodData().getFoodLevel() + " saturation=" + player.getFoodData().getSaturationLevel()
            + " added=" + player.hasEffect(MobEffects.WATER_BREATHING) + " removed=" + !player.hasEffect(MobEffects.DIG_SPEED)
            + " luckDuration=" + luckDuration + " stableTicks=" + stableTicks + " passed=" + primaryPassed);
        serverDone = true;
    }

    private static void backgroundTick(MinecraftServer server, ServerPlayer player) {
        if (queued || !SharedPlayer.shared(player) || player.getAbilities().invulnerable || player.getHealth() != 16
            || player.getFoodData().getFoodLevel() != 12 || player.getFoodData().getSaturationLevel() != 2
            || !player.hasEffect(MobEffects.DIG_SPEED)) return;
        if (!mirrored) System.out.println("MMLF SHARED PLAYER SELFTEST phase=mirror health=16.0 food=12 saturation=2.0 effect=true passed=true");
        mirrored = true;
        if (luckDuration(player) > SHORTENED_DURATION) return;
        queued = true;
        int tick = server.getTickCount();
        server.tell(new TickTask(tick, () -> {
            // Use the same server task queue as play packets. Retry if a busy server could not drain it before the next tick.
            if (server.getTickCount() != tick) { queued = false; return; }
            server.setDifficulty(Difficulty.NORMAL, true);
            player.getFoodData().eat(3, 0.5f);
            boolean added = player.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, 1200));
            boolean removed = player.removeEffect(MobEffects.DIG_SPEED);
            int oldDuration = luckDuration(player);
            boolean extended = oldDuration > 0 && player.addEffect(new MobEffectInstance(MobEffects.LUCK, EXTENDED_DURATION));
            boolean hurt = player.hurt(player.damageSources().magic(), 3);
            boolean passed = player.getFoodData().getFoodLevel() == 15 && added && removed && extended && !hurt && player.getHealth() == 16;
            System.out.println("MMLF SHARED PLAYER SELFTEST phase=queued_mutation tick=" + tick + " added=" + added
                + " removed=" + removed + " extended=" + extended + " oldLuckDuration=" + oldDuration
                + " localDamage=" + hurt + " passed=" + passed);
            serverDone = true;
        }));
    }

    private static int luckDuration(ServerPlayer player) {
        var effect = player.getEffect(MobEffects.LUCK);
        return effect == null ? 0 : effect.getDuration();
    }

    static void clientTick(Minecraft mc) {
        if (mc.options.tutorialStep != TutorialSteps.NONE) mc.getTutorial().setStep(TutorialSteps.NONE);
        if (!primaryPassed || clientDone) return;
        var player = mc.player;
        boolean passed = player.getHealth() == 13 && player.getFoodData().getFoodLevel() == 15
            && player.hasEffect(MobEffects.WATER_BREATHING) && !player.hasEffect(MobEffects.DIG_SPEED);
        if (!passed && ++clientTicks < TIMEOUT) return;
        System.out.println("MMLF SHARED PLAYER SELFTEST phase=client health=" + player.getHealth()
            + " food=" + player.getFoodData().getFoodLevel() + " added=" + player.hasEffect(MobEffects.WATER_BREATHING)
            + " removed=" + !player.hasEffect(MobEffects.DIG_SPEED) + " passed=" + passed);
        if (passed) Screenshot.grab(mc.gameDirectory, "mmlf-shared-player.png", mc.getMainRenderTarget(), message -> {
            System.out.println("MMLF screenshot " + message.getString());
            mc.execute(mc::stop);
        });
        clientDone = true;
    }
}
