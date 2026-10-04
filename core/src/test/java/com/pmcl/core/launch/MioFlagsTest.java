package com.pmcl.core.launch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MioFlagsTest {

    @Test
    void extraJvmFlagsStayNarrow() {
        assertEquals(List.of("-XX:+AlwaysPreTouch"), MioFlags.buildPretouch());
        assertEquals(List.of("-XX:+UseStringDeduplication"), MioFlags.buildStringDedup());
        assertEquals(List.of(
                "-XX:G1PeriodicGCInterval=30000",
                "-XX:G1PeriodicGCSystemLoadThreshold=2"), MioFlags.buildIdleGc());
    }

    @Test
    void discreteGpuEnvFollowsTheHost() {
        assertTrue(MioFlags.discreteGpuEnv("Mac OS X").isEmpty());
        assertEquals("SHIM_MCCOMPAT", MioFlags.discreteGpuEnv("Windows 11").get(0)[0]);
        List<String[]> linux = MioFlags.discreteGpuEnv("Linux");
        assertEquals("DRI_PRIME", linux.get(0)[0]);
        assertEquals("1", linux.get(0)[1]);
        assertEquals("__NV_PRIME_RENDER_OFFLOAD", linux.get(1)[0]);
    }
}
