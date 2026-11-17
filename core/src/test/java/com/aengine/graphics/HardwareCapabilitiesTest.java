package com.aengine.graphics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Reading the CPU name from the Windows registry query's output. */
class HardwareCapabilitiesTest {

    @Test
    void theProcessorNameIsReadFromTheRegistryLine() {
        // KNOWN_ISSUES #15
        assertEquals("AMD Ryzen 7 5800X 8-Core Processor",
            HardwareCapabilities.processorNameFromReg(
                "    ProcessorNameString    REG_SZ    AMD Ryzen 7 5800X 8-Core Processor             "));
        assertNull(HardwareCapabilities.processorNameFromReg("HKEY_LOCAL_MACHINE\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0"));
        assertNull(HardwareCapabilities.processorNameFromReg(""));
    }
}
