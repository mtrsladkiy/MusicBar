package dev.musicbar;

import com.google.gson.*;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.*;

 
public final class CoverArt {
    private static final Identifier ID=Identifier.fromNamespaceAndPath("musicbar","cover/current");
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"MusicBar-cover");t.setDaemon(true);return t;});
    private static String wanted="";private static long generation,retryAt;private static Future<?> task;
    private static volatile String active="";private static volatile int imageWidth,imageHeight;
    private CoverArt() {}
    private static String trackKey(MediaMonitor.Info i){return i.app()+"\u0000"+i.title()+"\u0000"+i.artist()+"\u0000"+i.album();}
    private static synchronized void request(MediaMonitor.Info info) {
        boolean enabled=MusicConfig.get().coverOnline;
        String key=info.isEmpty() || "demo".equals(info.app()) ? "" : trackKey(info) + "\u0000" + enabled;
        boolean changed=!wanted.equals(key);
        if(!changed && (key.isEmpty() || !enabled || !active.isEmpty() || System.currentTimeMillis()<retryAt))return;
        if(task!=null) task.cancel(true);
        wanted=key; long id=++generation; active=""; retryAt=System.currentTimeMillis()+60_000L;
        Minecraft.getInstance().execute(()->{if(active.isEmpty())Minecraft.getInstance().getTextureManager().release(ID);});
        if(key.isEmpty() || !enabled || info.title().isBlank() || info.artist().isBlank())return;
        task=WORKER.submit(()->{
            NativeImage image=null;
            try {
                byte[] bytes=null;
                try { bytes=WindowsCover.get(info); }
                catch(InterruptedException e) { throw e; }
                catch(Exception e) { MusicBarClient.LOG.warn("MusicBar: media cover unavailable: {}",e.toString()); }
                if(bytes!=null && !safeDimensions(bytes)) bytes=null;
                if(bytes==null) bytes=fetch(info);
                if(bytes==null || !safeDimensions(bytes))return;
                image=NativeImage.read(CoverImage.toPng(bytes));
                NativeImage ready=image;image=null;
                Minecraft.getInstance().execute(()->{
                    synchronized(CoverArt.class){
                        if(id!=generation || !key.equals(wanted)){ready.close();return;}
                        try {DynamicTexture texture=new DynamicTexture(()->"Music Bar album cover",ready);
                            Minecraft.getInstance().getTextureManager().register(ID,texture);
                            imageWidth=ready.getWidth();imageHeight=ready.getHeight();active=key;
                        }catch(Exception e){ready.close();MusicBarClient.LOG.warn("MusicBar: cover upload failed",e);}
                    }
                });
            }catch(InterruptedException e){Thread.currentThread().interrupt();}
            catch(Exception e){MusicBarClient.LOG.warn("MusicBar: cover search failed: {}",e.toString());}
            finally{if(image!=null)image.close();}
        });
    }
    private static byte[] fetch(MediaMonitor.Info info) throws Exception {
        String term=URLEncoder.encode(info.artist()+" "+info.title(),StandardCharsets.UTF_8);
        BoundedHttp.Response response=BoundedHttp.get(URI.create("https://itunes.apple.com/search?entity=song&limit=15&term="+term),1048576);
        if(response.status()!=200)return null;
        JsonObject json=JsonParser.parseString(new String(response.body(),StandardCharsets.UTF_8)).getAsJsonObject();
        if(!json.has("results") || !json.get("results").isJsonArray())return null;
        int best=-1;String cover=null;
        for(JsonElement el:json.getAsJsonArray("results")){
            if(!el.isJsonObject())continue;JsonObject obj=el.getAsJsonObject();
            int score=TrackMatch.score(info.title(),info.artist(),value(obj,"trackName"),value(obj,"artistName"));
            if(score<0)continue;
            if(info.durMs()>0 && obj.has("trackTimeMillis") && !obj.get("trackTimeMillis").isJsonNull()) {
                long diff=Math.abs(info.durMs()-obj.get("trackTimeMillis").getAsLong());if(diff>20000)continue;if(diff<5000)score+=2;
            }
            String u=value(obj,"artworkUrl100");if(!u.isBlank() && score>best){best=score;cover=u;}
        }
        if(cover==null || Thread.currentThread().isInterrupted())return null;
        URI uri=URI.create(cover.replace("/100x100bb", "/300x300bb"));String host=uri.getHost();
        if(!"https".equalsIgnoreCase(uri.getScheme()) || host==null || !host.toLowerCase(Locale.ROOT).endsWith(".mzstatic.com") || uri.getUserInfo()!=null || uri.getPort()!=-1)return null;
        BoundedHttp.Response img=BoundedHttp.get(uri,1048576);return img.status()==200?img.body():null;
    }
    private static String value(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    private static boolean safeDimensions(byte[] bytes)throws Exception{
        try(ImageInputStream in=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))){
            Iterator<ImageReader> iter=ImageIO.getImageReaders(in);if(!iter.hasNext())return false;ImageReader reader=iter.next();
            try{reader.setInput(in,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);return w>0&&h>0&&w<=2048&&h<=2048;}finally{reader.dispose();}
        }
    }
    public static boolean draw(GuiGraphics g,MediaMonitor.Info info,int x,int y,int size){
        request(info);String key=trackKey(info)+"\u0000"+MusicConfig.get().coverOnline;
        if(info.isEmpty()||!key.equals(active)||imageWidth<=0||imageHeight<=0)return false;
        int r=size/2,crop=Math.min(imageWidth,imageHeight),u=(imageWidth-crop)/2,v=(imageHeight-crop)/2;
        for(int row=0;row<size;row++){
            double dy=Math.abs(row+.5-size/2.0);int inset=(int)Math.ceil(r-Math.sqrt(Math.max(0,r*r-dy*dy)));
            if(inset*2>=size)continue;g.enableScissor(x+inset,y+row,x+size-inset,y+row+1);
            g.blit(RenderPipelines.GUI_TEXTURED,ID,x,y,(float)u,(float)v,size,size,crop,crop,imageWidth,imageHeight);g.disableScissor();
        }
        return true;
    }
}
