package io.github.alexeikramm.sovereign.server

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.alexeikramm.sovereign.MainActivity
import io.github.alexeikramm.sovereign.R
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The shipped [PairingPrompter] (Chunk 2b): surfaces an approval request as the in-app dialog (the
 * Server screen collects [pending]) and, so a docked phone does not need the app open, as a
 * high-priority notification that deep-links to that screen.
 *
 * [prompt] suspends until the user taps Approve/Deny ([resolve]) or the desktop's connection goes
 * away, which cancels the awaiting coroutine and clears the request. Only one request is shown at a
 * time ([gate]); a second desktop waits rather than stacking dialogs.
 */
object AppPairingPrompter : PairingPrompter {

    private val _pending = MutableStateFlow<PairingRequest?>(null)
    val pending: StateFlow<PairingRequest?> = _pending.asStateFlow()

    private val gate = Mutex()
    private val ids = AtomicLong()
    private var continuation: CancellableContinuation<Boolean>? = null
    private var appContext: Context? = null

    /** Called by the service before it can be asked to pair; notifications need a context. */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    override suspend fun prompt(request: PairingRequest): Boolean = gate.withLock {
        suspendCancellableCoroutine { cont ->
            val id = ids.incrementAndGet()
            val shown = request.copy(id = id)
            continuation = cont
            _pending.value = shown
            postNotification(shown)
            cont.invokeOnCancellation {
                if (_pending.value?.id == id) {
                    continuation = null
                    _pending.value = null
                    cancelNotification()
                }
            }
        }
    }

    /** The dialog's answer. Ignored if [id] is no longer the pending request. */
    fun resolve(id: Long, approved: Boolean) {
        val cont = continuation ?: return
        if (_pending.value?.id != id) return
        continuation = null
        _pending.value = null
        cancelNotification()
        cont.resume(approved)
    }

    /** Drops any outstanding request, e.g. when the server stops while one is on screen. */
    fun dismiss() {
        continuation?.cancel()
        continuation = null
        _pending.value = null
        cancelNotification()
    }

    // --- Notification --------------------------------------------------------------------------

    private fun postNotification(request: PairingRequest) {
        val context = appContext ?: return
        // POST_NOTIFICATIONS is optional: when it is denied the in-app dialog is the only cue, and
        // posting would throw. Declared and auto-granted below API 33.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java)
            .setAction(SovereignServerService.ACTION_OPEN_SERVER)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Pairing request")
            .setContentText("${request.name} wants to pair")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "Could not post the pairing notification", it) }
    }

    private fun cancelNotification() {
        val context = appContext ?: return
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Pairing requests", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "A desktop wants to pair with this phone" }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private const val CHANNEL_ID = "sovereign_pairing"
    private const val NOTIFICATION_ID = 42
    private const val TAG = "SovereignServer"
}
