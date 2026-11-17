package com.animo.app.player

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Referer allowlist for the megaplay page hop and every CDN hop after it.
 *
 * research/RESOLVER_SPEC.md §1: `Referer` is the only gate (the `?token=` HMAC buys nothing), and any
 * of the ~34 allowlisted domains works — it is a presence check, not a host match. That is why one
 * referer value is reused for playlist + segments instead of being derived per URL.
 *
 * research/RESOLVER_SPEC.md §5: the `/domains` allowlist has a 6 h TTL and is treated as data
 * (domains rotate), never as constants — but a failed refresh must never block playback, so this
 * object always keeps the measured list as a floor.
 */
object Referers {

    private const val DOMAINS_ENDPOINT = "https://megaplay.buzz/domains"
    private const val TTL_MS = 6L * 60L * 60L * 1000L

    /** Measured working list; the first entry resolved 15/15 embeds during research. */
    private val MEASURED_REFERERS = listOf(
        "https://anikototv.bz/",
        "https://megaplay.buzz/",
        "https://anikototv.to/",
        "https://anikoto.cz/",
    )

    private val current = AtomicReference(MEASURED_REFERERS)
    private val refreshedAtMs = AtomicLong(0L)
    private val refreshMutex = Mutex()

    private val HOST_REGEX =
        Regex("""(?i)\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,24}\b""")

    /** Referers to try, best-known first. Always non-empty. */
    val candidates: List<String>
        get() = current.get()

    fun primary(): String = candidates.firstOrNull() ?: MEASURED_REFERERS.first()

    /**
     * Pulls the rotating allowlist from `/domains?h=<UTC yyyyMMddHH>` and AES-decrypts it with the
     * same key/IV as `getSources` (research/RESOLVER_SPEC.md §1 hop 3). Never throws: on any failure
     * the previously known list is returned unchanged.
     */
    suspend fun refreshFromDomains(client: OkHttpClient, force: Boolean = false): List<String> =
        refreshMutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && now - refreshedAtMs.get() < TTL_MS) {
                return@withLock candidates
            }
            val discovered = try {
                fetchDomains(client, now)
            } catch (e: IOException) {
                emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            if (discovered.isNotEmpty()) {
                current.set(merge(discovered))
                refreshedAtMs.set(now)
            }
            candidates
        }

    private fun fetchDomains(client: OkHttpClient, nowMs: Long): List<String> {
        val request = Request.Builder()
            .url("$DOMAINS_ENDPOINT?h=${hourStamp(nowMs)}")
            .header("Referer", primary())
            .header("User-Agent", Media3Factory.DEFAULT_USER_AGENT)
            .header("Accept", "*/*")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            parseDomains(response.body?.string().orEmpty())
        }
    }

    private fun hourStamp(nowMs: Long): String =
        SimpleDateFormat("yyyyMMddHH", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(nowMs))

    /**
     * The endpoint has shipped both plaintext and encrypted bodies; accept either. Base64 ciphertext
     * cannot contain dots, so "did we find hostnames?" is a reliable discriminator.
     */
    private fun parseDomains(body: String): List<String> {
        val text = body.trim()
        if (text.isEmpty()) return emptyList()

        hostsFrom(text).takeIf { it.isNotEmpty() }?.let { return it }

        val cipher = JsonLite.stringField(text, "domains")
            ?: JsonLite.stringField(text, "data")
            ?: JsonLite.stringField(text, "result")
            ?: text.trim('"')
        val plain = try {
            MegaplayAes.decrypt(cipher)
        } catch (e: Exception) {
            ""
        }
        return if (plain.isBlank()) emptyList() else hostsFrom(plain)
    }

    private fun hostsFrom(text: String): List<String> =
        HOST_REGEX.findAll(text)
            .map { it.value.lowercase(Locale.US) }
            .filter { host -> !host.all { it.isDigit() || it == '.' } }
            .distinct()
            .map { host -> "https://$host/" }
            .toList()

    private fun merge(discovered: List<String>): List<String> {
        val merged = LinkedHashSet<String>(discovered.size + MEASURED_REFERERS.size)
        merged.addAll(discovered)
        merged.addAll(MEASURED_REFERERS)
        return merged.toList()
    }
}
