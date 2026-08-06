package com.ultramonitor.monitoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveStatsTest {

    @Test
    void tracksMinAvgMax() {
        LiveStats stats = new LiveStats();
        stats.update("cpu.temp", 50);
        stats.update("cpu.temp", 45);
        stats.update("cpu.temp", 55);

        assertTrue(stats.available("cpu.temp"));
        assertEquals(45.0, stats.min("cpu.temp"), 1e-9);
        assertEquals(55.0, stats.max("cpu.temp"), 1e-9);
        assertEquals(50.0, stats.avg("cpu.temp"), 1e-9);
    }

    @Test
    void ignoresUnavailableValues() {
        LiveStats stats = new LiveStats();
        stats.update("cpu.load", Double.NaN);
        assertFalse(stats.available("cpu.load"));

        stats.update("cpu.load", 12.5);
        stats.update("cpu.load", Double.POSITIVE_INFINITY);
        stats.update("cpu.load", Double.NEGATIVE_INFINITY);

        assertTrue(stats.available("cpu.load"));
        assertEquals(12.5, stats.min("cpu.load"), 1e-9);
        assertEquals(12.5, stats.max("cpu.load"), 1e-9);
        assertEquals(12.5, stats.avg("cpu.load"), 1e-9);
    }

    @Test
    void unknownKeyIsUnavailable() {
        LiveStats stats = new LiveStats();
        assertFalse(stats.available("missing"));
        assertTrue(Double.isNaN(stats.min("missing")));
        assertTrue(Double.isNaN(stats.avg("missing")));
        assertTrue(Double.isNaN(stats.max("missing")));
    }

    @Test
    void resetClearsEverything() {
        LiveStats stats = new LiveStats();
        stats.update("ram.load", 42);
        stats.reset();
        assertFalse(stats.available("ram.load"));
    }

    @Test
    void sensorStatsCountsSamples() {
        SensorStats stats = new SensorStats();
        stats.update(10);
        stats.update(20);
        assertEquals(2, stats.count());
        stats.reset();
        assertEquals(0, stats.count());
        assertFalse(stats.available());
    }
}
