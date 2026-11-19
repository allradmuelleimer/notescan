package com.nicedevelopments.vomblatt

import android.content.Context
import java.io.File

/**
 * Nur die Testfassung bringt unter assets/demo zwei echte Erkennungsergebnisse (Foto + MusicXML) mit.
 * Sie werden beim ersten Start einmalig in die Bibliothek gelegt.
 */
object Demo {
    /** Liefert true, wenn neue Stücke angelegt wurden. */
    fun seedIfNeeded(app: VomBlattApp): Boolean {
        val prefs = app.getSharedPreferences("app", Context.MODE_PRIVATE)
        if (prefs.getBoolean("demo_seeded", false)) return false
        val names = runCatching { app.assets.list("demo")?.toList() }.getOrNull().orEmpty()
        val bases = names.filter { it.endsWith(".musicxml") }.map { it.removeSuffix(".musicxml") }.sortedDescending()
        for (b in bases) try {
            val xml = app.assets.open("demo/$b.musicxml").bufferedReader().readText()
            val photo = if ("$b.jpg" in names) File(app.cacheDir, "$b.jpg").also { f ->
                app.assets.open("demo/$b.jpg").use { i -> f.outputStream().use { i.copyTo(it) } }
            } else null
            app.library.create(xml, photo, b)
            photo?.delete()
            Thread.sleep(5)
        } catch (e: Exception) {
            android.util.Log.e("VomBlatt", "Beispiel $b nicht angelegt", e)
        }
        prefs.edit().putBoolean("demo_seeded", true).apply()
        return bases.isNotEmpty()
    }
}
