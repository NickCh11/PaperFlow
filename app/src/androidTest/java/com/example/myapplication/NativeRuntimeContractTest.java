package com.example.myapplication;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import com.paperflow.translation.OfflineRuntime;
import com.paperflow.translation.RuntimeNative;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import org.json.*;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the actual JNI adapter and its installed offline session, without downloads. */
public class NativeRuntimeContractTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Before public void initialize()throws Exception{OfflineRuntime.get(context);}
    private JSONObject request(String operation)throws Exception{return new JSONObject().put("operation",operation);}
    private JSONObject invoke(JSONObject request)throws Exception{return new JSONObject(RuntimeNative.call(request.toString()));}
    private Set<String> codes(JSONArray rows)throws Exception{Set<String> result=new HashSet<>();for(int i=0;i<rows.length();i++)result.add(rows.getJSONObject(i).getString("code"));return result;}

    @Test public void flatImageDetectionKeepsBlankPagesOutOfRecognizerFallback()throws Exception{
        java.lang.reflect.Method uniform=OfflineRuntime.class.getDeclaredMethod("uniformImage",android.graphics.Bitmap.class);uniform.setAccessible(true);
        for(int[] size:new int[][]{{1,1},{64,64},{65,17}}){
            android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(size[0],size[1],android.graphics.Bitmap.Config.ARGB_8888);
            try{
                image.eraseColor(android.graphics.Color.WHITE);assertEquals(true,uniform.invoke(null,image));
                image.eraseColor(android.graphics.Color.BLACK);assertEquals(true,uniform.invoke(null,image));
                if(size[0]>1){
                    image.eraseColor(android.graphics.Color.WHITE);
                    image.setPixel(0,0,android.graphics.Color.rgb(247,247,247));assertEquals("Small background variation stays flat",true,uniform.invoke(null,image));
                    image.setPixel(0,0,android.graphics.Color.rgb(246,246,246));assertEquals("Visible contrast must allow recognition",false,uniform.invoke(null,image));
                }
            }finally{image.recycle();}
        }
    }

    @Test public void recognizerCandidatesReplaceOnlyOverlappingWordsWithBetterEvidence()throws Exception{
        OfflineRuntime runtime=OfflineRuntime.get(context);
        java.lang.reflect.Method collect=OfflineRuntime.class.getDeclaredMethod("collectLines",JSONArray.class,String.class,Map.class,Map.class,Map.class);collect.setAccessible(true);
        Map<Integer,JSONArray> selected=new LinkedHashMap<>();Map<Integer,Long> scores=new HashMap<>();Map<Integer,long[]> wordScores=new HashMap<>();
        JSONObject first=new JSONObject().put("text","HELLO").put("line",0).put("left",10).put("right",100).put("confidence",.2);
        collect.invoke(runtime,new JSONArray().put(first),"en",selected,scores,wordScores);assertEquals("HELLO",selected.get(0).getJSONObject(0).getString("text"));
        JSONObject better=new JSONObject(first.toString()).put("text","WORLD").put("confidence",.9);
        collect.invoke(runtime,new JSONArray().put(better),"en",selected,scores,wordScores);assertEquals("WORLD",selected.get(0).getJSONObject(0).getString("text"));
        JSONObject elsewhere=new JSONObject(better.toString()).put("text","OTHER").put("left",500).put("right",600).put("confidence",1);
        collect.invoke(runtime,new JSONArray().put(elsewhere),"en",selected,scores,wordScores);assertEquals("WORLD",selected.get(0).getJSONObject(0).getString("text"));assertEquals(10,selected.get(0).getJSONObject(0).getInt("left"));
        JSONObject wrongScript=new JSONObject(better.toString()).put("text","世界").put("confidence",1);
        collect.invoke(runtime,new JSONArray().put(wrongScript),"en",selected,scores,wordScores);assertEquals("WORLD",selected.get(0).getJSONObject(0).getString("text"));
        JSONObject digits=new JSONObject(first.toString()).put("line",2).put("text","123");collect.invoke(runtime,new JSONArray().put(digits),"en",selected,scores,wordScores);
        JSONObject left=new JSONObject(first.toString()).put("line",2).put("text","Hello"),right=new JSONObject(first.toString()).put("line",2).put("left",120).put("right",200).put("text","world");
        collect.invoke(runtime,new JSONArray().put(left).put(right),"en",selected,scores,wordScores);assertEquals(2,selected.get(2).length());assertEquals("world",selected.get(2).getJSONObject(1).getString("text"));
        assertEquals(1,selected.get(0).length());assertEquals(2,selected.size());
    }
    @Test public void malformedAndUnknownRequestsFailWithoutDamagingTheSession()throws Exception{
        Set<String> before=codes(new JSONArray(RuntimeNative.call(request("languages").toString())));
        for(String raw:new String[]{"{broken","null","[]","{}","{\"operation\":17}","{\"operation\":\"not-supported\"}"})assertThrows(IOException.class,()->RuntimeNative.call(raw));
        for(String catalog:new String[]{"{}","not-json","{\"formatVersion\":-1}"})assertThrows(IOException.class,()->invoke(request("catalog").put("catalog",catalog)));
        assertEquals(before,codes(new JSONArray(RuntimeNative.call(request("languages").toString()))));
    }

    @Test public void catalogRefreshAndDownloadPlansAreLocalMetadataOperations()throws Exception{
        JSONArray rows=new JSONArray(RuntimeNative.call(request("languages").toString()));Set<String> before=codes(rows);assertTrue(before.contains("en"));assertTrue(before.contains("el"));
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);assertFalse(row.getString("name").isEmpty());assertTrue(row.getLong("size")>=0);row.getBoolean("installed");row.getBoolean("ocr");row.getBoolean("fromEnglish");row.getBoolean("toEnglish");}
        assertEquals(0,invoke(request("refresh")).length());assertEquals(before,codes(new JSONArray(RuntimeNative.call(request("languages").toString()))));
        JSONObject plan=invoke(request("plan").put("language","en"));assertTrue(plan.getLong("size")>=0);assertNotNull(plan.getJSONArray("tasks"));assertNotNull(plan.getJSONObject("deleteAfter"));
        assertThrows(IOException.class,()->invoke(request("plan").put("language","paperflow-unknown-language")));
        for(String kind:new String[]{"ocr","translation"}){JSONObject upgrade=invoke(request("upgradePlan").put("language","en").put("kind",kind));assertTrue(upgrade.getLong("size")>=0);assertNotNull(upgrade.getJSONArray("tasks"));}
        JSONObject deletion=invoke(request("deletePlan").put("language","paperflow-unknown-language"));assertEquals(0,deletion.getJSONArray("files").length());assertEquals(0,deletion.getJSONArray("directories").length());
    }

    @Test public void archivePlansConstrainPathsAndRejectMissingOrUnsafeEntries()throws Exception{
        JSONObject base=request("zipPlan").put("extractTo","ocr/test-fixture");
        assertThrows(IOException.class,()->invoke(base));
        for(String entry:new String[]{"../outside.bin","nested/../../outside.bin"})assertThrows(entry,IOException.class,()->invoke(request("zipPlan").put("extractTo","ocr/test-fixture").put("entries",new JSONArray().put(entry))));
        JSONObject absolute=invoke(request("zipPlan").put("extractTo","ocr/test-fixture").put("entries",new JSONArray().put("/absolute.bin")));
        assertEquals("ocr/test-fixture/absolute.bin",absolute.getJSONArray("entries").getJSONObject(0).getString("path"));
        for(boolean marker:new boolean[]{false,true}){
            JSONObject query=request("zipPlan").put("extractTo","ocr/test-fixture").put("entries",new JSONArray().put("model/").put("model/weights.bin").put("model/dictionary.txt").put(17));
            if(marker)query.put("marker",new JSONObject().put("path","ocr/test-fixture/.installed").put("version",1));
            JSONObject planned=invoke(query);JSONArray entries=planned.getJSONArray("entries");assertEquals(3,entries.length());assertNotNull(planned.getJSONArray("replacePaths"));
            for(int i=0;i<entries.length();i++){JSONObject row=entries.getJSONObject(i);assertTrue(row.getString("path"),row.getString("path").startsWith("ocr/test-fixture/"));assertFalse(row.getString("path").contains(".."));row.getBoolean("directory");}
        }
    }

    @Test public void invalidImageDimensionsAndBuffersAreRejectedBeforeRecognition()throws Exception{
        ByteBuffer input=ByteBuffer.allocateDirect(16),output=ByteBuffer.allocateDirect(0);String request="{\"source\":\"en\",\"target\":\"\"}";
        for(int[] size:new int[][]{{0,2},{2,0},{-1,2},{2,-1},{20001,2},{2,20001}}){IOException error=assertThrows(IOException.class,()->RuntimeNative.image(input,size[0],size[1],request,output));assertTrue(error.getMessage().contains("Invalid image dimensions"));}
        assertThrows(IOException.class,()->RuntimeNative.image(input,2,2,"{broken",output));
        for(int capacity:new int[]{0,3,8}){ByteBuffer invalid=ByteBuffer.allocateDirect(capacity);IOException error=assertThrows(IOException.class,()->RuntimeNative.image(invalid,1,1,request,output));assertTrue(error.getMessage().contains("Invalid RGBA image"));}
        assertThrows(IOException.class,()->RuntimeNative.image(ByteBuffer.allocate(4),1,1,request,output));assertEquals(16,input.capacity());
    }

    @Test public void liveHandleOwnershipSupportsStateChangesAndNullCleanup()throws Exception{
        RuntimeNative.destroyLive(0);RuntimeNative.controlLive(0,false,false,false);RuntimeNative.changeLiveLanguages(0,"auto","en");
        for(String source:new String[]{"auto","en"}){
            long pipeline=RuntimeNative.createLive(source,"en");assertTrue(pipeline!=0);
            try{for(boolean enabled:new boolean[]{false,true})for(boolean scanning:new boolean[]{false,true})for(boolean reset:new boolean[]{false,true})RuntimeNative.controlLive(pipeline,enabled,scanning,reset);
                RuntimeNative.changeLiveLanguages(pipeline,"auto","en");RuntimeNative.changeLiveLanguages(pipeline,"en","en");
            }finally{RuntimeNative.destroyLive(pipeline);}
        }
    }

    @Test public void idleModelReleasePreservesCatalogAndDefersToLiveOwners() throws Exception {
        Set<String> before = codes(new JSONArray(RuntimeNative.call(request("languages").toString())));
        assertTrue(invoke(request("releaseIdleModels")).getBoolean("released"));
        long pipeline = RuntimeNative.createLive("en", "en"); assertTrue(pipeline != 0);
        try {
            assertFalse(invoke(request("releaseIdleModels")).getBoolean("released"));
            RuntimeNative.controlLive(pipeline, true, false, true);
            assertEquals(before, codes(new JSONArray(RuntimeNative.call(request("languages").toString()))));
        } finally { RuntimeNative.destroyLive(pipeline); }
        UiTestWait.until(() -> {
            try { return invoke(request("releaseIdleModels")).getBoolean("released"); }
            catch (Exception error) { throw new AssertionError(error); }
        }, "Released live pipeline still owns the offline model cache");
        assertEquals(before, codes(new JSONArray(RuntimeNative.call(request("languages").toString()))));
    }
}
