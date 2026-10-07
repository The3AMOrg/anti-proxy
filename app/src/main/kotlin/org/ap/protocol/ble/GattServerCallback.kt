package org.ap.protocol.ble

import android.bluetooth.*
import antiproxy.core.fragmentation.Reassembler
import antiproxy.core.fragmentation.ReassemblyResult
import antiproxy.core.protocol.*
import java.util.concurrent.ConcurrentHashMap

fun interface ProofReceivedCallback {
    fun onProofReceived(device: BluetoothDevice, envelopeData: ByteArray)
}

/**
 * High-performance GATT server callback for the Authority.
 * Handles Session reads, Proof fragment writes, CCCD subscriptions, and Status indications.
 */
class GattServerCallback(
    private val onProofReceived: ProofReceivedCallback
) : BluetoothGattServerCallback() {

    @Volatile
    var sessionSignedBytes: ByteArray? = null

    private val reassemblers = ConcurrentHashMap<String, Reassembler>()
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()

    @Volatile
    private var gattServer: BluetoothGattServer? = null

    @Volatile
    private var statusCharacteristic: BluetoothGattCharacteristic? = null

    fun setGattServer(server: BluetoothGattServer, statusChar: BluetoothGattCharacteristic) {
        this.gattServer = server
        this.statusCharacteristic = statusChar
    }

    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            connectedDevices[device.address] = device
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            connectedDevices.remove(device.address)
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
                onProofReceived.onProofReceived(device, result.payload)
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

    fun sendStatusIndication(device: BluetoothDevice, status: StatusCode) {
        val server = gattServer ?: return
        val char = statusCharacteristic ?: return
        char.value = byteArrayOf(status.code)
        server.notifyCharacteristicChanged(device, char, true)
    }

    fun sendStatusIndication(deviceAddress: String, status: StatusCode) {
        val device = connectedDevices[deviceAddress] ?: return
        sendStatusIndication(device, status)
    }

    fun cleanupStaleReassemblers() {
        val stale = reassemblers.entries.filter { it.value.isTimedOut() }.map { it.key }
        stale.forEach { reassemblers.remove(it) }
    }
}
