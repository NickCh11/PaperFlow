package com.example.myapplication;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhotoLanguageDetectorTest {
    @Test public void distinctiveArabicScriptsResolveAnUncertainOcrDetection(){
        assertEquals("ur",PhotoLanguageDetector.recoverScriptEvidence("","کے لیے گئے"));
        assertEquals("ug",PhotoLanguageDetector.recoverScriptEvidence("","ھەسەن ھۈسەن سۇ"));
        assertEquals("",PhotoLanguageDetector.recoverScriptEvidence("","قوس قزح ظاهرة جوية"));
        assertEquals("",PhotoLanguageDetector.recoverScriptEvidence("","ەڭ"));
        assertEquals("",PhotoLanguageDetector.recoverScriptEvidence("","ےے"));
    }
    @Test public void azeriRecoveryNeedsMoreThanAForeignLetterInTurkish(){
        assertEquals("az",PhotoLanguageDetector.recoverScriptEvidence("tr","Göy qursagi neticesinde yaranan hadisedir."));
        assertEquals("tr",PhotoLanguageDetector.recoverScriptEvidence("tr","Bu belge Qatar şirketi için hazırlanmıştır."));
        assertEquals("en",PhotoLanguageDetector.recoverScriptEvidence("en","This document describes Azerbaijan."));
        assertEquals("az",PhotoLanguageDetector.recoverScriptEvidence("tr","Azərbaycan"));
        assertEquals("tr",PhotoLanguageDetector.recoverScriptEvidence("tr","Merhaba dünya"));
        assertEquals("tr",PhotoLanguageDetector.recoverScriptEvidence("tr","Qatar yarananx"));
    }
}
