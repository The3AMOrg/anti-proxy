package org.ap.protocol.ble

import antiproxy.core.protocol.FRAGMENT_HEADER_SIZE

object MtuNegotiator {

    fun computeMaxPayloadPerChunk(negotiatedMtu: Int): Int {
        val effectiveMtu = negotiatedMtu.coerceAtLeast(MIN_ATT_MTU)
        val attPayload = effectiveMtu - ATT_HEADER_SIZE
        return (attPayload - FRAGMENT_HEADER_SIZE).coerceAtLeast(1)
    }

    fun maxChunkSize(negotiatedMtu: Int): Int = computeMaxPayloadPerChunk(negotiatedMtu)

    fun fitsWithoutFragmentation(dataLength: Int, negotiatedMtu: Int): Boolean {
        val attPayload = negotiatedMtu - ATT_HEADER_SIZE
        return dataLength <= attPayload
    }
}
