package com.example.myapplication;

import android.app.Application;
import android.graphics.*;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.camera.core.ImageCapture;
import androidx.exifinterface.media.ExifInterface;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Retains a scan across recreation; page ownership changes only on the main thread. */
public class CameraCaptureSession extends AndroidViewModel {
    final ArrayList<String> pages=new ArrayList<>();
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    final MutableLiveData<Integer> changes=new MutableLiveData<>(0);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final BookDraftStore bookDraft;
    String mode="Single",message;
    boolean initialized,busy,returned,returnRequested,grid,hd=true,bookDoublePage=true;
    volatile boolean cleared;
    int flash=ImageCapture.FLASH_MODE_OFF,timer;
    float previewRatio;boolean importOptionsChosen,importAutoCrop=true;int importFilter=1;
    Bitmap thumbnail;
    public CameraCaptureSession(Application app){super(app);bookDraft=new BookDraftStore(app);}
    File newFile(String prefix){File dir=new File(getApplication().getFilesDir(),"captures");if(!dir.isDirectory())dir.mkdirs();return new File(dir,prefix+"-"+UUID.randomUUID()+".jpg");}
    void notifyChange(){changes.setValue(changes.getValue()==null?1:changes.getValue()+1);}
    void fail(String text){main.post(()->{if(cleared)return;busy=false;message=text;notifyChange();});}
    void processCaptured(File file,String captureMode){
        processCaptured(file,captureMode,bookDoublePage);
    }
    void processCaptured(File file,String captureMode,boolean doublePage){
        try{commit(transform(file,captureMode,previewRatio,doublePage),captureMode,null);}
        catch(Exception|OutOfMemoryError error){file.delete();fail("Could not process the image. Please try again.");}
    }
    void importImages(List<Uri> uris){
        if(busy)return;busy=true;notifyChange();
        final String captureMode=mode;final boolean doublePage=bookDoublePage;final int limit="ID Cards".equals(mode)?Math.max(0,2-pages.size()):100;
        worker.execute(()->{
            ArrayList<String> result=new ArrayList<>();int failed=0;
            for(Uri uri:uris){
                if(result.size()>=limit||cleared)break;File file=newFile("import");
                try(InputStream input=getApplication().getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(file)){
                    if(input==null)throw new IOException(UiText.get(R.string.ui_unreadable_input));byte[] buffer=new byte[32768];int read;while((read=input.read(buffer))!=-1)out.write(buffer,0,read);
                }catch(Exception e){file.delete();failed++;continue;}
                try{result.addAll(transform(file,captureMode,0,doublePage));}catch(Exception|OutOfMemoryError e){file.delete();failed++;}
            }
            if(result.isEmpty())fail("No readable images were imported");
            else commit(result,captureMode,failed>0?"Some images could not be imported":null);
        });
    }
    private void commit(ArrayList<String> result,String captureMode,String notice){
        String warning=notice;
        if("Book".equals(captureMode)){
            ArrayList<String> saved=new ArrayList<>(pages);saved.addAll(result);
            try{bookDraft.save(saved,bookDoublePage);}catch(IOException error){warning="Pages are available, but the Book draft could not be saved. Review and save before closing.";}
        }
        Bitmap thumb=null;
        if(!result.isEmpty()){BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=16;thumb=BitmapFactory.decodeFile(result.get(result.size()-1),opts);}
        final Bitmap ready=thumb;final String status=warning;
        main.post(()->{
            if(cleared){if(!"Book".equals(captureMode))for(String path:result)new File(path).delete();if(ready!=null)ready.recycle();return;}
            pages.addAll(result);replaceThumbnail(ready);busy=false;message=status;
            returnRequested=!"Batch".equals(captureMode)&&!"Book".equals(captureMode)&&(!"ID Cards".equals(captureMode)||pages.size()>=2);notifyChange();
        });
    }
    void undo(){
        if(busy||pages.isEmpty())return;busy=true;notifyChange();
        final String removed=pages.get(pages.size()-1),previous=pages.size()>1?pages.get(pages.size()-2):null;
        final boolean book="Book".equals(mode),doublePage=bookDoublePage;final ArrayList<String> remaining=new ArrayList<>(pages);remaining.remove(remaining.size()-1);
        worker.execute(()->{
            if(book)try{bookDraft.save(remaining,doublePage);}catch(IOException error){fail("Could not update the Book draft. Please try again.");return;}
            new File(removed).delete();Bitmap next=null;
            if(previous!=null){BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=16;next=BitmapFactory.decodeFile(previous,opts);}
            final Bitmap ready=next;
            main.post(()->{if(cleared){if(ready!=null)ready.recycle();return;}pages.remove(pages.size()-1);replaceThumbnail(ready);busy=false;returnRequested=false;notifyChange();});
        });
    }
    private void replaceThumbnail(Bitmap next){Bitmap previous=thumbnail;thumbnail=next;if(previous!=null&&previous!=next&&!previous.isRecycled())previous.recycle();}
    /** Restore only into an empty Book camera; page count and thumbnail are published together. */
    void restoreBookDraft(){
        if(busy||!pages.isEmpty()||!"Book".equals(mode))return;busy=true;notifyChange();
        worker.execute(()->{
            try{
                BookDraftStore.Snapshot snapshot=bookDraft.read();bookDraft.save(snapshot.pages,snapshot.doublePage);
                Bitmap thumb=null;if(!snapshot.pages.isEmpty()){BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=16;thumb=BitmapFactory.decodeFile(snapshot.pages.get(snapshot.pages.size()-1),options);}
                final Bitmap ready=thumb;
                main.post(()->{if(cleared){if(ready!=null)ready.recycle();return;}pages.addAll(snapshot.pages);bookDoublePage=snapshot.doublePage;replaceThumbnail(ready);busy=false;returnRequested=false;notifyChange();});
            }catch(IOException error){fail("Could not restore the saved Book draft.");}
        });
    }
    /** Explicit user discard deletes only app-owned draft images, after queued work has finished. */
    void discardBookDraft(){
        if(busy)return;busy=true;notifyChange();final ArrayList<String> owned=new ArrayList<>(pages);
        worker.execute(()->{
            try{bookDraft.discard();for(String path:owned){File file=new File(path);File captures=new File(getApplication().getFilesDir(),"captures").getCanonicalFile();if(captures.equals(file.getCanonicalFile().getParentFile()))file.delete();}
                main.post(()->{if(cleared)return;pages.clear();replaceThumbnail(null);busy=false;returnRequested=false;notifyChange();});
            }catch(IOException error){fail("Could not discard the Book draft. Please try again.");}
        });
    }
    /** Call only after the editor has saved its PDF. The editor retains ownership of page files. */
    void completeBookDraft(){bookDraft.complete();returned=true;}
    static boolean requiresDocumentCrop(String mode){return "ID Cards".equals(mode)||"Book".equals(mode)||"Slides".equals(mode)||"Whiteboard".equals(mode)||"Timestamp".equals(mode);}
    private ArrayList<String> transform(File source,String captureMode,float viewportRatio)throws Exception{
        return transform(source,captureMode,viewportRatio,true);
    }
    private ArrayList<String> transform(File source,String captureMode,float viewportRatio,boolean doublePage)throws Exception{
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(source.getAbsolutePath(),bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException(UiText.get(R.string.ui_unreadable_image));
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
        while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>3200)options.inSampleSize*=2;
        Bitmap image=BitmapFactory.decodeFile(source.getAbsolutePath(),options);if(image==null)throw new IOException(UiText.get(R.string.ui_unreadable_image));
        try{
            image=ImageOrientation.upright(image,new ExifInterface(source.getAbsolutePath()));
            // Non-Book sources retain their frame; the editor applies the selected crop once.
            ArrayList<String> result=new ArrayList<>();
            if("Book".equals(captureMode)){
                java.util.List<float[]> quads;
                try{quads=LearnedPageBounds.book(image,doublePage);}catch(Exception error){android.util.Log.w("PaperFlowBook","Book analysis unavailable; using the selected page split",error);float[] whole=PageBoundaryDetector.detectImageGeometry(image);quads=LearnedPageBounds.bookFallback(image,whole,doublePage);}
                try{for(float[] quad:quads){Bitmap page=PageGeometry.crop(image.copy(Bitmap.Config.ARGB_8888,false),quad);File output=newFile("book-page");try{save(page,output);result.add(output.getAbsolutePath());}catch(Exception error){output.delete();throw error;}finally{page.recycle();}}}
                catch(Exception error){for(String path:result)new File(path).delete();throw error;}
                source.delete();
            }else{
                save(image,source);result.add(source.getAbsolutePath());
            }
            return result;
        }finally{if(image!=null&&!image.isRecycled())image.recycle();}
    }
    private void save(Bitmap image,File file)throws IOException{try(FileOutputStream out=new FileOutputStream(file)){if(!image.compress(Bitmap.CompressFormat.JPEG,94,out))throw new IOException(UiText.get(R.string.ui_save_failed));}}
    @Override protected void onCleared(){cleared=true;if(!returned&&!"Book".equals(mode))for(String path:pages)new File(path).delete();worker.shutdown();}
}
