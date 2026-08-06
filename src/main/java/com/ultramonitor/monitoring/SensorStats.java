package com.ultramonitor.monitoring;

/**
 * Accumulates min / avg / max statistics for a single sensor over a session.
 * Unavailable values (NaN / Infinity) are ignored.
 */
public final class SensorStats {

    /** Which aggregate value to read. */
    public enum Value { MIN, AVG, MAX }

    private double min = Double.POSITIVE_INFINITY;
    private double max = Double.NEGATIVE_INFINITY;
    private double sum;
    private long count;

    public void update(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return;
        }
        min = Math.min(min, value);
        max = Math.max(max, value);
        sum += value;
        count++;
    }

    public boolean available() {
        return count > 0;
    }

    public double min() {
        return available() ? min : Double.NaN;
    }

    public double max() {
        return available() ? max : Double.NaN;
    }

    public double avg() {
        return available() ? sum / count : Double.NaN;
    }

    public long count() {
        return count;
    }

    public void reset() {
        min = Double.POSITIVE_INFINITY;
        max = Double.NEGATIVE_INFINITY;
        sum = 0;
        count = 0;
    }
}
