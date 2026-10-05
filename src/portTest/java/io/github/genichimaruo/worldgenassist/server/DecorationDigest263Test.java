package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

class DecorationDigest263Test {
	@Test void canonicalNbtPreservesGameplayFieldsAndOrderAndOnlyNormalizesEntityUuid() {
		var first = new CompoundTag(); first.putInt("x", 100); first.putString("id", "minecraft:chest");
		var second = new CompoundTag(); second.putString("id", "minecraft:chest"); second.putInt("x", 100);
		assertEquals(hash(first, false), hash(second, false));
		second.putInt("x", 101); assertNotEquals(hash(first, false), hash(second, false));
		second.putInt("x", 100);
		first.putIntArray("UUID", new int[]{1,2,3,4}); second.putIntArray("UUID", new int[]{5,6,7,8});
		assertEquals(hash(first, true), hash(second, true)); assertNotEquals(hash(first, false), hash(second, false));
		first.putLong("LootTableSeed", 1); second.putLong("LootTableSeed", 2);
		assertNotEquals(hash(first, true), hash(second, true));
		var list = new ListTag(); list.add(first); list.add(second);
		var reversed = new ListTag(); reversed.add(second); reversed.add(first);
		var duplicate = new ListTag(); duplicate.add(first); duplicate.add(first);
		assertNotEquals(hash(list, true), hash(reversed, true)); assertNotEquals(hash(list, true), hash(duplicate, true));
		var light = new CompoundTag(); light.putBoolean("isLightOn", true);
		var low = new CompoundTag(); low.putByte("Y", (byte)-5); low.putByteArray("BlockLight", new byte[2048]);
		var high = new CompoundTag(); high.putByte("Y", (byte)20); high.putByteArray("SkyLight", new byte[2048]);
		var sections = new ListTag(); sections.add(low); sections.add(high); light.put("sections", sections);
		String digest = SavedLightDigest.compute(light);
		var reordered = new ListTag(); reordered.add(high); reordered.add(low); light.put("sections", reordered);
		assertEquals(digest, SavedLightDigest.compute(light));
		var changed = new byte[2048]; changed[2047] = 1; high.putByteArray("SkyLight", changed);
		assertNotEquals(digest, SavedLightDigest.compute(light));
		high.putByteArray("SkyLight", new byte[2048]); high.remove("SkyLight");
		assertNotEquals(digest, SavedLightDigest.compute(light)); // Presence is retained, not normalized away.
		high.putByteArray("SkyLight", new byte[2047]); assertThrows(IllegalArgumentException.class, () -> SavedLightDigest.compute(light));
		high.putByteArray("SkyLight", new byte[2048]); high.putByte("Y", (byte)21);
		assertThrows(IllegalArgumentException.class, () -> SavedLightDigest.compute(light));
		high.putByte("Y", (byte)-5); assertThrows(IllegalArgumentException.class, () -> SavedLightDigest.compute(light));
		high.putByte("Y", (byte)20); light.putBoolean("isLightOn", false);
		assertThrows(IllegalArgumentException.class, () -> SavedLightDigest.compute(light));
	}
	private static String hash(net.minecraft.nbt.Tag tag, boolean normalizeEntity) {
		return DecorationStageDigest.hash(out -> DecorationStageDigest.writeTag(out, tag, normalizeEntity));
	}
}
