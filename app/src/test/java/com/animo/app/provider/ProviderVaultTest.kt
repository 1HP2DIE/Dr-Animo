package com.animo.app.provider

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderVaultTest {

    private val key = ByteArray(32) { (it + 1).toByte() }

    private fun vaultAsset(entries: Map<String, String>, keyBytes: ByteArray = key): ByteArray {
        val plain = ByteArrayOutputStream()
        DataOutputStream(plain).run {
            writeInt(entries.size)
            entries.forEach { (id, value) ->
                val idBytes = id.toByteArray(Charsets.UTF_8)
                val valueBytes = value.toByteArray(Charsets.UTF_8)
                writeInt(idBytes.size)
                write(idBytes)
                writeInt(valueBytes.size)
                write(valueBytes)
            }
            flush()
        }

        val iv = ByteArray(12) { 0x0F }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))

        val out = ByteArrayOutputStream()
        out.write("PVA1".toByteArray(Charsets.UTF_8))
        out.write(iv)
        out.write(cipher.doFinal(plain.toByteArray()))
        return out.toByteArray()
    }

    @Test
    fun `decodes every entry`() {
        val decoded = ProviderVault.decode(
            vaultAsset(
                mapOf(
                    ProviderKey.CATALOG_API.slot to "https://catalog.invalid",
                    ProviderKey.STREAM_HOST.slot to "https://stream.invalid",
                    ProviderKey.STREAM_REFERERS.slot to "https://a.invalid/,https://b.invalid/",
                ),
            ),
            key,
        )

        assertEquals("https://catalog.invalid", decoded[ProviderKey.CATALOG_API.slot])
        assertEquals("https://stream.invalid", decoded[ProviderKey.STREAM_HOST.slot])
        assertEquals(3, decoded.size)
    }

    @Test
    fun `a wrong key decrypts nothing`() {
        val asset = vaultAsset(mapOf(ProviderKey.SUBTITLE_HOST_A.slot to "https://x.invalid/"))
        val wrongKey = ByteArray(32) { 0x01 }
        assertThrows(Exception::class.java) { ProviderVault.decode(asset, wrongKey) }
    }

    @Test
    fun `a tampered asset is rejected`() {
        val asset = vaultAsset(mapOf(ProviderKey.SUBTITLE_HOST_A.slot to "https://x.invalid/"))
        asset[asset.lastIndex] = (asset.last().toInt() xor 0xFF).toByte()
        assertThrows(Exception::class.java) { ProviderVault.decode(asset, key) }
    }

    @Test
    fun `bad magic and truncation are rejected`() {
        assertThrows(Exception::class.java) { ProviderVault.decode(ByteArray(64), key) }
        assertThrows(Exception::class.java) {
            ProviderVault.decode("PVA1".toByteArray() + ByteArray(4), key)
        }
        assertThrows(Exception::class.java) { ProviderVault.decode(ByteArray(0), key) }
    }

    @Test
    fun `an empty vault decodes to an empty map`() {
        assertTrue(ProviderVault.decode(vaultAsset(emptyMap()), key).isEmpty())
    }

    @Test
    fun `slots are unique`() {
        val slots = ProviderKey.entries.map { it.slot }
        assertEquals(slots.size, slots.toSet().size)
        assertTrue(slots.none { it.isBlank() })
    }

    @Test
    fun `a slot the vault does not carry answers null, never a placeholder`() {
        ProviderVault.installForTests(mapOf(ProviderKey.DUB_CDN.slot to "https://cdn.invalid"))
        assertNull(ProviderVault.optionalUrl(ProviderKey.CATALOG_API))
        assertTrue(ProviderVault.list(ProviderKey.STREAM_REFERERS).isEmpty())
        assertThrows(IllegalStateException::class.java) {
            ProviderVault.url(ProviderKey.CATALOG_API)
        }
    }

    @Test
    fun `length prefix mismatch does not read past the buffer`() {
        val plain = ByteBuffer.allocate(4 + 4 + 4).apply {
            putInt(1)
            putInt(999)
            putInt(1)
        }.array()
        // Encrypt this malformed payload the same way so only the parser can reject it.
        val iv = ByteArray(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val asset = "PVA1".toByteArray() + iv + cipher.doFinal(plain)

        assertThrows(Exception::class.java) { ProviderVault.decode(asset, key) }
    }
}
