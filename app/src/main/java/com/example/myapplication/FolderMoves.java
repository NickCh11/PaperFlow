package com.example.myapplication;

import android.content.*;
import android.database.Cursor;
import java.util.*;

final class FolderMoves {
    static Set<Long> descendants(DocumentStore store,long id){
        Map<Long,Long> parents=new HashMap<>();try(Cursor c=store.db().rawQuery("SELECT id,parent FROM folders",null)){while(c.moveToNext())parents.put(c.getLong(0),c.getLong(1));}
        Set<Long> result=new HashSet<>();result.add(id);boolean changed;
        do{changed=false;for(Map.Entry<Long,Long> row:parents.entrySet())if(result.contains(row.getValue()))changed|=result.add(row.getKey());}while(changed);return result;
    }
    static void move(Context context,DocumentStore store,long id,long target){
        store.db().beginTransaction();try{
            Set<Long> moved=descendants(store,id);if(id==0||moved.contains(target))throw new IllegalArgumentException(UiText.get(R.string.ui_a_folder_cannot_be_moved_inside_itself_or_its_subfolders));
            try(Cursor c=store.db().rawQuery("SELECT id FROM folders WHERE id=?",new String[]{Long.toString(id)})){if(!c.moveToFirst())throw new IllegalArgumentException(UiText.get(R.string.ui_folder_no_longer_exists));}
            if(target!=0)try(Cursor c=store.db().rawQuery("SELECT id FROM folders WHERE id=?",new String[]{Long.toString(target)})){if(!c.moveToFirst())throw new IllegalArgumentException(UiText.get(R.string.ui_destination_no_longer_exists));}
            if(!FolderLock.allowed(context,store,id)||!FolderLock.allowed(context,store,target))throw new IllegalStateException(UiText.get(R.string.ui_unlock_the_source_and_destination_folders_first));
            ContentValues values=new ContentValues();values.put("parent",target);store.db().update("folders",values,"id=?",new String[]{Long.toString(id)});
            ContentValues clear=new ContentValues();clear.put("search_text","");clear.put("search_meta","");clear.put("indexed_mtime",0);
            for(Long folder:moved)if(!FolderLock.ancestors(context,store,folder).isEmpty())store.db().update("docs",clear,"folder_id=?",new String[]{Long.toString(folder)});
            store.db().setTransactionSuccessful();
        }finally{store.db().endTransaction();}
    }
}
