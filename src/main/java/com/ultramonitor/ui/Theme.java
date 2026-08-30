package com.ultramonitor.ui;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared UI helpers: transparent-window decoration, stylesheet loading and
 * small pulse animations.
 */
public final class Theme {

    private Theme() {
    }

    /** Builds a scene on a transparent stage with the app stylesheet applied. */
    public static Scene scene(Pane root, double width, double height) {
        Scene scene = new Scene(root, width, height);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(Theme.class.getResource("styles.css").toExternalForm());
        return scene;
    }

    /**
     * Makes a stage frameless/transparent and applies the app icon so windows
     * don't fall back to the generic Java icon. Call before showing.
     */
    public static void decorate(Stage stage) {
        stage.initStyle(StageStyle.TRANSPARENT);
        Image[] icons = appIcons();
        if (icons.length > 0) {
            stage.getIcons().setAll(icons);
        }
    }

    /** Loads the bundled UltraMonitor icon at every size JavaFX/Windows uses. */
    public static Image[] appIcons() {
        List<Image> images = new ArrayList<>();
        for (int size : new int[] {16, 32, 48, 64, 128, 256}) {
            var url = Theme.class.getResource("icons/app-icon-" + size + ".png");
            if (url != null) {
                images.add(new Image(url.toExternalForm()));
            }
        }
        return images.toArray(new Image[0]);
    }

    /** Repeating opacity pulse (used for the "LIVE" indicator). */
    public static Timeline pulse(Node node, Duration period) {
        Timeline timeline = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(node.opacityProperty(), 1.0)),
                new KeyFrame(period, new KeyValue(node.opacityProperty(), 0.3)),
                new KeyFrame(period.multiply(2), new KeyValue(node.opacityProperty(), 1.0)));
        timeline.setCycleCount(Timeline.INDEFINITE);
        return timeline;
    }
}
