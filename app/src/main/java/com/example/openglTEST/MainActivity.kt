package com.example.openglTEST

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.openglTEST.wallpaper.TiltWallpaperService
import com.example.openglTEST.wallpaper.WallpaperTuning

class MainActivity : AppCompatActivity() {
    private data class Preset(
        val id: String,
        val label: String,
        val tuning: WallpaperTuning
    )

    private lateinit var presetLabel: TextView

    private val presets by lazy {
        listOf(
            Preset(
                "P1",
                "ザビモノ",
                WallpaperTuning.DEFAULT.copy(
                    presetId = "P1",
                    paletteAsset = "pack.json",
                    rgbSplitPx = 24f,
                    frontDotSizePx = 7f,
                    frontFlicker = 1.00f,
                    l0MaxSizePx = 96f,
                    stripeMaxWidthRatio = 0.44f,
                    parallaxSensitivity = 0.82f,
                    flickerMode = WallpaperTuning.FLICKER_MODE_TILT,
                    colorVariance = 0.00f,
                    accentChance = 0.00f
                )
            ),
            Preset(
                "P2",
                "ザビカラー",
                WallpaperTuning.DEFAULT.copy(
                    presetId = "P2",
                    paletteAsset = "pack_supervivid_test.json",
                    rgbSplitPx = 30f,
                    frontDotSizePx = 6f,
                    frontFlicker = 1.00f,
                    l0MaxSizePx = 90f,
                    stripeMaxWidthRatio = 0.48f,
                    parallaxSensitivity = 0.88f,
                    flickerMode = WallpaperTuning.FLICKER_MODE_TILT,
                    colorVariance = 0.70f,
                    accentChance = 0.32f
                )
            ),
            Preset(
                "P3",
                "ゆらモノ",
                WallpaperTuning.DEFAULT.copy(
                    presetId = "P3",
                    paletteAsset = "pack.json",
                    rgbSplitPx = 3f,
                    frontDotSizePx = 24f,
                    frontFlicker = 0.18f,
                    l0MaxSizePx = 180f,
                    stripeMaxWidthRatio = 0.08f,
                    parallaxSensitivity = 2.75f,
                    flickerMode = WallpaperTuning.FLICKER_MODE_TILT,
                    colorVariance = 0.00f,
                    accentChance = 0.00f
                )
            ),
            Preset(
                "P4",
                "ゆらカラー",
                WallpaperTuning.DEFAULT.copy(
                    presetId = "P4",
                    paletteAsset = "pack_vivid_test.json",
                    rgbSplitPx = 6f,
                    frontDotSizePx = 22f,
                    frontFlicker = 0.24f,
                    l0MaxSizePx = 190f,
                    stripeMaxWidthRatio = 0.10f,
                    parallaxSensitivity = 3.00f,
                    flickerMode = WallpaperTuning.FLICKER_MODE_TILT,
                    colorVariance = 0.58f,
                    accentChance = 0.20f
                )
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.versionText).text =
            "Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        presetLabel = findViewById(R.id.presetCurrentValue)

        val current = WallpaperTuning.load(this)
        val initial = presets.firstOrNull { it.id == current.presetId } ?: presets.first()
        if (initial.id != current.presetId) {
            WallpaperTuning.save(this, initial.tuning)
        }
        updatePresetLabel(initial)
        bindPresetButtons()
        bindWallpaperButton()
    }

    private fun bindPresetButtons() {
        val buttonIds = listOf(
            R.id.presetBtn1,
            R.id.presetBtn2,
            R.id.presetBtn3,
            R.id.presetBtn4
        )
        presets.zip(buttonIds).forEach { (preset, buttonId) ->
            findViewById<Button>(buttonId).setOnClickListener {
                WallpaperTuning.save(this, preset.tuning)
                WallpaperTuning.savePreset(this, preset.tuning)
                updatePresetLabel(preset)
            }
        }
    }

    private fun bindWallpaperButton() {
        findViewById<Button>(R.id.btnSetWallpaper).setOnClickListener {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@MainActivity, TiltWallpaperService::class.java)
                )
            }
            startActivity(intent)
        }
    }

    private fun updatePresetLabel(preset: Preset) {
        presetLabel.text = "現在: ${preset.label}"
    }
}
