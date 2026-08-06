package com.ultramonitor.ui;

import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.scene.Cursor;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/**
 * Animated on/off toggle switch used on the main menu.
 */
public final class SwitchControl extends StackPane {

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final double KNOB_RADIUS = 9.0;
    private static final double KNOB_CENTER_X = 13.0;
    private static final double KNOB_CENTER_Y = 13.0;

    private final BooleanProperty selected = new SimpleBooleanProperty(false);
    private final Circle knob = new Circle(KNOB_RADIUS);

    public SwitchControl() {
        getStyleClass().add("switch");
        setPrefSize(46, 26);
        setMinSize(46, 26);
        setMaxSize(46, 26);

        knob.getStyleClass().add("knob");
        knob.setCenterX(KNOB_CENTER_X);
        knob.setCenterY(KNOB_CENTER_Y);
        getChildren().add(knob);

        setCursor(Cursor.HAND);
        setFocusTraversable(true);

        setOnMouseClicked(e -> {
            setSelected(!isSelected());
            e.consume();
        });
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.ENTER) {
                setSelected(!isSelected());
                e.consume();
            }
        });
        selected.addListener((obs, old, now) -> animate(now));
    }

    public BooleanProperty selectedProperty() {
        return selected;
    }

    public boolean isSelected() {
        return selected.get();
    }

    public void setSelected(boolean value) {
        selected.set(value);
    }

    private void animate(boolean on) {
        TranslateTransition transition = new TranslateTransition(Duration.millis(180), knob);
        transition.setToX(on ? 20.0 : 0.0);
        transition.setInterpolator(Interpolator.EASE_BOTH);
        transition.play();
        pseudoClassStateChanged(SELECTED, on);
    }
}
