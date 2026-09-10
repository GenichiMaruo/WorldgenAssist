package io.github.genichimaruo.worldgenassist.server;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import io.github.genichimaruo.worldgenassist.WorldgenAssist;

/** Owns the inactive protocol-v3 research authority across Fabric server lifecycle events. */
public final class SeededLeafJobSecurityLifecycle {
	public static final int DEFAULT_MAX_DISCLOSED_ENTRIES_PER_OWNER = 100_000;
	public static final int DEFAULT_MAX_DISCLOSED_ENTRIES_GLOBAL = 100_000;
	public static final int DEFAULT_VALIDATION_SAMPLE_CELLS = 1;
	public static final String DISCLOSURE_LEDGER_FILE = "seeded_leaf_disclosure_budget.bin";
	private static final Duration TERMINAL_RETENTION = Duration.ofSeconds(30);

	private static SeededLeafJobSecurityLifecycle instance;

	private final SeededLeafJobAuthority authority;
	private final SeededLeafPersistentDisclosureLedger persistentDisclosureLedger;
	private final int maximumValidationTasks;
	private final int validationSampleCells;
	private final Duration validationTimeout;
	private volatile SeededLeafResultValidationExecutor resultValidationExecutor;

	SeededLeafJobSecurityLifecycle(SeededLeafJobAuthority authority) {
		this(authority, null, 0, 0, Duration.ZERO);
	}

	private SeededLeafJobSecurityLifecycle(
		SeededLeafJobAuthority authority,
		SeededLeafPersistentDisclosureLedger persistentDisclosureLedger,
		int maximumValidationTasks,
		int validationSampleCells,
		Duration validationTimeout
	) {
		this.authority = Objects.requireNonNull(authority, "authority");
		this.persistentDisclosureLedger = persistentDisclosureLedger;
		this.maximumValidationTasks = maximumValidationTasks;
		this.validationSampleCells = validationSampleCells;
		this.validationTimeout = Objects.requireNonNull(validationTimeout, "validationTimeout");
	}

	public static synchronized void register(RemoteWorldgenConfig config) {
		Objects.requireNonNull(config, "config");
		if (instance != null) {
			throw new IllegalStateException("SeededLeafJobSecurityLifecycle is already registered");
		}
		SecureRandom random = new SecureRandom();
		SeededLeafPersistentDisclosureLedger disclosureLedger = new SeededLeafPersistentDisclosureLedger(
			DEFAULT_MAX_DISCLOSED_ENTRIES_GLOBAL
		);
		SeededLeafJobAuthority authority = new SeededLeafJobAuthority(
			config.maxInFlightJobs(),
			config.maxInFlightJobs(),
			SeededLeafJobAuthority.MAX_PENDING_TRANSCRIPT_ENTRIES,
			DEFAULT_MAX_DISCLOSED_ENTRIES_PER_OWNER,
			config.jobTimeout(),
			TERMINAL_RETENTION,
			System::nanoTime,
			UUID::randomUUID,
			() -> io.github.genichimaruo.worldgenassist.common.OpaqueWorldgenContextId.random(random),
			() -> SeededLeafJobAuthenticator.random(random),
			disclosureLedger
		);
		SeededLeafJobSecurityLifecycle lifecycle = new SeededLeafJobSecurityLifecycle(
			authority,
			disclosureLedger,
			config.maxInFlightJobs(),
			Math.max(DEFAULT_VALIDATION_SAMPLE_CELLS, config.validationSampleCells()),
			config.jobTimeout()
		);
		instance = lifecycle;
		lifecycle.registerHandlers();
	}

	public static SeededLeafJobAuthority authority() {
		SeededLeafJobSecurityLifecycle lifecycle = instance;
		if (lifecycle == null) {
			throw new IllegalStateException("SeededLeafJobSecurityLifecycle is not registered");
		}
		return lifecycle.authority;
	}

	public static SeededLeafResultValidationExecutor resultValidationExecutor() {
		SeededLeafJobSecurityLifecycle lifecycle = instance;
		if (lifecycle == null || lifecycle.resultValidationExecutor == null) {
			throw new IllegalStateException("Seeded-leaf result validation executor is not active");
		}
		return lifecycle.resultValidationExecutor;
	}

	private void registerHandlers() {
		ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
		ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resourceManager) -> onDataPackReload());
		ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> onConnect(listener.player.getUUID()));
		ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> onDisconnect(listener.player.getUUID()));
		ServerTickEvents.END_SERVER_TICK.register(server -> onEndServerTick());
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> onServerStopping());
	}

	void onServerStarting() {
		authority.start();
		SeededLeafGlobalDisclosureBudget.Snapshot disclosure = authority.globalDisclosureSnapshot();
		WorldgenAssist.LOGGER.info(
			"[CAWG] seed_authority.started generation={} disclosure_entries_per_owner={} disclosure_entries_global_remaining={}",
			authority.contextGeneration(),
			DEFAULT_MAX_DISCLOSED_ENTRIES_PER_OWNER,
			disclosure.remainingEntries()
		);
	}

	void onServerStarting(MinecraftServer server) {
		Objects.requireNonNull(server, "server");
		if (persistentDisclosureLedger != null) {
			Path dataRoot = server.getWorldPath(LevelResource.DATA).toAbsolutePath().normalize();
			Path ledgerPath = dataRoot.resolve(WorldgenAssist.MOD_ID).resolve(DISCLOSURE_LEDGER_FILE).normalize();
			if (!ledgerPath.startsWith(dataRoot)) {
				throw new IllegalStateException("Seeded-leaf disclosure ledger escaped the world data directory");
			}
			SeededLeafPersistentDisclosureLedger.OpenStatus status = persistentDisclosureLedger.open(ledgerPath);
			SeededLeafGlobalDisclosureBudget.Snapshot snapshot = persistentDisclosureLedger.snapshot();
			if (status != SeededLeafPersistentDisclosureLedger.OpenStatus.OPENED) {
				WorldgenAssist.LOGGER.error(
					"[CAWG] seed_disclosure_ledger.unavailable reason={} policy=fail_closed",
					snapshot.failureReason()
				);
			} else {
				WorldgenAssist.LOGGER.info(
					"[CAWG] seed_disclosure_ledger.opened used_entries={} remaining_entries={} sequence={}",
					snapshot.usedEntries(),
					snapshot.remainingEntries(),
					snapshot.sequence()
				);
			}
		}
		onServerStarting();
		resultValidationExecutor = new SeededLeafResultValidationExecutor(
			authority,
			maximumValidationTasks,
			validationSampleCells,
			validationTimeout
		);
		WorldgenAssist.LOGGER.info(
			"[CAWG] seed_result_validation.started max_tasks={} sampled_cells={} timeout_ms={}",
			maximumValidationTasks,
			validationSampleCells,
			validationTimeout.toMillis()
		);
	}

	void onDataPackReload() {
		SeededLeafResultValidationExecutor validationExecutor = resultValidationExecutor;
		int cancelledValidation;
		int cancelled;
		long generation;
		if (validationExecutor == null) {
			cancelledValidation = 0;
			cancelled = authority.reload().size();
			generation = authority.contextGeneration();
		} else {
			SeededLeafResultValidationExecutor.ReloadResult reload = validationExecutor.invalidateAndReloadAuthority();
			cancelledValidation = reload.cancelledValidations();
			cancelled = reload.cancelledJobs();
			generation = reload.authorityGeneration();
		}
		WorldgenAssist.LOGGER.info(
			"[CAWG] seed_authority.rotated reason=datapack_reload generation={} cancelled_jobs={} cancelled_validations={}",
			generation,
			cancelled,
			cancelledValidation
		);
	}

	void onConnect(UUID ownerId) {
		SeededLeafResultValidationExecutor validationExecutor = resultValidationExecutor;
		if (validationExecutor != null) {
			validationExecutor.connectOwner(ownerId);
		}
	}

	void onDisconnect(UUID ownerId) {
		SeededLeafResultValidationExecutor validationExecutor = resultValidationExecutor;
		int cancelledValidation = validationExecutor == null ? 0 : validationExecutor.disconnectOwner(ownerId);
		int cancelled = authority.cancelAllForOwner(ownerId).size();
		if (cancelled > 0 || cancelledValidation > 0) {
			WorldgenAssist.LOGGER.info(
				"[CAWG] seed_authority.owner_disconnected owner={} cancelled_jobs={} cancelled_validations={}",
				ownerId,
				cancelled,
				cancelledValidation
			);
		}
	}

	void onEndServerTick() {
		int expired = authority.expireTimedOut().size();
		if (expired > 0) {
			WorldgenAssist.LOGGER.info("[CAWG] seed_authority.expired jobs={}", expired);
		}
	}

	void onServerStopping() {
		SeededLeafResultValidationExecutor validationExecutor = resultValidationExecutor;
		int cancelledValidation = validationExecutor == null ? 0 : validationExecutor.cancelAll();
		if (validationExecutor != null) {
			validationExecutor.close();
			resultValidationExecutor = null;
		}
		int cancelled = authority.stop().size();
		if (persistentDisclosureLedger != null) {
			persistentDisclosureLedger.close();
		}
		WorldgenAssist.LOGGER.info(
			"[CAWG] seed_authority.stopped generation={} cancelled_jobs={} cancelled_validations={}",
			authority.contextGeneration(),
			cancelled,
			cancelledValidation
		);
	}
}
