package com.teachermovies.torrent.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-checks core-model's `DownloadState.canTransitionTo` table against [DownloadStateMapper]:
 * any move the engine can report between two raw statuses that does not lose metadata must be
 * allowed by the table (the `Queued -> Verifying` rejection found in #52 would fail here).
 *
 * "Does not lose metadata" is read the way [DownloadStateMapper] reads it: a status whose phase is
 * still [RawPhase.DownloadingMetadata] maps to `FetchingMetadata` even if its `hasMetadata` flag is
 * set, so it counts as metadata not known. Without that, the pair `(Downloading, hasMetadata) ->
 * (DownloadingMetadata, hasMetadata)` -- which libtorrent does not produce -- would be reported as
 * the `Downloading -> FetchingMetadata` move the table deliberately rejects.
 */
class DownloadStateTransitionsTest {
    private val booleans = listOf(false, true)

    /** Every combination of [RawStatus]'s enum and boolean fields. */
    private val allStatuses: List<RawStatus> =
        RawPhase.entries.flatMap { phase ->
            booleans.flatMap { paused ->
                booleans.flatMap { autoManagedQueued ->
                    booleans.flatMap { hasMetadata ->
                        booleans.flatMap { hasError ->
                            booleans.map { isFinished ->
                                RawStatus(phase, paused, autoManagedQueued, hasMetadata, hasError, isFinished)
                            }
                        }
                    }
                }
            }
        }

    /** Whether [DownloadStateMapper] treats [raw] as having its metadata. */
    private fun metadataKnown(raw: RawStatus): Boolean = raw.hasMetadata && raw.phase != RawPhase.DownloadingMetadata

    @Test
    fun enumeratesEveryRawStatus() {
        assertEquals(RawPhase.entries.size * 32, allStatuses.toSet().size)
    }

    @Test
    fun everyMetadataPreservingMoveIsInTheTransitionTable() {
        val rejected =
            allStatuses.flatMap { from ->
                allStatuses
                    .filter { to -> !metadataKnown(from) || metadataKnown(to) }
                    .mapNotNull { to ->
                        val fromState = DownloadStateMapper.map(from)
                        val toState = DownloadStateMapper.map(to)
                        if (fromState.canTransitionTo(toState)) null else "$fromState -> $toState ($from -> $to)"
                    }
            }
        assertTrue("Moves the table rejects:\n${rejected.take(20).joinToString("\n")}", rejected.isEmpty())
    }
}
