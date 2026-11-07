package dev.musicbar;

 
public final class SpectrumAnalyzer {
    public static final int BANDS = 24;
    private static final int N = 2048, HOP = 512;
    private final float[] ring = new float[N];
    private final double[] re = new double[N], im = new double[N];
    private final int rate;
    private int index, count, since;
    private volatile float[] bands = new float[BANDS];
    private volatile long lastFrameMs;
    public SpectrumAnalyzer(int sampleRate) { rate = sampleRate; }
    public void push(float v) {
        ring[index] = Math.max(-1, Math.min(1, v)); index = (index+1)%N;
        count = Math.min(N,count+1);
        if (++since >= HOP && count == N) { since = 0; calculate(); }
    }
    private void calculate() {
        for (int i=0;i<N;i++) { re[i]=ring[(index+i)%N] * (0.5-0.5*Math.cos(2*Math.PI*i/(N-1))); im[i]=0; }
        for (int i=1,j=0;i<N;i++) {
            int bit=N>>>1; for (; (j&bit)!=0; bit>>>=1) j^=bit; j^=bit;
            if (i<j) { double t=re[i];re[i]=re[j];re[j]=t; }
        }
        for (int len=2;len<=N;len<<=1) {
            double angle=-2*Math.PI/len, wr0=Math.cos(angle), wi0=Math.sin(angle);
            for(int i=0;i<N;i+=len) { double wr=1,wi=0; for(int j=0;j<len/2;j++) {
                int a=i+j,b=a+len/2; double tr=wr*re[b]-wi*im[b], ti=wr*im[b]+wi*re[b];
                re[b]=re[a]-tr; im[b]=im[a]-ti; re[a]+=tr; im[a]+=ti;
                double nr=wr*wr0-wi*wi0; wi=wr*wi0+wi*wr0; wr=nr;
            }}
        }
        float[] out=new float[BANDS]; double high=Math.min(12000,rate*.45);
        for (int b=0;b<BANDS;b++) {
            double lo=30*Math.pow(high/30,(double)b/BANDS), hi=30*Math.pow(high/30,(double)(b+1)/BANDS);
            int first=Math.max(1,(int)Math.ceil(lo*N/rate)), last=Math.min(N/2-1,(int)Math.ceil(hi*N/rate)-1);
            if (last<first) { first=Math.max(1,Math.min(N/2-1,(int)Math.round(Math.sqrt(lo*hi)*N/rate))); last=first; }
            double max=0; for(int k=first;k<=last;k++) max=Math.max(max,Math.hypot(re[k],im[k])*4/N);
             
            out[b]=(float)Math.min(1,Math.sqrt(max*3));
        }
        bands=out; lastFrameMs=System.currentTimeMillis();
    }
    public float[] getBands() { return System.currentTimeMillis()-lastFrameMs < 1000 ? bands : new float[BANDS]; }
    public long lastFrameMs() { return lastFrameMs; }
}
