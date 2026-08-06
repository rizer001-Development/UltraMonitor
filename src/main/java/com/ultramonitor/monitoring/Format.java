package com.ultramonitor.monitoring;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Small formatting helpers shared by sensors and the System Info tab.
 * Unavailable values always render as the em dash {@code —}.
 */
public final class Format {

    public static final String DASH = "—";

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private Format() {
    }

    /** Human-readable bytes: "1.2 KB", "4.5 GB", … */
    public static String bytes(long value) {
        if (value <= 0) {
            return "0 B";
        }
        double v = value;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, "%.1f %s", v, units[i]);
    }

    /** Frequency in hertz → "3.60 GHz". */
    public static String ghz(double hz) {
        if (hz <= 0 || Double.isNaN(hz)) {
            return DASH;
        }
        return String.format(Locale.ROOT, "%.2f GHz", hz / 1e9);
    }

    /** MB/s rate → "12.30 MB/s". */
    public static String rate(double mbps) {
        if (Double.isNaN(mbps) || mbps < 0) {
            return DASH;
        }
        return String.format(Locale.ROOT, "%.2f MB/s", mbps);
    }

    /** Link speed in bits/s → "1.0 Gbps" / "100 Mbps". */
    public static String bits(long bitsPerSec) {
        if (bitsPerSec <= 0) {
            return DASH;
        }
        if (bitsPerSec >= 1_000_000_000) {
            return String.format(Locale.ROOT, "%.1f Gbps", bitsPerSec / 1e9);
        }
        return String.format(Locale.ROOT, "%.0f Mbps", bitsPerSec / 1e6);
    }

    /** Percent, tolerating NaN / negative "unknown" values. */
    public static String percent(double value) {
        if (Double.isNaN(value) || value < 0) {
            return DASH;
        }
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    /** Load average (or any small scalar). */
    public static String loadAvg(double value) {
        if (Double.isNaN(value)) {
            return DASH;
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** Duration in seconds → "3d 4h 5m" / "45m 30s". */
    public static String time(long seconds) {
        if (seconds < 0) {
            return DASH;
        }
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long secs = seconds % 60;
        if (days > 0) {
            return days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + secs + "s";
        }
        return secs + "s";
    }

    /** Battery minutes (input in seconds; negative means unknown/calculating). */
    public static String minutes(double seconds) {
        if (seconds < 0) {
            return DASH;
        }
        long total = (long) (seconds / 60.0);
        return total >= 60
                ? (total / 60) + "h " + String.format(Locale.ROOT, "%02dm", total % 60)
                : total + " min";
    }

    /** Battery capacity in milliwatt-hours → "45.2 Wh". */
    public static String wattHours(int mWh) {
        if (mWh <= 0) {
            return DASH;
        }
        return String.format(Locale.ROOT, "%.1f Wh", mWh / 1000.0);
    }

    /** Epoch seconds → "2026-08-06 14:02:11". */
    public static String timestamp(long epochSeconds) {
        if (epochSeconds <= 0) {
            return DASH;
        }
        return TIMESTAMP.format(Instant.ofEpochSecond(epochSeconds));
    }
}
