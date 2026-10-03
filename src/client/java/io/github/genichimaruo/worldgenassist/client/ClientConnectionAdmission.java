package io.github.genichimaruo.worldgenassist.client;

/** Identity fence. The worker serializes this fence with queue admission/revocation. */
final class ClientConnectionAdmission<C> {
	private C connection;
	private boolean suspended;
	synchronized void bind(C connection) { this.connection = connection; suspended = false; }
	synchronized boolean dispatch(C connection, Runnable admission) {
		if (this.connection != connection || suspended) return false;
		admission.run();
		return true;
	}
	synchronized boolean suspend(C connection) {
		if (this.connection != connection) return false;
		suspended = true;
		return true;
	}
	synchronized boolean resume(C connection) {
		if (this.connection != connection) return false;
		suspended = false;
		return true;
	}
	synchronized boolean suspended(C connection) { return this.connection == connection && suspended; }
	synchronized boolean clear(C connection) {
		if (this.connection != connection) return false;
		clear(); return true;
	}
	synchronized void clear() { connection = null; suspended = false; }
}
