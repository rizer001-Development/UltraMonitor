package com.ultramonitor.stress;

/**
 * A single stress workload (CPU, memory, disk or GPU). Started and stopped
 * from the UI thread but runs on its own worker threads. Implementations must
 * be safe to stop from any thread at any time.
 */
public interface StressTest extends AutoCloseable {

    /** Display name, e.g. "CPU". */
    String name();

    /** Short human-readable status, e.g. "16 threads" or "512 MB temp file". */
    String status();

    /** {@code true} while the workload is running. */
    boolean isRunning();

    /** Starts the workload; call only when not already running. */
    void start();

    /** Stops the workload and frees resources. Idempotent. */
    void stop();

    @Override
    default void close() {
        stop();
    }
}
