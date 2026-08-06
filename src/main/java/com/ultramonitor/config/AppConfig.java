package com.ultramonitor.config;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Portable application configuration persisted as {@code config.json} next to
 * the jar (falling back to {@code ~/.ultramonitor} when running from a build
 * directory). Currently stores the sensor refresh interval in milliseconds.
 */
public final class AppConfig {

    public static final int DEFAULT_INTERVAL_MS = 1000;
    public static final int MIN_INTERVAL_MS = 1;
    public static final int MAX_INTERVAL_MS = 10_000;

    private static final String FILE_NAME = "config.json";
    private static final String KEY = "refreshIntervalMs";

    private AppConfig() {
    }

    public static Path configFile() {
        return appDir().resolve(FILE_NAME);
    }

    /**
     * Directory next to the executable jar when running packaged, otherwise a
     * per-user directory so developer runs never try to write into build dirs.
     */
    public static Path appDir() {
        try {
            Path code = Paths.get(AppConfig.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (code.toString().toLowerCase().endsWith(".jar")) {
                return code.toAbsolutePath().getParent();
            }
        } catch (URISyntaxException | SecurityException ignored) {
            // fall through to user dir
        }
        return Paths.get(System.getProperty("user.home", "."), ".ultramonitor");
    }

    public static int loadIntervalMs() {
        try {
            Path file = configFile();
            if (Files.isReadable(file)) {
                String content = Files.readString(file);
                String digits = content.replaceAll("\\D", "");
                if (!digits.isEmpty()) {
                    int value = Integer.parseInt(digits);
                    if (value >= MIN_INTERVAL_MS && value <= MAX_INTERVAL_MS) {
                        return value;
                    }
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            // fall back to default
        }
        return DEFAULT_INTERVAL_MS;
    }

    /** Persists the interval; returns {@code true} on success. */
    public static boolean saveIntervalMs(int intervalMs) {
        int clamped = Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, intervalMs));
        try {
            Path dir = appDir();
            Files.createDirectories(dir);
            String json = "{\"" + KEY + "\": " + clamped + "}\n";
            Files.writeString(configFile(), json);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }
}
