package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class StartedTerrain263Test {
	@Test void expiredOrEvictedWorkCannotFillMetadataAndReadsDoNotRenewIt() {
		var started=new StartedTerrain<String>(2,10);
		started.mark("a",0);started.mark("b",1);assertTrue(started.contains("a",2));
		started.mark("a",3);assertEquals(2,started.size());
		started.mark("c",4);assertFalse(started.contains("a",4));assertTrue(started.contains("b",4));
		assertFalse(started.contains("b",11));assertTrue(started.contains("c",11));
		assertFalse(started.contains("c",14));assertEquals(0,started.size());
	}
	@Test void invalidationCanRetireOnlyOneOwnerEpochOrAllWorldWork() {
		record Key(String owner,int epoch,int world,int x){}
		var started=new StartedTerrain<Key>(4,100);
		var old=new Key("a",1,1,0);var current=new Key("a",2,1,0);var other=new Key("b",1,1,0);
		started.mark(old,0);assertFalse(started.contains(current,1));started.mark(current,1);started.mark(other,2);
		started.removeMatching(key->key.owner().equals("a")&&key.epoch()==1);
		assertFalse(started.contains(old,3));assertTrue(started.contains(current,3));assertTrue(started.contains(other,3));
		started.clear();assertEquals(0,started.size());assertFalse(started.contains(other,4));
	}
}
