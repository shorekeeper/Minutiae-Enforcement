package org.synergyst.minutiae.resolve;

import org.synergyst.minutiae.message.Arg;
import org.synergyst.minutiae.message.MessageKey;

/**
 * Raised when a syntactically valid command cannot be resolved into a coherent
 * sanction.
 *
 * <p>A resolve failure carries a message key and its arguments, so the command
 * boundary renders it through the localisation service in the sender's locale.
 * A raw-string form is retained for diagnostics that pass through an underlying
 * validator message which is not itself keyed.
 */
public final class ResolveException extends RuntimeException {

    private final MessageKey key;
    private final Arg[] args;

    public ResolveException(final MessageKey key, final Arg... args) {
        this.key = key;
        this.args = args;
    }

    public ResolveException(final String raw) {
        super(raw);
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