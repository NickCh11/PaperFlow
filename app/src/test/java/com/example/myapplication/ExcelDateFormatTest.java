package com.example.myapplication;

import org.junit.Test;
import static org.junit.Assert.*;

public class ExcelDateFormatTest {
    @Test public void builtInTimesKeepMinutesAndSeconds() {
        assertEquals("12:30 PM", ExcelDateFormat.render(0.520833333333,18,"",false));
        assertEquals("12:30:15", ExcelDateFormat.render(45000.521006944444,21,"",false));
        assertEquals("30:15", ExcelDateFormat.render(0.021006944444,45,"",false));
    }
    @Test public void elapsedHoursDoNotWrapAtMidnight() {
        assertEquals("36:00:00", ExcelDateFormat.render(1.5,46,"",false));
    }
    @Test public void epochAndExcelLeapDayCompatibility() {
        assertEquals("1/1/04", ExcelDateFormat.render(0,14,"",true));
        assertEquals("2/28/00", ExcelDateFormat.render(59,14,"",false));
        assertEquals("3/1/00", ExcelDateFormat.render(61,14,"",false));
    }
    @Test public void customFormatsDistinguishMonthAndMinute() {
        assertEquals("2023-03-15 12:30:00", ExcelDateFormat.render(45000.520833333333,0,"yyyy-mm-dd hh:mm:ss",false));
        assertEquals("12:00:00.125", ExcelDateFormat.render(0.5+0.125/86400,0,"hh:mm:ss.000",false));
    }
    @Test public void literalsAreNotDateTokens() {
        assertNull(ExcelDateFormat.render(12,0,"0 \"days\"",false));
        assertEquals("12 hours", ExcelDateFormat.render(0.5,0,"h\" hours\"",false));
    }
    @Test public void unsupportedAndInvalidValuesDoNotBecomeDates() {
        assertNull(ExcelDateFormat.render(12,0,"",false));
        for(double value:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY,2958466})
            assertNull(ExcelDateFormat.render(value,14,"",false));
    }
}
