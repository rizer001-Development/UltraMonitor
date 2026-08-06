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

/**
 * Pushes the disk with sequential writes and reads on a temporary file of a
 * configurable size. The file lives in the system temp directory and is
 * deleted on stop — even on early interruption.
 */
public final class DiskStress implements StressTest {

    private static final int BLOCK_BYTES = 1 << 20; // 1 MB

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
        Thread writer = new Thread(this::writeLoop, "ultramonitor-disk-w");
        writer.setDaemon(true);
        writer.start();
        workers.add(writer);
        Thread reader = new Thread(this::readLoop, "ultramonitor-disk-r");
        reader.setDaemon(true);
        reader.start();
        workers.add(reader);
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
        try (FileChannel channel = FileChannel.open(tempFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(block);
            while (running) {
                buffer.rewind();
                long position = written % sizeBytes;
                channel.write(buffer, position);
                written += block.length;
                if (position + block.length >= sizeBytes) {
                    channel.force(true); // make sure data really hits the disk
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
}
