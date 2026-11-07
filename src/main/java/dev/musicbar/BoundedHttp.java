package dev.musicbar;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.Locale;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

 
public final class BoundedHttp {
    private static final String UA="MusicBar/1.5.2 (Minecraft Fabric)";
    private static final boolean WINDOWS=System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win");
    static { try { System.setProperty("java.net.useSystemProxies","true"); } catch(Exception ignored) {} }
     
    private static final HttpClient HTTP=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).proxy(ProxySelector.getDefault()).connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    public record Response(int status, byte[] body) {}
    private BoundedHttp() {}
    public static Response get(URI uri, int limit) throws Exception {
        try { return javaGet(uri,limit); }
        catch(InterruptedException e) { throw e; }
        catch(Exception e) {
            if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String m=String.valueOf(e.getMessage());
            if(!WINDOWS || m.contains("too large")) throw e;
            MusicBarClient.LOG.warn("MusicBar: Java HTTP failed for {} ({}); retrying through Windows PowerShell", uri.getHost(), e.toString());
            try { return powerShellGet(uri,limit); }
            catch(InterruptedException ie) { throw ie; }
            catch(Exception e2) { e2.addSuppressed(e); throw e2; }
        }
    }
    private static Response powerShellGet(URI uri,int limit) throws Exception {
        Path script=Files.createTempFile("musicbar-http-",".ps1"), body=Files.createTempFile("musicbar-http-",".bin"), out=Files.createTempFile("musicbar-http-",".txt");
        Process p=null;
        try {
            try(InputStream in=BoundedHttp.class.getResourceAsStream("/musicbar/http.ps1")) {
                if(in==null) throw new IllegalStateException("Missing http.ps1");
                Files.copy(in,script,StandardCopyOption.REPLACE_EXISTING);
            }
            String root=System.getenv("SystemRoot");
            String ps=root!=null?root+"\\System32\\WindowsPowerShell\\v1.0\\powershell.exe":"powershell.exe";
            p=new ProcessBuilder(ps,"-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-WindowStyle","Hidden","-File",script.toString(),
                    "-Url",uri.toString(),"-Out",body.toString(),"-UserAgent",UA).redirectOutput(out.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!p.waitFor(20,TimeUnit.SECONDS)) throw new java.io.IOException("PowerShell HTTP timeout");
            if(p.exitValue()!=0) throw new java.io.IOException("PowerShell HTTP failed, exit "+p.exitValue());
            int status=Integer.parseInt(Files.readString(out).trim());
            if(Files.size(body)>limit) throw new IllegalStateException("HTTP body too large");
            return new Response(status,Files.readAllBytes(body));
        } finally {
            if(p!=null && p.isAlive()) p.destroyForcibly();
            for(Path f:new Path[]{script,body,out}) try{Files.deleteIfExists(f);}catch(Exception ignored){}
        }
    }
    private static Response javaGet(URI uri, int limit) throws Exception {
        HttpRequest req=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("User-Agent",UA).GET().build();
        CompletableFuture<HttpResponse<byte[]>> future=HTTP.sendAsync(req, response -> new Limited(limit));
        try { HttpResponse<byte[]> result=future.get(18,TimeUnit.SECONDS); return new Response(result.statusCode(),result.body()); }
        finally { if(!future.isDone()) future.cancel(true); }
    }
    static class Limited implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> done=new CompletableFuture<>(); final ByteArrayOutputStream out=new ByteArrayOutputStream(); final int limit;
        Flow.Subscription subscription;
        Limited(int limit) {this.limit=limit;}
        public CompletionStage<byte[]> getBody(){return done;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            try {
                for(ByteBuffer b:buffers) {int n=b.remaining(); if(n>limit-out.size())throw new IllegalStateException("HTTP body too large"); byte[] tmp=new byte[n];b.get(tmp);out.write(tmp);}
                subscription.request(1);
            } catch(Exception e) {subscription.cancel();done.completeExceptionally(e);}
        }
        public void onError(Throwable t){done.completeExceptionally(t);}
        public void onComplete(){done.complete(out.toByteArray());}
    }
}
