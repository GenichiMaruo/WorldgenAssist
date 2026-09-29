package io.github.genichimaruo.worldgenassist.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;

/** Benchmark-only client receipt timestamps on the same monotonic client clock. */
final class FabricClientChunkReceiptProbe {
	private static final Pattern MARKER = Pattern.compile("CAWG_SCENARIO_MEASURED_(BEGIN|END)_(\\d+)");
	private static volatile int activeRepeat;

	private FabricClientChunkReceiptProbe() {}

	static void register() {
		if (!"true".equalsIgnoreCase(System.getenv("WORLDGEN_ASSIST_CLIENT_MEASURE_RECEIPT"))) return;
		WorldgenAssist.LOGGER.info("[CAWG] benchmark.receipt_probe_registered");
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> recordMarker(message.getString()));
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, type, timestamp) -> recordMarker(message.getString()));
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			int repeat = activeRepeat;
			if (repeat == 0) return;
			WorldgenAssist.LOGGER.info("[CAWG] benchmark.chunk_received repeat={} chunk={},{} nanos={}",
				repeat, chunk.getPos().x(), chunk.getPos().z(), System.nanoTime());
		});
	}

	private static void recordMarker(String message) {
		Matcher marker = MARKER.matcher(message);
		if (!marker.find()) return;
		int repeat = Integer.parseInt(marker.group(2));
		if ("BEGIN".equals(marker.group(1))) activeRepeat = repeat;
		WorldgenAssist.LOGGER.info("[CAWG] benchmark.client_marker phase={} repeat={} nanos={}",
			marker.group(1), repeat, System.nanoTime());
		// Keep the last repeat active until the next BEGIN so delivery after
		// server generation completion remains visible.
	}
}
