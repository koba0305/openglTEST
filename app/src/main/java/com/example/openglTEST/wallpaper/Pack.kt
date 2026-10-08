package com.example.openglTEST.wallpaper

// ---- JSONを受けるためのデータ型（OpenGLで使いやすいfloat中心） ----

data class PaletteItem(val hex: String, val w: Float)

data class TileQuantize(val tile: Int? = null, val strength: Float)
data class BlueNoiseDropout(val prob: Float, val palette_lock: Boolean? = null)
data class RgbSplit(val px: Float, val dir: String? = null)

data class GlitchOps(
    val tile_quantize: TileQuantize,
    val blue_noise_dropout: BlueNoiseDropout,
    val rgb_split: RgbSplit
)

