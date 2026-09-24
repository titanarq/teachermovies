package com.teachermovies.http

/**
 * A [SubtitleStore] test double: by default it "succeeds" without touching disk, recording every
 * call it saw. [nextResult] lets a test force a failure.
 */
class FakeSubtitleStore(
    var nextResult: (torrentId: String, fileName: String, bytes: ByteArray) -> Result<String> =
        { torrentId, fileName, _ -> Result.success("/fake/$torrentId/subs/$fileName") },
) : SubtitleStore {
    val recordedCalls = mutableListOf<String>()

    override fun save(torrentId: String, fileName: String, bytes: ByteArray): Result<String> {
        recordedCalls += "save($torrentId,$fileName,${bytes.size} bytes)"
        return nextResult(torrentId, fileName, bytes)
    }
}
