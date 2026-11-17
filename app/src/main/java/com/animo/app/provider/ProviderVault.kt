package com.animo.app.provider

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The only place a provider endpoint exists at runtime.
 *
 * **How it is built.** `secrets/providers.properties` (git-ignored) is encrypted at build time with
 * AES-256-GCM into `assets/pv.bin`. The key is not written anywhere as a literal: the Gradle task
 * splits it into four 64-bit chunks, masks each with its own random constant, and emits the pairs as
 * generated Kotlin. [VaultMaterial] recombines them at runtime.
 *
 * **What that buys.** A string dump of the APK, a decompiled class, or the repository itself names no
 * host. Extracting the asset is not enough — it is ciphertext, and the key exists only as masked
 * fragments in generated code that is itself obfuscated. Someone determined enough to hook the
 * process can still observe traffic; no client can prevent that for a client that must connect.
 *
 * **Cost.** One decrypt at startup (a few KB of AES-GCM — microseconds on ARMv8), then a plain map
 * lookup per use. Nothing on the request path.
 *
 * Fails closed: if the asset is missing, tampered with, or encrypted under another key, the map stays
 * empty and [url] throws a message that names neither the provider nor the host. Startup is unaffected,
 * because nothing calls into the vault until playback or sync actually needs an endpoint.
 */
object ProviderVault {

    private const val TAG = "AnimoVault"
    private const val ASSET = "pv.bin"
    private const val MAGIC = "PVA1"
    private const val IV_BYTES = 12
    private const val MAX_ENTRIES = 512

    @Volatile
    private var entries: Map<String, String> = emptyMap()

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            try {
                val bytes = context.applicationContext.assets.open(ASSET).use { it.readBytes() }
                entries = decode(bytes, VaultMaterial.keyBytes())
                installed = true
            } catch (e: Exception) {
                // Deliberately quiet and host-free: a log line is not allowed to become the leak.
                Log.e(TAG, "vault unavailable (${e.javaClass.simpleName})")
            }
        }
    }

    /** A required endpoint. Throws only with an opaque slot name. */
    fun url(key: ProviderKey): String = optionalUrl(key)
        ?: error("provider slot '${key.slot}' is not provisioned")

    fun optionalUrl(key: ProviderKey): String? = entries[key.slot]?.takeIf { it.isNotBlank() }

    /** A required plain value (marker, AES key material). */
    fun text(key: ProviderKey): String = url(key)

    /** A comma-separated slot, split and trimmed. Never throws. */
    fun list(key: ProviderKey): List<String> =
        optionalUrl(key)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    /** True once a vault has been decoded — used by tests and by the diagnostics screen, if any. */
    fun isInstalled(): Boolean = installed

    /**
     * Pure decoder, separated from asset loading so it is unit-testable and so the parsing rules are
     * reviewable without an Android device.
     */
    internal fun decode(asset: ByteArray, key: ByteArray): Map<String, String> {
        require(asset.size > MAGIC.length + IV_BYTES) { "vault asset is truncated" }
        require(key.size == 32) { "vault key must be 32 bytes" }

        val magic = String(asset, 0, MAGIC.length, Charsets.UTF_8)
        require(magic == MAGIC) { "vault magic mismatch" }

        val iv = asset.copyOfRange(MAGIC.length, MAGIC.length + IV_BYTES)
        val ciphertext = asset.copyOfRange(MAGIC.length + IV_BYTES, asset.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(ciphertext)

        val buffer = ByteBuffer.wrap(plain)
        val count = buffer.int
        require(count in 0..MAX_ENTRIES) { "vault entry count out of range" }

        val out = HashMap<String, String>(count.coerceAtLeast(1))
        repeat(count) {
            val idLength = buffer.int
            require(idLength in 1..128) { "vault key length out of range" }
            val id = ByteArray(idLength).also { buffer.get(it) }

            val valueLength = buffer.int
            require(valueLength in 0..4096) { "vault value length out of range" }
            val value = ByteArray(valueLength).also { buffer.get(it) }

            out[String(id, Charsets.UTF_8)] = String(value, Charsets.UTF_8)
        }
        return out
    }

    /** Entry count, for a debug-only diagnostics screen. Names nothing. */
    fun size(): Int = entries.size

    /**
     * Test seam: unit tests have no assets, and the Hindi resolver tests exercise the real provider
     * classes. Visible to the test source set only (`internal`), never called from the app.
     */
    internal fun installForTests(values: Map<String, String>) {
        entries = values
        installed = true
    }
}
