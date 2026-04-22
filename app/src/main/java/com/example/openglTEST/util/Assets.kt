package com.example.openglTEST.util

import android.content.Context


fun readAssetText(ctx: Context, name: String): String =
    ctx.assets.open(name).use { it.reader(Charsets.UTF_8).readText() }
