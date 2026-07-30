package per.jau.chargelog.service

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingSessionPolicyTest {
    @Test
    fun stoppedRecordingStartsANewSession() {
        assertEquals(200L, RecordingSessionPolicy.sessionIdForStart(false, true, 100L, 200L))
    }

    @Test
    fun repeatedStartKeepsTheCurrentSession() {
        assertEquals(100L, RecordingSessionPolicy.sessionIdForStart(true, false, 100L, 200L))
    }

    @Test
    fun missingSessionIdIsRepairedEvenIfRecordingFlagIsSet() {
        assertEquals(200L, RecordingSessionPolicy.sessionIdForStart(true, false, 0L, 200L))
    }
}