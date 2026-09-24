package io.github.genichimaruo.worldgenassist.neoforge;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import io.github.genichimaruo.worldgenassist.WorldgenLoaderHooks;
import io.github.genichimaruo.worldgenassist.WorldgenPlatform;
import io.github.genichimaruo.worldgenassist.server.LocalWorldgenTaskBackend;
import io.github.genichimaruo.worldgenassist.server.NoiseStageBackendConfig;
import io.github.genichimaruo.worldgenassist.server.RemoteWorldgenConfig;
import io.github.genichimaruo.worldgenassist.server.RemoteWorldgenManager;
import io.github.genichimaruo.worldgenassist.server.ServerSettingsMenu;
import io.github.genichimaruo.worldgenassist.server.ServerTickBenchmarkLogger;
import io.github.genichimaruo.worldgenassist.server.WorldgenContextFingerprintLogger;
import io.github.genichimaruo.worldgenassist.network.SettingsPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobCancelPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobFailurePayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobRequestPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobResultPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerAcceptedPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerHelloPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(WorldgenAssist.MOD_ID)
public final class NeoWorldgenAssist implements WorldgenLoaderHooks {
    private final IEventBus modBus;

    public NeoWorldgenAssist(IEventBus modBus) {
        this.modBus = modBus;
        WorldgenPlatform.install(FMLPaths.CONFIGDIR.get(), "0.1.0-alpha.4+mc26.3", !FMLEnvironment.isProduction());
        WorldgenAssist.initialize(this);
        if (FMLEnvironment.getDist() == Dist.CLIENT) NeoClientInit.register(modBus);
    }

    @Override public void registerPayloads() {
        modBus.addListener(this::registerNetwork);
    }
    private void registerNetwork(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("3");
        registrar.playToServer(WorkerHelloPayload.TYPE, WorkerHelloPayload.CODEC,
            (payload, context) -> context.reply(remote().handleHello(((ServerPlayer) context.player()).getUUID(), payload)));
        registrar.playToServer(TerrainJobResultPayload.TYPE, TerrainJobResultPayload.CODEC,
            (payload, context) -> remote().handleResult(((ServerPlayer) context.player()).getUUID(), payload));
        registrar.playToServer(TerrainJobFailurePayload.TYPE, TerrainJobFailurePayload.CODEC,
            (payload, context) -> remote().handleFailure(((ServerPlayer) context.player()).getUUID(), payload));
        registrar.playBidirectional(SettingsPayload.TYPE, SettingsPayload.CODEC,
            (payload, context) -> {
                ServerPlayer player = (ServerPlayer) context.player();
                ServerSettingsMenu.handleRequest(player.level().getServer(), player, payload,
                    response -> PacketDistributor.sendToPlayer(player, response));
            });
        registrar.playToClient(WorkerAcceptedPayload.TYPE, WorkerAcceptedPayload.CODEC);
        registrar.playToClient(TerrainJobRequestPayload.TYPE, TerrainJobRequestPayload.CODEC);
        registrar.playToClient(TerrainJobCancelPayload.TYPE, TerrainJobCancelPayload.CODEC);
    }
    private RemoteWorldgenManager remote() {
        return manager;
    }
    private RemoteWorldgenManager manager;

    @Override public void registerSettings() {
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) ServerSettingsMenu.onDisconnect(player.getUUID());
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> ServerSettingsMenu.onServerStopped());
    }
    @Override public void registerRemote(RemoteWorldgenConfig config) {
        RemoteWorldgenManager.register(config, new NeoRemoteJobSender(), manager -> {
            this.manager = manager;
            NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) -> manager.onServerStarting(event.getServer()));
            NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> manager.onServerStopping());
            NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> manager.onServerStopped());
            NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre event) -> manager.onStartServerTick(event.getServer()));
            NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> manager.onEndServerTick(event.getServer()));
            NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
                if (event.getEntity() instanceof ServerPlayer player)
                    player.level().getServer().execute(() -> manager.onDisconnect(player.level().getServer(), player));
            });
            NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> {
                if (event.getEntity() instanceof ServerPlayer player)
                    manager.onDimensionChanged(player, event.getFrom(), event.getTo());
            });
        });
    }
    @Override public void registerLocalBackend(LocalWorldgenTaskBackend backend) {
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) -> backend.start());
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> backend.close());
    }
    @Override public void registerTickBenchmark(NoiseStageBackendConfig config) {
        ServerTickBenchmarkLogger benchmark = ServerTickBenchmarkLogger.create(config);
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) -> benchmark.reset());
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre event) -> benchmark.onStartTick());
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> benchmark.onEndTick());
    }
    @Override public void registerFingerprintLogger() {
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> WorldgenContextFingerprintLogger.log(event.getServer()));
    }
}
