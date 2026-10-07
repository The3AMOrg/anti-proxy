package antiproxy.core.protocol

import kotlin.math.min

fun encodeFragment(fragment: Fragment): ByteArray {
    val data = ByteArray(FRAGMENT_HEADER_SIZE + fragment.payload.size)
    putUInt16BE(data, 0, fragment.transferId)
    data[2] = fragment.sequenceNumber
    data[3] = fragment.totalChunks
    putUInt16BE(data, 4, fragment.chunkLength)
    putBytes(data, 6, fragment.payload)
    return data
}

fun decodeFragment(data: ByteArray): Fragment {
    require(data.size >= FRAGMENT_HEADER_SIZE) { "Data too short for fragment header" }
    val transferId = getUInt16BE(data, 0)
    val sequenceNumber = data[2]
    val totalChunks = data[3]
    val chunkLength = getUInt16BE(data, 4)
    
    require(totalChunks.toInt() in 1..MAX_FRAGMENT_CHUNKS) { "totalChunks > MAX_FRAGMENT_CHUNKS" }
    require(sequenceNumber >= 0 && sequenceNumber < totalChunks) { "Invalid sequenceNumber" }
    require(data.size >= FRAGMENT_HEADER_SIZE + chunkLength.toInt()) { "Data too short for specified chunkLength" }
    
    require(chunkLength.toInt() <= MAX_FRAGMENT_TOTAL_LENGTH) { "chunkLength too large" }
    
    val payload = getBytes(data, FRAGMENT_HEADER_SIZE, chunkLength.toInt())
    
    return Fragment(
        transferId = transferId,
        sequenceNumber = sequenceNumber,
        totalChunks = totalChunks,
        chunkLength = chunkLength,
        payload = payload
    )
}

fun fragmentPayload(transferId: UShort, payload: ByteArray, maxChunkSize: Int): List<Fragment> {
    require(payload.size <= MAX_FRAGMENT_TOTAL_LENGTH) { "Payload exceeds MAX_FRAGMENT_TOTAL_LENGTH" }
    require(maxChunkSize > 0) { "maxChunkSize must be positive" }
    
    val totalChunksInt = (payload.size + maxChunkSize - 1) / maxChunkSize
    require(totalChunksInt <= MAX_FRAGMENT_CHUNKS) { "Payload requires too many chunks" }
    val totalChunks = totalChunksInt.toByte()
    
    val fragments = mutableListOf<Fragment>()
    var offset = 0
    var seq = 0
    
    while (offset < payload.size) {
        val len = min(maxChunkSize, payload.size - offset)
        val chunkPayload = payload.copyOfRange(offset, offset + len)
        fragments.add(Fragment(
            transferId = transferId,
            sequenceNumber = seq.toByte(),
            totalChunks = totalChunks,
            chunkLength = len.toUShort(),
            payload = chunkPayload
        ))
        offset += len
        seq++
    }
    
    return fragments
}
