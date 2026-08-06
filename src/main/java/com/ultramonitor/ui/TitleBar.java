package com.ultramonitor.ui;

import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import java.util.List;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/**
 * Custom frameless window title bar with drag support and
 * minimize / maximize / close buttons.
 */
public final class TitleBar extends HBox {

    private final Stage stage;
    private List<Label> windowButtons = List.of();
    private double dragX;
    private double dragY;

    public TitleBar(Stage stage, String title, boolean maximizable) {
        this.stage = stage;
        getStyleClass().add("title-bar");

        Region dot = new Region();
        dot.getStyleClass().add("title-bar-dot");
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("title-bar-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label minButton = windowButton("–", "title-btn-min");
        minButton.setOnMouseClicked(e -> stage.setIconified(true));

        Label maxButton = windowButton("▢", "title-btn-max");
        maxButton.setOnMouseClicked(e -> stage.setMaximized(!stage.isMaximized()));
        stage.maximizedProperty().addListener((obs, old, now) -> maxButton.setText(now ? "❐" : "▢"));

        Label closeButton = windowButton("✕", "title-btn-close");
        closeButton.setOnMouseClicked(e -> stage.hide());
        this.windowButtons = List.of(minButton, maxButton, closeButton);

        getChildren().addAll(dot, titleLabel, spacer, minButton);
        if (maximizable) {
            getChildren().add(maxButton);
        }
        getChildren().add(closeButton);
        setAlignment(Pos.CENTER_LEFT);

        initDrag(maximizable);
    }

    private static Label windowButton(String glyph, String styleClass) {
        Label button = new Label(glyph);
        button.getStyleClass().addAll("title-btn", styleClass);
        return button;
    }

    private void initDrag(boolean maximizable) {
        setCursor(Cursor.OPEN_HAND);
        setOnMousePressed(e -> {
            dragX = e.getSceneX();
            dragY = e.getSceneY();
        });
        setOnMouseDragged(e -> {
            if (stage.isMaximized()) {
                stage.setMaximized(false);
                dragX = e.getSceneX();
                dragY = e.getSceneY();
                return;
            }
            stage.setX(e.getScreenX() - dragX);
            stage.setY(e.getScreenY() - dragY);
        });
        if (maximizable) {
            setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !windowButtons.contains(e.getTarget())) {
                    stage.setMaximized(!stage.isMaximized());
                }
            });
        }
    }
}
