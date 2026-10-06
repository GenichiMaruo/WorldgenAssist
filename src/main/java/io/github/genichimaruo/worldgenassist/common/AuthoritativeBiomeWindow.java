package io.github.genichimaruo.worldgenassist.common;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Immutable server-generated 3x3 input. It grants no permission to apply a result. */
public record AuthoritativeBiomeWindow(int centerX, int centerZ, int minY, int height, List<CompleteBiomeData> chunks) {
	public static final int MAX_BYTES = 20 + 9 * (4 + CompleteBiomeData.MAX_BYTES);
	public AuthoritativeBiomeWindow {
		chunks = List.copyOf(chunks);
		if (chunks.size() != 9 || height < 16 || height > TerrainDensityJob.MAX_HEIGHT || height % 16 != 0 || minY % 16 != 0)
			throw new IllegalArgumentException("Invalid authoritative biome window geometry");
		Math.addExact(minY,height);Math.multiplyExact(Math.addExact(centerX,1),16);Math.multiplyExact(Math.subtractExact(centerX,1),16);
		Math.multiplyExact(Math.addExact(centerZ,1),16);Math.multiplyExact(Math.subtractExact(centerZ,1),16);
		for(var data:chunks) if(data.minY()!=minY || data.height()!=height) throw new IllegalArgumentException("Biome input geometry differs");
	}
	public static AuthoritativeBiomeWindow capture(int x,int z,int minY,int height,BiFunction<Integer,Integer,ChunkAccess> source) {
		var data=new java.util.ArrayList<CompleteBiomeData>(9);
		for(int dz=-1;dz<=1;dz++) for(int dx=-1;dx<=1;dx++) {
			ChunkAccess chunk=Objects.requireNonNull(source.apply(Math.addExact(x,dx),Math.addExact(z,dz)));
			if(chunk.getPos().x()!=x+dx || chunk.getPos().z()!=z+dz || chunk.getMinY()!=minY || chunk.getHeight()!=height
				|| (chunk.getClass()!=ProtoChunk.class && chunk.getClass()!=net.minecraft.world.level.chunk.LevelChunk.class)
				|| !chunk.getHighestGeneratedStatus().isOrAfter(ChunkStatus.BIOMES) || chunk.isUpgrading() || chunk.isOldNoiseGeneration() || chunk.getBelowZeroRetrogen()!=null)
				throw new IllegalArgumentException("Authoritative BIOMES dependency unavailable");
			data.add(CompleteBiomeData.capture(chunk));
		}
		return new AuthoritativeBiomeWindow(x,z,minY,height,data);
	}
	/** Private AIR chunks only, ORIGINAL filler; exact unused palettes are part of the input too. */
	public ProtoChunk[][] restore(HolderLookup.Provider registries,PalettedContainerFactory containers,java.util.Set<Holder<Biome>> possible) {
		var registry=registries.lookupOrThrow(Registries.BIOME);
		ProtoChunk[][] result=new ProtoChunk[3][3];
		for(int z=0;z<3;z++) for(int x=0;x<3;x++) {
			CompleteBiomeData data=chunks.get(z*3+x);
			var choices=data.names().stream().map(name->registry.getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.parse(name)))).toList();
			if(!possible.containsAll(choices)) throw new IllegalArgumentException("Authoritative biome input outside generator domain");
			var chunk=new ProtoChunk(new ChunkPos(centerX+x-1,centerZ+z-1),UpgradeData.EMPTY,LevelHeightAccessor.create(minY,height),containers,null);
			int minX=Math.multiplyExact(chunk.getPos().x(),4),minZ=Math.multiplyExact(chunk.getPos().z(),4),minQuartY=minY/4;
			chunk.fillBiomesFromNoise((qx,qy,qz)->{
				int dx=qx-minX,dz=qz-minZ,dy=qy-minQuartY;
				if(dx<0 || dx>3 || dz<0 || dz>3 || dy<0 || dy>=height/4) throw new IllegalArgumentException("Authoritative resolver outside chunk");
				return choices.get(data.code(dy>>2,dx,dy&3,dz));
			});
			if(!data.equals(CompleteBiomeData.capture(chunk))) throw new IllegalArgumentException("Original private biome input palette differs");
			chunk.setPersistedStatus(ChunkStatus.BIOMES);result[z][x]=chunk;
		}
		return result;
	}
	public byte[] encode() {
		var bodies=chunks.stream().map(CompleteBiomeData::encode).toList();
		int size=20;for(byte[] body:bodies) size=Math.addExact(size,4+body.length);
		ByteBuffer out=ByteBuffer.allocate(size).putInt(1).putInt(centerX).putInt(centerZ).putInt(minY).putInt(height);
		for(byte[] body:bodies) out.putInt(body.length).put(body);
		return out.array();
	}
	/** Same exact v2 framing as the live inspector, including unused palette names. */
	public byte[] digest() {
		try {
			var hash=java.security.MessageDigest.getInstance("SHA-256");
			hash.update("worldgen_assist:terrain_biome_window_v2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
			hash.update(ByteBuffer.allocate(16).putInt(centerX).putInt(centerZ).putInt(minY).putInt(height).array());
			var names=chunks.stream().flatMap(data->data.names().stream()).distinct().sorted().toList();
			var codes=new java.util.HashMap<String,Integer>();hash.update(ByteBuffer.allocate(4).putInt(names.size()).array());
			for(int i=0;i<names.size();i++) {
				String name=names.get(i);codes.put(name,i);byte[] raw=name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
				hash.update((byte)(raw.length>>>8));hash.update((byte)raw.length);hash.update(raw);
			}
			byte[] samples=new byte[9*16*(height/4)*2];int cursor=0;
			for(var data:chunks) for(int z=0;z<4;z++) for(int x=0;x<4;x++) for(int y=0;y<height/4;y++) {
				int code=codes.get(data.names().get(data.code(y>>2,x,y&3,z)));samples[cursor++]=(byte)(code>>>8);samples[cursor++]=(byte)code;
			}
			hash.update(samples);return hash.digest();
		}catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
	}
	public static AuthoritativeBiomeWindow decode(byte[] bytes) {
		if(bytes.length<20 || bytes.length>MAX_BYTES) throw new IllegalArgumentException("Authoritative input byte bound");
		try {
			ByteBuffer in=ByteBuffer.wrap(bytes);
			if(in.getInt()!=1) throw new IllegalArgumentException("Authoritative input version differs");
			int x=in.getInt(),z=in.getInt(),minY=in.getInt(),height=in.getInt();var chunks=new java.util.ArrayList<CompleteBiomeData>(9);
			for(int i=0;i<9;i++) {
				int size=in.getInt();if(size<14 || size>CompleteBiomeData.MAX_BYTES || size>in.remaining()) throw new IllegalArgumentException("Authoritative chunk body bound");
				byte[] body=new byte[size];in.get(body);chunks.add(CompleteBiomeData.decode(body));
			}
			if(in.hasRemaining()) throw new IllegalArgumentException("Trailing authoritative input bytes");
			return new AuthoritativeBiomeWindow(x,z,minY,height,chunks);
		}catch(java.nio.BufferUnderflowException error){throw new IllegalArgumentException("Truncated authoritative biome input",error);}
	}
}
