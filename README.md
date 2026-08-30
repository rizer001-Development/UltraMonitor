# UltraMonitor

![Java](https://img.shields.io/badge/Java-21%2B-blue) ![JavaFX](https://img.shields.io/badge/JavaFX-25-orange) ![Development status](https://img.shields.io/badge/status-Alpha-red) ![License](https://img.shields.io/badge/License-AGPLv3-red)

**A portable desktop utility for monitoring your hardware sensors and stress testing your system.**

---

### 📄 Organization Docs

[![Guide](https://img.shields.io/badge/Guide-rizer001--Development-00AEFF)](https://github.com/rizer001-Development/.github/blob/main/GUIDE.md) · [![Contributing](https://img.shields.io/badge/Contributing-rizer001--Development-4CAF50)](https://github.com/rizer001-Development/.github/blob/main/CONTRIBUTING.md) · [![Security](https://img.shields.io/badge/Security-rizer001--Development-D9534F)](https://github.com/rizer001-Development/.github/blob/main/SECURITY.md) · [![Code of Conduct](https://img.shields.io/badge/Code%20of%20Conduct-rizer001--Development-5BC0DE)](https://github.com/rizer001-Development/.github/blob/main/CODE_OF_CONDUCT.md)

UltraMonitor shows real-time readings from your CPU, memory, disks and network, and can put
your system under load with CPU / RAM / disk / GPU stress tests to check stability and cooling.
Built with **Java 21+** (compiled on Java 25), **JavaFX**, **LWJGL/OpenGL** and **OSHI** —
no installation required, just a single jar + launcher.

---

## Features

- **Main menu** — toggle switches open the *Sensors* and *Stress Test* windows, which can stay
  open side by side so you can watch the sensors while stressing.
- **Sensors window, two tabs:**
  - *Sensors* — every sensor OSHI finds, as a live `Current | Min | Avg | Max` table:
    - CPU temperature, load, frequency and voltage;
    - per-core load and per-core frequency;
    - RAM and swap usage;
    - disk usage plus per-disk read/write rates;
    - network download/upload rates;
    - battery level, voltage and time remaining (on laptops);
    - fan speeds (when exposed by the OS).
  - *System Info* — the full hardware inventory grouped by section (OS, CPU, memory, motherboard,
    BIOS, graphics, disks, file systems, network, battery, displays, USB, sound), with live
    values highlighted in blue.
- **Configurable refresh rate** — from **1 to 10,000 ms**; persisted to `config.json` next to
  the application.
- **Stress test** — four tests with their own parameters:
  - *CPU* — full load across every execution unit: vectorized FMA pass (auto-vectorized to AVX2),
    a scalar transcendental chain and a cache-buster walk (thread slider);
  - *RAM* — allocate and constantly read/write memory buffers with up to 8 partitioned churn
    threads to saturate memory bandwidth (slider: % of available RAM);
  - *Disk* — parallel sequential writes/reads (with periodic fsync) plus random 64 KB I/O on a
    temporary file (slider: file size in MB);
  - *GPU* — a true GPU burn via **OpenGL (LWJGL)**: a fullscreen window draws a fullscreen
    quad whose fragment shader computes the **Mandelbrot set** with vsync disabled
    (`glfwSwapInterval(0)`) — hundreds of millions of fragment operations per frame, so the
    GPU renders as fast as the hardware allows. A **five-level selector** (Light, Medium,
    Intense, Extreme, Meltdown) scales the per-pixel iterations and render resolution — from
    a gentle 128-iteration warm-up at 720p right up to a 2048-iteration 4K burn (Meltdown) —
    so you can pick anything from a quiet test to the absolute maximum load. The live status
    shows the OpenGL device (e.g. `NVIDIA GeForce RTX 3060`) and the chosen level, proving a
    real GPU is being hammered. Falls back to CPU-only AWT rendering (also level-scaled) in
    headless environments.
  - Optional duration in seconds (empty = run until stopped manually; max 86,400 s),
    **Start / Stop** buttons, live CPU load, RAM load and temperature gauges, a progress bar and
    **automatic stop at 90 °C**.
- **Dark UI** — custom frameless windows, animated toggles and a live "LIVE" indicator.
- **Custom app icon** — the menu, Sensors and Stress Test windows (plus the GLFW GPU stress window) carry a bundled UltraMonitor icon instead of the generic Java icon.
- **Portable** — a single jar + launcher, no installation.
- **Headless self-test** for CI / smoke tests: `java -jar UltraMonitor.jar --selftest`.
- **Stress-test CSV report** — after a run, click **Report** to export the recorded samples
  (elapsed, CPU load, RAM load, CPU temperature) as a CSV.
- **Headless stress CLI** — run the stress engine from a terminal and get a CSV report
  (see below).

> Note: on Windows, CPU temperature and fan readings require a driver such as
> [LibreHardwareMonitor](https://github.com/LibreHardwareMonitor/LibreHardwareMonitor)
> (or admin rights with OpenHardwareMonitor). Without one, those sensors show «—».

---

## Requirements

- **Java 21 or newer** (built and tested on Java 25).

## Build

```bat
build.bat
```

This compiles the project and produces a portable build in `dist/`:

```
dist\
├── UltraMonitor.jar
└── launch.bat
```

You can also build manually via Gradle:

```bat
gradlew.bat clean fatJar
```

To target a different OS classifier for the bundled JavaFX natives (e.g. building on
Linux but producing a Windows jar):

```bat
gradlew.bat clean fatJar -Pjavafx.platform=win
```

## Run

```bat
dist\launch.bat
```

Or from a terminal:

```bat
java -jar dist\UltraMonitor.jar
```

During development:

```bat
gradlew.bat run
```

## Self-test (CI / smoke tests)

```bat
java -jar UltraMonitor.jar --selftest
```

A headless mode: probes the sensors, prints the readings and system info, and exits with
code `0` on success or `1` on error.

## Stress test CLI

Run the same stress engine from a terminal, no GUI:

```bat
java -jar UltraMonitor.jar stress --cpu --ram --duration 60 --report stress.csv
```

Options:

| Flag | Meaning |
|------|---------|
| `--cpu` / `--ram` / `--disk` / `--gpu` | which tests to run (at least one required) |
| `--duration SEC` | stop after `SEC` seconds (default: until temperature limit) |
| `--report FILE.csv` | write a CSV report (elapsed, CPU %, RAM %, CPU temp) |
| `--temp-limit C` | auto-stop temperature in °C (default: 90) |

After the run it prints a summary (sample count, average CPU/RAM load, peak temperature) and,
if `--report` was given, writes the CSV next to the jar.

---

## Configuration

`config.json` is stored next to the jar (falls back to `~/.ultramonitor` when running from a
build directory):

```json
{ "refreshIntervalMs": 1000 }
```

- `refreshIntervalMs` — sensor refresh interval in milliseconds (1–10,000).

---

## Project layout

```
src/main/java/com/ultramonitor/
├── Main.java              entry point (GUI + --selftest)
├── GuiApp.java            JavaFX application: menu + sensors and stress test windows
├── config/AppConfig.java  portable config.json persistence
├── monitoring/            hardware abstraction layer (OSHI)
│   ├── MetricsCollector   interface shared by GUI and CLI
│   ├── OshiMetricsProvider OSHI implementation
│   ├── SensorReading      one sensor sample
│   ├── SensorStats        min/avg/max accumulator
│   ├── LiveStats          session stats for all sensors
│   ├── SensorRow          display-ready table row
│   ├── InfoEntry          one System Info key/value row
│   └── Format             unit & time formatting helpers
├── stress/                stress test engine
│   ├── StressTest         test interface
│   ├── StressRunner       start/stop a set of tests
│   ├── CpuStress          CPU load
│   ├── MemoryStress       RAM load
│   ├── DiskStress         disk load
│   ├── GpuStress          GPU load
│   └── StressReporter     per-run sample collection + CSV/summary export
└── ui/                    JavaFX views, switch, title bar, theme
```

Unit tests live in `src/test/java/com/ultramonitor/`.

---

## Roadmap

- [x] Live sensors (all sensors + full system info, two tabs)
- [x] CPU / RAM / disk — see [STRESS_TEST_PLAN.md](STRESS_TEST_PLAN.md)
- [ ] Charts & history graphs
- [x] Stress test CSV report export

---

## License

Licensed under the **GNU Affero General Public License v3.0 (AGPL-3.0)** — see [LICENSE](LICENSE).
