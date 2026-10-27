package com.example.myapplication;

import android.content.*;
import android.view.*;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class LibraryPagingRegressionTest {
    private final android.app.Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private Object field(MainActivity a,String name){try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(a);}catch(Exception e){throw new AssertionError(e);}}
    private void set(MainActivity a,String name,Object value){try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(a,value);}catch(Exception e){throw new AssertionError(e);}}
    private void render(MainActivity a,boolean home){try{java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod(home?"renderHome":"renderAllDocuments");m.setAccessible(true);m.invoke(a);}catch(Exception e){throw new AssertionError(e);}}
    private void collect(View view,String prefix,Set<String> result){if(view instanceof TextView){String text=((TextView)view).getText().toString();if(text.startsWith(prefix)&&text.length()>prefix.length()&&Character.isDigit(text.charAt(prefix.length())))result.add(text);}if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collect(group.getChildAt(i),prefix,result);}}
    private Set<String> names(MainActivity a,String prefix){Set<String> result=new HashSet<>();Runnable read=()->collect((View)field(a,"content"),prefix,result);if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())read.run();else inst.runOnMainSync(read);return result;}
    private View control(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){View found=control(g.getChildAt(i),text);if(found!=null)return found;}}return null;}

    private final class Fixture implements AutoCloseable {
        final Context context=inst.getTargetContext();final String prefix="paging-"+UUID.randomUUID()+"-";final long folder;
        Fixture(int count){try(DocumentStore store=new DocumentStore(context)){
            ContentValues values=new ContentValues();values.put("name",prefix);folder=store.db().insertOrThrow("folders",null,values);
            for(int i=0;i<count;i++){String path=new File(context.getFilesDir(),prefix+i+".pdf").getPath();store.registerDocument(path,prefix+i,1);values.clear();values.put("folder_id",folder);values.put("search_meta",DocumentStore.normalize(prefix+i));store.db().update("docs",values,"path=?",new String[]{path});}
        }}
        @Override public void close(){try(DocumentStore store=new DocumentStore(context)){store.db().delete("docs","name LIKE ?",new String[]{prefix+"%"});store.db().delete("folders","id=?",new String[]{Long.toString(folder)});}}
    }

    @Test public void deletingTheFinalWindowReturnsToTheLastNonemptyPage()throws Exception{
        for(boolean home:new boolean[]{true,false})try(Fixture fixture=new Fixture(65);ActivityScenario<MainActivity> scenario=ActivityScenario.launch(new Intent(fixture.context,MainActivity.class))){
            AtomicReference<MainActivity> owner=new AtomicReference<>();scenario.onActivity(a->{owner.set(a);set(a,"searchQuery",fixture.prefix);set(a,"folderId",fixture.folder);render(a,home);});
            UiTestWait.until(()->names(owner.get(),fixture.prefix).size()==30,"First library window did not load");
            for(int next=1;next<=2;next++){
                int offset=next*30;scenario.onActivity(a->{View button=control((View)field(a,"content"),UiText.get(R.string.ui_next));assertNotNull(button);assertTrue(button.performClick());});
                UiTestWait.until(()->Integer.valueOf(offset).equals(field(owner.get(),"libraryOffset"))&&names(owner.get(),fixture.prefix).size()==(offset==60?5:30),"Next library window did not load");
            }
            Set<String> finalPage=names(owner.get(),fixture.prefix);
            try(DocumentStore store=new DocumentStore(fixture.context)){for(String name:finalPage)store.db().delete("docs","name=?",new String[]{name});}
            scenario.onActivity(a->render(a,home));
            UiTestWait.until(()->Integer.valueOf(30).equals(field(owner.get(),"libraryOffset"))&&names(owner.get(),fixture.prefix).size()==30,"Deletion left the user on an empty final window");
            scenario.onActivity(a->{View previous=control((View)field(a,"content"),UiText.get(R.string.ui_previous));assertNotNull(previous);assertTrue(previous.performClick());});
            UiTestWait.until(()->Integer.valueOf(0).equals(field(owner.get(),"libraryOffset"))&&names(owner.get(),fixture.prefix).size()==30,"Previous library window did not load");
        }
    }

    @Test public void aSlowPreviousQueryCannotReplaceTheNewSearchResults()throws Exception{
        CountDownLatch release=new CountDownLatch(1);
        try(Fixture fixture=new Fixture(2);ActivityScenario<MainActivity> scenario=ActivityScenario.launch(new Intent(fixture.context,MainActivity.class))){
            AtomicReference<MainActivity> owner=new AtomicReference<>();scenario.onActivity(owner::set);
            ExecutorService worker=(ExecutorService)field(owner.get(),"libraryWorker");CountDownLatch held=new CountDownLatch(1);
            worker.execute(()->{held.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(held.await(5,TimeUnit.SECONDS));
            scenario.onActivity(a->{set(a,"searchQuery",fixture.prefix+"0");render(a,true);set(a,"searchQuery",fixture.prefix+"1");render(a,true);});
            release.countDown();
            UiTestWait.until(()->names(owner.get(),fixture.prefix).equals(Collections.singleton(fixture.prefix+"1")),"Stale query replaced the current search");
        }finally{release.countDown();}
    }

    @Test public void favoritesAndTrashWindowsDoNotMixTheirDocuments()throws Exception{
        try(Fixture fixture=new Fixture(3);ActivityScenario<MainActivity> scenario=ActivityScenario.launch(new Intent(fixture.context,MainActivity.class))){
            try(DocumentStore store=new DocumentStore(fixture.context)){
                ContentValues values=new ContentValues();values.put("favorite",1);store.db().update("docs",values,"name=?",new String[]{fixture.prefix+"0"});
                values.clear();values.put("deleted_at",System.currentTimeMillis());store.db().update("docs",values,"name=?",new String[]{fixture.prefix+"2"});
            }
            AtomicReference<MainActivity> owner=new AtomicReference<>();scenario.onActivity(a->{owner.set(a);set(a,"folderId",fixture.folder);set(a,"favoritesOnly",false);set(a,"trashView",false);render(a,false);});
            UiTestWait.until(()->names(owner.get(),fixture.prefix).equals(new HashSet<>(Arrays.asList(fixture.prefix+"0",fixture.prefix+"1"))),"Active window included a trashed document");
            scenario.onActivity(a->{set(a,"favoritesOnly",true);render(a,false);});
            UiTestWait.until(()->names(owner.get(),fixture.prefix).equals(Collections.singleton(fixture.prefix+"0")),"Favorites window included a non-favorite");
            scenario.onActivity(a->{set(a,"trashView",true);render(a,false);});
            UiTestWait.until(()->{Set<String> result=names(owner.get(),fixture.prefix);return result.size()==1&&result.iterator().next().startsWith(fixture.prefix+"2");},"Trash window included an active document");
            scenario.onActivity(a->{set(a,"searchQuery",fixture.prefix+"missing");render(a,true);});
            UiTestWait.until(()->control((View)field(owner.get(),"content"),UiText.get(R.string.ui_no_files_found))!=null,"An empty search did not show its empty state");
        }
    }
}
