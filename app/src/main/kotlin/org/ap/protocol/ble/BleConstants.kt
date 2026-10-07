package org.ap.protocol.ble

import android.os.ParcelUuid
import antiproxy.core.protocol.PROOF_CHAR_UUID_STRING
import antiproxy.core.protocol.SERVICE_UUID_STRING
import antiproxy.core.protocol.SESSION_CHAR_UUID_STRING
import antiproxy.core.protocol.STATUS_CHAR_UUID_STRING
import java.util.UUID

val SERVICE_UUID: UUID = UUID.fromString(SERVICE_UUID_STRING)
val SERVICE_PARCEL_UUID: ParcelUuid = ParcelUuid(SERVICE_UUID)

val SESSION_CHAR_UUID: UUID = UUID.fromString(SESSION_CHAR_UUID_STRING)
val PROOF_CHAR_UUID: UUID = UUID.fromString(PROOF_CHAR_UUID_STRING)
val STATUS_CHAR_UUID: UUID = UUID.fromString(STATUS_CHAR_UUID_STRING)

val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

const val REQUESTED_MTU = 517
const val MIN_ATT_MTU = 23
const val ATT_HEADER_SIZE = 3

fun usablePayload(mtu: Int): Int = (mtu - ATT_HEADER_SIZE).coerceAtLeast(0)
