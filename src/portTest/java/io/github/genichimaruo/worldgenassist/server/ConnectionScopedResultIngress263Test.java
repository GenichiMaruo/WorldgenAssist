package io.github.genichimaruo.worldgenassist.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import io.github.genichimaruo.worldgenassist.common.*;
import io.github.genichimaruo.worldgenassist.network.TerrainJobResultPayload;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ConnectionScopedResultIngress263Test {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test void queuedReceiptsKeepTheirOwnerAndBecomeInvalidOnReplacementOrDisconnect() {
		var ingress = new ConnectionScopedResultIngress<Object>();
		Object old = new Object(), replacement = new Object(), other = new Object();
		UUID a = UUID.randomUUID(), b = UUID.randomUUID();
		List<UUID> owners = new ArrayList<>(); List<BooleanSupplier> queued = new ArrayList<>();
		ConnectionScopedResultIngress.Receiver receiver = (owner, payload, received, current) -> {
			assertEquals(123, received); owners.add(owner); queued.add(current);
		};
		var identity = new TerrainJobIdentity(WorldgenProtocolVersion.CURRENT, UUID.randomUUID(),
			Identifier.parse("minecraft:overworld"), 0, 0, WorldgenContextFingerprint.fromHex("12".repeat(32)));
		var payload = new TerrainJobResultPayload(TerrainDensityResultEnvelope.encode(new TerrainDensityResult(identity, new double[]{1}, 0)));
		ingress.bind(old, a, receiver); ingress.bind(other, b, receiver);
		assertTrue(ingress.receive(old, payload, 123)); assertTrue(ingress.receive(other, payload, 123));
		ingress.bind(replacement, a, receiver);
		assertFalse(queued.get(0).getAsBoolean()); assertTrue(queued.get(1).getAsBoolean());
		assertFalse(ingress.receive(old, payload, 123));
		ingress.remove(old); // A delayed old disconnect must not revoke the replacement.
		assertTrue(ingress.receive(replacement, payload, 123));
		assertEquals(List.of(a, b, a), owners);
		ingress.remove(other); assertFalse(queued.get(1).getAsBoolean()); assertTrue(queued.get(2).getAsBoolean());
		ingress.clear(); assertFalse(queued.get(2).getAsBoolean());
	}
}
