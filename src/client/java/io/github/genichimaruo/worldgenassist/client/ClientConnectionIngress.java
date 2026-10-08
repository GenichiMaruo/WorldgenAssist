package io.github.genichimaruo.worldgenassist.client;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** One captured client connection. Revoked admissions never become current again. */
public final class ClientConnectionIngress<C, P> {
	public interface Receiver<P> {
		boolean receive(P payload, long receivedNanos, BooleanSupplier current);
		void suspend();
		/** Called only by the main-thread owner after its original context transition. */
		boolean resume();
		void close();
	}
	private volatile Session<C, P> session;

	public synchronized void bind(C connection, Receiver<P> receiver) {
		Objects.requireNonNull(connection);
		Objects.requireNonNull(receiver);
		clear();
		session = new Session<>(connection, receiver);
	}
	public boolean matches(C connection) {
		var captured = session;
		return captured != null && captured.connection == connection;
	}
	public boolean receive(C connection, P payload, long receivedNanos) {
		var captured = session;
		if (captured == null || captured.connection != connection || !captured.active) return false;
		return captured.receiver.receive(payload, receivedNanos,
			() -> captured.active && session == captured);
	}
	public synchronized void suspend(C connection) {
		var captured = session;
		if (captured == null || captured.connection != connection) return;
		captured.pendingTransitions++;
		if (!captured.active) return;
		captured.active = false;
		captured.receiver.suspend();
	}
	public synchronized boolean resume(C connection) {
		var captured = session;
		if (captured == null || captured.connection != connection) return false;
		if (captured.pendingTransitions > 0 && --captured.pendingTransitions > 0) return false;
		if (captured.active) return true;
		if (!captured.receiver.resume()) return false;
		// A new identity prevents previously queued callbacks from reviving.
		session = new Session<>(connection, captured.receiver);
		return true;
	}
	public synchronized void remove(C connection) {
		if (matches(connection)) clear();
	}
	public synchronized void clear() {
		var captured = session;
		session = null;
		if (captured != null) {
			captured.active = false;
			captured.receiver.close();
		}
	}
	private static final class Session<C, P> {
		final C connection;
		final Receiver<P> receiver;
		volatile boolean active = true;
		int pendingTransitions;
		Session(C connection, Receiver<P> receiver) {
			this.connection = connection;
			this.receiver = receiver;
		}
	}
}
