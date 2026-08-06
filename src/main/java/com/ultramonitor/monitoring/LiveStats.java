package com.ultramonitor.monitoring;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-wide statistics for every known sensor key. Thread-safe.
 */
public final class LiveStats {

    private final Map<String, SensorStats> stats = new ConcurrentHashMap<>();

    public void update(String key, double value) {
        stats.computeIfAbsent(key, k -> new SensorStats()).update(value);
    }

    public boolean available(String key) {
        SensorStats s = stats.get(key);
        return s != null && s.available();
    }

    public double min(String key) {
        SensorStats s = stats.get(key);
        return s == null ? Double.NaN : s.min();
    }

    public double max(String key) {
        SensorStats s = stats.get(key);
        return s == null ? Double.NaN : s.max();
    }

    public double avg(String key) {
        SensorStats s = stats.get(key);
        return s == null ? Double.NaN : s.avg();
    }

    public void reset() {
        stats.clear();
    }
}
