package com.paperflow.translation;

import org.junit.Test;
import static org.junit.Assert.*;

public class OfflineRuntimeWorkerPolicyTest {
    @Test public void lowMemoryDevicesUseOneWorkerAndLargerDevicesKeepABoundedPool() {
        long gibibyte = 1024L * 1024 * 1024;
        for (long memory : new long[]{-1, 0, gibibyte, 2 * gibibyte, 3 * gibibyte})
            assertEquals(1, OfflineRuntime.workerCount(false, memory));
        for (long memory : new long[]{0, gibibyte, 8 * gibibyte, Long.MAX_VALUE})
            assertEquals(1, OfflineRuntime.workerCount(true, memory));
        for (long memory : new long[]{3 * gibibyte + 1, 4 * gibibyte, 16 * gibibyte, Long.MAX_VALUE})
            assertEquals(2, OfflineRuntime.workerCount(false, memory));
    }
}
