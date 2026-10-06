package com.enzo.ilhadinamica

import android.content.Context
import android.content.SharedPreferences

/** Preferências da ilha: ligada, tamanho e ajuste fino de posição em volta da câmera. */
class IslandPrefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("ilha", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Escala da cápsula (0.7–1.1), igual à propriedade "scale" do protótipo. */
    var scale: Float
        get() = sp.getFloat("scale", 1f)
        set(v) = sp.edit().putFloat("scale", v.coerceIn(0.7f, 1.1f)).apply()

    /** Tamanho da bolha em relação ao furo da câmera. */
    var bubbleScale: Float
        get() = sp.getFloat("bubbleScale", 1f)
        set(v) = sp.edit().putFloat("bubbleScale", v.coerceIn(0.6f, 1.8f)).apply()

    /** Deslocamento horizontal em dp. */
    var offsetX: Float
        get() = sp.getFloat("dx", 0f)
        set(v) = sp.edit().putFloat("dx", v).apply()

    /** Deslocamento vertical em dp. */
    var offsetY: Float
        get() = sp.getFloat("dy", 0f)
        set(v) = sp.edit().putFloat("dy", v).apply()

    /** Abrir a cápsula automaticamente quando chega notificação/mídia real. */
    var realEvents: Boolean
        get() = sp.getBoolean("realEvents", true)
        set(v) = sp.edit().putBoolean("realEvents", v).apply()
}
