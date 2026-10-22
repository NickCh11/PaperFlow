package com.example.myapplication;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;

/** Downloads immutable model bytes. Every source must satisfy the same pinned digest. */
final class ModelDownload {
    interface Progress { void update(long bytes, long total); }
    static final class Source {
        final String url; final boolean gzip;
        Source(String url, boolean gzip) { this.url=url; this.gzip=gzip; }
    }
    static final class Spec {
        final long size; final String digest; final boolean gitBlob;
        Spec(long size,String digest,boolean gitBlob) {
            if(size<=0||digest==null||!digest.matches(gitBlob?"[0-9a-f]{40}":"[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid model fingerprint");
            this.size=size;this.digest=digest;this.gitBlob=gitBlob;
        }
    }
    static String assetName(Spec spec){return (spec.gitBlob?"blobsha1-":"sha256-")+spec.digest+(spec.gitBlob?".traineddata":".bin");}
    enum Reason { HTTP, SIZE, INTEGRITY }
    static final class Failure extends IOException {
        final Reason reason; final int status;
        Failure(Reason reason,int status) { super("Model download "+reason+(status==0?"":" "+status));this.reason=reason;this.status=status; }
    }
    static final class Cancelled extends InterruptedIOException {}
    private static void checkCancelled(BooleanSupplier cancel)throws Cancelled {
        if(cancel.getAsBoolean()||Thread.currentThread().isInterrupted())throw new Cancelled();
    }
    static void download(List<Source> sources,File target,Spec spec,BooleanSupplier cancel,Progress progress)throws IOException {
        checkCancelled(cancel);
        File temporary=new File(target.getParentFile(),target.getName()+".part");
        IOException last=null;Failure integrityFailure=null;
        try {
            for(Source source:sources)for(int attempt=0;attempt<2;attempt++) {
                checkCancelled(cancel);
                try { transfer(source,temporary,spec,cancel,progress);checkCancelled(cancel);
                    try { Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                    catch(AtomicMoveNotSupportedException unsupported) { Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING); }
                    progress.update(spec.size,spec.size);return;
                } catch(Cancelled cancelled) { throw cancelled; }
                catch(IOException problem) {
                    last=problem;
                    if(problem instanceof Failure) {
                        Failure failure=(Failure)problem;
                        if(failure.reason==Reason.INTEGRITY||failure.reason==Reason.SIZE)integrityFailure=failure;
                        if(failure.reason!=Reason.HTTP||(failure.status<500&&failure.status!=429&&failure.status!=408))break;
                    }
                    if(attempt==0)for(int slice=0;slice<10;slice++) {
                        checkCancelled(cancel);
                        try { Thread.sleep(50); }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new Cancelled();}
                    }
                } finally { Files.deleteIfExists(temporary.toPath()); }
            }
            if(integrityFailure!=null)throw integrityFailure;
            if(last!=null)throw last;
            throw new IOException("No model download sources configured");
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }
    private static HttpURLConnection connect(String address)throws IOException {
        URL url=new URL(address);boolean secure="https".equals(url.getProtocol());
        for(int redirects=0;redirects<=5;redirects++) {
            if(!"https".equals(url.getProtocol())&&!"http".equals(url.getProtocol()))throw new IOException("Invalid model download protocol");
            if(secure&&!"https".equals(url.getProtocol()))throw new IOException("Insecure model download redirect");
            HttpURLConnection connection=(HttpURLConnection)url.openConnection();
            connection.setConnectTimeout(20000);connection.setReadTimeout(15000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent","PaperFlow-Models/1");
            int status;
            try { status=connection.getResponseCode(); } catch(IOException failure){connection.disconnect();throw failure;}
            if(status==301||status==302||status==303||status==307||status==308) {
                String location=connection.getHeaderField("Location");connection.disconnect();
                if(location==null)throw new IOException("Missing model redirect destination");
                url=new URL(url,location);continue;
            }
            if(status!=200){connection.disconnect();throw new Failure(Reason.HTTP,status);}
            return connection;
        }
        throw new IOException("Too many model download redirects");
    }
    private static void transfer(Source source,File temporary,Spec spec,BooleanSupplier cancel,Progress progress)throws IOException {
        HttpURLConnection connection=connect(source.url);
        try {
            checkCancelled(cancel);
            MessageDigest digest;
            try { digest=MessageDigest.getInstance(spec.gitBlob?"SHA-1":"SHA-256"); }
            catch(NoSuchAlgorithmException missing){throw new IOException(missing);}
            if(spec.gitBlob)digest.update(("blob "+spec.size+"\0").getBytes(StandardCharsets.UTF_8));
            long total=0,lastProgress=System.nanoTime()-150000001L;
            try(InputStream network=connection.getInputStream();InputStream input=source.gzip?new GZIPInputStream(network):network;FileOutputStream output=new FileOutputStream(temporary)) {
                byte[] bytes=new byte[65536];int count;
                while((count=input.read(bytes))!=-1) {
                    checkCancelled(cancel);total+=count;
                    if(total>spec.size)throw new Failure(Reason.SIZE,0);
                    digest.update(bytes,0,count);output.write(bytes,0,count);
                    long now=System.nanoTime();if(now-lastProgress>150000000){progress.update(total,spec.size);lastProgress=now;}
                }
                output.getFD().sync();
            }
            StringBuilder actual=new StringBuilder();for(byte value:digest.digest())actual.append(String.format(Locale.ROOT,"%02x",value&255));
            if(total!=spec.size||!spec.digest.contentEquals(actual))throw new Failure(Reason.INTEGRITY,0);
        } finally { connection.disconnect(); }
    }
}
