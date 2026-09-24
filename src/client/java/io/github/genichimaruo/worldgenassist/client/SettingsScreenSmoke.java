package io.github.genichimaruo.worldgenassist.client;

import io.github.genichimaruo.worldgenassist.WorldgenPlatform;
import io.github.genichimaruo.worldgenassist.server.RemoteWorldgenConfig;
import io.github.genichimaruo.worldgenassist.server.ServerSettingsStore;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Explicit development-only smoke fixture, restricted to the isolated Gradle fixture directory. */
public final class SettingsScreenSmoke {
	private int step;
	private int ticks;
	private final long deadline = System.nanoTime() + 120_000_000_000L;
	private SettingsScreenSmoke() {}
	public static SettingsScreenSmoke createIfEnabled() {
		return WorldgenPlatform.isDevelopment() && Boolean.getBoolean("worldgen_assist.settings_smoke")
			? new SettingsScreenSmoke() : null;
	}
	public void tick(Minecraft client) {
		Path directory = client.gameDirectory.toPath().toAbsolutePath().normalize();
		Path evidenceRoot = Path.of(System.getProperty("worldgen_assist.settings_smoke_root", "")).toAbsolutePath().normalize();
		if (!directory.getFileName().toString().equals("settings-client")
			|| !evidenceRoot.getFileName().toString().equals("test-artifacts")
			|| !directory.startsWith(evidenceRoot)) {
			throw new IllegalStateException("Settings smoke requires an isolated test-artifacts fixture directory");
		}
		if (++ticks % 20 != 0) return;
		try {
			Screen screen = client.gui.screen();
			if (System.nanoTime() > deadline) {
				throw new IllegalStateException("Settings smoke deadline exceeded at screen="
					+ (screen == null ? "null" : screen.getClass().getName())
					+ " overlay=" + (client.gui.overlay() == null ? "null" : client.gui.overlay().getClass().getName()));
			}
			if (step == 0) {
				if (screen instanceof AccessibilityOnboardingScreen onboarding) {
					onboarding.onClose();
					return;
				}
				if (!(screen instanceof TitleScreen)) return;
				client.gui.setScreen(new OptionsScreen(screen, client.options));
			} else if (step == 1) {
				press(screen, Component.literal("WorldgenAssist"));
			} else if (step == 2) {
				if (!(screen instanceof WorldgenSettingsScreen)) throw new IllegalStateException("Settings entry did not open screen");
				capture(client, "settings-client.png");
				press(screen, label("participation", true));
			} else if (step == 3) {
				press(screen, label("save_client"));
				if (ClientSettings.participation()) throw new IllegalStateException("Client participation was not saved");
			} else if (step == 4) {
				press(screen, label("server"));
			} else if (step == 5) {
				capture(client, "settings-server-1.png");
				press(screen, label("field0", false));
				press(screen, label("save_server"));
				RemoteWorldgenConfig saved = ServerSettingsStore.load();
				if (!saved.enabled() || saved.remoteExecutionEnabled()) throw new IllegalStateException("Separate disclosure gate failed");
			} else if (step == 6) {
				press(screen, label("page", 1, 3));
			} else if (step == 7) {
				capture(client, "settings-server-2.png");
				press(screen, label("page", 2, 3));
			} else if (step == 8) {
				capture(client, "settings-server-3.png");
			} else if (step == 9) {
				for (String name : new String[]{"settings-client.png", "settings-server-1.png", "settings-server-2.png", "settings-server-3.png"}) {
					if (!Files.isRegularFile(directory.resolve("screenshots").resolve(name))) throw new IllegalStateException("Screenshot missing: " + name);
				}
				ServerSettingsStore.save(RemoteWorldgenConfig.defaults()); ClientSettings.save(true);
				Files.writeString(directory.resolve("settings-smoke-result.json"), "{\"success\":true,\"screenshots\":4,\"persistence\":true,\"separate_disclosure_gate\":true}");
				client.stop();
			}
			step++;
		} catch (Exception exception) {
			io.github.genichimaruo.worldgenassist.WorldgenAssist.LOGGER.error("[CAWG] settings.smoke_failed step={}", step, exception);
			try { Files.writeString(directory.resolve("settings-smoke-result.json"), "{\"success\":false,\"step\":" + step + "}"); }
			catch (Exception ignored) { }
			client.stop();
		}
	}
	private static Component label(String key, Object... args) { return Component.translatable("worldgen_assist.settings." + key, args); }
	private static void press(Screen screen, Component label) {
		Button button = screen.children().stream().filter(w -> w instanceof Button && ((Button) w).getMessage().getString().equals(label.getString()))
			.map(w -> (Button)w).findFirst().orElseThrow(() -> new IllegalStateException("Missing button: " + label.getString()));
		if (!button.active) throw new IllegalStateException("Button is disabled: " + label.getString());
		button.onPress(new KeyEvent(257, 0, 0));
	}
	private static void capture(Minecraft client, String name) {
		Screenshot.grab(client.gameDirectory, name, client.gameRenderer.mainRenderTarget(), 1, message -> {});
	}
}
