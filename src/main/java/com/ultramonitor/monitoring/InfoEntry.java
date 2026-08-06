package com.ultramonitor.monitoring;

/**
 * One row of the System Info tab: a key/value pair grouped under a section.
 *
 * @param section human-readable group header (e.g. "Processor", "Memory")
 * @param key     stable identifier used by the UI to tell rows apart
 * @param label   human-readable parameter name
 * @param value   formatted display value
 * @param live    {@code true} when the value is refreshed on every sample tick
 */
public record InfoEntry(String section, String key, String label, String value, boolean live) {
}
