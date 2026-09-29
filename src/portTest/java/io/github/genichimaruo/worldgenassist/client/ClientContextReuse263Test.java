package io.github.genichimaruo.worldgenassist.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import io.github.genichimaruo.worldgenassist.common.*;
import io.github.genichimaruo.worldgenassist.server.WorldgenContextFingerprintFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ClientContextReuse263Test {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test void reuseStillChecksFingerprintAndResetRebuildsContext() {
		var registries = VanillaRegistries.createWorldLookup();
		var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER);
		var noise = settings.value().noiseSettings();
		var dimension = Identifier.parse("minecraft:the_nether");
		var fingerprint = WorldgenContextFingerprintFactory.create(registries, dimension, 8675309L, true,
			noise.minY(), noise.height(), settings);
		var identity = new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT, UUID.randomUUID(), dimension, 2, -3, fingerprint);
		var job = new TerrainDensityJob(identity, 8675309L, true, NoiseGeneratorSettings.NETHER.identifier(), noise.minY(), noise.height(), 1, 1);
		var session = new ClientTerrainDensityComputer.Session(registries);
		var first = ClientTerrainDensityComputer.compute(session, dimension, job);
		assertSame(session.prepare(job, settings), session.prepare(job, settings));
		assertArrayEquals(first.densities(), ClientTerrainDensityComputer.compute(session, dimension, job).densities());
		var changed = new TerrainDensityJob(identity, 8675310L, true, job.noiseSettings(), job.minY(), job.height(), 1, 1);
		assertThrows(ClientTerrainDensityComputer.RejectedJobException.class,
			() -> ClientTerrainDensityComputer.compute(session, dimension, changed));
		var context = session.prepare(job, settings);
		session.clear();
		assertEquals(0, session.size());
		assertNotSame(context, session.prepare(job, settings));
	}
}
