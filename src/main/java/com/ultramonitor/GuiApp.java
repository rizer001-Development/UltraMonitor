package com.ultramonitor;

import com.ultramonitor.ui.MainMenuView;
import com.ultramonitor.ui.SensorsView;
import com.ultramonitor.ui.StressTestView;
import com.ultramonitor.ui.Theme;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

/**
 * JavaFX application: the main menu owns two toggle switches that open and
 * close the Sensors and Stress Test windows.
 *
 * <p>Each window is rebuilt on every open and torn down on every close, so its
 * sampler/ticker threads are shut down while hidden and never start twice
 * (previously the cached StressTestView was closed but reused, leaving a dead
 * ticker on the next open).</p>
 */
public final class GuiApp extends Application {

    private MainMenuView menu;
    private Stage sensorsStage;
    private SensorsView sensorsView;
    private Stage stressStage;
    private StressTestView stressView;

    @Override
    public void start(Stage stage) {
        menu = new MainMenuView(stage);
        stage.setScene(Theme.scene(menu.root, 560, 470));
        stage.setResizable(false);
        Theme.decorate(stage);
        stage.setOnHidden(e -> Platform.exit());
        stage.show();
        menu.animateIn();

        wireSwitches();
    }

    @Override
    public void stop() {
        if (sensorsView != null) {
            sensorsView.close();
        }
        if (stressView != null) {
            stressView.close();
        }
    }

    private void wireSwitches() {
        menu.sensorsSwitch.selectedProperty().addListener((obs, old, on) -> {
            if (on) {
                showSensors();
            } else {
                hideSensors();
            }
        });
        menu.stressSwitch.selectedProperty().addListener((obs, old, on) -> {
            if (on) {
                showStress();
            } else {
                hideStress();
            }
        });
    }

    private void showSensors() {
        if (sensorsStage == null) {
            sensorsStage = new Stage();
            sensorsView = new SensorsView(sensorsStage);
            sensorsStage.setScene(Theme.scene(sensorsView.root, 980, 700));
            Theme.decorate(sensorsStage);
            sensorsStage.setOnHidden(e -> {
                // stop sampling while hidden; the view is recreated on next open
                if (sensorsView != null) {
                    sensorsView.close();
                    sensorsView = null;
                }
                sensorsStage = null;
                menu.sensorsSwitch.setSelected(false);
            });
        }
        sensorsStage.show();
        sensorsStage.toFront();
        sensorsStage.requestFocus();
    }

    private void hideSensors() {
        if (sensorsStage != null) {
            sensorsStage.hide();
        }
    }

    private void showStress() {
        if (stressStage == null) {
            stressStage = new Stage();
            stressView = new StressTestView(stressStage);
            stressStage.setScene(Theme.scene(stressView.root, 880, 660));
            Theme.decorate(stressStage);
            stressStage.setOnHidden(e -> {
                if (stressView != null) {
                    stressView.close();
                    stressView = null;
                }
                stressStage = null;
                menu.stressSwitch.setSelected(false);
            });
        }
        stressStage.show();
        stressStage.toFront();
        stressStage.requestFocus();
    }

    private void hideStress() {
        if (stressStage != null) {
            stressStage.hide();
        }
    }
}