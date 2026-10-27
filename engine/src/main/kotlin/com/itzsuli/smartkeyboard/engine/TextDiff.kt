package com.itzsuli.smartkeyboard.engine

/** Word-level diff between an original text and its AI-corrected version. */
object TextDiff {

    sealed class Segment {
        data class Same(val text: String) : Segment()
        data class Changed(val before: String, val after: String) : Segment()
    }

    fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val start = i
            when {
                TextUtils.isWordCharAt(text, i) -> while (i < text.length && TextUtils.isWordCharAt(text, i)) i++
                text[i].isWhitespace() -> while (i < text.length && text[i].isWhitespace()) i++
                else -> i++
            }
            out.add(text.substring(start, i))
        }
        return out
    }

    fun diff(before: String, after: String): List<Segment> {
        val a = tokenize(before)
        val b = tokenize(after)
        val n = a.size
        val m = b.size
        // LCS table; texts handled here are a few sentences long.
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = ArrayList<Segment>()
        val same = StringBuilder()
        val del = StringBuilder()
        val ins = StringBuilder()
        fun flushChange() {
            if (del.isNotEmpty() || ins.isNotEmpty()) {
                if (same.isNotEmpty()) { out.add(Segment.Same(same.toString())); same.setLength(0) }
                out.add(Segment.Changed(del.toString(), ins.toString()))
                del.setLength(0); ins.setLength(0)
            }
        }
        var i = 0
        var j = 0
        while (i < n || j < m) {
            if (i < n && j < m && a[i] == b[j]) {
                flushChange()
                same.append(a[i]); i++; j++
            } else if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) {
                ins.append(b[j]); j++
            } else {
                del.append(a[i]); i++
            }
        }
        flushChange()
        if (same.isNotEmpty()) out.add(Segment.Same(same.toString()))
        return out
    }

    /** Single-word replacements ("becuase" → "because") worth teaching the local autocorrect. */
    fun wordFixes(before: String, after: String): List<Pair<String, String>> {
        val fixes = ArrayList<Pair<String, String>>()
        for (s in diff(before, after)) {
            if (s !is Segment.Changed) continue
            val old = s.before.trim()
            val new = s.after.trim()
            if (old.isEmpty() || new.isEmpty()) continue
            if (old.any { !TextUtils.isWordChar(it) } || new.any { !TextUtils.isWordChar(it) && it != '-' }) continue
            fixes.add(old to new)
        }
        return fixes
    }

    fun changeCount(before: String, after: String): Int = diff(before, after).count { it is Segment.Changed }
}
