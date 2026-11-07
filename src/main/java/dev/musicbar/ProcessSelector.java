package dev.musicbar;
import java.util.*;
import java.util.regex.*;

 
public final class ProcessSelector {
    private ProcessSelector() {}
    static Set<String> names(String app, String override) {
        Set<String> names=new LinkedHashSet<>();
        if(override!=null && !override.isBlank()) { String n=base(override.trim()); if(!n.isBlank()) names.add(n); return names; }
        String id=app==null ? "" : app.toLowerCase(Locale.ROOT);
        if(id.contains("spotify")) names.add("spotify");
        if(id.contains("yandexmusic") || id.contains("yandex.music")) { names.add("yandexmusic"); names.add("yandex music"); }
        if(id.contains("vkmusic") || id.contains("vk.music")) { names.add("vkmusic"); names.add("vk music"); }
        if(id.contains("chrome")) names.add("chrome");
        if(id.contains("msedge") || id.contains("microsoftedge")) names.add("msedge");
        if(id.contains("firefox")) names.add("firefox");
        if(id.contains("opera")) { names.add("opera"); names.add("opera_gx"); }
        if(id.contains("brave")) names.add("brave");
        if(id.contains("yandexbrowser") || id.contains("yandex.browser") || id.contains("browser.exe")) names.add("browser");
        Matcher m=Pattern.compile("(?i)([a-z0-9 _.-]+)\\.exe").matcher(id); while(m.find()) names.add(base(m.group(1)));
        return names;
    }
    private static String base(String s) {
        String n=s.replace('\\','/').toLowerCase(Locale.ROOT); int i=n.lastIndexOf('/'); if(i>=0)n=n.substring(i+1);
        return n.endsWith(".exe")?n.substring(0,n.length()-4):n;
    }
    public static long find(String app, String override) {
        Set<String> names=names(app,override); if(names.isEmpty()) return 0;
        try (var stream=ProcessHandle.allProcesses()) {
            List<ProcessHandle> candidates=stream.filter(p -> p.info().command().map(c -> names.contains(base(c))).orElse(false)).toList();
            return candidates.stream().filter(p -> p.parent().flatMap(q -> q.info().command()).map(c -> !names.contains(base(c))).orElse(true))
                .min(Comparator.comparing(p -> p.info().startInstant().orElse(java.time.Instant.MAX)))
                .or(() -> candidates.stream().findFirst()).map(ProcessHandle::pid).orElse(0L);
        } catch (Exception e) { return 0; }
    }
}
