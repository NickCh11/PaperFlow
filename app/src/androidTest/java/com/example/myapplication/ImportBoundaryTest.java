package com.example.myapplication;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

public class ImportBoundaryTest {
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}

    @Test public void viewKeepsMimeAndDropsPrivilegedExtras(){
        Uri uri=Uri.parse("content://external.provider/document/report");
        Intent incoming=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/pdf")
                .putExtra("pdf_password","forged").addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        Intent target=DocumentEntryActivity.sanitize(context(),incoming);
        assertEquals(Intent.ACTION_VIEW,target.getAction());assertEquals(uri,target.getData());
        assertEquals("application/pdf",target.getType());assertFalse(target.hasExtra("pdf_password"));
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,target.getFlags());
    }

    @Test public void multipleImportFiltersDuplicatesAndInvalidParcelablesAndCapsGrants(){
        ArrayList<Parcelable> streams=new ArrayList<>();
        streams.add(null);streams.add(new Bundle());streams.add(Uri.parse("file:///private.pdf"));
        streams.add(Uri.parse("content://"+context().getPackageName()+".files/private.pdf"));
        for(int i=0;i<150;i++){Uri uri=Uri.parse("content://external.provider/document/"+i);streams.add(uri);streams.add(uri);}
        Intent target=DocumentEntryActivity.sanitize(context(),new Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM,streams));
        ArrayList<Uri> accepted=target.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        assertEquals(100,accepted.size());assertEquals(100,target.getClipData().getItemCount());
        assertEquals(100,new java.util.HashSet<>(accepted).size());
        assertEquals(Uri.parse("content://external.provider/document/99"),accepted.get(99));
    }

    @Test public void emptyUnknownAndMissingStreamsFallBackToLauncher(){
        Intent[] inputs={new Intent("forged.command"),new Intent(Intent.ACTION_VIEW),
                new Intent(Intent.ACTION_SEND),new Intent(Intent.ACTION_SEND_MULTIPLE),
                new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM,new ArrayList<Uri>())};
        for(Intent input:inputs){Intent target=DocumentEntryActivity.sanitize(context(),input);
            assertEquals(Intent.ACTION_MAIN,target.getAction());assertNull(target.getClipData());assertEquals(0,target.getFlags());}
    }
}
