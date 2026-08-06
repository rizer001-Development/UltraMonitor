package com.ultramonitor;

import com.ultramonitor.monitoring.InfoEntry;
import com.ultramonitor.monitoring.MetricsCollector;
import com.ultramonitor.monitoring.OshiMetricsProvider;
import com.ultramonitor.monitoring.SensorReading;
import com.ultramonitor.monitoring.SensorRow;
import javafx.application.Application;

import java.util.List;
import java.util.Locale;

/**
 * UltraMonitor — a portable hardware monitoring & stress testing utility.
 *
 * A plain launcher (not extending {@link Application}) so the portable
 * single-jar distribution runs with JavaFX on the classpath.
 * {@code --selftest} runs a headless hardware probe for CI / smoke tests.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 1 && "--selftest".equals(args[0])) {
            System.exit(runSelfTest() ? 0 : 1);
        }
        Application.launch(GuiApp.class, args);
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
}
