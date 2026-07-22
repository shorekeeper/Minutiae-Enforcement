/**
 * Network layer: proxy awareness and multi-server effect propagation.
 *
 * <p>The layer solves exactly one distributed problem: an enforcement effect
 * produced on one server instance must reach the subject wherever the subject
 * currently is. Everything else remains deliberately instance-local.
 *
 * <h2>Topology model</h2>
 * <p>A network is a set of server instances sharing one storage backend
 * behind a player-routing proxy. Each instance carries a stable identifier
 * ({@link org.synergyst.minutiae.net.ServerIdentity}) used for sanction
 * attribution, directive addressing, and cursor tracking. An instance with
 * networking disabled behaves exactly as a standalone server; every publish
 * operation degrades to a no-op.
 *
 * <h2>Directive bus</h2>
 * <p>Cross-server effects travel as directives: durable rows in the shared
 * backend, published by the originating instance and consumed by every other
 * instance through periodic polling. The design properties are:
 * <ul>
 *   <li><b>At-least-once, per-server ordered.</b> Each instance advances a
 *       persistent cursor over the monotonically increasing directive
 *       identifier; a crash between application and cursor persistence
 *       replays the tail, so every handler is idempotent by contract.</li>
 *   <li><b>TTL-bounded.</b> A directive expires after a configured span and
 *       is purged; an instance that was offline longer than the TTL misses
 *       the directive by design, because its join-time reconciliation path
 *       (behaviour reload on player join, connection gate at pre-login)
 *       re-derives the same state from the authoritative tables.</li>
 *   <li><b>Zero-dependency.</b> No message broker, no plugin channels. Plugin
 *       messaging is rejected because delivery is coupled to a player
 *       connection on both endpoints and is silently lossy; a broker is
 *       rejected as an operational dependency the storage backend already
 *       subsumes.</li>
 * </ul>
 *
 * <h2>Directive kinds</h2>
 * <p>{@code KICK} removes an online subject with a localised screen, used
 * when a connection-blocking sanction is issued on another instance.
 * {@code SYNC_BEHAVIOUR} rebuilds the subject's in-memory behavioural state
 * from the authoritative tables and reconciles concealment, used on
 * issuance, amendment, and lift of behaviour-carrying sanctions. Both
 * handlers are idempotent: applying either against an already-consistent
 * instance is a no-op.
 *
 * <h2>What stays instance-local, and why</h2>
 * <p>Complex-event state (recurrence windows, sequence partial matches),
 * automaton throttles, and self-mute sets are instance-local. Platform
 * events are local by nature; a cross-instance event sequence would require
 * distributed ordering guarantees the platform cannot provide, for a
 * semantics no moderation scenario demands. The access gate and the
 * behaviour reload on join are storage-backed and therefore already
 * network-consistent without directives.
 *
 * <h2>Proxy requirements</h2>
 * <p>Behind a proxy the backend must receive forwarded addresses and
 * profiles: Velocity modern forwarding, or BungeeCord {@code ip_forward}
 * with the matching Paper settings. Without forwarding every connection
 * presents the proxy address, which silently corrupts fingerprint signal
 * quality, and offline-mode profiles corrupt identity attribution.
 * {@link org.synergyst.minutiae.net.ForwardingSentinel} detects the
 * symptomatic pattern and reports it; it cannot repair a configuration
 * problem, only surface it.
 */
package org.synergyst.minutiae.net;