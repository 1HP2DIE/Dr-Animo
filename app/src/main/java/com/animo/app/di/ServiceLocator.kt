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
import com.animo.app.net.OkHttpTransportChannel
import com.animo.app.net.TransportRouter
import com.animo.app.net.webview.WebViewChannel
import com.animo.app.player.ResolveException
import com.animo.app.player.StreamResolver
import com.animo.app.player.AltStreamSource
import com.animo.app.provider.ProviderVault
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
                    // Endpoints are decrypted once, here, before anything can ask for one.
                    ProviderVault.install(app)
                    AppLog.i("container installed")
                }
            }
        }
    }

    fun isInstalled(): Boolean = applicationContext != null

    // ---------------------------------------------------------------- infrastructure

    val httpClient: OkHttpClient by lazy { HttpClientFactory.create(requireContext()) }

    /**
     * The fallback ladder every third-party host goes through: the shared OkHttp client first, the
     * invisible System WebView last (it can solve a JS challenge and leaves the clearance cookie in
     * the same `CookieManager` [httpClient] reads from).
     *
     * Lazy on purpose: building the channels touches no WebView and opens no socket, but there is
     * also no reason to pay for it in `onCreate`.
     */
    val transport: TransportRouter by lazy {
        val webView = WebViewChannel(requireContext(), httpClient)
        val router = TransportRouter(
            listOf(
                OkHttpTransportChannel(httpClient),
                webView,
            ),
            log = AppLog::d,
        )
        // A clearance and a cooldown are both statements about the IP that earned them: after a
        // Wi-Fi to mobile switch neither is true any more, so the ladder starts clean.
        HttpClientFactory.addNetworkChangeListener {
            webView.clearAll()
            router.resetBudget()
        }
        router
    }

    val json: Json by lazy { AnimoJson }

    val api: AnikotoApi by lazy { AnikotoApi(client = httpClient, json = json) }

    val database: AnimoDatabase by lazy { AnimoDatabase.build(requireContext()) }

    val animeDao: AnimeDao by lazy { database.animeDao() }

    val episodeDao: EpisodeDao by lazy { database.episodeDao() }

    val progressDao: ProgressDao by lazy { database.progressDao() }

    val favorites: com.animo.app.data.repo.FavoritesRepository by lazy {
        com.animo.app.data.repo.FavoritesRepository(database.favoriteDao())
    }

    // ---------------------------------------------------------------- data layer

    val seriesFetcher: SeriesFetcher by lazy {
        SeriesFetcher(api = api, db = database, animeDao = animeDao, episodeDao = episodeDao)
    }

    val catalogSyncer: CatalogSyncer by lazy {
        CatalogSyncer(api = api, db = database, animeDao = animeDao, seriesFetcher = seriesFetcher)
    }

    val episodeBackfill: EpisodeBackfill by lazy {
        EpisodeBackfill(seriesFetcher = seriesFetcher, animeDao = animeDao, favoriteDao = database.favoriteDao())
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
            recordSynced = { epochMs ->
                com.animo.app.ui.settings.SettingsPreferences(requireContext()).recordSync(epochMs)
            },
        )
    }

    /** What the player resolves through: a downloaded episode first, the network otherwise. */
    val streamRepository: StreamRepository by lazy {
        com.animo.app.download.OfflineFirstStreamRepository(
            network = networkStreamRepository,
            local = { episodeId, audio -> downloads.localStream(episodeId, audio) },
        )
    }

    val downloads: com.animo.app.download.DownloadCenter by lazy {
        com.animo.app.download.DownloadCenter(
            context = requireContext(),
            animeRepository = animeRepository,
            streamRepository = { networkStreamRepository },
            client = httpClient,
        )
    }

    val networkStreamRepository: StreamRepository by lazy {
        RoutingStreamRepository(
            original = StreamRepositoryImpl(
                resolverProvider = { streamResolver },
                // Second chance for episodes the embed chain cannot open, in up to seven audio
                // tracks. The AniList id comes from the catalogue row, read on demand.
                alternative = AltStreamSource(httpClient),
                anilistIdOf = { id -> animeDao.findById(id)?.aniId?.takeIf { it.isNotBlank() } },
                animeTitlesOf = { id ->
                    animeDao.findById(id)?.let { row -> listOf(row.title, row.alternative) }.orEmpty()
                },
                animeYearOf = { id -> animeDao.findById(id)?.year },
                fallbacks = listOf(
                    com.animo.app.player.MirrorStreamSource(httpClient),
                    com.animo.app.player.ArchiveStreamSource(httpClient),
                ),
            ),
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

    /**
     * Providers the app adds at start-up, after the P0 chain. Kept in a separate list so a provider
     * whose slots are not provisioned can only *shorten* the chain: the P0 entry is never replaced,
     * and an empty list is a supported state.
     */
    @Volatile
    private var extraProviders: List<AnimeProvider> = emptyList()

    fun registerProvider(provider: AnimeProvider) {
        synchronized(this) { extraProviders = extraProviders + provider }
        AppLog.i("catalogue provider registered: ${provider.javaClass.simpleName}")
    }

    private val p0Provider: AnimeProvider by lazy {
        AnikotoProvider(api = api, resolverProvider = { streamResolver })
    }

    /** P0 chain. ReAnime/AnimeEgg join here once their recipes are ported. */
    val providerChain: List<AnimeProvider>
        get() = listOf(p0Provider) + extraProviders

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
     * Exactly one resolver per process, registered by `AnimoApp` rather than looked up by class name.
     *
     * The old reflective lookup had two problems: it broke under R8 (the class gets renamed), and it
     * left the provider's name as a string in the DEX. A direct registration fixes both.
     */
    val streamResolver: StreamResolver
        get() = explicitResolver.get() ?: UnavailableStreamResolver

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
