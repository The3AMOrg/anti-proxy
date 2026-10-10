package org.ap.protocol.ble

import android.bluetooth.*
import android.os.Build
import antiproxy.core.fragmentation.Reassembler
import antiproxy.core.fragmentation.ReassemblyResult
import antiproxy.core.protocol.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

fun interface ProofReceivedCallback {
    fun onProofReceived(device: BluetoothDevice, envelopeData: ByteArray)
}

/**
 * High-performance, concurrent GATT server callback for the Authority.
 * Handles Session reads, Proof fragment writes, CCCD subscriptions, Status indications,
 * and actively cleans up connections to prevent hardware channel starvation.
 */
class GattServerCallback(
    private val onProofReceived: ProofReceivedCallback
) : BluetoothGattServerCallback() {

    @Volatile
    var sessionSignedBytes: ByteArray? = null

    var onServiceAddedListener: ((status: Int, service: BluetoothGattService) -> Unit)? = null

    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reassemblers = ConcurrentHashMap<String, Reassembler>()
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val connectionTimestamps = ConcurrentHashMap<String, Long>()

    @Volatile
    private var gattServer: BluetoothGattServer? = null

    @Volatile
    private var statusCharacteristic: BluetoothGattCharacteristic? = null

    private val indicationLock = Any()

    fun setGattServer(server: BluetoothGattServer, statusChar: BluetoothGattCharacteristic) {
        this.gattServer = server
        this.statusCharacteristic = statusChar
    }

    override fun onServiceAdded(status: Int, service: BluetoothGattService) {
        onServiceAddedListener?.invoke(status, service)
    }

    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            connectedDevices[device.address] = device
            connectionTimestamps[device.address] = System.currentTimeMillis()
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            connectedDevices.remove(device.address)
            connectionTimestamps.remove(device.address)
            reassemblers.remove(device.address)
        }
    }

    override fun onCharacteristicReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        characteristic: BluetoothGattCharacteristic
    ) {
        val server = gattServer ?: return
        if (characteristic.uuid == SESSION_CHAR_UUID) {
            val data = sessionSignedBytes
            if (data == null) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }
            if (offset >= data.size) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
                return
            }
            val chunk = data.copyOfRange(offset, data.size)
            server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, chunk)
        } else {
            server.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
        }
    }

    override fun onCharacteristicWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?
    ) {
        val server = gattServer ?: return
        if (characteristic.uuid != PROOF_CHAR_UUID || value == null) {
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            }
            return
        }

        if (value.size < FRAGMENT_HEADER_SIZE || value.size > usablePayload(REQUESTED_MTU)) {
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
            sendStatusIndication(device, StatusCode.MALFORMED_FRAME)
            return
        }

        val fragment = try {
            decodeFragment(value)
        } catch (_: Exception) {
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
            sendStatusIndication(device, StatusCode.MALFORMED_FRAME)
            return
        }

        val reassembler = reassemblers.getOrPut(device.address) {
            Reassembler(fragment.transferId)
        }

        val result = reassembler.addFragment(fragment)

        if (responseNeeded) {
            server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        when (result) {
            is ReassemblyResult.Complete -> {
                reassemblers.remove(device.address)
                // Offload crypto verification and SQLite write off the Bluetooth Binder thread
                callbackScope.launch {
                    onProofReceived.onProofReceived(device, result.payload)
                }
            }
            is ReassemblyResult.Error -> {
                reassemblers.remove(device.address)
                sendStatusIndication(device, StatusCode.MTU_OR_FRAGMENT_ERROR)
            }
            is ReassemblyResult.Incomplete -> {
                // Awaiting remaining fragments
            }
        }
    }

    override fun onDescriptorWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?
    ) {
        val server = gattServer ?: return
        if (descriptor.uuid == CCCD_UUID) {
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        } else {
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            }
        }
    }

    fun sendStatusIndication(device: BluetoothDevice, status: StatusCode): Boolean {
        val server = gattServer ?: return false
        val char = statusCharacteristic ?: return false
        val statusBytes = byteArrayOf(status.code)
        return synchronized(indicationLock) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val res = server.notifyCharacteristicChanged(device, char, true, statusBytes)
                res == BluetoothStatusCodes.SUCCESS
            } else {
                char.value = statusBytes
                server.notifyCharacteristicChanged(device, char, true)
            }
        }
    }

    fun sendStatusIndication(deviceAddress: String, status: StatusCode): Boolean {
        val device = connectedDevices[deviceAddress] ?: return false
        return sendStatusIndication(device, status)
    }

    fun disconnectClient(device: BluetoothDevice) {
        try {
            gattServer?.cancelConnection(device)
        } catch (_: Exception) { }
        connectedDevices.remove(device.address)
        connectionTimestamps.remove(device.address)
        reassemblers.remove(device.address)
    }

    fun disconnectClient(deviceAddress: String) {
        val device = connectedDevices[deviceAddress]
        if (device != null) {
            disconnectClient(device)
        } else {
            connectedDevices.remove(deviceAddress)
            connectionTimestamps.remove(deviceAddress)
            reassemblers.remove(deviceAddress)
        }
    }

    fun cleanupStaleConnections(timeoutMs: Long = 5_000L) {
        val now = System.currentTimeMillis()
        connectedDevices.forEach { (address, device) ->
            val connectedAt = connectionTimestamps[address] ?: now
            if (now - connectedAt > timeoutMs) {
                disconnectClient(device)
            }
        }
        cleanupStaleReassemblers()
    }

    fun cleanupStaleReassemblers() {
        val stale = reassemblers.entries.filter { it.value.isTimedOut() }.map { it.key }
        stale.forEach { reassemblers.remove(it) }
    }

    fun close() {
        callbackScope.cancel()
        connectedDevices.clear()
        connectionTimestamps.clear()
        reassemblers.clear()
    }
}
