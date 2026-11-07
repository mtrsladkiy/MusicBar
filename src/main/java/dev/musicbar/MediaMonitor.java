package dev.musicbar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.regex.Pattern;

 





public final class MediaMonitor {

    public record Info(String title, String artist, String album, String app,
                       boolean playing, long posMs, long durMs, long baseNanos) {
        public static final Info NONE = new Info("", "", "", "", false, 0, 0, 0);

        public boolean isEmpty() {
            return title.isEmpty() && artist.isEmpty();
        }

         
        public long currentPos() {
            long p = posMs + (playing ? (System.nanoTime() - baseNanos) / 1_000_000L : 0);
            if (durMs > 0) p = Math.min(p, durMs);
            return Math.max(0, p);
        }
    }

    @FunctionalInterface
    private interface StreamHandler {
        void handle(InputStream in) throws IOException;
    }

    public final BeatDetector beat = new BeatDetector();
    private volatile SpectrumAnalyzer spectrum;
    private volatile long audioGeneration;
    public float[] spectrum() { SpectrumAnalyzer s = spectrum; return s == null ? new float[SpectrumAnalyzer.BANDS] : s.getBands(); }
    private volatile Info info = Info.NONE;
    private volatile String error;
    private volatile boolean running;
    private final List<Process> procs = new CopyOnWriteArrayList<>();

    public Info info() {
        return info;
    }

    public String error() {
        return error;
    }

     

    public void start() {
        if (running) return;
        running = true;
        Runtime.getRuntime().addShutdownHook(new Thread(this::killAll, "MusicBar-shutdown"));
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) startWindows();
            else if (os.contains("mac")) startMac();
            else startLinux();
        } catch (Exception e) {
            error = "Ошибка запуска: " + e.getMessage();
            MusicBarClient.LOG.warn("MediaMonitor start failed", e);
        }
    }

    public void stop() {
        running = false;
        killAll();
    }

    private void killAll() {
        for (Process p : procs) p.destroyForcibly();
        procs.clear();
    }

     

    private void startWindows() throws IOException {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("musicbar-scripts");
        Files.createDirectories(dir);
        Path media = extract("media.ps1", dir);
        Path spectrumScript = extract("spectrum-process.ps1", dir);
        String root = System.getenv("SystemRoot");
        String ps = root != null ? root + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" : "powershell.exe";
        spawn("media", psCmd(ps, media), in -> lines(in, this::onWinMedia));
        startProcessAudio(ps, spectrumScript);
    }

    private void startProcessAudio(String ps, Path script) {
        Thread manager = new Thread(() -> {
            Process current = null; long currentPid = 0; long retryAt = 0; String missingSource = "";
            try {
                while (running) {
                    Info playing = info;
                    long pid = playing.playing() && !playing.isEmpty()
                            ? ProcessSelector.find(playing.app(), MusicConfig.get().musicProcess) : 0;
                    String sourceName = playing.app() + " | " + MusicConfig.get().musicProcess;
                    if (playing.playing() && !playing.isEmpty() && pid == 0 && !sourceName.equals(missingSource)) {
                        MusicBarClient.LOG.warn("MusicBar: could not identify media process for '{}'. Set musicProcess in config/musicbar.json if needed", sourceName);
                        missingSource = sourceName;
                    }
                    if (pid != 0) missingSource = "";
                    if (current != null && (pid != currentPid || !current.isAlive())) {
                        current.destroyForcibly(); procs.remove(current); current=null; currentPid=0; audioGeneration++; spectrum=null;
                    }
                    if (pid != 0 && current == null && System.currentTimeMillis() >= retryAt) {
                        retryAt = System.currentTimeMillis()+5000;
                        try {
                            List<String> command = new ArrayList<>(psCmd(ps,script)); command.add(1, "-MTA"); command.add("-TargetPid"); command.add(Long.toString(pid));
                            Process started = new ProcessBuilder(command).start(); current=started; currentPid=pid; procs.add(started);
                            long generation = ++audioGeneration;
                            Thread reader = new Thread(() -> {
                                try { readWindowsPcm(started.getInputStream(), generation); }
                                catch (IOException e) { MusicBarClient.LOG.warn("MusicBar process audio: {}", e.toString()); }
                            }, "MusicBar-process-pcm"); reader.setDaemon(true); reader.start();
                            Thread err = new Thread(() -> {
                                try (BufferedReader r = new BufferedReader(new InputStreamReader(started.getErrorStream(), StandardCharsets.UTF_8))) {
                                    String line; int n=0; while ((line=r.readLine()) != null) if(n++<12) MusicBarClient.LOG.warn("MusicBar audio: {}", line);
                                } catch (IOException ignored) {}
                            }, "MusicBar-process-errors"); err.setDaemon(true); err.start();
                            MusicBarClient.LOG.info("MusicBar: starting process-only capture for PID {}", pid);
                        } catch (IOException ex) { MusicBarClient.LOG.warn("MusicBar: process capture failed",ex); }
                    }
                    sleep(1200);
                }
            } finally { if(current!=null) {current.destroyForcibly(); procs.remove(current);} spectrum=null; }
        }, "MusicBar-process-manager");
        manager.setDaemon(true); manager.start();
    }

    private static List<String> psCmd(String ps, Path script) {
        return List.of(ps, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden", "-File", script.toString());
    }

    private static Path extract(String name, Path dir) throws IOException {
        Path target = dir.resolve(name);
        try (InputStream in = MediaMonitor.class.getResourceAsStream("/musicbar/" + name)) {
            if (in == null) throw new IOException("Нет ресурса " + name);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private void onWinMedia(String line) {
        try {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            if (o.has("error")) {
                error = o.get("error").getAsString();
                info = Info.NONE;
                return;
            }
            error = null;
            if (o.has("none")) {
                info = Info.NONE;
                return;
            }
            String title = str(o, "title");
            String artist = str(o, "artist");
            if (title.isEmpty() && artist.isEmpty()) {
                info = Info.NONE;
                return;
            }
            boolean playing = "Playing".equalsIgnoreCase(str(o, "status"));
            long pos = lng(o, "pos");
            long start = lng(o, "start");
            long end = lng(o, "end");
            long updated = lng(o, "updated");
            long dur = Math.max(0, end - start);
            long age = updated > 0 ? Math.max(0, System.currentTimeMillis() - updated) : 0;
            if (age > 6 * 3600_000L) age = 0;                  
            long p = Math.max(0, pos - start) + (playing ? age : 0);
            Info prior = info;
            info = new Info(title, artist, str(o, "album"), str(o, "app"), playing, p, dur, System.nanoTime());
            if (!title.equals(prior.title()) || !artist.equals(prior.artist()) || !str(o, "app").equals(prior.app()))
                MusicBarClient.LOG.info("MusicBar: media track changed; updating online content");
        } catch (Exception e) {
            MusicBarClient.LOG.warn("MusicBar: could not parse media update: {}", e.toString());
        }
    }

    private void readWindowsPcm(InputStream in, long generation) throws IOException {
        byte[] header = in.readNBytes(4);
        if (header.length != 4) throw new IOException("WASAPI loopback did not start");
        int rate = (header[0]&255) | ((header[1]&255)<<8) | ((header[2]&255)<<16) | ((header[3]&255)<<24);
        if (rate < 8000 || rate > 384000) throw new IOException("Invalid sample rate");
        MusicBarClient.LOG.info("MusicBar: process audio started, sample rate {}", rate);
        readPcmAtRate(in, rate, generation);
    }

    private void readPcmAtRate(InputStream in, int rate, long generation) throws IOException {
        if (generation >= 0 && generation != audioGeneration) return;
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(rate); spectrum = analyzer;
        int carry = -1; byte[] buf = new byte[4096]; int n, samples = 0;
        while (running && (generation < 0 || generation == audioGeneration) && (n = in.read(buf)) > 0) {
            for (int i=0;i<n;i++) {
                if (carry < 0) { carry=buf[i]&255; continue; }
                short v=(short)((buf[i]<<8)|carry); carry=-1; analyzer.push(v/32768f);
                if (++samples >= rate/50) { samples=0; float[] bands=analyzer.getBands();
                    float bass=0; for(int b=0;b<Math.min(8,bands.length);b++) bass=Math.max(bass,bands[b]); beat.push(bass); }
            }
        }
    }

     

    private static final String SEP = "|~|";

    private void startLinux() {
        poll("media", () -> {
            List<String> out;
            try {
                out = run(List.of("playerctl", "metadata", "--format",
                        "{{status}}" + SEP + "{{position}}" + SEP + "{{mpris:length}}" + SEP
                                + "{{artist}}" + SEP + "{{title}}" + SEP + "{{album}}"));
            } catch (IOException e) {
                error = "Установите playerctl (sudo apt install playerctl)";
                return false;
            }
            error = null;
            if (out == null || out.isEmpty()) {
                info = Info.NONE;
                return true;
            }
            String[] p = out.get(0).split(Pattern.quote(SEP), -1);
            if (p.length < 6 || (p[3].isEmpty() && p[4].isEmpty())) {
                info = Info.NONE;
                return true;
            }
            long pos = parseLong(p[1]) / 1000;
            long dur = parseLong(p[2]) / 1000;
            info = new Info(p[4], p[3], p[5], "", "Playing".equalsIgnoreCase(p[0]), pos, dur, System.nanoTime());
            return true;
        });
        spawn("audio", List.of("parec", "--device=@DEFAULT_MONITOR@", "--format=s16le",
                "--rate=22050", "--channels=1", "--latency-msec=20"), in -> readPcmAtRate(in, 22050, -1));
    }

     

    private void startMac() {
        poll("media", () -> {
            List<String> out;
            try {
                out = run(List.of("nowplaying-cli", "get", "title", "artist", "album", "duration", "elapsedTime", "playbackRate"));
            } catch (IOException e) {
                error = "Установите nowplaying-cli (brew install nowplaying-cli)";
                return false;
            }
            error = null;
            if (out == null || out.size() < 6) {
                info = Info.NONE;
                return true;
            }
            String title = clean(out.get(0));
            String artist = clean(out.get(1));
            if (title.isEmpty() && artist.isEmpty()) {
                info = Info.NONE;
                return true;
            }
            long dur = (long) (parseDouble(out.get(3)) * 1000);
            long pos = (long) (parseDouble(out.get(4)) * 1000);
            boolean playing = parseDouble(out.get(5)) > 0;
            info = new Info(title, artist, clean(out.get(2)), "", playing, pos, dur, System.nanoTime());
            return true;
        });
    }

     

     
    private void spawn(String name, List<String> cmd, StreamHandler handler) {
        Thread t = new Thread(() -> {
            int fails = 0;
            while (running && fails < 4) {
                long started = System.currentTimeMillis();
                try {
                    Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    procs.add(p);
                    try {
                        handler.handle(p.getInputStream());
                    } finally {
                        p.destroyForcibly();
                        procs.remove(p);
                        if (name.equals("media") && running) { info=Info.NONE; MusicBarClient.LOG.warn("MusicBar: media monitor stopped; restarting"); }
                    }
                } catch (IOException e) {
                    if (fails == 0) MusicBarClient.LOG.warn("MusicBar[{}]: {}", name, e.toString());
                    if (name.equals("media") && error == null) error = "Не удалось запустить: " + cmd.get(0);
                }
                fails = System.currentTimeMillis() - started < 5000 ? fails + 1 : 0;
                sleep(1500);
            }
        }, "MusicBar-" + name);
        t.setDaemon(true);
        t.start();
    }

     
    private void poll(String name, Callable<Boolean> task) {
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    if (!task.call()) return;
                } catch (Exception e) {
                    MusicBarClient.LOG.debug("MusicBar[{}] poll error", name, e);
                }
                sleep(1000);
            }
        }, "MusicBar-" + name);
        t.setDaemon(true);
        t.start();
    }

    private void lines(InputStream in, Consumer<String> onLine) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = r.readLine()) != null) onLine.accept(line);
        }
    }

     
    private static List<String> run(List<String> cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        }
        try {
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return p.exitValue() == 0 ? out : null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static long lng(JsonObject o, String k) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String clean(String s) {
        s = s == null ? "" : s.trim();
        return s.equals("null") || s.equals("(null)") ? "" : s;
    }
}
