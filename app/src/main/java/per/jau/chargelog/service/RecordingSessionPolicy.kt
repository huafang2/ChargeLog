package per.jau.chargelog.service

internal object RecordingSessionPolicy {
    fun sessionIdForStart(
        wasRecording: Boolean,
        forceNew: Boolean,
        existingSessionId: Long,
        now: Long
    ): Long = if (!wasRecording || forceNew || existingSessionId <= 0L) {
        now
    } else {
        existingSessionId
    }

    fun shouldPersistSample(
        isRecording: Boolean,
        activeSessionId: Long,
        sampleSessionId: Long
    ): Boolean = isRecording && activeSessionId == sampleSessionId
}
