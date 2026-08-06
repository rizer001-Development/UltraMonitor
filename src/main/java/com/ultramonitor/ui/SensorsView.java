package com.ultramonitor.ui;

import com.ultramonitor.config.AppConfig;
import com.ultramonitor.monitoring.InfoEntry;
import com.ultramonitor.monitoring.LiveStats;
import com.ultramonitor.monitoring.MetricsCollector;
import com.ultramonitor.monitoring.OshiMetricsProvider;
import com.ultramonitor.monitoring.SensorReading;
import com.ultramonitor.monitoring.SensorRow;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * The sensors window with two tabs:
 * <ul>
 *   <li><b>Sensors</b> — every sensor OSHI finds, as a live
 *       {@code Current / Min / Avg / Max} table;</li>
 *   <li><b>System Info</b> — the full hardware inventory grouped by section,
 *       with live values highlighted.</li>
 * </ul>
 * Both refresh at the interval (1–10,000 ms) configured in the bottom bar,
 * which is persisted to {@code config.json}.
 */
public final class SensorsView {

    private static final long UI_THROTTLE_NANOS = 100_000_000L; // 100 ms
    /** Show every sensor until this grace period passes, then hide the ones
     *  that never produced a real value. Time-based (not sample-count-based)
     *  so slow machines are not punished. */
    private static final long HIDE_UNAVAILABLE_GRACE_NANOS = 5_000_000_000L;
    /** The System Info inventory is rebuilt at most this often, independently. */
    private static final long SYSTEM_INFO_INTERVAL_MS = 2000;

    private final LiveStats stats = new LiveStats();
    private final ObservableList<SensorRow> rows = FXCollections.observableArrayList();
    private final TableView<SensorRow> sensorTable = new TableView<>(rows);
    private final ObservableList<InfoRow> infoRows = FXCollections.observableArrayList();
    private final TableView<InfoRow> infoTable = new TableView<>(infoRows);
    private final TextField intervalField = new TextField();
    private final Label statusLabel = new Label();
    private final Label savedLabel = new Label("✓ Saved");
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ultramonitor-sampler");
        thread.setDaemon(true);
        return thread;
    });
    // Dedicated thread: the system inventory queries are slow and must never
    // delay the sensor sampler, or the sensors table would freeze.
    private final ScheduledExecutorService systemSampler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ultramonitor-system");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong lastUiNanos = new AtomicLong();
    private final AtomicBoolean infoTabSelected = new AtomicBoolean();
    /** Keys that produced a real value at least once since the window opened. */
    private final java.util.Set<String> everAvailable = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final long openedAtNanos = System.nanoTime();
    private TabPane tabs;

    private volatile MetricsCollector collector;
    private ScheduledFuture<?> sampleTask;
    private int intervalMs = AppConfig.loadIntervalMs();

    public final Pane root;

    public SensorsView(Stage stage) {
        VBox shell = new VBox();
        shell.getStyleClass().add("window");

        tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(new Tab("Sensors", sensorArea()));
        tabs.getTabs().add(new Tab("System Info", infoArea()));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        tabs.getSelectionModel().selectedIndexProperty().addListener(
                (obs, old, idx) -> infoTabSelected.set(idx != null && idx.intValue() == 1));
        infoTabSelected.set(tabs.getSelectionModel().getSelectedIndex() == 1);

        shell.getChildren().addAll(new TitleBar(stage, "Sensors", true), tabs, bottomBar());
        root = shell;
        applyInterval(intervalMs);
        systemSampler.scheduleWithFixedDelay(this::sampleSystem, 300, SYSTEM_INFO_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void close() {
        sampler.shutdownNow();
        systemSampler.shutdownNow();
        if (collector != null) {
            collector.close();
        }
    }

    // ------------------------------------------------------------------ UI --

    private Pane sensorArea() {
        sensorTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        sensorTable.setSelectionModel(null);
        sensorTable.setFocusTraversable(false);
        sensorTable.getStyleClass().add("sensor-table");

        Label placeholder = new Label("Reading sensors…");
        placeholder.getStyleClass().add("table-placeholder");
        sensorTable.setPlaceholder(placeholder);

        sensorTable.getColumns().add(column("Sensor", SensorRow::name, "name-cell", false));
        sensorTable.getColumns().add(column("Current", SensorRow::current, "value-cell current-cell", true));
        sensorTable.getColumns().add(column("Min", SensorRow::min, "value-cell", true));
        sensorTable.getColumns().add(column("Avg", SensorRow::avg, "value-cell", true));
        sensorTable.getColumns().add(column("Max", SensorRow::max, "value-cell", true));

        VBox area = new VBox(sensorTable);
        area.setPadding(new Insets(2, 18, 8, 18));
        VBox.setVgrow(sensorTable, Priority.ALWAYS);
        return area;
    }

    private Pane infoArea() {
        infoTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        infoTable.setSelectionModel(null);
        infoTable.setFocusTraversable(false);
        infoTable.getStyleClass().add("sensor-table");

        Label placeholder = new Label("Collecting system information…");
        placeholder.getStyleClass().add("table-placeholder");
        infoTable.setPlaceholder(placeholder);

        TableColumn<InfoRow, String> itemColumn = new TableColumn<>("Item");
        itemColumn.setPrefWidth(280);
        itemColumn.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().item()));
        itemColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("info-section-label");
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                InfoRow row = getTableRow() == null ? null : getTableRow().getItem();
                setText(item);
                if (row != null && row.header()) {
                    getStyleClass().add("info-section-label");
                }
            }
        });

        TableColumn<InfoRow, String> valueColumn = new TableColumn<>("Value");
        valueColumn.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().value()));
        valueColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("info-value-label");
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(item);
                getStyleClass().add("info-value-label");
            }
        });

        infoTable.getColumns().add(itemColumn);
        infoTable.getColumns().add(valueColumn);

        infoTable.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(InfoRow item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("info-section-row", "info-live-row");
                if (!empty && item != null) {
                    if (item.header()) {
                        getStyleClass().add("info-section-row");
                    } else if (item.live()) {
                        getStyleClass().add("info-live-row");
                    }
                }
            }
        });

        VBox area = new VBox(infoTable);
        area.setPadding(new Insets(2, 18, 8, 18));
        VBox.setVgrow(infoTable, Priority.ALWAYS);
        return area;
    }

    private Pane bottomBar() {
        HBox bar = new HBox(10);
        bar.getStyleClass().add("sensor-bottom");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(14, 22, 16, 22));

        Label rateLabel = new Label("Update rate (ms):");
        rateLabel.getStyleClass().add("field-label");

        intervalField.setText(String.valueOf(intervalMs));
        intervalField.setPrefColumnCount(5);
        intervalField.setAlignment(Pos.CENTER_RIGHT);
        intervalField.getStyleClass().add("interval-field");
        intervalField.textProperty().addListener((obs, old, text) -> {
            String sanitized = text.replaceAll("\\D", "");
            if (!sanitized.equals(text)) {
                intervalField.setText(sanitized);
            } else if (sanitized.length() > 5) {
                intervalField.setText(sanitized.substring(0, 5));
            }
        });
        intervalField.setOnAction(e -> saveInterval());

        Label msLabel = new Label("ms");
        msLabel.getStyleClass().add("hint");
        Label hint = new Label("(1 – 10,000)");
        hint.getStyleClass().add("hint");

        Button save = new Button("Save");
        save.getStyleClass().addAll("btn", "btn-primary");
        save.setOnAction(e -> saveInterval());

        savedLabel.getStyleClass().add("saved-label");
        savedLabel.setOpacity(0);
        savedLabel.setVisible(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusLabel.getStyleClass().add("field-label");

        bar.getChildren().addAll(rateLabel, intervalField, msLabel, hint, save, savedLabel, spacer, statusLabel, livePill());
        return bar;
    }

    private static Pane livePill() {
        Circle dot = new Circle(4);
        dot.getStyleClass().add("live-dot");
        Label live = new Label("LIVE");
        live.getStyleClass().add("live-label");
        HBox pill = new HBox(6);
        pill.setAlignment(Pos.CENTER);
        pill.getStyleClass().add("pill");
        pill.getChildren().addAll(dot, live);
        Theme.pulse(dot, Duration.millis(900)).play();
        return pill;
    }

    private TableColumn<SensorRow, String> column(String header,
                                                  Function<SensorRow, String> getter,
                                                  String styleClass, boolean valueColumn) {
        TableColumn<SensorRow, String> column = new TableColumn<>(header);
        column.setCellValueFactory(d -> new ReadOnlyStringWrapper(getter.apply(d.getValue())));
        String[] tokens = styleClass.split("\\s+");
        column.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("name-cell", "value-cell", "current-cell", "unavailable-cell");
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                SensorRow row = getTableRow() == null ? null : getTableRow().getItem();
                setText(item);
                getStyleClass().addAll(tokens);
                if (valueColumn && row != null && !row.available()) {
                    getStyleClass().add("unavailable-cell");
                }
            }
        });
        return column;
    }

    // ------------------------------------------------------------ sampling --

    private void sample() {
        try {
            MetricsCollector provider = collector();
            List<SensorReading> readings = provider.sample();
            for (SensorReading reading : readings) {
                stats.update(reading.key(), reading.value());
            }
            boolean grace = System.nanoTime() - openedAtNanos < HIDE_UNAVAILABLE_GRACE_NANOS;
            List<SensorRow> built = new ArrayList<>(readings.size());
            for (SensorReading reading : readings) {
                if (reading.available()) {
                    everAvailable.add(reading.key());
                }
                // Sensors the platform can never read (temps without a driver,
                // battery details, fans…) only clutter the list with "—": hide
                // them once the grace period has passed without real data.
                boolean show = reading.available()
                        || grace
                        || everAvailable.contains(reading.key());
                if (!show) {
                    continue;
                }
                built.add(SensorRow.of(reading, stats));
            }
            long now = System.nanoTime();
            long last = lastUiNanos.get();
            if (now - last >= UI_THROTTLE_NANOS && lastUiNanos.compareAndSet(last, now)) {
                javafx.application.Platform.runLater(() -> rows.setAll(built));
            }
        } catch (Throwable ignored) {
            // A single failed sample must not kill the app.
        }
    }

    /**
     * Rebuilds the System Info tab on its own schedule so the heavier live
     * queries never delay the sensor table, and only while that tab is open.
     */
    private void sampleSystem() {
        if (!infoTabSelected.get()) {
            return;
        }
        try {
            MetricsCollector provider = collector();
            List<InfoRow> info = buildInfoRows(provider.systemInfo());
            javafx.application.Platform.runLater(() -> infoRows.setAll(info));
        } catch (Throwable ignored) {
            // A single failed refresh must not kill the app.
        }
    }

    private MetricsCollector collector() {
        MetricsCollector provider = collector;
        if (provider == null) {
            synchronized (this) {
                provider = collector;
                if (provider == null) {
                    provider = new OshiMetricsProvider();
                    collector = provider;
                }
            }
        }
        return provider;
    }

    private void applyInterval(int ms) {
        intervalMs = ms;
        if (sampleTask != null) {
            sampleTask.cancel(false);
        }
        sampleTask = sampler.scheduleWithFixedDelay(this::sample, 0, ms, TimeUnit.MILLISECONDS);
        statusLabel.setText("Updating every " + String.format(Locale.ROOT, "%,d", ms) + " ms");
    }

    private void saveInterval() {
        int value;
        try {
            value = Integer.parseInt(intervalField.getText());
        } catch (NumberFormatException e) {
            value = -1;
        }
        intervalField.getStyleClass().remove("invalid");
        if (value < AppConfig.MIN_INTERVAL_MS || value > AppConfig.MAX_INTERVAL_MS) {
            intervalField.getStyleClass().add("invalid");
            statusLabel.setText("Enter a value between " + AppConfig.MIN_INTERVAL_MS
                    + " and " + AppConfig.MAX_INTERVAL_MS);
            return;
        }
        if (AppConfig.saveIntervalMs(value)) {
            applyInterval(value);
            flashSaved();
        } else {
            statusLabel.setText("Could not write config.json");
        }
    }

    private void flashSaved() {
        savedLabel.setVisible(true);
        savedLabel.setOpacity(0);
        Timeline timeline = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(savedLabel.opacityProperty(), 0)),
                new KeyFrame(Duration.millis(220), new KeyValue(savedLabel.opacityProperty(), 1)),
                new KeyFrame(Duration.millis(1500), new KeyValue(savedLabel.opacityProperty(), 1)),
                new KeyFrame(Duration.millis(1950), new KeyValue(savedLabel.opacityProperty(), 0)));
        timeline.setOnFinished(e -> savedLabel.setVisible(false));
        timeline.play();
    }

    /** Groups a flat entry list into section-header rows followed by value rows. */
    private static List<InfoRow> buildInfoRows(List<InfoEntry> entries) {
        List<InfoRow> result = new ArrayList<>(entries.size() + 8);
        String currentSection = null;
        for (InfoEntry entry : entries) {
            if (!entry.section().equals(currentSection)) {
                currentSection = entry.section();
                result.add(new InfoRow(currentSection, "", true, false));
            }
            result.add(new InfoRow(entry.label(), entry.value(), false, entry.live()));
        }
        return result;
    }

    /** A display row for the System Info tab; header rows render the section name. */
    private record InfoRow(String item, String value, boolean header, boolean live) {
    }
}
