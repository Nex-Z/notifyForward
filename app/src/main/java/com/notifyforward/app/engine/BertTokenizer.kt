package com.notifyforward.app.engine

/** BERT cased BasicTokenizer + greedy WordPiece, matching do_lower_case=false. */
class BertTokenizer(vocabulary: List<String>) {
    private val vocab = vocabulary.withIndex().associate { it.value to it.index.toLong() }
    private val unknown = vocab.getValue("[UNK]")

    fun encode(text: String, maxLength: Int = 128): LongArray {
        require(maxLength >= 2)
        val words = ArrayList<String>()
        var offset = 0
        Regex("\\[(?:UNK|SEP|PAD|CLS|MASK)\\]").findAll(text).forEach { match ->
            words.addAll(basicTokens(text.substring(offset, match.range.first)))
            words.add(match.value)
            offset = match.range.last + 1
        }
        words.addAll(basicTokens(text.substring(offset)))
        val ids = arrayListOf(vocab.getValue("[CLS]"))
        for (word in words) {
            if (word.isEmpty()) continue
            val pieces = ArrayList<Long>()
            val points = word.codePoints().toArray()
            if (points.size > 100) pieces.add(unknown)
            else {
                var start = 0
                while (start < points.size) {
                    var end = points.size
                    var found: Long? = null
                    while (end > start) {
                        val token = (if (start > 0) "##" else "") + String(points, start, end - start)
                        found = vocab[token]
                        if (found != null) break
                        end--
                    }
                    if (found == null) { pieces.clear(); pieces.add(unknown); break }
                    pieces.add(found); start = end
                }
            }
            ids.addAll(pieces.take(maxLength - 1 - ids.size))
            if (ids.size >= maxLength - 1) break
        }
        ids.add(vocab.getValue("[SEP]"))
        return ids.toLongArray()
    }

    private fun basicTokens(text: String): List<String> {
        val clean = StringBuilder()
        text.codePoints().forEach { cp ->
            when {
                cp == 0 || cp == 0xfffd -> Unit
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> clean.append(' ')
                Character.getType(cp) in listOf(Character.CONTROL.toInt(), Character.FORMAT.toInt()) -> Unit
                isChinese(cp) -> clean.append(' ').appendCodePoint(cp).append(' ')
                else -> clean.appendCodePoint(cp)
            }
        }
        val basic = StringBuilder()
        clean.toString().codePoints().forEach { cp ->
            when {
                isPunctuation(cp) -> basic.append(' ').appendCodePoint(cp).append(' ')
                else -> basic.appendCodePoint(cp)
            }
        }
        return basic.toString().trim().split(Regex("\\s+"))
    }

    private fun isChinese(c: Int) = c in 0x4e00..0x9fff || c in 0x3400..0x4dbf || c in 0x20000..0x2a6df || c in 0x2a700..0x2b73f || c in 0x2b740..0x2b81f || c in 0x2b820..0x2ceaf || c in 0xf900..0xfaff || c in 0x2f800..0x2fa1f
    private fun isPunctuation(c: Int) = c in 33..47 || c in 58..64 || c in 91..96 || c in 123..126 || Character.getType(c) in listOf(20,21,22,23,24,29,30)
}
