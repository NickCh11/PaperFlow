package com.example.myapplication;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.junit.Test;
import static org.junit.Assert.*;

public class CloudCredentialsTest {
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
