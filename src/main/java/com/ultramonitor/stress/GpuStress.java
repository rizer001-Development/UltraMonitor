package com.ultramonitor.stress;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Pushes the graphics pipeline with a tight rendering loop: gradient fills,
 * rotated shapes and alpha compositing on a scratch image. On Windows these
 * operations run through the hardware-accelerated Java2D pipeline (DirectX),
 * putting real load on the GPU. Degrades gracefully to CPU rendering in
 * headless environments.
 */
public final class GpuStress implements StressTest {

    private static final int WORKERS = 2;

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
        int size = 512;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
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
                graphics.setPaint(new GradientPaint(0, 0, Color.getHSBColor(hue, 1f, 1f),
                        size, size, Color.BLACK));
                graphics.fillRect(0, 0, size, size);

                graphics.rotate(angle, size / 2.0, size / 2.0);
                graphics.setComposite(AlphaComposite.SrcOver);
                graphics.setPaint(new GradientPaint(0, 0, Color.WHITE, size, size, Color.BLUE));
                graphics.fillOval(size / 4, size / 4, size / 2, size / 2);
                graphics.rotate(-angle, size / 2.0, size / 2.0);

                graphics.drawImage(image, 0, 0, size / 2, size / 2, null);
                frames++;
            }
        } finally {
            graphics.dispose();
        }
        sink = frames;
    }
}
