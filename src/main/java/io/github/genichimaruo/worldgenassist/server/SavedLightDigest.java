package io.github.genichimaruo.worldgenassist.server;

import java.util.TreeMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/** Strict stock Overworld saved-light evidence, including the two exterior light sections. */
final class SavedLightDigest {
	private SavedLightDigest() {}
	static String compute(CompoundTag chunk) {
		if (!chunk.getBoolean("isLightOn").orElse(false)) throw new IllegalArgumentException("saved FULL light is not correct");
		var sections = new TreeMap<Integer, CompoundTag>();
		var source = chunk.get("sections");
		if (!(source instanceof ListTag list) || list.size() > 26) throw new IllegalArgumentException("saved light sections");
		for (var entry : list) {
			if (!(entry instanceof CompoundTag section)) throw new IllegalArgumentException("saved light section type");
			int y = section.getByte("Y").orElseThrow();
			if (y < -5 || y > 20 || sections.put(y, section) != null) throw new IllegalArgumentException("saved light section coordinate");
			for (String name : new String[]{"SkyLight", "BlockLight"}) if (section.contains(name)
				&& section.getByteArray(name).orElseThrow().length != 2048) throw new IllegalArgumentException("saved light array bounds");
		}
		return DecorationStageDigest.hash(out -> {
			out.writeUTF("worldgen_assist:saved_light"); out.writeInt(1); out.writeBoolean(true);
			for (var entry : sections.entrySet()) {
				var section = entry.getValue();
				if (!section.contains("SkyLight") && !section.contains("BlockLight")) continue;
				out.writeInt(entry.getKey());
				for (String name : new String[]{"SkyLight", "BlockLight"}) {
					out.writeBoolean(section.contains(name));
					if (section.contains(name)) out.write(section.getByteArray(name).orElseThrow());
				}
			}
		});
	}
}
