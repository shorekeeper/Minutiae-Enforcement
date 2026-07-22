package org.synergyst.minutiae.storage;

/**
 * Unchecked wrapper for storage-layer failures.
 *
 * <p>Raised for any condition that prevents a storage operation from completing
 * correctly, including connection acquisition failure, migration failure, and
 * SQL execution errors. The originating {@link Throwable}, when present, is
 * retained as the cause.
 */
public final class StorageException extends RuntimeException {

    public StorageException(final String message) {
        super(message);
    }

    public StorageException(final String message, final Throwable cause) {
        super(message, cause);
    }
}