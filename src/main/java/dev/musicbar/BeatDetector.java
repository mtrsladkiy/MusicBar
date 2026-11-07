package dev.musicbar;

 



public final class BeatDetector {
    public volatile float level;           
    public volatile long beatSeq;          
    public volatile float beatStrength;    
    public volatile long lastSampleMs;     

    private float peakNorm = 0.05f;
    private float fast;
    private float prevFast;
    private float avg;
    private long lastBeatMs;

    public void push(float raw) {
        long now = System.currentTimeMillis();
        lastSampleMs = now;
        raw = Math.max(0f, Math.min(1f, raw));

        peakNorm = Math.max(raw, peakNorm * 0.9996f);
        if (peakNorm < 0.02f) peakNorm = 0.02f;
        float x = raw / peakNorm;
        level = x;

        fast = fast * 0.45f + x * 0.55f;
        avg += (x - avg) * 0.025f;

        float sens = MusicConfig.get().sensitivity / 100f;
        float ratio = 1.7f - 0.58f * sens;            
        boolean rising = fast - prevFast > 0.04f;

        if (fast > avg * ratio + 0.03f && rising && x > 0.22f && now - lastBeatMs > 170) {
            lastBeatMs = now;
            beatStrength = Math.max(0.4f, Math.min(1f, (fast - avg) * 2.2f + 0.45f));
            beatSeq++;
        }
        prevFast = fast;
    }
}
