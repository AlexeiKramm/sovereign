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

    /**
     * Bytes on disk for **this model only**.
     *
     * Measured from the files the SDK says belong to it — `model_path`, `tokenizer_path`,
     * `mmproj_path` — and never by walking a directory. The model manager nests every model under one
     * shared cache root, so a directory walk counts the *other* models too: it reported 4.34 GB for a
     * 3.35 GB model, with a leftover 0.6B probe model from the spike inside that number (found on the
     * device, 2026-09-12). A size the user cannot trust is worse than no size.
     */
    suspend fun installedBytes(context: Context, model: CleanupModel): Long =
        paths(context, model)?.ownFiles()?.sumOf { it.length() } ?: 0L

    /**
     * Every model the manager has locally, each with its own size — **including ids this app's
     * catalogue does not know about** (the spike's probe models, for instance). A cache the user
     * cannot see is a cache the user cannot reclaim, and the probe's 0.6B model is exactly that.
     */
    suspend fun installedModels(context: Context): List<CachedModel> {
        if (ensureReady(context) is SdkState.NotReady) return emptyList()
        val ids = withContext(Dispatchers.IO) {
            runCatching { ModelManagerWrapper.list() }.getOrNull().orEmpty()
        }
        return ids.map { id ->
            val bytes = withContext(Dispatchers.IO) {
                runCatching { ModelManagerWrapper.getPaths(id) }.getOrNull()
                    ?.ownFiles()?.sumOf { it.length() } ?: 0L
            }
            CachedModel(
                id = id,
                bytes = bytes,
                inCatalogue = CleanupModels.ALL.any { it.repository == id },
            )
        }.sortedByDescending { it.bytes }
    }

    private fun ModelPaths.ownFiles(): List<File> =
        // listOfNotNull, because the SDK's bean types mmproj_path (and friends) as nullable: an LLM
        // has no projector, and inventing one would be a load-time failure.
        listOfNotNull(model_path, tokenizer_path, mmproj_path)
            .filter { it.isNotBlank() }
            .map(::File)
            .filter { it.exists() }

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

    suspend fun remove(context: Context, model: CleanupModel): Result<Unit> =
        removeById(context, model.repository)

    /**
     * Deletes any model the manager knows, by id — a catalogue update or a leftover probe model must
     * not be undeletable just because this app never listed it. It then **re-checks** rather than
     * trusting the return code: the caller is told what is true afterwards, not what a code claimed.
     */
    suspend fun removeById(context: Context, id: String): Result<Unit> {
        if (ensureReady(context) is SdkState.NotReady) {
            return Result.failure(IllegalStateException("SDK not ready — nothing was deleted"))
        }
        val code = withContext(Dispatchers.IO) {
            runCatching { ModelManagerWrapper.remove(id) }
        }
        code.exceptionOrNull()?.let { return Result.failure(it) }
        val stillThere = withContext(Dispatchers.IO) {
            runCatching { ModelManagerWrapper.getPaths(id) }.getOrNull()?.ownFiles()?.isNotEmpty() == true
        }
        return if (stillThere) {
            Result.failure(IllegalStateException("still on disk after remove (code ${code.getOrNull()})"))
        } else {
            Result.success(Unit)
        }
    }

    private fun <T> CancellableContinuation<T>.resumeIfActive(value: T) {
        if (isActive) resume(value)
    }
}
