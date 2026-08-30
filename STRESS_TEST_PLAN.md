# UltraMonitor — Stress Test Plan

Status: **in progress** — M1–M3 done (CPU/RAM/Disk/GPU in the GUI with parameters,
Start/Stop, live gauges, duration and temperature auto-stop) · Remaining: M4 report
export, M5 CLI

This document describes how the Stress Test feature will work: what it does,
how it is architected, and the safety rules that keep it from damaging
hardware. It reuses the monitoring engine already built for the Sensors window.

---

## 1. Goal

Let the user deliberately push their CPU, memory and disk to full load from the
Stress Test window, watch live temperatures/loads during the run, and get a
summary report afterwards. Same look & feel as the rest of the app (dark theme,
toggle switches, live values), fully portable.

## 2. User flow (GUI)

```
Stress Test window
 ├─ Test cards (one per test type, each with its own toggle)
 │    CPU      — threads selector (Auto = all logical cores)
 │    Memory   — size selector (Auto = e.g. 50% of free RAM)
 │    Disk     — temp file size selector (e.g. 512 MB / 1 GB)
 │    GPU      — intensity selector (Light / Medium / Intense / Extreme / Meltdown)
 ├─ Duration:  [___] seconds   (empty = run until stopped manually)
 ├─ Safety:    Auto-stop above [90] °C  (editable, 50–100)
 ├─ [▶ Start]  [■ Stop]
 └─ Live panel: current CPU load / CPU temp / per-test progress bars
                + result summary when finished (avg load, peak temp, duration)
```

- Starting a test shows a **confirmation dialog**: "This will push your
  hardware to 100% load. Temperatures will rise. UltraMonitor will
  automatically stop the test if CPU temperature exceeds the limit. Continue?"
- **Stop** stops all running tests. Closing the window also stops everything.
- When a test finishes, its row shows a summary; the **Report** button exports
  a CSV (test, duration, avg CPU load, peak temperature, notes on throttling).

## 3. Engine architecture

New package `com.ultramonitor.stress`, mirroring the monitoring package so the
GUI stays a thin layer on top:

```
stress/
├── StressTest.java          // interface: start(), stop(), isRunning(),
│                            //   progress() 0..1, summary()
├── CpuStressTest            // N threads spinning on FPU/AVX-style work;
│                            //   threads pinned across logical processors
├── MemoryStressTest         // allocate buffers (configurable fraction of RAM),
│                            //   continuously write + read back
├── DiskStressTest           // sequential + random read/write on a temp file
│                            //   (created in TEMP, deleted on finish)
├── StressRunner             // executor that runs tests, tracks progress,
│                            //   stops everything on Stop / temperature limit
└── StressReport             // record: per-test results for CSV export
```

- `StressTest` is started/stopped from the FX thread but **runs on worker
  threads**; progress and safety checks run on a 1 s scheduler thread (reusing
  the same OSHI temperature read as the Sensors window).
- CPU test targets `availableProcessors()` threads by default; a subset
  selector allows leaving cores free so the app stays responsive.
- Memory test is **sized before the run** (not grown during) so it cannot
  trigger the Windows OOM killer on the running system.
- Disk test only touches a temporary file inside the system temp dir and
  deletes it in a `finally` block, even on early stop.

## 4. Safety

| Hazard | Mitigation |
|---|---|
| Overheating | Temperature watchdog thread; auto-stop above the configured limit (default 90 °C). CPU temp comes from OSHI; if the platform reports no temp (`n/a`), the watchdog is skipped and a warning is shown instead. |
| Memory exhaustion | Allocation is fixed up front and capped at a safe % of free RAM; buffers are touched immediately so they are really committed. |
| Disk wear / free space | Only a temp file, capped size, deleted afterwards. |
| Non-responsive system | CPU test leaves a configurable number of cores free (default 0 but can be set); UI runs on its own thread. |
| Long runs | Duration is optional; every run is interruptible with Stop and by closing the window. |

## 5. CLI (stretch goal)

`java -jar UltraMonitor.jar stress --cpu --memory --duration 300 --report out.csv`
— headless stress runs for scripts and CI, reusing the same engine and the
same safety watchdog. Implemented only after the GUI flow is stable.

## 6. Milestones

| # | Content | Done when |
|---|---|---|
| M1 | CPU test end-to-end (card, threads selector, start/stop, live load + temp, auto-stop) | Full-load run shows live stats and stops on temperature |
| M2 | Memory test + progress bars + result summaries | Test finishes/aborts cleanly, summary correct |
| M3 | Disk test + temp-file lifecycle | File created and deleted; random/sequential modes work |
| M4 | Confirmation dialog, combined runs (CPU+RAM+disk at once), CSV report export | All safety rules verified, report matches reality |
| M5 | CLI commands + documentation | `--help` works, CI runs a short CPU test |

The Sensors window stays untouched during M1–M3; both features share the OSHI
provider and the interval configuration.
