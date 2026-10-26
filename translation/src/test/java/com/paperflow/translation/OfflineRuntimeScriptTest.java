package com.paperflow.translation;

import org.junit.Test;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

/** Validate our recognizer routing against Unicode characters, without model inference. */
public class OfflineRuntimeScriptTest {
    @Test public void scriptRoutingAcceptsItsWritingSystemAndRejectsUnrelatedText()throws Exception{
        Method matches=OfflineRuntime.class.getDeclaredMethod("matchesScript",String.class,Character.UnicodeScript.class);
        matches.setAccessible(true);
        String[][] examples={
            {"Latn","A"},{"Grek","Ω"},{"Cyrl","Ж"},{"Arab","ش"},{"Hebr","א"},
            {"Deva","अ"},{"Beng","অ"},{"Geor","ა"},{"Gujr","અ"},{"Hang","한"},
            {"Hans","漢"},{"Hant","漢"},{"Jpan","漢あカ"},{"Knda","ಕ"},{"Mlym","മ"},
            {"Taml","அ"},{"Telu","అ"},{"Thai","ก"}
        };
        for(String[] example:examples){
            for(int cp:example[1].codePoints().toArray())
                assertEquals(example[0]+" must route "+new String(Character.toChars(cp)),true,matches.invoke(null,example[0],Character.UnicodeScript.of(cp)));
            assertEquals(example[0]+" must reject punctuation",false,matches.invoke(null,example[0],Character.UnicodeScript.of('.')));
            int unrelated=example[0].equals("Latn")?'Ω':'A';
            assertEquals(example[0]+" must reject another script",false,matches.invoke(null,example[0],Character.UnicodeScript.of(unrelated)));
        }
        assertEquals(false,matches.invoke(null,"Jpan",Character.UnicodeScript.HANGUL));
        assertEquals(false,matches.invoke(null,"unknown",Character.UnicodeScript.LATIN));
    }
}
