package dev.sjtu.allinone

import android.app.Application
import android.content.Context
import dev.sjtu.allinone.background.AppSyncScheduler
import dev.sjtu.allinone.data.AppRepository
import dev.sjtu.allinone.data.Settings
import dev.sjtu.allinone.data.local.AppDatabase
import dev.sjtu.allinone.data.remote.CanvasApi
import dev.sjtu.allinone.data.remote.IdsApi
import dev.sjtu.allinone.data.remote.JsonTransport
import dev.sjtu.allinone.data.remote.MemoryCookieJar
import dev.sjtu.allinone.data.remote.SchoolApi
import dev.sjtu.allinone.data.remote.SessionCookieJar
import dev.sjtu.allinone.data.remote.okHttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class AppContainer(context: Context) {
    val settings = Settings(context)
    val db = AppDatabase.create(context)

    @Volatile var activeAccount: String = runBlocking { settings.activeAccount() }
        private set

    val schoolCookies = SessionCookieJar(context, "j.sjtu.edu.cn", "school") { activeAccount }
    val canvasCookies = SessionCookieJar(context, "oc.sjtu.edu.cn", "canvas") { activeAccount }
    private val schoolClient = okHttpClient(schoolCookies)
    private val canvasClient = okHttpClient(canvasCookies)
    private val idsClient = okHttpClient(MemoryCookieJar())
    private val schoolTransport = JsonTransport(schoolClient, "j.sjtu.edu.cn")
    private val canvasTransport = JsonTransport(canvasClient, "oc.sjtu.edu.cn")
    private val idsTransport = JsonTransport(idsClient, "ids.sjtu.edu.cn")

    val schoolApi = SchoolApi(schoolTransport)
    val canvasApi = CanvasApi(canvasTransport)
    val idsApi = IdsApi(idsClient, idsTransport)
    val repository = AppRepository(db, settings, schoolApi, canvasApi, idsApi,
        schoolCookies, canvasCookies, { activeAccount }, { activeAccount = it })

    suspend fun selectedYear(): String = settings.year.first()
    suspend fun selectedSemester(): Int = settings.semester.first()
}

class SJTUApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        AppSyncScheduler.schedule(this)
    }
}
