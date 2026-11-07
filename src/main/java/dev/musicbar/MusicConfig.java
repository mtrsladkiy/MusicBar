package dev.musicbar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

 
public final class MusicConfig {
    public enum PulseMode {
        AUTO("Авто"), AUDIO("Звук ПК"), SIMULATED("Имитация"), OFF("Выкл");

        public final String label;

        PulseMode(String label) {
            this.label = label;
        }

        public PulseMode next() {
            return switch (this) { case AUTO -> AUDIO; case AUDIO -> OFF; default -> AUTO; };
        }
    }

     
    public boolean enabled = true;
    public boolean hideWhenIdle = false;
    public boolean showArtist = true;
    public boolean showProgress = true;
    public boolean showVisualizer = true;
    public boolean showLyrics = true;
    public String musicProcess = "";  
    public boolean coverOnline = true;  
    public boolean lyricsOnline = false;  

     
    public int width = 270;
    public int offsetX = 0;
    public int offsetY = 6;

     
    public int accent = 0xA973FF;        
    public int accent2 = 0x7863FF;       
    public int glow = 0xA973FF;          
    public int background = 0x171521;
    public int text = 0xFFFFFF;
    public int subtext = 0xB9B2C9;
    public boolean rainbow = false;      

     
    public int backgroundAlpha = 225;    
    public int glowSize = 4;             
    public int glowIntensity = 45;       
    public PulseMode pulseMode = PulseMode.AUTO;
    public int pulseStrength = 45;       
    public int sensitivity = 60;         

     

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static MusicConfig instance = new MusicConfig();

    public static MusicConfig get() {
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("musicbar.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                MusicConfig c = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), MusicConfig.class);
                if (c != null) instance = c;
            }
        } catch (Exception e) {
            MusicBarClient.LOG.warn("Не удалось прочитать musicbar.json, использую значения по умолчанию", e);
        }
        if (instance.pulseMode == null || instance.pulseMode == PulseMode.SIMULATED) instance.pulseMode = PulseMode.AUTO;
        instance.clamp();
        instance.save();
    }

    public void save() {
        clamp();
        try {
            Files.writeString(path(), GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (Exception e) {
            MusicBarClient.LOG.warn("Не удалось сохранить musicbar.json", e);
        }
    }

     
    public void resetToDefaults() {
        MusicConfig d = new MusicConfig();
        try {
            for (Field f : MusicConfig.class.getDeclaredFields()) {
                int m = f.getModifiers();
                if (Modifier.isStatic(m) || Modifier.isFinal(m)) continue;
                f.set(this, f.get(d));
            }
        } catch (IllegalAccessException ignored) {
        }
    }

    private void clamp() {
        width = Math.max(140, Math.min(360, width));
        offsetX = Math.max(-400, Math.min(400, offsetX));
        offsetY = Math.max(0, Math.min(200, offsetY));
        backgroundAlpha = Math.max(0, Math.min(255, backgroundAlpha));
        glowSize = Math.max(0, Math.min(16, glowSize));
        glowIntensity = Math.max(0, Math.min(100, glowIntensity));
        pulseStrength = Math.max(0, Math.min(100, pulseStrength));
        sensitivity = Math.max(0, Math.min(100, sensitivity));
        accent &= 0xFFFFFF;
        accent2 &= 0xFFFFFF;
        glow &= 0xFFFFFF;
        background &= 0xFFFFFF;
        text &= 0xFFFFFF;
        subtext &= 0xFFFFFF;
    }
}
