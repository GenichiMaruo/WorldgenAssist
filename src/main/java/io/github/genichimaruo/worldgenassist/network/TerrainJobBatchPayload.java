package io.github.genichimaruo.worldgenassist.network;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import io.github.genichimaruo.worldgenassist.common.TerrainDensityJob;

/** Optional v4 channel: approvals remain individually identified and bounded. */
public record TerrainJobBatchPayload(List<TerrainDensityJob> jobs) implements CustomPacketPayload {
	public static final int MAX_JOBS = 4;
	public static final Type<TerrainJobBatchPayload> TYPE = WorldgenPayloadTypes.type("terrain_job_batch");
	public static final StreamCodec<RegistryFriendlyByteBuf, TerrainJobBatchPayload> CODEC =
		CustomPacketPayload.codec(TerrainJobBatchPayload::write, TerrainJobBatchPayload::read);

	public TerrainJobBatchPayload {
		jobs = List.copyOf(jobs);
		if (jobs.isEmpty() || jobs.size() > MAX_JOBS) throw new IllegalArgumentException("Invalid batch size");
		var ids = new HashSet<java.util.UUID>();
		for (var job : jobs) if (!ids.add(job.identity().jobId())) throw new IllegalArgumentException("Duplicate batch job");
	}
	private void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeVarInt(jobs.size());
		for (var job : jobs) WorldgenPayloadCodecs.writeJob(buffer, job);
	}
	private static TerrainJobBatchPayload read(RegistryFriendlyByteBuf buffer) {
		int size = buffer.readVarInt();
		if (size < 1 || size > MAX_JOBS) throw new IllegalArgumentException("Invalid batch size: " + size);
		var jobs = new ArrayList<TerrainDensityJob>(size);
		for (int i = 0; i < size; i++) jobs.add(WorldgenPayloadCodecs.readJob(buffer));
		return new TerrainJobBatchPayload(jobs);
	}
	@Override public Type<TerrainJobBatchPayload> type() { return TYPE; }
}
