package fi.ak.smartvoice.cleanup

import android.content.Context
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ModelPaths
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.ModelType
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * Whether the SDK is usable, in three states rather than two — because "init returned" and "init
 * confirmed" are not the same fact, and the difference mattered during the spike (D-029):
 * the reference probe called `init` and used the model manager immediately without waiting for the
 * callback, and the pull worked. Rather than assume either way, the state is reported and shown.
 */
sealed interface SdkState {
    /** `init` called back with success. */
    data object Ready : SdkState

    /** The callback never arrived in time; the model manager is still attempted (see above). */
    data object Unconfirmed : SdkState

    /** `init` reported a failure, which is surfaced instead of being swallowed. */
    data class NotReady(val message: String) : SdkState
}

/**
 * The GenieX model manager, wrapped so that exactly **one file in the app** touches the SDK's
 * storage API. D-030 accepted two download paths on purpose: ASR is our own sha256-pinned downloader
 * (`asr/ModelDownloader.kt`), while the cleanup model uses the SDK's manager, because the SDK knows
 * which files its own runtime needs for a given GGUF. Keeping that in one file keeps the cost — two
 * managers, two failure modes — from spreading through the app.
 *
 * Every call happens off the main thread: this reaches native code and the network. Failures are
 * returned, never swallowed: a model that is silently absent is the bug this screen exists to
 * prevent.
 */
object CleanupModelStore {

    /**
     * How long to wait for `init`'s callback before proceeding anyway. The probe's behaviour (use
     * the manager right after `init`) is the only thing verified on the phone, so a slow or absent
     * callback must not block the screen forever.
     */
    private const val INIT_TIMEOUT_MS = 10_000L

    private val initLock = Mutex()

    @Volatile
    private var sdkState: SdkState? = null

    /**
     * `GenieXSdk.init` before anything else: it registers the plugins (which is what makes the
     * native libraries' *extraction* matter, docs/build.md) and initialises the model manager.
     *
     * Success is memoised. A failure is **not**, so a later attempt can still succeed — for example
     * once the native libraries have been extracted for the first time.
     */
    suspend fun ensureReady(context: Context): SdkState {
        sdkState?.let { return it }
        return initLock.withLock {
            sdkState?.let { return@withLock it }
            val outcome: SdkState = withContext(Dispatchers.IO) {
                withTimeoutOrNull(INIT_TIMEOUT_MS) {
                    suspendCancellableCoroutine<SdkState> { continuation ->
                        val callback = object : GenieXSdk.InitCallback {
                            override fun onSuccess() =
                                continuation.resumeIfActive(SdkState.Ready)

                            override fun onFailure(message: String) =
                                continuation.resumeIfActive(SdkState.NotReady(message))
                        }
                        runCatching { GenieXSdk.getInstance().init(context.applicationContext, callback) }
                            .onFailure { error ->
                                continuation.resumeIfActive(
                                    SdkState.NotReady(error.message ?: error::class.java.simpleName),
                                )
                            }
                    }
                }
            } ?: SdkState.Unconfirmed

            if (outcome is SdkState.Ready || outcome is SdkState.Unconfirmed) sdkState = outcome
            outcome
        }
    }

    /**
     * Where the runtime would find this model, or null if it is not pulled.
     *
     * "Pulled" is verified rather than inferred: `getPaths` can return a `ModelPaths` whose
     * `model_path` is empty, and the file on disk is the thing that actually matters at load time.
     */
    suspend fun paths(context: Context, model: CleanupModel): ModelPaths? {
        if (ensureReady(context) is SdkState.NotReady) return null
        val paths = withContext(Dispatchers.IO) {
            runCatching { ModelManagerWrapper.getPaths(model.repository) }.getOrNull()
        } ?: return null
        if (paths.model_path.isBlank()) return null
        if (!File(paths.model_path).exists()) return null
        return paths
    }

    /** Bytes actually on disk, so the screen can show what the download really cost. */
    suspend fun installedBytes(context: Context, model: CleanupModel): Long {
        val paths = paths(context, model) ?: return 0L
        val directory = paths.model_dir.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(paths.model_path).parentFile
            ?: return File(paths.model_path).length()
        return withContext(Dispatchers.IO) {
            directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }

    /**
     * The SDK's own progress stream. `hub = AUTO` and an explicit `ModelType.LLM` are set rather
     * than left to defaults: this is a GGUF text model, and a wrong model type is a load-time
     * failure, not a download-time one. `chipset` is deliberately left unset — the AI-Hub (`qairt`)
     * path needs it (D-009), this Hugging Face GGUF path does not, and setting a wrong one there
     * would be the only way to break it.
     */
    fun pull(model: CleanupModel): Flow<ModelManagerWrapper.PullEvent> =
        ModelManagerWrapper.pullFlow(
            ModelPullInput(
                model_name = model.repository,
                precision = model.precision,
                hub = com.geniex.sdk.bean.HubSource.AUTO,
                display_name = model.label,
                model_type = ModelType.LLM,
            ),
        )

    /**
     * Deletes the model through the SDK, then **re-checks** rather than trusting the return code:
     * the caller is told what is true afterwards, not what a code claimed.
     */
    suspend fun remove(context: Context, model: CleanupModel): Result<Unit> {
        if (ensureReady(context) is SdkState.NotReady) {
            return Result.failure(IllegalStateException("SDK not ready — nothing was deleted"))
        }
        val code = withContext(Dispatchers.IO) {
            runCatching { ModelManagerWrapper.remove(model.repository) }
        }
        code.exceptionOrNull()?.let { return Result.failure(it) }
        return if (paths(context, model) == null) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("the model is still on disk after remove (code ${code.getOrNull()})"))
        }
    }

    private fun <T> CancellableContinuation<T>.resumeIfActive(value: T) {
        if (isActive) resume(value)
    }
}
