package org.ap.protocol.ble

import android.bluetooth.*
import android.content.Context
import antiproxy.core.protocol.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream

/**
 * High-performance student-side GATT client handling sequential BLE GATT operations.
 * Requests CONNECTION_PRIORITY_HIGH immediately upon connection to minimize latency.
 */
class StudentGattClient(private val context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var gatt: BluetoothGatt? = null
    private var negotiatedMtu: Int = MIN_ATT_MTU
    private val opMutex = Mutex()

    private var connectDeferred: CompletableDeferred<Boolean>? = null
    private var servicesDeferred: CompletableDeferred<Boolean>? = null
    private var mtuDeferred: CompletableDeferred<Int>? = null
    private var readDeferred: CompletableDeferred<ByteArray>? = null
    private var writeDeferred: CompletableDeferred<Boolean>? = null
    private var descriptorDeferred: CompletableDeferred<Boolean>? = null
    private var rssiDeferred: CompletableDeferred<Int>? = null
    // Set from coroutines, completed from the GATT binder thread
    @Volatile private var statusDeferred: CompletableDeferred<StatusCode>? = null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                // Optimize connection interval immediately
                try {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                } catch (_: Exception) { }
                connectDeferred?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                connectDeferred?.complete(false)
                servicesDeferred?.complete(false)
                readDeferred?.completeExceptionally(IllegalStateException("Disconnected with status: $status"))
                writeDeferred?.complete(false)
                descriptorDeferred?.complete(false)
                rssiDeferred?.completeExceptionally(IllegalStateException("Disconnected during RSSI read"))
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            servicesDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                mtuDeferred?.complete(mtu)
            } else {
                mtuDeferred?.complete(MIN_ATT_MTU)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val value = characteristic.value ?: ByteArray(0)
                readDeferred?.complete(value)
            } else {
                readDeferred?.completeExceptionally(IllegalStateException("Read failed with status: $status"))
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            descriptorDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                rssiDeferred?.complete(rssi)
            } else {
                rssiDeferred?.completeExceptionally(IllegalStateException("RSSI read failed: $status"))
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == STATUS_CHAR_UUID) {
                val codeByte = characteristic.value?.firstOrNull()
                val code = if (codeByte != null) StatusCode.fromByte(codeByte) else null
                statusDeferred?.complete(code ?: StatusCode.MALFORMED_FRAME)
            }
        }
    }

    suspend fun connect(deviceAddress: String, timeoutMs: Long = 8_000L): Boolean = opMutex.withLock {
        val adapter = bluetoothManager.adapter ?: return false
        val device = try { adapter.getRemoteDevice(deviceAddress) } catch (_: Exception) { return false }

        val deferred = CompletableDeferred<Boolean>()
        connectDeferred = deferred

        val g = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        this.gatt = g

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: Exception) {
            disconnect()
            false
        } finally {
            connectDeferred = null
        }
    }

    suspend fun discoverServices(timeoutMs: Long = 4_000L): Boolean = opMutex.withLock {
        val g = gatt ?: return false
        val deferred = CompletableDeferred<Boolean>()
        servicesDeferred = deferred
        if (!g.discoverServices()) return false

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: Exception) {
            false
        } finally {
            servicesDeferred = null
        }
    }

    suspend fun requestMtu(timeoutMs: Long = 2_000L): Int = opMutex.withLock {
        val g = gatt ?: return MIN_ATT_MTU
        val deferred = CompletableDeferred<Int>()
        mtuDeferred = deferred
        if (!g.requestMtu(REQUESTED_MTU)) {
            return MIN_ATT_MTU
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: Exception) {
            MIN_ATT_MTU
        } finally {
            mtuDeferred = null
        }
    }

    suspend fun readSession(timeoutMs: Long = 4_000L): ByteArray = opMutex.withLock {
        val g = gatt ?: error("Not connected")
        val service = g.getService(SERVICE_UUID) ?: error("AntiProxy service not found")
        val sessionChar = service.getCharacteristic(SESSION_CHAR_UUID) ?: error("Session char not found")

        val deferred = CompletableDeferred<ByteArray>()
        readDeferred = deferred
        check(g.readCharacteristic(sessionChar)) { "Failed to initiate session read" }

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            readDeferred = null
        }
    }

    suspend fun enableStatusIndications(timeoutMs: Long = 3_000L): Boolean = opMutex.withLock {
        val g = gatt ?: return false
        val service = g.getService(SERVICE_UUID) ?: return false
        val statusChar = service.getCharacteristic(STATUS_CHAR_UUID) ?: return false

        check(g.setCharacteristicNotification(statusChar, true)) { "Failed to enable notification locally" }

        val descriptor = statusChar.getDescriptor(CCCD_UUID) ?: return false
        descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE

        val deferred = CompletableDeferred<Boolean>()
        descriptorDeferred = deferred
        check(g.writeDescriptor(descriptor)) { "Failed to write CCCD descriptor" }

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: Exception) {
            false
        } finally {
            descriptorDeferred = null
        }
    }

    suspend fun writeProofEnvelope(
        envelopeBytes: ByteArray,
        transferId: UShort = kotlin.random.Random.nextInt(1, 65535).toUShort(),
        timeoutMs: Long = 8_000L
    ) = opMutex.withLock {
        val g = gatt ?: error("Not connected")
        val service = g.getService(SERVICE_UUID) ?: error("Service not found")
        val proofChar = service.getCharacteristic(PROOF_CHAR_UUID) ?: error("Proof characteristic not found")
        proofChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        // Arm before sending: the authority can answer before the last write callback
        // returns, and an indication with nothing waiting for it is lost.
        statusDeferred = CompletableDeferred()

        val maxChunk = MtuNegotiator.maxChunkSize(negotiatedMtu)
        val fragments = fragmentPayload(transferId, envelopeBytes, maxChunk)

        withTimeout(timeoutMs) {
            for (fragment in fragments) {
                val wireBytes = encodeFragment(fragment)
                proofChar.value = wireBytes

                val deferred = CompletableDeferred<Boolean>()
                writeDeferred = deferred
                check(g.writeCharacteristic(proofChar)) { "Failed to initiate fragment write" }

                val success = deferred.await()
                writeDeferred = null
                check(success) { "Fragment write failed on sequence: ${fragment.sequenceNumber}" }
            }
        }
    }

    suspend fun awaitStatusIndication(timeoutMs: Long = 8_000L): StatusCode {
        val deferred = statusDeferred ?: CompletableDeferred<StatusCode>().also { statusDeferred = it }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            statusDeferred = null
        }
    }

    suspend fun readRssi(timeoutMs: Long = 2_000L): Int = opMutex.withLock {
        val g = gatt ?: error("Not connected")
        val deferred = CompletableDeferred<Int>()
        rssiDeferred = deferred
        check(g.readRemoteRssi()) { "Failed to initiate RSSI read" }

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            rssiDeferred = null
        }
    }

    fun disconnect() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) { }
        gatt = null
    }
}
