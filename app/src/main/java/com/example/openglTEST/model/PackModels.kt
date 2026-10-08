// app/src/main/java/com/example/openglTEST/wallpaper/model/PackModels.kt
package com.example.openglTEST.wallpaper.model

data class PaletteItem(
    val hex: String,
    val w: Double
)

data class LayerCfg(
    val id: String,
    val depth: Double,
    val parallax: Double,
    val opacity: Double,
    val fps: Int,
    val palette_bias: List<Int>
)

data class TileQuantize(
    val tile: Int,
    val strength: Double
)

data class BlueNoiseDropout(
    val prob: Double,
    val palette_lock: Boolean
)

data class RgbSplit(
    val px: Double,
    val dir: String?
)

data class GlitchOps(
    val feedback_delay: Map<String, Any>? = null,
    val advection_flow: Map<String, Any>? = null,
    val rolling_scan_skew: Map<String, Any>? = null,
    val blue_noise_dropout: BlueNoiseDropout,
    val tile_quantize: TileQuantize,
    val chroma_phase_lag: Map<String, Any>? = null,
    val rgb_split: RgbSplit
)

data class Pack(
    val version: Int,
    val seed: Int,
    val background: Map<String, String>,
    val palette: List<PaletteItem>,
    val layers: List<LayerCfg>,
    val glitch_ops: GlitchOps
)
