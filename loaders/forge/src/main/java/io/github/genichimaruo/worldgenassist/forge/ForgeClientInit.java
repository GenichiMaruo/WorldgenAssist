package io.github.genichimaruo.worldgenassist.forge;

import io.github.genichimaruo.worldgenassist.client.ClientWorkerTransport;
import io.github.genichimaruo.worldgenassist.client.ClientWorldgenWorker;
import io.github.genichimaruo.worldgenassist.client.WorldgenSettingsScreen;
import io.github.genichimaruo.worldgenassist.network.SettingsPayload;
import io.github.genichimaruo.worldgenassist.network.ForgeResultFragmentPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobResultPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobCancelPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobRequestPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerAcceptedPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraftforge.client.event.ScreenEvent;

public final class ForgeClientInit {
    private static ClientWorldgenWorker worker;
    private ForgeClientInit() {}

    static void register() {
        WorldgenSettingsScreen.installTransport(ForgeClientInit::canSend, ForgeClientInit::send);
        ScreenEvent.Init.Post.BUS.addListener(event -> {
            if (event.getScreen() instanceof OptionsScreen)
                event.addListener(WorldgenSettingsScreen.optionsButton(event.getScreen()));
        });
    }

    public static void onLogin() { worker().onJoin(); }
    public static void onDisconnect() { if (worker != null) worker.onDisconnect(); }

    static void onSettings(SettingsPayload payload) {
        WorldgenSettingsScreen.receiveCurrent(Minecraft.getInstance(), payload);
    }
    static void onAccepted(WorkerAcceptedPayload payload) { worker().onAccepted(payload); }
    static void onJob(TerrainJobRequestPayload payload) { worker().handleRequest(Minecraft.getInstance(), payload.job()); }
    static void onCancel(TerrainJobCancelPayload payload) { worker().cancel(payload); }

    private static boolean canSend() {
        var listener = Minecraft.getInstance().getConnection();
        return listener != null && ForgeNetwork.canSend(listener.getConnection());
    }
    private static void send(CustomPacketPayload payload) {
        var listener = Minecraft.getInstance().getConnection();
        if (listener == null) return;
        if (payload instanceof TerrainJobResultPayload result) {
            for (ForgeResultFragmentPayload fragment : ForgeResultFragmentPayload.split(result.result()))
                ForgeNetwork.send(fragment, listener.getConnection());
        } else {
            ForgeNetwork.send(payload, listener.getConnection());
        }
    }
    private static ClientWorldgenWorker worker() {
        if (worker == null) {
            worker = new ClientWorldgenWorker(new ClientWorkerTransport() {
                @Override public boolean canSendHello() { return canSend(); }
                @Override public void send(CustomPacketPayload payload) { ForgeClientInit.send(payload); }
            });
        }
        return worker;
    }
}
