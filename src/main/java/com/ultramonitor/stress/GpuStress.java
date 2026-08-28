package com.ultramonitor.stress;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BoxBlur;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Sphere;
import javafx.scene.transform.Rotate;
import javafx.stage.Stage;

import java.awt.AlphaComposite;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.util.ArrayList;
import java.util.List;

/**
 * Pushes the GPU through the JavaFX rendering pipeline (Prism), which is
 * hardware-accelerated on Windows via Direct3D. It opens one or two render
 * windows, each showing a rotating, texture-mapped 3D sphere lit by two
 * coloured lights, overlaid with a translucent canvas that is re-blurred every
 * frame and redrawn with dozens of gradient shapes and alpha-blended image
 * blits — a workload that saturates the vertex, fragment and pixel pipelines.
 *
 * <p>In headless environments (CI, servers) it falls back to the CPU-only AWT
 * renderer so the test still runs and can be stopped cleanly.</p>
 */
public final class GpuStress implements StressTest {

    private static final int WINDOWS = 2;
    private static final int VIEW_W = 640;
    private static final int VIEW_H = 480;
    private static final int SHAPES_PER_FRAME = 28;

    private final List<GpuWindow> windows = new ArrayList<>();
    private final List<Thread> awtWorkers = new ArrayList<>();
    private volatile AnimationTimer timer;
    private volatile WritableImage noise;
    private volatile boolean running;
    private volatile long sink;
    private static volatile boolean fxReady;
    private static volatile boolean fxStartedByUs;

    @Override
    public String name() {
        return "GPU";
    }

    @Override
    public String status() {
        return "hardware-accelerated rendering";
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void start() {
        running = true;
        if (GraphicsEnvironment.isHeadless()) {
            startAwtFallback();
        } else {
            startFx();
        }
    }

    @Override
    public void stop() {
        running = false;
        if (timer != null || !windows.isEmpty()) {
            Platform.runLater(() -> {
                if (timer != null) {
                    timer.stop();
                }
                for (GpuWindow window : windows) {
                    window.stage.hide();
                }
            });
            // If we bootstrapped JavaFX ourselves (CLI / tests), shut it down so
            // the JVM is not kept alive by the FX thread.
            if (fxStartedByUs) {
                Platform.exit();
            }
        } else {
            // AWT fallback workers.
            for (Thread worker : awtWorkers) {
                try {
                    worker.join(300);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            awtWorkers.clear();
        }
    }

    // ------------------------------------------------------------ FX path --

    private void startFx() {
        if (fxReady) {
            Platform.runLater(this::showWindows);
            return;
        }
        try {
            Platform.startup(this::initFx);
            fxStartedByUs = true;
        } catch (IllegalStateException alreadyRunning) {
            // The GUI app already bootstrapped the FX toolkit.
            Platform.runLater(this::initFx);
        }
        fxReady = true;
    }

    private void initFx() {
        if (timer != null) {
            return;
        }
        noise = makeNoise();
        for (int i = 0; i < WINDOWS; i++) {
            windows.add(new GpuWindow(120 + i * 640, 80 + i * 40));
        }
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                double t = now / 1_000_000_000.0;
                for (GpuWindow window : windows) {
                    window.render(t);
                }
                if (!running) {
                    timer.stop();
                }
            }
        };
        timer.start();
    }

    private void showWindows() {
        for (GpuWindow window : windows) {
            window.stage.show();
            window.stage.toFront();
        }
        if (timer != null) {
            timer.start();
        }
    }

    /** Pseudo-random texture used as the sphere's diffuse map and 2D overlay. */
    private WritableImage makeNoise() {
        WritableImage image = new WritableImage(VIEW_W, VIEW_H);
        PixelWriter writer = image.getPixelWriter();
        int seed = 12345;
        for (int y = 0; y < VIEW_H; y++) {
            for (int x = 0; x < VIEW_W; x++) {
                seed = seed * 1664525 + 1013904223;
                int r = (seed >>> 16) & 0xFF;
                int g = (seed >>> 8) & 0xFF;
                int b = seed & 0xFF;
                writer.setArgb(x, y, (0xFF << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    /** One render window: textured 3D sphere + blurred 2D overlay. */
    private final class GpuWindow {

        final Stage stage;
        final Canvas canvas;
        final Rotate rotateX = new Rotate(0, Rotate.X_AXIS);
        final Rotate rotateY = new Rotate(0, Rotate.Y_AXIS);

        GpuWindow(double x, double y) {
            Sphere sphere = new Sphere(170, 96);
            PhongMaterial material = new PhongMaterial(Color.WHITE);
            material.setDiffuseMap(noise);
            sphere.setMaterial(material);
            sphere.getTransforms().addAll(rotateX, rotateY);

            PointLight light1 = new PointLight(Color.WHITE);
            light1.setTranslateX(360);
            light1.setTranslateY(260);
            light1.setTranslateZ(420);
            PointLight light2 = new PointLight(Color.PALEGOLDENROD);
            light2.setTranslateX(-360);
            light2.setTranslateY(-260);
            light2.setTranslateZ(-320);

            Group world = new Group(sphere, light1, light2);
            PerspectiveCamera camera = new PerspectiveCamera(true);
            camera.setTranslateZ(-560);
            SubScene subScene = new SubScene(world, VIEW_W, VIEW_H, true, SceneAntialiasing.BALANCED);
            subScene.setCamera(camera);

            canvas = new Canvas(VIEW_W, VIEW_H);
            Group overlay = new Group(canvas);
            overlay.setEffect(new BoxBlur(6, 6, 3));

            StackPane root = new StackPane(subScene, overlay);
            Scene scene = new Scene(root, VIEW_W, VIEW_H, true);
            scene.setFill(Color.TRANSPARENT);

            stage = new Stage();
            stage.setTitle("UltraMonitor GPU Stress");
            stage.setScene(scene);
            stage.setX(x);
            stage.setY(y);
            stage.setResizable(false);
        }

        void render(double t) {
            rotateX.setAngle((t * 45) % 360);
            rotateY.setAngle((t * 30) % 360);

            GraphicsContext gc = canvas.getGraphicsContext2D();
            double w = canvas.getWidth();
            double h = canvas.getHeight();
            gc.clearRect(0, 0, w, h);

            // Rotated cluster of translucent gradient shapes.
            gc.save();
            gc.translate(w / 2, h / 2);
            gc.rotate((t * 70) % 360);
            for (int i = 0; i < SHAPES_PER_FRAME; i++) {
                gc.setFill(Color.hsb((t * 45 + i * 15) % 360, 0.8, 0.95, 0.35));
                double r = 36 + (i % 6) * 16;
                gc.fillOval(-r - (i % 5) * 30, -r + (i % 4) * 24, r * 2, r * 2);
                gc.fillArc((i % 5) * 44, -(i % 3) * 32, 90, 90, (t * 50 + i * 20) % 360, 120, ArcType.ROUND);
            }
            gc.restore();

            // Alpha-blended full-canvas image blit — heavy compositing bandwidth.
            gc.setGlobalAlpha(0.25);
            gc.save();
            gc.translate(w / 2, h / 2);
            gc.rotate((t * 40) % 360);
            gc.drawImage(noise, -w / 2, -h / 2, w, h);
            gc.restore();
            gc.setGlobalAlpha(1.0);
        }
    }

    // ----------------------------------------------------- AWT fallback ----

    private void startAwtFallback() {
        for (int i = 0; i < 2; i++) {
            Thread thread = new Thread(this::awtRenderLoop, "ultramonitor-gpu-" + i);
            thread.setDaemon(true);
            thread.start();
            awtWorkers.add(thread);
        }
    }

    private void awtRenderLoop() {
        int size = 512;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        BufferedImage blurred = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        BufferedImage overlay = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);

        float[] kernelData = new float[9];
        java.util.Arrays.fill(kernelData, 1f / 9f);
        ConvolveOp blur = new ConvolveOp(new Kernel(3, 3, kernelData), ConvolveOp.EDGE_NO_OP, null);

        long frames = 0;
        double angle = 0;
        try {
            while (running) {
                angle += 0.02;
                if (angle > Math.PI * 2) {
                    angle = 0;
                }
                float hue = (float) ((frames % 360) / 360.0);
                graphics.setComposite(AlphaComposite.Src);
                graphics.setPaint(new GradientPaint(0, 0, java.awt.Color.getHSBColor(hue, 1f, 1f),
                        size, size, java.awt.Color.BLACK));
                graphics.fillRect(0, 0, size, size);

                AffineTransform saved = graphics.getTransform();
                graphics.rotate(angle, size / 2.0, size / 2.0);
                for (int i = 0; i < 8; i++) {
                    float h = (hue + i * 0.05f) % 1f;
                    graphics.setPaint(new GradientPaint(0, 0,
                            java.awt.Color.getHSBColor(h, 1f, 0.9f), size, size, java.awt.Color.BLACK));
                    graphics.fillOval((i * 97) % size, (i * 53) % size, size / 3, size / 3);
                    graphics.fillRect((i * 71) % size, (i * 29) % size, size / 4, size / 4);
                }
                graphics.setTransform(saved);

                Graphics2D og = overlay.createGraphics();
                og.setComposite(AlphaComposite.Src);
                og.setColor(java.awt.Color.BLACK);
                og.fillRect(0, 0, size, size);
                og.setComposite(AlphaComposite.SrcOver.derive(0.45f));
                og.setPaint(new GradientPaint(0, 0, java.awt.Color.WHITE, size, size, java.awt.Color.CYAN));
                og.fillOval(size / 4, size / 4, size / 2, size / 2);
                og.dispose();
                graphics.drawImage(overlay, 0, 0, null);

                if ((frames & 31) == 0) {
                    blur.filter(image, blurred);
                    graphics.drawImage(blurred, 0, 0, null);
                }
                frames++;
            }
        } finally {
            graphics.dispose();
        }
        sink = frames;
    }
}
