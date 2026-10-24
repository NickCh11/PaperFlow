package com.example.myapplication;

import android.app.KeyguardManager;
import android.app.Instrumentation;
import android.content.*;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.security.keystore.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.*;
import javax.crypto.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;

/** Uses a temporary PIN only on an unsecured disposable emulator; never changes a user's lock. */
public class CloudKeyStoreRegressionTest {
    private static final String FIXTURE_PIN="753191";
    private static final String CURRENT="paperflow_google_translation_auth_v2",LEGACY="paperflow_google_translation";
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private String shell(String command)throws Exception{
        try(ParcelFileDescriptor descriptor=inst.getUiAutomation().executeShellCommand(command);
            java.io.InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);return bytes.toString("UTF-8");
        }
    }
    private Object call(MainActivity activity,String name,Class<?>[] types,Object... arguments)throws Exception{
        java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(activity,arguments);}catch(java.lang.reflect.InvocationTargetException error){throw (Exception)error.getCause();}
    }
    private void press(String label){
        onView(withText(label)).perform(new androidx.test.espresso.ViewAction(){
            @Override public org.hamcrest.Matcher<android.view.View> getConstraints(){return isAssignableFrom(android.widget.Button.class);}
            @Override public String getDescription(){return "Activate the dialog button after credential fixture setup";}
            @Override public void perform(androidx.test.espresso.UiController controller,android.view.View view){assertTrue(view.performClick());controller.loopMainThreadUntilIdle();}
        });
    }
    private boolean containsVisibleLabel(android.view.View view,String label){
        if(!view.isShown())return false;
        if(view instanceof android.widget.TextView){
            android.widget.TextView text=(android.widget.TextView)view;
            if(label.contentEquals(text.getText())||label.contentEquals(text.getHint()==null?"":text.getHint()))return true;
        }
        if(view instanceof android.view.ViewGroup){
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)if(containsVisibleLabel(group.getChildAt(i),label))return true;
        }
        return false;
    }
    private void awaitDialog(int label){
        String expected=UiText.get(label);
        UiTestWait.until(()->{
            for(android.view.View root:android.view.inspector.WindowInspector.getGlobalWindowViews()){
                android.view.View button=root.findViewById(android.R.id.button1);
                if(root.isAttachedToWindow()&&button!=null&&button.isShown()&&button.isEnabled()&&containsVisibleLabel(root,expected))return true;
            }
            return false;
        },"Cloud dialog was not ready: "+expected);
    }

    @Test public void authenticatedStorageRoundTripsAndMigratesLegacyCiphertext()throws Exception{
        Context context=inst.getTargetContext();
        assumeTrue("Credential mutation is confined to a disposable emulator",Build.FINGERPRINT.contains("generic")||Build.FINGERPRINT.contains("sdk_gphone"));
        assumeTrue("Never replace an existing screen lock",!context.getSystemService(KeyguardManager.class).isDeviceSecure());
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        SharedPreferences preferences=context.getSharedPreferences("google_translation",0);
        assumeTrue("Never replace existing Cloud credentials",!preferences.contains("value")&&!store.containsAlias(CURRENT)&&!store.containsAlias(LEGACY));
        ExecutorService worker=Executors.newSingleThreadExecutor();boolean pinCreated=false;
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(new Intent(context,MainActivity.class))){
            java.util.concurrent.atomic.AtomicReference<MainActivity> activity=new java.util.concurrent.atomic.AtomicReference<>();scenario.onActivity(activity::set);
            assertArrayEquals(new String[]{"","en"},(String[])worker.submit(()->call(activity.get(),"readGoogleSettings",new Class<?>[0])).get(5,TimeUnit.SECONDS));
            String result=shell("locksettings set-pin "+FIXTURE_PIN);pinCreated=context.getSystemService(KeyguardManager.class).isDeviceSecure();assertTrue(result,pinCreated);
            assertTrue(shell("locksettings verify --old "+FIXTURE_PIN).contains("verified"));
            scenario.onActivity(a->{
                new androidx.lifecycle.ViewModelProvider(a).get(MainActivity.WordState.class).pendingCloud=new MainActivity.CloudRequest("/fixture/report.pdf","Fixture","",null);
                try{call(a,"finishGoogleUnlock",new Class<?>[]{int.class},android.app.Activity.RESULT_OK);}catch(Exception error){throw new AssertionError(error);}
            });
            awaitDialog(R.string.ui_google_cloud_api_key);
            press(UiText.get(R.string.ui_save));
            onView(withClassName(org.hamcrest.Matchers.equalTo(android.widget.EditText.class.getName()))).check(matches(hasErrorText(UiText.get(R.string.ui_enter_your_google_cloud_api_key)))).perform(replaceText("fixture-api-value"),closeSoftKeyboard());
            press(UiText.get(R.string.ui_save));
            UiTestWait.until(()->preferences.getInt("key_version",0)==2,"Authenticated settings were not saved");
            awaitDialog(R.string.ui_upload_pages_to_google_cloud);
            onView(withText(UiText.get(R.string.ui_upload_pages_to_google_cloud))).check(matches(isDisplayed()));
            press(UiText.get(R.string.ui_cancel));
            assertArrayEquals(new String[]{"fixture-api-value","en"},(String[])worker.submit(()->call(activity.get(),"readGoogleSettings",new Class<?>[0])).get(5,TimeUnit.SECONDS));
            SecretKey key=(SecretKey)store.getKey(CURRENT,null);
            KeyInfo policy=(KeyInfo)SecretKeyFactory.getInstance(key.getAlgorithm(),"AndroidKeyStore").getKeySpec(key,KeyInfo.class);
            assertTrue(policy.isUserAuthenticationRequired());assertEquals(300,policy.getUserAuthenticationValidityDurationSeconds());
            assertFalse(preferences.getString("value","").contains("fixture-api-value"));
            seedLegacy(preferences);
            assertArrayEquals(new String[]{"legacy-fixture-value","en"},(String[])worker.submit(()->call(activity.get(),"readGoogleSettings",new Class<?>[0])).get(10,TimeUnit.SECONDS));
            assertEquals(2,preferences.getInt("key_version",0));assertFalse(store.containsAlias(LEGACY));
            assertArrayEquals(new String[]{"legacy-fixture-value","en"},(String[])worker.submit(()->call(activity.get(),"readGoogleSettings",new Class<?>[0])).get(5,TimeUnit.SECONDS));
        }finally{
            try{worker.shutdownNow();preferences.edit().clear().commit();store.deleteEntry(CURRENT);store.deleteEntry(LEGACY);}
            finally{if(pinCreated)assertTrue(shell("locksettings clear --old "+FIXTURE_PIN).contains("cleared"));}
        }
    }

    @SuppressWarnings("java:S6288") // Reproduces the unauthenticated legacy format solely for migration testing.
    private void seedLegacy(SharedPreferences preferences)throws Exception{
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(LEGACY,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,generator.generateKey());
        byte[] value=cipher.doFinal("legacy-fixture-value\nen".getBytes(StandardCharsets.UTF_8));
        assertTrue(preferences.edit().putString("iv",android.util.Base64.encodeToString(cipher.getIV(),android.util.Base64.NO_WRAP)).putString("value",android.util.Base64.encodeToString(value,android.util.Base64.NO_WRAP)).putInt("key_version",1).commit());
    }
}
