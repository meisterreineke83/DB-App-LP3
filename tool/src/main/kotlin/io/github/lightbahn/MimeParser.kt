package io.github.lightbahn

import java.nio.charset.Charset
import java.util.Base64

/** Das, was wir aus einer Bahn-Mail brauchen. */
internal data class ParsedMail(
    val subject: String,
    val date: String,
    val text: String,
    val pdfs: List<Pair<String, ByteArray>>,
)

/**
 * Einfacher MIME-Parser für eine einzelne E-Mail (RFC 5322 / 2045 / 2046 / 2047).
 * Die Rohbytes werden als ISO-8859-1 gelesen, damit jedes Byte genau einem Zeichen
 * entspricht und Anhänge verlustfrei zurückgewonnen werden können.
 */
internal object MimeParser {

    fun parse(raw: ByteArray): ParsedMail {
        val message = String(raw, Charsets.ISO_8859_1)
        val (headers, _) = splitHeaders(message)
        val texts = mutableListOf<String>()
        val pdfs = mutableListOf<Pair<String, ByteArray>>()
        walk(message, texts, pdfs, depth = 0)
        return ParsedMail(
            subject = decodeEncodedWords(headers["subject"].orEmpty()).trim(),
            date = headers["date"].orEmpty().trim(),
            text = texts.joinToString("\n").take(6000),
            pdfs = pdfs,
        )
    }

    private fun walk(
        part: String,
        texts: MutableList<String>,
        pdfs: MutableList<Pair<String, ByteArray>>,
        depth: Int,
    ) {
        if (depth > 8) return
        val (headers, body) = splitHeaders(part)
        val contentType = headers["content-type"].orEmpty()
        val mimeType = contentType.substringBefore(';').trim().lowercase().ifEmpty { "text/plain" }
        val encoding = headers["content-transfer-encoding"].orEmpty().trim().lowercase()
        val disposition = headers["content-disposition"].orEmpty()

        if (mimeType.startsWith("multipart/")) {
            val boundary = param(contentType, "boundary") ?: return
            body.split("--$boundary")
                .drop(1) // Präambel
                .filterNot { it.startsWith("--") } // Epilog
                .forEach { sub -> walk(sub.trimStart('\r', '\n'), texts, pdfs, depth + 1) }
            return
        }

        if (mimeType == "message/rfc822") {
            walk(body, texts, pdfs, depth + 1)
            return
        }

        val filename = param(disposition, "filename") ?: param(contentType, "name")
        val decodedName = filename?.let { decodeEncodedWords(it) }
        val isPdf = mimeType == "application/pdf" ||
            (mimeType == "application/octet-stream" && decodedName?.lowercase()?.endsWith(".pdf") == true)

        val bytes = decodeBody(body, encoding)
        val looksLikePdf = bytes.size > 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()
        when {
            isPdf || (looksLikePdf && !mimeType.startsWith("text/")) -> pdfs += (decodedName ?: "Fahrkarte.pdf") to bytes
            mimeType == "text/plain" -> texts += String(bytes, charsetOf(contentType))
            mimeType == "text/html" -> texts += htmlToText(String(bytes, charsetOf(contentType)))
        }
    }

    private fun splitHeaders(part: String): Pair<Map<String, String>, String> {
        val crlf = part.indexOf("\r\n\r\n")
        val lf = part.indexOf("\n\n")
        val (end, skip) = when {
            crlf >= 0 && (lf < 0 || crlf <= lf) -> crlf to 4
            lf >= 0 -> lf to 2
            else -> return emptyMap<String, String>() to part
        }
        val headerBlock = part.substring(0, end).replace(Regex("\r?\n[ \t]+"), " ")
        val headers = mutableMapOf<String, String>()
        headerBlock.split(Regex("\r?\n")).forEach { line ->
            val colon = line.indexOf(':')
            if (colon > 0) {
                val key = line.substring(0, colon).trim().lowercase()
                if (key !in headers) headers[key] = line.substring(colon + 1).trim()
            }
        }
        return headers to part.substring(end + skip)
    }

    private fun param(header: String, name: String): String? {
        // RFC 2231: filename*=utf-8''Fahrkarte%20Hamburg.pdf
        Regex("""(?i)\b$name\*=([^']*)'[^']*'([^;]+)""").find(header)?.let { m ->
            val charset = runCatching { Charset.forName(m.groupValues[1].ifEmpty { "UTF-8" }) }
                .getOrDefault(Charsets.UTF_8)
            return percentDecode(m.groupValues[2].trim().trim('"'), charset)
        }
        Regex("""(?i)\b$name="([^"]*)"""").find(header)?.let { return it.groupValues[1] }
        Regex("""(?i)\b$name=([^;\s]+)""").find(header)?.let { return it.groupValues[1] }
        return null
    }

    private fun decodeBody(body: String, encoding: String): ByteArray {
        val bytes = body.toByteArray(Charsets.ISO_8859_1)
        return when (encoding) {
            "base64" -> runCatching { Base64.getMimeDecoder().decode(bytes) }.getOrDefault(ByteArray(0))
            "quoted-printable" -> decodeQuotedPrintable(body)
            else -> bytes
        }
    }

    private fun decodeQuotedPrintable(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val s = text.replace("=\r\n", "").replace("=\n", "")
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '=' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            out.write(c.code and 0xFF)
            i++
        }
        return out.toByteArray()
    }

    private fun charsetOf(contentType: String): Charset {
        val name = param(contentType, "charset") ?: return Charsets.UTF_8
        return runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }

    /** RFC 2047: =?utf-8?B?...?= bzw. =?utf-8?Q?...?= */
    fun decodeEncodedWords(value: String): String {
        val joined = value.replace(Regex("""\?=\s+=\?"""), "?==?")
        return Regex("""=\?([^?]+)\?([BbQq])\?([^?]*)\?=""").replace(joined) { m ->
            val charset = runCatching { Charset.forName(m.groupValues[1]) }.getOrDefault(Charsets.UTF_8)
            val payload = m.groupValues[3]
            val bytes = if (m.groupValues[2].equals("B", ignoreCase = true)) {
                runCatching { Base64.getDecoder().decode(payload) }.getOrDefault(ByteArray(0))
            } else {
                decodeQuotedPrintable(payload.replace('_', ' '))
            }
            String(bytes, charset)
        }
    }

    private fun percentDecode(value: String, charset: Charset): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(charset))
            i++
        }
        return String(out.toByteArray(), charset)
    }

    private fun htmlToText(html: String): String =
        html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</tr>|</div>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&auml;", "ä").replace("&ouml;", "ö").replace("&uuml;", "ü")
            .replace("&Auml;", "Ä").replace("&Ouml;", "Ö").replace("&Uuml;", "Ü")
            .replace("&szlig;", "ß")
            .replace(Regex("[ \t]+"), " ")
            .replace(Regex("\n\\s*\n+"), "\n")
            .trim()
}
