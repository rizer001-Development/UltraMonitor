package com.ultramonitor.monitoring;

import java.util.List;

/**
 * Abstraction over the hardware monitoring backend so the GUI and the
 * CLI both use the same engine.
 */
public interface MetricsCollector extends AutoCloseable {

    /**
     * Reads all sensors once. Implementations should be cheap enough to be
     * called at the configured refresh rate.
     */
    List<SensorReading> sample();

    /**
     * Full system inventory (CPU, memory, motherboard, storage, network, …)
     * as grouped key/value rows. Live rows are recomputed on every call;
     * static rows are cached. Providers that do not support it return empty.
     */
    default List<InfoEntry> systemInfo() {
        return List.of();
    }

    @Override
    void close();
}
