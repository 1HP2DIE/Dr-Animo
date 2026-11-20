package com.animo.app.player.movielink

/**
 * Port of the site's `dec(b,s,r)` download-link obfuscation:
 *
 * ```js
 * function dec(b,s,r){var x=parseInt(s,16)>>>0,i;
 *   for(i=0;i<r;i++){x=(Math.imul(x,1103515245)+12345)>>>0;}
 *   var raw=atob(b),o="",j;
 *   for(j=0;j<raw.length;j++){x=(Math.imul(x,1103515245)+12345)>>>0;
 *     o+=String.fromCharCode(raw.charCodeAt(j)^((x>>>16)&255));}return o;}
 * ```
 *
 * `b` is the `data-dl` attribute (standard base64), `s` is `data-ds` (a 32-bit seed written in hex)
 * and `r` is `data-dr` (the round count, measured 300). The generator is a 32-bit LCG; only the
 * third byte of each state is used as the XOR key, so this is obfuscation, not encryption.
 *
 * Two details are easy to get wrong and are why this is a direct port rather than "some PRNG":
 *  - `Math.imul` + `>>>0` is exactly 32-bit wrapping arithmetic, which is what Kotlin [Int]
 *    multiplication/addition already does, so no masking is needed on the state between steps.
 *  - The seed is advanced [rounds] times *before* the first payload byte is consumed; the advance
 *    inside the loop is a separate step. Skipping the pre-roll produces plausible-looking garbage.
 *
 * Pure Kotlin: no `android.*`, no IO, no base64 dependency, so it runs in plain JVM unit tests.
 */
object MovieLinkDownloadDecoder {

    private const val MULTIPLIER = 1103515245
    private const val INCREMENT = 12345

    private const val BASE64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** `char code -> 6-bit value`, `-1` for every byte that is not a base64 digit. */
    private val BASE64_LOOKUP = IntArray(128) { -1 }.also { lookup ->
        BASE64_ALPHABET.forEachIndexed { value, ch -> lookup[ch.code] = value }
    }

    /**
     * Decodes one `data-dl` attribute.
     *
     * @param payloadBase64 the `data-dl` value (standard base64, padding optional).
     * @param hexSeed the `data-ds` value, up to 8 hex digits; higher bits are dropped like `>>>0`.
     * @param rounds the `data-dr` value: LCG steps before the keystream starts.
     * @return the decoded path, e.g. `/file/…/Movie.Name.1080p.mkv`.
     * @throws IllegalArgumentException when an attribute is empty, the seed is not hex, or the
     * payload is not base64 — i.e. when the site changed the scheme.
     */
    fun decode(payloadBase64: String, hexSeed: String, rounds: Int): String {
        require(payloadBase64.isNotBlank()) { "empty data-dl payload" }
        require(rounds >= 0) { "negative data-dr rounds: $rounds" }

        val bytes = base64Decode(payloadBase64)
        require(bytes.isNotEmpty()) { "data-dl payload decoded to no bytes" }

        var state = seedOf(hexSeed)
        repeat(rounds) { state = step(state) }

        val out = CharArray(bytes.size)
        for (index in bytes.indices) {
            state = step(state)
            out[index] = ((bytes[index].toInt() and 0xFF) xor ((state ushr 16) and 0xFF)).toChar()
        }
        return String(out)
    }

    /** One LCG step. Kotlin [Int] arithmetic wraps at 32 bits, matching `imul(...)>>>0`. */
    private fun step(state: Int): Int = state * MULTIPLIER + INCREMENT

    private fun seedOf(hexSeed: String): Int {
        val trimmed = hexSeed.trim()
        require(trimmed.isNotEmpty()) { "empty data-ds seed" }
        val value = trimmed.toLongOrNull(radix = 16)
            ?: throw IllegalArgumentException("data-ds is not hex: '$hexSeed'")
        return value.toInt()
    }

    /**
     * Standard base64 to raw bytes. Decoding is done by hand for the same reason as
     * `JuicyDecoder`: `java.util.Base64` is API 26+ and this object must stay usable below that.
     *
     * URL-safe characters and whitespace are tolerated even though the site emits standard base64,
     * because a reformatted attribute should not cost a whole episode.
     */
    private fun base64Decode(body: String): ByteArray {
        val bytes = ByteArray(body.length * 3 / 4 + 3)
        var written = 0
        var accumulator = 0
        var bits = 0

        for (ch in body) {
            if (ch == '=' || ch.isWhitespace()) continue
            val value = when {
                ch == '-' -> BASE64_LOOKUP['+'.code]
                ch == '_' -> BASE64_LOOKUP['/'.code]
                ch.code < BASE64_LOOKUP.size -> BASE64_LOOKUP[ch.code]
                else -> -1
            }
            require(value >= 0) { "data-dl is not base64: '${ch}'" }
            accumulator = (accumulator shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                bytes[written++] = (accumulator shr bits).toByte()
            }
        }
        return bytes.copyOf(written)
    }
}
