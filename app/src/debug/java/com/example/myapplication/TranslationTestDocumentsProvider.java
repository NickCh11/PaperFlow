package com.example.myapplication;

import android.database.*;
import android.os.*;
import android.provider.*;
import java.io.*;

/** Debug-only SAF destination used to verify saving several translated images. */
public class TranslationTestDocumentsProvider extends DocumentsProvider {
    static final String[] COLUMNS={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS,DocumentsContract.Document.COLUMN_SIZE};
    File root;
    @Override public boolean onCreate(){root=new File(getContext().getCacheDir(),"translation-test-destination");return root.isDirectory()||root.mkdirs();}
    File file(String id)throws FileNotFoundException {
        if(id.equals("root"))return root;
        try{File file=new File(root,id).getCanonicalFile();if(!file.getPath().startsWith(root.getCanonicalPath()+File.separator))throw new FileNotFoundException();return file;}catch(IOException error){throw new FileNotFoundException(id);}
    }
    @Override public Cursor queryRoots(String[] projection){MatrixCursor cursor=new MatrixCursor(new String[]{DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_DOCUMENT_ID,DocumentsContract.Root.COLUMN_TITLE,DocumentsContract.Root.COLUMN_FLAGS});cursor.addRow(new Object[]{"root","root","Translation test destination",DocumentsContract.Root.FLAG_SUPPORTS_CREATE|DocumentsContract.Root.FLAG_LOCAL_ONLY});return cursor;}
    void row(MatrixCursor cursor,File file){cursor.addRow(new Object[]{file.equals(root)?"root":file.getName(),file.getName(),file.isDirectory()?DocumentsContract.Document.MIME_TYPE_DIR:"image/png",file.isDirectory()?DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE:DocumentsContract.Document.FLAG_SUPPORTS_WRITE|DocumentsContract.Document.FLAG_SUPPORTS_DELETE,file.length()});}
    @Override public Cursor queryDocument(String id,String[] projection)throws FileNotFoundException {MatrixCursor cursor=new MatrixCursor(COLUMNS);row(cursor,file(id));return cursor;}
    @Override public Cursor queryChildDocuments(String id,String[] projection,String sortOrder)throws FileNotFoundException {MatrixCursor cursor=new MatrixCursor(COLUMNS);File[] files=file(id).listFiles();if(files!=null)for(File file:files)row(cursor,file);return cursor;}
    @Override public String createDocument(String parent,String mime,String name)throws FileNotFoundException {
        if(!parent.equals("root"))throw new FileNotFoundException();File file=file(name);try{if(!file.createNewFile())throw new FileNotFoundException("Duplicate test destination");return file.getName();}catch(IOException error){throw new FileNotFoundException(error.getMessage());}
    }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException {return ParcelFileDescriptor.open(file(id),ParcelFileDescriptor.parseMode(mode));}
    @Override public boolean isChildDocument(String parent,String child){try{return parent.equals("root")&&!child.equals("root")&&file(child).isFile();}catch(FileNotFoundException error){return false;}}
    @Override public void deleteDocument(String id)throws FileNotFoundException {if(id.equals("root")||!file(id).delete())throw new FileNotFoundException(id);}
}
