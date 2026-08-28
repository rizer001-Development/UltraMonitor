package com.ultramonitor.ui;

import com.ultramonitor.monitoring.MetricsCollector;
import com.ultramonitor.monitoring.OshiMetricsProvider;
import com.ultramonitor.monitoring.SensorReading;
import com.ultramonitor.stress.CpuStress;
import com.ultramonitor.stress.DiskStress;
import com.ultramonitor.stress.GpuStress;
import com.ultramonitor.stress.MemoryStress;
import com.ultramonitor.stress.StressReporter;
import com.ultramonitor.stress.StressRunner;
import com.ultramonitor.stress.StressTest;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.Scene;
import javafx.scene.control.ProgressBar;
import javafx.stage.FileChooser;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The Stress Test window: four toggle cards (CPU, RAM, Disk, GPU) with their
 * parameters, live gauges (CPU load, RAM, temperature), an optional duration
 * and Start / Stop buttons. Runs on its own worker threads and auto-stops if
 * the CPU temperature exceeds a safe limit.
 */
public final class StressTestView {

    private static final double AUTO_STOP_TEMP_C = 90.0;
    private static final long TICK_NANOS = 500_000_000L; // 500 ms

    private static final String CPU_ICON =
            "M4 4h16v16H4z M9 1.5h6v2.5H9z M9 20h6v2.5H9z M1.5 9h2.5v6H1.5z M20 9h2.5v6H20z M9 9h6v6H9z";
    private static final String RAM_ICON = "M2 6h20v8H2z M6 14v5h12v-5 M8 19v2 M16 19v2 M6 9h12";
    private static final String DISK_ICON = "M3 5h18v6H3z M3 13h18v6H3z M6 8h.01 M6 16h.01";
    private static final String GPU_ICON = "M2 6h20v12H2z M8 12h8 M10 10v4 M14 10v4 M6 21h12";

    private final BooleanProperty runningProperty = new SimpleBooleanProperty(false);

    private final SwitchControl cpuSwitch = new SwitchControl();
    private final SwitchControl ramSwitch = new SwitchControl();
    private final SwitchControl diskSwitch = new SwitchControl();
    private final SwitchControl gpuSwitch = new SwitchControl();

    private final Slider cpuThreadsSlider = new Slider(1, Math.max(1, Runtime.getRuntime().availableProcessors()),
            Runtime.getRuntime().availableProcessors());
    private final Slider ramPercentSlider = new Slider(10, 80, 50);
    private final Slider diskMbSlider = new Slider(256, 4096, 512);

    private final Label cpuLoadLabel = new Label("—");
    private final Label ramLoadLabel = new Label("—");
    private final Label tempLabel = new Label("—");
    private final Label elapsedLabel = new Label("");
    private final Label statusLabel = new Label("Select one or more tests and press Start.");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final TextField durationField = new TextField();
    private final Button startButton = new Button("Start");
    private final Button stopButton = new Button("Stop");
    private final Button reportButton = new Button("Report");

    private final StressReporter reporter = new StressReporter();

    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ultramonitor-stress-ticker");
        thread.setDaemon(true);
        return thread;
    });

    private volatile MetricsCollector metrics;
    private volatile StressRunner runner;
    private volatile boolean uiRunning;

    public final Pane root;

    public StressTestView(Stage stage) {
        VBox shell = new VBox();
        shell.getStyleClass().add("window");

        VBox cards = new VBox(10);
        cards.setPadding(new Insets(14, 22, 6, 22));
        cards.getChildren().addAll(
                cpuCard(),
                ramCard(),
                diskCard(),
                gpuCard());

        Pane live = livePanel();
        Pane bottom = bottomBar();
        VBox.setVgrow(cards, Priority.ALWAYS);

        shell.getChildren().addAll(new TitleBar(stage, "Stress Test", true), cards, live, bottom);
        root = shell;

        runningProperty.addListener((obs, old, running) -> {
            cpuSwitch.setDisable(running);
            ramSwitch.setDisable(running);
            diskSwitch.setDisable(running);
            gpuSwitch.setDisable(running);
            startButton.setDisable(running);
            stopButton.setDisable(!running);
            reportButton.setDisable(running || reporter.sampleCount() == 0);
        });
        runningProperty.set(false);

        ticker.scheduleWithFixedDelay(this::tick, 300, 500, TimeUnit.MILLISECONDS);
    }

    /** Stops everything and shuts down the sampler; safe to call twice. */
    public void close() {
        StressRunner current = runner;
        if (current != null && current.isRunning()) {
            current.stop("Window closed");
        }
        ticker.shutdownNow();
        MetricsCollector provider = metrics;
        if (provider != null) {
            provider.close();
        }
    }

    // ------------------------------------------------------------------ UI --

    private Pane cpuCard() {
        int cores = Runtime.getRuntime().availableProcessors();
        Label value = new Label();
        cpuThreadsSlider.valueProperty().addListener((obs, old, now) ->
                value.setText(coresLabel((int) Math.round(now.doubleValue()), cores)));
        value.setText(coresLabel(cores, cores));
        return testCard("CPU", "Full floating-point load on every selected core",
                CPU_ICON, cpuSwitch, cpuThreadsSlider, value);
    }

    private Pane ramCard() {
        Label value = new Label();
        ramPercentSlider.valueProperty().addListener((obs, old, now) ->
                value.setText((int) Math.round(now.doubleValue()) + "% of available RAM"));
        value.setText("50% of available RAM");
        return testCard("RAM", "Allocate and constantly read/write memory buffers",
                RAM_ICON, ramSwitch, ramPercentSlider, value);
    }

    private Pane diskCard() {
        diskMbSlider.setBlockIncrement(256);
        Label value = new Label();
        diskMbSlider.valueProperty().addListener((obs, old, now) ->
                value.setText((int) Math.round(now.doubleValue()) + " MB temp file"));
        value.setText("512 MB temp file");
        return testCard("Disk", "Sequential writes and reads on a temporary file",
                DISK_ICON, diskSwitch, diskMbSlider, value);
    }

    private Pane gpuCard() {
        return testCard("GPU", "Rendering pipeline stress (accelerated graphics)",
                GPU_ICON, gpuSwitch, null, null);
    }

    private Pane testCard(String title, String subtitle, String iconPath,
                          SwitchControl sw, Slider slider, Label valueLabel) {
        HBox card = new HBox(14);
        card.getStyleClass().add("card");
        card.setAlignment(Pos.CENTER_LEFT);

        StackPane iconBox = new StackPane();
        iconBox.getStyleClass().add("card-icon");
        SVGPath icon = new SVGPath();
        icon.setContent(iconPath);
        icon.getStyleClass().add("card-svg");
        iconBox.getChildren().add(icon);

        VBox texts = new VBox(3);
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("card-title");
        Label subtitleLabel = new Label(subtitle);
        subtitleLabel.getStyleClass().add("card-subtitle");
        texts.getChildren().addAll(titleLabel, subtitleLabel);

        if (slider != null) {
            slider.getStyleClass().add("stress-slider");
            slider.setMaxWidth(280);
            slider.setPrefWidth(220);
            slider.disableProperty().bind(sw.selectedProperty().not());
            HBox paramRow = new HBox(10);
            paramRow.setAlignment(Pos.CENTER_LEFT);
            paramRow.getChildren().add(slider);
            if (valueLabel != null) {
                valueLabel.getStyleClass().add("param-value");
                valueLabel.disableProperty().bind(sw.selectedProperty().not());
                paramRow.getChildren().add(valueLabel);
            }
            texts.getChildren().add(paramRow);
        }

        HBox.setHgrow(texts, Priority.ALWAYS);
        card.getChildren().addAll(iconBox, texts, sw);
        return card;
    }

    private Pane livePanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(6, 22, 4, 22));
        panel.getStyleClass().add("stress-live");

        HBox gauges = new HBox(10);
        gauges.setAlignment(Pos.CENTER_LEFT);
        gauges.getChildren().addAll(
                gauge("CPU Load", cpuLoadLabel),
                gauge("RAM Load", ramLoadLabel),
                gauge("CPU Temp", tempLabel));

        HBox progressRow = new HBox(10);
        progressRow.setAlignment(Pos.CENTER_LEFT);
        progressBar.getStyleClass().add("stress-progress");
        progressBar.setPrefWidth(999);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(8);
        progressBar.setVisible(false);
        progressBar.managedProperty().bind(progressBar.visibleProperty());
        elapsedLabel.getStyleClass().add("elapsed-label");
        HBox.setHgrow(progressBar, Priority.ALWAYS);
        progressRow.getChildren().addAll(progressBar, elapsedLabel);

        statusLabel.getStyleClass().add("field-label");
        statusLabel.setWrapText(true);

        panel.getChildren().addAll(gauges, progressRow, statusLabel);
        return panel;
    }

    private Pane gauge(String caption, Label value) {
        VBox box = new VBox(0);
        box.getStyleClass().add("mini-gauge");
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(8, 16, 8, 16));
        Label captionLabel = new Label(caption);
        captionLabel.getStyleClass().add("gauge-caption");
        value.getStyleClass().add("gauge-value");
        box.getChildren().addAll(captionLabel, value);
        return box;
    }

    private Pane bottomBar() {
        HBox bar = new HBox(10);
        bar.getStyleClass().add("sensor-bottom");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(12, 22, 14, 22));

        Label durationLabel = new Label("Duration (sec):");
        durationLabel.getStyleClass().add("field-label");

        durationField.setPrefColumnCount(5);
        durationField.setAlignment(Pos.CENTER_RIGHT);
        durationField.getStyleClass().add("interval-field");
        durationField.setPromptText("∞");
        durationField.textProperty().addListener((obs, old, text) -> {
            String sanitized = text.replaceAll("\\D", "");
            if (!sanitized.equals(text)) {
                durationField.setText(sanitized);
            } else if (sanitized.length() > 5) {
                durationField.setText(sanitized.substring(0, 5));
            }
        });
        durationField.setOnAction(e -> startTests());

        Label hint = new Label("(empty = run until stopped)");
        hint.getStyleClass().add("hint");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        startButton.getStyleClass().addAll("btn", "btn-primary");
        startButton.setOnAction(e -> startTests());

        stopButton.getStyleClass().addAll("btn", "btn-danger");
        stopButton.setDisable(true);
        stopButton.setOnAction(e -> {
            StressRunner current = runner;
            if (current != null) {
                current.stop("Stopped by user");
            }
        });

        reportButton.getStyleClass().addAll("btn", "btn-secondary");
        reportButton.setDisable(true);
        reportButton.setOnAction(e -> exportReport());

        bar.getChildren().addAll(durationLabel, durationField, hint, spacer, startButton, reportButton, stopButton);
        return bar;
    }

    private static String coresLabel(int selected, int total) {
        return selected >= total
                ? "All " + total + " threads"
                : selected + " of " + total + " threads";
    }

    // ---------------------------------------------------------------- engine --

    private void startTests() {
        if (runningProperty.get()) {
            return;
        }
        List<StressTest> tests = new ArrayList<>();
        if (cpuSwitch.isSelected()) {
            tests.add(new CpuStress(threadCount()));
        }
        if (ramSwitch.isSelected()) {
            tests.add(new MemoryStress((int) Math.round(ramPercentSlider.getValue())));
        }
        if (diskSwitch.isSelected()) {
            tests.add(new DiskStress((long) Math.round(diskMbSlider.getValue())));
        }
        if (gpuSwitch.isSelected()) {
            tests.add(new GpuStress());
        }
        if (tests.isEmpty()) {
            statusLabel.setText("Select at least one test to run.");
            return;
        }
        long duration = parseDuration();
        if (duration < 0) {
            statusLabel.setText("Duration must be between 0 and 86,400 seconds.");
            return;
        }
        if (!confirmStart(tests, duration)) {
            return;
        }
        StressRunner created = new StressRunner(tests);
        try {
            created.start(duration);
        } catch (RuntimeException e) {
            statusLabel.setText("Could not start: " + e.getMessage());
            return;
        }
        reporter.start();
        runner = created;
        uiRunning = true;
        runningProperty.set(true);
        statusLabel.setText("Starting " + names(tests) + " …");
    }

    /**
     * Asks the user to confirm before pushing the hardware to full load.
     * Returns {@code true} when the run should proceed.
     */
    /**
     * Asks the user to confirm before pushing the hardware to full load.
     * Shows a themed frameless dialog (same look as the app windows).
     * Returns {@code true} when the run should proceed.
     */
    private boolean confirmStart(List<StressTest> tests, long duration) {
        final boolean[] approved = {false};

        Stage dialog = new Stage();
        dialog.initModality(Modality.WINDOW_MODAL);
        javafx.stage.Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        Theme.decorate(dialog);

        VBox shell = new VBox();
        shell.getStyleClass().add("window");

        HBox header = new HBox(14);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(14, 22, 6, 22));

        StackPane iconBox = new StackPane();
        iconBox.getStyleClass().add("card-icon");
        SVGPath warnIcon = new SVGPath();
        warnIcon.setContent("M12 3 2 21h20L12 3zm0 4 6.5 11h-13L12 7zm0 5.5a1 1 0 0 0-1 1v2a1 1 0 0 0 2 0v-2a1 1 0 0 0-1-1zm0 5a1.2 1.2 0 1 0 0 .01z");
        warnIcon.getStyleClass().add("dialog-warn-icon");
        iconBox.getChildren().add(warnIcon);

        VBox headerTexts = new VBox(2);
        Label titleLabel = new Label("Start Stress Test");
        titleLabel.getStyleClass().add("card-title");
        Label subtitleLabel = new Label("Push your hardware to full load?");
        subtitleLabel.getStyleClass().add("card-subtitle");
        headerTexts.getChildren().addAll(titleLabel, subtitleLabel);
        header.getChildren().addAll(iconBox, headerTexts);

        StringBuilder bodyText = new StringBuilder();
        bodyText.append("This will run: ").append(names(tests)).append(" at 100% load.");
        if (duration > 0) {
            bodyText.append("\nDuration: ").append(duration).append(" seconds.");
        } else {
            bodyText.append("\nRuns until you press Stop.");
        }
        bodyText.append("\n\nTemperatures will rise. UltraMonitor will stop the test automatically")
                .append(" if the CPU temperature exceeds ")
                .append(String.format(Locale.ROOT, "%.0f", AUTO_STOP_TEMP_C))
                .append(" °C.\n\nContinue?");
        Label body = new Label(bodyText.toString());
        body.getStyleClass().add("desc");
        body.setWrapText(true);
        body.setMaxWidth(400);
        VBox.setMargin(body, new Insets(4, 22, 4, 22));

        Button cancelButton = new Button("Cancel");
        cancelButton.getStyleClass().addAll("btn", "btn-secondary");
        cancelButton.setOnAction(e -> dialog.hide());

        Button startButton = new Button("Start");
        startButton.getStyleClass().addAll("btn", "btn-primary");
        startButton.setDefaultButton(true);
        startButton.setOnAction(e -> {
            approved[0] = true;
            dialog.hide();
        });

        HBox actions = new HBox(10);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setPadding(new Insets(10, 22, 18, 22));
        actions.getChildren().addAll(cancelButton, startButton);

        shell.getChildren().addAll(new TitleBar(dialog, "Confirm", false), header, body, actions);

        Scene scene = Theme.scene(shell, 460, 300);
        dialog.setScene(scene);
        dialog.setResizable(false);
        dialog.showAndWait();
        return approved[0];
    }

    private void stopBySystem(StressRunner current, String reason) {
        if (current != null) {
            current.stop(reason);
        }
    }

    private void tick() {
        try {
            List<SensorReading> readings = metrics().sample();
            double cpuLoad = value(readings, "cpu.load");
            double ramLoad = value(readings, "ram.load");
            double temp = value(readings, "cpu.temp");

            StressRunner current = runner;
            boolean running = current != null && current.isRunning();

            reporter.tick(cpuLoad, ramLoad, temp);

            String elapsed = running ? formatElapsed(current.elapsedSeconds()) : "";
            String status = buildStatus(current, running);

            javafx.application.Platform.runLater(() -> {
                cpuLoadLabel.setText(percent(cpuLoad));
                ramLoadLabel.setText(percent(ramLoad));
                tempLabel.setText(temp > 0 ? String.format(Locale.ROOT, "%.0f °C", temp) : "—");
                elapsedLabel.setText(elapsed);
                statusLabel.setText(status);
                if (running) {
                    double progress = current.progress();
                    progressBar.setVisible(true);
                    progressBar.setProgress(Double.isNaN(progress)
                            ? javafx.scene.control.ProgressIndicator.INDETERMINATE_PROGRESS
                            : progress);
                } else {
                    progressBar.setVisible(false);
                }
            });

            if (running) {
                if (temp > 0 && temp >= AUTO_STOP_TEMP_C) {
                    stopBySystem(current, "CPU temperature reached 90 °C — stopped automatically");
                } else if (current.isFinishedByTime()) {
                    stopBySystem(current, "Duration reached");
                }
            }

            if (uiRunning && (current == null || !current.isRunning())) {
                uiRunning = false;
                javafx.application.Platform.runLater(() -> {
                    runningProperty.set(false);
                    statusLabel.setText(current == null ? "Stopped." : "Finished — " + current.stopReason());
                });
            }
        } catch (Throwable ignored) {
            // A single failed tick must not kill the window.
        }
    }

    /** Opens a file dialog and writes the recorded run to a CSV report. */
    private void exportReport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Stress Test Report");
        chooser.setInitialFileName("stress-report.csv");
        File file = chooser.showSaveDialog(root.getScene() == null ? null : root.getScene().getWindow());
        if (file == null) {
            return;
        }
        Path target = file.toPath();
        if (!target.toString().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            target = target.resolveSibling(target.getFileName().toString() + ".csv");
        }
        String reason = runner == null ? null : runner.stopReason();
        if (reason == null || reason.isBlank()) {
            reason = null;
        }
        boolean ok = reporter.writeCsv(target, reason);
        statusLabel.setText(ok
                ? "Report saved to " + target.getFileName()
                : "Could not write report to " + target.getFileName());
    }

    private String buildStatus(StressRunner current, boolean running) {
        if (current == null) {
            return "Select one or more tests and press Start.";
        }
        String summary = current.tests().stream()
                .map(t -> t.name() + ": " + t.status())
                .collect(Collectors.joining("  ·  "));
        if (!running) {
            return "Finished — " + current.stopReason() + "  |  " + summary;
        }
        return summary;
    }

    private MetricsCollector metrics() {
        MetricsCollector provider = metrics;
        if (provider == null) {
            synchronized (this) {
                provider = metrics;
                if (provider == null) {
                    provider = new OshiMetricsProvider();
                    metrics = provider;
                }
            }
        }
        return provider;
    }

    private int threadCount() {
        return Math.max(1, (int) Math.round(cpuThreadsSlider.getValue()));
    }

    private long parseDuration() {
        String text = durationField.getText();
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            long value = Long.parseLong(text);
            return value >= 0 && value <= 86_400 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String names(List<StressTest> tests) {
        return tests.stream().map(StressTest::name).collect(Collectors.joining(" + "));
    }

    private static double value(List<SensorReading> readings, String key) {
        for (SensorReading reading : readings) {
            if (reading.key().equals(key)) {
                return reading.value();
            }
        }
        return Double.NaN;
    }

    private static String percent(double value) {
        return Double.isNaN(value) ? "—" : String.format(Locale.ROOT, "%.0f%%", value);
    }

    private static String formatElapsed(long seconds) {
        long minutes = seconds / 60;
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds % 60);
    }
}
