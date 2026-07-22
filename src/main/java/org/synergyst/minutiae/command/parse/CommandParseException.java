package org.synergyst.minutiae.command.parse;

import org.synergyst.minutiae.message.Arg;
import org.synergyst.minutiae.message.MessageKey;

/**
 * Raised when raw command input violates the ban command surface grammar.
 *
 * <p>A parse failure carries a message key and its arguments, so the command
 * boundary renders it through the localisation service in the sender's locale.
 * The exception message is set to the key path, giving non-localised consumers
 * (the web panel, logs) a stable machine-readable identifier. A raw-string
 * form is retained for diagnostics originating in nested validators that are
 * not themselves keyed.
 *
 * <p>Parse failures are recoverable at the command boundary: the handler
 * reports the message to the sender and completes without further effect.
 */
public final class CommandParseException extends RuntimeException {

    private final MessageKey key;
    private final Arg[] args;

    public CommandParseException(final MessageKey key, final Arg... args) {
        super(key.path());
        this.key = key;
        this.args = args;
    }

    public CommandParseException(final String message) {
        super(message);
        this.key = null;
        this.args = new Arg[0];
    }

    /** Returns the message key, or null when this is a raw-string failure. */
    public MessageKey key() {
        return key;
    }

    /** Returns the message arguments. */
    public Arg[] args() {
        return args;
    }

    /** Reports whether this failure carries a message key. */
    public boolean hasKey() {
        return key != null;
    }
}