package org.synergyst.minutiae.storage.sqlite;

import org.synergyst.minutiae.config.StorageConfig;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.storage.StorageException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fixed-capacity JDBC connection pool for a single SQLite database file.
 *
 * <p>The pool eagerly opens all connections at construction and never grows
 * beyond {@code maxConnections}. Idle connections are held in a stack; the most
 * recently released connection is reused first to maximise driver-side cache
 * locality. Borrow requests that cannot be satisfied within the configured
 * acquisition timeout fail with a {@link StorageException} rather than blocking
 * indefinitely.
 *
 * <p>Each connection is opened in WAL journal mode with {@code synchronous}
 * set to NORMAL, foreign-key enforcement enabled, and a per-connection
 * {@code busy_timeout}. WAL permits concurrent readers alongside a single
 * writer; write serialisation is handled internally by SQLite and bounded by
 * the busy timeout.
 *
 * <p>Access to the internal free-list is guarded by a single lock. The pool is
 * safe for concurrent borrow and release.
 */
public final class ConnectionPool implements AutoCloseable {

    private final KernelLogger log;
    private final StorageConfig config;
    private final String jdbcUrl;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final ArrayDeque<Connection> free;
    private final Connection[] all;

    private boolean closed;

    /**
     * Opens the pool and eagerly establishes all physical connections.
     *
     * @param log     diagnostic logger
     * @param config  storage configuration providing capacity and timeouts
     * @param jdbcUrl fully-qualified SQLite JDBC URL
     * @throws StorageException if any connection cannot be established
     */
    public ConnectionPool(final KernelLogger log, final StorageConfig config, final String jdbcUrl) {
        this.log = log;
        this.config = config;
        this.jdbcUrl = jdbcUrl;
        this.free = new ArrayDeque<>(config.maxConnections());
        this.all = new Connection[config.maxConnections()];

        log.trace("pool", "opening %d connection(s) to %s", config.maxConnections(), jdbcUrl);
        for (int i = 0; i < config.maxConnections(); i++) {
            final Connection connection = open();
            all[i] = connection;
            free.push(connection);
        }
        log.info("pool", "connection pool ready (capacity=%d)", config.maxConnections());
    }

    private Connection open() {
        try {
            final Properties props = new Properties();
            final Connection connection = DriverManager.getConnection(jdbcUrl, props);
            connection.setAutoCommit(true);
            try (final Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA busy_timeout=" + config.busyTimeoutMs());
            }
            return connection;
        } catch (final SQLException e) {
            throw new StorageException("failed to open SQLite connection", e);
        }
    }

    /**
     * Borrows a connection from the pool, waiting up to the configured
     * acquisition timeout for one to become available.
     *
     * @return a pooled connection; must be returned via {@link #release(Connection)}
     * @throws StorageException if the pool is closed or the timeout elapses
     */
    public Connection borrow() {
        lock.lock();
        try {
            long remaining = TimeUnit.MILLISECONDS.toNanos(config.acquireTimeoutMs());
            while (free.isEmpty()) {
                if (closed) {
                    throw new StorageException("connection pool is closed");
                }
                if (remaining <= 0L) {
                    throw new StorageException("connection acquisition timed out after "
                            + config.acquireTimeoutMs() + "ms");
                }
                remaining = available.awaitNanos(remaining);
            }
            return free.pop();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException("interrupted while acquiring connection", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a connection to the pool and signals one waiting borrower.
     *
     * @param connection a connection previously obtained from {@link #borrow()}
     */
    public void release(final Connection connection) {
        if (connection == null) {
            return;
        }
        lock.lock();
        try {
            if (closed) {
                closeQuietly(connection);
                return;
            }
            free.push(connection);
            available.signal();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            available.signalAll();
        } finally {
            lock.unlock();
        }
        for (final Connection connection : all) {
            closeQuietly(connection);
        }
        log.trace("pool", "all connections closed");
    }

    private void closeQuietly(final Connection connection) {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (final SQLException e) {
            log.warn("pool", "connection close failed: %s", e.getMessage());
        }
    }
}