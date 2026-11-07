package dev.musicbar;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

 
public final class LyricsService {
    private static final ExecutorService EXEC=Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"MusicBar-lyrics");t.setDaemon(true);return t;});
    private static volatile List<LrcParser.Line> lines=List.of();
    private static volatile String status="missing";
    private static final ThreadLocal<Boolean> networkError=ThreadLocal.withInitial(()->false);
    private static String key=""; private static long generation, retryAt; private static Future<?> task;
    private static final List<LrcParser.Line> DEMO=LrcParser.parse("[00:00.00]Музыка звучит прямо сейчас\n[00:05.00]Текст песни и подсветка слов\n");
    private LyricsService() {}
    public static synchronized void refresh(){key="";generation++;lines=List.of();status="missing";retryAt=0;if(task!=null)task.cancel(true);}
    public static LrcParser.Current current(MediaMonitor.Info info){
        if("demo".equals(info.app()))return LrcParser.current(DEMO,info.currentPos()%10000,10000);
        if(info.isEmpty()) return null;
        request(info);
        return LrcParser.current(lines,info.currentPos(),info.durMs());
    }
    public static String message() {
        return switch(status) {case "loading" -> "Загрузка текста..."; case "ready" -> "♪"; case "offline" -> "Онлайн-тексты выключены"; case "network" -> "Ошибка загрузки текста"; default -> "Текст не найден";};
    }
    private static synchronized void request(MediaMonitor.Info info){
        boolean online=MusicConfig.get().lyricsOnline;
        String next=info.artist()+"\u0000"+info.title()+"\u0000"+info.album()+"\u0000"+online;
        boolean changed=!next.equals(key);
        if(!changed && (status.equals("loading") || status.equals("ready") || !online || System.currentTimeMillis()<retryAt))return;
        if(task!=null)task.cancel(true);
        key=next;lines=List.of();status="loading";long id=++generation;
        task=EXEC.submit(() -> {
            networkError.set(false); List<LrcParser.Line> found=load(info,online); boolean failed=networkError.get(); networkError.remove();
            synchronized(LyricsService.class){ if(id==generation){lines=found;status=found.isEmpty()?(online?(failed?"network":"missing"):"offline"):"ready";retryAt=System.currentTimeMillis()+60_000L;}}
        });
    }
    private static String safe(String s){return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();}
    private static List<LrcParser.Line> load(MediaMonitor.Info info,boolean online){
        try {
            Path dir=FabricLoader.getInstance().getConfigDir().resolve("musicbar-lyrics");Files.createDirectories(dir);
            String expected=safe(info.artist())+" - "+safe(info.title())+".lrc";
            try(var files=Files.list(dir)) {
                Path path=files.filter(Files::isRegularFile).filter(p->p.getFileName().toString().equalsIgnoreCase(expected)).findFirst().orElse(null);
                if(path!=null && Files.size(path)<=524288){var local=LrcParser.parse(Files.readString(path,StandardCharsets.UTF_8));if(!local.isEmpty())return local;}
            }
        } catch(Exception e){MusicBarClient.LOG.warn("MusicBar: cannot read local LRC",e);}
        if(!online || info.title().isBlank() || info.artist().isBlank())return List.of();
        try {
            String query="track_name="+enc(info.title())+"&artist_name="+enc(info.artist());
            String get="https://lrclib.net/api/get?"+query;
            if(!info.album().isBlank())get+="&album_name="+enc(info.album());
            if(info.durMs()>0)get+="&duration="+Math.round(info.durMs()/1000.0);
            try {
                BoundedHttp.Response exact=BoundedHttp.get(URI.create(get),524288);
                if(exact.status()==200){
                    List<LrcParser.Line> found=fromJson(JsonParser.parseString(new String(exact.body(),StandardCharsets.UTF_8)).getAsJsonObject());
                    if(!found.isEmpty())return found;
                } else if(exact.status()!=404) {
                    networkError.set(true);
                    MusicBarClient.LOG.warn("MusicBar: LRCLIB exact HTTP {}", exact.status());
                }
            } catch(InterruptedException e){throw e;}
            catch(Exception e){
                if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
                networkError.set(true);
                MusicBarClient.LOG.warn("MusicBar: LRCLIB exact request failed; trying search: {}", e.toString());
            }
            if(Thread.currentThread().isInterrupted())return List.of();
            BoundedHttp.Response search=BoundedHttp.get(URI.create("https://lrclib.net/api/search?"+query),1048576);
            if(search.status()!=200){networkError.set(true);MusicBarClient.LOG.warn("MusicBar: LRCLIB search HTTP {}",search.status());return List.of();}
            networkError.set(false);
            JsonArray array=JsonParser.parseString(new String(search.body(),StandardCharsets.UTF_8)).getAsJsonArray();
            int best=-1;List<LrcParser.Line> result=List.of();
            for(JsonElement elem:array){
                if(!elem.isJsonObject())continue;JsonObject obj=elem.getAsJsonObject();
                int score=TrackMatch.score(info.title(),info.artist(),value(obj,"trackName"),value(obj,"artistName"));
                if(score<0)continue;
                if(info.durMs()>0 && obj.has("duration") && !obj.get("duration").isJsonNull()){
                    double delta=Math.abs(info.durMs()/1000.0-obj.get("duration").getAsDouble());if(delta>20)continue;if(delta<5)score+=2;
                }
                List<LrcParser.Line> candidate=fromJson(obj);if(!candidate.isEmpty() && score>best){best=score;result=candidate;}
            }
            return result;
        }catch(InterruptedException e){Thread.currentThread().interrupt();return List.of();}
        catch(Exception e){if(!Thread.currentThread().isInterrupted()){networkError.set(true);MusicBarClient.LOG.warn("MusicBar: lyrics search failed: {}",e.toString());}return List.of();}
    }
    private static List<LrcParser.Line> fromJson(JsonObject obj){
        if(obj.has("syncedLyrics")&&!obj.get("syncedLyrics").isJsonNull())return LrcParser.parse(obj.get("syncedLyrics").getAsString());
        if(obj.has("instrumental") && !obj.get("instrumental").isJsonNull() && obj.get("instrumental").getAsBoolean())return LrcParser.parse("[00:00.00]\n");
        return List.of();
    }
    private static String value(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    private static String enc(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
}
