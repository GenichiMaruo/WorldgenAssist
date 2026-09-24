package io.github.genichimaruo.worldgenassist.forge;

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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod(WorldgenAssist.MOD_ID)
public final class ForgeWorldgenAssist implements WorldgenLoaderHooks {
    private RemoteWorldgenManager manager;
    public ForgeWorldgenAssist() {
        WorldgenPlatform.install(FMLPaths.CONFIGDIR.get(), "0.1.0-alpha.4+mc26.3", !FMLEnvironment.production);
        WorldgenAssist.initialize(this);
        if (FMLEnvironment.dist == Dist.CLIENT) ForgeClientInit.register();
    }
    RemoteWorldgenManager manager() { return manager; }
    @Override public void registerPayloads() { ForgeNetwork.register(this); }
    @Override public void registerSettings() {
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(event -> {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                ServerSettingsMenu.onDisconnect(player.getUUID());
                ForgeResultAssembler.removeOwner(player.getUUID());
            }
        });
        ServerStoppedEvent.BUS.addListener(event -> {
            ServerSettingsMenu.onServerStopped();
            ForgeResultAssembler.clear();
        });
    }
    @Override public void registerRemote(RemoteWorldgenConfig config) {
        RemoteWorldgenManager.register(config, new ForgeRemoteJobSender(), manager -> {
            this.manager = manager;
            ServerStartingEvent.BUS.addListener(event -> manager.onServerStarting(event.getServer()));
            ServerStoppingEvent.BUS.addListener(event -> manager.onServerStopping());
            ServerStoppedEvent.BUS.addListener(event -> manager.onServerStopped());
            TickEvent.ServerTickEvent.Pre.BUS.addListener(event -> manager.onStartServerTick(event.server()));
            TickEvent.ServerTickEvent.Post.BUS.addListener(event -> {
                manager.onEndServerTick(event.server());
                ForgeResultAssembler.prune();
            });
            PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(event -> {
                if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)
                    player.level().getServer().execute(() -> manager.onDisconnect(player.level().getServer(), player));
            });
            PlayerEvent.PlayerChangedDimensionEvent.BUS.addListener(event -> {
                if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)
                    manager.onDimensionChanged(player, event.getFrom(), event.getTo());
            });
        });
    }
    @Override public void registerLocalBackend(LocalWorldgenTaskBackend backend) {
        ServerStartingEvent.BUS.addListener(event -> backend.start());
        ServerStoppedEvent.BUS.addListener(event -> backend.close());
    }
    @Override public void registerTickBenchmark(NoiseStageBackendConfig config) {
        ServerTickBenchmarkLogger benchmark = ServerTickBenchmarkLogger.create(config);
        ServerStartingEvent.BUS.addListener(event -> benchmark.reset());
        TickEvent.ServerTickEvent.Pre.BUS.addListener(event -> benchmark.onStartTick());
        TickEvent.ServerTickEvent.Post.BUS.addListener(event -> benchmark.onEndTick());
    }
    @Override public void registerFingerprintLogger() {
        ServerStartedEvent.BUS.addListener(event -> WorldgenContextFingerprintLogger.log(event.getServer()));
    }
}
