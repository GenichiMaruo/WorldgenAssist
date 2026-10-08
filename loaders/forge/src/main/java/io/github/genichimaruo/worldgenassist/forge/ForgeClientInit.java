package io.github.genichimaruo.worldgenassist.forge;

import io.github.genichimaruo.worldgenassist.client.ClientWorkerTransport;
import io.github.genichimaruo.worldgenassist.client.ClientWorldgenWorker;
import io.github.genichimaruo.worldgenassist.client.NativeClientRequestIngress;
import io.github.genichimaruo.worldgenassist.client.WorldgenSettingsScreen;
import io.github.genichimaruo.worldgenassist.client.SettingsScreenSmoke;
import io.github.genichimaruo.worldgenassist.network.SettingsPayload;
import io.github.genichimaruo.worldgenassist.network.ForgeResultFragmentPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobResultPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobCancelPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobRequestPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobBatchPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerAcceptedPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;

public final class ForgeClientInit {
    private static ClientWorldgenWorker worker;
    private ForgeClientInit() {}

    static void register() {
        io.github.genichimaruo.worldgenassist.WorldgenAssist.LOGGER.info(
            "[CAWG] native.client_request_ingress enabled={}", NativeClientRequestIngress.enabled());
        WorldgenSettingsScreen.installTransport(ForgeClientInit::canSend, ForgeClientInit::send);
        SettingsScreenSmoke smoke = SettingsScreenSmoke.createIfEnabled();
        if (smoke != null) TickEvent.ClientTickEvent.Post.BUS.addListener(event -> smoke.tick(Minecraft.getInstance()));
        ScreenEvent.Init.Post.BUS.addListener(event -> {
            if (event.getScreen() instanceof OptionsScreen)
                event.addListener(WorldgenSettingsScreen.optionsButton(event.getScreen()));
        });
    }

    public static void onLogin() { NativeClientRequestIngress.clear(); worker().onJoin(); }
    public static void onDisconnect() { NativeClientRequestIngress.clear(); if (worker != null) worker.onDisconnect(); }

    static void onSettings(SettingsPayload payload) {
        WorldgenSettingsScreen.receiveCurrent(Minecraft.getInstance(), payload);
    }
    static void onAccepted(WorkerAcceptedPayload payload, Connection connection) {
        var client = Minecraft.getInstance();
        if (!NativeClientRequestIngress.isCurrent(client, connection)) return;
        var currentWorker = worker();
        currentWorker.onAccepted(payload);
        if (payload.accepted()) NativeClientRequestIngress.prepare(client, currentWorker);
        else NativeClientRequestIngress.remove(connection);
    }
    /** Native fallback runs on MAIN and keeps the original receive connection. */
    static void onRequestPayload(CustomPacketPayload payload, Connection connection) {
        var client = Minecraft.getInstance();
        if (!NativeClientRequestIngress.isCurrent(client, connection)) return;
        var currentWorker = worker();
        if (payload instanceof TerrainJobRequestPayload request) {
            NativeClientRequestIngress.prepare(client, currentWorker);
            currentWorker.handleRequest(client, request.job());
        } else if (payload instanceof TerrainJobBatchPayload batch) {
            NativeClientRequestIngress.prepare(client, currentWorker);
            for (var job : batch.jobs()) currentWorker.handleRequest(client, job);
        } else if (payload instanceof TerrainJobCancelPayload cancel) currentWorker.cancel(cancel);
    }

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
                @Override public java.util.function.Consumer<CustomPacketPayload> captureSender() {
                    var connection = Minecraft.getInstance().getConnection().getConnection();
                    return payload -> {
                        if (!connection.isConnected()) return;
                        if (payload instanceof TerrainJobResultPayload result) {
                            for (var fragment : ForgeResultFragmentPayload.split(result.result())) ForgeNetwork.send(fragment, connection);
                        } else ForgeNetwork.send(payload, connection);
                    };
                }
            });
        }
        return worker;
    }
}
