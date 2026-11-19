package com.nicedevelopments.vomblatt

import android.app.Application
import com.nicedevelopments.vomblatt.audio.PianoEngine
import com.nicedevelopments.vomblatt.data.Library
import com.nicedevelopments.vomblatt.recognize.Recognizer

class VomBlattApp : Application() {
    val library by lazy { Library(this) }
    val engine by lazy { PianoEngine(this) }
    val recognizer by lazy { Recognizer.create(this) }
    val pro by lazy { com.nicedevelopments.vomblatt.pro.ProBilling(this) }

    /** Zählt hoch, wenn sich die Bibliothek im Hintergrund geändert hat. */
    val libraryVersion = kotlinx.coroutines.flow.MutableStateFlow(0)

    override fun onCreate() {
        super.onCreate()
        pro.start()
        Thread { if (Demo.seedIfNeeded(this)) libraryVersion.value++ }.start()
    }
}
