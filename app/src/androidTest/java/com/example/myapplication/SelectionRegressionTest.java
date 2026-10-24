package com.example.myapplication;

import android.content.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.File;
import java.util.Map;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.action.ViewActions.click;

public class SelectionRegressionTest {
    @Test public void trashingOneCardRetainsOnlyTheOtherPageSelection()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefix="selection-regression-"+java.util.UUID.randomUUID();long[] ids=new long[2];
        try(DocumentStore store=new DocumentStore(context)){
            for(int i=0;i<2;i++){String path=new File(context.getFilesDir(),prefix+i+".pdf").getPath();store.registerDocument(path,prefix+i,1);
                try(android.database.Cursor cursor=store.db().rawQuery("SELECT id FROM docs WHERE path=?",new String[]{path})){assertTrue(cursor.moveToFirst());ids[i]=cursor.getLong(0);}}
        }
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(new Intent(context,MainActivity.class))){
            scenario.onActivity(a->{
                Map<Long,String> selected=selected(a);selected.put(ids[0],"first-page-choice");selected.put(ids[1],"other-page-choice");
                try{java.lang.reflect.Method menu=MainActivity.class.getDeclaredMethod("showDocumentMenu",long.class,String.class,String.class,boolean.class);menu.setAccessible(true);menu.invoke(a,ids[0],prefix,"unused",false);}
                catch(Exception error){throw new AssertionError(error);}
            });
            onView(withText(UiText.get(R.string.ui_move_to_trash))).perform(click());
            scenario.onActivity(a->{assertFalse(selected(a).containsKey(ids[0]));assertEquals("other-page-choice",selected(a).get(ids[1]));});
            try(DocumentStore store=new DocumentStore(context);android.database.Cursor cursor=store.db().rawQuery("SELECT deleted_at FROM docs WHERE id=?",new String[]{Long.toString(ids[0])})){assertTrue(cursor.moveToFirst());assertTrue(cursor.getLong(0)>0);}
        }finally{try(DocumentStore store=new DocumentStore(context)){store.db().delete("docs","name LIKE ?",new String[]{prefix+"%"});}}
    }
    @SuppressWarnings("unchecked") private static Map<Long,String> selected(MainActivity activity){
        try{java.lang.reflect.Field field=MainActivity.class.getDeclaredField("selectedDocuments");field.setAccessible(true);return (Map<Long,String>)field.get(activity);}
        catch(Exception error){throw new AssertionError(error);}
    }
}
