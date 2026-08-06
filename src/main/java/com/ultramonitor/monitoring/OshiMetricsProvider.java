package com.ultramonitor.monitoring;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.CentralProcessor.ProcessorIdentifier;
import oshi.hardware.ComputerSystem;
import oshi.hardware.Display;
import oshi.hardware.DisplayInfo;
import oshi.hardware.Firmware;
import oshi.hardware.GlobalMemory;
import oshi.hardware.GraphicsCard;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HWPartition;
import oshi.hardware.NetworkIF;
import oshi.hardware.PowerSource;
import oshi.hardware.Sensors;
import oshi.hardware.SoundCard;
import oshi.hardware.UsbDevice;
import oshi.hardware.VirtualMemory;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@link MetricsCollector} backed by OSHI (cross-platform hardware library).
 * Provides CPU temperature/load/voltage/frequency, per-core load & frequency,
 * RAM & swap usage, disk usage and read/write rates, network download/upload
 * rates, battery state and fan speeds — plus a full {@link #systemInfo()}
 * inventory of the machine.
 */
public final class OshiMetricsProvider implements MetricsCollector {

    private static final double BYTES_PER_MB = 1024.0 * 1024.0;

    private final SystemInfo oshi;
    private final CentralProcessor cpu;
    private final GlobalMemory memory;
    private final VirtualMemory swap;
    private final Sensors sensors;
    private final OperatingSystem os;
    private final ComputerSystem computer;
    private final List<HWDiskStore> disks;
    private final List<OSFileStore> fileStores;
    private final List<NetworkIF> nets;

    private long[] prevSystemTicks;
    private long[][] prevCoreTicks;
    private final Map<String, Counter> counters = new LinkedHashMap<>();

    /** Ordered sections of system info; live entries are re-evaluated per call. */
    private final List<SectionDef> sections = new ArrayList<>();

    /** Last sampled system load, shared with the System Info tab. */
    private volatile double lastSystemLoad = Double.NaN;
    /** Network rates from the most recent sample(), exposed to systemInfo(). */
    private volatile double lastNetDown = Double.NaN;
    private volatile double lastNetUp = Double.NaN;
    /** Written by sample() (under its lock) and read by systemInfo(). */
    private final Map<String, double[]> lastNicRates = new java.util.concurrent.ConcurrentHashMap<>();

    // Querying current CPU frequencies on Windows goes through WMI performance
    // counters that can take seconds per call, so they are cached and only
    // refreshed at most every two seconds (see {@link #coreFreqs()}).
    private volatile long freqCacheNanos;
    private volatile long[] cachedCoreFreqs = new long[0];
    private volatile double cachedAvgFreq = Double.NaN;

    // Context switches / interrupts are also slow WMI queries; cached with a
    // ten-second refresh so the System Info tab never hammers WMI.
    private volatile long ctxCacheNanos;
    private volatile long cachedContextSwitches;
    private volatile long cachedInterrupts;

    public OshiMetricsProvider() {
        this.oshi = new SystemInfo();
        this.cpu = oshi.getHardware().getProcessor();
        this.memory = oshi.getHardware().getMemory();
        this.swap = memory.getVirtualMemory();
        this.sensors = oshi.getHardware().getSensors();
        this.os = oshi.getOperatingSystem();
        this.computer = oshi.getHardware().getComputerSystem();
        this.disks = oshi.getHardware().getDiskStores();
        this.fileStores = os.getFileSystem().getFileStores();
        this.nets = oshi.getHardware().getNetworkIFs();
        this.prevSystemTicks = cpu.getSystemCpuLoadTicks();
        this.prevCoreTicks = cpu.getProcessorCpuLoadTicks();
        buildSystemInfo();
    }

    @Override
    public synchronized List<SensorReading> sample() {
        List<SensorReading> readings = new ArrayList<>();
        long now = System.nanoTime();

        // --- CPU temperature -------------------------------------------------
        double cpuTemp = sensors.getCpuTemperature();
        readings.add(new SensorReading("cpu.temp", "CPU Temp", "°C", cpuTemp > 0 ? cpuTemp : Double.NaN));

        // --- CPU load (system) ----------------------------------------------
        long[] curSystemTicks = cpu.getSystemCpuLoadTicks();
        lastSystemLoad = cpu.getSystemCpuLoadBetweenTicks(prevSystemTicks, curSystemTicks) * 100.0;
        prevSystemTicks = curSystemTicks;
        readings.add(new SensorReading("cpu.load", "CPU Load", "%", lastSystemLoad));

        // --- CPU frequency & voltage ----------------------------------------
        double freq = avgFrequency();
        readings.add(new SensorReading("cpu.freq", "CPU Frequency", "GHz", freq > 0 ? freq : Double.NaN));

        double cpuVoltage = sensors.getCpuVoltage();
        readings.add(new SensorReading("cpu.voltage", "CPU Voltage", "V", cpuVoltage > 0 ? cpuVoltage : Double.NaN));

        // --- Per-core load & frequency --------------------------------------
        long[][] curCoreTicks = cpu.getProcessorCpuLoadTicks();
        double[] coreLoads = cpu.getProcessorCpuLoadBetweenTicks(prevCoreTicks, curCoreTicks);
        prevCoreTicks = curCoreTicks;
        long[] coreFreqs = coreFreqs();
        for (int i = 0; i < coreLoads.length; i++) {
            readings.add(new SensorReading("cpu.core." + i, "Core " + i, "%", coreLoads[i] * 100.0));
            readings.add(new SensorReading("cpu.core." + i + ".freq", "Core " + i + " Freq", "GHz",
                    coreFreqs.length > i && coreFreqs[i] > 0 ? coreFreqs[i] / 1e9 : Double.NaN));
        }

        // --- RAM & swap ------------------------------------------------------
        long totalMem = memory.getTotal();
        long usedMem = totalMem - memory.getAvailable();
        readings.add(new SensorReading("ram.used", "RAM Used", "GB", usedMem / (double) (1024 * 1024 * 1024)));
        readings.add(new SensorReading("ram.load", "RAM Load", "%", totalMem > 0 ? usedMem * 100.0 / totalMem : Double.NaN));

        long swapTotal = swap.getSwapTotal();
        readings.add(new SensorReading("swap.used", "Swap Used", "GB",
                swapTotal > 0 ? swap.getSwapUsed() / (double) (1024 * 1024 * 1024) : Double.NaN));

        // --- Disk usage (file stores) ---------------------------------------
        for (OSFileStore store : fileStores) {
            String key = "disk.used." + store.getName();
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            double usedPct = total > 0 ? (total - usable) * 100.0 / total : Double.NaN;
            readings.add(new SensorReading(key, store.getName() + " Used", "%", usedPct));
        }

        // --- Disk read/write rates ------------------------------------------
        for (HWDiskStore disk : disks) {
            String name = disk.getName();
            String model = disk.getModel() == null ? name : disk.getModel();
            readings.add(new SensorReading("disk.read." + name, model + " Read", "MB/s",
                    rate("disk." + name + ".read", disk.getReadBytes(), now)));
            readings.add(new SensorReading("disk.write." + name, model + " Write", "MB/s",
                    rate("disk." + name + ".write", disk.getWriteBytes(), now)));
        }

        // --- Network download/upload rates ----------------------------------
        long bytesRecv = 0;
        long bytesSent = 0;
        lastNicRates.clear();
        for (NetworkIF net : nets) {
            if (isLoopback(net)) {
                continue;
            }
            net.updateAttributes();
            long recv = net.getBytesRecv();
            long sent = net.getBytesSent();
            bytesRecv += recv;
            bytesSent += sent;
            lastNicRates.put(net.getName(), new double[]{
                    rate("net." + net.getName() + ".recv", recv, now),
                    rate("net." + net.getName() + ".sent", sent, now)});
        }
        lastNetDown = rate("net.recv", bytesRecv, now);
        lastNetUp = rate("net.sent", bytesSent, now);
        readings.add(new SensorReading("net.down", "Network Down", "MB/s", lastNetDown));
        readings.add(new SensorReading("net.up", "Network Up", "MB/s", lastNetUp));

        // --- Battery ---------------------------------------------------------
        List<PowerSource> powerSources = oshi.getHardware().getPowerSources();
        if (!powerSources.isEmpty()) {
            for (int i = 0; i < powerSources.size(); i++) {
                PowerSource ps = powerSources.get(i);
                String base = "battery." + i;
                double level = ps.getRemainingCapacityPercent();
                readings.add(new SensorReading(base + ".level", "Battery Level", "%",
                        level >= 0 ? level * 100.0 : Double.NaN));
                double voltage = ps.getVoltage();
                readings.add(new SensorReading(base + ".voltage", "Battery Voltage", "V",
                        voltage > 0 ? voltage : Double.NaN));
                double remaining = ps.getTimeRemainingEstimated();
                readings.add(new SensorReading(base + ".time", "Battery Time Left", "min",
                        remaining > 0 ? remaining / 60.0 : Double.NaN));
            }
        }

        // --- Fans ------------------------------------------------------------
        int[] fans = sensors.getFanSpeeds();
        if (fans.length > 0) {
            for (int i = 0; i < fans.length; i++) {
                readings.add(new SensorReading("fan." + i, "Fan " + (i + 1), "RPM", fans[i] > 0 ? fans[i] : Double.NaN));
            }
        }

        return readings;
    }

    @Override
    public List<InfoEntry> systemInfo() {
        List<InfoEntry> result = new ArrayList<>();
        for (SectionDef section : sections) {
            for (EntryDef def : section.entries()) {
                String value;
                try {
                    value = def.value().get();
                } catch (Throwable t) {
                    value = Format.DASH;
                }
                result.add(new InfoEntry(section.name(), def.key(), def.label(), value, def.live()));
            }
        }
        return result;
    }

    @Override
    public void close() {
        // OSHI holds no open native resources that require explicit cleanup.
    }

    // ------------------------------------------------------------ inventory --

    /** Builds the static part of the system inventory once; live rows update later. */
    private void buildSystemInfo() {
        OperatingSystem.OSVersionInfo version = os.getVersionInfo();
        section("Operating System", defs(
                entry("os.name", "Operating System",
                        os.getManufacturer() + " " + os.getFamily() + " " + version.getVersion()),
                entry("os.build", "Build", version.getBuildNumber()),
                entry("os.codename", "Code Name", version.getCodeName()),
                entry("os.arch", "Architecture",
                        System.getProperty("os.arch", "?") + " · " + os.getBitness() + "-bit"),
                entry("os.booted", "Booted At", Format.timestamp(os.getSystemBootTime())),
                live("os.uptime", "Uptime", () -> Format.time(os.getSystemUptime())),
                live("os.processes", "Processes", () -> String.valueOf(os.getProcessCount()))));

        ProcessorIdentifier pid = cpu.getProcessorIdentifier();
        section("Processor", defs(
                entry("cpu.name", "Name", pid.getName()),
                entry("cpu.vendor", "Vendor", pid.getVendor()),
                entry("cpu.identifier", "Identifier", pid.getIdentifier()),
                entry("cpu.family", "Family", pid.getFamily()),
                entry("cpu.model", "Model", pid.getModel()),
                entry("cpu.stepping", "Stepping", pid.getStepping()),
                entry("cpu.processorid", "Processor ID", pid.getProcessorID()),
                entry("cpu.cores", "Physical Cores", String.valueOf(cpu.getPhysicalProcessorCount())),
                entry("cpu.threads", "Logical Processors", String.valueOf(cpu.getLogicalProcessorCount())),
                entry("cpu.basefreq", "Base Frequency", Format.ghz(pid.getVendorFreq())),
                entry("cpu.maxfreq", "Max Frequency", Format.ghz(cpu.getMaxFreq())),
                live("cpu.freq", "Current Frequency", () -> Format.ghz(avgFrequency())),
                live("cpu.load", "Usage", () -> Format.percent(lastSystemLoad)),
                live("cpu.load1", "Load Average (1 min)",
                        () -> Format.loadAvg(safeLoadAvg())),
                live("cpu.ctx", "Context Switches",
                        () -> String.format(Locale.ROOT, "%,d", cachedContextSwitches())),
                live("cpu.intr", "Interrupts",
                        () -> String.format(Locale.ROOT, "%,d", cachedInterrupts()))));

        section("Memory", defs(
                entry("mem.total", "Total", Format.bytes(memory.getTotal())),
                entry("mem.page", "Page Size", Format.bytes(memory.getPageSize())),
                entry("mem.swap.total", "Swap Total", Format.bytes(swap.getSwapTotal())),
                live("mem.used", "Used", () -> Format.bytes(memory.getTotal() - memory.getAvailable())),
                live("mem.usedpct", "Used %", () -> Format.percent(memoryPct())),
                live("mem.available", "Available", () -> Format.bytes(memory.getAvailable())),
                live("mem.swap.used", "Swap Used",
                        () -> swap.getSwapTotal() > 0 ? Format.bytes(swap.getSwapUsed()) : Format.DASH)));

        section("Motherboard", defs(
                entry("mb.manufacturer", "Manufacturer", computer.getBaseboard().getManufacturer()),
                entry("mb.model", "Model", computer.getBaseboard().getModel()),
                entry("mb.version", "Version", computer.getBaseboard().getVersion()),
                entry("mb.serial", "Serial Number", computer.getBaseboard().getSerialNumber())));

        section("System", defs(
                entry("sys.manufacturer", "Manufacturer", computer.getManufacturer()),
                entry("sys.model", "Model", computer.getModel()),
                entry("sys.serial", "Serial Number", computer.getSerialNumber())));

        Firmware firmware = computer.getFirmware();
        section("BIOS", defs(
                entry("bios.vendor", "Vendor", firmware.getManufacturer()),
                entry("bios.name", "Name", firmware.getName()),
                entry("bios.version", "Version", firmware.getVersion()),
                entry("bios.date", "Release Date", firmware.getReleaseDate())));

        List<GraphicsCard> gpus = oshi.getHardware().getGraphicsCards();
        for (int i = 0; i < gpus.size(); i++) {
            GraphicsCard gpu = gpus.get(i);
            String name = gpu.getName().isBlank() ? "Graphics " + (i + 1) : gpu.getName();
            section(name, defs(
                    entry("gpu." + i + ".vendor", "Vendor", gpu.getVendor()),
                    entry("gpu." + i + ".vram", "Video Memory", Format.bytes(gpu.getVRam())),
                    entry("gpu." + i + ".driver", "Driver", gpu.getVersionInfo())));
        }

        for (int i = 0; i < disks.size(); i++) {
            HWDiskStore disk = disks.get(i);
            List<EntryDef> defs = new ArrayList<>();
            defs.add(entry("disk." + i + ".model", "Model", disk.getModel()));
            defs.add(entry("disk." + i + ".serial", "Serial Number", disk.getSerial()));
            defs.add(entry("disk." + i + ".size", "Size", Format.bytes(disk.getSize())));
            List<HWPartition> partitions = disk.getPartitions();
            for (int p = 0; p < partitions.size() && p < 4; p++) {
                HWPartition part = partitions.get(p);
                String mount = part.getMountPoint().isBlank() ? part.getName() : part.getMountPoint();
                defs.add(entry("disk." + i + ".part" + p, "Partition " + (p + 1),
                        mount + " · " + Format.bytes(part.getSize())));
            }
            section("Disk " + (i + 1), defs);
        }

        for (int i = 0; i < fileStores.size(); i++) {
            OSFileStore fs = fileStores.get(i);
            String label = fs.getName().isBlank() ? fs.getMount() : fs.getName();
            section(label, defs(
                    entry("fs." + i + ".volume", "Volume", fs.getVolume()),
                    entry("fs." + i + ".type", "Type", fs.getType()),
                    entry("fs." + i + ".total", "Total", Format.bytes(fs.getTotalSpace())),
                    live("fs." + i + ".used", "Used",
                            () -> Format.bytes(fs.getTotalSpace() - fs.getFreeSpace())),
                    live("fs." + i + ".usedpct", "Used %",
                            () -> Format.percent(pct(fs.getTotalSpace(), fs.getTotalSpace() - fs.getFreeSpace()))),
                    live("fs." + i + ".free", "Free", () -> Format.bytes(fs.getFreeSpace()))));
        }

        List<SectionDef> network = new ArrayList<>();
        network.add(new SectionDef("Network Traffic", defs(
                live("net.down", "Download (total)", () -> Format.rate(lastNetDown)),
                live("net.up", "Upload (total)", () -> Format.rate(lastNetUp)))));
        int netIdx = 0;
        for (NetworkIF net : nets) {
            if (isLoopback(net)) {
                continue;
            }
            String display = net.getDisplayName().isBlank() ? net.getName() : net.getDisplayName();
            int idx = netIdx++;
            network.add(new SectionDef(display + " (" + net.getName() + ")", defs(
                    entry("net." + idx + ".mac", "MAC Address", net.getMacaddr()),
                    entry("net." + idx + ".ipv4", "IPv4", join(net.getIPv4addr())),
                    entry("net." + idx + ".ipv6", "IPv6", join(net.getIPv6addr())),
                    entry("net." + idx + ".speed", "Link Speed", Format.bits(net.getSpeed())),
                    entry("net." + idx + ".mtu", "MTU", String.valueOf(net.getMTU())),
                    live("net." + idx + ".down", "Download", () -> Format.rate(nicRate(net.getName(), 0))),
                    live("net." + idx + ".up", "Upload", () -> Format.rate(nicRate(net.getName(), 1))))));
        }
        sections.addAll(network);

        List<PowerSource> powerSources = oshi.getHardware().getPowerSources();
        for (int i = 0; i < powerSources.size(); i++) {
            PowerSource ps = powerSources.get(i);
            String label = ps.getName().isBlank() ? "Battery" : ps.getName();
            section(label, defs(
                    entry("bat." + i + ".design", "Design Capacity", Format.wattHours(ps.getDesignCapacity())),
                    entry("bat." + i + ".max", "Max Capacity", Format.wattHours(ps.getMaxCapacity())),
                    live("bat." + i + ".level", "Level",
                            () -> Format.percent(ps.getRemainingCapacityPercent() * 100.0)),
                    live("bat." + i + ".voltage", "Voltage",
                            () -> ps.getVoltage() > 0
                                    ? String.format(Locale.ROOT, "%.2f V", ps.getVoltage())
                                    : Format.DASH),
                    live("bat." + i + ".time", "Time Remaining",
                            () -> Format.minutes(ps.getTimeRemainingEstimated())),
                    live("bat." + i + ".ac", "AC Power", () -> ps.isPowerOnLine() ? "Plugged in" : "On battery"),
                    live("bat." + i + ".state", "Charge State",
                            () -> ps.isCharging() ? "Charging"
                                    : ps.isDischarging() ? "Discharging" : "Idle")));
        }

        List<Display> displays = oshi.getHardware().getDisplays();
        for (int i = 0; i < displays.size(); i++) {
            try {
                DisplayInfo di = displays.get(i).getDisplayInfo();
                String model = di.getModel().isBlank() ? di.getProductID() : di.getModel();
                String res = di.getPreferredResolution();
                double sizeInches = di.getHcm() > 0 && di.getVcm() > 0
                        ? Math.hypot(di.getHcm() / 10.0, di.getVcm() / 10.0) / 2.54
                        : 0;
                section("Display " + (i + 1), defs(
                        entry("disp." + i + ".manufacturer", "Manufacturer", di.getManufacturerID()),
                        entry("disp." + i + ".model", "Model", model),
                        entry("disp." + i + ".serial", "Serial Number", di.getProductSerialNumber()),
                        entry("disp." + i + ".resolution", "Resolution",
                                res.isBlank() ? Format.DASH
                                        : sizeInches > 0
                                                ? res + String.format(Locale.ROOT, " · %.1f\"", sizeInches)
                                                : res)));
            } catch (Throwable ignored) {
                // A single display that fails to decode must not break the inventory.
            }
        }

        try {
            List<UsbDevice> usb = oshi.getHardware().getUsbDevices(false);
            List<EntryDef> defs = new ArrayList<>();
            int shown = 0;
            for (UsbDevice device : usb) {
                if (shown >= 10) {
                    defs.add(entry("usb.more", "…", "and " + (usb.size() - 10) + " more devices"));
                    break;
                }
                String label = device.getName().isBlank() ? "Device " + (shown + 1) : device.getName();
                defs.add(entry("usb." + shown, label, device.getVendor()));
                shown++;
            }
            section("USB Devices", defs);
        } catch (Throwable ignored) {
            // USB enumeration is platform-sensitive; it is optional info.
        }

        List<SoundCard> sound = oshi.getHardware().getSoundCards();
        for (int i = 0; i < sound.size(); i++) {
            SoundCard card = sound.get(i);
            section(card.getName().isBlank() ? "Sound Card " + (i + 1) : card.getName(), defs(
                    entry("snd." + i + ".codec", "Codec", card.getCodec()),
                    entry("snd." + i + ".driver", "Driver", card.getDriverVersion())));
        }

        section("Runtime", defs(
                entry("rt.java", "Java Version", System.getProperty("java.version", Format.DASH)),
                entry("rt.vendor", "Java Vendor", System.getProperty("java.vendor", Format.DASH)),
                entry("rt.jvm", "JVM Architecture", System.getProperty("os.arch", Format.DASH)),
                entry("rt.home", "Java Home", System.getProperty("java.home", Format.DASH))));
    }

    // --------------------------------------------------------------- helpers --

    private void section(String name, List<EntryDef> defs) {
        sections.add(new SectionDef(name, defs));
    }

    private static List<EntryDef> defs(EntryDef... defs) {
        return new ArrayList<>(List.of(defs));
    }

    private static EntryDef entry(String key, String label, String value) {
        return new EntryDef(key, label, false, () -> value);
    }

    private static EntryDef live(String key, String label, Supplier<String> value) {
        return new EntryDef(key, label, true, value);
    }

    private double safeLoadAvg() {
        try {
            return cpu.getSystemLoadAverage(1)[0];
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    private double memoryPct() {
        long total = memory.getTotal();
        return total > 0 ? (total - memory.getAvailable()) * 100.0 / total : Double.NaN;
    }

    private static double pct(long total, long used) {
        return total > 0 ? used * 100.0 / total : Double.NaN;
    }

    private double nicRate(String nicName, int component) {
        double[] rates = lastNicRates.get(nicName);
        return rates == null ? Double.NaN : rates[component];
    }

    private static String join(String[] values) {
        if (values == null || values.length == 0) {
            return Format.DASH;
        }
        return String.join(", ", values);
    }

    /** Per-core frequencies, refreshed at most every two seconds. */
    private long[] coreFreqs() {
        long now = System.nanoTime();
        long[] cached = cachedCoreFreqs;
        if (cached.length == 0 || now - freqCacheNanos > 2_000_000_000L) {
            long[] fresh = cpu.getCurrentFreq();
            long sum = 0;
            int nonZero = 0;
            for (long f : fresh) {
                if (f > 0) {
                    sum += f;
                    nonZero++;
                }
            }
            cachedCoreFreqs = fresh;
            cachedAvgFreq = nonZero > 0 ? sum / (double) nonZero / 1e9
                    : cpu.getProcessorIdentifier().getVendorFreq() / 1e9;
            freqCacheNanos = now;
            cached = fresh;
        }
        return cached;
    }

    /** Average CPU frequency in GHz, backed by the two-second cache. */
    private double avgFrequency() {
        coreFreqs();
        return cachedAvgFreq;
    }

    private long cachedContextSwitches() {
        long now = System.nanoTime();
        if (now - ctxCacheNanos > 10_000_000_000L) {
            try {
                cachedContextSwitches = cpu.getContextSwitches();
            } catch (Throwable ignored) {
                // keep the previous cached value
            }
            ctxCacheNanos = now;
        }
        return cachedContextSwitches;
    }

    private long cachedInterrupts() {
        long now = System.nanoTime();
        if (now - ctxCacheNanos > 10_000_000_000L) {
            try {
                cachedInterrupts = cpu.getInterrupts();
            } catch (Throwable ignored) {
                // keep the previous cached value
            }
            ctxCacheNanos = now;
        }
        return cachedInterrupts;
    }

    private static boolean isLoopback(NetworkIF net) {
        String haystack = (net.getDisplayName() + " " + net.getName()).toLowerCase(Locale.ROOT);
        return haystack.contains("loopback");
    }

    /** MB/s between the previous and current byte counter sample, or NaN on the first sample. */
    private double rate(String bucket, long newBytes, long nowNanos) {
        Counter prev = counters.get(bucket);
        double rate = Double.NaN;
        if (prev != null && prev.nanos != 0) {
            long dtNanos = nowNanos - prev.nanos;
            if (dtNanos > 0) {
                double dtSec = dtNanos / 1e9;
                rate = Math.max(0, (newBytes - prev.bytes) / BYTES_PER_MB / dtSec);
            }
        }
        counters.put(bucket, new Counter(newBytes, nowNanos));
        return rate;
    }

    private record SectionDef(String name, List<EntryDef> entries) {
    }

    private record EntryDef(String key, String label, boolean live, Supplier<String> value) {
    }

    private static final class Counter {
        final long bytes;
        final long nanos;

        Counter(long bytes, long nanos) {
            this.bytes = bytes;
            this.nanos = nanos;
        }
    }
}
