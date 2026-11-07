package dev.musicbar;
import java.text.Normalizer;
import java.util.Locale;
 
public final class TrackMatch {
    private TrackMatch() {}
    public static String norm(String s) {
        if(s==null) return "";
        s=Normalizer.normalize(s,Normalizer.Form.NFKD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT);
        s=s.replaceAll("(?iu)\\s*[\\(\\[]\\s*(feat\\.?|ft\\.?|featuring)\\b[^\\)\\]]*[\\)\\]]", "");
        s=s.replaceAll("(?iu)\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$", "");
        return s.replaceAll("[^\\p{L}\\p{N}]+"," ").trim().replaceAll("\\s+"," ");
    }
    public static int score(String title,String artist,String candidateTitle,String candidateArtist) {
        String t=norm(title), a=norm(artist), ct=norm(candidateTitle), ca=norm(candidateArtist);
        if(t.isEmpty()||a.isEmpty()||ct.isEmpty()||ca.isEmpty()) return -1;
        boolean titleOK=t.equals(ct) || (Math.min(t.length(),ct.length())>=5 && (ct.startsWith(t+" ") || t.startsWith(ct+" ")));
        boolean artistOK=a.equals(ca) || (Math.min(a.length(),ca.length())>=4 && (ca.startsWith(a+" ") || a.startsWith(ca+" ")));
        if(!titleOK || !artistOK)return -1;
        return (t.equals(ct)?4:2)+(a.equals(ca)?4:2);
    }
}
