package com.example.myapplication;

import android.content.*;
import android.graphics.*;
import android.os.StrictMode;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.paperflow.translation.OfflineRuntime;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real shared PaddleOCR/MNN recognition: no mocked OCR result and no external OCR service. */
public class OfflineWordRecognitionTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Bitmap image(){Bitmap image=Bitmap.createBitmap(1200,800,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(Color.WHITE);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));paint.setTextSize(68);canvas.drawText("PAPERFLOW OFFLINE OCR",65,240,paint);canvas.drawText("LOCAL DOCUMENT 12345",65,380,paint);return image;}
    private File scannedPdf()throws Exception{
        PDFBoxResourceLoader.init(context);File file=File.createTempFile("offline-word-scan-",".pdf",context.getCacheDir());Bitmap image=image();
        try(PDDocument doc=new PDDocument()){PDPage page=new PDPage(new PDRectangle(600,400));doc.addPage(page);try(PDPageContentStream stream=new PDPageContentStream(doc,page)){stream.drawImage(LosslessFactory.createFromImage(doc,image),0,0,600,400);}assertTrue(new PDFTextStripper().getText(doc).trim().isEmpty());doc.save(file);}finally{image.recycle();}return file;
    }
    private String wordText(File file)throws Exception{
        try(ZipFile zip=new ZipFile(file);InputStream in=zip.getInputStream(zip.getEntry("word/document.xml"));ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] bytes=new byte[8192];int count;while((count=in.read(bytes))!=-1)out.write(bytes,0,count);return out.toString("UTF-8");
        }
    }

    @Test public void scannedPdfUsesTheEmbeddedTranslatorAcrossAllOfflineWordRoutes()throws Exception{
        OfflineRuntime runtime=OfflineRuntime.get(context);SharedPreferences preferences=context.getSharedPreferences("local-languages",0);Set<String> before=new HashSet<>(preferences.getStringSet("ocr-enabled",Collections.emptySet()));
        File source=scannedPdf();StrictMode.ThreadPolicy policy=StrictMode.getThreadPolicy();preferences.edit().putStringSet("ocr-enabled",Collections.singleton("en")).commit();
        try{
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder(policy).detectNetwork().penaltyDeathOnNetwork().build());
            Bitmap image=image();try{String text=runtime.recognize(image,"en",50f).getString("text").toUpperCase(Locale.ROOT);assertTrue(text,text.contains("PAPERFLOW"));assertTrue(text,text.contains("12345"));}finally{image.recycle();}
            for(int route:new int[]{WordConversion.LOCAL,WordConversion.AUTO,WordConversion.LAYOUT,WordConversion.APPEARANCE}){
                WordConversion.Result result=WordConversion.convert(context,source.getPath(),"",route,new WordConversion.Job(),(d,t,m)->{});
                try{assertEquals(1,result.pages);assertEquals(0,result.nativePages);if(route==WordConversion.LOCAL||route==WordConversion.AUTO)assertEquals(1,result.ocrPages);
                    String xml=wordText(result.file).toUpperCase(Locale.ROOT);if(route!=WordConversion.APPEARANCE){assertTrue(xml,xml.contains("PAPERFLOW"));assertTrue(xml,xml.contains("OFFLINE"));assertTrue(xml,xml.contains("12345"));}
                    if(route==WordConversion.LAYOUT||route==WordConversion.APPEARANCE)try(ZipFile zip=new ZipFile(result.file);InputStream page=zip.getInputStream(zip.getEntry("word/media/page1.jpg"))){Bitmap exported=BitmapFactory.decodeStream(page);try{assertNotNull(exported);assertTrue(exported.getWidth()>100);assertTrue(exported.getHeight()>100);}finally{if(exported!=null)exported.recycle();}}
                }
                finally{result.file.delete();}
            }
        }finally{StrictMode.setThreadPolicy(policy);preferences.edit().putStringSet("ocr-enabled",before).commit();source.delete();}
    }

    @Test public void missingEnabledLanguagesAndTheRemovedCloudRouteCannotStartRecognition()throws Exception{
        OfflineRuntime.get(context);SharedPreferences preferences=context.getSharedPreferences("local-languages",0);Set<String> before=new HashSet<>(preferences.getStringSet("ocr-enabled",Collections.emptySet()));File source=scannedPdf();
        try{preferences.edit().putStringSet("ocr-enabled",Collections.emptySet()).commit();
            IllegalStateException missing=assertThrows(IllegalStateException.class,()->WordConversion.convert(context,source.getPath(),"",WordConversion.LOCAL,new WordConversion.Job(),(d,t,m)->{}));assertEquals(UiText.get(R.string.ui_download_and_enable_ocr_languages_in_ocr_languages_first),missing.getMessage());
            assertThrows(IllegalArgumentException.class,()->WordConversion.convert(context,source.getPath(),"",3,new WordConversion.Job(),(d,t,m)->{}));assertTrue(source.isFile());
        }finally{preferences.edit().putStringSet("ocr-enabled",before).commit();source.delete();}
    }

    @Test public void largePhotographsKeepSmallTextAndOriginalWordCoordinates() throws Exception {
        OfflineRuntime runtime = OfflineRuntime.get(context);
        Bitmap photograph = Bitmap.createBitmap(2400, 3200, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(photograph); canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.BLACK);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); paint.setTextSize(52);
            canvas.drawText("OFFLINE DOCUMENT 12345", 320, 500, paint);
            canvas.drawText("Small text remains readable", 320, 1000, paint);
            canvas.drawText("98765", 1800, 2800, paint);
            org.json.JSONObject result = runtime.recognize(photograph, "en", 50f);
            String text = result.getString("text").toUpperCase(Locale.ROOT);
            for (String expected : new String[]{"OFFLINE", "12345", "SMALL", "98765"}) assertTrue(text, text.contains(expected));
            boolean found = false; org.json.JSONArray words = result.getJSONArray("words");
            for (int i = 0; i < words.length(); i++) {
                org.json.JSONObject word = words.getJSONObject(i);
                if (word.getString("text").contains("98765")) {
                    assertTrue(word.toString(), word.getDouble("left") > 1500);
                    assertTrue(word.toString(), word.getDouble("top") > 2400);
                    assertTrue(word.getDouble("right") <= photograph.getWidth());
                    assertTrue(word.getDouble("bottom") <= photograph.getHeight()); found = true;
                }
            }
            assertTrue("OCR lost the word at the original photograph coordinates", found);
            assertEquals(2400, photograph.getWidth()); assertEquals(3200, photograph.getHeight());
            assertEquals(Color.WHITE, photograph.getPixel(0, 0));
        } finally { photograph.recycle(); }
    }

    private RadioGroup group(View view){if(view instanceof RadioGroup)return (RadioGroup)view;if(view instanceof ViewGroup){ViewGroup parent=(ViewGroup)view;for(int i=0;i<parent.getChildCount();i++){RadioGroup found=group(parent.getChildAt(i));if(found!=null)return found;}}return null;}
    @Test public void wordRouteChooserContainsOnlyLocalOptionsWithStableRouteIdentifiers()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(new Intent(context,MainActivity.class))){
            scenario.onActivity(a->{try{java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod("beginWordConversion",String.class,String.class);method.setAccessible(true);method.invoke(a,"/fixture/scan.pdf","Scan");}catch(Exception error){throw new AssertionError(error);}
                RadioGroup choices=null;for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews()){RadioGroup found=group(root);if(found!=null)choices=found;}assertNotNull(choices);assertEquals(5,choices.getChildCount());Set<Integer> ids=new HashSet<>();
                for(int i=0;i<choices.getChildCount();i++){RadioButton button=(RadioButton)choices.getChildAt(i);ids.add((Integer)button.getTag());String label=button.getText().toString().toLowerCase(Locale.ROOT);assertFalse(label.contains("google"));assertFalse(label.contains("tesseract"));}
                assertEquals(new HashSet<>(Arrays.asList(0,1,2,4,5)),ids);assertEquals(WordConversion.LAYOUT,((View)choices.findViewById(choices.getCheckedRadioButtonId())).getTag());
            });
        }
    }
}
