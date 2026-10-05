package io.github.genichimaruo.worldgenassist.server;

import java.util.Arrays;
import java.util.StringJoiner;
import io.github.genichimaruo.worldgenassist.common.CompleteTerrainData;

/** Bounded failure-only explanation. Never changes equality, approval or result bytes. */
final class CompleteTerrainDifference {
	private CompleteTerrainDifference() { }
	static String describe(CompleteTerrainData a, CompleteTerrainData b) {
		var result = new StringJoiner(";");
		if (a.minY() != b.minY() || a.height() != b.height() || a.blockCount() != b.blockCount()) {
			return "geometry="+a.minY()+","+a.height()+","+a.blockCount()+"/"+b.minY()+","+b.height()+","+b.blockCount();
		}
		int choices = 0, first = -1;
		for (int i = 0; i < a.blockCount(); i++) if (a.choice(i) != b.choice(i)) { choices++; if (first < 0) first = i; }
		if (first >= 0) {
			int column = first / a.height();
			result.add("blocks="+choices+",first="+(column % 16)+","+(a.minY()+first % a.height())+","+(column / 16)+",codes="+a.choice(first)+"/"+b.choice(first));
		}
		appendHeights(result,"surface",a.surfaceHeights(),b.surfaceHeights());
		appendHeights(result,"floor",a.floorHeights(),b.floorHeights());
		int sections = 0, firstSection = -1, firstIndex = -1, firstLength = 0, secondLength = 0;
		for (int section = 0; section < a.height()/16; section++) {
			short[] x = a.postProcessing(section), y = b.postProcessing(section);
			if (!Arrays.equals(x,y)) {
				sections++;
				if (firstSection < 0) { firstSection = section; firstIndex = Arrays.mismatch(x,y); firstLength = x.length; secondLength = y.length; }
			}
		}
		if (firstSection >= 0) result.add("postprocessing_sections="+sections+",first_section="+firstSection+",first_index="+firstIndex+",lengths="+firstLength+"/"+secondLength);
		if (!Arrays.equals(a.biomeWindowDigest(),b.biomeWindowDigest())) result.add("biome_digest=different");
		return result.length() == 0 ? "equal" : result.toString();
	}
	private static void appendHeights(StringJoiner result, String name, short[] a, short[] b) {
		int count = 0, first = -1;
		for (int i = 0; i < a.length; i++) if (a[i] != b[i]) { count++; if (first < 0) first = i; }
		if (first >= 0) result.add(name+"="+count+",first_column="+first+",heights="+a[first]+"/"+b[first]);
	}
}
