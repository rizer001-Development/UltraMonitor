package com.ultramonitor.monitoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatTest {

    @Test
    void bytesScalesUnits() {
        assertEquals("0 B", Format.bytes(0));
        assertEquals("1.0 KB", Format.bytes(1024));
        assertEquals("1.5 MB", Format.bytes(1536 * 1024));
        assertEquals("2.0 GB", Format.bytes(2L * 1024 * 1024 * 1024));
    }

    @Test
    void ghzFormatsOrDashes() {
        assertEquals("3.60 GHz", Format.ghz(3.6e9));
        assertEquals("—", Format.ghz(0));
        assertEquals("—", Format.ghz(Double.NaN));
    }

    @Test
    void rateFormatsOrDashes() {
        assertEquals("12.30 MB/s", Format.rate(12.3));
        assertEquals("—", Format.rate(Double.NaN));
        assertEquals("—", Format.rate(-1));
    }

    @Test
    void percentFormatsOrDashes() {
        assertEquals("45.0%", Format.percent(45.0));
        assertEquals("—", Format.percent(-1.0));
        assertEquals("—", Format.percent(Double.NaN));
    }

    @Test
    void timeReadsNaturally() {
        assertEquals("0s", Format.time(0));
        assertEquals("45s", Format.time(45));
        assertEquals("2m 5s", Format.time(125));
        assertEquals("1h 2m", Format.time(3725));
        assertEquals("2d 3h 4m", Format.time(2 * 86400 + 3 * 3600 + 4 * 60 + 30));
        assertEquals("—", Format.time(-5));
    }

    @Test
    void minutesReadsNaturally() {
        assertEquals("—", Format.minutes(-1.0));
        assertEquals("30 min", Format.minutes(1800));
        assertEquals("2h 05m", Format.minutes(7500));
    }

    @Test
    void bitsAndWattsAndTimestamps() {
        assertEquals("1.0 Gbps", Format.bits(1_000_000_000L));
        assertEquals("100 Mbps", Format.bits(100_000_000L));
        assertEquals("—", Format.bits(0));
        assertEquals("45.2 Wh", Format.wattHours(45_200));
        assertEquals("—", Format.wattHours(0));
        assertEquals("—", Format.timestamp(0));
        // Timestamp is rendered in the local zone, so only check the shape.
        String ts = Format.timestamp(System.currentTimeMillis() / 1000);
        assertTrue(ts.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"), ts);
    }
}
