package com.ultramonitor.ui;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * Main menu: program name, description and toggle cards that open the
 * Sensors and Stress Test windows.
 */
public final class MainMenuView {

    public final Pane root;
    public final SwitchControl sensorsSwitch = new SwitchControl();
    public final SwitchControl stressSwitch = new SwitchControl();

    private static final String THERMOMETER_ICON = "M14 4v10.54a4 4 0 1 1-4 0V4a2 2 0 0 1 4 0Z";
    private static final String ACTIVITY_ICON = "M22 12h-4l-3 9L9 3l-3 9H2";

    public MainMenuView(Stage stage) {
        VBox shell = new VBox();
        shell.getStyleClass().add("window");

        TitleBar titleBar = new TitleBar(stage, "UltraMonitor", false);

        VBox content = new VBox(16);
        content.setPadding(new Insets(26, 30, 20, 30));
        content.getStyleClass().add("menu-content");

        content.getChildren().addAll(
                brand(),
                description(),
                cards(),
                footer());

        VBox.setVgrow(content, Priority.ALWAYS);
        shell.getChildren().addAll(titleBar, content);
        root = shell;
    }

    /** Soft entrance animation for the menu content. */
    public void animateIn() {
        FadeTransition fade = new FadeTransition(Duration.millis(420), root);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        TranslateTransition slide = new TranslateTransition(Duration.millis(420), root);
        slide.setFromY(14);
        slide.setToY(0);
        new ParallelTransition(fade, slide).play();
    }

    private Pane brand() {
        HBox row = new HBox(16);
        row.setAlignment(Pos.CENTER_LEFT);

        Label logo = new Label("UM");
        logo.getStyleClass().add("logo-text");
        StackPane logoBox = new StackPane(logo);
        logoBox.getStyleClass().add("logo-box");

        VBox texts = new VBox(1);
        Text title = new Text("UltraMonitor");
        title.getStyleClass().add("brand-title");
        Label tagline = new Label("Real-time hardware monitoring & stress testing");
        tagline.getStyleClass().add("tagline");
        texts.getChildren().addAll(title, tagline);

        row.getChildren().addAll(logoBox, texts);
        return row;
    }

    private Region description() {
        Label desc = new Label(
                "Live sensors for your CPU, memory, disks and network — plus CPU, memory and disk "
                        + "stress tests to push your hardware to its limits. Portable and open source.");
        desc.getStyleClass().add("desc");
        desc.setWrapText(true);
        return desc;
    }

    private Pane cards() {
        VBox cards = new VBox(12);
        cards.getChildren().addAll(
                card("Sensors", "Live hardware sensors", THERMOMETER_ICON, sensorsSwitch),
                card("Stress Test", "CPU, RAM, disk & GPU load", ACTIVITY_ICON, stressSwitch));
        return cards;
    }

    private Region footer() {
        Label footer = new Label("v0.1.0   •   Open source   •   AGPLv3");
        footer.getStyleClass().add("footer");
        return footer;
    }

    private Region card(String title, String subtitle, String iconPath, SwitchControl sw) {
        HBox card = new HBox(14);
        card.getStyleClass().add("card");
        card.setAlignment(Pos.CENTER_LEFT);

        StackPane iconBox = new StackPane();
        iconBox.getStyleClass().add("card-icon");
        SVGPath icon = new SVGPath();
        icon.setContent(iconPath);
        icon.getStyleClass().add("card-svg");
        iconBox.getChildren().add(icon);

        VBox texts = new VBox(2);
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("card-title");
        Label subtitleLabel = new Label(subtitle);
        subtitleLabel.getStyleClass().add("card-subtitle");
        texts.getChildren().addAll(titleLabel, subtitleLabel);
        HBox.setHgrow(texts, Priority.ALWAYS);

        card.getChildren().addAll(iconBox, texts, sw);
        card.setOnMouseClicked(e -> sw.setSelected(!sw.isSelected()));
        return card;
    }
}
