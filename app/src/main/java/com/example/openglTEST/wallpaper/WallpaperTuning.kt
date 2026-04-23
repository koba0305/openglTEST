package com.example.openglTEST.wallpaper

import android.content.Context

data class WallpaperTuning(
    val presetId: String,
    val paletteAsset: String,
    val rgbSplitPx: Float,
    val frontDotSizePx: Float,
    val frontFlicker: Float,
    val l0MaxSizePx: Float,
    val stripeMaxWidthRatio: Float,
    val parallaxSensitivity: Float,
    val flickerMode: Int,
    val colorVariance: Float,
    val accentChance: Float
) {
    companion object {
        const val FLICKER_MODE_NONE = 0
        const val FLICKER_MODE_ALWAYS = 1
        const val FLICKER_MODE_TILT = 2

        private const val PREFS_NAME = "wallpaper_tuning"
        private const val KEY_PRESET_ID = "preset_id"
        private const val KEY_PALETTE_ASSET = "palette_asset"
        private const val KEY_RGB_SPLIT_PX = "rgb_split_px"
        private const val KEY_FRONT_DOT_SIZE_PX = "front_dot_size_px"
        private const val KEY_FRONT_FLICKER = "front_flicker"
        private const val KEY_L0_MAX_SIZE_PX = "l0_max_size_px"
        private const val KEY_STRIPE_MAX_WIDTH_RATIO = "stripe_max_width_ratio"
        private const val KEY_PARALLAX_SENSITIVITY = "parallax_sensitivity"
        private const val KEY_FLICKER_MODE = "flicker_mode"
        private const val KEY_COLOR_VARIANCE = "color_variance"
        private const val KEY_ACCENT_CHANCE = "accent_chance"

        private fun presetKey(prefix: String, presetId: String) = "${prefix}_${presetId}"

        val DEFAULT = WallpaperTuning(
            presetId = "P1",
            paletteAsset = "pack_vivid_test.json",
            rgbSplitPx = 8f,
            frontDotSizePx = 12f,
            frontFlicker = 0.72f,
            l0MaxSizePx = 120f,
            stripeMaxWidthRatio = 0.18f,
            parallaxSensitivity = 1.35f,
            flickerMode = FLICKER_MODE_ALWAYS,
            colorVariance = 0.58f,
            accentChance = 0.16f
        )

        fun load(context: Context): WallpaperTuning {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return WallpaperTuning(
                presetId = prefs.getString(KEY_PRESET_ID, DEFAULT.presetId) ?: DEFAULT.presetId,
                paletteAsset = prefs.getString(KEY_PALETTE_ASSET, DEFAULT.paletteAsset) ?: DEFAULT.paletteAsset,
                rgbSplitPx = prefs.getFloat(KEY_RGB_SPLIT_PX, DEFAULT.rgbSplitPx),
                frontDotSizePx = prefs.getFloat(KEY_FRONT_DOT_SIZE_PX, DEFAULT.frontDotSizePx),
                frontFlicker = prefs.getFloat(
                    KEY_FRONT_FLICKER,
                    prefs.getFloat("front_dot_breakup", DEFAULT.frontFlicker)
                ),
                l0MaxSizePx = prefs.getFloat(
                    KEY_L0_MAX_SIZE_PX,
                    prefs.getFloat("band_height_px", DEFAULT.l0MaxSizePx)
                ),
                stripeMaxWidthRatio = prefs.getFloat(
                    KEY_STRIPE_MAX_WIDTH_RATIO,
                    prefs.getFloat("stripe_strength", DEFAULT.stripeMaxWidthRatio)
                ),
                parallaxSensitivity = prefs.getFloat(KEY_PARALLAX_SENSITIVITY, DEFAULT.parallaxSensitivity),
                flickerMode = prefs.getInt(KEY_FLICKER_MODE, DEFAULT.flickerMode),
                colorVariance = prefs.getFloat(KEY_COLOR_VARIANCE, DEFAULT.colorVariance),
                accentChance = prefs.getFloat(KEY_ACCENT_CHANCE, DEFAULT.accentChance)
            )
        }

        fun save(context: Context, tuning: WallpaperTuning) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PRESET_ID, tuning.presetId)
                .putString(KEY_PALETTE_ASSET, tuning.paletteAsset)
                .putFloat(KEY_RGB_SPLIT_PX, tuning.rgbSplitPx)
                .putFloat(KEY_FRONT_DOT_SIZE_PX, tuning.frontDotSizePx)
                .putFloat(KEY_FRONT_FLICKER, tuning.frontFlicker)
                .putFloat(KEY_L0_MAX_SIZE_PX, tuning.l0MaxSizePx)
                .putFloat(KEY_STRIPE_MAX_WIDTH_RATIO, tuning.stripeMaxWidthRatio)
                .putFloat(KEY_PARALLAX_SENSITIVITY, tuning.parallaxSensitivity)
                .putInt(KEY_FLICKER_MODE, tuning.flickerMode)
                .putFloat(KEY_COLOR_VARIANCE, tuning.colorVariance)
                .putFloat(KEY_ACCENT_CHANCE, tuning.accentChance)
                .apply()
        }

        fun loadPreset(context: Context, presetId: String, fallback: WallpaperTuning): WallpaperTuning {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return fallback.copy(
                presetId = presetId,
                paletteAsset = prefs.getString(presetKey(KEY_PALETTE_ASSET, presetId), fallback.paletteAsset) ?: fallback.paletteAsset,
                rgbSplitPx = prefs.getFloat(presetKey(KEY_RGB_SPLIT_PX, presetId), fallback.rgbSplitPx),
                frontDotSizePx = prefs.getFloat(presetKey(KEY_FRONT_DOT_SIZE_PX, presetId), fallback.frontDotSizePx),
                frontFlicker = prefs.getFloat(presetKey(KEY_FRONT_FLICKER, presetId), fallback.frontFlicker),
                l0MaxSizePx = prefs.getFloat(presetKey(KEY_L0_MAX_SIZE_PX, presetId), fallback.l0MaxSizePx),
                stripeMaxWidthRatio = prefs.getFloat(presetKey(KEY_STRIPE_MAX_WIDTH_RATIO, presetId), fallback.stripeMaxWidthRatio),
                parallaxSensitivity = prefs.getFloat(presetKey(KEY_PARALLAX_SENSITIVITY, presetId), fallback.parallaxSensitivity),
                flickerMode = prefs.getInt(presetKey(KEY_FLICKER_MODE, presetId), fallback.flickerMode),
                colorVariance = prefs.getFloat(presetKey(KEY_COLOR_VARIANCE, presetId), fallback.colorVariance),
                accentChance = prefs.getFloat(presetKey(KEY_ACCENT_CHANCE, presetId), fallback.accentChance)
            )
        }

        fun savePreset(context: Context, tuning: WallpaperTuning) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(presetKey(KEY_PALETTE_ASSET, tuning.presetId), tuning.paletteAsset)
                .putFloat(presetKey(KEY_RGB_SPLIT_PX, tuning.presetId), tuning.rgbSplitPx)
                .putFloat(presetKey(KEY_FRONT_DOT_SIZE_PX, tuning.presetId), tuning.frontDotSizePx)
                .putFloat(presetKey(KEY_FRONT_FLICKER, tuning.presetId), tuning.frontFlicker)
                .putFloat(presetKey(KEY_L0_MAX_SIZE_PX, tuning.presetId), tuning.l0MaxSizePx)
                .putFloat(presetKey(KEY_STRIPE_MAX_WIDTH_RATIO, tuning.presetId), tuning.stripeMaxWidthRatio)
                .putFloat(presetKey(KEY_PARALLAX_SENSITIVITY, tuning.presetId), tuning.parallaxSensitivity)
                .putInt(presetKey(KEY_FLICKER_MODE, tuning.presetId), tuning.flickerMode)
                .putFloat(presetKey(KEY_COLOR_VARIANCE, tuning.presetId), tuning.colorVariance)
                .putFloat(presetKey(KEY_ACCENT_CHANCE, tuning.presetId), tuning.accentChance)
                .apply()
        }
    }
}
