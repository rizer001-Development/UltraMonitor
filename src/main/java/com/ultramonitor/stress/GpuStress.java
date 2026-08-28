package com.ultramonitor.stress;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.util.ArrayList;
import java.util.List;

/**
 * Pushes the graphics pipeline with a heavy rendering loop per thread:
 * large gradient fills, rotated compound shapes, alpha-blended overlay
 * compositing and a periodic 3×3 convolution blur. On Windows these operations
 * run through the hardware-accelerated Java2D pipeline (DirectX), putting real
 * load on the GPU; they degrade gracefully to CPU rendering in headless
 * environments.
 */
public final class GpuStress implements StressTest {

    private static final int WORKERS = 4;
    private static final int SIZE = 1024;
    private static final int SHAPES_PER_FRAME = 8;

    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running;
    private volatile long sink;

    @Override
    public String name() {
        return "GPU";
    }

    @Override
    public String status() {
        return WORKERS + " rendering threads";
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void start() {
        running = true;
        for (int i = 0; i < WORKERS; i++) {
            Thread thread = new Thread(this::renderLoop, "ultramonitor-gpu-" + i);
            thread.setDaemon(true);
            thread.start();
            workers.add(thread);
        }
    }

    @Override
    public void stop() {
        running = false;
        for (Thread worker : workers) {
            try {
                worker.join(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        workers.clear();
    }

    private void renderLoop() {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        BufferedImage overlay = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // 3x3 box blur — very expensive convolution, run every 32nd frame.
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

                // Background gradient.
                graphics.setComposite(AlphaComposite.Src);
                graphics.setPaint(new GradientPaint(0, 0, Color.getHSBColor(hue, 1f, 1f),
                        SIZE, SIZE, Color.BLACK));
                graphics.fillRect(0, 0, SIZE, SIZE);

                // Rotated compound shapes — many gradient fills per frame.
                AffineTransform saved = graphics.getTransform();
                graphics.rotate(angle, SIZE / 2.0, SIZE / 2.0);
                for (int i = 0; i < SHAPES_PER_FRAME; i++) {
                    float h = (hue + i * 0.05f) % 1f;
                    graphics.setPaint(new GradientPaint(0, 0,
                            Color.getHSBColor(h, 1f, 0.9f), SIZE, SIZE, Color.BLACK));
                    graphics.fillOval((i * 97) % SIZE, (i * 53) % SIZE, SIZE / 3, SIZE / 3);
                    graphics.fillRect((i * 71) % SIZE, (i * 29) % SIZE, SIZE / 4, SIZE / 4);
                    int[] xs = {(i * 43) % SIZE, (i * 61) % SIZE, (i * 89) % SIZE};
                    int[] ys = {(i * 37) % SIZE, (i * 83) % SIZE, (i * 13) % SIZE};
                    graphics.fillPolygon(xs, ys, xs.length);
                }
                graphics.setTransform(saved);

                // Alpha-blended overlay: heavy compositing bandwidth.
                Graphics2D og = overlay.createGraphics();
                og.setComposite(AlphaComposite.Src);
                og.setColor(Color.BLACK);
                og.fillRect(0, 0, SIZE, SIZE);
                og.setComposite(AlphaComposite.SrcOver.derive(0.45f));
                og.setPaint(new GradientPaint(0, 0, Color.WHITE, SIZE, SIZE, Color.CYAN));
                og.fillOval(SIZE / 4, SIZE / 4, SIZE / 2, SIZE / 2);
                og.setPaint(new GradientPaint(0, 0, Color.MAGENTA, SIZE, SIZE, Color.BLUE));
                og.fillOval((int) (Math.sin(angle * 2) * 120) + SIZE / 2,
                        (int) (Math.cos(angle * 3) * 120) + SIZE / 2, SIZE / 3, SIZE / 3);
                og.dispose();
                graphics.drawImage(overlay, 0, 0, null);

                // Periodic heavy convolution.
                if ((frames & 31) == 0) {
                    blur.filter(image, image);
                }
                frames++;
            }
        } finally {
            graphics.dispose();
        }
        sink = frames;
    }
}
