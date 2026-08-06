package com.ultramonitor.stress;

import oshi.SystemInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Allocates a configurable percentage of the currently available RAM, touches
 * every page so it is really committed, then keeps reading and writing the
 * buffers so memory bandwidth stays busy. Allocation runs on a worker thread so
 * the caller (the UI) never freezes while gigabytes are being reserved.
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
        allocator = new Thread(this::allocate, "ultramonitor-ram-alloc");
        allocator.setDaemon(true);
        allocator.start();
    }

    private void allocate() {
        long target = explicitBytes > 0
                ? explicitBytes
                : new SystemInfo().getHardware().getMemory().getAvailable() * percent / 100L;
        int count = (int) Math.max(1, target / CHUNK_BYTES);
        byte[][] allocated = new byte[count][];
        for (int i = 0; i < count && running; i++) {
            allocated[i] = new byte[CHUNK_BYTES];
            java.util.Arrays.fill(allocated[i], (byte) i); // touch every page
        }
        buffers = allocated;
        for (int i = 0; i < Math.min(4, count); i++) {
            Thread thread = new Thread(this::churn, "ultramonitor-ram-" + i);
            thread.setDaemon(true);
            thread.start();
            workers.add(thread);
        }
    }

    @Override
    public void stop() {
        running = false;
        for (Thread worker : workers) {
            joinQuietly(worker);
        }
        workers.clear();
        Thread allocation = allocator;
        if (allocation != null) {
            joinQuietly(allocation);
            allocator = null;
        }
        buffers = new byte[0][];
        // Let the JVM reclaim the buffers without blocking the caller.
        Thread collector = new Thread(() -> System.gc(), "ultramonitor-ram-gc");
        collector.setDaemon(true);
        collector.start();
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void churn() {
        long checksum = 0;
        while (running) {
            for (byte[] buffer : buffers) {
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
