package com.animo.app.di

import android.content.Context
import com.animo.app.AppLog
import com.animo.app.data.local.AnimoDatabase
import com.animo.app.data.local.dao.AnimeDao
import com.animo.app.data.local.dao.EpisodeDao
import com.animo.app.data.local.dao.ProgressDao
import com.animo.app.data.remote.AnikotoApi
import com.animo.app.data.remote.HttpClientFactory
import com.animo.app.data.remote.dto.AnimoJson
import com.animo.app.data.remote.provider.AnikotoProvider
import com.animo.app.data.remote.provider.AnimeProvider
import com.animo.app.data.repo.AnimeRepositoryImpl
import com.animo.app.data.repo.StreamRepositoryImpl
import com.animo.app.data.sync.CatalogSyncer
import com.animo.app.data.sync.EpisodeBackfill
import com.animo.app.data.sync.SeriesFetcher
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.PlayableStream
import com.animo.app.data.repo.RoutingStreamRepository
import com.animo.app.domain.repo.AnimeRepository
import com.animo.app.domain.repo.HindiCatalog
import com.animo.app.domain.repo.StreamRepository
import com.animo.app.player.ResolveException
import com.animo.app.player.StreamResolver
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Manual dependency container (no Hilt — STACK.md rule 7).
 *
 * `AnimoApp` calls [install] before anything else; every getter is a process-wide lazy singleton.
 * The container is intentionally *not* an `Application` field so `WorkManager` (which builds the
 * worker before/independently of UI state) can call [install] with the same application context and
 * get the same instances.
 *
 * ## Contract for the other build agents
 * - **Agent B (UI)** — `ServiceLocator.animeRepository` and nothing else. It is an
 *   `AnimeRepository`, so it never sees Room, OkHttp or DTOs.
 * - **Agent C (Player)** — `ServiceLocator.streamRepository` for resolution and
 *   `ServiceLocator.httpClient` for the Media3 `OkHttpDataSource`. If your resolver needs a custom
 *   constructor, call `ServiceLocator.registerStreamResolver(...)` once during app start, or keep a
 *   public `(OkHttpClient)` constructor and it will be found reflectively.
 */
object ServiceLocator {

    /** Class the container looks for when nobody registered a resolver explicitly. */
    private const val STREAM_RESOLVER_CLASS = "com.animo.app.player.MegaplayStreamResolver"

    @Volatile
    private var applicationContext: Context? = null

    private val explicitResolver = AtomicReference<StreamResolver?>(null)

    /**
     * The Hindi layer (catalogue + resolver) is registered by the app once it is constructed, because
     * it owns two OkHttp-backed providers and a JS-free decoder that the rest of the graph never
     * needs. `null` is a supported state: every Hindi path then fails with a message instead of a
     * `NoClassDefFoundError`.
     */
    private val hindiCatalogRef = AtomicReference<HindiCatalog?>(null)

    private val hindiStreamRef = AtomicReference<StreamRepository?>(null)

    /**
     * Idempotent. Safe to call from `Application.onCreate` and from a `CoroutineWorker`.
     */
    fun install(context: Context) {
        val app = context.applicationContext ?: context
        if (applicationContext == null) {
            synchronized(this) {
                if (applicationContext == null) {
                    applicationContext = app
                    AppLog.i("container installed")
                }
            }
        }
    }

    fun isInstalled(): Boolean = applicationContext != null

    // ---------------------------------------------------------------- infrastructure

    val httpClient: OkHttpClient by lazy { HttpClientFactory.create() }

    val json: Json by lazy { AnimoJson }

    val api: AnikotoApi by lazy { AnikotoApi(client = httpClient, json = json) }

    val database: AnimoDatabase by lazy { AnimoDatabase.build(requireContext()) }

    val animeDao: AnimeDao by lazy { database.animeDao() }

    val episodeDao: EpisodeDao by lazy { database.episodeDao() }

    val progressDao: ProgressDao by lazy { database.progressDao() }

    // ---------------------------------------------------------------- data layer

    val seriesFetcher: SeriesFetcher by lazy {
        SeriesFetcher(api = api, db = database, animeDao = animeDao, episodeDao = episodeDao)
    }

    val catalogSyncer: CatalogSyncer by lazy {
        CatalogSyncer(api = api, db = database, animeDao = animeDao, seriesFetcher = seriesFetcher)
    }

    val episodeBackfill: EpisodeBackfill by lazy {
        EpisodeBackfill(seriesFetcher = seriesFetcher, animeDao = animeDao)
    }

    val animeRepository: AnimeRepository by lazy {
        AnimeRepositoryImpl(
            db = database,
            animeDao = animeDao,
            episodeDao = episodeDao,
            progressDao = progressDao,
            catalogSyncer = catalogSyncer,
            episodeBackfill = episodeBackfill,
            hindiCatalog = { hindiCatalogRef.get() },
        )
    }

    val streamRepository: StreamRepository by lazy {
        RoutingStreamRepository(
            original = StreamRepositoryImpl(resolverProvider = { streamResolver }),
            hindi = { hindiStreamRef.get() },
        )
    }

    /** Read by the UI to show Hindi counts and to trigger a Hindi sync; `null` until registered. */
    val hindiCatalog: HindiCatalog? get() = hindiCatalogRef.get()

    fun registerHindiCatalog(catalog: HindiCatalog) {
        hindiCatalogRef.set(catalog)
        AppLog.i("hindi catalogue registered: ${catalog.javaClass.simpleName}")
    }

    fun registerHindiStreamRepository(repository: StreamRepository) {
        hindiStreamRef.set(repository)
        AppLog.i("hindi stream repository registered: ${repository.javaClass.simpleName}")
    }

    // ---------------------------------------------------------------- providers

    /** P0 chain. ReAnime/AnimeEgg join here once their recipes are ported. */
    val providerChain: List<AnimeProvider> by lazy {
        listOf(AnikotoProvider(api = api, resolverProvider = { streamResolver }))
    }

    // ---------------------------------------------------------------- stream resolver

    /**
     * Overrides the reflective lookup — call before the first playback if your resolver takes a
     * constructor this container cannot guess (see class KDoc).
     */
    fun registerStreamResolver(resolver: StreamResolver) {
        explicitResolver.set(resolver)
        AppLog.i("stream resolver registered: ${resolver.javaClass.simpleName}")
    }

    /**
     * Lazily instantiated, exactly once per process. Reads the explicit registration first, then
     * tries — in order — `(OkHttpClient)`, `(Context, OkHttpClient)` and `()` constructors of
     * [STREAM_RESOLVER_CLASS].
     *
     * The player module is compiled separately from this one, so this is the single place in the
     * build that is allowed to be class-name-driven. Until it exists, [UnavailableStreamResolver]
     * keeps the app compiling and turns playback into a normal `Result.failure` instead of a crash.
     */
    val streamResolver: StreamResolver
        get() = explicitResolver.get() ?: reflectedResolver

    private val reflectedResolver: StreamResolver by lazy { loadStreamResolver() }

    private fun loadStreamResolver(): StreamResolver {
        val resolverClass = runCatching { Class.forName(STREAM_RESOLVER_CLASS) }.getOrNull()
        if (resolverClass == null) {
            AppLog.w("$STREAM_RESOLVER_CLASS not present yet — playback reports unavailable")
            return UnavailableStreamResolver
        }

        val instance = instantiate(resolverClass)
        if (instance == null) {
            AppLog.e("no usable constructor on $STREAM_RESOLVER_CLASS")
            return UnavailableStreamResolver
        }

        return instance as? StreamResolver ?: run {
            AppLog.e("$STREAM_RESOLVER_CLASS does not implement StreamResolver")
            UnavailableStreamResolver
        }
    }

    /**
     * Instantiates the resolver without a compile-time dependency on the player module.
     *
     * Two passes, because a Kotlin class with default arguments compiles to **one** full-arity
     * constructor plus a synthetic `(..., int, DefaultConstructorMarker)` one — the convenient
     * `(OkHttpClient)` form does not exist in the bytecode unless the author added `@JvmOverloads`.
     */
    private fun instantiate(resolverClass: Class<*>): Any? {
        val direct: List<Array<Class<*>>> = listOf(
            arrayOf(OkHttpClient::class.java),
            arrayOf(Context::class.java, OkHttpClient::class.java),
            emptyArray(),
        )

        for (signature in direct) {
            val candidate = runCatching {
                val args = signature.map { type ->
                    when {
                        type == OkHttpClient::class.java -> httpClient
                        type == Context::class.java -> requireContext()
                        else -> error("unsupported parameter $type")
                    }
                }.toTypedArray()
                resolverClass.getConstructor(*signature).newInstance(*args)
            }.getOrNull()
            if (candidate != null) return candidate
        }

        // Kotlin default-argument constructor: (anchor..., argN, mask:Int, marker). Mask bits mark
        // which trailing arguments are defaulted; the marker is ignored by the generated body.
        for (constructor in resolverClass.constructors) {
            val parameterTypes = constructor.parameterTypes
            if (parameterTypes.size < 3) continue
            if (!parameterTypes.last().name.endsWith("DefaultConstructorMarker")) continue

            val anchor = when {
                parameterTypes.first() == OkHttpClient::class.java -> httpClient
                parameterTypes.first() == Context::class.java -> requireContext()
                else -> continue
            }

            val realCount = parameterTypes.size - 2
            val args = arrayOfNulls<Any?>(parameterTypes.size)
            args[0] = anchor
            // Everything from index 1 on is masked out, so null is never dereferenced.
            args[realCount] = (1 shl realCount) - 2
            args[realCount + 1] = null

            val candidate = runCatching { constructor.newInstance(*args) }.getOrNull()
            if (candidate != null) return candidate
        }

        return null
    }

    private fun requireContext(): Context = applicationContext
        ?: error("ServiceLocator.install(context) was never called")

    /**
     * Stand-in while the player module is not compiled in. Returning a normal failure (instead of
     * `TODO()`) keeps every UI path exercised and shows the user a real error message.
     */
    private object UnavailableStreamResolver : StreamResolver {
        override suspend fun resolve(
            embedUrl: String,
            audio: AudioTrack,
        ): Result<PlayableStream> = Result.failure(
            ResolveException.Parse("stream resolver unavailable (player module not installed)"),
        )
    }
}
