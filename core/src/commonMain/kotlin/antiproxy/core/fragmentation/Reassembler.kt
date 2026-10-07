package antiproxy.core.fragmentation

import antiproxy.core.protocol.*

sealed class ReassemblyResult {
    data class Complete(val payload: ByteArray) : ReassemblyResult()
    object Incomplete : ReassemblyResult()  // More fragments needed
    data class Error(val reason: String) : ReassemblyResult()
}

/**
 * Reassembles fragmented proof transfers.
 * Validates: duplicate sequences, missing chunks, inconsistent total count,
 * excessive total length, transfer timeout.
 */
class Reassembler(private val expectedTransferId: UShort) {
    private val fragments = mutableMapOf<Int, ByteArray>()  // seq -> payload
    private var expectedTotal: Int = -1
    private var totalReceivedBytes: Int = 0
    private val createdAtMs: Long = System.currentTimeMillis()

    fun addFragment(fragment: Fragment): ReassemblyResult {
        // Check transfer ID
        if (fragment.transferId != expectedTransferId) {
            return ReassemblyResult.Error("Wrong transfer ID")
        }
        // Check timeout
        if (System.currentTimeMillis() - createdAtMs > FRAGMENT_TRANSFER_TIMEOUT_MS) {
            return ReassemblyResult.Error("Transfer timeout")
        }
        val seq = fragment.sequenceNumber.toInt() and 0xFF
        val total = fragment.totalChunks.toInt() and 0xFF
        // Validate total chunks
        if (total < 1 || total > MAX_FRAGMENT_CHUNKS) {
            return ReassemblyResult.Error("Invalid total chunks: $total")
        }
        // Check consistency of totalChunks across fragments
        if (expectedTotal == -1) {
            expectedTotal = total
        } else if (total != expectedTotal) {
            return ReassemblyResult.Error("Inconsistent total chunks")
        }
        // Validate sequence number
        if (seq < 0 || seq >= total) {
            return ReassemblyResult.Error("Sequence number out of range")
        }
        // Reject duplicate
        if (fragments.containsKey(seq)) {
            return ReassemblyResult.Error("Duplicate sequence number: $seq")
        }
        // Check total accumulated length
        val newTotal = totalReceivedBytes + fragment.payload.size
        if (newTotal > MAX_FRAGMENT_TOTAL_LENGTH) {
            return ReassemblyResult.Error("Excessive total length")
        }
        fragments[seq] = fragment.payload.copyOf()
        totalReceivedBytes = newTotal
        // Check if complete
        if (fragments.size == expectedTotal) {
            val assembled = ByteArray(totalReceivedBytes)
            var offset = 0
            for (i in 0 until expectedTotal) {
                val chunk = fragments[i] ?: return ReassemblyResult.Error("Missing chunk $i")
                chunk.copyInto(assembled, offset)
                offset += chunk.size
            }
            return ReassemblyResult.Complete(assembled)
        }
        return ReassemblyResult.Incomplete
    }

    fun isTimedOut(): Boolean =
        System.currentTimeMillis() - createdAtMs > FRAGMENT_TRANSFER_TIMEOUT_MS
}
