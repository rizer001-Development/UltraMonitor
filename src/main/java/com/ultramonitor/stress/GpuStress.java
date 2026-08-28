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
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.paint.Stop;
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
 * hardware-accelerated on Windows via Direct3D. It opens two render windows,
 * each with a rotating, texture-mapped 3D sphere cluster lit by several
 * coloured lights, overlaid with a translucent canvas that is re-blurred every
 * frame and redrawn with dozens of gradient shapes and alpha-blended image
 * blits — a workload that saturates the vertex, fragment and pixel pipelines.
 *
 * <p>In headless environments (CI, servers) it falls back to the CPU-only AWT
 * renderer so the test still runs and can be stopped cleanly. The active Prism
 * pipeline is detected and reported by {@link #status()}, so a software
 * fallback is visible instead of silently doing nothing.</p>
 */
public final class GpuStress implements StressTest {

    private static final int WINDOWS = 2;
    private static final int VIEW_W = 1280;
    private static final int VIEW_H = 800;
    private static final int SHAPES_PER_FRAME = 80;
    private static final int SPHERE_DIVISIONS = 192;
    /** Extra render passes per pulse; GUI pulses are vsync-capped (~60 fps),
     *  so this multiplies the GPU work done per displayed frame. */
    private static final int PASSES_PER_PULSE = 3;

    // Shared across instances: the GUI rebuilds the StressTestView (and thus a
    // new GpuStress) on every open, but the FX toolkit, windows and timer must
    // survive so a second run restarts rendering instead of creating nothing.
    private static final List<GpuWindow> windows = new ArrayList<>();
    private static final List<Thread> awtWorkers = new ArrayList<>();
    private static volatile AnimationTimer timer;
    private static volatile WritableImage noise;
    private static volatile boolean running;
    private static volatile long sink;
    private static volatile boolean fxReady;
    private static volatile boolean fxStartedByUs;
    private static volatile String pipeline = "unknown";

    @Override
    public String name() {
        return "GPU";
    }

    @Override
    public String status() {
        if (GraphicsEnvironment.isHeadless()) {
            return "CPU fallback (headless)";
        }
        String p = pipeline.toLowerCase();
        boolean hardware = p.contains("d3d") || p.contains("es2");
        return hardware ? "GPU · " + pipeline + " pipeline" : "software · " + pipeline;
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
        // Default JavaFX pulses are throttled to the display refresh rate
        // (~60 fps), which caps how hard the GPU is pushed. Asking for
        // full-speed pulses makes Prism render as fast as the hardware allows.
        // Must be set before the toolkit starts; harmless in the GUI app where
        // the toolkit is already running.
        System.setProperty("javafx.animation.fullspeed", "true");
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
            // Second run: just bring the existing windows back and resume.
            showWindows();
            return;
        }
        noise = makeNoise();
        for (int i = 0; i < WINDOWS; i++) {
            GpuWindow window = new GpuWindow(90 + i * 700, 60 + i * 60);
            windows.add(window);
            // Windows MUST be shown for Prism to run render passes — a hidden
            // stage never renders a frame, so the GPU stays idle.
            window.stage.show();
        }
        pipeline = detectPipeline();
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                double t = now / 1_000_000_000.0;
                // Multiple render passes per pulse multiply the GPU work done per
                // displayed frame (GUI pulses are vsync-capped at ~60 fps).
                for (int pass = 0; pass < PASSES_PER_PULSE; pass++) {
                    double tt = t + pass * 0.004;
                    for (GpuWindow window : windows) {
                        window.render(tt);
                    }
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

    /** Pseudo-random texture used as the spheres' diffuse map and 2D overlay. */
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

    /** Reflectively reads the active Prism pipeline: D3DPipeline / ES2Pipeline (hardware) vs SWPipeline. */
    private static String detectPipeline() {
        try {
            Class<?> graphicsPipeline = Class.forName("com.sun.prism.GraphicsPipeline");
            Object pipelineInstance = graphicsPipeline.getMethod("getPipeline").invoke(null);
            return pipelineInstance.getClass().getSimpleName();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    /** One render window: textured 3D sphere cluster + blurred 2D overlay. */
    private final class GpuWindow {

        final Stage stage;
        final Canvas canvas;
        final Canvas canvas2;
        final Rotate mainRotateX = new Rotate(0, Rotate.X_AXIS);
        final Rotate mainRotateY = new Rotate(0, Rotate.Y_AXIS);
        final Group orbiters = new Group();
        final Rotate orbitRotate = new Rotate(0, Rotate.Z_AXIS);

        GpuWindow(double x, double y) {
            // Main textured sphere, very high polygon count.
            Sphere main = new Sphere(180, SPHERE_DIVISIONS);
            PhongMaterial mainMaterial = new PhongMaterial(Color.WHITE);
            mainMaterial.setDiffuseMap(noise);
            mainMaterial.setSpecularColor(Color.WHITE);
            main.setMaterial(mainMaterial);
            main.getTransforms().addAll(mainRotateX, mainRotateY);

            // Three smaller orbiting spheres, each textured too.
            Color[] orbitColors = {Color.LIGHTBLUE, Color.PALEVIOLETRED, Color.LIGHTGREEN};
            for (int i = 0; i < orbitColors.length; i++) {
                Sphere orbiter = new Sphere(70 + i * 15, SPHERE_DIVISIONS / 2);
                PhongMaterial material = new PhongMaterial(orbitColors[i]);
                material.setDiffuseMap(noise);
                material.setSpecularColor(Color.WHITE);
                orbiter.setMaterial(material);
                orbiter.setTranslateX((i - 1) * 320);
                orbiter.setTranslateY((i % 2 == 0 ? 1 : -1) * 180);
                orbiter.setTranslateZ(60);
                orbiters.getChildren().add(orbiter);
            }
            orbiters.getTransforms().add(orbitRotate);

            // Three coloured lights for rich per-pixel shading.
            PointLight light1 = new PointLight(Color.WHITE);
            light1.setTranslateX(420);
            light1.setTranslateY(300);
            light1.setTranslateZ(480);
            PointLight light2 = new PointLight(Color.PALEGOLDENROD);
            light2.setTranslateX(-420);
            light2.setTranslateY(-300);
            light2.setTranslateZ(-360);
            PointLight light3 = new PointLight(Color.DODGERBLUE);
            light3.setTranslateX(0);
            light3.setTranslateY(-420);
            light3.setTranslateZ(200);

            Group world = new Group(main, orbiters, light1, light2, light3);
            PerspectiveCamera camera = new PerspectiveCamera(true);
            camera.setTranslateZ(-720);
            SubScene subScene = new SubScene(world, VIEW_W, VIEW_H, true, SceneAntialiasing.BALANCED);
            subScene.setCamera(camera);

            // Two stacked canvas layers, each re-blurred every frame: two blur
            // passes plus twice the draw calls per pulse.
            canvas = new Canvas(VIEW_W, VIEW_H);
            canvas2 = new Canvas(VIEW_W, VIEW_H);
            Group overlay = new Group(canvas);
            overlay.setEffect(new BoxBlur(16, 16, 5));
            Group overlay2 = new Group(canvas2);
            overlay2.setEffect(new BoxBlur(8, 8, 3));

            StackPane root = new StackPane(subScene, overlay, overlay2);
            Scene scene = new Scene(root, VIEW_W, VIEW_H, true);
            scene.setFill(Color.TRANSPARENT);

            // The canvas and 3D view track the window size, so maximizing the
            // window turns it into a full-screen GPU burn (more pixels = more
            // fragment work), like FurMark. Open maximized by default.
            subScene.widthProperty().bind(root.widthProperty());
            subScene.heightProperty().bind(root.heightProperty());
            canvas.widthProperty().bind(root.widthProperty());
            canvas.heightProperty().bind(root.heightProperty());
            canvas2.widthProperty().bind(root.widthProperty());
            canvas2.heightProperty().bind(root.heightProperty());

            stage = new Stage();
            stage.setTitle("UltraMonitor GPU Stress");
            stage.setScene(scene);
            stage.setX(x);
            stage.setY(y);
            stage.setMinWidth(640);
            stage.setMinHeight(480);
        }

        void render(double t) {
            mainRotateX.setAngle((t * 55) % 360);
            mainRotateY.setAngle((t * 37) % 360);
            orbitRotate.setAngle((t * 120) % 360);

            GraphicsContext gc = canvas.getGraphicsContext2D();
            double w = canvas.getWidth();
            double h = canvas.getHeight();
            gc.clearRect(0, 0, w, h);

            // Full-canvas rotating gradient fill — pure fill-rate work.
            gc.setFill(new LinearGradient(0, 0, w, h, false, CycleMethod.REPEAT,
                    new Stop(0, Color.hsb((t * 40) % 360, 0.9, 0.5)),
                    new Stop(1, Color.hsb((t * 40 + 180) % 360, 0.9, 0.3))));
            gc.fillRect(0, 0, w, h);

            // Rotated cluster of translucent gradient shapes.
            gc.save();
            gc.translate(w / 2, h / 2);
            gc.rotate((t * 90) % 360);
            for (int i = 0; i < SHAPES_PER_FRAME; i++) {
                gc.setFill(Color.hsb((t * 45 + i * 7) % 360, 0.8, 0.95, 0.35));
                double r = 30 + (i % 8) * 14;
                gc.fillOval(-r - (i % 5) * 34, -r + (i % 4) * 28, r * 2, r * 2);
                gc.fillArc((i % 5) * 48, -(i % 3) * 36, 110, 110, (t * 60 + i * 20) % 360, 120, ArcType.ROUND);
            }
            gc.restore();

            // Two full-canvas alpha-blended blits — heavy compositing bandwidth.
            gc.setGlobalAlpha(0.25);
            gc.save();
            gc.translate(w / 2, h / 2);
            gc.rotate((t * 45) % 360);
            gc.drawImage(noise, -w / 2, -h / 2, w, h);
            gc.restore();
            gc.setGlobalAlpha(0.15);
            gc.save();
            gc.translate(w / 2, h / 2);
            gc.rotate(-(t * 30) % 360);
            gc.scale(0.7, 0.7);
            gc.drawImage(noise, -w / 2, -h / 2, w, h);
            gc.restore();
            gc.setGlobalAlpha(1.0);

            // Second canvas feeds a second, stronger blur layer — more GPU
            // fragment work per frame.
            GraphicsContext gc2 = canvas2.getGraphicsContext2D();
            gc2.clearRect(0, 0, w, h);
            gc2.setGlobalAlpha(0.3);
            for (int i = 0; i < SHAPES_PER_FRAME / 2; i++) {
                gc2.setFill(Color.hsb((t * 30 + i * 11) % 360, 0.9, 0.9, 0.4));
                double r = 60 + (i % 5) * 24;
                gc2.fillOval((i * 73) % w, (i * 47) % h, r * 2, r * 2);
            }
            gc2.setGlobalAlpha(1.0);
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
