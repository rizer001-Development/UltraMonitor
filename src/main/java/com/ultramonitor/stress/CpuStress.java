package com.ultramonitor.stress;

import java.util.ArrayList;
import java.util.List;

/**
 * Pins a configurable number of threads to a rotating set of heavy workloads so
 * each logical processor runs at full load across all of its execution units:
 *
 * <ol>
 *   <li><b>Vector FMA pass</b> — a tight {@code Math.fma} loop over a per-thread
 *       buffer that fits in L2. The JIT auto-vectorizes it to AVX2, saturating
 *       the floating-point pipes.</li>
 *   <li><b>Transcendental chain</b> — sin/cos/sqrt/exp/log, which cannot be
 *       vectorized and hammer the FPU's transcendental units.</li>
 *   <li><b>Cache-buster walk</b> — a strided read-modify-write over a buffer far
 *       larger than L3, adding memory-bandwidth and TLB pressure.</li>
 * </ol>
 *
 * Every result is folded into a volatile sink to defeat dead-code elimination.
 */
public final class CpuStress implements StressTest {

    /** Double-array words for the vector pass: 256 KB fits comfortably in L2. */
    private static final int VECTOR_WORDS = 1 << 15; // 32_768 doubles = 256 KB
    /** Cache-buster buffer size: far beyond any L3, 8 MB per thread. */
    private static final int WALK_BYTES = 8 << 20;

    private final int threads;
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running;
    private volatile double sink;

    public CpuStress(int threads) {
        this.threads = Math.max(1, threads);
    }

    @Override
    public String name() {
        return "CPU";
    }

    @Override
    public String status() {
        return threads + " thread" + (threads == 1 ? "" : "s");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void start() {
        running = true;
        for (int i = 0; i < threads; i++) {
            Thread thread = new Thread(this::burn, "ultramonitor-cpu-" + i);
            thread.setDaemon(true);
            thread.start();
            workers.add(thread);
        }
    }

    @Override
    public void stop() {
        running = false;
        for (Thread worker : workers) {
            try {
                worker.join(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        workers.clear();
    }

    private void burn() {
        // Thread-local buffers: vector pass needs L2 residency, the walker must miss L3.
        double[] vector = new double[VECTOR_WORDS];
        java.util.Arrays.fill(vector, 1.0000001);
        byte[] walk = new byte[WALK_BYTES];
        double acc = 0.5;
        long counter = 0;
        int walkPos = 0;
        while (running) {
            // 1) Vectorized FMA pass — auto-vectorized to AVX2 by the JIT.
            for (int i = 0; i < VECTOR_WORDS; i++) {
                vector[i] = Math.fma(vector[i], 1.0000001, 1e-9);
            }
            acc = vector[0];

            // 2) Scalar transcendental chain — FPU-transcendental saturation.
            for (int i = 0; i < 64; i++) {
                acc = Math.sin(acc) * 1.0000001 + Math.cos(acc * 0.9999999);
                acc = Math.sqrt(Math.abs(acc)) + 1e-9;
                acc = Math.exp(Math.log(Math.abs(acc) + 1.0));
                acc = Math.fma(acc, 1.0000000001, 1e-12);
            }

            // 3) Cache-buster: a 64-byte-strided walk misses every cache line,
            //    hammering memory bandwidth and the TLB.
            walkPos = (walkPos + 4096) & (WALK_BYTES - 1);
            for (int i = 0; i < 256; i++) {
                int idx = (walkPos + i * 64) & (WALK_BYTES - 1);
                walk[idx] = (byte) (walk[idx] + 1);
            }

            if ((++counter & 15) == 0) {
                sink = acc + counter + walk[walkPos];
            }
        }
        sink = acc + walk[walkPos];
    }
}
