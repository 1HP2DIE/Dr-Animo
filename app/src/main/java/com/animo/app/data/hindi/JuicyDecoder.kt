package com.animo.app.data.hindi

/**
 * Decodes the `_juicycodes` playlist obfuscation that the Hindi-dub host
 * `argon.razorshell.space` wraps around every stream.
 *
 * Running `player.js` in a JS engine is not an option on Android (no WebView in unit tests, and no
 * JS evaluator dependency is worth carrying for one host), so this is a direct port of the four
 * steps `player.js` performs. They were read off the shipped decoder and re-verified against a node
 * `vm` run of the same file on the same payload:
 *
 * 1. The last three characters encode the salt: each one sits exactly 100 code points above a digit,
 *    so `'fhi'` means the digits `2 4 5`, i.e. salt `245`.
 * 2. The body before those three characters is URL-safe base64 of a string of the ten symbols in
 *    [SYMBOL_MAP], decoded as raw bytes (one char per byte).
 * 3. Each symbol maps back to its index, giving a stream of digits.
 * 4. Every four digits are one character: `group % 1000 - salt`, read as a UTF-16 code unit.
 *
 * The result is the JavaScript the embed would have `eval`ed — a `jwplayer().setup({...})` call whose
 * `sources.file` is the master playlist, which is the only `.m3u8` in it.
 *
 * `player.js` also applies its `str_decode` (a ROT13) twice in a row, which is the identity on the
 * ASCII letters it touches, so that step is not reproduced here.
 *
 * Pure Kotlin: no `android.*`, no IO, no base64 dependency, so it runs in plain JVM unit tests.
 */
object JuicyDecoder {

    private const val SALT_LENGTH = 3
    private const val SALT_OFFSET = 100
    private const val GROUP_SIZE = 4
    private const val GROUP_MODULUS = 1000

    /** Index in this list *is* the digit the symbol stands for. */
    private val SYMBOL_MAP: List<Char> = listOf('`', '%', '-', '+', '*', '$', '!', '_', '^', '=')

    private val SYMBOL_DIGITS: Map<Char, Int> =
        SYMBOL_MAP.withIndex().associate { (digit, symbol) -> symbol to digit }

    private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** `code -> 6-bit value`, `-1` for every byte that is not a base64 digit. */
    private val BASE64_LOOKUP = IntArray(128) { -1 }.also { lookup ->
        BASE64_ALPHABET.forEachIndexed { value, ch -> lookup[ch.code] = value }
    }

    private val JUICY_CALL = Regex("_juicycodes\\s*\\(")

    /** A quoted `https…m3u8`, once escaped slashes have been unescaped. */
    private val M3U8_URL = Regex("""https?://[^\s"'\\]+?\.m3u8""")

    /**
     * Returns the master `.m3u8` URL hidden in [payload], where [payload] is the concatenation of the
     * string arguments of the embed page's `_juicycodes(...)` call.
     *
     * @throws IllegalArgumentException when [payload] is too short, its salt or body is not decodable,
     * or the decoded playlist names no `.m3u8` — i.e. when the host changed the scheme.
     */
    fun decode(payload: String): String {
        require(payload.length > SALT_LENGTH) {
            "juicy payload is too short: ${payload.length} chars"
        }

        val body = payload.substring(0, payload.length - SALT_LENGTH)
        val salt = saltOf(payload.substring(payload.length - SALT_LENGTH))

        val symbols = base64Decode(body)
        val digits = StringBuilder(symbols.length)
        for (symbol in symbols) {
            val digit = SYMBOL_DIGITS[symbol]
                ?: throw IllegalArgumentException(
                    "juicy payload body holds an unmappable symbol U+${symbol.code.toString(16)}",
                )
            digits.append(digit)
        }
        require(digits.length % GROUP_SIZE == 0) {
            "juicy payload decoded to ${digits.length} digit symbols, which is not a multiple of $GROUP_SIZE"
        }

        val source = StringBuilder(digits.length / GROUP_SIZE)
        for (start in 0 until digits.length step GROUP_SIZE) {
            val group = digits.substring(start, start + GROUP_SIZE)
            val code = group.toInt() % GROUP_MODULUS - salt
            // String.fromCharCode coerces through ToUint16, so a negative code wraps the same way here.
            source.append((code and 0xFFFF).toChar())
        }

        val unescaped = source.toString().replace("\\/", "/")
        return M3U8_URL.find(unescaped)?.value
            ?: throw IllegalArgumentException("juicy payload decoded to a playlist with no .m3u8 URL")
    }

    /**
     * Pulls the payload out of an embed page by concatenating the quoted arguments of its
     * `_juicycodes("…"+"…"…);` call.
     *
     * The call is split into short chunks, so it is not enough to grab the first string. Scanning is
     * done character by character rather than with a regex so that escaped quotes, `)` inside a
     * literal, and newlines between the chunks all survive.
     *
     * @return `null` when the page has no `_juicycodes` call, or the call carries no string arguments.
     */
    fun extractPayload(embedHtml: String): String? {
        val call = JUICY_CALL.find(embedHtml) ?: return null
        val arguments = argumentsOf(embedHtml, call.range.last) ?: return null
        val chunks = stringLiteralsOf(arguments)
        if (chunks.isEmpty()) return null
        return chunks.joinToString(separator = "")
    }

    /** Recovers the salt, which is written as three digits each shifted by [SALT_OFFSET]. */
    private fun saltOf(tail: String): Int {
        val digits = StringBuilder(SALT_LENGTH)
        for (ch in tail) {
            val digit = ch.code - SALT_OFFSET
            require(digit in 0..9) {
                "juicy payload salt is not digit-encoded: '${ch}' is U+${ch.code.toString(16)}"
            }
            digits.append(digit)
        }
        return digits.toString().toInt()
    }

    /**
     * URL-safe base64 to a string of one char per byte, matching what the site's decoder sees.
     *
     * Padding is recomputed because the payload arrives as a single unwrapped line, and the decoder
     * must not depend on `java.util.Base64` (unavailable below API 26 without desugaring).
     */
    private fun base64Decode(body: String): String {
        val bytes = ByteArray(body.length * 3 / 4 + 3)
        var written = 0
        var accumulator = 0
        var bits = 0

        for (ch in body) {
            if (ch == '=') continue
            val value = when {
                ch == '_' -> BASE64_LOOKUP['+'.code]
                ch == '-' -> BASE64_LOOKUP['/'.code]
                ch.code < BASE64_LOOKUP.size -> BASE64_LOOKUP[ch.code]
                else -> -1
            }
            require(value >= 0) { "juicy payload body is not base64: '${ch}'" }
            accumulator = (accumulator shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                bytes[written++] = (accumulator shr bits).toByte()
            }
        }

        return String(bytes, 0, written, Charsets.ISO_8859_1)
    }

    /** Returns the text between [openParen] and its matching `)`, quoting-aware. */
    private fun argumentsOf(source: String, openParen: Int): String? {
        var depth = 0
        var quote = NO_QUOTE
        var escaped = false

        for (index in openParen until source.length) {
            val ch = source[index]
            if (quote != NO_QUOTE) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == quote -> quote = NO_QUOTE
                }
                continue
            }
            when (ch) {
                '\'', '"' -> quote = ch
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(openParen + 1, index)
                }
            }
        }
        return null
    }

    /** Every string literal in [arguments], unescaped, in source order. */
    private fun stringLiteralsOf(arguments: String): List<String> {
        val literals = mutableListOf<String>()
        var index = 0

        while (index < arguments.length) {
            val quote = arguments[index]
            if (quote != '\'' && quote != '"') {
                index++
                continue
            }
            val literal = StringBuilder()
            index++
            while (index < arguments.length && arguments[index] != quote) {
                val ch = arguments[index]
                if (ch != '\\' || index + 1 == arguments.length) {
                    literal.append(ch)
                    index++
                    continue
                }
                index++
                when (val escape = arguments[index]) {
                    'b' -> literal.append('\b')
                    'f' -> literal.append('\u000C')
                    'n' -> literal.append('\n')
                    'r' -> literal.append('\r')
                    't' -> literal.append('\t')
                    'u' -> {
                        val hex = if (index + 4 < arguments.length) {
                            arguments.substring(index + 1, index + 5).toIntOrNull(radix = 16)
                        } else {
                            null
                        }
                        if (hex != null) {
                            literal.append(hex.toChar())
                            index += 4
                        } else {
                            literal.append(escape)
                        }
                    }
                    else -> literal.append(escape)
                }
                index++
            }
            literals += literal.toString()
            index++
        }
        return literals
    }

    private const val NO_QUOTE = '\u0000'
}
