package com.example.myapplication;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** App-owned memory and page assembly regression checks, without model accuracy assertions. */
public class BookProcessingMemoryTest {
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final Context context=inst.getTargetContext();

    @Test public void bandedRemapRetainsEveryRowAndLandscapeOrientation()throws Exception {
        Field field=CurvedPaper.class.getDeclaredField("grids");field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String,float[][][]> grids=(Map<String,float[][][]>)field.get(null);
        float[][][] identity={{{-1,1},{-1,1}},{{-1,-1},{1,1}}};
        for(int[] size:new int[][]{{257,259},{529,257}}){
            String key="band-identity-"+size[0];Bitmap source=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888),result=null;
            int[] row=new int[size[0]];
            for(int y=0;y<size[1];y++){for(int x=0;x<size[0];x++)row[x]=Color.rgb(x%256,y%256,(x+y)%256);source.setPixels(row,0,row.length,0,y,row.length,1);}
            try{
                synchronized(CurvedPaper.class){grids.put(key,identity);result=CurvedPaper.flatten(context,source,key);grids.remove(key);}
                assertFalse(source.isRecycled());assertEquals(source.getWidth(),result.getWidth());assertEquals(source.getHeight(),result.getHeight());
                assertTrue("Identity remapping must retain band boundaries and the partial final band",source.sameAs(result));
            }finally{synchronized(CurvedPaper.class){grids.remove(key);}source.recycle();if(result!=null)result.recycle();}
        }
    }

    @Test public void bookProSavesSeveralCameraSizedPagesIntoOneReadablePdf()throws Exception {
        File directory=new File(context.getFilesDir(),"documents");assertTrue(directory.isDirectory()||directory.mkdirs());
        Set<String> before=new HashSet<>(Arrays.asList(Objects.requireNonNull(directory.list())));
        ArrayList<String> pages=new ArrayList<>();Activity editor=null;
        SharedPreferences languages=context.getSharedPreferences("local-languages",0);
        Set<String> selected=new HashSet<>(languages.getStringSet("ocr-enabled",Collections.emptySet()));
        // Exercise enhancement, curved-paper correction and OCR together at export resolution.
        assertTrue(languages.edit().putStringSet("ocr-enabled",new HashSet<>(Collections.singletonList("en"))).commit());
        assertFalse("English OCR fixture must be installed",com.paperflow.scanengine.LocalOcr.activeLanguages(context).isEmpty());
        try{
            for(int i=0;i<4;i++){
                File input=File.createTempFile("book-memory-",".jpg",context.getCacheDir());pages.add(android.net.Uri.fromFile(input).toString());
                Bitmap image=Bitmap.createBitmap(2000,2800,Bitmap.Config.ARGB_8888);
                try{Canvas canvas=new Canvas(image);canvas.drawColor(Color.WHITE);Paint paint=new Paint();paint.setColor(Color.BLACK);paint.setTextSize(55);for(int y=180;y<2600;y+=100)canvas.drawText("Book page "+(i+1)+" - line "+y,140,y,paint);try(OutputStream out=new FileOutputStream(input)){assertTrue(image.compress(Bitmap.CompressFormat.JPEG,94,out));}}finally{image.recycle();}
            }
            editor=inst.startActivitySync(new Intent(context,ScanEditorActivity.class).putStringArrayListExtra("pages",pages).putExtra("capture_mode","Book").putExtra("book_review",true).putExtra("start_crop",false).putExtra("initial_filter",1).putExtra("name","Book memory regression").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Field loading=ScanEditorActivity.class.getDeclaredField("loadingPage");loading.setAccessible(true);
            Field configs=ScanEditorActivity.class.getDeclaredField("pageConfigs");configs.setAccessible(true);
            Activity active=editor;
            UiTestWait.until(()->{try{return !loading.getBoolean(active);}catch(IllegalAccessException error){throw new AssertionError(error);}},"Book preview did not finish");
            inst.runOnMainSync(()->{try{
                @SuppressWarnings("unchecked") List<org.json.JSONObject> settings=(List<org.json.JSONObject>)configs.get(active);
                for(org.json.JSONObject setting:settings){setting.put("dewarp",1);setting.put("paper",0);setting.put("fingers",false);setting.put("demoire",false);}
                Method save=ScanEditorActivity.class.getDeclaredMethod("save");save.setAccessible(true);save.invoke(active);
            }catch(Exception error){throw new AssertionError(error);}});
            long end=SystemClock.elapsedRealtime()+120000;while(!editor.isFinishing()&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);
            assertTrue("Book Pro export did not finish",editor.isFinishing());
            File[] generated=directory.listFiles(file->!before.contains(file.getName())&&file.getName().endsWith(".pdf"));assertNotNull(generated);assertEquals(1,generated.length);
            try(PDDocument pdf=PDDocument.load(generated[0])){assertEquals(4,pdf.getNumberOfPages());for(int i=0;i<4;i++)assertTrue(pdf.getPage(i).getResources().getXObjectNames().iterator().hasNext());}
        }finally{
            if(editor!=null){Activity active=editor;inst.runOnMainSync(active::finish);}
            for(String page:pages)new File(android.net.Uri.parse(page).getPath()).delete();
            File[] generated=directory.listFiles(file->!before.contains(file.getName()));if(generated!=null)for(File file:generated){if(file.getName().endsWith(".pdf"))ScanArchive.remove(context,file.getAbsolutePath());file.delete();}
            languages.edit().putStringSet("ocr-enabled",selected).commit();CurvedPaper.release();
        }
    }
}
