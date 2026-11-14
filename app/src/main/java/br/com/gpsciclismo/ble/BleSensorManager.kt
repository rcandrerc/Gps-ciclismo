package br.com.gpsciclismo.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import br.com.gpsciclismo.core.BleParsers
import br.com.gpsciclismo.core.CadenceCalculator
import br.com.gpsciclismo.core.Ftms
import br.com.gpsciclismo.core.TrainerCommand
import br.com.gpsciclismo.data.Prefs
import br.com.gpsciclismo.data.SensorKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** Últimos valores recebidos dos sensores, com o horário de cada um. */
data class SensorReadings(
    val heartRate: Int? = null,
    val heartRateAt: Long = 0,
    val power: Int? = null,
    val powerAt: Long = 0,
    val cadence: Int? = null,
    val cadenceAt: Long = 0,
    /** Velocidade calculada pelo rolo smart (usada no indoor). */
    val trainerSpeedKmh: Double? = null,
    val trainerSpeedAt: Long = 0,
) {
    private fun fresh(at: Long, now: Long) = now - at < STALE_MS
    fun heartRateNow(now: Long) = heartRate.takeIf { fresh(heartRateAt, now) }
    fun powerNow(now: Long) = power.takeIf { fresh(powerAt, now) }
    fun cadenceNow(now: Long) = cadence.takeIf { fresh(cadenceAt, now) }
    fun trainerSpeedNow(now: Long) = trainerSpeedKmh.takeIf { fresh(trainerSpeedAt, now) }

    companion object { const val STALE_MS = 5000L }
}

enum class LinkState { DISCONNECTED, CONNECTING, CONNECTED }

/** Situação do controle do rolo no modo ERG. */
enum class TrainerControl { OFF, WAITING, OK, DENIED }

data class FoundDevice(val address: String, val name: String, val kinds: Set<SensorKind>, val rssi: Int)

/**
 * Conecta nos sensores pareados usando os perfis Bluetooth padrão
 * (Heart Rate, Cycling Speed & Cadence e Cycling Power) e reconecta sozinho se cair.
 */
@SuppressLint("MissingPermission")
class BleSensorManager(private val context: Context, private val prefs: Prefs) {

    companion object {
        private const val TAG = "BleSensors"
        val HR_SERVICE: UUID = uuid16(0x180D)
        val HR_MEASUREMENT: UUID = uuid16(0x2A37)
        val CSC_SERVICE: UUID = uuid16(0x1816)
        val CSC_MEASUREMENT: UUID = uuid16(0x2A5B)
        val CP_SERVICE: UUID = uuid16(0x1818)
        val CP_MEASUREMENT: UUID = uuid16(0x2A63)
        val FTMS_SERVICE: UUID = uuid16(0x1826)
        val FTMS_INDOOR_BIKE: UUID = uuid16(0x2AD2)
        val FTMS_CONTROL: UUID = uuid16(0x2AD9)
        val CCCD: UUID = uuid16(0x2902)

        fun uuid16(v: Int): UUID = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))

        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())

    private val _readings = MutableStateFlow(SensorReadings())
    val readings: StateFlow<SensorReadings> = _readings.asStateFlow()

    private val _links = MutableStateFlow<Map<String, LinkState>>(emptyMap())
    /** Estado da conexão por endereço do sensor. */
    val links: StateFlow<Map<String, LinkState>> = _links.asStateFlow()

    private val _found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val found: StateFlow<List<FoundDevice>> = _found.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _trainer = MutableStateFlow(TrainerControl.OFF)
    /** Se o rolo aceitou o controle do app (ERG). */
    val trainer: StateFlow<TrainerControl> = _trainer.asStateFlow()

    private val gatts = mutableMapOf<String, BluetoothGatt>()
    private val callbacks = mutableMapOf<String, GattCallback>()
    /** Aparelhos que têm o serviço Cycling Power (preferido ao FTMS para potência). */
    private val hasCyclingPower = mutableSetOf<String>()
    private var command: TrainerCommand = TrainerCommand.None
    private var controlGranted = false
    private var active = false
    private val cscCadence = CadenceCalculator()
    private val powerCadence = CadenceCalculator()

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    // ---------------- Busca ----------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
            val kinds = buildSet {
                if (HR_SERVICE in uuids) add(SensorKind.HEART_RATE)
                if (CSC_SERVICE in uuids) add(SensorKind.CADENCE)
                if (CP_SERVICE in uuids) { add(SensorKind.POWER); add(SensorKind.CADENCE) }
                if (FTMS_SERVICE in uuids) { add(SensorKind.TRAINER); add(SensorKind.POWER); add(SensorKind.CADENCE) }
            }
            if (kinds.isEmpty()) return
            val name = result.scanRecord?.deviceName ?: result.device.name ?: result.device.address
            val dev = FoundDevice(result.device.address, name, kinds, result.rssi)
            _found.update { list -> (list.filter { it.address != dev.address } + dev).sortedByDescending { it.rssi } }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan falhou: $errorCode")
            _scanning.value = false
        }
    }

    fun startScan() {
        if (!hasPermissions() || !isBluetoothOn() || _scanning.value) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        _found.value = emptyList()
        val filters = listOf(HR_SERVICE, CSC_SERVICE, CP_SERVICE, FTMS_SERVICE).map {
            ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, scanCallback)
        _scanning.value = true
        main.postDelayed({ stopScan() }, 20_000)
    }

    fun stopScan() {
        if (!_scanning.value) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        _scanning.value = false
    }

    // ---------------- Conexão ----------------

    /** Conecta em todos os sensores salvos e mantém reconectando até [disconnectAll]. */
    fun connectSaved() {
        if (!hasPermissions() || !isBluetoothOn()) return
        active = true
        prefs.sensors.value.values.map { it.address }.distinct().forEach { connect(it) }
    }

    fun disconnectAll() {
        active = false
        main.removeCallbacksAndMessages(null)
        gatts.values.forEach { runCatching { it.disconnect(); it.close() } }
        gatts.clear()
        callbacks.clear()
        hasCyclingPower.clear()
        command = TrainerCommand.None
        controlGranted = false
        _trainer.value = TrainerControl.OFF
        _links.value = emptyMap()
        _readings.value = SensorReadings()
        cscCadence.reset(); powerCadence.reset()
    }

    /** Reaplica a lista de sensores salvos (ex.: depois de parear um novo). */
    fun refreshConnections() {
        if (!active) return
        val wanted = prefs.sensors.value.values.map { it.address }.toSet()
        (gatts.keys - wanted).forEach { addr ->
            gatts.remove(addr)?.let { runCatching { it.disconnect(); it.close() } }
            callbacks.remove(addr)
            _links.update { it - addr }
        }
        (wanted - gatts.keys).forEach { connect(it) }
    }

    private fun connect(address: String) {
        if (gatts.containsKey(address)) return
        val device: BluetoothDevice = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        setLink(address, LinkState.CONNECTING)
        val cb = GattCallback(address)
        callbacks[address] = cb
        val gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        if (gatt != null) gatts[address] = gatt
    }

    private fun scheduleReconnect(address: String) {
        main.postDelayed({
            if (active && address in prefs.sensors.value.values.map { it.address } && !gatts.containsKey(address)) {
                connect(address)
            }
        }, 3000)
    }

    private fun setLink(address: String, state: LinkState) = _links.update { it + (address to state) }

    /** Operações GATT têm de ir uma de cada vez; esta fila garante isso. */
    private sealed class GattOp {
        class Subscribe(val ch: BluetoothGattCharacteristic, val indicate: Boolean) : GattOp()
        class Write(val ch: BluetoothGattCharacteristic, val value: ByteArray) : GattOp()
    }

    private inner class GattCallback(private val address: String) : BluetoothGattCallback() {
        private val queue = ArrayDeque<GattOp>()
        private var busy: GattOp? = null
        var gatt: BluetoothGatt? = null; private set
        var control: BluetoothGattCharacteristic? = null; private set

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    setLink(address, LinkState.CONNECTED)
                    gatt.discoverServices()
                } else {
                    setLink(address, LinkState.DISCONNECTED)
                    queue.clear(); busy = null; control = null; this.gatt = null
                    if (isTrainer(address)) {
                        controlGranted = false
                        if (command != TrainerCommand.None) _trainer.value = TrainerControl.WAITING
                    }
                    if (gatts[address] === gatt) gatts.remove(address)
                    runCatching { gatt.close() }
                    scheduleReconnect(address)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            main.post {
                this.gatt = gatt
                queue.clear(); busy = null
                if (gatt.getService(CP_SERVICE) != null) hasCyclingPower += address else hasCyclingPower -= address
                listOf(
                    HR_SERVICE to HR_MEASUREMENT,
                    CSC_SERVICE to CSC_MEASUREMENT,
                    CP_SERVICE to CP_MEASUREMENT,
                    FTMS_SERVICE to FTMS_INDOOR_BIKE,
                ).forEach { (s, c) -> gatt.getService(s)?.getCharacteristic(c)?.let { queue.addLast(GattOp.Subscribe(it, false)) } }
                control = gatt.getService(FTMS_SERVICE)?.getCharacteristic(FTMS_CONTROL)
                if (isTrainer(address)) control?.let { queue.addLast(GattOp.Subscribe(it, true)) }
                runNext()
                if (isTrainer(address) && command != TrainerCommand.None) takeControl()
            }
        }

        /** Pede o controle do rolo; a resposta chega em [onControlResponse]. */
        fun takeControl() {
            val ch = control ?: return
            controlGranted = false
            _trainer.value = TrainerControl.WAITING
            enqueue(GattOp.Write(ch, Ftms.requestControl()))
        }

        fun write(value: ByteArray) { control?.let { enqueue(GattOp.Write(it, value)) } }

        private fun enqueue(op: GattOp) {
            queue.addLast(op)
            if (busy == null) runNext()
        }

        private fun runNext() {
            val g = gatt ?: return
            val op = queue.removeFirstOrNull() ?: run { busy = null; return }
            busy = op
            // Se o aparelho não responder, segue a fila mesmo assim.
            main.postDelayed({ if (busy === op) runNext() }, 3000)
            when (op) {
                is GattOp.Subscribe -> {
                    g.setCharacteristicNotification(op.ch, true)
                    val desc = op.ch.getDescriptor(CCCD) ?: run { runNext(); return }
                    val value = if (op.indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(desc, value)
                    } else {
                        @Suppress("DEPRECATION")
                        desc.value = value
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(desc)
                    }
                }
                is GattOp.Write -> {
                    val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeCharacteristic(op.ch, op.value, type)
                    } else {
                        @Suppress("DEPRECATION")
                        op.ch.writeType = type
                        @Suppress("DEPRECATION")
                        op.ch.value = op.value
                        @Suppress("DEPRECATION")
                        g.writeCharacteristic(op.ch)
                    }
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post { runNext() }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            main.post { runNext() }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handle(address, ch.uuid, value)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = ch.value ?: return
            handle(address, ch.uuid, v)
        }
    }

    // ---------------- Rolo (FTMS / ERG) ----------------

    private fun isTrainer(address: String) = prefs.sensors.value[SensorKind.TRAINER]?.address == address

    private fun trainerCallback(): GattCallback? =
        prefs.sensors.value[SensorKind.TRAINER]?.address?.let { callbacks[it] }

    fun hasTrainer(): Boolean = prefs.sensors.value[SensorKind.TRAINER] != null

    /**
     * Diz ao rolo o que fazer. Só manda algo quando muda; se o rolo cair,
     * pede o controle de novo e reaplica ao reconectar.
     */
    fun setTrainerCommand(cmd: TrainerCommand) {
        main.post {
            if (cmd == command) return@post
            val wasNone = command == TrainerCommand.None
            command = cmd
            val cb = trainerCallback()?.takeIf { it.gatt != null }
            when {
                cmd == TrainerCommand.None -> {
                    // Devolve o rolo ao modo normal.
                    if (controlGranted) cb?.write(Ftms.reset())
                    controlGranted = false
                    _trainer.value = TrainerControl.OFF
                }
                cb == null -> _trainer.value = TrainerControl.WAITING
                wasNone || !controlGranted -> cb.takeControl()
                else -> sendCommand(cb)
            }
        }
    }

    private fun sendCommand(cb: GattCallback) {
        when (val c = command) {
            is TrainerCommand.Erg -> cb.write(Ftms.setTargetPower(c.watts))
            TrainerCommand.Flat -> cb.write(Ftms.flatRoad())
            TrainerCommand.None -> Unit
        }
    }

    private fun onControlResponse(address: String, data: ByteArray) {
        val r = Ftms.response(data) ?: return
        val cb = callbacks[address] ?: return
        when (r.requestOp) {
            Ftms.OP_REQUEST_CONTROL -> {
                if (r.ok) {
                    controlGranted = true
                    _trainer.value = TrainerControl.OK
                    cb.write(Ftms.startOrResume())
                    sendCommand(cb)
                } else {
                    Log.w(TAG, "rolo recusou o controle: ${r.result}")
                    _trainer.value = TrainerControl.DENIED
                }
            }
            Ftms.OP_SET_TARGET_POWER, Ftms.OP_SIMULATION ->
                if (!r.ok) Log.w(TAG, "rolo recusou o comando ${r.requestOp}: ${r.result}")
        }
    }

    private fun roleOf(address: String): Set<SensorKind> {
        val saved = prefs.sensors.value
        val roles = saved.values.filter { it.address == address }.map { it.kind }.toMutableSet()
        // Rolo smart também serve de potência e cadência quando não há outro sensor escolhido.
        if (SensorKind.TRAINER in roles) {
            if (saved[SensorKind.POWER] == null) roles += SensorKind.POWER
            if (saved[SensorKind.CADENCE] == null) roles += SensorKind.CADENCE
        }
        return roles
    }

    private fun handle(address: String, uuid: UUID, data: ByteArray) {
        val now = System.currentTimeMillis()
        val roles = roleOf(address)
        when (uuid) {
            HR_MEASUREMENT -> if (SensorKind.HEART_RATE in roles) BleParsers.heartRate(data)?.let { hr ->
                _readings.update { it.copy(heartRate = hr, heartRateAt = now) }
            }
            CSC_MEASUREMENT -> if (SensorKind.CADENCE in roles) BleParsers.csc(data)?.crank?.let { c ->
                val rpm = cscCadence.update(c, now)
                _readings.update { it.copy(cadence = rpm, cadenceAt = now) }
            }
            CP_MEASUREMENT -> BleParsers.cyclingPower(data)?.let { m ->
                if (SensorKind.POWER in roles) _readings.update { it.copy(power = m.watts, powerAt = now) }
                // Medidor de potência como fonte de cadência, se ele foi escolhido para isso.
                if (SensorKind.CADENCE in roles && m.crank != null) {
                    val rpm = powerCadence.update(m.crank, now)
                    _readings.update { it.copy(cadence = rpm, cadenceAt = now) }
                }
            }
            FTMS_INDOOR_BIKE -> Ftms.indoorBikeData(data)?.let { d ->
                if (SensorKind.TRAINER in roles && d.speedKmh != null) {
                    _readings.update { it.copy(trainerSpeedKmh = d.speedKmh, trainerSpeedAt = now) }
                }
                // Se o mesmo aparelho tem Cycling Power, a potência já vem de lá.
                if (address !in hasCyclingPower && SensorKind.POWER in roles && d.power != null) {
                    _readings.update { it.copy(power = d.power, powerAt = now) }
                }
                // Cadência do FTMS (muitos rolos não mandam pedivela no Cycling Power).
                if (SensorKind.CADENCE in roles && d.cadence != null) _readings.update { it.copy(cadence = d.cadence, cadenceAt = now) }
            }
            FTMS_CONTROL -> main.post { onControlResponse(address, data) }
        }
    }
}
