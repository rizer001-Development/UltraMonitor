package com.ultramonitor.stress;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pushes the disk with a mix of parallel sequential writes, sequential reads
 * and random-access I/O on a temporary file of a configurable size:
 *
 * <ul>
 *   <li>two writer threads issuing large (4 MB) sequential writes with periodic
 *       {@code force()} so data really reaches the disk, not just page cache;</li>
 *   <li>two reader threads streaming sequential reads;</li>
 *   <li>one random-I/O thread doing 64 KB scattered read/write pairs to stress
 *       seek latency, the drive's queue and the filesystem journal.</li>
 * </ul>
 *
 * The file lives in the system temp directory and is deleted on stop — even on
 * early interruption.
 */
public final class DiskStress implements StressTest {

    private static final int BLOCK_BYTES = 4 << 20;      // 4 MB
    private static final int RANDOM_CHUNK_BYTES = 64 << 10; // 64 KB
    private static final int IO_PAIRS = 2;

    private final long sizeBytes;
    private final long sizeMb;
    private volatile boolean running;
    private volatile Path tempFile;
    private volatile long sink;
    private final List<Thread> workers = new ArrayList<>();

    public DiskStress(long sizeMb) {
        this.sizeMb = Math.max(64, sizeMb);
        this.sizeBytes = this.sizeMb * 1024L * 1024L;
    }

    @Override
    public String name() {
        return "Disk";
    }

    @Override
    public String status() {
        return sizeMb + " MB temp file";
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void start() {
        running = true;
        try {
            tempFile = Files.createTempFile("ultramonitor-disk-", ".bin");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create temp file for disk test", e);
        }
        for (int i = 0; i < IO_PAIRS; i++) {
            spawn("ultramonitor-disk-w" + i, this::writeLoop);
            spawn("ultramonitor-disk-r" + i, this::readLoop);
        }
        spawn("ultramonitor-disk-rnd", this::randomIo);
    }

    private void spawn(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
        workers.add(thread);
    }

    @Override
    public void stop() {
        running = false;
        for (Thread worker : workers) {
            try {
                worker.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        workers.clear();
        Path file = tempFile;
        if (file != null) {
            // On Windows a file still open by a slow worker cannot be deleted;
            // retry briefly after the workers have closed their channels.
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    if (Files.deleteIfExists(file)) {
                        break;
                    }
                } catch (IOException ignored) {
                    // try again below
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            tempFile = null;
        }
    }

    private void writeLoop() {
        byte[] block = new byte[BLOCK_BYTES];
        new Random(42).nextBytes(block);
        long written = 0;
        long passes = 0;
        try (FileChannel channel = FileChannel.open(tempFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(block);
            while (running) {
                buffer.rewind();
                long position = written % sizeBytes;
                channel.write(buffer, position);
                written += block.length;
                if (position + block.length >= sizeBytes) {
                    passes++;
                    // Flush data+metadata to disk every 4th full pass so the
                    // controller and storage really are exercised, not just the
                    // OS page cache.
                    channel.force((passes & 3) == 0);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Disk test failed", e);
        }
        sink = written;
    }

    private void readLoop() {
        byte[] block = new byte[BLOCK_BYTES];
        long read = 0;
        try (FileChannel channel = FileChannel.open(tempFile,
                StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.wrap(block);
            while (running) {
                buffer.rewind();
                long position = read % sizeBytes;
                channel.read(buffer, position);
                read += block.length;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Disk test failed", e);
        }
        sink = read;
    }

    private void randomIo() {
        byte[] chunk = new byte[RANDOM_CHUNK_BYTES];
        new Random(7).nextBytes(chunk);
        long ops = 0;
        try (FileChannel channel = FileChannel.open(tempFile,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(chunk);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            while (running) {
                long maxPos = Math.max(1, sizeBytes - RANDOM_CHUNK_BYTES);
                long position = (random.nextLong() & Long.MAX_VALUE) % maxPos;
                buffer.rewind();
                if ((ops & 1) == 0) {
                    channel.write(buffer, position);
                } else {
                    channel.read(buffer, position);
                }
                ops++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Disk test failed", e);
        }
        sink = ops;
    }
}
