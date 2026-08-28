package com.ultramonitor.stress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Collects per-tick CPU/RAM/temperature samples during a stress run and can
 * render a CSV report plus a human-readable summary. Shared by the GUI (report
 * button) and the CLI (stress mode).
 *
 * <p>Thread-safe: lazily initialized timer buckets, samples appended from any
 * tick thread, report written at the end.</p>
 */
public final class StressReporter {

    /** One sample row. */
    private record Sample(double cpuLoad, double ramLoad, double temp) {}

    private volatile long startEpochMillis;
    private final List<Sample> samples = new ArrayList<>();
    private double minTemp = Double.NaN;
    private double maxTemp = Double.NaN;
    private double sumCpu = 0;
    private long cpuCount = 0;
    private double sumRam = 0;
    private long ramCount = 0;

    public void start() {
        this.startEpochMillis = System.currentTimeMillis();
        synchronized (samples) {
            samples.clear();
        }
        minTemp = Double.NaN;
        maxTemp = Double.NaN;
        sumCpu = 0;
        cpuCount = 0;
        sumRam = 0;
        ramCount = 0;
    }

    /** Clears all recorded samples and resets the clock; same as {@link #start()}. */
    public void reset() {
        start();
    }

    /** Adds a tick sample. NaN values are skipped for stats but kept as blanks. */
    public synchronized void tick(double cpuLoad, double ramLoad, double temp) {
        Sample sample = new Sample(cpuLoad, ramLoad, temp);
        synchronized (samples) {
            samples.add(sample);
        }
        if (!Double.isNaN(cpuLoad)) { sumCpu += cpuLoad; cpuCount++; }
        if (!Double.isNaN(ramLoad)) { sumRam += ramLoad; ramCount++; }
        if (!Double.isNaN(temp) && temp > 0) {
            if (Double.isNaN(minTemp) || temp < minTemp) minTemp = temp;
            if (Double.isNaN(maxTemp) || temp > maxTemp) maxTemp = temp;
        }
    }

    public int sampleCount() {
        synchronized (samples) {
            return samples.size();
        }
    }

    /** Peak CPU temperature reached during the run, or NaN if none read. */
    public double peakTemp() {
        return maxTemp;
    }

    /** Average CPU load, or NaN. */
    public double avgCpuLoad() {
        return cpuCount > 0 ? sumCpu / cpuCount : Double.NaN;
    }

    /** Average RAM load, or NaN. */
    public double avgRamLoad() {
        return ramCount > 0 ? sumRam / ramCount : Double.NaN;
    }

    /**
     * Writes a CSV report of the run to {@code target}. Columns:
     * {@code elapsed_seconds,cpu_load_pct,ram_load_pct,cpu_temp_c}.
     *
     * @param stopReason optional note describing how the run ended, or {@code null}
     * @return {@code true} on success
     */
    public synchronized boolean writeCsv(Path target, String stopReason) {
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            StringBuilder sb = new StringBuilder();
            if (stopReason != null && !stopReason.isBlank()) {
                String reason = stopReason.replace("\"", "\"\"");
                sb.append("stop_reason,\"").append(reason).append("\"\n");
            }
            sb.append("elapsed_seconds,cpu_load_pct,ram_load_pct,cpu_temp_c\n");
            List<Sample> copy;
            synchronized (samples) {
                copy = new ArrayList<>(samples);
            }
            long startVal = startEpochMillis;
            for (Sample s : copy) {
                double elapsed = (double) (System.currentTimeMillis() - startVal) / 1000.0;
                sb.append(fmt3(elapsed)).append(',')
                        .append(fmt2(s.cpuLoad())).append(',')
                        .append(fmt2(s.ramLoad())).append(',')
                        .append(fmt2(s.temp())).append('\n');
            }
            Files.writeString(target, sb.toString());
            return true;
        } catch (IOException e) {
            System.err.println("[StressReporter] CSV write failed: " + e.getMessage());
            return false;
        }
    }

    /** Short human-readable summary lines, e.g. for CLI stdout. */
    public List<String> summary(long durationSeconds) {
        List<String> lines = new ArrayList<>();
        lines.add("Samples: " + sampleCount() + " over " + durationSeconds + " s");
        lines.add(String.format(Locale.ROOT, "Average CPU load: %.1f%%", orDash(avgCpuLoad())));
        lines.add(String.format(Locale.ROOT, "Average RAM load: %.1f%%", orDash(avgRamLoad())));
        lines.add(String.format(Locale.ROOT, "CPU temperature: peak %.1f °C", orDash(maxTemp)));
        return lines;
    }

    private static double orDash(double value) {
        return value;
    }

    private static String fmt3(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%.3f", v);
    }

    private static String fmt2(double v) {
        return Double.isNaN(v) || v < 0 ? "" : String.format(Locale.ROOT, "%.2f", v);
    }
}