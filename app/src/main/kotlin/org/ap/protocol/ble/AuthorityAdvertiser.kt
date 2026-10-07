package org.ap.protocol.ble

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import antiproxy.core.protocol.StatusCode

/**
 * Authority BLE advertising and GATT server manager.
 * Hosts the AntiProxy service with Session (Read), Proof (Write), and Status (Indicate) characteristics.
 * Advertises with service UUID for discovery with high power and low latency.
 */
class AuthorityAdvertiser(private val context: Context) {

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: GattServerCallback? = null

    @Volatile
    private var advertising: Boolean = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
        }
    }

    fun start(sessionSignedBytes: ByteArray, onProofReceived: ProofReceivedCallback) {
        val adapter = bluetoothAdapter ?: error("Bluetooth not supported on this device")
        check(adapter.isEnabled) { "Bluetooth is disabled" }

        val serverCallback = GattServerCallback(onProofReceived)
        serverCallback.sessionSignedBytes = sessionSignedBytes

        val server = bluetoothManager.openGattServer(context, serverCallback)
            ?: error("Failed to open BluetoothGattServer")

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val sessionChar = BluetoothGattCharacteristic(
            SESSION_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(sessionChar)

        val proofChar = BluetoothGattCharacteristic(
            PROOF_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(proofChar)

        val statusChar = BluetoothGattCharacteristic(
            STATUS_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_INDICATE or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        statusChar.addDescriptor(cccd)
        service.addCharacteristic(statusChar)

        server.addService(service)
        serverCallback.setGattServer(server, statusChar)

        this.gattServer = server
        this.callback = serverCallback

        val adv = adapter.bluetoothLeAdvertiser ?: error("BLE Advertising not supported")
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(SERVICE_PARCEL_UUID)
            .build()

        adv.startAdvertising(settings, data, advertiseCallback)
        this.advertiser = adv
    }

    fun sendStatus(device: BluetoothDevice, status: StatusCode) {
        callback?.sendStatusIndication(device, status)
    }

    fun sendStatus(deviceAddress: String, status: StatusCode) {
        callback?.sendStatusIndication(deviceAddress, status)
    }

    fun stop() {
        try {
            if (advertising) {
                advertiser?.stopAdvertising(advertiseCallback)
                advertising = false
            }
        } catch (_: Exception) { }

        try {
            gattServer?.close()
            gattServer = null
        } catch (_: Exception) { }

        callback = null
        advertiser = null
    }

    fun isAdvertising(): Boolean = advertising
}
