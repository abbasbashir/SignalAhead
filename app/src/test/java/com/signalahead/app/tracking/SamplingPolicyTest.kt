package com.signalahead.app.tracking
import org.junit.Assert.*
import org.junit.Test
class SamplingPolicyTest {
    @Test fun batteryOverridesFastMode(){ assertEquals(90L,SamplingPolicy.seconds("Responsive",true,false,false,true)) }
    @Test fun knownPlacesUseLessFrequentLocation(){
        assertEquals(60L,SamplingPolicy.seconds("Balanced",false,false,true,false))
        assertEquals(15L,SamplingPolicy.seconds("Balanced",false,false,true,true))
    }
    @Test fun confirmedPlacesWaitForRefreshWindow(){
        assertFalse(SamplingPolicy.recheck(1000L,2000L,24))
        assertTrue(SamplingPolicy.recheck(1000L,86_401_000L,24))
    }
    @Test fun onlyRepeatJourneysConfirm(){
        assertEquals("UNCONFIRMED",SamplingPolicy.status(1))
        assertEquals("POSSIBLE",SamplingPolicy.status(2))
        assertEquals("CONFIRMED",SamplingPolicy.status(3))
        assertNull(SamplingPolicy.category(null))
        assertNull(SamplingPolicy.category(2))
    }
}
