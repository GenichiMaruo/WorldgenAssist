package io.github.genichimaruo.worldgenassist.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Bounded expiry-ordered metadata. Reads never extend a retired task's lifetime. */
final class StartedTerrain<K> {
	private final int capacity;
	private final long lifetime;
	private final Map<K,Long> entries=new LinkedHashMap<>();
	StartedTerrain(int capacity,long lifetime){
		if(capacity<1||lifetime<1)throw new IllegalArgumentException("Invalid terrain lifetime bounds");
		this.capacity=capacity;this.lifetime=lifetime;
	}
	synchronized void mark(K key,long now){
		prune(now);if(entries.containsKey(key))return;
		if(entries.size()>=capacity)entries.remove(entries.keySet().iterator().next());
		entries.put(key,now+lifetime);
	}
	synchronized boolean contains(K key,long now){prune(now);return entries.containsKey(key);}
	synchronized void removeMatching(Predicate<K> predicate){entries.keySet().removeIf(predicate);}
	synchronized void clear(){entries.clear();}
	synchronized int size(){return entries.size();}
	private void prune(long now){
		var iterator=entries.entrySet().iterator();
		while(iterator.hasNext()) {if(iterator.next().getValue()>now)break;iterator.remove();}
	}
}
