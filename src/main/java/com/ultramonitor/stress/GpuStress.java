package com.ultramonitor.stress;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
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
import javafx.scene.shape.Sphere;
import javafx.scene.transform.Rotate;
import javafx.stage.Screen;
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
import java.util.Random;

/**
 * Pushes the GPU through the JavaFX rendering pipeline (Prism), which is
 * hardware-accelerated on Windows via Direct3D. One maximized window fills the
 * primary screen; the scene is a dense 3D swarm of hundreds of individually
 * lit, texture-mapped spheres orbiting a large central sphere, overlaid with a
 * canvas carrying thousands of fine translucent particles re-blurred every
 * frame. Prism renders the 3D geometry and the blur effects on the GPU, so the
 * workload saturates the vertex and fragment pipelines at high resolution.
 *
 * <p>In headless environments (CI, servers) it falls back to the CPU-only AWT
 * renderer so the test still runs and can be stopped cleanly. The active Prism
 * pipeline is detected and reported by {@link #status()}, so a software
 * fallback is visible instead of silently doing nothing.</p>
 */
public final class GpuStress implements StressTest {

    private static final int SWARM_SIZE = 180;
    private static final int PARTICLES = 4000;
    private static final int SPHERE_DIVISIONS = 96;
    /** Extra render passes per pulse; GUI pulses are vsync-capped, so this
     *  multiplies the GPU work done per displayed frame. */
    private static final int PASSES_PER_PULSE = 4;

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
        // (~60 fps). Asking for full-speed pulses makes Prism render as fast as
        // the hardware allows. Must be set before the toolkit starts; harmless
        // in the GUI app where the toolkit is already running.
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
        GpuWindow window = new GpuWindow();
        windows.add(window);
        // Windows MUST be shown for Prism to run render passes — a hidden
        // stage never renders a frame, so the GPU stays idle.
        window.stage.show();
        pipeline = detectPipeline();
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                double t = now / 1_000_000_000.0;
                // Multiple render passes per pulse multiply the GPU work done
                // per displayed frame (GUI pulses are vsync-capped).
                for (int pass = 0; pass < PASSES_PER_PULSE; pass++) {
                    double tt = t + pass * 0.004;
                    for (GpuWindow w : windows) {
                        w.render(tt);
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

    /** Pseudo-random texture used as the spheres' diffuse map. */
    private WritableImage makeNoise() {
        WritableImage image = new WritableImage(1024, 1024);
        PixelWriter writer = image.getPixelWriter();
        int seed = 12345;
        for (int y = 0; y < 1024; y++) {
            for (int x = 0; x < 1024; x++) {
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

    /** The single render window: full-screen 3D swarm + particle blur overlay. */
    private final class GpuWindow {

        final Stage stage;
        final Canvas canvas;
        final Rotate swarmRotate = new Rotate(0, Rotate.Y_AXIS);
        final Rotate swarmTilt = new Rotate(0, Rotate.X_AXIS);
        final Rotate mainRotateX = new Rotate(0, Rotate.X_AXIS);
        final Rotate mainRotateY = new Rotate(0, Rotate.Y_AXIS);
        final double w;
        final double h;
        final double scale;

        GpuWindow() {
            Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
            w = bounds.getWidth();
            h = bounds.getHeight();
            scale = Math.min(w, h) / 900.0; // scene tuned for a ~900px viewport

            Random random = new Random(42);
            Group world = new Group();

            // Dense swarm of small textured spheres at random positions.
            Group swarm = new Group();
            for (int i = 0; i < SWARM_SIZE; i++) {
                double radius = (14 + random.nextDouble() * 55) * scale;
                Sphere sphere = new Sphere(radius, SPHERE_DIVISIONS / 2);
                PhongMaterial material = new PhongMaterial(
                        Color.hsb(random.nextDouble() * 360, 0.75, 1.0));
                material.setDiffuseMap(noise);
                material.setSpecularColor(Color.WHITE);
                sphere.setMaterial(material);
                double spread = 1500 * scale;
                sphere.setTranslateX((random.nextDouble() - 0.5) * 2 * spread);
                sphere.setTranslateY((random.nextDouble() - 0.5) * 2 * spread);
                sphere.setTranslateZ((random.nextDouble() - 0.5) * 2 * spread);
                swarm.getChildren().add(sphere);
            }
            swarm.getTransforms().addAll(swarmRotate, swarmTilt);

            // Large central sphere, heavily tessellated.
            Sphere main = new Sphere(240 * scale, SPHERE_DIVISIONS);
            PhongMaterial mainMaterial = new PhongMaterial(Color.WHITE);
            mainMaterial.setDiffuseMap(noise);
            mainMaterial.setSpecularColor(Color.WHITE);
            main.setMaterial(mainMaterial);
            main.getTransforms().addAll(mainRotateX, mainRotateY);

            // Four coloured lights sweeping the scene for per-pixel shading.
            PointLight[] lights = new PointLight[4];
            Color[] lightColors = {Color.WHITE, Color.PALEGOLDENROD, Color.DODGERBLUE, Color.HOTPINK};
            for (int i = 0; i < lights.length; i++) {
                lights[i] = new PointLight(lightColors[i]);
            }
            lights[0].setTranslateX(1500 * scale);
            lights[0].setTranslateY(800 * scale);
            lights[0].setTranslateZ(1600 * scale);
            lights[1].setTranslateX(-1500 * scale);
            lights[1].setTranslateY(-800 * scale);
            lights[1].setTranslateZ(-1400 * scale);
            lights[2].setTranslateX(0);
            lights[2].setTranslateY(-1400 * scale);
            lights[2].setTranslateZ(600 * scale);
            lights[3].setTranslateX(0);
            lights[3].setTranslateY(1400 * scale);
            lights[3].setTranslateZ(-600 * scale);

            world.getChildren().addAll(main, swarm);
            world.getChildren().addAll(lights);

            PerspectiveCamera camera = new PerspectiveCamera(true);
            camera.setTranslateZ(-(2600 * scale));
            camera.setFieldOfView(55);
            SubScene subScene = new SubScene(world, w, h, true, SceneAntialiasing.BALANCED);
            subScene.setCamera(camera);

            // Particle overlay: thousands of fine dots, re-blurred every frame.
            canvas = new Canvas(w, h);
            Group overlay = new Group(canvas);
            overlay.setEffect(new BoxBlur(10, 10, 3));

            StackPane root = new StackPane(subScene, overlay);
            Scene scene = new Scene(root, w, h, true);
            scene.setFill(Color.TRANSPARENT);

            stage = new Stage();
            stage.setTitle("UltraMonitor GPU Stress");
            stage.setScene(scene);
            stage.setMinWidth(640);
            stage.setMinHeight(480);
            // Fill the whole screen — maximum resolution, maximum fragment work.
            stage.setMaximized(true);
        }

        void render(double t) {
            swarmRotate.setAngle((t * 30) % 360);
            swarmTilt.setAngle(Math.sin(t * 0.5) * 20);
            mainRotateX.setAngle((t * 55) % 360);
            mainRotateY.setAngle((t * 37) % 360);

            GraphicsContext gc = canvas.getGraphicsContext2D();
            gc.clearRect(0, 0, w, h);
            // Thousands of fine translucent particles — dense fragment work.
            double spin = (t * 60) % 360;
            for (int i = 0; i < PARTICLES; i++) {
                double px = ((i * 2654435761L) & 0xFFFFF) % (long) w;
                double py = ((i * 40503L) & 0xFFFFF) % (long) h;
                double r = 1.2 + (i % 5) * 0.7;
                gc.setFill(Color.hsb((t * 40 + i * 0.7) % 360, 0.9, 1.0, 0.35));
                gc.fillOval(px, py + Math.sin(t + i) * 8, r, r);
            }
            // A few large translucent rings sweeping across — extra fill-rate.
            gc.setGlobalAlpha(0.12);
            gc.setFill(Color.hsb(spin, 0.9, 1.0));
            gc.fillOval(w / 2 - 600, h / 2 - 600 + Math.sin(t) * 200, 1200, 1200);
            gc.setGlobalAlpha(0.06);
            gc.setFill(Color.hsb((spin + 180) % 360, 0.9, 1.0));
            gc.fillOval(w / 2 - 900, h / 2 - 900 + Math.cos(t) * 200, 1800, 1800);
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
