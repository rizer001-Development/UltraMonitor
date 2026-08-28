package com.ultramonitor.stress;

import oshi.SystemInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Allocates a configurable percentage of the currently available RAM, touches
 * every page so it is really committed, then keeps reading and writing the
 * buffers so memory bandwidth stays busy. Allocation runs on a worker thread so
 * the caller (the UI) never freezes while gigabytes are being reserved.
 *
 * <p>Stopping is safe at any time: if stop() fires while the allocator is still
 * filling buffers, the interrupted allocation is drained and released so no
 * chunk leaks (previously the partially-allocated array was dropped without a
 * reference, leaking potentially gigabytes at the worst possible moment).
 */
public final class MemoryStress implements StressTest {

    private static final int CHUNK_BYTES = 1 << 24; // 16 MB

    private final int percent;
    private final long explicitBytes;
    private volatile byte[][] buffers = new byte[0][];
    private volatile boolean running;
    private volatile long sink;
    private final List<Thread> workers = new ArrayList<>();
    private volatile Thread allocator;

    public MemoryStress(int percent) {
        this.percent = Math.max(5, Math.min(90, percent));
        this.explicitBytes = 0;
    }

    /** Test-friendly constructor with an explicit target size in bytes. */
    MemoryStress(long explicitBytes) {
        this.percent = 0;
        this.explicitBytes = Math.max(CHUNK_BYTES, explicitBytes);
    }

    @Override
    public String name() {
        return "RAM";
    }

    @Override
    public String status() {
        if (running && buffers.length == 0) {
            return "allocating…";
        }
        if (buffers.length > 0) {
            return (buffers.length * (long) CHUNK_BYTES / (1024L * 1024L * 1024L)) + " GB allocated";
        }
        return percent > 0 ? percent + "% of available RAM" : "memory buffers";
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void start() {
        running = true;
        Thread thread = new Thread(this::allocate, "ultramonitor-ram-alloc");
        thread.setDaemon(true);
        allocator = thread;
        thread.start();
    }

    private void allocate() {
        long target = explicitBytes > 0
                ? explicitBytes
                : new SystemInfo().getHardware().getMemory().getAvailable() * percent / 100L;
        int count = Math.max(1, (int) (target / CHUNK_BYTES));
        byte[][] allocated = new byte[count][];
        byte[][] kept = new byte[count][];
        int keptCount = 0;
        try {
            for (int i = 0; i < count && running; i++) {
                byte[] chunk = new byte[CHUNK_BYTES];
                java.util.Arrays.fill(chunk, (byte) i); // touch every page
                allocated[i] = chunk;
                kept[keptCount++] = chunk;
            }
        } catch (OutOfMemoryError oom) {
            // Stop filling; keep whatever was already committed so the stress can
            // continue with what we have instead of dying.
        } finally {
            if (!running || keptCount == 0) {
                // stop() was called mid-allocation (or nothing was allocated): drain
                // the temp array so partial chunks are released, publish an empty buffer.
                java.util.Arrays.fill(kept, 0, keptCount, null);
                buffers = new byte[0][];
            } else {
                byte[][] finalBuffers = new byte[keptCount][];
                System.arraycopy(kept, 0, finalBuffers, 0, keptCount);
                buffers = finalBuffers;
            }
        }
        if (keptCount > 0 && running) {
            // Scale churn threads with core count (up to 8) so memory bandwidth
            // is saturated, and partition the buffers so threads never fight
            // over the same cache lines.
            int churners = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
            final int total = Math.min(churners, keptCount);
            for (int t = 0; t < total; t++) {
                final int slice = t;
                Thread thread = new Thread(() -> churn(slice, total), "ultramonitor-ram-" + t);
                thread.setDaemon(true);
                thread.start();
                workers.add(thread);
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        if (allocator != null) {
            joinQuietly(allocator);
            allocator = null;
        }
        for (Thread worker : workers) {
            joinQuietly(worker);
        }
        workers.clear();
        byte[][] old = buffers;
        buffers = new byte[0][];
        // Null out references before handing back to GC; done on a daemon thread
        // so stop() never blocks the UI on a large release.
        if (old.length > 0) {
            Thread collector = new Thread(() -> {
                java.util.Arrays.fill(old, null);
            }, "ultramonitor-ram-release");
            collector.setDaemon(true);
            collector.start();
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(3000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void churn(int slice, int total) {
        long checksum = 0;
        while (running) {
            byte[][] buf = buffers;
            for (int b = slice; b < buf.length; b += total) {
                byte[] buffer = buf[b];
                if (buffer == null) {
                    continue;
                }
                for (int i = 0; i < buffer.length; i += 4096) {
                    checksum += buffer[i];
                    buffer[i] = (byte) (buffer[i] + 1);
                }
            }
        }
        sink = checksum;
    }
}