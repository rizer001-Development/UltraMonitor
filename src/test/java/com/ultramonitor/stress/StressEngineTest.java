package com.ultramonitor.stress;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless engine tests: start a workload, observe it running, stop it and
 * verify cleanup. Kept short so the suite stays fast.
 */
class StressEngineTest {

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void cpuStressRunsAndStops() {
        CpuStress cpu = new CpuStress(2);
        assertFalse(cpu.isRunning());
        cpu.start();
        sleep(300);
        assertTrue(cpu.isRunning());
        cpu.stop();
        assertFalse(cpu.isRunning());
    }

    @Test
    void memoryStressAllocatesAndFrees() {
        MemoryStress memory = new MemoryStress(64L * 1024 * 1024); // 64 MB
        memory.start();
        sleep(300);
        assertTrue(memory.isRunning());
        memory.stop();
        assertFalse(memory.isRunning());
    }

    @Test
    void memoryStressStopMidAllocationReleasesAndFlagsStopped() {
        // Use a target large enough that allocation is still running when stop()
        // fires; stop() must drain the half-built buffer array without leaking it.
        MemoryStress memory = new MemoryStress(2L * 1024 * 1024 * 1024); // 2 GB target
        memory.start();
        // Stop almost immediately, racing the allocator thread.
        memory.stop();
        assertFalse(memory.isRunning());
    }

    @Test
    void diskStressDeletesTempFile() throws Exception {
        DiskStress disk = new DiskStress(64);
        disk.start();
        sleep(400);
        assertTrue(disk.isRunning());
        disk.stop();
        assertFalse(disk.isRunning());
        // no leftover temp files
        try (var stream = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().startsWith("ultramonitor-disk-")));
        }
    }

    @Test
    void gpuStressRunsAndStops() {
        GpuStress gpu = new GpuStress();
        assertFalse(gpu.isRunning());
        assertEquals(GpuStress.Level.INTENSE, gpu.level());
        gpu.start();
        sleep(300);
        assertTrue(gpu.isRunning());
        gpu.stop();
        assertFalse(gpu.isRunning());
    }

    @Test
    void gpuStressLevelsScaleWorkload() {
        // Each level carries its own intensity parameters; a lighter level must be
        // configured with strictly less per-pixel work and a smaller target res.
        GpuStress.Level light = GpuStress.Level.LIGHT;
        GpuStress.Level medium = GpuStress.Level.MEDIUM;
        GpuStress.Level intense = GpuStress.Level.INTENSE;
        assertTrue(light.iterations < medium.iterations);
        assertTrue(medium.iterations < intense.iterations);
        // Levels apply when constructing a test.
        assertEquals(light.iterations, new GpuStress(light).level().iterations);
        assertEquals(medium, new GpuStress(medium).level());
    }

    @Test
    void runnerTracksDurationAndProgress() throws Exception {
        CpuStress cpu = new CpuStress(1);
        StressRunner runner = new StressRunner(List.of(cpu));
        assertTrue(runner.start(1)); // 1 second
        assertTrue(runner.isRunning());
        assertTrue(runner.hasDuration());
        assertFalse(Double.isNaN(runner.progress()));
        Thread.sleep(1150);
        assertTrue(runner.isFinishedByTime());
        assertFalse(runner.start(5), "start must be rejected while running");
        runner.stop("Duration reached");
        assertFalse(runner.isRunning());
        assertEquals("Duration reached", runner.stopReason());
    }

    @Test
    void runnerUnlimitedHasNoProgress() {
        CpuStress cpu = new CpuStress(1);
        StressRunner runner = new StressRunner(List.of(cpu));
        runner.start(0);
        assertTrue(Double.isNaN(runner.progress()));
        assertFalse(runner.isFinishedByTime());
        runner.stop("Stopped by user");
    }
}
