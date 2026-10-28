package com.example.myapplication;

import android.content.Context;
import android.net.Uri;
import androidx.core.content.FileProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.Assert.*;

/** Real provider access: only export folders are shareable, never private state. */
public class FileSharingBoundaryTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private String authority(){return context.getPackageName()+".files";}

    @Test public void exportedDocumentsConversionsAndTranslationFilesRemainReadable()throws Exception{
        File[] roots={new File(context.getFilesDir(),"documents"),new File(context.getFilesDir(),"conversions"),new File(context.getCacheDir(),"translation-exports")};
        byte[] payload={9,2,7,4};
        for(File root:roots){
            assertTrue(root.isDirectory()||root.mkdirs());File file=File.createTempFile("sharing-boundary-",".bin",root);
            try{
                Files.write(file.toPath(),payload);Uri uri=FileProvider.getUriForFile(context,authority(),file);
                assertEquals("content",uri.getScheme());assertEquals(authority(),uri.getAuthority());
                try(InputStream input=context.getContentResolver().openInputStream(uri)){
                    assertNotNull(input);for(byte expected:payload)assertEquals(expected&255,input.read());assertEquals(-1,input.read());
                }
                assertArrayEquals(payload,Files.readAllBytes(file.toPath()));
            }finally{assertTrue(file.delete());}
        }
    }

    @Test public void privateStateAndSiblingFoldersCannotBecomeReadableThroughSharingUris()throws Exception{
        for(File file:new File[]{
                new File(context.getFilesDir(),"private-state.json"),new File(context.getCacheDir(),"private-preview.png"),
                new File(context.getFilesDir(),"offline-language-packs/model.bin"),new File(context.getFilesDir(),"captures/photo.jpg")})
            assertThrows("Private path became shareable: "+file,IllegalArgumentException.class,()->FileProvider.getUriForFile(context,authority(),file));
        File sibling=new File(context.getFilesDir(),"documents-"+UUID.randomUUID());assertTrue(sibling.mkdir());
        File privateFile=new File(sibling,"canary.bin");byte[] original={5,1,9};
        try{
            Files.write(privateFile.toPath(),original);
            // A URI builder may match a folder-name prefix. Only the resolver
            // can establish whether that URI actually publishes the sibling.
            Uri uri=null;try{uri=FileProvider.getUriForFile(context,authority(),privateFile);}catch(IllegalArgumentException rejected){}
            if(uri!=null){Uri candidate=uri;assertThrows(java.io.FileNotFoundException.class,()->{try(InputStream ignored=context.getContentResolver().openInputStream(candidate)){fail("Provider published a private sibling file");}});}
            assertArrayEquals(original,Files.readAllBytes(privateFile.toPath()));
        }finally{assertTrue(privateFile.delete());assertTrue(sibling.delete());}
        android.content.pm.ProviderInfo provider=context.getPackageManager().resolveContentProvider(authority(),0);
        assertNotNull(provider);assertFalse(provider.exported);assertTrue(provider.grantUriPermissions);
    }

    @Test public void forgedContentUrisCannotTraverseFromDocumentsIntoPrivateFiles()throws Exception{
        File privateFile=File.createTempFile("private-sharing-canary-",".bin",context.getFilesDir());byte[] original={4,8,1};
        try{
            Files.write(privateFile.toPath(),original);
            Uri encoded=new Uri.Builder().scheme("content").authority(authority()).appendPath("documents").appendPath("../"+privateFile.getName()).build();
            Uri plain=Uri.parse("content://"+authority()+"/documents/../"+privateFile.getName());
            for(Uri uri:new Uri[]{encoded,plain})
                assertThrows(SecurityException.class,()->{try(InputStream ignored=context.getContentResolver().openInputStream(uri)){fail("Provider escaped its document root");}});
            assertArrayEquals(original,Files.readAllBytes(privateFile.toPath()));
        }finally{assertTrue(privateFile.delete());}
    }
}
