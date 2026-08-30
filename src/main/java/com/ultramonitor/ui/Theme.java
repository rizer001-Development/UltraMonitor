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
import com.ultramonitor.config.AppConfig;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
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

    /**
     * The custom icon file loaded next to the app (see {@link AppConfig#appDir()}).
     * Placing a file here overrides the bundled icon so users can brand the app
     * without rebuilding. Square PNG with transparency.
     */
    public static final String EXTERNAL_ICON = "app-icon.png";

    /**
     * Loads the app icon. If {@code app-icon.png} exists next to the jar (or in
     * the portables folder) it is used and overrides the bundled sizes, so the
     * user can drop in their own icon without a rebuild.
     */
    public static Image[] appIcons() {
        List<Image> images = new ArrayList<>();

        Path external = AppConfig.appDir().resolve(EXTERNAL_ICON);
        if (Files.isReadable(external)) {
            try {
                // The whole image is used; JavaFX derives the smaller sizes.
                images.add(new Image(external.toUri().toString()));
                return images.toArray(new Image[0]);
            } catch (Throwable ignored) {
                // Malformed custom file — fall through to the bundled icon.
                images.clear();
            }
        }

        // Bundled fallback at every size JavaFX/Windows uses.
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
