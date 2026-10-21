package io.github.lightbahn

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Sehr kleiner PDF-Textextraktor – gerade genug, um aus einer Bahn-Fahrkarte
 * Datum und Bahnhöfe herauszulesen. Keine Bibliothek nötig (für Light-Tools
 * ist keine PDF-Bibliothek erlaubt).
 *
 * Unterstützt: FlateDecode, Objekt-Streams, Seitenbaum mit geerbten Ressourcen,
 * Schriften mit ToUnicode-Tabelle (auch 2-Byte-Schriften), Form-XObjects.
 * Alles, was nicht klappt, wird still übersprungen.
 */
internal object PdfText {

    fun extract(pdf: ByteArray, maxPages: Int = 4): String = runCatching {
        Doc(pdf).text(maxPages)
    }.getOrDefault("")

    // ---------------------------------------------------------------- Objektmodell

    private data class Ref(val num: Int)
    private data class Name(val value: String)
    private class Str(val bytes: ByteArray)
    private data class Op(val value: String)
    private class Obj(val value: Any?, val stream: ByteArray?)

    private class Lexer(val b: ByteArray, var p: Int = 0) {
        fun eof() = p >= b.size
        private fun ch(i: Int = p): Int = if (i < b.size) b[i].toInt() and 0xFF else -1

        private fun isWs(c: Int) = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
        private fun isDelim(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
            c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code

        fun skipWs() {
            while (!eof()) {
                val c = ch()
                if (isWs(c)) p++
                else if (c == '%'.code) { while (!eof() && ch() != 10 && ch() != 13) p++ }
                else break
            }
        }

        fun peekKeyword(word: String): Boolean {
            skipWs()
            if (p + word.length > b.size) return false
            for (i in word.indices) if (ch(p + i) != word[i].code) return false
            return true
        }

        /** Liest einen Wert. Im Inhalts-Modus kommen Operatoren als [Op] zurück. */
        fun next(): Any? {
            skipWs()
            if (eof()) return EOF
            val c = ch()
            return when {
                c == '<'.code && ch(p + 1) == '<'.code -> { p += 2; dict() }
                c == '<'.code -> { p++; hex() }
                c == '('.code -> { p++; literal() }
                c == '['.code -> { p++; array() }
                c == '/'.code -> { p++; Name(token()) }
                c == ']'.code || c == '>'.code || c == ')'.code || c == '}'.code || c == '{'.code -> { p++; Op(c.toChar().toString()) }
                else -> {
                    val t = token()
                    if (t.isEmpty()) { p++; return Op("") }
                    val n = t.toDoubleOrNull()
                    when {
                        n != null -> {
                            // "12 0 R" → Verweis
                            val save = p
                            if (t.all { it.isDigit() }) {
                                skipWs()
                                val g = token()
                                if (g.isNotEmpty() && g.all { it.isDigit() }) {
                                    skipWs()
                                    if (ch() == 'R'.code && (eof() || isWs(ch(p + 1)) || isDelim(ch(p + 1)) || ch(p + 1) == -1)) {
                                        p++
                                        return Ref(t.toInt())
                                    }
                                }
                            }
                            p = save
                            n
                        }
                        t == "true" -> true
                        t == "false" -> false
                        t == "null" -> null
                        else -> Op(t)
                    }
                }
            }
        }

        private fun token(): String {
            val start = p
            while (!eof() && !isWs(ch()) && !isDelim(ch())) p++
            val raw = String(b, start, p - start, Charsets.ISO_8859_1)
            return if ('#' in raw) raw.replace(Regex("#([0-9A-Fa-f]{2})")) { it.groupValues[1].toInt(16).toChar().toString() } else raw
        }

        private fun dict(): Map<String, Any?> {
            val m = HashMap<String, Any?>()
            while (true) {
                skipWs()
                if (eof()) return m
                if (ch() == '>'.code && ch(p + 1) == '>'.code) { p += 2; return m }
                val k = next()
                if (k !is Name) { if (k === EOF) return m; continue }
                m[k.value] = next()
            }
        }

        private fun array(): List<Any?> {
            val l = ArrayList<Any?>()
            while (true) {
                skipWs()
                if (eof()) return l
                if (ch() == ']'.code) { p++; return l }
                l += next()
            }
        }

        private fun hex(): Str {
            val out = ByteArrayOutputStream()
            var hi = -1
            while (!eof()) {
                val c = ch(); p++
                if (c == '>'.code) break
                val d = Character.digit(c, 16)
                if (d < 0) continue
                if (hi < 0) hi = d else { out.write(hi * 16 + d); hi = -1 }
            }
            if (hi >= 0) out.write(hi * 16)
            return Str(out.toByteArray())
        }

        private fun literal(): Str {
            val out = ByteArrayOutputStream()
            var depth = 1
            while (!eof()) {
                val c = ch(); p++
                when (c) {
                    '('.code -> { depth++; out.write(c) }
                    ')'.code -> { depth--; if (depth == 0) break; out.write(c) }
                    '\\'.code -> {
                        val e = ch(); p++
                        when (e) {
                            'n'.code -> out.write(10)
                            'r'.code -> out.write(13)
                            't'.code -> out.write(9)
                            'b'.code -> out.write(8)
                            'f'.code -> out.write(12)
                            13 -> if (ch() == 10) p++
                            10 -> {}
                            in '0'.code..'7'.code -> {
                                var v = e - '0'.code
                                repeat(2) { if (ch() in '0'.code..'7'.code) { v = v * 8 + (ch() - '0'.code); p++ } }
                                out.write(v and 0xFF)
                            }
                            else -> out.write(e)
                        }
                    }
                    else -> out.write(c)
                }
            }
            return Str(out.toByteArray())
        }

        companion object {
            val EOF = Any()
        }
    }

    // ---------------------------------------------------------------- Dokument

    private class Doc(val b: ByteArray) {
        private val objects = HashMap<Int, Obj>()

        init {
            val s = String(b, Charsets.ISO_8859_1)
            val head = Regex("""(\d+)\s+(\d+)\s+obj\b""")
            var from = 0
            while (true) {
                val m = head.find(s, from) ?: break
                val lx = Lexer(b, m.range.last + 1)
                val value = runCatching { lx.next() }.getOrNull()
                var stream: ByteArray? = null
                var end = lx.p
                if (lx.peekKeyword("stream")) {
                    var start = lx.p + "stream".length
                    if (start < b.size && b[start] == '\r'.code.toByte()) start++
                    if (start < b.size && b[start] == '\n'.code.toByte()) start++
                    val len = ((value as? Map<*, *>)?.get("Length") as? Double)?.toInt()
                    val stop = if (len != null && start + len <= b.size &&
                        s.startsWith("endstream", skipWs(s, start + len))
                    ) {
                        start + len
                    } else {
                        s.indexOf("endstream", start).takeIf { it >= 0 } ?: b.size
                    }
                    stream = b.copyOfRange(start, stop)
                    end = stop
                }
                objects[m.groupValues[1].toInt()] = Obj(value, stream)
                from = maxOf(end, m.range.last + 1)
            }
            // Objekte aus Objekt-Streams (PDF 1.5+)
            objects.values.toList().forEach { o ->
                val d = o.value as? Map<*, *> ?: return@forEach
                if ((d["Type"] as? Name)?.value != "ObjStm") return@forEach
                val data = decode(o) ?: return@forEach
                val n = (d["N"] as? Double)?.toInt() ?: return@forEach
                val first = (d["First"] as? Double)?.toInt() ?: return@forEach
                val header = Lexer(data)
                val pairs = (0 until n).mapNotNull {
                    val num = header.next() as? Double ?: return@mapNotNull null
                    val off = header.next() as? Double ?: return@mapNotNull null
                    num.toInt() to off.toInt()
                }
                pairs.forEach { (num, off) ->
                    if (num !in objects) {
                        val v = runCatching { Lexer(data, first + off).next() }.getOrNull()
                        objects[num] = Obj(v, null)
                    }
                }
            }
        }

        private fun skipWs(s: String, i: Int): Int {
            var j = i
            while (j < s.length && s[j].isWhitespace()) j++
            return j
        }

        fun resolve(v: Any?): Any? {
            var x = v
            var guard = 0
            while (x is Ref && guard++ < 10) x = objects[x.num]?.value
            return x
        }

        private fun objOf(v: Any?): Obj? = (v as? Ref)?.let { objects[it.num] }

        fun decode(o: Obj): ByteArray? {
            val raw = o.stream ?: return null
            val d = o.value as? Map<*, *> ?: return raw
            val filters = when (val f = resolve(d["Filter"])) {
                null -> emptyList()
                is Name -> listOf(f.value)
                is List<*> -> f.mapNotNull { (resolve(it) as? Name)?.value }
                else -> return null
            }
            var data = raw
            for (f in filters) {
                data = when (f) {
                    "FlateDecode", "Fl" -> inflate(data) ?: return null
                    else -> return null // Bilder u. Ä. brauchen wir nicht
                }
            }
            return data
        }

        private fun inflate(data: ByteArray): ByteArray? {
            val inf = Inflater()
            inf.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            try {
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0) {
                        if (inf.needsInput() || inf.needsDictionary()) break
                    }
                    out.write(buf, 0, n)
                    if (out.size() > 20_000_000) break
                }
            } catch (e: Exception) {
                // Halb kaputte Streams: nehmen, was bis hierher lesbar war.
            } finally {
                inf.end()
            }
            return if (out.size() > 0) out.toByteArray() else null
        }

        // ------------------------------------------------------------ Seiten

        private fun pages(): List<Map<*, *>> {
            val all = objects.values.mapNotNull { it.value as? Map<*, *> }
            val roots = all.filter { (it["Type"] as? Name)?.value == "Pages" && it["Parent"] == null }
            val result = ArrayList<Map<*, *>>()
            fun walk(node: Map<*, *>, depth: Int) {
                if (depth > 20) return
                when ((node["Type"] as? Name)?.value) {
                    "Page" -> result += node
                    else -> (resolve(node["Kids"]) as? List<*>)?.forEach { k ->
                        (resolve(k) as? Map<*, *>)?.let { walk(it, depth + 1) }
                    }
                }
            }
            roots.forEach { walk(it, 0) }
            if (result.isEmpty()) result += all.filter { (it["Type"] as? Name)?.value == "Page" }
            return result
        }

        private fun resourcesOf(page: Map<*, *>): Map<*, *>? {
            var node: Map<*, *>? = page
            var guard = 0
            while (node != null && guard++ < 20) {
                (resolve(node["Resources"]) as? Map<*, *>)?.let { return it }
                node = resolve(node["Parent"]) as? Map<*, *>
            }
            return null
        }

        fun text(maxPages: Int): String {
            val sb = StringBuilder()
            pages().take(maxPages).forEach { page ->
                val content = ByteArrayOutputStream()
                val c = page["Contents"]
                val refs = (resolve(c) as? List<*>) ?: listOf(c)
                refs.forEach { r -> objOf(r)?.let { o -> decode(o)?.let { content.write(it); content.write(10) } } }
                TextWriter(this, sb).run(content.toByteArray(), resourcesOf(page), 0)
                sb.append("\n\n")
            }
            return sb.toString()
                .replace(Regex("[ \\t\\u00A0]+"), " ")
                .replace(Regex(" *\\n *"), "\n")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        }

        // ------------------------------------------------------------ Schriften

        private val fontCache = HashMap<Any, Font>()

        fun font(resources: Map<*, *>?, name: String): Font {
            val fonts = resolve(resources?.get("Font")) as? Map<*, *>
            val raw = fonts?.get(name)
            val key: Any = raw as? Ref ?: name
            return fontCache.getOrPut(key) { buildFont(resolve(raw) as? Map<*, *>) }
        }

        private fun buildFont(d: Map<*, *>?): Font {
            if (d == null) return Font(1, null)
            val type0 = (d["Subtype"] as? Name)?.value == "Type0"
            var bytes = if (type0) 2 else 1
            val map = objOf(d["ToUnicode"])?.let { decode(it) }?.let { cmap ->
                val text = String(cmap, Charsets.ISO_8859_1)
                Regex("""begincodespacerange\s*<([0-9A-Fa-f]+)>""").find(text)?.let { bytes = maxOf(1, it.groupValues[1].length / 2) }
                parseCMap(text)
            }
            return Font(bytes, map)
        }

        private fun parseCMap(text: String): Map<Int, String> {
            val map = HashMap<Int, String>()
            fun utf16(hex: String): String {
                val clean = hex.filter { Character.digit(it, 16) >= 0 }
                val bs = ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                return String(bs, Charsets.UTF_16BE)
            }
            Regex("""beginbfchar(.*?)endbfchar""", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { block ->
                Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]*)>""").findAll(block.groupValues[1]).forEach {
                    map[it.groupValues[1].toInt(16)] = utf16(it.groupValues[2])
                }
            }
            Regex("""beginbfrange(.*?)endbfrange""", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { block ->
                Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*(<[0-9A-Fa-f]*>|\[[^\]]*\])""")
                    .findAll(block.groupValues[1]).forEach { m ->
                        val lo = m.groupValues[1].toInt(16)
                        val hi = m.groupValues[2].toInt(16)
                        if (hi - lo > 65535) return@forEach
                        val dst = m.groupValues[3]
                        if (dst.startsWith("[")) {
                            Regex("""<([0-9A-Fa-f]*)>""").findAll(dst).forEachIndexed { i, h ->
                                if (lo + i <= hi) map[lo + i] = utf16(h.groupValues[1])
                            }
                        } else {
                            val hex = dst.trim('<', '>')
                            if (hex.length < 4) return@forEach
                            val base = utf16(hex)
                            val last = base.last()
                            for (code in lo..hi) map[code] = base.dropLast(1) + (last + (code - lo))
                        }
                    }
            }
            return map
        }

        fun xobject(resources: Map<*, *>?, name: String): Pair<ByteArray, Map<*, *>?>? {
            val xo = resolve(resources?.get("XObject")) as? Map<*, *> ?: return null
            val o = objOf(xo[name]) ?: return null
            val d = o.value as? Map<*, *> ?: return null
            if ((d["Subtype"] as? Name)?.value != "Form") return null
            val data = decode(o) ?: return null
            return data to ((resolve(d["Resources"]) as? Map<*, *>) ?: resources)
        }
    }

    private class Font(val bytes: Int, val toUnicode: Map<Int, String>?) {
        fun decode(s: ByteArray): String {
            val sb = StringBuilder()
            var i = 0
            while (i < s.size) {
                if (bytes == 2 && i + 1 < s.size) {
                    val code = ((s[i].toInt() and 0xFF) shl 8) or (s[i + 1].toInt() and 0xFF)
                    toUnicode?.get(code)?.let { sb.append(it) }
                    i += 2
                } else {
                    val code = s[i].toInt() and 0xFF
                    val mapped = toUnicode?.get(code)
                    sb.append(mapped ?: String(byteArrayOf(s[i]), CP1252))
                    i++
                }
            }
            return sb.toString()
        }

        companion object {
            val CP1252 = runCatching { charset("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)
        }
    }

    // ---------------------------------------------------------------- Inhalts-Stream

    private class TextWriter(val doc: Doc, val out: StringBuilder) {
        private var lineY = 0.0
        private var lastY: Double? = null
        private var pendingSpace = false

        fun run(content: ByteArray, resources: Map<*, *>?, depth: Int) {
            if (depth > 4) return
            val lx = Lexer(content)
            val ops = ArrayList<Any?>()
            var font = doc.font(resources, "")
            var leading = 0.0
            while (true) {
                val t = runCatching { lx.next() }.getOrElse { return }
                if (t === Lexer.EOF) break
                if (t !is Op) { ops += t; continue }
                when (t.value) {
                    "BT" -> { lineY = 0.0 }
                    "Tf" -> (ops.getOrNull(0) as? Name)?.let { font = doc.font(resources, it.value) }
                    "TL" -> leading = num(ops.getOrNull(0))
                    "Td" -> move(num(ops.getOrNull(1)))
                    "TD" -> { leading = -num(ops.getOrNull(1)); move(num(ops.getOrNull(1))) }
                    "Tm" -> setY(num(ops.getOrNull(5)))
                    "T*" -> move(-leading.coerceAtLeast(1.0))
                    "Tj" -> show(font, ops.getOrNull(0))
                    "'" -> { move(-leading.coerceAtLeast(1.0)); show(font, ops.getOrNull(0)) }
                    "\"" -> { move(-leading.coerceAtLeast(1.0)); show(font, ops.getOrNull(2)) }
                    "TJ" -> (ops.getOrNull(0) as? List<*>)?.forEach { item ->
                        if (item is Str) show(font, item) else if (item is Double && item < -200) pendingSpace = true
                    }
                    "Do" -> (ops.getOrNull(0) as? Name)?.let { n ->
                        doc.xobject(resources, n.value)?.let { (data, res) ->
                            out.append('\n'); lastY = null
                            run(data, res, depth + 1)
                            out.append('\n'); lastY = null
                        }
                    }
                    "ID" -> skipInlineImage(lx, content)
                }
                ops.clear()
            }
        }

        private fun num(v: Any?): Double = (v as? Double) ?: 0.0

        private fun move(dy: Double) {
            lineY += dy
            pendingSpace = true
        }

        private fun setY(y: Double) {
            lineY = y
            pendingSpace = true
        }

        private fun show(font: Font, v: Any?) {
            val s = (v as? Str)?.let { font.decode(it.bytes) } ?: return
            if (s.isEmpty()) return
            val last = lastY
            if (last != null && kotlin.math.abs(last - lineY) > 1.0) out.append('\n')
            else if (pendingSpace && out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
            pendingSpace = false
            lastY = lineY
            out.append(s)
        }

        private fun skipInlineImage(lx: Lexer, b: ByteArray) {
            var i = lx.p + 1
            while (i + 2 < b.size) {
                if (b[i] == 'E'.code.toByte() && b[i + 1] == 'I'.code.toByte() &&
                    (b[i - 1].toInt() == 32 || b[i - 1].toInt() == 10 || b[i - 1].toInt() == 13) &&
                    (b[i + 2].toInt() == 32 || b[i + 2].toInt() == 10 || b[i + 2].toInt() == 13)
                ) {
                    lx.p = i + 2
                    return
                }
                i++
            }
            lx.p = b.size
        }
    }
}
