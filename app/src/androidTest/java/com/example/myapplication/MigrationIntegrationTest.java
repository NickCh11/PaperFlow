package com.example.myapplication;

import android.content.Context;
import android.graphics.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.paperflow.scanengine.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Runs real production engines on the device, with pinned downloaded languages. */
@RunWith(AndroidJUnit4.class)
public class MigrationIntegrationTest {
    private Context context(){Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();PDFBoxResourceLoader.init(c);return c;}
    private JSONObject language(String kind,String code)throws Exception{JSONArray list=LanguageModels.catalog(context(),kind);for(int i=0;i<list.length();i++)if(list.getJSONObject(i).getString("code").equals(code))return list.getJSONObject(i);throw new IOException(code);}
    private void install(String kind,String code)throws Exception{JSONObject entry=language(kind,code);if(!LanguageModels.installed(context(),kind,entry))LanguageModels.download(context(),kind,entry,()->false,(a,b)->{});}
    private Bitmap textImage(){Bitmap b=Bitmap.createBitmap(1200,700,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(b);canvas.drawColor(Color.WHITE);Paint p=new Paint(3);p.setColor(Color.BLACK);p.setTextSize(54);canvas.drawText("PaperFlow offline document",70,140,p);canvas.drawText("Hello world",70,240,p);canvas.drawText("This is a searchable PDF.",70,340,p);canvas.drawText("Καλημέρα κόσμε",70,450,p);return b;}
    @Test public void ocrSearchablePdfAndExistingPdfPreserveRotation()throws Exception{
        install("ocr","eng");install("ocr","ell");Context c=context();Set<String> previous=new HashSet<>(c.getSharedPreferences("local-languages",0).getStringSet("ocr-enabled",Collections.emptySet()));
        c.getSharedPreferences("local-languages",0).edit().putStringSet("ocr-enabled",new HashSet<>(Arrays.asList("eng","ell"))).commit();Bitmap b=textImage();
        try{
            String recognized=LocalOcr.text(c,b);assertTrue(recognized,recognized.contains("Hello"));assertTrue(recognized,recognized.contains("Καλημέρα"));
            try(PDDocument doc=new PDDocument()){SearchablePdf.append(c,doc,b,600,350);String text=new PDFTextStripper().getText(doc);assertTrue(text,text.contains("Hello"));assertTrue(text,text.contains("Καλημέρα"));doc.save(new File(c.getFilesDir(),"migration-searchable.pdf"));}
            c.getSharedPreferences("local-languages",0).edit().putStringSet("ocr-enabled",Collections.emptySet()).commit();
            try(PDDocument doc=new PDDocument()){SearchablePdf.append(c,doc,b,600,350);assertTrue(new PDFTextStripper().getText(doc).trim().isEmpty());doc.getPage(0).setRotation(90);c.getSharedPreferences("local-languages",0).edit().putStringSet("ocr-enabled",new HashSet<>(Arrays.asList("eng","ell"))).commit();doc.getPage(0).setRotation(0);Bitmap rendered=new com.tom_roush.pdfbox.rendering.PDFRenderer(doc).renderImage(0,4);try(FileOutputStream debug=new FileOutputStream(new File(c.getFilesDir(),"migration-ocr-render.png"))){rendered.compress(Bitmap.CompressFormat.PNG,100,debug);}String renderedText=LocalOcr.text(c,rendered);rendered.recycle();doc.getPage(0).setRotation(90);PdfOcr.apply(c,doc,Collections.singletonList(0),(a,z)->{});assertEquals(90,doc.getPage(0).getRotation());doc.save(new File(c.getFilesDir(),"migration-existing-rotated.pdf"));PDFTextStripper sortedStripper=new PDFTextStripper();sortedStripper.setSortByPosition(true);String extracted=sortedStripper.getText(doc);assertTrue("Rendered OCR: "+renderedText+" / PDF: "+extracted,extracted.contains("Hello"));int size=doc.getPage(0).getCOSObject().toString().length();PdfOcr.apply(c,doc,Collections.singletonList(0),(a,z)->{});assertEquals(size,doc.getPage(0).getCOSObject().toString().length());doc.save(new File(c.getFilesDir(),"migration-existing-rotated.pdf"));}
        }finally{b.recycle();c.getSharedPreferences("local-languages",0).edit().putStringSet("ocr-enabled",previous).commit();}
    }
    @Test public void nativeGreekEnglishBothDirectionsAndOverlay()throws Exception{
        install("translation","el-en");install("translation","en-el");JSONObject result=new JSONObject();
        try(OfflineTranslator translator=new OfflineTranslator(context(),"el-en")){String text=translator.translate("Καλημέρα κόσμε. Αυτό είναι ένα βιβλίο.");assertFalse(text,text.contains("Αυτό"));assertTrue(text,text.toLowerCase(Locale.ROOT).contains("book"));result.put("el-en",text);}
        try(OfflineTranslator translator=new OfflineTranslator(context(),"en-el")){String text=translator.translate("Hello world. This is a book.");assertTrue(text,text.matches("(?s).*[Α-Ωα-ω].*"));result.put("en-el",text);}
        Bitmap b=textImage();com.paperflow.imageprocessing.OcrTextBox word=new com.paperflow.imageprocessing.OcrTextBox("Hello world",new com.paperflow.imageprocessing.ImageRect(70,190,540,250),60,250);ArrayList<TranslationRenderer.Block> blocks=TranslationRenderer.blocks(Collections.singletonList(word));blocks.get(0).translation="Καλημέρα κόσμε";TranslationRenderer.Result translated=TranslationRenderer.render(b,blocks);assertFalse(b.sameAs(translated.bitmap));assertEquals(b.getPixel(10,10),translated.bitmap.getPixel(10,10));try(FileOutputStream out=new FileOutputStream(new File(context().getFilesDir(),"migration-translation.png"))){translated.bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}translated.bitmap.recycle();b.recycle();
        try(FileOutputStream out=new FileOutputStream(new File(context().getFilesDir(),"migration-native-translation.json"))){out.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
    @Test public void unmarkPreservesEveryUnselectedPixelAndPortraitRuns(){
        Bitmap source=Bitmap.createBitmap(1600,1100,Bitmap.Config.ARGB_8888);source.eraseColor(Color.WHITE);Canvas canvas=new Canvas(source);Paint p=new Paint();p.setColor(Color.BLACK);canvas.drawRect(650,420,850,500,p);Bitmap mask=Bitmap.createBitmap(1600,1100,Bitmap.Config.ARGB_8888);p.setColor(Color.WHITE);new Canvas(mask).drawRect(630,400,870,520,p);Bitmap result=Inpainter.inpaint(source,mask);assertEquals(Color.WHITE,result.getPixel(750,460));int[] before=new int[1600*1100],after=before.clone(),selection=before.clone();source.getPixels(before,0,1600,0,0,1600,1100);result.getPixels(after,0,1600,0,0,1600,1100);mask.getPixels(selection,0,1600,0,0,1600,1100);for(int i=0;i<before.length;i++)if((selection[i]>>>24)<=32)assertEquals("Unselected pixel "+i,before[i],after[i]);
        PortraitSegmenter.Mask portrait=PortraitSegmenter.mask(context(),source);assertEquals(portrait.width*portrait.height,portrait.values.length);for(float value:portrait.values)assertTrue(Float.isFinite(value)&&value>=0&&value<=1);source.recycle();mask.recycle();result.recycle();
    }
    @Test public void allAuthorizedPhotosRunScanEngineAndBothFilters()throws Exception{
        Context c=context();JSONArray rows=new JSONArray();File report=new File(c.getExternalFilesDir(null),"scanengine-migration-audit");report.mkdirs();int count=0,detected=0;
        for(String folder:new String[]{"user-boundary-photos","user-boundary-test2","user-boundary-test3"}){
            File root=TestFixtures.directory(c,folder);File[] files=root.listFiles((d,n)->n.toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg|png)$"));assertNotNull("Missing private fixtures "+folder,files);Arrays.sort(files);
            for(File file:files){BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),options);options.inJustDecodeBounds=false;options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>1800)options.inSampleSize*=2;Bitmap source=BitmapFactory.decodeFile(file.getPath(),options);source=ImageOrientation.upright(source,new androidx.exifinterface.media.ExifInterface(file.getPath()));int sample=source.getPixel(0,0);long start=android.os.SystemClock.elapsedRealtime();ScanEngine.Analysis analysis=ScanEngine.analyze(source,false);float[] corners=analysis.corners;count++;if(corners!=null){detected++;assertTrue(file.getName(),PageBoundaryDetector.valid(corners));}
                Bitmap marked=source.copy(Bitmap.Config.ARGB_8888,true);Canvas canvas=new Canvas(marked);Paint p=new Paint(3);p.setStyle(Paint.Style.STROKE);p.setColor(Color.GREEN);p.setStrokeWidth(5);if(corners!=null){Path path=new Path();for(int k=0;k<4;k++){float x=corners[2*k]*source.getWidth(),y=corners[2*k+1]*source.getHeight();if(k==0)path.moveTo(x,y);else path.lineTo(x,y);}path.close();canvas.drawPath(path,p);}Bitmap cropped=corners==null?source.copy(Bitmap.Config.ARGB_8888,false):ScanEngine.crop(source,corners);int color=ScanEngine.colorMode(cropped);Bitmap enhanced=ScanEngine.enhance(cropped,color);assertFalse(source.isRecycled());assertEquals(sample,source.getPixel(0,0));assertEquals(cropped.getWidth(),enhanced.getWidth());assertEquals(cropped.getHeight(),enhanced.getHeight());
                String prefix=folder+"-"+file.getName();for(int variant=0;variant<3;variant++){Bitmap b=variant==0?marked:variant==1?cropped:enhanced;try(FileOutputStream out=new FileOutputStream(new File(report,prefix+"-"+variant+".jpg"))){b.compress(Bitmap.CompressFormat.JPEG,86,out);}}rows.put(new JSONObject().put("folder",folder).put("image",file.getName()).put("detected",corners!=null).put("corners",corners==null?JSONObject.NULL:new JSONArray(corners)).put("colorMode",color).put("ms",android.os.SystemClock.elapsedRealtime()-start));source.recycle();marked.recycle();cropped.recycle();enhanced.recycle();
            }
        }
        try(FileOutputStream out=new FileOutputStream(new File(report,"report.json"))){out.write(new JSONObject().put("fixtureSource",TestFixtures.ci()?"generated-ci":"private-local").put("photos",count).put("detected",detected).put("results",rows).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}assertEquals("All fixture sets",TestFixtures.ci()?9:58,count);
    }
    @Test public void zxingQrAndRotatedBarcode()throws Exception {
        java.lang.reflect.Method decode=ScanCodeActivity.ScanSession.class.getDeclaredMethod("decodeCodes",Bitmap.class);decode.setAccessible(true);
        for(com.google.zxing.BarcodeFormat format:new com.google.zxing.BarcodeFormat[]{com.google.zxing.BarcodeFormat.QR_CODE,com.google.zxing.BarcodeFormat.CODE_128}){
            String value=format==com.google.zxing.BarcodeFormat.QR_CODE?"https://example.com/scan":"PAPERFLOW123";com.google.zxing.common.BitMatrix bits=new com.google.zxing.MultiFormatWriter().encode(value,format,500,300);Bitmap image=Bitmap.createBitmap(500,300,Bitmap.Config.ARGB_8888);int[] pixels=new int[500*300];for(int y=0;y<300;y++)for(int x=0;x<500;x++)pixels[y*500+x]=bits.get(x,y)?Color.BLACK:Color.WHITE;image.setPixels(pixels,0,500,0,0,500,300);Matrix matrix=new Matrix();matrix.postRotate(90);Bitmap rotated=Bitmap.createBitmap(image,0,0,500,300,matrix,true);List<?> result=(List<?>)decode.invoke(null,rotated);assertFalse("Rotated "+format,result.isEmpty());java.lang.reflect.Field field=result.get(0).getClass().getDeclaredField("value");field.setAccessible(true);assertEquals(value,field.get(result.get(0)));rotated.recycle();image.recycle();
        }
    }

    @Test public void bookKeepsOffCenterCreaseAndSinglePageFallback()throws Exception {
        Bitmap book=Bitmap.createBitmap(900,600,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(book);canvas.drawColor(0xff50453a);Paint paint=new Paint(3);paint.setColor(0xfff1eee5);canvas.drawRect(45,40,855,560,paint);paint.setColor(0xff504030);canvas.drawRect(376,40,386,560,paint);paint.setColor(Color.DKGRAY);paint.setStrokeWidth(2);for(int y=110;y<500;y+=35){canvas.drawLine(95,y,320,y,paint);canvas.drawLine(450,y,800,y,paint);}
        try{List<float[]> split=LearnedPageBounds.book(book,true);assertEquals("Off-center crease must retain two pages",2,split.size());assertEquals(1,LearnedPageBounds.book(book,false).size());for(float[] quad:split)assertTrue(PageBoundaryDetector.valid(quad));float leftMax=Math.max(split.get(0)[2],split.get(0)[4]),rightMin=Math.min(split.get(1)[0],split.get(1)[6]);assertEquals(381f/900,leftMax,.04);assertEquals(381f/900,rightMin,.04);}finally{book.recycle();}
        Bitmap blank=Bitmap.createBitmap(480,320,Bitmap.Config.ARGB_8888);blank.eraseColor(Color.WHITE);try{assertEquals(1,LearnedPageBounds.book(blank,true).size());}finally{blank.recycle();}
    }

}
