package com.example.myapplication;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class TemporaryInputsTest {
    @Rule public TemporaryFolder root=new TemporaryFolder();
    @Test public void deletesOwnedCapturesButPreservesDraftsDocumentsAndExternalFiles()throws Exception{
        File files=root.newFolder("files"),captures=new File(files,"captures"),docs=new File(files,"documents");assertTrue(captures.mkdir());assertTrue(docs.mkdir());
        File consumed=new File(captures,"one.jpg"),draft=new File(captures,"draft.jpg"),document=new File(docs,"saved.pdf"),external=root.newFile("provider.jpg");
        assertTrue(consumed.createNewFile());assertTrue(draft.createNewFile());assertTrue(document.createNewFile());
        TemporaryInputs.remove(files,Arrays.asList(consumed,draft,document,external),Collections.singletonList(draft.getPath()));
        assertFalse(consumed.exists());assertTrue(draft.exists());assertTrue(document.exists());assertTrue(external.exists());
    }
    @Test public void traversalAndNestedPathsDoNotEstablishOwnership()throws Exception{
        File files=root.newFolder("files");
        for(String path:new String[]{"captures/../documents/a.pdf","captures/nested/a.jpg"}){
            try{TemporaryInputs.owned(files,new File(files,path).getPath());fail(path);}catch(IOException expected){}
        }
    }
}
