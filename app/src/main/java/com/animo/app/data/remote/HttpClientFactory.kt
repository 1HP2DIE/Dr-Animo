package com.animo.app.data.remote

import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/**
 * The single OkHttp client for the whole app (catalog, resolver, Coil, Media3 all share it so TLS
 * sessions and the connection pool are reused).
 *
 * Deliberately **log-free**: no HttpLoggingInterceptor, no body dumps. Episode payloads are large
 * and the API returns no secrets, so there is nothing worth the I/O.
 */
object HttpClientFactory {

    /** Desktop Chrome UA — the API and megaplay both serve the useful payloads to a browser UA. */
    const val UA_DESKTOP: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun create(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 4
                maxRequestsPerHost = 2
            },
        )
        .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
        .build()
}
