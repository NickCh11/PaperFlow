package com.example.myapplication;

import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.io.*;
import java.util.*;

/** A scan publishes its optional gallery copies only after the PDF has been saved. */
final class GalleryExport implements AutoCloseable {
    private final Context context;private final ArrayList<Uri> pending=new ArrayList<>();private boolean committed;
    GalleryExport(Context context){this.context=context;}
    private Uri create(String label,String mime,String extension)throws IOException {
        String name="PaperFlow-"+label+"-"+UUID.randomUUID()+"."+extension;ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,name);values.put(MediaStore.Images.Media.MIME_TYPE,mime);
        if(Build.VERSION.SDK_INT>=29){values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/PaperFlow");values.put(MediaStore.Images.Media.IS_PENDING,1);}
        else {if(androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IOException(UiText.get(R.string.ui_allow_gallery_storage_in_android_settings_first));File folder=new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),"PaperFlow");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException(UiText.get(R.string.ui_gallery_storage_unavailable));values.put(MediaStore.Images.Media.DATA,new File(folder,name).getAbsolutePath());}
        Uri uri=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IOException(UiText.get(R.string.ui_could_not_create_a_gallery_image));pending.add(uri);return uri;
    }
    void original(String path)throws IOException {
        Uri source=Uri.parse(path);String mime=context.getContentResolver().getType(source);if(mime==null){String extension=android.webkit.MimeTypeMap.getFileExtensionFromUrl(path).toLowerCase(Locale.ROOT);mime=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);}if(mime==null||!mime.startsWith("image/"))mime="image/jpeg";
        String extension=android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);if(extension==null)extension="jpg";Uri target=create("original",mime,extension);
        try(InputStream in=context.getContentResolver().openInputStream(source);OutputStream out=context.getContentResolver().openOutputStream(target)){if(in==null||out==null)throw new IOException(UiText.get(R.string.ui_image_unavailable));byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
    }
    void processed(Bitmap image)throws IOException {Uri target=create("scan","image/jpeg","jpg");try(OutputStream out=context.getContentResolver().openOutputStream(target)){if(out==null||!image.compress(Bitmap.CompressFormat.JPEG,95,out))throw new IOException(UiText.get(R.string.ui_could_not_save_the_scan_image));}}
    void commit()throws IOException {if(Build.VERSION.SDK_INT>=29)for(Uri uri:pending){ContentValues ready=new ContentValues();ready.put(MediaStore.Images.Media.IS_PENDING,0);if(context.getContentResolver().update(uri,ready,null,null)!=1)throw new IOException(UiText.get(R.string.ui_could_not_publish_the_gallery_image));}committed=true;}
    @Override public void close(){if(!committed)for(Uri uri:pending)try{context.getContentResolver().delete(uri,null,null);}catch(Exception ignored){}}
}
