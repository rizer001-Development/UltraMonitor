package com.ultramonitor.monitoring;

/**
 * A single sensor reading produced by a {@link MetricsCollector}.
 *
 * @param key   stable identifier used for tracking stats across samples
 * @param name  human-readable sensor name shown in the UI
 * @param unit  display unit (e.g. "°C", "%", "GHz")
 * @param value current value, or {@link Double#NaN} when unavailable
 */
public record SensorReading(String key, String name, String unit, double value) {
    public static SensorReading unavailable(String key, String name, String unit) {
        return new SensorReading(key, name, unit, Double.NaN);
    }

    public boolean available() {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
