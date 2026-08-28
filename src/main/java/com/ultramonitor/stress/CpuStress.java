package com.ultramonitor.stress;

import java.util.ArrayList;
import java.util.List;

/**
 * Pins a configurable number of threads to a rotating set of heavy workloads so
 * each logical processor runs at full load across all of its execution units:
 *
 * <ol>
 *   <li><b>Vector FMA pass</b> — an 8-way unrolled {@code Math.fma} loop over a
 *       per-thread buffer that fits in <em>L1</em> (32 KB), so it is FP-pipe
 *       bound, not memory-stalled. The JIT auto-vectorizes it to AVX2 / AVX-512.</li>
 *   <li><b>Transcendental chain</b> — sin/cos/sqrt/exp/log/tan, which cannot be
 *       vectorized, plus an occasional {@code pow()} — the most expensive FPU
 *       operation there is.</li>
 *   <li><b>Integer multiply chain</b> — a 64-bit multiply chain that cannot be
 *       parallelized, hammering the ALU and the 64-bit multiplier.</li>
 *   <li><b>Cache-buster walk</b> — a strided read-modify-write over a buffer far
 *       larger than L3, adding memory-bandwidth and TLB pressure.</li>
 * </ol>
 *
 * Every result is folded into a volatile sink to defeat dead-code elimination.
 */
public final class CpuStress implements StressTest {

    /** Double-array words for the FMA pass: 32 KB fits entirely in L1. */
    private static final int FMA_WORDS = 4096;
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
        // Thread-local buffers: the FMA pass must live in L1, the walker must miss L3.
        double[] fma = new double[FMA_WORDS];
        java.util.Arrays.fill(fma, 1.0000001);
        byte[] walk = new byte[WALK_BYTES];

        // Four independent FP accumulators to expose instruction-level parallelism.
        double a = 0.5, b = 0.7, c = 0.9, d = 0.3;
        // 64-bit integer chain (Knuth's multiplicative LCG constants).
        long acc = 0x9E3779B97F4A7C15L;
        long counter = 0;
        int walkPos = 0;
        final double k = 1.0000000001;
        final double e = 1e-12;

        while (running) {
            // 1) L1-resident FMA pass, 8-way unrolled → auto-vectorized to AVX2.
            for (int i = 0; i < FMA_WORDS; i += 8) {
                fma[i]     = Math.fma(fma[i],     k, e);
                fma[i + 1] = Math.fma(fma[i + 1], k, e);
                fma[i + 2] = Math.fma(fma[i + 2], k, e);
                fma[i + 3] = Math.fma(fma[i + 3], k, e);
                fma[i + 4] = Math.fma(fma[i + 4], k, e);
                fma[i + 5] = Math.fma(fma[i + 5], k, e);
                fma[i + 6] = Math.fma(fma[i + 6], k, e);
                fma[i + 7] = Math.fma(fma[i + 7], k, e);
            }
            a = fma[0];

            // 2) Scalar transcendental chain — FPU-transcendental saturation.
            for (int i = 0; i < 128; i++) {
                a = Math.sin(a) * 1.0000001 + Math.cos(a * 0.9999999);
                b = Math.sqrt(Math.abs(b)) + 1e-9;
                c = Math.exp(Math.log(Math.abs(c) + 1.0));
                d = Math.tan(d * 0.5) * 0.5 + 0.5;
                a = Math.fma(a, k, e);
            }
            if ((counter & 255) == 0) {
                a = Math.pow(Math.abs(a) + 1.0, 1.000000001);
            }

            // 3) Serial 64-bit multiply chain — ALU + multiplier latency.
            for (int i = 0; i < 64; i++) {
                acc = acc * 6364136223846793005L + 1442695040888963407L;
            }

            // 4) Cache-buster: 64-byte-strided walk misses every cache line,
            //    hammering memory bandwidth and the TLB.
            walkPos = (walkPos + 4096) & (WALK_BYTES - 1);
            for (int i = 0; i < 128; i++) {
                int idx = (walkPos + i * 64) & (WALK_BYTES - 1);
                walk[idx] = (byte) (walk[idx] + 1);
            }

            if ((++counter & 15) == 0) {
                sink = a + b + c + d + acc + walk[walkPos];
            }
        }
        sink = a + b + c + d + acc + walk[walkPos];
    }
}
