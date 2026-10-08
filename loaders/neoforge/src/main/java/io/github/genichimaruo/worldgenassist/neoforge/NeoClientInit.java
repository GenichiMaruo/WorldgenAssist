package io.github.genichimaruo.worldgenassist.neoforge;

import io.github.genichimaruo.worldgenassist.client.ClientWorkerTransport;
import io.github.genichimaruo.worldgenassist.client.ClientWorldgenWorker;
import io.github.genichimaruo.worldgenassist.client.NativeClientRequestIngress;
import io.github.genichimaruo.worldgenassist.client.WorldgenSettingsScreen;
import io.github.genichimaruo.worldgenassist.client.SettingsScreenSmoke;
import io.github.genichimaruo.worldgenassist.network.SettingsPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobCancelPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobRequestPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobBatchPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerAcceptedPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerHelloPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.handling.IPayloadContext;

final class NeoClientInit {
    private static ClientWorldgenWorker worker;
    private NeoClientInit() {}

    static void register(IEventBus modBus) {
        io.github.genichimaruo.worldgenassist.WorldgenAssist.LOGGER.info(
            "[CAWG] native.client_request_ingress enabled={}", NativeClientRequestIngress.enabled());
        modBus.addListener(NeoClientInit::registerNetwork);
        WorldgenSettingsScreen.installTransport(() -> canSend(SettingsPayload.TYPE.id()), ClientPacketDistributor::sendToServer);
        SettingsScreenSmoke smoke = SettingsScreenSmoke.createIfEnabled();
        if (smoke != null) NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> smoke.tick(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post event) -> {
            if (event.getScreen() instanceof OptionsScreen)
                event.addListener(WorldgenSettingsScreen.optionsButton(event.getScreen()));
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> {
            NativeClientRequestIngress.clear(); worker().onJoin();
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            NativeClientRequestIngress.clear(); worker().onDisconnect();
        });
    }

    private static void registerNetwork(RegisterClientPayloadHandlersEvent event) {
        event.register(SettingsPayload.TYPE,
            (payload, context) -> WorldgenSettingsScreen.receiveCurrent(Minecraft.getInstance(), payload));
        event.register(WorkerAcceptedPayload.TYPE, (payload, context) -> {
            var client = Minecraft.getInstance();
            var connection = context.connection();
            if (!NativeClientRequestIngress.isCurrent(client, connection)) return;
            var currentWorker = worker();
            currentWorker.onAccepted(payload);
            if (payload.accepted()) NativeClientRequestIngress.prepare(client, currentWorker);
            else NativeClientRequestIngress.remove(connection);
        });
        event.register(TerrainJobRequestPayload.TYPE, HandlerThread.NETWORK, NeoClientInit::receiveRequest);
        event.register(TerrainJobBatchPayload.TYPE, HandlerThread.NETWORK, NeoClientInit::receiveRequest);
        event.register(TerrainJobCancelPayload.TYPE, HandlerThread.NETWORK, NeoClientInit::receiveRequest);
    }

    private static void receiveRequest(CustomPacketPayload payload, IPayloadContext context) {
        var connection = context.connection();
        long receivedNanos = System.nanoTime();
        if (NativeClientRequestIngress.receive(connection, payload, receivedNanos)) return;
        context.enqueueWork(() -> {
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
        });
    }

    private static boolean canSend(net.minecraft.resources.Identifier id) {
        var listener = Minecraft.getInstance().getConnection();
        return listener != null && NetworkRegistry.hasChannel(listener, id);
    }

    private static ClientWorldgenWorker worker() {
        if (worker == null) {
            worker = new ClientWorldgenWorker(new ClientWorkerTransport() {
                @Override public boolean canSendHello() { return canSend(WorkerHelloPayload.TYPE.id()); }
                @Override public void send(CustomPacketPayload payload) { ClientPacketDistributor.sendToServer(payload); }
                @Override public java.util.function.Consumer<CustomPacketPayload> captureSender() {
                    var connection = Minecraft.getInstance().getConnection().getConnection();
                    return payload -> {
                        if (connection.isConnected()) connection.send(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(payload));
                    };
                }
            });
        }
        return worker;
    }
}
