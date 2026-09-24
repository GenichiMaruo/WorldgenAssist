package io.github.genichimaruo.worldgenassist.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screens.options.OptionsScreen;

import io.github.genichimaruo.worldgenassist.network.SettingsPayload;

public final class FabricSettingsScreenEvents {
	private FabricSettingsScreenEvents() {}

	public static void register() {
		WorldgenSettingsScreen.installTransport(() -> ClientPlayNetworking.canSend(SettingsPayload.TYPE),
			ClientPlayNetworking::send);
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof OptionsScreen) Screens.getWidgets(screen).add(WorldgenSettingsScreen.optionsButton(screen));
		});
		ClientPlayNetworking.registerGlobalReceiver(SettingsPayload.TYPE,
			(payload, context) -> WorldgenSettingsScreen.receiveCurrent(context.client(), payload));
	}
}
