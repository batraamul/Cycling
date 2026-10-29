package com.batraamul.cycling

import android.app.Application
import com.batraamul.cycling.core.SystemClock
import com.batraamul.cycling.data.AppDatabase
import com.batraamul.cycling.data.AppRepository
import com.batraamul.cycling.data.BackupManager
import com.batraamul.cycling.tracking.AndroidEnvironment
import com.batraamul.cycling.tracking.AndroidLocationFeed
import com.batraamul.cycling.tracking.AndroidStepSensor
import com.batraamul.cycling.tracking.TrackingController
import com.batraamul.cycling.tracking.TrackingEngine
import com.batraamul.cycling.tracking.TrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob

/**
 * Application = the object that lives as long as the app's process. It builds the shared
 * objects once ("manual dependency injection" — no DI framework needed at this size).
 */
class CyclingApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        TrackingService.createChannels(this)
    }
}

class AppContainer(app: Application) {
    val db: AppDatabase = AppDatabase.create(app)
    val environment = AndroidEnvironment(app)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    val engine = TrackingEngine(
        db = db,
        clock = SystemClock,
        stepSensorFactory = { force -> AndroidStepSensor(app, force) },
        location = AndroidLocationFeed(app),
        env = environment,
        scope = engineScope,
    )
    val controller = TrackingController(app, engine)
    val repository = AppRepository(db, SystemClock, environment)
    val backup = BackupManager(db, SystemClock, environment)
}
