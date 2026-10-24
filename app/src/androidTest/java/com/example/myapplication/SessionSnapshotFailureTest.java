package com.example.myapplication;

import android.content.Context;
import android.util.AtomicFile;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionSnapshotFailureTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private String read(File path)throws Exception {
        // The instrumentation thread is deliberately a processing thread, not the app main thread.
        return SessionSnapshots.read(path);
    }
    private void remove(File path)throws Exception {
        SessionSnapshots.delete(path);
        assertThrows(IOException.class,()->read(path)); // Serial IO barrier also verifies deletion.
    }

    @Test public void nestedSnapshotDirectoryIsCreatedAndDeletionIsOrdered()throws Exception {
        File directory=new File(context.getCacheDir(),"snapshot-parent-"+java.util.UUID.randomUUID());
        File child=new File(directory,"nested"), file=new File(child,"state.json");
        try {
            assertFalse(directory.exists());SessionSnapshots.write(file,()->"Unicode Ελληνικά\n日本語");
            assertEquals("Unicode Ελληνικά\n日本語",read(file));assertTrue(child.isDirectory());
            remove(file);assertFalse(file.exists());
        } finally {remove(file);child.delete();directory.delete();}
    }

    @Test public void snapshotReadOnMainThreadFailsBeforeDoingIo() {
        File file=new File(context.getCacheDir(),"main-thread-read-does-not-exist.json");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->assertThrows(IllegalStateException.class,()->SessionSnapshots.read(file)));
        assertFalse(file.exists());
    }

    @Test public void aNonDirectoryParentCannotOverwriteAnUnrelatedFile()throws Exception {
        File parent=File.createTempFile("snapshot-parent-", ".txt",context.getCacheDir());
        byte[] sentinel="existing unrelated data".getBytes(StandardCharsets.UTF_8);Files.write(parent.toPath(),sentinel);
        File child=new File(parent,"snapshot.json");
        try {
            SessionSnapshots.write(child,()->"new snapshot");assertThrows(IOException.class,()->read(child));
            assertArrayEquals(sentinel,Files.readAllBytes(parent.toPath()));assertFalse(child.exists());
        } finally {parent.delete();}
    }

    @Test public void invalidOrOversizedEncodedSnapshotsKeepTheLastGoodCopy()throws Exception {
        File file=File.createTempFile("snapshot-limit-", ".json",context.getCacheDir());
        try {
            SessionSnapshots.write(file,()->"last good state");assertEquals("last good state",read(file));
            SessionSnapshots.write(file,()->null);assertEquals("last good state",read(file));
            SessionSnapshots.write(file,()->{
                // Multibyte text reaches the byte limit without requiring a giant UTF-16 fixture.
                char[] text=new char[(32*1024*1024)/3+1];java.util.Arrays.fill(text,'\u20ac');return new String(text);
            });
            assertEquals("last good state",read(file));
            assertEquals("last good state",new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        } finally {remove(file);}
    }

    @Test public void interruptedAtomicWriteRestoresThePreviousCommittedSnapshot()throws Exception {
        File file=File.createTempFile("snapshot-interrupted-", ".json",context.getCacheDir());
        try {
            SessionSnapshots.write(file,()->"committed state");assertEquals("committed state",read(file));
            AtomicFile atomic=new AtomicFile(file);
            try(FileOutputStream output=atomic.startWrite()){output.write("partial replacement".getBytes(StandardCharsets.UTF_8));}
            assertEquals("committed state",read(file));
        } finally {remove(file);}
    }

    @Test public void encoderOutOfMemoryRetainsCommittedStateAndTheIoWorkerKeepsRunning()throws Exception {
        File file=File.createTempFile("snapshot-encoder-oom-", ".json",context.getCacheDir());
        try {
            SessionSnapshots.write(file,()->"committed state");assertEquals("committed state",read(file));
            SessionSnapshots.write(file,()->{throw new OutOfMemoryError("Simulated snapshot encoder allocation failure");});
            assertEquals("committed state",read(file));
            SessionSnapshots.write(file,()->"later successful state");assertEquals("later successful state",read(file));
        } finally {remove(file);}
    }

    @Test public void oversizedDiskSnapshotIsRejectedRatherThanReturnedTruncated()throws Exception {
        File file=File.createTempFile("snapshot-disk-limit-", ".json",context.getCacheDir());
        long length=32L*1024*1024+1;
        try {
            try(RandomAccessFile output=new RandomAccessFile(file,"rw")){output.setLength(length);}
            IOException failure=assertThrows(IOException.class,()->read(file));
            assertNotNull(failure.getCause());assertTrue(failure.getCause().getMessage().contains("too large"));
            assertEquals(length,file.length());
        } finally {remove(file);}
    }

    @Test public void streamingUtf8PreservesSurrogatePairsAcrossChunksAndFinishesTheEncoder()throws Exception {
        File file=File.createTempFile("snapshot-unicode-", ".json",context.getCacheDir());
        StringBuilder text=new StringBuilder();for(int i=0;i<8191;i++)text.append('a');
        text.append("\ud83d\ude00 Ελληνικά 日本語\ud800");String value=text.toString();
        try {
            SessionSnapshots.write(file,()->value);
            String expected=new String(value.getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8);
            assertEquals(expected,read(file));assertArrayEquals(value.getBytes(StandardCharsets.UTF_8),Files.readAllBytes(file.toPath()));
        }finally{remove(file);}
    }

    @Test public void exactlyTheByteLimitCanBeWrittenAndReadWithoutTruncation()throws Exception {
        File file=File.createTempFile("snapshot-exact-limit-", ".json",context.getCacheDir());
        int limit=32*1024*1024;
        try {
            SessionSnapshots.write(file,()->{char[] value=new char[limit];java.util.Arrays.fill(value,'x');value[0]='a';value[limit-1]='z';return new String(value);});
            String restored=read(file);assertEquals(limit,restored.length());assertEquals('a',restored.charAt(0));assertEquals('z',restored.charAt(limit-1));assertEquals(limit,file.length());
        }finally{remove(file);}
    }
}
