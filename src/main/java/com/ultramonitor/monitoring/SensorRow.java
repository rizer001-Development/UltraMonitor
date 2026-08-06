package com.ultramonitor.monitoring;

import java.util.Locale;

/**
 * A display-ready row combining a {@link SensorReading} with its session
 * statistics. All values are formatted strings (value + unit), so the UI can
 * render the table directly.
 */
public record SensorRow(String name, String unit, String current, String min, String avg, String max,
                        boolean available) {

    private static final String DASH = "—";

    public static SensorRow of(SensorReading reading, LiveStats stats) {
        if (!reading.available()) {
            return new SensorRow(reading.name(), reading.unit(), DASH, DASH, DASH, DASH, false);
        }
        String unit = reading.unit();
        return new SensorRow(
                reading.name(),
                unit,
                format(reading.value()) + " " + unit,
                formatStat(stats, reading.key(), unit, SensorStats.Value.MIN),
                formatStat(stats, reading.key(), unit, SensorStats.Value.AVG),
                formatStat(stats, reading.key(), unit, SensorStats.Value.MAX),
                true);
    }

    private static String formatStat(LiveStats stats, String key, String unit, SensorStats.Value which) {
        if (!stats.available(key)) {
            return DASH;
        }
        double v = switch (which) {
            case MIN -> stats.min(key);
            case AVG -> stats.avg(key);
            case MAX -> stats.max(key);
        };
        return format(v) + " " + unit;
    }

    public static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
