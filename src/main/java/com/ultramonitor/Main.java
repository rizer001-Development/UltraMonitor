package com.ultramonitor;

import com.ultramonitor.monitoring.InfoEntry;
import com.ultramonitor.monitoring.MetricsCollector;
import com.ultramonitor.monitoring.OshiMetricsProvider;
import com.ultramonitor.monitoring.SensorReading;
import com.ultramonitor.monitoring.SensorRow;
import com.ultramonitor.stress.CpuStress;
import com.ultramonitor.stress.DiskStress;
import com.ultramonitor.stress.GpuStress;
import com.ultramonitor.stress.MemoryStress;
import com.ultramonitor.stress.StressReporter;
import com.ultramonitor.stress.StressRunner;
import com.ultramonitor.stress.StressTest;
import javafx.application.Application;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * UltraMonitor — a portable hardware monitoring & stress testing utility.
 *
 * <p>A plain launcher (not extending {@link Application}) so the portable
 * single-jar distribution runs with JavaFX on the classpath.</p>
 *
 * <ul>
 *   <li>{@code --selftest} — headless hardware probe for CI / smoke tests.</li>
 *   <li>{@code stress [--cpu] [--ram] [--disk] [--gpu] [--duration SEC]
 *       [--report FILE.csv] [--temp-limit C]}
 *       — headless stress run, prints a summary and optionally writes a CSV.</li>
 * </ul>
 */
public final class Main {

    private static final double DEFAULT_TEMP_LIMIT_C = 90.0;

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            Application.launch(GuiApp.class, args);
            return;
        }
        switch (args[0]) {
            case "--selftest" -> System.exit(runSelfTest() ? 0 : 1);
            case "stress", "--stress" -> System.exit(runStressCli(args) ? 0 : 1);
            default -> Application.launch(GuiApp.class, args);
        }
    }

    // ------------------------------------------------------------ CLI probe --

    private static boolean runSelfTest() {
        var out = new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                java.nio.charset.StandardCharsets.UTF_8);
        out.println("UltraMonitor self-test");
        out.println("Java: " + System.getProperty("java.version"));
        try (MetricsCollector collector = new OshiMetricsProvider()) {
            collector.sample(); // prime rate counters and tick counters
            Thread.sleep(750);
            List<SensorReading> readings = collector.sample();
            out.println("Sensors: " + readings.size());
            for (SensorReading reading : readings) {
                String value = reading.available()
                        ? SensorRow.format(reading.value()) + " " + reading.unit()
                        : "n/a";
                out.printf(Locale.ROOT, "  %-28s %s%n", reading.name(), value);
            }
            List<InfoEntry> sysInfo = collector.systemInfo();
            out.println("System info entries: " + sysInfo.size());
            int shown = 0;
            for (InfoEntry entry : sysInfo) {
                if (shown >= 6) {
                    break;
                }
                out.printf(Locale.ROOT, "  [%s] %-22s %s%n", entry.section(), entry.label(), entry.value());
                shown++;
            }
            return true;
        } catch (Throwable t) {
            System.err.println("Self-test failed: " + t);
            t.printStackTrace();
            return false;
        }
    }

    // ------------------------------------------------------------ CLI stress --

    private static boolean runStressCli(String[] rawArgs) {
        // Parse flags.
        boolean cpu = false, ram = false, disk = false, gpu = false;
        long duration = 0;          // 0 = run until stopped; CLI stops on limit
        Path report = null;
        double tempLimit = DEFAULT_TEMP_LIMIT_C;

        List<String> args = new ArrayList<>(List.of(rawArgs));
        for (int i = 1; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--cpu" -> cpu = true;
                case "--ram", "--memory" -> ram = true;
                case "--disk" -> disk = true;
                case "--gpu" -> gpu = true;
                case "--duration" -> { if (i + 1 < args.size()) duration = parseLong(args.get(++i), 0); }
                case "--report" -> { if (i + 1 < args.size()) report = Paths.get(args.get(++i)); }
                case "--temp-limit" -> { if (i + 1 < args.size()) tempLimit = parseDouble(args.get(++i), DEFAULT_TEMP_LIMIT_C); }
                case "--help", "-h" -> { printStressHelp(); return true; }
                default -> {
                    System.err.println("Unknown stress flag: " + args.get(i));
                    printStressHelp();
                    return false;
                }
            }
        }

        if (!cpu && !ram && !disk && !gpu) {
            System.err.println("Select at least one test: --cpu, --ram, --disk, --gpu");
            printStressHelp();
            return false;
        }

        List<StressTest> tests = new ArrayList<>();
        if (cpu) tests.add(new CpuStress(Runtime.getRuntime().availableProcessors()));
        if (ram) tests.add(new MemoryStress(50));
        if (disk) tests.add(new DiskStress(512));
        if (gpu) tests.add(new GpuStress());

        System.out.println("=== UltraMonitor stress run ===");
        System.out.println("Tests: " + String.join(" + ", tests.stream().map(StressTest::name).toList()));
        System.out.println("Duration: " + (duration > 0 ? duration + " s" : "until temperature limit"));
        System.out.println("Temp limit: " + String.format(Locale.ROOT, "%.0f °C", tempLimit));

        StressReporter reporter = new StressReporter();
        reporter.start();

        try (MetricsCollector collector = new OshiMetricsProvider()) {
            StressRunner runner = new StressRunner(tests);
            runner.start(duration > 0 ? duration : 0);

            long tickIntervalMs = 500;
            long deadlineNanos = duration > 0
                    ? System.nanoTime() + duration * 1_000_000_000L
                    : Long.MAX_VALUE;
            boolean timedOut = false;
            boolean tempStopped = false;
            String reason = null;

            while (runner.isRunning()) {
                // Sample sensors.
                double cpuLoad = Double.NaN, ramLoad = Double.NaN, temp = Double.NaN;
                try {
                    for (SensorReading r : collector.sample()) {
                        switch (r.key()) {
                            case "cpu.load" -> cpuLoad = r.value();
                            case "ram.load" -> ramLoad = r.value();
                            case "cpu.temp" -> temp = r.value();
                            default -> { }
                        }
                    }
                } catch (Throwable ignored) {
                    // keep last values
                }
                reporter.tick(cpuLoad, ramLoad, temp);

                if (temp >= 0 && temp >= tempLimit) {
                    tempStopped = true;
                    reason = "CPU temperature " + String.format(Locale.ROOT, "%.1f", temp)
                            + " °C >= limit " + String.format(Locale.ROOT, "%.0f", tempLimit)
                            + " °C — stopped automatically";
                    runner.stop(reason);
                    break;
                }
                if (duration > 0 && System.nanoTime() >= deadlineNanos) {
                    timedOut = true;
                    reason = "Duration reached (" + duration + " s)";
                    runner.stop(reason);
                    break;
                }

                Thread.sleep(tickIntervalMs);
            }

            if (!timedOut && !tempStopped && reason == null) {
                reason = runner.stopReason();
            }

            // Print summary.
            System.out.println();
            System.out.println("--- Summary ---");
            System.out.printf(Locale.ROOT, "Ran for ~%d s  |  %s%n",
                    runner.elapsedSeconds(), reason == null || reason.isBlank() ? "finished" : reason);
            for (String line : reporter.summary(runner.elapsedSeconds())) {
                System.out.println(line);
            }

            // Optional CSV.
            if (report != null) {
                boolean ok = reporter.writeCsv(report,
                        reason == null || reason.isBlank() ? null : reason);
                System.out.println(ok ? "Report written: " + report.toAbsolutePath()
                        : "Failed to write report: " + report.toAbsolutePath());
            }
            return true;
        } catch (Throwable t) {
            System.err.println("Stress run failed: " + t);
            t.printStackTrace();
            // Make sure tests are stopped even on unexpected errors.
            for (StressTest test : tests) {
                try { test.stop(); } catch (Throwable ignored) { }
            }
            return false;
        }
    }

    private static void printStressHelp() {
        System.out.println();
        System.out.println("UltraMonitor stress — headless hardware stress run");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar UltraMonitor.jar stress [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --cpu               run CPU load test");
        System.out.println("  --ram               run RAM load test");
        System.out.println("  --disk              run disk load test");
        System.out.println("  --gpu               run GPU load test");
        System.out.println("  --duration SEC      stop after SEC seconds (default: until temp limit)");
        System.out.println("  --report FILE.csv   write a CSV report (elapsed,cpu%,ram%,tempC)");
        System.out.println("  --temp-limit C      auto-stop temperature in °C (default: 90)");
        System.out.println("  --help, -h          show this help");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java -jar UltraMonitor.jar stress --cpu --ram --duration 60 --report stress.csv");
    }

    private static long parseLong(String value, long def) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double parseDouble(String value, double def) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}