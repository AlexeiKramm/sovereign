package io.github.alexeikramm.sovereign.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import io.github.alexeikramm.sovereign.MainActivity
import io.github.alexeikramm.sovereign.R
import io.github.alexeikramm.sovereign.data.AppSettings
import io.github.alexeikramm.sovereign.data.DictationHistoryStore
import io.github.alexeikramm.sovereign.data.PairedDeviceStore
import io.github.alexeikramm.sovereign.data.UsageStatsStore
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
import kotlinx.coroutines.withTimeout

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
 * Pairing: the mutual-accept dialog (Chunk 2b) backed by the persisted, Keystore-wrapped registry;
 * the desktop's request surfaces on the Server screen and as a notification. The service is still
 * started from the app's toggle (Chunk 2a) or, in a debug build, over adb for a walkthrough.
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

    /** The auto-stop the current session was started with; surfaced in the dashboard snapshot. */
    private var timeoutMinutes = AppSettings.DEFAULT_SERVER_TIMEOUT_MIN

    /** The absolute auto-stop deadline in the elapsedRealtime domain, or null for a manual session. */
    private var endsAtRealtimeMs: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Commands on a live server (D-133/D-136), not starts: delivered with startService while the
        // service is already foreground.
        when (intent?.action) {
            ACTION_DISCONNECT_CLIENT -> {
                intent.getStringExtra(EXTRA_DEVICE_ID)?.let { id ->
                    Log.i(TAG, "Disconnect requested for client $id")
                    server?.disconnectDevice(id)
                }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                Log.i(TAG, "Stop requested from the notification")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_EXTEND -> {
                if (running) extendSession(ServerSessionTiming.EXTEND_MINUTES) else stopSelf()
                return START_NOT_STICKY
            }
        }
        if (running) {
            Log.i(TAG, "Already serving; ignoring repeat start")
            return START_NOT_STICKY
        }
        running = true
        ServerRuntime.shared.onStarting()

        timeoutMinutes = intent?.getIntExtra(EXTRA_TIMEOUT_MINUTES, AppSettings(this).serverTimeoutMinutes)
            ?: AppSettings(this).serverTimeoutMinutes
        // The deadline is known before the notification is built, so the countdown is right from the
        // first frame (and the same value the runtime publishes for the Home row).
        endsAtRealtimeMs = ServerSessionTiming.deadlineFrom(SystemClock.elapsedRealtime(), timeoutMinutes)

        createNotificationChannel()
        if (!startInForeground()) {
            running = false
            ServerRuntime.shared.onFailed("Could not start the foreground service")
            return START_NOT_STICKY
        }
        startTimeout()
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
        // A pairing dialog cannot outlive the server that asked for it.
        AppPairingPrompter.dismiss()
        // D-105: leaving server mode restores the pill's screen-off / keyboard-close unloads.
        session?.setServerMode(false)
        session = null
        scope.cancel()
        tls = null
        ServerRuntime.shared.onStopped()
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
                // The mutual-accept dialog (Chunk 2b): one persisted, Keystore-wrapped registry, with
                // the request surfaced by AppPairingPrompter. Replaces 1b's debug auto-accept.
                AppPairingPrompter.attach(this@SovereignServerService)
                val pairedStore = PairedDeviceStore(this@SovereignServerService)
                val pairing = RegistryPairingAuthority(
                    serverSpki = identity.spkiSha256,
                    store = pairedStore,
                    prompter = AppPairingPrompter,
                )

                // The one shared session (D-106/D-112): never construct a second engine set.
                val shared = DictationSessionHolder.shared(this@SovereignServerService)
                shared.setServerMode(true)
                session = shared
                val host = SessionCaptureHost(
                    settings = AppSettings(this@SovereignServerService),
                    session = shared,
                    scope = scope,
                    historyStore = DictationHistoryStore(this@SovereignServerService),
                    usageStatsStore = UsageStatsStore(this@SovereignServerService),
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
                // Java-WebSocket binds asynchronously: `start()` returning says nothing about the
                // bind, so wait for the real outcome before claiming to be up. Reporting RUNNING and
                // advertising over mDNS after a failed bind left every client refused while the
                // dashboard still read "running" (found on device).
                try {
                    withTimeout(SERVER_START_TIMEOUT_MS) { ws.awaitBound() }
                } catch (e: Exception) {
                    Log.e(TAG, "Server failed to bind port ${ServerProtocol.DEFAULT_PORT}", e)
                    ServerRuntime.shared.onFailed(e.message ?: "The server could not bind its port")
                    ws.shutdown()
                    server = null
                    stopSelf()
                    return@launch
                }
                advertise(serverIdentity, identity.spkiSha256, paired = pairedStore.hasAnyEnabled())
                val endpoint = ServerEndpoint.localIpv4()?.let { ServerEndpoint.url(it) }
                ServerRuntime.shared.onRunning(
                    endpoint = endpoint,
                    startedAtRealtimeMs = SystemClock.elapsedRealtime(),
                    timeoutMinutes = timeoutMinutes,
                )
                Log.i(TAG, "Server started: id=$deviceId name=${serverIdentity.serverName} spki=${identity.spkiSha256} endpoint=$endpoint")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start the server", e)
                ServerRuntime.shared.onFailed(e.message ?: "The server failed to start")
                stopSelf()
            }
        }
    }

    private fun startTimeout() {
        val endsAt = endsAtRealtimeMs
        if (endsAt == null) {
            Log.i(TAG, "Session timeout disabled (manual stop)")
            return
        }
        Log.i(TAG, "Auto-stop armed for $timeoutMinutes min (not persisted across process death)")
        scheduleStopAt(endsAt)
    }

    private fun scheduleStopAt(endsAtRealtimeMs: Long) {
        timeoutJob?.cancel()
        val delayMs = maxOf(0L, endsAtRealtimeMs - SystemClock.elapsedRealtime())
        timeoutJob = scope.launch {
            delay(delayMs)
            Log.i(TAG, "Session timeout reached; stopping")
            stopSelf()
        }
    }

    /** `＋ 30 min` from the notification: move the deadline, reschedule, and refresh the countdown. */
    private fun extendSession(minutes: Int) {
        val current = endsAtRealtimeMs
        if (current == null) {
            Log.i(TAG, "Ignoring extend: the session is manual")
            return
        }
        val newEndsAt = ServerSessionTiming.extendedDeadline(current, SystemClock.elapsedRealtime(), minutes)
        endsAtRealtimeMs = newEndsAt
        ServerRuntime.shared.onDeadlineMoved(newEndsAt)
        Log.i(TAG, "Session extended by $minutes min")
        scheduleStopAt(newEndsAt)
        updateNotification()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
    }

    // --- mDNS (PROTOCOL.md §2) ---------------------------------------------------------------------

    private fun advertise(identity: ServerIdentity, spkiSha256: String, paired: Boolean) {
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
                .txtAttributes(identity.deviceId, identity.serverName, spkiSha256, paired = paired)
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

    /**
     * The ongoing notification (Chunk 2a deep-link; D-136 countdown + actions). Tapping it returns to
     * the Server screen; the actions talk to this running service, so the app is never needed. The
     * countdown is the **system chronometer** — it ticks itself, so nothing re-notifies per second.
     */
    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_OPEN_SERVER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sovereign Server")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pending)
            .addAction(0, "Stop", servicePendingIntent(ACTION_STOP, REQ_STOP))

        val endsAt = endsAtRealtimeMs
        if (endsAt != null) {
            builder
                .setContentText("Ready to dictate")
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                // NotificationCompat wants a wall-clock target; convert from the elapsedRealtime deadline.
                .setWhen(System.currentTimeMillis() + (endsAt - SystemClock.elapsedRealtime()))
                .addAction(0, "＋ 30 min", servicePendingIntent(ACTION_EXTEND, REQ_EXTEND))
        } else {
            builder.setContentText("On until stopped")
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, SovereignServerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // --- Identity --------------------------------------------------------------------------------

    /** A stable per-install UUID, generated on first run and never reused (PROTOCOL.md §0). */
    private fun installDeviceId(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_DEVICE_ID, id) }
        return id
    }

    companion object {
        private const val TAG = "SovereignServer"
        private const val CHANNEL_ID = "sovereign_server"
        private const val NOTIFICATION_ID = 41
        private const val PREFS_NAME = "sovereign_server"
        private const val KEY_DEVICE_ID = "device_id"

        /**
         * How long to wait for the socket to bind before declaring the start failed. Java-WebSocket
         * reports the bind result asynchronously; without this bound a stuck bind would hang the
         * start coroutine instead of surfacing an error.
         */
        private const val SERVER_START_TIMEOUT_MS = 5_000L

        /** Set on the notification's `Intent` so `MainActivity` can open the Server screen. */
        const val ACTION_OPEN_SERVER = "io.github.alexeikramm.sovereign.action.OPEN_SERVER"

        /** Optional override for the device walkthrough / the dashboard's timeout selector. */
        const val EXTRA_TIMEOUT_MINUTES = "timeout_minutes"

        /** Command action (D-133): close the live connection for [EXTRA_DEVICE_ID]. */
        const val ACTION_DISCONNECT_CLIENT = "io.github.alexeikramm.sovereign.action.DISCONNECT_CLIENT"

        /** The client to drop, for [ACTION_DISCONNECT_CLIENT]. */
        const val EXTRA_DEVICE_ID = "device_id"

        /** Notification action (D-136): stop the server now. */
        const val ACTION_STOP = "io.github.alexeikramm.sovereign.action.SERVER_STOP"

        /** Notification action (D-136): add [ServerSessionTiming.EXTEND_MINUTES] to the auto-stop. */
        const val ACTION_EXTEND = "io.github.alexeikramm.sovereign.action.SERVER_EXTEND"

        private const val REQ_STOP = 1
        private const val REQ_EXTEND = 2
    }
}
