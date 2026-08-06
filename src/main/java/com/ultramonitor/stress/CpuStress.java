package com.ultramonitor.stress;

import java.util.ArrayList;
import java.util.List;

/**
 * Pins a configurable number of threads to heavy floating-point work so each
 * logical processor runs at full load. The computation is written to resist
 * dead-code elimination by folding every result into a volatile sink.
 */
public final class CpuStress implements StressTest {

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
        double x = 0.1;
        while (running) {
            x = Math.sin(x) * 1.0000001 + Math.cos(x * 0.9999999);
            x = Math.sqrt(Math.abs(x)) + 0.0000001;
            x = Math.exp(Math.log(Math.abs(x) + 1.0));
            x = Math.fma(x, 1.0000000001, 0.0000000001);
            if (x > 1e9) {
                x = 0.1;
            }
        }
        sink = x;
    }
}
