package io.github.genichimaruo.worldgenassist.client;

import io.github.genichimaruo.worldgenassist.network.SettingsPayload;
import io.github.genichimaruo.worldgenassist.server.RemoteWorldgenConfig;
import io.github.genichimaruo.worldgenassist.server.ServerSettingsStore;
import java.io.IOException;
import java.time.Duration;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;

/** Vanilla/Fabric screen API only; no game GUI mixins. */
public final class WorldgenSettingsScreen extends Screen {
	private final Screen parent;
	private final SettingsRequestTracker requests = new SettingsRequestTracker();
	private boolean participation = ClientSettings.participation();
	private RemoteWorldgenConfig policy;
	private int revision;
	private int page;
	private boolean waiting;
	private String status = "hint";
	private long requestTime;
	private WorldgenSettingsScreen(Screen parent) { super(text("title")); this.parent = parent; }
	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof OptionsScreen) {
				Screens.getWidgets(screen).add(Button.builder(Component.literal("WorldgenAssist"), b -> client.gui.setScreen(new WorldgenSettingsScreen(screen)))
					.bounds(4, 4, 110, 20).build());
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(SettingsPayload.TYPE, (payload, context) -> context.client().execute(() -> {
			if (context.client().gui.screen() instanceof WorldgenSettingsScreen screen) screen.receive(payload);
		}));
	}
	private static Component text(String key, Object... args) { return Component.translatable("worldgen_assist.settings." + key, args); }
	@Override protected void init() {
		int x = width / 2 - 150;
		addRenderableWidget(new StringWidget(width / 2 - font.width(title) / 2, 20, font.width(title), 18, title, font));
		if (policy == null) {
			button(x, 60, text("participation", participation), () -> { participation = !participation; rebuildWidgets(); });
			button(x, 84, text("save_client"), () -> {
				try { ClientSettings.save(participation); status = "client_saved"; }
				catch (IOException e) { status = "io_error"; }
				rebuildWidgets();
			});
			Button server = button(x, 116, text("server"), this::openServer);
			server.active = !waiting && (minecraft.getConnection() == null || ClientPlayNetworking.canSend(SettingsPayload.TYPE));
		} else {
			int start = page * 4;
			for (int i = start; i < Math.min(start + 4, 9); i++) {
				final int field = i;
				Button option = button(x, 44 + (i - start) * 23, fieldLabel(i), () -> { change(field); rebuildWidgets(); });
				option.active = !waiting;
				if (i == 1) option.setTooltip(Tooltip.create(text("disclosure_warning")));
			}
			button(x, 137, text("page", page + 1, 3), () -> { page = (page + 1) % 3; rebuildWidgets(); });
			button(x, 160, text("save_server"), this::saveServer).active = !waiting;
		}
		Button info = button(x, height - 54, text(status), () -> {});
		info.setTooltip(Tooltip.create(text(policy == null ? "client_hint" : "server_hint")));
		button(x, height - 28, text("back"), this::onClose);
	}
	private Button button(int x, int y, Component label, Runnable action) {
		return addRenderableWidget(Button.builder(label, b -> action.run()).bounds(x, y, 300, 20).build());
	}
	private void openServer() {
		if (minecraft.getConnection() == null) { policy = ServerSettingsStore.load(); status = "server_loaded"; rebuildWidgets(); }
		else request(SettingsPayload.READ, RemoteWorldgenConfig.defaults());
	}
	private void request(int action, RemoteWorldgenConfig config) {
		long requestId = requests.begin();
		waiting = true; requestTime = System.nanoTime(); status = "waiting";
		ClientPlayNetworking.send(new SettingsPayload(action, revision, requestId, config)); rebuildWidgets();
	}
	private void saveServer() {
		if (minecraft.getConnection() != null) { request(SettingsPayload.SAVE, policy); return; }
		try { ServerSettingsStore.save(policy); status = "server_saved"; }
		catch (IOException e) { status = "io_error"; }
		rebuildWidgets();
	}
	private void receive(SettingsPayload payload) {
		if (!waiting || !requests.accepts(payload.requestId())) return;
		waiting = false;
		requests.clear();
		status = switch (payload.action()) {
			case SettingsPayload.STATE -> "server_loaded";
			case SettingsPayload.SAVED -> "server_saved";
			case SettingsPayload.DENIED -> "denied";
			case SettingsPayload.STALE -> "stale";
			case SettingsPayload.RATE_LIMITED -> "rate_limited";
			default -> "io_error";
		};
		if (payload.action() == SettingsPayload.STATE || payload.action() == SettingsPayload.SAVED || payload.action() == SettingsPayload.STALE) {
			policy = payload.config(); revision = payload.revision();
		}
		rebuildWidgets();
	}
	@Override public void tick() {
		if (waiting && System.nanoTime() - requestTime > 5_000_000_000L) { waiting = false; requests.clear(); status = "timeout"; rebuildWidgets(); }
	}
	private Component fieldLabel(int field) {
		Object value = switch (field) {
			case 0 -> policy.enabled(); case 1 -> policy.seedDisclosureMode() == RemoteWorldgenConfig.SeedDisclosureMode.TRUSTED_RAW;
			case 2 -> policy.maxInFlightJobs(); case 3 -> policy.jobTimeout().toMillis(); case 4 -> policy.cacheEntries();
			case 5 -> policy.predictionEnabled(); case 6 -> policy.predictionIntervalTicks(); case 7 -> policy.predictionLeadChunks();
			default -> policy.validationSampleCells();
		};
		return text("field" + field, value);
	}
	private static int next(int value, int... values) { for (int v : values) if (v > value) return v; return values[0]; }
	private void change(int f) {
		int cache = f == 4 ? next(policy.cacheEntries(), 0, 8, 16, 32, 64, 128, 256) : policy.cacheEntries();
		boolean prediction = f == 5 ? !policy.predictionEnabled() : policy.predictionEnabled();
		if (f == 4 && cache == 0) prediction = false;
		if (prediction && cache == 0) cache = 16;
		policy = new RemoteWorldgenConfig(f == 0 ? !policy.enabled() : policy.enabled(),
			f == 1 ? (policy.seedDisclosureMode() == RemoteWorldgenConfig.SeedDisclosureMode.DENY ? RemoteWorldgenConfig.SeedDisclosureMode.TRUSTED_RAW : RemoteWorldgenConfig.SeedDisclosureMode.DENY) : policy.seedDisclosureMode(),
			f == 2 ? next(policy.maxInFlightJobs(), 1, 2, 4, 8, 16, 32, 64) : policy.maxInFlightJobs(),
			f == 3 ? Duration.ofMillis(next((int) policy.jobTimeout().toMillis(), 500, 1000, 2000, 5000, 10000, 30000, 60000)) : policy.jobTimeout(),
			cache, prediction, f == 6 ? next(policy.predictionIntervalTicks(), 5, 10, 20, 40, 100, 1200) : policy.predictionIntervalTicks(),
			f == 7 ? next(policy.predictionLeadChunks(), 1, 2, 4, 8) : policy.predictionLeadChunks(),
			f == 8 ? next(policy.validationSampleCells(), 0, 1, 4, 8, 16, 32, 64) : policy.validationSampleCells());
	}
	@Override public void onClose() { minecraft.gui.setScreen(parent); }
}
