package com.example.openglTEST

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.openglTEST.wallpaper.TiltWallpaperService
import com.example.openglTEST.wallpaper.WallpaperTuning
import com.example.openglTEST.BuildConfig


class MainActivity : AppCompatActivity() {
    private data class Preset(
        val id: String,
        val label: String,
        val tuning: WallpaperTuning
    )

    private data class SliderBinding(
        val label: TextView,
        val seekBar: SeekBar,
        val max: Float,
        val valueFormatter: (Float) -> String
    )

    private val sliderBindings = mutableMapOf<Int, SliderBinding>()
    private lateinit var presetLabel: TextView
    private lateinit var selectedPresetLabel: TextView
    private lateinit var editingPresetLabel: TextView
    private lateinit var paletteAssetLabel: TextView
    private var updatingUi = false
    private var selectedPresetId = "P1"
    private var editingPresetId: String? = null

    private val presets by lazy {
        listOf(
            Preset("P1", "ザビモノ", WallpaperTuning.DEFAULT.copy(
                presetId = "P1",
                paletteAsset = "pack.json",
                rgbSplitPx = 18f,
                frontDotSizePx = 18f,
                frontFlicker = 0.98f,
                l0MaxSizePx = 210f,
                stripeMaxWidthRatio = 0.30f,
                parallaxSensitivity = 0.82f,
                flickerMode = WallpaperTuning.FLICKER_MODE_TILT,
                colorVariance = 0.00f,
                accentChance = 0.00f
            )),
            Preset("P2", "ザビカラー", WallpaperTuning.DEFAULT.copy(
                presetId = "P2",
                paletteAsset = "pack_supervivid_test.json",
                rgbSplitPx = 24f,
                frontDotSizePx = 16f,
                frontFlicker = 1.00f,
                l0MaxSizePx = 220f,
                stripeMaxWidthRatio = 0.34f,
                parallaxSensitivity = 0.88f,
                flickerMode = WallpaperTuning.FLICKER_MODE_TILT,
                colorVariance = 0.70f,
                accentChance = 0.32f
            )),
            Preset("P3", "ゆらモノ", WallpaperTuning.DEFAULT.copy(
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
            )),
            Preset("P4", "ゆらカラー", WallpaperTuning.DEFAULT.copy(
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
            ))
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // バージョンを表示
        val versionText = findViewById<TextView>(R.id.versionText)
        versionText.text = "Version: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})"
        presetLabel = findViewById(R.id.presetCurrentValue)
        selectedPresetLabel = findViewById(R.id.presetSelectedValue)
        editingPresetLabel = findViewById(R.id.editingPresetValue)
        paletteAssetLabel = findViewById(R.id.paletteAssetValue)

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
        bindPresetButtons()
        bindPresetActions()
        bindPaletteButtons()
        val current = WallpaperTuning.load(this)
        val safeCurrent = if (presets.any { it.id == current.presetId }) {
            current
        } else {
            presets.first().tuning
        }
        selectedPresetId = safeCurrent.presetId
        WallpaperTuning.save(this, safeCurrent)
        refreshUi(safeCurrent)

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
        sliderBindings[seekBarId] = SliderBinding(label, seekBar, max, valueFormatter)
        setSliderValue(seekBarId, current)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (updatingUi) return
                val value = (max * progress / stepCount.toFloat()).coerceIn(0f, max)
                label.text = valueFormatter(value)
                val updated = update(
                    WallpaperTuning.load(this@MainActivity),
                    value
                )
                WallpaperTuning.save(this@MainActivity, updated)
                updatePresetLabel(updated)
                updateEditingLabel()
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
            if (updatingUi) return@setOnCheckedChangeListener
            val mode = when (id) {
                R.id.flickerModeNone -> WallpaperTuning.FLICKER_MODE_NONE
                R.id.flickerModeTilt -> WallpaperTuning.FLICKER_MODE_TILT
                else -> WallpaperTuning.FLICKER_MODE_ALWAYS
            }
            val updated = WallpaperTuning.load(this).copy(
                flickerMode = mode
            )
            WallpaperTuning.save(this, updated)
            updatePresetLabel(updated)
            updateEditingLabel()
        }
    }

    private fun bindPresetButtons() {
        val buttonIds = listOf(
            R.id.presetBtn1, R.id.presetBtn2, R.id.presetBtn3, R.id.presetBtn4,
            R.id.presetBtn5, R.id.presetBtn6, R.id.presetBtn7, R.id.presetBtn8
        )
        buttonIds.forEach { buttonId ->
            findViewById<Button>(buttonId).visibility = View.GONE
        }
        presets.zip(buttonIds).forEach { (preset, buttonId) ->
            findViewById<Button>(buttonId).apply {
                visibility = View.VISIBLE
                text = preset.label
                setOnClickListener {
                    selectedPresetId = preset.id
                    updateSelectedPresetLabel()
                }
            }
        }
    }

    private fun bindPresetActions() {
        findViewById<Button>(R.id.btnEditPreset).setOnClickListener {
            val tuning = loadSelectedPreset()
            editingPresetId = selectedPresetId
            WallpaperTuning.save(this, tuning)
            refreshUi(tuning)
        }

        findViewById<Button>(R.id.btnPreviewPreset).setOnClickListener {
            val tuning = loadSelectedPreset()
            WallpaperTuning.save(this, tuning)
            refreshUi(tuning)
        }

        findViewById<Button>(R.id.btnSavePreset).setOnClickListener {
            val targetPresetId = editingPresetId ?: selectedPresetId
            val tuning = WallpaperTuning.load(this).copy(presetId = targetPresetId)
            WallpaperTuning.savePreset(this, tuning)
            editingPresetId = targetPresetId
            selectedPresetId = targetPresetId
            refreshUi(tuning)
        }
    }

    private fun bindPaletteButtons() {
        findViewById<Button>(R.id.btnPaletteBase).setOnClickListener {
            val updated = WallpaperTuning.load(this).copy(paletteAsset = "pack.json")
            WallpaperTuning.save(this, updated)
            refreshUi(updated)
        }
        findViewById<Button>(R.id.btnPaletteVivid).setOnClickListener {
            val updated = WallpaperTuning.load(this).copy(paletteAsset = "pack_vivid_test.json")
            WallpaperTuning.save(this, updated)
            refreshUi(updated)
        }
        findViewById<Button>(R.id.btnPaletteSuperVivid).setOnClickListener {
            val updated = WallpaperTuning.load(this).copy(paletteAsset = "pack_supervivid_test.json")
            WallpaperTuning.save(this, updated)
            refreshUi(updated)
        }
    }

    private fun loadSelectedPreset(): WallpaperTuning {
        val preset = presets.first { it.id == selectedPresetId }
        return WallpaperTuning.loadPreset(this, selectedPresetId, preset.tuning)
    }

    private fun refreshUi(tuning: WallpaperTuning) {
        updatingUi = true
        try {
            setSliderValue(R.id.rgbSplitSeek, tuning.rgbSplitPx)
            setSliderValue(R.id.parallaxSeek, tuning.parallaxSensitivity)
            setSliderValue(R.id.frontDotSizeSeek, tuning.frontDotSizePx)
            setSliderValue(R.id.frontDotBreakupSeek, tuning.frontFlicker)
            setSliderValue(R.id.l0MaxSizeSeek, tuning.l0MaxSizePx)
            setSliderValue(R.id.stripeMaxWidthSeek, tuning.stripeMaxWidthRatio)
            setSliderValue(R.id.colorVarianceSeek, tuning.colorVariance)
            setSliderValue(R.id.accentChanceSeek, tuning.accentChance)

            val group = findViewById<RadioGroup>(R.id.flickerModeGroup)
            val checkedId = when (tuning.flickerMode) {
                WallpaperTuning.FLICKER_MODE_NONE -> R.id.flickerModeNone
                WallpaperTuning.FLICKER_MODE_TILT -> R.id.flickerModeTilt
                else -> R.id.flickerModeAlways
            }
            group.check(checkedId)
            updatePresetLabel(tuning)
            updateSelectedPresetLabel()
            updateEditingLabel()
            updatePaletteLabel(tuning)
        } finally {
            updatingUi = false
        }
    }

    private fun setSliderValue(seekBarId: Int, value: Float) {
        val binding = sliderBindings[seekBarId] ?: return
        binding.seekBar.progress = ((value / binding.max) * binding.seekBar.max).toInt()
            .coerceIn(0, binding.seekBar.max)
        binding.label.text = binding.valueFormatter(value)
    }

    private fun updatePresetLabel(tuning: WallpaperTuning) {
        val presetName = presets.firstOrNull { it.id == tuning.presetId }?.label ?: "Custom"
        presetLabel.text = "現在: $presetName"
    }

    private fun updateSelectedPresetLabel() {
        val selectedName = presets.firstOrNull { it.id == selectedPresetId }?.label ?: selectedPresetId
        selectedPresetLabel.text = "選択中: $selectedName"
    }

    private fun updateEditingLabel() {
        val label = editingPresetId?.let { id ->
            presets.firstOrNull { it.id == id }?.label ?: id
        } ?: "なし"
        editingPresetLabel.text = "編集中: $label"
    }

    private fun updatePaletteLabel(tuning: WallpaperTuning) {
        val label = when (tuning.paletteAsset) {
            "pack.json" -> "base"
            "pack_vivid_test.json" -> "vivid"
            "pack_supervivid_test.json" -> "supervivid"
            else -> tuning.paletteAsset
        }
        paletteAssetLabel.text = "色セット: $label"
    }
}
