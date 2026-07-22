package org.synergyst.minutiae.net;

import java.util.UUID;

/**
 * Publication surface of the network layer.
 *
 * <p>Publishers fire and forget: publication is asynchronous, failures are
 * logged by the implementation, and no caller blocks on delivery. The
 * {@link #NOOP} instance backs standalone deployments so that call sites
 * carry no conditional logic.
 */
public interface NetworkBus {

    /** The inert bus of a standalone deployment. */
    NetworkBus NOOP = new NetworkBus() {
        @Override
        public void publishKick(final UUID subject, final String reason) {
        }

        @Override
        public void publishBehaviourSync(final UUID subject) {
        }
    };

    /**
     * Requests removal of the subject on every other instance.
     *
     * @param subject the subject account
     * @param reason  the display reason carried to the kick screen
     */
    void publishKick(UUID subject, String reason);

    /**
     * Requests behavioural-state reconciliation of the subject on every
     * other instance.
     *
     * @param subject the subject account
     */
    void publishBehaviourSync(UUID subject);
}