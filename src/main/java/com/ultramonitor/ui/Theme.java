package com.ultramonitor.ui;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

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

    /** Makes a stage frameless/transparent; call before showing. */
    public static void decorate(Stage stage) {
        stage.initStyle(StageStyle.TRANSPARENT);
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
