package org.synergyst.minutiae.command.dsl;

/**
 * A command execution handler.
 *
 * <p>Handlers return no value; command success is signalled uniformly by the
 * framework. A handler may throw: the framework catches any thrown exception,
 * reports a concise error line to the sender, and completes the command without
 * propagating the throwable into the server command dispatcher.
 */
@FunctionalInterface
public interface Handler {

    /**
     * Executes the command.
     *
     * @param ctx the execution context
     * @throws Exception to signal a handler-level failure; caught and reported
     *                   by the framework
     */
    void handle(Ctx ctx) throws Exception;
}