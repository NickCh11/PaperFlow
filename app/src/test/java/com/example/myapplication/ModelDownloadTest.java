package com.example.myapplication;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.zip.GZIPOutputStream;
import org.junit.*;
import static org.junit.Assert.*;

/** Real HTTP transfers exercise fallback, corruption, retries and atomic installation. */
public class ModelDownloadTest {
    private ServerSocket server;private ExecutorService worker;private Path directory;private File target;
    private final Map<String,Supplier<Response>> routes=new ConcurrentHashMap<>();
    private static final class Response{final int status;final byte[] bytes;final String location;Response(int status,byte[] bytes,String location){this.status=status;this.bytes=bytes;this.location=location;}}
    private void acceptRequests(){while(!server.isClosed())try(Socket socket=server.accept()){
        socket.setSoTimeout(5000);BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));String line=in.readLine();if(line==null)continue;String path=line.split(" ")[1];while((line=in.readLine())!=null&&!line.isEmpty()){}
        Supplier<Response> route=routes.get(path);Response response=route==null?new Response(404,new byte[]{0},null):route.get();
        String headers="HTTP/1.1 "+response.status+" Response\r\nContent-Length: "+response.bytes.length+"\r\nConnection: close\r\n"+(response.location==null?"":"Location: "+response.location+"\r\n")+"\r\n";
        OutputStream out=socket.getOutputStream();out.write(headers.getBytes(StandardCharsets.US_ASCII));out.write(response.bytes);out.flush();
    }catch(IOException closedOrCancelled){/* Closing a response on cancellation is expected. */}}

    private final byte[] good="A pinned offline language model".getBytes(StandardCharsets.UTF_8);
    @Before public void before()throws Exception{server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));worker=Executors.newSingleThreadExecutor();worker.submit(this::acceptRequests);directory=Files.createTempDirectory("model-download-");target=directory.resolve("model.bin").toFile();}
    @After public void after()throws Exception{server.close();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));try(java.util.stream.Stream<Path> paths=Files.walk(directory)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new UncheckedIOException(e);}});}}
    private String url(String path){return "http://127.0.0.1:"+server.getLocalPort()+path;}
    private void serve(String path,int status,byte[] bytes,AtomicInteger count){routes.put(path,()->{if(count!=null)count.incrementAndGet();return new Response(status,bytes,null);});}
    private String hash(byte[] bytes,boolean git)throws Exception{MessageDigest hash=MessageDigest.getInstance(git?"SHA-1":"SHA-256");if(git)hash.update(("blob "+bytes.length+"\0").getBytes(StandardCharsets.UTF_8));StringBuilder value=new StringBuilder();for(byte b:hash.digest(bytes))value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();}
    private ModelDownload.Spec spec(boolean git)throws Exception{return new ModelDownload.Spec(good.length,hash(good,git),git);}
    private ModelDownload.Source source(String path){return new ModelDownload.Source(url(path),false);}
    private void download(ModelDownload.Source...sources)throws Exception{ModelDownload.download(Arrays.asList(sources),target,spec(false),()->false,(a,b)->{});}
    private void cleanPartial(){assertFalse(new File(target+".part").exists());}

    @Test public void missingOfficialUsesMirrorWithoutRetrying404()throws Exception{
        AtomicInteger official=new AtomicInteger(),mirror=new AtomicInteger();serve("/official",404,new byte[]{1},official);serve("/mirror",200,good,mirror);
        download(source("/official"),source("/mirror"));assertArrayEquals(good,Files.readAllBytes(target.toPath()));assertEquals(1,official.get());assertEquals(1,mirror.get());cleanPartial();
    }
    @Test public void corruptOfficialFallsBackButNeverAcceptsDifferentBytes()throws Exception{
        byte[] corrupt=good.clone();corrupt[0]^=1;serve("/corrupt",200,corrupt,null);serve("/mirror",200,good,null);
        download(source("/corrupt"),source("/mirror"));assertArrayEquals(good,Files.readAllBytes(target.toPath()));cleanPartial();
    }
    @Test public void allInvalidSourcesPreserveExistingInstallation()throws Exception{
        byte[] original="Existing working pack".getBytes(StandardCharsets.UTF_8);Files.write(target.toPath(),original);
        byte[] corrupt=good.clone();corrupt[1]^=1;serve("/a",200,corrupt,null);serve("/b",200,corrupt,null);
        try{download(source("/a"),source("/b"));fail("Corruption accepted");}catch(ModelDownload.Failure expected){assertEquals(ModelDownload.Reason.INTEGRITY,expected.reason);}
        assertArrayEquals(original,Files.readAllBytes(target.toPath()));cleanPartial();
    }
    @Test public void corruptionIsReportedEvenWhenTheMirrorIsMissing()throws Exception{
        byte[] corrupt=good.clone();corrupt[0]^=1;serve("/corrupt",200,corrupt,null);serve("/absent",404,new byte[]{0},null);
        try{download(source("/corrupt"),source("/absent"));fail("Corruption accepted");}catch(ModelDownload.Failure expected){assertEquals(ModelDownload.Reason.INTEGRITY,expected.reason);}
        assertFalse(target.exists());cleanPartial();
    }
    @Test public void cancellationStopsBeforeMirrorAndLeavesNoPartial()throws Exception{
        AtomicBoolean cancelled=new AtomicBoolean();AtomicInteger mirror=new AtomicInteger();serve("/a",200,good,null);serve("/b",200,good,mirror);
        try{ModelDownload.download(Arrays.asList(source("/a"),source("/b")),target,spec(false),cancelled::get,(a,b)->cancelled.set(true));fail("Cancellation ignored");}catch(ModelDownload.Cancelled expected){}
        assertFalse(target.exists());assertEquals(0,mirror.get());cleanPartial();
    }
    @Test public void transientServerFailureRetriesBeforeUsingMirror()throws Exception{
        AtomicInteger attempts=new AtomicInteger(),mirror=new AtomicInteger();
        routes.put("/retry",()->new Response(attempts.incrementAndGet()==1?503:200,good,null));serve("/mirror",200,good,mirror);
        download(source("/retry"),source("/mirror"));assertEquals(2,attempts.get());assertEquals(0,mirror.get());assertArrayEquals(good,Files.readAllBytes(target.toPath()));
    }
    @Test public void oversizedGzipFallsBackToRawMirrorWithSameGitBlobPin()throws Exception{
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();try(GZIPOutputStream out=new GZIPOutputStream(compressed)){out.write(new byte[good.length*100]);}
        serve("/compressed",200,compressed.toByteArray(),null);serve("/raw",200,good,null);
        ModelDownload.download(Arrays.asList(new ModelDownload.Source(url("/compressed"),true),source("/raw")),target,spec(true),()->false,(a,b)->{});
        assertArrayEquals(good,Files.readAllBytes(target.toPath()));cleanPartial();
    }
    @Test public void relativeRedirectAndPinnedAssetNamesAreSupported()throws Exception{
        routes.put("/redirect",()->new Response(307,new byte[0],"/bytes"));serve("/bytes",200,good,null);
        download(source("/redirect"));assertArrayEquals(good,Files.readAllBytes(target.toPath()));
        assertEquals("sha256-"+hash(good,false)+".bin",ModelDownload.assetName(spec(false)));assertEquals("blobsha1-"+hash(good,true)+".traineddata",ModelDownload.assetName(spec(true)));
    }
}
