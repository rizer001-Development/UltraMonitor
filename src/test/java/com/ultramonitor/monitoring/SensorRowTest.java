package com.ultramonitor.monitoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensorRowTest {

    @Test
    void formatsValueWithUnit() {
        LiveStats stats = new LiveStats();
        SensorRow row = SensorRow.of(
                new SensorReading("cpu.temp", "CPU Temp", "°C", 50.0), stats);

        assertTrue(row.available());
        assertEquals("CPU Temp", row.name());
        assertEquals("50.0 °C", row.current());
    }

    @Test
    void minAvgMaxReflectStats() {
        LiveStats stats = new LiveStats();
        stats.update("cpu.temp", 45);
        stats.update("cpu.temp", 55);

        SensorRow row = SensorRow.of(
                new SensorReading("cpu.temp", "CPU Temp", "°C", 50.0), stats);

        assertEquals("45.0 °C", row.min());
        assertEquals("50.0 °C", row.avg());
        assertEquals("55.0 °C", row.max());
    }

    @Test
    void unavailableReadingShowsDash() {
        SensorRow row = SensorRow.of(
                new SensorReading("cpu.temp", "CPU Temp", "°C", Double.NaN), new LiveStats());

        assertFalse(row.available());
        assertEquals("—", row.current());
        assertEquals("—", row.min());
        assertEquals("—", row.max());
    }
}
