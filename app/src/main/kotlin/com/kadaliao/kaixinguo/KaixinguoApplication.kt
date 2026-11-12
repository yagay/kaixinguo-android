package com.kadaliao.kaixinguo

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import com.kadaliao.kaixinguo.account.AccountRepository
import com.kadaliao.kaixinguo.account.PassportRepository
import com.kadaliao.kaixinguo.account.LocalFollows
import com.kadaliao.kaixinguo.diagnostics.Diagnostics
import com.kadaliao.kaixinguo.settings.AppSettings
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.media3.datasource.DefaultHttpDataSource
import com.kadaliao.kaixinguo.covers.CoverStore
import com.kadaliao.kaixinguo.model.AppModel
import com.kadaliao.kaixinguo.model.SdkCatalog
import com.kadaliao.kaixinguo.model.debugLogger
import com.kadaliao.kaixinguo.playback.ExoVideo
import com.kadaliao.kaixinguo.playback.StreamRegistry
import com.kadaliao.kaixinguo.ui.Router
import dev.openhongguo.ClientOptions
import dev.openhongguo.OpenhongguoClient
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * KaixinguoApplication holds what lives as long as the app: the model, the
 * video, the covers and where the viewer is. The activity may come and go,
 * as the screen turns or picture in picture starts; they stay.
 */
class KaixinguoApplication : Application() {
    /** The app's model; null when the SDK could not start, as [startError] says. */
    var model: AppModel? = null
        private set
    var startError = ""
        private set
    lateinit var video: ExoVideo
        private set
    lateinit var covers: CoverStore
        private set
    val router = Router()
    lateinit var settings: AppSettings
        private set
    lateinit var account: AccountRepository
        private set
    lateinit var passport: PassportRepository
        private set
    var follows: LocalFollows? = null
        private set

    override fun onCreate() {
        super.onCreate()
        // The separate website process never starts the SDK or decoder.
        if (Application.getProcessName() != packageName) return
        Diagnostics.install(this)
        settings = AppSettings(this)
        account = AccountRepository(this)
        passport = PassportRepository(this) { cookie ->
            account.signInWithOfficialWebsite(cookie)
        }
        Diagnostics.record("lifecycle", "app_created")
        val debug = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debug) debugLogger = { Log.d("kxg", it) }
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
        val streams = StreamRegistry(http)
        video = ExoVideo(this, streams, http)
        covers = CoverStore(File(cacheDir, "covers"))
        try {
            val client = OpenhongguoClient.create(
                this,
                ClientOptions(retryAttempts = 3, deviceResets = 3, logger = if (debug) { m -> Log.d("sdk", m) } else null),
            )
            val scope = MainScope()
            model = AppModel(SdkCatalog(client), filesDir, cacheDir, scope, video, streams).also { m ->
                m.artwork = covers::jpeg
                follows = LocalFollows(m.catalog, m.scope, filesDir)
                // The app opens where the viewer left it: the tab and pages
                // now, the player as the first window opens. Where they are
                // goes to disk as it changes, so the next launch finds it
                // however this one ends.
                router.restore(m.lastSession)
                scope.launch { snapshotFlow { router.saveTo(m.session()) }.collect(m::saveSession) }
                scope.launch {
                    snapshotFlow { router.tab.name }.distinctUntilChanged().collect { tab ->
                        Diagnostics.record("navigation", "tab_" + tab.lowercase())
                    }
                }
                scope.launch {
                    snapshotFlow {
                        Triple(m.player.state.javaClass.simpleName, m.player.isPresented, m.player.paused)
                    }.distinctUntilChanged().collect { (state, presented, paused) ->
                        Diagnostics.record("player", "state_" + state.lowercase())
                        Diagnostics.record("player", if (presented) "visible" else "hidden")
                        if (paused) Diagnostics.record("player", "paused")
                    }
                }
                scope.launch {
                    snapshotFlow { m.home.cards.size to m.home.loading }.distinctUntilChanged().collect { (_, loading) ->
                        Diagnostics.record("feed", if (loading) "home_loading" else "home_settled")
                    }
                }
            }
        } catch (e: Exception) {
            startError = e.message ?: e.toString()
            Diagnostics.record("lifecycle", "sdk_start_failed")
            Log.e("kxg", "start", e)
        }
    }

    /** Saves where the viewer is now, as the app leaves the screen. */
    fun saveSession() {
        val m = model ?: return
        m.saveSession(router.saveTo(m.session()))
    }
}
