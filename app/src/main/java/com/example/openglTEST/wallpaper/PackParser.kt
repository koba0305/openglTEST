// app/src/main/java/com/example/openglTEST/wallpaper/PackParser.kt
package com.example.openglTEST.wallpaper

import android.content.Context
import android.util.Log
import com.example.openglTEST.wallpaper.model.Pack
import com.google.gson.Gson

private const val TAG = "PackParser"

/**
 * assets 内の pack.json を読んで Pack にデコードするヘルパー。
 * エラー時は null を返す。
 */
fun loadPackFromAssets(
    context: Context,
    fileName: String = "pack.json"
): Pack? {
    return try {
        val json = context.assets.open(fileName)
            .bufferedReader()
            .use { it.readText() }

        val pack = Gson().fromJson(json, Pack::class.java)
        Log.d(TAG, "Pack loaded from assets/$fileName, layers=${pack.layers.size}")
        pack
    } catch (e: Exception) {
        Log.w(TAG, "Failed to load pack from assets/$fileName: ${e.message}")
        null
    }
}

/**
 * 任意の JSON 文字列をそのまま Pack にパースするだけの関数。
 */
fun parsePackJson(json: String): Pack =
    Gson().fromJson(json, Pack::class.java)
