package io.github.alexeikramm.sovereign.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import io.github.alexeikramm.sovereign.MainActivity
import io.github.alexeikramm.sovereign.R
import io.github.alexeikramm.sovereign.data.AppSettings
import io.github.alexeikramm.sovereign.dictation.DictationSession
import io.github.alexeikramm.sovereign.dictation.DictationSessionHolder
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts the phone-as-desktop-server (Chunk 1b): the pinned `wss://` endpoint, the mDNS announcement
 * and the bridge to the one shared `DictationSession`.
 *
 * Foreground-service shape (inherited platform constraints, 2026-10-06): the service holds
 * `specialUse | microphone` for its whole life. Android 14+ will not grant a while-in-use
 * (`microphone`) type to a background app, so a remote `START_DICTATION` cannot acquire it later —
 * start while an Activity is visible and keep it until the server stops. `specialUse` (not
 * `dataSync`) is the idle type because Android 15+ caps `dataSync` at 6 h / 24 h.
 *
 * Pairing: a debuggable build auto-accepts (release builds deny), standing in for Chunk 2's
 * mutual-accept dialog + Keystore registry. There is no toggle UI yet; start it with
 * `adb shell am start-foreground-service -n io.github.alexeikramm.sovereign/.server.SovereignServerService`.
 */
class SovereignServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var running = false
    private var tls: ServerTls.Identity? = null
    private var session: DictationSession? = null
    private var captureHost: SessionCaptureHost? = null
    private var server: SovereignWebSocketServer? = null
    private var nsdManager: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var timeoutJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) {
            Log.i(TAG, "Already serving; ignoring repeat start")
            return START_NOT_STICKY
        }
        running = true

        createNotificationChannel()
        if (!startInForeground()) {
            running = false
            return START_NOT_STICKY
        }
        startTimeout(intent)
        startServer()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        timeoutJob?.cancel()
        timeoutJob = null
        unregisterDiscovery()
        server?.shutdown()
        server = null
        // Aborts a live capture and releases the arbiter, then stops our callbacks.
        captureHost?.dispose()
        captureHost = null
        // D-105: leaving server mode restores the pill's screen-off / keyboard-close unloads.
        session?.setServerMode(false)
        session = null
        scope.cancel()
        tls = null
        Log.i(TAG, "SovereignServerService destroyed")
        super.onDestroy()
    }

    // --- Startup ---------------------------------------------------------------------------------

    private fun startServer() {
        scope.launch {
            try {
                val identity = withContext(Dispatchers.IO) { ServerTls.loadOrCreate(filesDir) }
                tls = identity

                val deviceId = installDeviceId()
                val serverIdentity = ServerIdentity(deviceId, Build.MODEL ?: "Sovereign phone")
                val pairing = InMemoryPairingAuthority(
                    serverSpki = identity.spkiSha256,
                    approveResult = isDebuggable(),
                )

                // The one shared session (D-106/D-112): never construct a second engine set.
                val shared = DictationSessionHolder.shared(this@SovereignServerService)
                shared.setServerMode(true)
                session = shared
                val host = SessionCaptureHost(
                    settings = AppSettings(this@SovereignServerService),
                    session = shared,
                    scope = scope,
                )
                captureHost = host

                val ws = SovereignWebSocketServer(
                    boundPort = ServerProtocol.DEFAULT_PORT,
                    tls = identity,
                    identity = serverIdentity,
                    pairing = pairing,
                    capture = host,
                    dispatcher = Dispatchers.Main.immediate,
                )
                server = ws
                ws.start()
                advertise(serverIdentity, identity.spkiSha256)
                Log.i(TAG, "Server started: id=$deviceId name=${serverIdentity.serverName} spki=${identity.spkiSha256}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start the server", e)
                stopSelf()
            }
        }
    }

    private fun startTimeout(intent: Intent?) {
        val minutes = intent?.getIntExtra(EXTRA_TIMEOUT_MINUTES, DEFAULT_TIMEOUT_MINUTES)
            ?: DEFAULT_TIMEOUT_MINUTES
        if (minutes <= 0) {
            Log.i(TAG, "Session timeout disabled (manual stop)")
            return
        }
        Log.i(TAG, "Auto-stop armed for $minutes min (not persisted across process death)")
        timeoutJob = scope.launch {
            delay(minutes.toLong() * 60_000L)
            Log.i(TAG, "Session timeout reached after $minutes min; stopping")
            stopSelf()
        }
    }

    // --- mDNS (PROTOCOL.md §2) ---------------------------------------------------------------------

    private fun advertise(identity: ServerIdentity, spkiSha256: String) {
        val manager = getSystemService(NsdManager::class.java)
        if (manager == null) {
            Log.w(TAG, "No NsdManager; mDNS discovery unavailable")
            return
        }
        val info = NsdServiceInfo().apply {
            serviceName = ServerDiscovery.instanceName(identity.deviceId)
            serviceType = ServerDiscovery.NSD_SERVICE_TYPE
            port = ServerProtocol.DEFAULT_PORT
            ServerDiscovery
                .txtAttributes(identity.deviceId, identity.serverName, spkiSha256, paired = false)
                .forEach { (key, value) -> setAttribute(key, value) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "mDNS registered: ${info.serviceName} on port ${info.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: errorCode=$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "mDNS unregistered: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS unregistration failed: errorCode=$errorCode")
            }
        }
        nsdManager = manager
        nsdListener = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "mDNS registerService threw", it) }
    }

    private fun unregisterDiscovery() {
        val manager = nsdManager
        val listener = nsdListener
        nsdManager = null
        nsdListener = null
        if (manager != null && listener != null) {
            runCatching { manager.unregisterService(listener) }
        }
    }

    // --- Foreground ------------------------------------------------------------------------------

    private fun startInForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            foregroundServiceType(),
        )
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to enter the foreground", e)
        stopSelf()
        false
    }

    /**
     * `microphone` is held for the whole session. `specialUse` is added on API 34+ where it is a real
     * type; on older versions the legacy `dataSync` cap does not apply and `microphone` alone is
     * accepted.
     */
    private fun foregroundServiceType(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        return type
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Server", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps the phone available as a desktop dictation server"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** The ongoing notification; tapping it returns to the app (Chunk 2 routes it to the dashboard). */
    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sovereign Server Active")
            .setContentText("Ready to dictate")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    // --- Identity --------------------------------------------------------------------------------

    /** A stable per-install UUID, generated on first run and never reused (PROTOCOL.md §0). */
    private fun installDeviceId(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_DEVICE_ID, id) }
        return id
    }

    /**
     * The 1b pairing stand-in is auto-accept **only in a debuggable build**; a release build denies
     * every pairing request until Chunk 2's approval dialog exists. This must never ship as-is.
     */
    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    companion object {
        private const val TAG = "SovereignServer"
        private const val CHANNEL_ID = "sovereign_server"
        private const val NOTIFICATION_ID = 41
        private const val PREFS_NAME = "sovereign_server"
        private const val KEY_DEVICE_ID = "device_id"

        /** Auto-stop default; Chunk 2 replaces it with the 1 h / 2 h / manual selector. */
        private const val DEFAULT_TIMEOUT_MINUTES = 120

        /** Optional override for the device walkthrough (`--ei timeout_minutes 0` disables it). */
        const val EXTRA_TIMEOUT_MINUTES = "timeout_minutes"
    }
}
