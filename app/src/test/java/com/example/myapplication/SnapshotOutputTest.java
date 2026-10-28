package com.example.myapplication;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class SnapshotOutputTest {
    @Test public void byteLimitIncludesSingleByteWritesAndRejectedWritesDoNotLeakData()throws Exception {
        ByteArrayOutputStream destination=new ByteArrayOutputStream();
        SessionSnapshots.LimitedOutput output=new SessionSnapshots.LimitedOutput(destination,4);
        output.write(1);output.write(new byte[]{8,2,3,9},1,2);output.write(4);
        assertThrows(IOException.class,()->output.write(5));assertThrows(IOException.class,()->output.write(new byte[]{6},0,1));
        assertArrayEquals(new byte[]{1,2,3,4},destination.toByteArray());
    }

    @Test public void oversizedChunkIsRejectedAsAWhole()throws Exception {
        ByteArrayOutputStream destination=new ByteArrayOutputStream();
        SessionSnapshots.LimitedOutput output=new SessionSnapshots.LimitedOutput(destination,3);
        output.write(new byte[]{1,2},0,2);assertThrows(IOException.class,()->output.write(new byte[]{3,4},0,2));
        output.write(3);assertArrayEquals(new byte[]{1,2,3},destination.toByteArray());
    }

    @Test public void encoderCloseLeavesTheAtomicFileDescriptorOpenForCommit()throws Exception {
        File file=File.createTempFile("snapshot-ownership-", ".bin");
        try(FileOutputStream target=new FileOutputStream(file)){
            SessionSnapshots.LimitedOutput output=new SessionSnapshots.LimitedOutput(target,8);
            output.write(1);output.close();target.write(2);target.getFD().sync();
            assertArrayEquals(new byte[]{1,2},Files.readAllBytes(file.toPath()));
        }finally{file.delete();}
    }
}
