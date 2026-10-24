package com.paperflow.scanengine

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class OcrLanguageTest {
    @Test fun languageAliasesAndScriptsResolveToTheCorrectLocale() {
        assertEquals("el", OcrLanguage("ell").locale.language)
        assertEquals(Locale.GERMAN, OcrLanguage("deu").locale)
        assertEquals("Cyrl", OcrLanguage("aze_cyrl").locale.script)
        assertEquals("Cyrl", OcrLanguage("uzb_cyrl").locale.script)
        assertEquals("Latn", OcrLanguage("srp_latn").locale.script)
        assertEquals("vertical", OcrLanguage("jpn_vert").locale.variant)
        assertEquals("vertical", OcrLanguage("chi_sim_vert").locale.variant)
        assertEquals(Locale.SIMPLIFIED_CHINESE.country, OcrLanguage("chi_sim").locale.country)
        assertEquals("eng", OcrLanguage("eng").locale.language)
    }

    @Test fun everyListedLanguageHasAStableLocaleAndADisplayName() {
        assertTrue(OcrLanguage.AVAILABLE_LANGUAGE_CODES.isNotEmpty())
        assertEquals(OcrLanguage.AVAILABLE_LANGUAGE_CODES.size, OcrLanguage.AVAILABLE_LANGUAGE_CODES.toSet().size)
        for (code in OcrLanguage.AVAILABLE_LANGUAGE_CODES) {
            val language = OcrLanguage(code)
            assertFalse(code, language.locale.language.isBlank())
            assertFalse(code, language.displayName(Locale.ENGLISH).isBlank())
            assertSame(language.locale, language.locale)
        }
    }
}
