package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import io.netty.buffer.Unpooled;
import io.github.genichimaruo.worldgenassist.common.*;
import io.github.genichimaruo.worldgenassist.network.*;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMapper;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

class BiomePhase263Test {
	static { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	private static final Identifier DIM = Identifier.withDefaultNamespace("overworld");
	private static TerrainDensityJob job() {
		return new TerrainDensityJob(new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT,UUID.randomUUID(),DIM,-2,3,
			WorldgenContextFingerprint.fromBytes(new byte[32])),8675309,true,DIM,0,16,1,1,TerrainWorkKind.COMPLETE_TERRAIN,TerrainBeardifierData.EMPTY,true);
	}
	private static CompleteBiomeData biome(boolean forest) {
		byte[] voxels=new byte[64];Arrays.fill(voxels,(byte)(forest?0:1));
		return new CompleteBiomeData(0,16,List.of("minecraft:forest","minecraft:plains"),new byte[][]{{0,1}},voxels);
	}
	private static TerrainDensityResult full(TerrainDensityJob job,CompleteBiomeData biomes) {
		return TerrainDensityResult.fromCompleteTerrain(job.identity(),new CompleteTerrainData(0,16,new byte[4096],new short[256],new short[256],
			new short[][]{new short[0]},new byte[32],biomes),1);
	}
	private static RemoteJobCoordinator.Submission submission(UUID owner) { return new RemoteJobCoordinator.Submission(owner,job(),new CompletableFuture<>()); }
	@Test void boundedWireAndFinalBodiesNeverImportLocalAuthority() {
		var job=job();var data=biome(true);byte[] encoded=data.encode();
		assertEquals(data,CompleteBiomeData.decode(encoded));assertEquals(0,data.code(0,3,3,3));
		assertTrue(encoded.length<=CompleteBiomeData.MAX_BYTES);
		assertThrows(IllegalArgumentException.class,()->CompleteBiomeData.decode(Arrays.copyOf(encoded,encoded.length-1)));
		assertThrows(IllegalArgumentException.class,()->CompleteBiomeData.decode(Arrays.copyOf(encoded,encoded.length+1)));
		byte[] bad=encoded.clone();bad[bad.length-1]=(byte)128;
		assertThrows(IllegalArgumentException.class,()->CompleteBiomeData.decode(bad));
		assertThrows(IllegalArgumentException.class,()->new CompleteBiomeData(0,16,List.of("minecraft:plains","minecraft:forest"),new byte[][]{{0,1}},new byte[64]));
		assertThrows(IllegalArgumentException.class,()->new CompleteBiomeData(0,16,data.names(),new byte[][]{{0,0}},new byte[64]));
		var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
		try {
			var payload=new TerrainBiomeResultPayload(job.identity(),data);
			TerrainBiomeResultPayload.CODEC.encode(buffer,payload);assertEquals(payload,TerrainBiomeResultPayload.CODEC.decode(buffer));assertEquals(0,buffer.readableBytes());
			buffer.clear();var batch=new TerrainJobBatchPayload(List.of(job,new TerrainDensityJob(job().identity(),8675309,true,DIM,0,16,1,1,TerrainWorkKind.COMPLETE_TERRAIN)));
			TerrainJobBatchPayload.CODEC.encode(buffer,batch);assertEquals(batch,TerrainJobBatchPayload.CODEC.decode(buffer));assertEquals(0,buffer.readableBytes());
		} finally {buffer.release();}
		var value=full(job,data);var approved=value.withLocalApproval(()->true,()->{},null,true);
		assertFalse(value.hasVerifiedCenterBiomes());assertTrue(approved.hasVerifiedCenterBiomes());
		assertFalse(value.withLocalApproval(()->true,()->{}).hasVerifiedCenterBiomes());
		var auditCurrent=new AtomicBoolean(true);
		var audited=RemoteDensityValidator.Prepared.completeTerrain(job,value.completeTerrain(),0).authorizeAuditedCenterBiomes(value,auditCurrent::get);
		assertTrue(audited.hasVerifiedCenterBiomes());assertFalse(audited.hasPeerVerification());assertFalse(audited.hasAgreedCenterBiomes());
		assertSame(value,RemoteDensityValidator.Prepared.completeTerrain(job,null,0).authorizeAuditedCenterBiomes(value,()->true));
		assertThrows(IllegalArgumentException.class,()->value.withServerAuditApproval(null,()->true));
		assertThrows(RemoteDensityValidator.RemoteDensityValidationException.class,()->RemoteDensityValidator.Prepared.completeTerrain(job,full(job,biome(false)).completeTerrain(),0).authorizeAuditedCenterBiomes(value,()->true));
		assertFalse(TerrainDensityResultEnvelope.encode(audited,TerrainWorkKind.COMPLETE_TERRAIN).decode().hasVerifiedCenterBiomes());
		auditCurrent.set(false);assertFalse(audited.hasVerifiedCenterBiomes());assertThrows(IllegalArgumentException.class,audited::requireCurrentAuthority);
		var decoded=TerrainDensityResultEnvelope.encode(approved,TerrainWorkKind.COMPLETE_TERRAIN).decode();
		assertEquals(value,decoded);assertFalse(decoded.hasPeerVerification());assertFalse(decoded.hasAgreedCenterBiomes());
		assertDoesNotThrow(()->RemoteDensityValidator.Prepared.completeTerrain(job,value.completeTerrain(),0).compare(decoded));
		assertThrows(RemoteDensityValidator.RemoteDensityValidationException.class,()->RemoteDensityValidator.Prepared.completeTerrain(job,null,0).compare(full(job,null)));
		assertThrows(RemoteDensityValidator.RemoteDensityValidationException.class,()->RemoteDensityValidator.Prepared.completeTerrain(job,value.completeTerrain(),0).compare(full(job,biome(false))));
		byte[] legacy=full(job,null).completeTerrain().encode();legacy=Arrays.copyOf(legacy,legacy.length-1);java.nio.ByteBuffer.wrap(legacy).putInt(1);
		assertNull(CompleteTerrainData.decode(legacy,0,16).centerBiomes());
		UUID owner=UUID.randomUUID();var key=new RemoteDensityResultCache.Key(1,DIM,-2,3,job.identity().contextFingerprint(),DIM,0,16,1,1,owner,1,TerrainWorkKind.COMPLETE_TERRAIN);
		var cache=new RemoteDensityResultCache(2);cache.put(key,approved);assertSame(approved,cache.peekResult(key).orElseThrow());assertEquals(1,cache.size());
		assertSame(approved,cache.takeResult(key).orElseThrow());assertTrue(cache.peekResult(key).isEmpty());
		var expired=new RemoteDensityResultCache(2,0);expired.put(key,approved);assertTrue(expired.peekResult(key).isEmpty());
		assertThrows(IllegalArgumentException.class,()->new TerrainDensityJob(job.identity(),8675309,true,DIM,0,16,1,1,TerrainWorkKind.DENSITY,TerrainBeardifierData.EMPTY,true));
	}
	@Test void distinctPeerPhaseIsBoundedRevocableAndConsistentWithFinalReplies() {
		var a=submission(UUID.randomUUID());var b=submission(UUID.randomUUID());var current=new AtomicBoolean(true);var connected=new AtomicBoolean(true);var book=new EarlyBiomePairs<String>(2);
		var base=a.job();var input=new AuthoritativeBiomeWindow(-2,3,0,16,java.util.Collections.nCopies(9,biome(true)));
		var bound=new TerrainDensityJob(base.identity(),base.worldSeed(),base.generateStructures(),base.noiseSettings(),0,16,1,1,base.workKind(),base.shaping(),true,input);
		assertThrows(IllegalArgumentException.class,()->book.register("different-input",new RemoteJobCoordinator.Submission(a.ownerId(),bound,a.result()),b,Long.MAX_VALUE,()->true));
		assertEquals(0,book.size());
		var pair=book.register("chunk",a,b,System.nanoTime()+30_000_000_000L,current::get);assertNotNull(pair);assertEquals(2,book.size());
		final var initialPair=pair;
		assertNull(book.register("other",submission(UUID.randomUUID()),submission(UUID.randomUUID()),Long.MAX_VALUE,()->true));
		book.receive(b.ownerId(),new TerrainBiomeResultPayload(a.job().identity(),biome(true)));assertTrue(pair.ready().isEmpty());
		book.receive(a.ownerId(),new TerrainBiomeResultPayload(a.job().identity(),biome(true)),connected::get);assertTrue(pair.ready().isEmpty());
		book.receive(b.ownerId(),new TerrainBiomeResultPayload(b.job().identity(),biome(true)));assertEquals(biome(true),pair.ready().orElseThrow());
		assertDoesNotThrow(()->initialPair.checkFull(full(a.job(),biome(true))));
		assertDoesNotThrow(()->initialPair.checkFull(full(b.job(),biome(true))));
		book.complete(initialPair,true);assertEquals(0,book.size());assertTrue(initialPair.ready().isPresent());
		connected.set(false);assertTrue(initialPair.ready().isEmpty());assertThrows(java.util.concurrent.CancellationException.class,initialPair::requireCurrent);
		current.set(false);assertTrue(pair.ready().isEmpty());assertThrows(java.util.concurrent.CancellationException.class,pair::requireCurrent);
		book.removeOwner(b.ownerId());assertEquals(0,book.size());assertTrue(book.get("chunk").isEmpty());
		pair=book.register("chunk",a,b,Long.MAX_VALUE,()->true);
		book.receive(a.ownerId(),new TerrainBiomeResultPayload(a.job().identity(),biome(true)));
		book.receive(b.ownerId(),new TerrainBiomeResultPayload(b.job().identity(),biome(false)));assertTrue(pair.ready().isEmpty());
		// Different original histories retain terrain-only peer approval and its alternative; no biome authority.
		var first=full(a.job(),biome(true));var second=full(b.job(),biome(false));a.result().complete(first);b.result().complete(second);
		var agreed=CompleteTerrainPeerVerifier.agree(a,b,()->true,()->{},error->{throw error;}).join();
		assertTrue(agreed.hasPeerVerification());assertFalse(agreed.hasAgreedCenterBiomes());
		final var activePair=pair;
		assertThrows(RemoteDensityValidator.RemoteDensityValidationException.class,()->activePair.checkFull(full(a.job(),biome(false))));
		assertTrue(activePair.ready().isEmpty());book.clear();assertEquals(0,book.size());
		assertThrows(IllegalArgumentException.class,()->book.register("same-owner",a,new RemoteJobCoordinator.Submission(a.ownerId(),job(),new CompletableFuture<>()),Long.MAX_VALUE,()->true));
		var late=book.register("expired",submission(UUID.randomUUID()),submission(UUID.randomUUID()),System.nanoTime()-1,()->true);assertTrue(late.ready().isEmpty());assertEquals(0,book.size());
	}
	@Test void originalFillerMatchesAllVoxelsAndUnusedPalettesBeforeAnyWrite() {
		var registries=VanillaRegistries.createWorldLookup();var biomes=registries.lookupOrThrow(Registries.BIOME);var plains=biomes.getOrThrow(Biomes.PLAINS);
		IdMapper<Holder<Biome>> ids=new IdMapper<>();biomes.listElements().forEach(ids::add);
		var blocks=Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);var strategy=Strategy.createForBiomes(ids);
		var containers=new PalettedContainerFactory(blocks,Blocks.AIR.defaultBlockState(),PalettedContainer.codecRW(BlockState.CODEC,blocks,Blocks.AIR.defaultBlockState()),
			strategy,plains,PalettedContainer.codecRO(Biome.CODEC,strategy,plains));
		var generator=new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
			.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)),registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD));
		var choices=generator.getBiomeSource().possibleBiomes().stream().sorted(java.util.Comparator.comparing(b->b.unwrapKey().orElseThrow().identifier().toString())).limit(12).toList();
		var source=new ProtoChunk(new ChunkPos(-2,3),UpgradeData.EMPTY,LevelHeightAccessor.create(-64,384),containers,null);
		// An independent original fill crosses local/global palette thresholds and retains unused default entries.
		source.fillBiomesFromNoise((x,y,z)->choices.get(Math.floorMod(x*17+y*7+z*3,choices.size())));
		var data=CompleteBiomeData.capture(source);assertEquals(data,CompleteBiomeData.decode(data.encode()));
		var target=new ProtoChunk(source.getPos(),UpgradeData.EMPTY,source,containers,null);target.setPersistedStatus(ChunkStatus.STRUCTURE_REFERENCES);
		var sections=target.getSections().clone();var states=Arrays.stream(sections).map(LevelChunkSection::getStates).toArray();var original=CompleteBiomeData.capture(target);
		var prepared=EarlyBiomeApplicator.prepare(data,target,generator,registries,()->{});assertEquals(original,CompleteBiomeData.capture(target));assertTrue(prepared.apply());
		assertEquals(ChunkStatus.STRUCTURE_REFERENCES,target.getPersistedStatus());assertEquals(data,CompleteBiomeData.capture(target));
		for(int s=0;s<sections.length;s++) {
			assertSame(sections[s],target.getSection(s));assertSame(states[s],target.getSection(s).getStates());
			for(int x=0;x<4;x++)for(int y=0;y<4;y++)for(int z=0;z<4;z++)assertSame(source.getSection(s).getNoiseBiome(x,y,z),target.getSection(s).getNoiseBiome(x,y,z));
		}
		assertThrows(IllegalStateException.class,prepared::apply);
		var denied=new ProtoChunk(source.getPos(),UpgradeData.EMPTY,source,containers,null);var before=CompleteBiomeData.capture(denied);
		assertThrows(java.util.concurrent.CancellationException.class,()->EarlyBiomeApplicator.prepare(data,denied,generator,registries,()->{throw new java.util.concurrent.CancellationException();}));
		assertEquals(before,CompleteBiomeData.capture(denied));
		var current=new AtomicBoolean(true);var finalGuard=EarlyBiomeApplicator.prepare(data,denied,generator,registries,()->{
			if(!current.get())throw new java.util.concurrent.CancellationException();
		});current.set(false);assertFalse(finalGuard.apply());assertEquals(before,CompleteBiomeData.capture(denied));
		denied.fillBiomesFromNoise((x,y,z)->biomes.getOrThrow(Biomes.NETHER_WASTES));var outside=CompleteBiomeData.capture(denied);
		var empty=new ProtoChunk(source.getPos(),UpgradeData.EMPTY,source,containers,null);
		assertThrows(IllegalArgumentException.class,()->EarlyBiomeApplicator.prepare(outside,empty,generator,registries,()->{}));assertEquals(before,CompleteBiomeData.capture(empty));
	}
}
