package dev.musicbar;

import com.google.gson.JsonObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

 
final class WindowsCover {
    private WindowsCover() {}
    static byte[] get(MediaMonitor.Info info) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return null;
        Path script=Files.createTempFile("musicbar-cover-", ".ps1");
        Path image=Files.createTempFile("musicbar-cover-", ".img");
        Process p=null;
        try {
            try(InputStream in=WindowsCover.class.getResourceAsStream("/musicbar/cover.ps1")) {
                if(in==null) throw new IllegalStateException("Missing cover.ps1");
                Files.copy(in,script,StandardCopyOption.REPLACE_EXISTING);
            }
            JsonObject obj=new JsonObject();obj.addProperty("app",info.app());obj.addProperty("title",info.title());obj.addProperty("artist",info.artist());
            String encoded=Base64.getEncoder().encodeToString(obj.toString().getBytes(StandardCharsets.UTF_8));
            String root=System.getenv("SystemRoot");
            String ps=root!=null?root+"\\System32\\WindowsPowerShell\\v1.0\\powershell.exe":"powershell.exe";
            p=new ProcessBuilder(ps,"-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-WindowStyle","Hidden","-File",script.toString(),"-Expected",encoded)
                    .redirectOutput(image.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!p.waitFor(12,TimeUnit.SECONDS)) { MusicBarClient.LOG.warn("MusicBar: media cover request timed out");return null; }
            long size=Files.size(image);
            if(p.exitValue()!=0 || size<=0 || size>1048576) {
                MusicBarClient.LOG.info("MusicBar: current media session did not provide a cover (exit {})",p.exitValue());return null;
            }
            MusicBarClient.LOG.info("MusicBar: cover received from the current Windows media session");
            return Files.readAllBytes(image);
        } finally {
            if(p!=null && p.isAlive()) { p.destroyForcibly(); try { p.waitFor(2,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } }
            try { Files.deleteIfExists(script); } catch(Exception ignored) {}
            try { Files.deleteIfExists(image); } catch(Exception ignored) {}
        }
    }
}
