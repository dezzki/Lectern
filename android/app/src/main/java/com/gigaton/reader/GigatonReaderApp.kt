package com.gigaton.reader

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.gigaton.reader.data.local.AppDatabase
import com.gigaton.reader.data.local.FileStore
import com.gigaton.reader.data.local.SettingsStore
import com.gigaton.reader.data.network.ApiClient
import com.gigaton.reader.data.repository.Repository

class GigatonReaderApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        installCrashLogger()
    }

    /**
     * Records uncaught exceptions to [crashLogFile] so real crashes can be
     * inspected without adb. The system default handler still runs afterwards.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                crashLogFile().appendText(
                    buildString {
                        appendLine("=== ${java.time.Instant.now()} · ${thread.name} ===")
                        appendLine(android.util.Log.getStackTraceString(throwable))
                        appendLine()
                    }
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun crashLogFile(): java.io.File = java.io.File(filesDir, "crash.log")

    /** Records non-fatal errors (e.g. page render failures) into the same log
     *  the Connect screen can copy, so the user can send the real stack trace. */
    fun logRuntimeError(step: String, throwable: Throwable) {
        runCatching {
            crashLogFile().appendText(
                buildString {
                    appendLine("=== $step @ ${java.time.Instant.now()} ===")
                    appendLine(android.util.Log.getStackTraceString(throwable))
                    appendLine()
                }
            )
        }
        android.util.Log.e(TAG, "Runtime error in $step", throwable)
    }

    private companion object {
        const val TAG = "GigatonReader"
    }
}

/** Hand-rolled dependency container. */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val settings = SettingsStore(appContext)

    val database: AppDatabase = Room.databaseBuilder(
        appContext, AppDatabase::class.java, "gigaton_reader.db"
    ).fallbackToDestructiveMigration().build()

    val fileStore = FileStore(appContext)

    @Volatile private var cachedBase: String = ""
    @Volatile private var cachedApi: ApiClient? = null

    /** Returns an ApiClient for the current base URL, rebuilding if it changed. */
    fun api(): ApiClient {
        val base = settings.baseUrl
        cachedApi?.let { if (cachedBase == base) return it }
        val client = ApiClient(base, token = { settings.token })
        cachedApi = client
        cachedBase = base
        return client
    }

    fun resetApi() {
        cachedApi = null
        cachedBase = ""
    }

    val repository: Repository by lazy { Repository(this) }

    /** Cross-screen "jump to this page" requests, consumed by the reader. */
    private val _jumpRequests = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    val jumpRequests: kotlinx.coroutines.flow.StateFlow<Int?> = _jumpRequests

    fun requestJump(page: Int) {
        _jumpRequests.value = page
    }

    fun consumeJump() {
        _jumpRequests.value = null
    }
}