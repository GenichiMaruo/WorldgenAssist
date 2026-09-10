package io.github.genichimaruo.worldgenassist.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import io.github.genichimaruo.worldgenassist.common.SeededLeafDensityResultEnvelope;
import io.github.genichimaruo.worldgenassist.common.SeededLeafFixtureConfig;
import io.github.genichimaruo.worldgenassist.common.SeededLeafJobClaim;
import io.github.genichimaruo.worldgenassist.network.SeededLeafFixturePayloads;

/** Opt-in fixture client; the worker's real-exit capacity survives reconnects. */
final class SeededLeafFixtureClient {
	private static final String FAULT_PROPERTY = "worldgen_assist.client.seeded_leaf.fixture_fault";
	private static final String FAULT_ENVIRONMENT = "WORLDGEN_ASSIST_CLIENT_SEEDED_LEAF_FIXTURE_FAULT";

	private final SeededLeafClientWorker worker = new SeededLeafClientWorker();
	private final HolderLookup.Provider registries = VanillaRegistries.createLookup();
	private final FixtureFault fault = FixtureFault.fromDevelopmentFixtureFlag();
	private boolean accepted;
	private long epoch;
	private SeededLeafJobClaim activeClaim;

	SeededLeafFixtureClient() {
		if (fault != FixtureFault.NONE) {
			WorldgenAssist.LOGGER.warn("[CAWG] seeded_fixture.client_fault_enabled mode={}", fault.logName);
		}
	}

	void register() {
		ClientPlayNetworking.registerGlobalReceiver(SeededLeafFixturePayloads.Accepted.TYPE, (payload, context) -> {
			accepted = payload.enabled();
			WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.client_handshake accepted={}", accepted);
		});
		ClientPlayNetworking.registerGlobalReceiver(SeededLeafFixturePayloads.Request.TYPE, (payload, context) -> {
			Minecraft client = context.client();
			SeededLeafJobClaim claim = SeededLeafJobClaim.fromAuthorization(payload.job());
			if (!accepted || client.level == null || activeClaim != null) {
				ClientPlayNetworking.send(new SeededLeafFixturePayloads.Failure(claim));
				return;
			}
			activeClaim = claim;
			if (fault == FixtureFault.WITHHOLD_RESULT) {
				// Deliberately retain the one bounded active claim until server cancellation,
				// reload, or disconnect. Do not sleep or occupy the render thread.
				WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.client_fault_held id={} mode=timeout", claim.jobId());
				return;
			}
			if (fault == FixtureFault.MALFORMED_ENVELOPE) {
				// The claim and shape are authentic, but the one-byte DEFLATE stream is
				// invalid. It remains codec-valid to exercise claim-before-decode.
				SeededLeafDensityResultEnvelope malformed = new SeededLeafDensityResultEnvelope(
					claim, payload.job().job().sampleCount(), SeededLeafDensityResultEnvelope.Encoding.DEFLATE,
					new byte[] { 0 }, 0L, 0L);
				activeClaim = null;
				ClientPlayNetworking.send(new SeededLeafFixturePayloads.Result(malformed));
				WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.client_fault_malformed id={} encoding=deflate", claim.jobId());
				return;
			}
			long admittedEpoch = epoch;
			Object listener = client.getConnection();
			worker.submit(registries, client.level.dimension().identifier(), payload.job()).whenComplete((result, error) -> {
				client.execute(() -> {
					if (epoch != admittedEpoch || client.getConnection() != listener || activeClaim != claim) { return; }
					activeClaim = null;
					if (error != null) {
						ClientPlayNetworking.send(new SeededLeafFixturePayloads.Failure(claim));
						WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.client_failed id={}", claim.jobId());
					} else {
						ClientPlayNetworking.send(new SeededLeafFixturePayloads.Result(result));
						WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.client_complete id={} samples={}", claim.jobId(), result.densityCount());
					}
				});
			});
		});
		ClientPlayNetworking.registerGlobalReceiver(SeededLeafFixturePayloads.Cancel.TYPE, (payload, context) -> {
			if (activeClaim != null && activeClaim.equals(payload.claim())) {
				SeededLeafJobClaim cancelled = activeClaim;
				activeClaim = null;
				worker.cancel(cancelled.jobId());
				WorldgenAssist.LOGGER.info("[CAWG] seeded_fixture.client_cancelled id={}", cancelled.jobId());
			}
		});
		ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> {
			reset();
			if (ClientPlayNetworking.canSend(SeededLeafFixturePayloads.Hello.TYPE)) {
				sender.sendPacket(new SeededLeafFixturePayloads.Hello(SeededLeafFixtureConfig.PROTOCOL));
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> reset());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { reset(); worker.close(); });
	}

	private void reset() {
		accepted = false;
		epoch++;
		SeededLeafJobClaim cancelled = activeClaim;
		activeClaim = null;
		if (cancelled != null) { worker.cancel(cancelled.jobId()); }
	}

	private enum FixtureFault {
		NONE("none"),
		WITHHOLD_RESULT("timeout"),
		MALFORMED_ENVELOPE("malformed");

		private final String logName;

		FixtureFault(String logName) {
			this.logName = logName;
		}

		private static FixtureFault fromDevelopmentFixtureFlag() {
			if (!SeededLeafFixtureConfig.clientEnabled() || !FabricLoader.getInstance().isDevelopmentEnvironment()) {
				return NONE;
			}
			String configured = System.getProperty(FAULT_PROPERTY);
			if (configured == null) { configured = System.getenv(FAULT_ENVIRONMENT); }
			if (configured == null || configured.isBlank() || "none".equalsIgnoreCase(configured)) { return NONE; }
			return switch (configured) {
				case "timeout", "withhold" -> WITHHOLD_RESULT;
				case "malformed" -> MALFORMED_ENVELOPE;
				default -> {
					WorldgenAssist.LOGGER.warn("[CAWG] seeded_fixture.client_fault_ignored reason=unknown_value");
					yield NONE;
				}
			};
		}
	}
}
