package com.example.myapplication;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.junit.Test;
import static org.junit.Assert.*;

public class CloudCredentialsTest {
    @Test public void pendingConversionStateNeverPersistsTheApiKey(){
        MainActivity.CloudRequest request=new MainActivity.CloudRequest("/private/report.pdf","Report","pdf-password","temporary-api-key");
        android.os.Bundle saved=request.saved();
        assertEquals(3,saved.size());
        MainActivity.CloudRequest restored=MainActivity.CloudRequest.restore(saved);
        assertEquals(request.path,restored.path);assertEquals(request.name,restored.name);assertEquals(request.password,restored.password);
        assertNull(restored.key);assertNull(MainActivity.CloudRequest.restore(null));
    }

    @Test public void rotationRetainsThePendingConversionAndCancellationClearsIt()throws Exception{
        android.content.Context context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
        MainActivity.CloudRequest request=new MainActivity.CloudRequest("/private/report.pdf","Report","",null);
        try(androidx.test.core.app.ActivityScenario<MainActivity> scenario=androidx.test.core.app.ActivityScenario.launch(new android.content.Intent(context,MainActivity.class))){
            scenario.onActivity(a->state(a).pendingCloud=request);
            scenario.recreate();
            scenario.onActivity(a->{
                assertSame(request,state(a).pendingCloud);
                try{java.lang.reflect.Method callback=MainActivity.class.getDeclaredMethod("finishGoogleUnlock",int.class);callback.setAccessible(true);callback.invoke(a,android.app.Activity.RESULT_CANCELED);}
                catch(Exception error){throw new AssertionError(error);}
                assertNull(state(a).pendingCloud);
            });
        }
    }

    private static MainActivity.WordState state(MainActivity activity){
        return new androidx.lifecycle.ViewModelProvider(activity).get(MainActivity.WordState.class);
    }

    @Test public void resumedCloudConversionRechecksTheDocumentFolderLock()throws Exception{
        android.content.Context context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
        String path=new java.io.File(context.getFilesDir(),"cloud-guard-"+java.util.UUID.randomUUID()+".pdf").getPath();long folder;
        try(DocumentStore store=new DocumentStore(context)){
            android.content.ContentValues values=new android.content.ContentValues();values.put("name","Cloud guard fixture");folder=store.db().insertOrThrow("folders",null,values);
            store.registerDocument(path,"Cloud guard fixture",1);values.clear();values.put("folder_id",folder);store.db().update("docs",values,"path=?",new String[]{path});
        }
        context.getSharedPreferences("folder_locks",0).edit().putString("pin_"+folder,"test-only-invalid-hash").commit();
        try(androidx.test.core.app.ActivityScenario<MainActivity> scenario=androidx.test.core.app.ActivityScenario.launch(new android.content.Intent(context,MainActivity.class))){
            scenario.onActivity(a->{
                state(a).pendingCloud=new MainActivity.CloudRequest(path,"Cloud guard fixture","",null);
                try{java.lang.reflect.Method callback=MainActivity.class.getDeclaredMethod("finishGoogleUnlock",int.class);callback.setAccessible(true);callback.invoke(a,android.app.Activity.RESULT_OK);}
                catch(Exception error){throw new AssertionError(error);}
            });
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(UiText.get(R.string.ui_folder_pin))).check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(UiText.get(R.string.ui_cancel))).perform(androidx.test.espresso.action.ViewActions.click());
        }finally{
            context.getSharedPreferences("folder_locks",0).edit().remove("pin_"+folder).commit();
            try(DocumentStore store=new DocumentStore(context)){store.db().delete("docs","path=?",new String[]{path});store.db().delete("folders","id=?",new String[]{Long.toString(folder)});}
        }
    }

    @Test public void cloudKeyRequiresRecentDeviceAuthentication(){
        KeyGenParameterSpec spec=MainActivity.googleKeySpec("test-cloud-policy");
        assertTrue(spec.isUserAuthenticationRequired());
        assertEquals(300,spec.getUserAuthenticationValidityDurationSeconds());
        assertEquals(KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT,spec.getPurposes());
        assertArrayEquals(new String[]{KeyProperties.BLOCK_MODE_GCM},spec.getBlockModes());
        assertArrayEquals(new String[]{KeyProperties.ENCRYPTION_PADDING_NONE},spec.getEncryptionPaddings());
        if(android.os.Build.VERSION.SDK_INT>=30){
            assertEquals(KeyProperties.AUTH_DEVICE_CREDENTIAL|KeyProperties.AUTH_BIOMETRIC_STRONG,spec.getUserAuthenticationType());
        }
    }
}
