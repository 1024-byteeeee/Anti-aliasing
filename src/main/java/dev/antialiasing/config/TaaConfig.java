package dev.antialiasing.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import dev.antialiasing.TaaClient;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.io.Reader;

public final class TaaConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("taa.json");
    public boolean enabled = true;
    public int antialiasingMode = 1; // 0: TAA, 1: SSAA
    public int ssaaScale = 2; // Per-axis scale: 2x2, 3x3, 4x4 samples.
    @SerializedName(value = "historyWeight", alternate = {"referenceWeight"})
    public double historyWeight = 0.97;
    public boolean reduceHistoryOnMotion = true;
    @SerializedName(value = "sharpening", alternate = {"referenceSharpening"})
    public double sharpening = 0.2;
    public int sampleCount = 8;
    public int sharpeningMode = 0;
    public double casAmount = 1.0;
    public int debugView = 0;

    public static TaaConfig load() {
        if (Files.exists(FILE)) {
            try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                TaaConfig config = GSON.fromJson(reader, TaaConfig.class);
                if (config != null) { config.sanitize(); return config; }
            } catch (IOException | RuntimeException e) {
                TaaClient.LOGGER.warn("Cannot read Anti-aliasing config; using defaults", e);
            }
        }
        return new TaaConfig();
    }

    public void sanitize() {
        if (antialiasingMode < 0 || antialiasingMode > 1) antialiasingMode = 1;
        ssaaScale = Math.max(2, Math.min(4, ssaaScale));
        historyWeight = finiteClamp(historyWeight, 0.97, 0, 1);
        sharpening = finiteClamp(sharpening, 0.2, 0, 1);
        sampleCount = Math.max(1, Math.min(256, sampleCount));
        sharpeningMode = Math.max(0, Math.min(2, sharpeningMode));
        casAmount = finiteClamp(casAmount, 1, 0.1, 1);
        debugView = Math.max(0, Math.min(3, debugView));
    }

    private static double finiteClamp(double value, double fallback, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    public boolean save() {
        sanitize();
        Path temp = FILE.resolveSibling("taa.json.tmp");
        try {
            Files.createDirectories(FILE.getParent());
            Files.write(temp, GSON.toJson(this).getBytes(StandardCharsets.UTF_8));
            try { Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            TaaClient.LOGGER.error("Cannot save Anti-aliasing settings", e);
            return false;
        }
    }

    public void defaults() {
        enabled = true;
        antialiasingMode = 1;
        ssaaScale = 2;
        resetTemporalValues();
        debugView = 0;
    }

    public void resetTemporalValues() {
        historyWeight = 0.97;
        reduceHistoryOnMotion = true;
        sharpening = 0.2;
        sampleCount = 8;
        sharpeningMode = 0;
        casAmount = 1;
    }
}
