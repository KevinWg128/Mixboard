package hk.mixboard.ime

import android.content.res.AssetManager
import java.io.File

/**
 * Read-only tables built by tools/build_dict.py, loaded once in the background.
 * Each asset line is `key<TAB>value value value`, most likely value first.
 */
class Dictionary(private val assets: AssetManager) {

    @Volatile
    private var quick: Map<String, String> = emptyMap()

    @Volatile
    private var assoc: Map<String, String> = emptyMap()

    @Volatile
    private var english: Map<String, String> = emptyMap()

    fun loadAsync(onLoaded: () -> Unit) {
        Thread({
            quick = read("quick.tsv")
            assoc = read("assoc.tsv")
            english = read("en2zh.tsv")
            onLoaded()
        }, "mixboard-dict").start()
    }

    /** 速成 candidates for a one- or two-letter code. */
    fun quick(code: String): List<String> = split(quick[code])

    /** 聯想: what usually follows [ch]. */
    fun associate(ch: String): List<String> = split(assoc[ch])

    /** English word to Chinese, e.g. toy to 玩具. */
    fun english(word: String): List<String> = split(english[word])

    private fun read(name: String): Map<String, String> {
        val map = HashMap<String, String>(1 shl 16)
        assets.open(name).bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab > 0) map[line.substring(0, tab)] = line.substring(tab + 1)
            }
        }
        return map
    }

    private fun split(s: String?): List<String> = if (s.isNullOrEmpty()) emptyList() else s.split(' ')
}

/**
 * What you actually pick, kept on the phone only (app-private storage).
 * Context keys: `q:<code>` 速成 picks, `e:<word>` English picks, `a:<char>` what you typed after a char.
 */
class UserDict(private val file: File) {
    private val data = HashMap<String, HashMap<String, Int>>()
    private var dirty = 0

    fun load() {
        data.clear()
        if (!file.exists()) return
        try {
            file.forEachLine(Charsets.UTF_8) { line ->
                val p = line.split('\t')
                val n = p.getOrNull(2)?.toIntOrNull()
                if (p.size == 3 && n != null) data.getOrPut(p[0]) { HashMap() }[p[1]] = n
            }
        } catch (_: Exception) {
            data.clear()
        }
    }

    fun learn(context: String, picked: String) {
        val m = data.getOrPut(context) { HashMap() }
        m[picked] = (m[picked] ?: 0) + 1
        if (++dirty >= SAVE_EVERY) save()
    }

    /** Your own picks first (most used first), then the built-in order. */
    fun rank(context: String, base: List<String>): List<String> {
        val m = data[context]
        if (m.isNullOrEmpty()) return base
        val learned = m.entries.sortedByDescending { it.value }.map { it.key }
        val seen = learned.toHashSet()
        return learned + base.filter { it !in seen }
    }

    fun save() {
        if (dirty == 0) return
        dirty = 0
        prune()
        try {
            val tmp = File(file.path + ".tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                for ((ctx, m) in data) for ((cand, n) in m) {
                    w.append(ctx).append('\t').append(cand).append('\t').append(n.toString()).append('\n')
                }
            }
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }

    fun clear() {
        data.clear()
        dirty = 0
        file.delete()
    }

    /** Keeps the file small: once it gets big, drop the one-off picks. */
    private fun prune() {
        if (data.values.sumOf { it.size } < MAX_ENTRIES) return
        val it = data.values.iterator()
        while (it.hasNext()) {
            val m = it.next()
            m.values.removeAll { n -> n <= 1 }
            if (m.isEmpty()) it.remove()
        }
    }

    private companion object {
        const val SAVE_EVERY = 15
        const val MAX_ENTRIES = 20_000
    }
}

object Prefs {
    const val FILE = "mixboard"
    const val ASSOC = "assoc"
    const val ENGLISH = "english"
    const val SMART_PUNCT = "smart_punct"
    const val HAPTICS = "haptics"
    const val SPLIT = "split_wide"
    const val RECENT_EMOJI = "recent_emoji"
    const val RESET_REQUESTED = "reset_requested"
    const val RESET_DONE = "reset_done"
}
