package com.animo.app

import android.app.Application
import com.animo.app.data.hindi.HindiCatalogImpl
import com.animo.app.data.hindi.HindiStreamRepositoryImpl
import com.animo.app.data.hindi.RareAnimesProvider
import com.animo.app.data.hindi.ToonoProvider
import com.animo.app.data.remote.HttpClientFactory
import com.animo.app.data.remote.provider.movielink.MovieLinkProvider
import com.animo.app.data.sync.SyncScheduler
import com.animo.app.di.ServiceLocator
import com.animo.app.player.MegaplayStreamResolver
import com.animo.app.player.movielink.MovieLinkStreamResolver
import com.animo.app.provider.ProviderKey
import com.animo.app.provider.ProviderVault

/**
 * Process entry point. Order matters:
 *
 * 1. install the container (everything else calls into it),
 * 2. schedule the nightly catalog sync (idempotent, `KEEP`),
 * 3. nothing else — no eager network, no eager DB open. The first screen decides what it needs, and
 *    "cache or crash" means an empty catalog is a valid, renderable state.
 */
class AnimoApp : Application() {

    override fun onCreate() {
        super.onCreate()

        ServiceLocator.install(this)
        AppLog.i("Animo ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) starting")

        // Deterministic resolver wiring (skips the reflective lookup in ServiceLocator).
        ServiceLocator.registerStreamResolver(
            MegaplayStreamResolver(client = ServiceLocator.httpClient),
        )

        // Second catalogue source. Both host slots are optional: when either is missing the provider
        // is not registered at all, so the chain stays at P0 and start-up never throws.
        val movieLinkIndex = ProviderVault.optionalUrl(ProviderKey.MOVIELINK_INDEX_HOST)
        val movieLinkReferer = ProviderVault.optionalUrl(ProviderKey.MOVIELINK_WATCH_REFERER)
        if (movieLinkIndex != null && movieLinkReferer != null) {
            val movieLinkResolver = MovieLinkStreamResolver(
                client = ServiceLocator.httpClient,
                watchReferer = movieLinkReferer,
                userAgent = ProviderVault.optionalUrl(ProviderKey.MOVIELINK_AVATAR_UA)
                    ?: HttpClientFactory.UA_DESKTOP,
            )
            ServiceLocator.registerProvider(
                MovieLinkProvider(
                    client = ServiceLocator.httpClient,
                    indexHost = movieLinkIndex,
                    resolverProvider = { movieLinkResolver },
                ),
            )
        } else {
            AppLog.w(
                "catalogue provider skipped: slots " +
                    "${ProviderKey.MOVIELINK_INDEX_HOST.slot}/" +
                    "${ProviderKey.MOVIELINK_WATCH_REFERER.slot} not provisioned",
            )
        }

        // Hindi sources: two providers, one catalogue, one resolver. Registered rather than built by
        // the container so every Hindi path degrades to a readable failure if it is missing.
        val hindiHttp = ServiceLocator.httpClient
        val toono = ToonoProvider(hindiHttp)
        val rareAnimes = RareAnimesProvider(hindiHttp)
        ServiceLocator.registerHindiCatalog(
            HindiCatalogImpl(
                animeDao = ServiceLocator.animeDao,
                episodeDao = ServiceLocator.episodeDao,
                toono = toono,
                rareAnimes = rareAnimes,
                log = AppLog::d,
            ),
        )
        ServiceLocator.registerHindiStreamRepository(
            HindiStreamRepositoryImpl(
                http = hindiHttp,
                toono = toono,
                rareAnimes = rareAnimes,
                log = AppLog::d,
            ),
        )

        SyncScheduler.ensureScheduled(this)
        SyncScheduler.syncAiredSoon(this)
    }
}
