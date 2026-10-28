package com.example.myapplication;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** One schema and registration path for the library and document tools. */
final class DocumentStore extends SQLiteOpenHelper {
    private final Context context;
    DocumentStore(Context context){super(context.getApplicationContext(),"paperflow.db",null,9);this.context=context.getApplicationContext();}
    @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE docs (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, path TEXT NOT NULL, pages INTEGER NOT NULL, created INTEGER NOT NULL, favorite INTEGER NOT NULL DEFAULT 0, last_opened INTEGER NOT NULL DEFAULT 0, tags TEXT NOT NULL DEFAULT '')");addLibrarySchema(db);addProtectionSchema(db);addRevisionSchema(db);addRecentIndexes(db);}
    private void addRevisionSchema(SQLiteDatabase db){db.execSQL("CREATE TABLE document_revisions (source_path TEXT PRIMARY KEY, latest_path TEXT NOT NULL)");}
    private void addRecentIndexes(SQLiteDatabase db){db.execSQL("CREATE INDEX IF NOT EXISTS docs_path ON docs(path)");db.execSQL("CREATE INDEX IF NOT EXISTS document_revisions_latest ON document_revisions(latest_path)");}
    private void addProtectionSchema(SQLiteDatabase db){db.execSQL("CREATE TABLE document_guards (path TEXT NOT NULL, folder_id INTEGER NOT NULL, PRIMARY KEY(path,folder_id))");db.execSQL("CREATE INDEX document_guards_folder ON document_guards(folder_id)");}
    private void addLibrarySchema(SQLiteDatabase db){
        db.execSQL("ALTER TABLE docs ADD COLUMN folder_id INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE docs ADD COLUMN deleted_at INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE docs ADD COLUMN search_meta TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE docs ADD COLUMN search_text TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE docs ADD COLUMN indexed_mtime INTEGER NOT NULL DEFAULT 0");
        db.execSQL("CREATE TABLE folders (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, parent INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX docs_location ON docs(deleted_at,folder_id)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){
        if(oldVersion<2)db.execSQL("ALTER TABLE docs ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0");
        if(oldVersion<4){db.execSQL("ALTER TABLE docs ADD COLUMN last_opened INTEGER NOT NULL DEFAULT 0");db.execSQL("UPDATE docs SET last_opened=created");}
        if(oldVersion<5)db.execSQL("ALTER TABLE docs ADD COLUMN tags TEXT NOT NULL DEFAULT ''");
        if(oldVersion<6)addLibrarySchema(db);
        if(oldVersion<7)addProtectionSchema(db);
        if(oldVersion<8)addRevisionSchema(db);
        if(oldVersion<9)addRecentIndexes(db);
    }
    SQLiteDatabase db(){return getWritableDatabase();}
    static String recentRevisionSql(){return "NOT EXISTS (SELECT 1 FROM document_revisions r JOIN docs latest ON latest.path=r.latest_path WHERE r.source_path=docs.path AND latest.deleted_at=0)";}
    void signedRevision(String source,String output){
        if(source==null||source.equals(output))return;
        db().beginTransaction();try{
            ContentValues advance=new ContentValues();advance.put("latest_path",output);db().update("document_revisions",advance,"latest_path=?",new String[]{source});
            ContentValues row=new ContentValues();row.put("source_path",source);row.put("latest_path",output);db().insertWithOnConflict("document_revisions",null,row,SQLiteDatabase.CONFLICT_REPLACE);
            db().setTransactionSuccessful();
        }finally{db().endTransaction();}
    }
    static void inheritProtection(Context context,String path,java.util.Collection<String> origins){
        try(DocumentStore store=new DocumentStore(context)){
            store.db().beginTransaction();try{
                java.util.Set<Long> roots=new java.util.LinkedHashSet<>();for(String origin:origins)if(origin!=null)roots.addAll(store.protectionRoots(origin));
                for(Long root:roots){ContentValues values=new ContentValues();values.put("path",path);values.put("folder_id",root);store.db().insertWithOnConflict("document_guards",null,values,SQLiteDatabase.CONFLICT_IGNORE);}
                store.db().setTransactionSuccessful();
            }finally{store.db().endTransaction();}
        }
    }
    /** Protection provenance survives a derivative being moved out of its source folder. */
    java.util.Set<Long> protectionRoots(String path){
        java.util.Set<Long> roots=new java.util.LinkedHashSet<>();
        try(Cursor row=db().rawQuery("SELECT folder_id FROM docs WHERE path=?",new String[]{path})){if(row.moveToFirst())roots.addAll(FolderLock.ancestors(context,this,row.getLong(0)));}
        try(Cursor rows=db().rawQuery("SELECT folder_id FROM document_guards WHERE path=?",new String[]{path})){while(rows.moveToNext())roots.addAll(FolderLock.ancestors(context,this,rows.getLong(0)));}
        return roots;
    }
    void registerDerived(String path,String name,int pages,java.util.Collection<String> origins){
        db().beginTransaction();try{
            java.util.Set<Long> guards=new java.util.LinkedHashSet<>();Long folder=null;
            for(String origin:origins)if(origin!=null){guards.addAll(protectionRoots(origin));if(folder==null)try(Cursor row=db().rawQuery("SELECT folder_id FROM docs WHERE path=?",new String[]{origin})){if(row.moveToFirst())folder=row.getLong(0);}}
            registerDocument(path,name,pages);
            if(folder!=null){ContentValues values=new ContentValues();values.put("folder_id",folder);db().update("docs",values,"path=?",new String[]{path});}
            for(Long guard:guards){ContentValues values=new ContentValues();values.put("path",path);values.put("folder_id",guard);db().insertWithOnConflict("document_guards",null,values,SQLiteDatabase.CONFLICT_IGNORE);}
            if(!protectionRoots(path).isEmpty()){ContentValues clear=new ContentValues();clear.put("search_text","");clear.put("search_meta","");clear.put("indexed_mtime",0);db().update("docs",clear,"path=?",new String[]{path});}
            db().setTransactionSuccessful();
        }finally{db().endTransaction();}
    }
    void registerDocument(String path,String name,int pages){
        ContentValues values=new ContentValues();long now=System.currentTimeMillis();values.put("name",name);values.put("path",path);values.put("pages",Math.max(0,pages));values.put("created",now);values.put("last_opened",now);values.put("favorite",0);
        boolean exists;try(Cursor cursor=db().query("docs",new String[]{"id"},"path=?",new String[]{path},null,null,null)){exists=cursor.moveToFirst();}
        if(exists){values.remove("created");values.remove("favorite");values.put("indexed_mtime",0);db().update("docs",values,"path=?",new String[]{path});}else {long folder=context.getSharedPreferences("scan_defaults",0).getLong("folder",0);if(folder!=0)try(Cursor cursor=db().rawQuery("SELECT id FROM folders WHERE id=?",new String[]{Long.toString(folder)})){if(!cursor.moveToFirst())folder=0;}values.put("folder_id",folder);db().insertOrThrow("docs",null,values);}
    }
    void trash(long id){ContentValues values=new ContentValues();values.put("deleted_at",System.currentTimeMillis());db().update("docs",values,"id=?",new String[]{Long.toString(id)});}
    static String normalize(String value){return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(java.util.Locale.ROOT);}
    void indexText(){
        try(Cursor c=db().query("docs",new String[]{"id","path","indexed_mtime","name","tags","search_meta","folder_id"},"deleted_at=0",null,null,null,null)){
            while(c.moveToNext()){
                if(!protectionRoots(c.getString(1)).isEmpty())continue;
                String meta=normalize(c.getString(3)+" "+c.getString(4));if(!meta.equals(c.getString(5))){ContentValues values=new ContentValues();values.put("search_meta",meta);db().update("docs",values,"id=?",new String[]{c.getString(0)});}
                java.io.File file=new java.io.File(c.getString(1));long stamp=file.lastModified();if(stamp==0||stamp==c.getLong(2))continue;
                try(com.tom_roush.pdfbox.pdmodel.PDDocument document=com.tom_roush.pdfbox.pdmodel.PDDocument.load(file,com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(16*1024*1024))){
                    String text=document.getCurrentAccessPermission().canExtractContent()?new com.tom_roush.pdfbox.text.PDFTextStripper().getText(document):"";
                    if(!protectionRoots(c.getString(1)).isEmpty())continue;
                    ContentValues values=new ContentValues();values.put("search_text",normalize(text));values.put("indexed_mtime",stamp);db().update("docs",values,"id=?",new String[]{c.getString(0)});
                }catch(Exception|OutOfMemoryError ignored){ContentValues values=new ContentValues();values.put("search_text","");values.put("indexed_mtime",stamp);db().update("docs",values,"id=?",new String[]{c.getString(0)});}
            }
        }
    }
}
