/**
 * Dispatch layer binding compiled unit plans to live platform events.
 *
 * <p>The layer owns four concerns and nothing else:
 * <ul>
 *   <li><b>Loading</b> - {@link org.synergyst.minutiae.dispatch.AlamService}
 *       scans the definition directory, compiles every source through the
 *       full planning pipeline, reports diagnostics, and publishes the
 *       verified plans and stamped layout definitions;</li>
 *   <li><b>Gating</b> - {@link org.synergyst.minutiae.dispatch.DispatchEngine}
 *       decides whether an armed rule fires against an incoming event,
 *       driving the recurrence window, the sequence tracker with per-step
 *       fact capture, the per-automaton throttle, and the self-mute set;</li>
 *   <li><b>Firing</b> - a passing rule's verdict is evaluated to a sanction
 *       descriptor, materialised onto the command surface, and driven through
 *       the shared resolver and executor, so an automatically issued sanction
 *       traverses the identical validation and application path as a manual
 *       command;</li>
 *   <li><b>Inspection</b> - {@link org.synergyst.minutiae.dispatch.AlamCommand}
 *       enumerates armed rules and simulates synthetic events in forced
 *       dry-run without consuming any tracking state.</li>
 * </ul>
 *
 * <p>Failure posture: a guard evaluation fault is absorbed as an unsatisfied
 * guard; a verdict evaluation fault is absorbed as a non-firing with a
 * logged warning; a resolver rejection is reported to the issuing sender and
 * suppressed. No throwable of this layer propagates into a platform event
 * handler.
 */
package org.synergyst.minutiae.dispatch;