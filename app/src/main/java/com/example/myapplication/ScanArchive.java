package com.example.myapplication;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Immutable source photos and recipes belong to one exported PDF version. */
final class ScanArchive {
    private static File directory(Context c, String pdf) {
        return new File(c.getFilesDir(), "scan-originals/" + UUID.nameUUIDFromBytes(pdf.getBytes(StandardCharsets.UTF_8)));
    }
    static JSONObject read(Context c,String pdf)throws Exception {
        return new JSONObject(new String(Files.readAllBytes(new File(directory(c,pdf),"recipe.json").toPath()),StandardCharsets.UTF_8));
    }
    static boolean exists(Context c,String pdf){return new File(directory(c,pdf),"recipe.json").isFile();}
    static void save(Context c,File pdf,String mode,int paper,List<String> pages,List<Integer> turns,List<Integer> filters,List<float[]> crops,List<Integer> brightness,List<Integer> contrast,int dewarp,boolean fingers,boolean demoire,List<JSONObject> configs)throws Exception{
        File dir=directory(c,pdf.getAbsolutePath());if(!dir.mkdirs()&&!dir.isDirectory())throw new IOException(UiText.get(R.string.ui_cannot_retain_original_photos));
        try{JSONArray entries=new JSONArray();for(int i=0;i<pages.size();i++){
            android.graphics.BitmapFactory.Options probe=new android.graphics.BitmapFactory.Options();probe.inJustDecodeBounds=true;try(InputStream input=c.getContentResolver().openInputStream(Uri.parse(pages.get(i)))){android.graphics.BitmapFactory.decodeStream(input,null,probe);}String extension=probe.outMimeType==null?null:android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(probe.outMimeType);
            File photo=new File(dir,i+"."+(extension==null?"jpg":extension));try(InputStream in=c.getContentResolver().openInputStream(Uri.parse(pages.get(i)));OutputStream out=new FileOutputStream(photo)){
                if(in==null)throw new IOException(UiText.get(R.string.ui_original_photo_unavailable));byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            }
            try{File original=new File(Uri.parse(pages.get(i)).getPath());if(original.isFile())photo.setLastModified(original.lastModified());}catch(Exception ignored){}
            JSONArray corners=new JSONArray();for(float f:crops.get(i))corners.put(f);
            entries.put(new JSONObject().put("photo",photo.getName()).put("rotation",turns.get(i)).put("filter",filters.get(i)).put("crop",corners).put("brightness",brightness.get(i)).put("contrast",contrast.get(i)).put("config",configs.get(i)));
        }JSONObject recipe=new JSONObject().put("version",2).put("mode",mode).put("paper",paper).put("dewarp",dewarp).put("fingers",fingers).put("demoire",demoire).put("pages",entries);
            try(OutputStream out=new FileOutputStream(new File(dir,"recipe.json"))){out.write(recipe.toString().getBytes(StandardCharsets.UTF_8));}
        }catch(Exception e){remove(c,pdf.getAbsolutePath());throw e;}
    }
    static JSONObject config(JSONObject root,JSONObject entry,int index)throws Exception{
        if(entry.has("config"))return new JSONObject(entry.getJSONObject("config").toString());
        String mode=root.optString("mode","Single");boolean id=mode.equals("ID Card")||mode.equals("ID Cards");return new JSONObject().put("mode",mode).put("paper",root.optInt("paper")).put("dewarp",root.optInt("dewarp",1)).put("fingers",root.optBoolean("fingers")).put("demoire",root.optBoolean("demoire")).put("group",Integer.toString(id?index/2:index));
    }
    /** Each input has independent settings and ID front/back groups; ordinary PDFs get rendered fallback pages. */
    static void combine(Context c,List<File> inputs,List<String> passwords,File output)throws Exception{
        boolean hasOriginals=false;for(File input:inputs)hasOriginals|=exists(c,input.getAbsolutePath());if(!hasOriginals)return;
        for(int i=0;i<inputs.size();i++)try(com.tom_roush.pdfbox.pdmodel.PDDocument doc=com.tom_roush.pdfbox.pdmodel.PDDocument.load(inputs.get(i),passwords.get(i))){if(!doc.getCurrentAccessPermission().canExtractContent())return;}
        File target=directory(c,output.getAbsolutePath());if(!target.mkdirs()&&!target.isDirectory())throw new IOException(UiText.get(R.string.ui_cannot_retain_combined_scan_originals));JSONArray pages=new JSONArray();
        try{for(int source=0;source<inputs.size();source++){File input=inputs.get(source);String prefix=source+"-";if(exists(c,input.getAbsolutePath())){JSONObject root=read(c,input.getAbsolutePath());JSONArray original=root.getJSONArray("pages");File dir=directory(c,input.getAbsolutePath());for(int i=0;i<original.length();i++){JSONObject entry=new JSONObject(original.getJSONObject(i).toString());File photo=new File(dir,entry.getString("photo")).getCanonicalFile();if(!photo.getParentFile().equals(dir.getCanonicalFile())||!photo.isFile())throw new IOException(UiText.get(R.string.ui_original_scan_photo_unavailable));int dot=photo.getName().lastIndexOf('.');String filename=pages.length()+(dot>=0?photo.getName().substring(dot):".image");File copy=new File(target,filename);Files.copy(photo.toPath(),copy.toPath());copy.setLastModified(photo.lastModified());JSONObject config=config(root,entry,i);config.put("group",prefix+config.getString("group"));entry.put("photo",filename).put("config",config);pages.put(entry);}}
            else try(com.tom_roush.pdfbox.pdmodel.PDDocument doc=com.tom_roush.pdfbox.pdmodel.PDDocument.load(input,passwords.get(source))){com.tom_roush.pdfbox.rendering.PDFRenderer renderer=new com.tom_roush.pdfbox.rendering.PDFRenderer(doc);for(int i=0;i<doc.getNumberOfPages();i++){com.tom_roush.pdfbox.pdmodel.common.PDRectangle box=doc.getPage(i).getCropBox();android.graphics.Bitmap image=renderer.renderImage(i,Math.min(2400/box.getWidth(),3200/box.getHeight()));String filename=pages.length()+"-rendered.jpg";try(OutputStream out=new FileOutputStream(new File(target,filename))){if(!image.compress(android.graphics.Bitmap.CompressFormat.JPEG,96,out))throw new IOException(UiText.get(R.string.ui_cannot_retain_pdf_page));}finally{image.recycle();}JSONObject config=new JSONObject().put("mode","Single").put("paper",0).put("dewarp",0).put("fingers",false).put("demoire",false).put("rendered",true).put("group",prefix+i);pages.put(new JSONObject().put("photo",filename).put("rotation",0).put("filter",0).put("crop",new JSONArray(new float[]{0,0,1,0,1,1,0,1})).put("brightness",0).put("contrast",0).put("config",config));}}
        }JSONObject root=new JSONObject().put("version",2).put("mode","Mixed").put("pages",pages);Files.write(new File(target,"recipe.json").toPath(),root.toString().getBytes(StandardCharsets.UTF_8));}catch(Exception|OutOfMemoryError e){remove(c,output.getAbsolutePath());throw e;}
    }
    static Intent intent(Context c,String pdf,String name,String password)throws Exception{
        try(com.tom_roush.pdfbox.pdmodel.PDDocument doc=com.tom_roush.pdfbox.pdmodel.PDDocument.load(new File(pdf),password)){PdfPermissions.modify(doc);PdfPermissions.extract(doc);}
        JSONObject recipe=read(c,pdf);JSONArray entries=recipe.getJSONArray("pages");ArrayList<String> photos=new ArrayList<>();File dir=directory(c,pdf);
        for(int i=0;i<entries.length();i++){File photo=new File(dir,entries.getJSONObject(i).getString("photo")).getCanonicalFile();if(!photo.getParentFile().equals(dir.getCanonicalFile())||!photo.isFile())throw new IOException(UiText.get(R.string.ui_original_photo_unavailable));photos.add(Uri.fromFile(photo).toString());}
        return new Intent(c,ScanEditorActivity.class).putStringArrayListExtra("pages",photos).putExtra("scan_recipe",recipe.toString()).putExtra("capture_mode",recipe.optString("mode","Single")).putExtra("name",name+" · edited").putExtra("start_crop",false).putExtra("edit_source",pdf).putExtra("edit_password",password);
    }
    static void remove(Context c,String pdf){File dir=directory(c,pdf);File[] children=dir.listFiles();if(children!=null)for(File file:children)file.delete();dir.delete();}
}
