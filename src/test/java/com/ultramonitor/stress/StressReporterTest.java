package com.ultramonitor.stress;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for {@link StressReporter}: sample aggregation and CSV export.
 */
class StressReporterTest {

    @Test
    void aggregatesAveragesAndPeak() {
        StressReporter reporter = new StressReporter();
        reporter.start();
        reporter.tick(20.0, 30.0, 50.0);
        reporter.tick(40.0, 50.0, 60.0);
        reporter.tick(60.0, 70.0, 55.0);

        assertEquals(3, reporter.sampleCount());
        assertEquals(40.0, reporter.avgCpuLoad(), 1e-9);
        assertEquals(50.0, reporter.avgRamLoad(), 1e-9);
        assertEquals(60.0, reporter.peakTemp(), 1e-9);
    }

    @Test
    void skipsNaNsFromStats() {
        StressReporter reporter = new StressReporter();
        reporter.start();
        reporter.tick(Double.NaN, Double.NaN, Double.NaN);
        reporter.tick(10.0, Double.NaN, Double.NaN);

        assertEquals(2, reporter.sampleCount());
        // Only one valid CPU sample => average is that sample.
        assertEquals(10.0, reporter.avgCpuLoad(), 1e-9);
        assertTrue(Double.isNaN(reporter.avgRamLoad()));
        assertTrue(Double.isNaN(reporter.peakTemp()));
    }

    @Test
    void resetClearsSamples() {
        StressReporter reporter = new StressReporter();
        reporter.start();
        reporter.tick(10, 20, 30);
        assertEquals(1, reporter.sampleCount());

        reporter.start();
        assertEquals(0, reporter.sampleCount());
        assertTrue(Double.isNaN(reporter.avgCpuLoad()));
    }

    @Test
    void writesCsvWithHeaderAndRows() throws Exception {
        Path target = Files.createTempFile("um-report-", ".csv");
        try {
            StressReporter reporter = new StressReporter();
            reporter.start();
            reporter.tick(11.0, 22.0, 33.0);
            reporter.tick(44.0, 55.0, 66.0);

            assertTrue(reporter.writeCsv(target, "Stopped by user"));
            List<String> lines = Files.readAllLines(target);
            assertEquals("stop_reason,\"Stopped by user\"", lines.get(0));
            assertEquals("elapsed_seconds,cpu_load_pct,ram_load_pct,cpu_temp_c", lines.get(1));
            assertEquals(4, lines.size(), "stop_reason + header + 2 sample rows");

            String row = lines.get(2);
            assertTrue(row.contains(",11.00,22.00,33.00"), "row=" + row);
        } finally {
            Files.deleteIfExists(target);
        }
    }
}