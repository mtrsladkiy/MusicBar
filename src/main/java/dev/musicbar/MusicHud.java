package dev.musicbar;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

 
public final class MusicHud {

    private static float pulse;           
    private static float smoothLevel;     
    private static long lastSeq;
    private static long lastNanos = System.nanoTime();
    private static LrcParser.Current shownLine, previousLine;
    private static String lyricTrack = "";
    private static long lyricChanged;
    private static float smoothHighlightX, smoothHighlightWidth, smoothScroll;
    private static boolean highlightReady;
    private static final float[] waveBands = new float[SpectrumAnalyzer.BANDS];

    private MusicHud() {}

     

    public static void render(GuiGraphics g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        MusicConfig c = MusicConfig.get();
        if (!c.enabled || MusicBarClient.monitor() == null) return;
        if (mc.screen instanceof EditorScreen) return;            
        MediaMonitor.Info info = MusicBarClient.monitor().info();
        if (c.hideWhenIdle && info.isEmpty()) return;
        draw(g, mc.font, mc.getWindow().getGuiScaledWidth(), info, MusicBarClient.monitor().error());
    }

     
    public static MediaMonitor.Info demo() {
        long ms = (System.nanoTime() / 1_000_000L) % 243_000L;
        return new MediaMonitor.Info("Midnight City", "M83", "Hurry Up, We're Dreaming", "demo",
                true, ms, 243_000L, System.nanoTime());
    }

     

    public static void draw(GuiGraphics g, Font font, int screenW, MediaMonitor.Info info, String error) {
        MusicConfig c = MusicConfig.get();
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        float t = now / 1e9f;
        animate(info, dt, t, c);
        float p = pulse * (c.pulseStrength / 100f);
        int accent = c.rainbow ? hsv(t * .10f, .65f, 1f) : c.accent;
        int accent2 = c.rainbow ? hsv(t * .10f + .18f, .65f, 1f) : c.accent2;
        int glow = c.rainbow ? accent2 : c.glow;
        boolean idle = info.isEmpty();
        String title = idle ? "Ничего не играет" : info.title();
        String artist = idle ? "Запустите музыку" : info.artist();
        String head = title + (c.showArtist && !artist.isBlank() ? "  •  " + artist : "");
        int w = Math.max(100, Math.min(c.width, screenW - 8));
        int h = 22 + (c.showLyrics ? 12 : 0) + (c.showProgress ? 10 : 0);
        int x = (screenW - w) / 2 + c.offsetX;
        int y = Minecraft.getInstance().screen instanceof EditorScreen ? Math.min(8, c.offsetY) : c.offsetY + (c.showVisualizer ? 25 : 0);
        int right = x + w;
        if (c.showVisualizer) drawSpectrum(g, x, y, w, h, accent, accent2, dt);
         
        int spread = Math.min(8, c.glowSize);
        for (int i = spread; i >= 1; i--) {
            float f = (spread-i+1f) / Math.max(1, spread);
            rounded(g, x-i, y-i, right+i, y+h+i, 10+i, argb(Math.round(c.glowIntensity * .23f * f), glow));
        }
        rounded(g, x, y, right, y+h, 10, argb(205, lerp(accent2, 0x777486, .70f)));
        rounded(g, x+1, y+1, right-1, y+h-1, 9, argb(c.backgroundAlpha, c.background));
         
        int ix=x+16, iy=y+h/2;
        rounded(g, ix-11, iy-11, ix+11, iy+11, 11, argb(255, accent2));
        if (!CoverArt.draw(g, info, ix-10, iy-10, 20)) {
            rounded(g, ix-9, iy-9, ix+9, iy+9, 9, argb(255, lerp(accent, 0xFFFFFF, .45f)));
            g.drawString(font, "♪", ix-3, iy-4, 0xFFFFFFFF, false);
        }
        int cx=x+32, maxW=Math.max(12,w-40);
        int cy=y+6;
        marquee(g, font, head, cx, cy, maxW, 0xFF000000 | c.text, now);
        cy+=11;
        if (c.showLyrics) {
            LrcParser.Current line = idle ? null : LyricsService.current(info);
            if (line == null) line = new LrcParser.Current(idle ? "Включи трек" : LyricsService.message(),0,0,false);
            drawAnimatedLyric(g, font, line, info, cx, cy, maxW, accent, c.subtext, now, dt);
            cy+=12;
        }
        if (c.showProgress) drawProgress(g, font, info, idle, cx, right-8, cy, accent, accent2, c, p, info.playing());
    }

    private static void drawSpectrum(GuiGraphics g, int x, int y, int w, int h, int accent, int accent2, float dt) {
        MediaMonitor monitor = MusicBarClient.monitor();
        float[] actual = monitor == null ? new float[SpectrumAnalyzer.BANDS] : monitor.spectrum();
        for (int i=0;i<waveBands.length;i++) {
            float target = actual[i];
            float speed = target > waveBands[i] ? 20f : 8f;
            waveBands[i] += (target-waveBands[i])*Math.min(1,dt*speed);
        }
         
        int columns = Math.max(10, (w-16)/2);
        for (int i=0;i<columns;i++) {
            float frac = (i+.5f)/columns;
            float pos = Math.abs(frac*2-1) * (waveBands.length-1);
            int band = Math.min(waveBands.length-2, (int)pos);
            float value = waveBands[band] * (1-(pos-band)) + waveBands[band+1]*(pos-band);
            if (value < .025f) continue;
            int height = Math.round(value*25), xx=x+8+i*2;
            int color = lerp(lerp(accent2,0x26DFFF,.55f), accent, frac);
            g.fill(xx, y-height-3, xx+2, y+1, argb(38,color));
            g.fill(xx, y-height, xx+2, y+1, argb(155,color));
            g.fill(xx, y+h-1, xx+2, y+h+height+3, argb(38,color));
            g.fill(xx, y+h-1, xx+2, y+h+height, argb(155,color));
        }
        for (int i=0;i<6;i++) {
            float value=waveBands[18+i]; if(value<.025f) continue;
            int length=Math.round(value*20), yy=y+6+Math.round((h-12)*(i+.5f)/6f);
            int color=lerp(accent2,accent,i/5f);
            rounded(g,x-length,yy-2,x+2,yy+3,2,argb(165,color));
            rounded(g,x+w-2,yy-2,x+w+length,yy+3,2,argb(165,color));
        }
    }

    private static void drawAnimatedLyric(GuiGraphics g, Font font, LrcParser.Current line, MediaMonitor.Info info,
                                         int x, int y, int width, int accent, int subtext, long now, float dt) {
        String track = info.artist() + "\u0000" + info.title();
        if (!track.equals(lyricTrack)) { shownLine=null; previousLine=null; highlightReady=false; lyricTrack=track; }
        if (shownLine == null || !shownLine.text().equals(line.text())) {
            previousLine=shownLine; shownLine=line; lyricChanged=now; highlightReady=false;
        } else shownLine=line;
        float alpha = Math.min(1f, (now-lyricChanged)/260_000_000f);
        if (previousLine != null && alpha < 1) paintLyric(g,font,previousLine,x,y,width,accent,subtext,1-alpha, false,dt);
        paintLyric(g,font,shownLine,x,y,width,accent,subtext,alpha,true,dt);
        if (alpha >= 1) previousLine=null;
    }

    private static void paintLyric(GuiGraphics g, Font font, LrcParser.Current line, int x, int y, int width, int accent,
                                   int subtext, float opacity, boolean smooth, float dt) {
        if (opacity <= .01f) return;
        String text = line.text(); int alpha = Math.round(opacity*255);
        if (text.isBlank()) { g.drawString(font, "♪", x, y, argb(alpha,subtext), true); return; }
        int before = font.width(text.substring(0, line.start()));
        int wordWidth = font.width(text.substring(line.start(), line.end()));
        int total = font.width(text);
        int desiredScroll = Math.max(0, Math.min(Math.max(0, total-width), before+wordWidth-width/2));
        float scroll=desiredScroll, wordX=before, wordW=wordWidth;
        if (smooth) {
            if (!highlightReady) { smoothScroll=scroll; smoothHighlightX=wordX; smoothHighlightWidth=wordW; highlightReady=true; }
            float a = 1f-(float)Math.exp(-14f*dt);
            smoothScroll+=(scroll-smoothScroll)*a; smoothHighlightX+=(wordX-smoothHighlightX)*a; smoothHighlightWidth+=(wordW-smoothHighlightWidth)*a;
            scroll=smoothScroll; wordX=smoothHighlightX; wordW=smoothHighlightWidth;
        }
        int sx = Math.round(x-scroll), highlightX = Math.round(sx+wordX), hw=Math.round(wordW);
        g.enableScissor(x, y-2, x+width, y+10);
        if (hw > 0) {
            rounded(g, highlightX-2, y-2, highlightX+hw+2, y+10, 3, argb(Math.round(105*opacity), accent));
            rounded(g, highlightX-1, y-1, highlightX+hw+1, y+9, 2, argb(Math.round(130*opacity), accent));
        }
        g.drawString(font, text, sx, y, argb(alpha,subtext), true);
        if (hw > 0 && highlightX < x+width && highlightX+hw > x) {
            g.enableScissor(Math.max(x, highlightX), y-1, Math.min(x+width, highlightX+hw), y+10);
            g.drawString(font, text, sx, y, argb(alpha,0xFFFFFF), true);
            g.disableScissor();
        }
        g.disableScissor();
    }

    private static void rounded(GuiGraphics g, int x1, int y1, int x2, int y2, int radius, int color) {
        int h = y2-y1, w = x2-x1;
        if (h <= 0 || w <= 0) return;
        int r = Math.min(radius, Math.min(w/2, h/2));
        for (int yy=0; yy<h; yy++) {
            int d = Math.min(yy, h-1-yy), cut = 0;
            if (d < r) { double dy = r-d-.5; cut = (int)Math.ceil(r-Math.sqrt(Math.max(0,r*r-dy*dy))); }
            g.fill(x1+cut, y1+yy, x2-cut, y1+yy+1, color);
        }
    }

     

    private static void drawProgress(GuiGraphics g, Font font, MediaMonitor.Info info, boolean idle,
                                     int left, int right, int y, int accent, int accent2,
                                     MusicConfig c, float p, boolean playing) {
        long pos = idle ? 0 : info.currentPos();
        long dur = idle ? 0 : info.durMs();
        String ts = fmt(pos);
        String te = dur > 0 ? fmt(dur) : "--:--";
        int sub = 0xFF000000 | c.subtext;
        g.drawString(font, ts, left, y, sub, true);
        g.drawString(font, te, right - font.width(te), y, sub, true);

        int bl = left + font.width(ts) + 5;
        int br = right - font.width(te) - 5;
        int by = y + 3;
        if (br - bl < 10) return;
        g.fill(bl, by, br, by + 3, 0x30FFFFFF);                             

        float frac = dur > 0 ? Math.min(1f, (float) pos / dur) : 0f;
        int fillEnd = bl + Math.round((br - bl) * frac);
        float dim = playing ? 1f : 0.55f;
        int span = Math.max(1, br - bl);
        for (int sx = bl; sx < fillEnd; sx += 2) {
            int ex = Math.min(fillEnd, sx + 2);
            int col = lerp(accent, accent2, (sx - bl) / (float) span);
            col = lerp(col, 0xFFFFFF, p * 0.35f);
            g.fill(sx, by, ex, by + 3, argb(Math.round(255 * dim), col));
        }
        if (dur > 0) {                                                      
            int kc = argb(255, lerp(accent2, 0xFFFFFF, 0.6f + 0.4f * p));
            g.fill(fillEnd - 1, by - 1, fillEnd + 1, by + 4, kc);
        }
    }

     
    private static void marquee(GuiGraphics g, Font font, String s, int x, int y, int maxW, int color, long nowNanos) {
        int tw = font.width(s);
        if (tw <= maxW) {
            g.drawString(font, s, x, y, color, true);
            return;
        }
        float t = nowNanos / 1e9f;
        float cycle = tw + 28f;
        float off = (t * 18f) % cycle;
        g.enableScissor(x, y - 1, x + maxW, y + 10);
        g.drawString(font, s, Math.round(x - off), y, color, true);
        g.drawString(font, s, Math.round(x - off + cycle), y, color, true);
        g.disableScissor();
    }

     

    private static void animate(MediaMonitor.Info info, float dt, float t, MusicConfig c) {
        pulse = Math.max(0f, pulse - dt * 3.2f);
        float target = 0f;
        boolean playing = info.playing() && !info.isEmpty();
        MusicConfig.PulseMode mode = c.pulseMode;

        if (playing && mode != MusicConfig.PulseMode.OFF && MusicBarClient.monitor() != null) {
            BeatDetector bd = MusicBarClient.monitor().beat;
            boolean fresh = System.currentTimeMillis() - bd.lastSampleMs < 1500;
            if (fresh) {
                long seq = bd.beatSeq;
                if (seq != lastSeq) { lastSeq = seq; pulse = Math.max(pulse, bd.beatStrength); }
                target = bd.level;
            }
        }
        smoothLevel += (target - smoothLevel) * Math.min(1f, dt * 10f);
    }

     

    private static void rrect(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        g.fill(x1 + 1, y1, x2 - 1, y2, color);
        g.fill(x1, y1 + 1, x1 + 1, y2 - 1, color);
        g.fill(x2 - 1, y1 + 1, x2, y2 - 1, color);
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600;
        long m = (s / 60) % 60;
        long sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }

    static int argb(int alpha, int rgb) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
    }

    static int lerp(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int gr = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (gr << 8) | bl;
    }

    static int hsv(float h, float s, float v) {
        h = h - (float) Math.floor(h);
        float hh = h * 6f;
        int i = (int) hh;
        float f = hh - i;
        float p = v * (1 - s), q = v * (1 - f * s), tt = v * (1 - (1 - f) * s);
        float r, g, b;
        switch (i % 6) {
            case 0 -> { r = v; g = tt; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = tt; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = tt; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }
}
