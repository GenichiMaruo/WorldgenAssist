package io.github.genichimaruo.worldgenassist.neoforge;

import io.github.genichimaruo.worldgenassist.client.ClientWorkerTransport;
import io.github.genichimaruo.worldgenassist.client.ClientWorldgenWorker;
import io.github.genichimaruo.worldgenassist.client.WorldgenSettingsScreen;
import io.github.genichimaruo.worldgenassist.client.SettingsScreenSmoke;
import io.github.genichimaruo.worldgenassist.network.SettingsPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobCancelPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobRequestPayload;
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

final class NeoClientInit {
    private static ClientWorldgenWorker worker;
    private NeoClientInit() {}

    static void register(IEventBus modBus) {
        modBus.addListener(NeoClientInit::registerNetwork);
        WorldgenSettingsScreen.installTransport(() -> canSend(SettingsPayload.TYPE.id()), ClientPacketDistributor::sendToServer);
        SettingsScreenSmoke smoke = SettingsScreenSmoke.createIfEnabled();
        if (smoke != null) NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> smoke.tick(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post event) -> {
            if (event.getScreen() instanceof OptionsScreen)
                event.addListener(WorldgenSettingsScreen.optionsButton(event.getScreen()));
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> worker().onJoin());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> worker().onDisconnect());
    }

    private static void registerNetwork(RegisterClientPayloadHandlersEvent event) {
        event.register(SettingsPayload.TYPE,
            (payload, context) -> WorldgenSettingsScreen.receiveCurrent(Minecraft.getInstance(), payload));
        event.register(WorkerAcceptedPayload.TYPE, (payload, context) -> worker().onAccepted(payload));
        event.register(TerrainJobRequestPayload.TYPE,
            (payload, context) -> worker().handleRequest(Minecraft.getInstance(), payload.job()));
        event.register(TerrainJobCancelPayload.TYPE, (payload, context) -> worker().cancel(payload));
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
            });
        }
        return worker;
    }
}
