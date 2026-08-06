package com.ultramonitor.stress;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a set of {@link StressTest}s together and tracks elapsed time so the UI
 * can show overall progress against an optional duration. All methods are safe
 * to call from any thread.
 */
public final class StressRunner {

    private final List<StressTest> tests;
    private volatile boolean running;
    private volatile long startedNanos;
    private volatile long durationSeconds; // 0 = run until stopped
    private volatile String stopReason = "";

    public StressRunner(List<StressTest> tests) {
        this.tests = List.copyOf(tests);
    }

    /**
     * Starts all tests; returns {@code false} if already running. If any test
     * fails to start, the already-started ones are stopped again so the runner
     * never stays in a half-started state.
     */
    public synchronized boolean start(long durationSeconds) {
        if (running) {
            return false;
        }
        this.running = true;
        this.durationSeconds = Math.max(0, durationSeconds);
        this.startedNanos = System.nanoTime();
        this.stopReason = "";
        List<StressTest> started = new ArrayList<>();
        try {
            for (StressTest test : tests) {
                test.start();
                started.add(test);
            }
            return true;
        } catch (Throwable t) {
            for (StressTest test : started) {
                try {
                    test.stop();
                } catch (Throwable ignored) {
                    // best effort rollback
                }
            }
            running = false;
            stopReason = "Failed to start: " + t.getMessage();
            throw new IllegalStateException("Could not start stress test", t);
        }
    }

    /** Stops all tests; safe to call repeatedly. */
    public synchronized void stop(String reason) {
        if (!running) {
            return;
        }
        running = false;
        stopReason = reason == null || reason.isBlank() ? "Stopped" : reason;
        for (StressTest test : tests) {
            test.stop();
        }
    }

    public boolean isRunning() {
        return running;
    }

    public List<StressTest> tests() {
        return tests;
    }

    /** Seconds since start (0 when idle). */
    public long elapsedSeconds() {
        return running ? (System.nanoTime() - startedNanos) / 1_000_000_000L : 0;
    }

    /** Overall progress 0..1 against the duration, or NaN when unlimited. */
    public double progress() {
        if (durationSeconds <= 0) {
            return Double.NaN;
        }
        return Math.min(1.0, elapsedSeconds() / (double) durationSeconds);
    }

    /** True when the configured duration has elapsed and tests still run. */
    public boolean isFinishedByTime() {
        return running && durationSeconds > 0 && elapsedSeconds() >= durationSeconds;
    }

    public String stopReason() {
        return stopReason;
    }

    public boolean hasDuration() {
        return durationSeconds > 0;
    }

    public long durationSeconds() {
        return durationSeconds;
    }
}
