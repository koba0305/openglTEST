package com.example.openglTEST

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.openglTEST.wallpaper.TiltWallpaperService
import com.example.openglTEST.wallpaper.WallpaperTuning
import com.example.openglTEST.BuildConfig


class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // バージョンを表示
        val versionText = findViewById<TextView>(R.id.versionText)
        versionText.text = "Version: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})"

        bindSlider(
            labelId = R.id.rgbSplitValue,
            seekBarId = R.id.rgbSplitSeek,
            valueFormatter = { "%.1f px".format(it) },
            current = WallpaperTuning.load(this).rgbSplitPx,
            max = 24f
        ) { tuning, value -> tuning.copy(rgbSplitPx = value) }

        bindSlider(
            labelId = R.id.parallaxValue,
            seekBarId = R.id.parallaxSeek,
            valueFormatter = { "%.2fx".format(it) },
            current = WallpaperTuning.load(this).parallaxSensitivity,
            max = 3f
        ) { tuning, value -> tuning.copy(parallaxSensitivity = value.coerceAtLeast(0.1f)) }

        bindSlider(
            labelId = R.id.frontDotSizeValue,
            seekBarId = R.id.frontDotSizeSeek,
            valueFormatter = { "%.1f px".format(it) },
            current = WallpaperTuning.load(this).frontDotSizePx,
            max = 32f
        ) { tuning, value -> tuning.copy(frontDotSizePx = value) }

        bindSlider(
            labelId = R.id.frontDotBreakupValue,
            seekBarId = R.id.frontDotBreakupSeek,
            valueFormatter = { "%.2f".format(it) },
            current = WallpaperTuning.load(this).frontFlicker,
            max = 1f
        ) { tuning, value -> tuning.copy(frontFlicker = value) }

        bindSlider(
            labelId = R.id.l0MaxSizeValue,
            seekBarId = R.id.l0MaxSizeSeek,
            valueFormatter = { "%.0f px".format(it) },
            current = WallpaperTuning.load(this).l0MaxSizePx,
            max = 320f
        ) { tuning, value -> tuning.copy(l0MaxSizePx = value) }

        bindSlider(
            labelId = R.id.stripeMaxWidthValue,
            seekBarId = R.id.stripeMaxWidthSeek,
            valueFormatter = { "%.0f%% of screen".format(it * 100f) },
            current = WallpaperTuning.load(this).stripeMaxWidthRatio,
            max = 0.5f
        ) { tuning, value -> tuning.copy(stripeMaxWidthRatio = value) }

        bindSlider(
            labelId = R.id.colorVarianceValue,
            seekBarId = R.id.colorVarianceSeek,
            valueFormatter = { "%.0f%%".format(it * 100f) },
            current = WallpaperTuning.load(this).colorVariance,
            max = 1f
        ) { tuning, value -> tuning.copy(colorVariance = value) }

        bindSlider(
            labelId = R.id.accentChanceValue,
            seekBarId = R.id.accentChanceSeek,
            valueFormatter = { "%.0f%%".format(it * 100f) },
            current = WallpaperTuning.load(this).accentChance,
            max = 0.5f
        ) { tuning, value -> tuning.copy(accentChance = value) }

        bindFlickerMode()

        // ボタンで壁紙設定画面へ
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

    private fun bindSlider(
        labelId: Int,
        seekBarId: Int,
        valueFormatter: (Float) -> String,
        current: Float,
        max: Float,
        update: (WallpaperTuning, Float) -> WallpaperTuning
    ) {
        val label = findViewById<TextView>(labelId)
        val seekBar = findViewById<SeekBar>(seekBarId)
        val stepCount = 100
        seekBar.max = stepCount
        seekBar.progress = ((current / max) * stepCount).toInt().coerceIn(0, stepCount)
        label.text = valueFormatter(current)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (max * progress / stepCount.toFloat()).coerceIn(0f, max)
                label.text = valueFormatter(value)
                val updated = update(WallpaperTuning.load(this@MainActivity), value)
                WallpaperTuning.save(this@MainActivity, updated)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun bindFlickerMode() {
        val group = findViewById<RadioGroup>(R.id.flickerModeGroup)
        val checkedId = when (WallpaperTuning.load(this).flickerMode) {
            WallpaperTuning.FLICKER_MODE_NONE -> R.id.flickerModeNone
            WallpaperTuning.FLICKER_MODE_TILT -> R.id.flickerModeTilt
            else -> R.id.flickerModeAlways
        }
        group.check(checkedId)
        group.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                R.id.flickerModeNone -> WallpaperTuning.FLICKER_MODE_NONE
                R.id.flickerModeTilt -> WallpaperTuning.FLICKER_MODE_TILT
                else -> WallpaperTuning.FLICKER_MODE_ALWAYS
            }
            WallpaperTuning.save(this, WallpaperTuning.load(this).copy(flickerMode = mode))
        }
    }
}
