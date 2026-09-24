package io.github.genichimaruo.worldgenassist.forge;

import io.github.genichimaruo.worldgenassist.network.SettingsPayload;
import io.github.genichimaruo.worldgenassist.network.ForgeResultFragmentPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobCancelPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobFailurePayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobRequestPayload;
import io.github.genichimaruo.worldgenassist.network.TerrainJobResultPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerAcceptedPayload;
import io.github.genichimaruo.worldgenassist.network.WorkerHelloPayload;
import io.github.genichimaruo.worldgenassist.server.ServerSettingsMenu;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.event.network.CustomPayloadEvent;

final class ForgeNetwork {
    private static Channel<CustomPacketPayload> channel;
    private ForgeNetwork() {}

    static void register(ForgeWorldgenAssist owner) {
        if (channel != null) throw new IllegalStateException("Forge channel already registered");
        channel = ChannelBuilder.named("worldgen_assist:play")
            .networkProtocolVersion(3).optional().payloadChannel().play().flow(PacketFlow.SERVERBOUND)
            .addMain(WorkerHelloPayload.TYPE, WorkerHelloPayload.CODEC, (payload, context) -> {
                ServerPlayer player = context.getSender();
                if (player != null) channel.reply(owner.manager().handleHello(player.getUUID(), payload), context);
            })
            .addMain(TerrainJobResultPayload.TYPE, TerrainJobResultPayload.CODEC, (payload, context) -> {
                ServerPlayer player = context.getSender();
                if (player != null) owner.manager().handleResult(player.getUUID(), payload);
            })
            .addMain(ForgeResultFragmentPayload.TYPE, ForgeResultFragmentPayload.CODEC, (payload, context) -> {
                ServerPlayer player = context.getSender();
                if (player != null && owner.manager().acceptsFragment(player.getUUID(), payload.identity())) {
                    var result = ForgeResultAssembler.accept(player.getUUID(), payload);
                    if (result != null) owner.manager().handleResult(player.getUUID(), new TerrainJobResultPayload(result));
                }
            })
            .addMain(TerrainJobFailurePayload.TYPE, TerrainJobFailurePayload.CODEC, (payload, context) -> {
                ServerPlayer player = context.getSender();
                if (player != null) owner.manager().handleFailure(player.getUUID(), payload);
            })
            .flow((PacketFlow) null)
            .addMain(SettingsPayload.TYPE, SettingsPayload.CODEC, (payload, context) -> {
                if (context.isClientSide()) {
                    ForgeClientInit.onSettings(payload);
                } else {
                    ServerPlayer player = context.getSender();
                    if (player != null) ServerSettingsMenu.handleRequest(player.level().getServer(), player, payload,
                        response -> send(response, context.getConnection()));
                }
            })
            .flow(PacketFlow.CLIENTBOUND)
            .addMain(WorkerAcceptedPayload.TYPE, WorkerAcceptedPayload.CODEC,
                (payload, context) -> ForgeClientInit.onAccepted(payload))
            .addMain(TerrainJobRequestPayload.TYPE, TerrainJobRequestPayload.CODEC,
                (payload, context) -> ForgeClientInit.onJob(payload))
            .addMain(TerrainJobCancelPayload.TYPE, TerrainJobCancelPayload.CODEC,
                (payload, context) -> ForgeClientInit.onCancel(payload))
            .build();
    }

    static boolean canSend(Connection connection) { return channel != null && channel.isRemotePresent(connection); }
    static void send(CustomPacketPayload payload, Connection connection) { channel.send(payload, connection); }
}
