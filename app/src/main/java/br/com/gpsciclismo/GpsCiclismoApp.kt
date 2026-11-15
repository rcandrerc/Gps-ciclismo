package br.com.gpsciclismo

import android.app.Application
import br.com.gpsciclismo.ble.BleSensorManager
import br.com.gpsciclismo.data.AppDatabase
import br.com.gpsciclismo.data.CalendarRepository
import br.com.gpsciclismo.data.CloudSync
import br.com.gpsciclismo.data.Prefs
import br.com.gpsciclismo.recording.RideRecorder
import org.osmdroid.config.Configuration

class GpsCiclismoApp : Application() {
    lateinit var db: AppDatabase; private set
    lateinit var prefs: Prefs; private set
    lateinit var sensors: BleSensorManager; private set
    lateinit var recorder: RideRecorder; private set
    lateinit var calendar: CalendarRepository; private set
    lateinit var cloud: CloudSync; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDatabase.create(this)
        prefs = Prefs(this)
        sensors = BleSensorManager(this, prefs)
        recorder = RideRecorder(this, db, prefs, sensors)
        calendar = CalendarRepository(this)
        cloud = CloudSync(this, db, prefs)
        cloud.requestSync() // ao abrir o app: sobe o que faltou e baixa o que veio de outro aparelho
        Configuration.getInstance().userAgentValue = packageName
    }

    companion object {
        lateinit var instance: GpsCiclismoApp; private set
    }
}
