package dev.musicbar;

import java.util.*;
import java.util.regex.*;

 
public final class LrcParser {
    private static final Pattern LINE = Pattern.compile("^\\s*((?:\\[\\d+:\\d{2}(?:[.:]\\d{1,3})?\\])+)(.*)$");
    private static final Pattern TIME = Pattern.compile("[\\[<](\\d+):(\\d{2})(?:[.:](\\d{1,3}))?[\\]>]");
    private static final Pattern TAG = Pattern.compile("<(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?>");
    private static final Pattern WORD = Pattern.compile("\\S+");
    private static final Pattern OFFSET = Pattern.compile("(?i)^\\s*\\[offset:([+-]?\\d+)\\]\\s*$");
    public record Word(int start, int end, long at) {}
    public record Line(long at, String text, List<Word> words, boolean precise) {}
    public record Current(String text, int start, int end, boolean precise) {}
    private LrcParser() {}
    private static long time(Matcher m) {
        String f = m.group(3); long fraction = f == null ? 0 : Integer.parseInt((f + "00").substring(0,3));
        return (Long.parseLong(m.group(1))*60 + Long.parseLong(m.group(2)))*1000 + fraction;
    }
    public static List<Line> parse(String source) {
        if (source == null || source.length() > 524288) return List.of();
        String[] rows = source.replace("\uFEFF", "").split("\\R"); long offset = 0;
        for (String row : rows) { Matcher m = OFFSET.matcher(row); if (m.matches()) try { offset = Long.parseLong(m.group(1)); } catch (NumberFormatException ignored) {} }
        List<Line> result = new ArrayList<>();
        for (String row : rows) try {
            Matcher lm = LINE.matcher(row); if (!lm.matches()) continue;
            List<Long> starts = new ArrayList<>(); Matcher tm = TIME.matcher(lm.group(1)); while (tm.find()) starts.add(Math.max(0, time(tm) + offset));
            String body = lm.group(2); Matcher tags = TAG.matcher(body); StringBuilder text = new StringBuilder();
            List<Word> words = new ArrayList<>(); int last = 0; long at = starts.get(0); boolean precise = false;
            while (tags.find()) {
                addWords(text, words, body.substring(last, tags.start()), at);
                at = Math.max(0, time(tags) + offset); precise = true; last = tags.end();
            }
            addWords(text, words, body.substring(last), at);
            String s = text.toString();
            for (long start : starts) result.add(new Line(start, s, List.copyOf(words), precise));
        } catch (RuntimeException ignored) { }
        result.sort(Comparator.comparingLong(Line::at)); return List.copyOf(result);
    }
    private static void addWords(StringBuilder text, List<Word> words, String segment, long at) {
        int base = text.length(); text.append(segment); Matcher m = WORD.matcher(segment);
        while (m.find()) words.add(new Word(base+m.start(), base+m.end(), at));
    }
    public static Current current(List<Line> lines, long pos, long duration) {
        if (lines == null || lines.isEmpty() || pos < lines.get(0).at()) return null;
        int i = 0; while (i+1 < lines.size() && lines.get(i+1).at() <= pos) i++;
        Line line = lines.get(i); if (line.words().isEmpty()) return new Current(line.text(), 0, 0, line.precise());
        int wi = 0;
        if (line.precise()) { for (int j=0;j<line.words().size();j++) if (line.words().get(j).at() <= pos) wi=j; else break; }
        else {
            long end = i+1 < lines.size() ? lines.get(i+1).at() : duration > line.at() ? duration : line.at()+6000;
             
             
             
            long estimatedDuration = Math.max(1200L, line.words().size() * 450L);
            long visibleDuration = Math.max(1, Math.min(end - line.at(), estimatedDuration));
            double fraction = Math.max(0, Math.min(0.999999, (double)(pos-line.at())/visibleDuration));
            wi = Math.min(line.words().size()-1, (int)(fraction*line.words().size()));
        }
        Word w = line.words().get(wi); return new Current(line.text(), w.start(), w.end(), line.precise());
    }
}
