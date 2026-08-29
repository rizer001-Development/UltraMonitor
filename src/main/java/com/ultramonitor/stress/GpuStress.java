package com.ultramonitor.stress;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

import java.awt.AlphaComposite;
import java.nio.FloatBuffer;
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

import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor;
import static org.lwjgl.glfw.GLFW.glfwGetVideoMode;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_FALSE;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glGetString;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL20.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL20.GL_FLOAT;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL20.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL20.glAttachShader;
import static org.lwjgl.opengl.GL20.glCompileShader;
import static org.lwjgl.opengl.GL20.glCreateProgram;
import static org.lwjgl.opengl.GL20.glCreateShader;
import static org.lwjgl.opengl.GL20.glDeleteProgram;
import static org.lwjgl.opengl.GL20.glDeleteShader;
import static org.lwjgl.opengl.GL20.glGetProgrami;
import static org.lwjgl.opengl.GL20.glGetShaderi;
import static org.lwjgl.opengl.GL20.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL20.glGetUniformLocation;
import static org.lwjgl.opengl.GL20.glLinkProgram;
import static org.lwjgl.opengl.GL20.glShaderSource;
import static org.lwjgl.opengl.GL20.glUniform1f;
import static org.lwjgl.opengl.GL20.glUniform2f;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

/**
 * Pushes the GPU through OpenGL (LWJGL): a fullscreen quad rendered with a
 * heavy fragment shader computing the Mandelbrot set at 512 iterations per
 * pixel. That is hundreds of millions of floating-point fragment operations
 * per frame — a genuine, measurable GPU workload (the same technique used by
 * GPU-burn tools). Vsync is disabled so the GPU renders as fast as it can.
 *
 * <p>In headless environments it falls back to the CPU-only AWT renderer. The
 * OpenGL renderer string (e.g. "NVIDIA GeForce RTX 3060 / PCIe / SSE2") is
 * reported by {@link #status()}, proving a real GPU is being hammered.</p>
 */
public final class GpuStress implements StressTest {

    private static final int FRACTAL_ITERATIONS = 512;
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;

    private static final String VERTEX_SHADER = """
            #version 330 core
            layout(location = 0) in vec2 position;
            void main() {
                gl_Position = vec4(position, 0.0, 1.0);
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 330 core
            uniform vec2 uCenter;
            uniform float uScale;
            uniform vec2 uResolution;
            out vec4 fragColor;

            void main() {
                // Map fragment position to the Mandelbrot plane.
                vec2 uv = gl_FragCoord.xy;
                vec2 c = (uv - 0.5 * uResolution) / (0.5 * uResolution.y * uScale) + uCenter;

                // Iterate z = z^2 + c — the actual GPU workload.
                vec2 z = vec2(0.0);
                int iter = 0;
                for (int i = 0; i < %d; i++) {
                    z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;
                    if (dot(z, z) > 4.0) {
                        break;
                    }
                    iter = i;
                }

                // Smooth colouring so the picture stays interesting.
                float m = float(iter) / %d.0;
                float r = 0.5 + 0.5 * cos(3.0 + m * 6.28);
                float g = 0.5 + 0.5 * cos(3.0 + m * 6.28 + 2.1);
                float b = 0.5 + 0.5 * cos(3.0 + m * 6.28 + 4.2);
                fragColor = vec4(r, g, b, 1.0);
            }
            """.formatted(FRACTAL_ITERATIONS, FRACTAL_ITERATIONS);

    // Shared across instances so a second run reuses nothing stale; the GLFW
    // window and render loop are owned by a dedicated worker thread.
    private static final List<Thread> workers = new ArrayList<>();
    private static volatile boolean running;
    private static volatile long sink;
    private static volatile String renderer = "";

    @Override
    public String name() {
        return "GPU";
    }

    @Override
    public String status() {
        if (renderer.isBlank()) {
            return "OpenGL " + (GraphicsEnvironment.isHeadless() ? "fallback (headless)" : "unavailable");
        }
        return "OpenGL · " + renderer;
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
            startGl();
        }
    }

    @Override
    public void stop() {
        running = false;
        for (Thread worker : workers) {
            try {
                worker.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        workers.clear();
    }

    // -------------------------------------------------------- OpenGL path --

    private void startGl() {
        Thread thread = new Thread(this::glLoop, "ultramonitor-gpu-gl");
        thread.setDaemon(true);
        thread.start();
        workers.add(thread);
    }

    private void glLoop() {
        try {
            if (!glfwInit()) {
                renderer = "";
                startAwtFallback();
                return;
            }

            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
            glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);

            long window = glfwCreateWindow(WIDTH, HEIGHT, "UltraMonitor GPU Stress", 0, 0);
            if (window == 0) {
                glfwTerminate();
                startAwtFallback();
                return;
            }
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            glfwSwapInterval(0); // uncapped frame rate — let the GPU run free

            renderer = glGetString(GL20.GL_RENDERER);

            // Fullscreen quad: two triangles over the whole clip space.
            // Its 6 vertices are uploaded to a VBO so the rasterizer actually
            // has geometry to draw (without vertex data the window stays black).
            int vao = glGenVertexArrays();
            glBindVertexArray(vao);
            int vbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                glBufferData(GL_ARRAY_BUFFER, vertices(stack), GL_STATIC_DRAW);
            }
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0L);

            int program = createProgram();
            glUseProgram(program);
            int uCenter = glGetUniformLocation(program, "uCenter");
            int uScale = glGetUniformLocation(program, "uScale");
            int uResolution = glGetUniformLocation(program, "uResolution");

            long frames = 0;
            double startNanos = System.nanoTime();
            double angle = 0;
            double zoom = 1.0;
            while (running && !glfwWindowShouldClose(window)) {
                double t = (System.nanoTime() - startNanos) / 1_000_000_000.0;

                // Slow zooming and panning so the fractal keeps changing.
                zoom = 1.0 + t * 0.02;
                angle = t * 0.3;
                double cx = -0.5 + Math.sin(angle) * 0.3;
                double cy = 0.0 + Math.cos(angle) * 0.2;

                glUniform2f(uCenter, (float) cx, (float) cy);
                glUniform1f(uScale, (float) zoom);
                glUniform2f(uResolution, WIDTH, HEIGHT);

                glClearColor(0, 0, 0, 1);
                glClear(GL_COLOR_BUFFER_BIT);
                glDrawArrays(GL_TRIANGLES, 0, 6);
                glfwSwapBuffers(window);
                glfwPollEvents();
                frames++;
                if ((frames & 511) == 0) {
                    sink = frames;
                }
            }

            sink = frames;
            glDeleteProgram(program);
            glfwDestroyWindow(window);
            glfwTerminate();
        } catch (Throwable t) {
            renderer = "";
            startAwtFallback();
        }
    }

    /** Six 2D vertices forming the fullscreen quad (two triangles, CCW). */
    private static FloatBuffer vertices(MemoryStack stack) {
        FloatBuffer data = stack.callocFloat(6 * 2);
        data.put(-1f).put(-1f) // bottom-left
                .put(1f).put(-1f)  // bottom-right
                .put(-1f).put(1f)  // top-left
                .put(-1f).put(1f)  // top-left
                .put(1f).put(-1f)  // bottom-right
                .put(1f).put(1f);  // top-right
        data.flip();
        return data;
    }

    private static int createProgram() {
        int vertex = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vertex, VERTEX_SHADER);
        glCompileShader(vertex);
        checkShader(vertex);

        int fragment = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fragment, FRAGMENT_SHADER);
        glCompileShader(fragment);
        checkShader(fragment);

        int program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Shader link failed");
        }
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        return program;
    }

    private static void checkShader(int shader) {
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Shader compile failed: " + glGetShaderInfoLog(shader));
        }
    }

    // ----------------------------------------------------- AWT fallback ----

    private void startAwtFallback() {
        for (int i = 0; i < 2; i++) {
            Thread thread = new Thread(this::awtRenderLoop, "ultramonitor-gpu-" + i);
            thread.setDaemon(true);
            thread.start();
            workers.add(thread);
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
