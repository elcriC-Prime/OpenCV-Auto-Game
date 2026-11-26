package com.battlecats.autobot

import android.content.Context
import android.graphics.BitmapFactory
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

enum class GameState(val label: String) {
    STAGE_SELECT("圖1 戰鬥開始"),
    IN_BATTLE("圖2 戰鬥中"),
    VICTORY_REWARD("圖3 過關獎勵"),
    STAMINA_POPUP("圖4 統率力不足"),
    VICTORY_MENU("圖5 過關結算"),
    EX_POPUP("圖6 EX關卡"),
    UNKNOWN("辨識待機中"),
}

data class MatchResult(
    val name: String,
    val score: Double,
    val brightnessRatio: Double,
    val centerX: Float,
    val centerY: Float,
    val scaleUsed: Float,
)

data class Detection(
    val state: GameState,
    val targetX: Float?,
    val targetY: Float?,
    val match: MatchResult?,
)

private data class RoiFrac(val x0: Double, val y0: Double, val x1: Double, val y1: Double)

class ScreenMatcher(context: Context) {

    companion object {
        private const val REF_HEIGHT = 472
        private const val MISSES_BEFORE_RESCAN = 15

        private val CANDIDATE_SCALES = listOf(
            1.00f,
            0.90f,
            0.80f,
            0.70f,
            0.60f,
            0.52f,
            1.10f,
            1.20f,
        )

        private val TEMPLATE_NAMES = listOf(
            "btn_battle_start",
            "icon_pause",
            "reward_header",
            "dialog_stamina",
            "btn_return_map",
            "dialog_ex_stage",
            "btn_yes",
            "btn_yes_ex",
        )

        private val TEMPLATE_ROIS = mapOf(
            "dialog_stamina" to RoiFrac(0.15, 0.15, 0.85, 0.70),
            "dialog_ex_stage" to RoiFrac(0.15, 0.15, 0.85, 0.70),
            "btn_yes" to RoiFrac(0.15, 0.35, 0.65, 0.85),
            "btn_yes_ex" to RoiFrac(0.15, 0.35, 0.65, 0.85),
            "reward_header" to RoiFrac(0.15, 0.40, 0.85, 0.90),
            "btn_return_map" to RoiFrac(0.55, 0.00, 1.00, 0.45),
            "btn_battle_start" to RoiFrac(0.45, 0.35, 1.00, 0.95),
            "icon_pause" to RoiFrac(0.00, 0.00, 0.45, 0.40),
        )
    }

    private val baseTemplates = mutableMapOf<String, Mat>()
    private val scaledTemplates = mutableMapOf<Pair<String, Float>, Mat>()
    private val templateMeans = mutableMapOf<Pair<String, Float>, Double>()

    private var cachedScale: Float = 1.00f
    private var hasLockedScale: Boolean = false
    private var consecutiveMisses: Int = 0
    private var fallbackCursor: Int = 0

    // Reusable working Mats (initialized AFTER OpenCVLoader.initLocal()!)
    private lateinit var resizedRgba: Mat
    private lateinit var frameRefBgr: Mat

    init {
        check(OpenCVLoader.initLocal()) { "OpenCV native library failed to initialize" }
        resizedRgba = Mat()
        frameRefBgr = Mat()
        val assets = context.assets
        for (name in TEMPLATE_NAMES) {
            assets.open("templates/$name.png").use { stream ->
                val bmp = BitmapFactory.decodeStream(stream)
                    ?: error("Failed to decode template: $name.png")
                val rgba = Mat()
                val bgr = Mat()
                try {
                    Utils.bitmapToMat(bmp, rgba)
                    Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                    val cloned = bgr.clone()
                    baseTemplates[name] = cloned
                    scaledTemplates[name to 1.00f] = cloned
                    val m = Core.mean(cloned)
                    templateMeans[name to 1.00f] = (m.`val`[0] + m.`val`[1] + m.`val`[2]) / 3.0
                } finally {
                    rgba.release()
                    bgr.release()
                    bmp.recycle()
                }
            }
        }
    }

    fun resetScaleLock() {
        hasLockedScale = false
        consecutiveMisses = 0
        fallbackCursor = 0
    }

    fun isIdleAtLockedScale(): Boolean = hasLockedScale && consecutiveMisses < MISSES_BEFORE_RESCAN

    private fun getScaledTemplate(name: String, scale: Float): Pair<Mat, Double> {
        val key = name to scale
        val existing = scaledTemplates[key]
        if (existing != null) {
            return existing to (templateMeans[key] ?: 1.0)
        }
        val base = baseTemplates.getValue(name)
        val tw = (base.cols() * scale).roundToInt().coerceAtLeast(8)
        val th = (base.rows() * scale).roundToInt().coerceAtLeast(8)
        val resized = Mat()
        val interp = if (scale < 1.0f) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR
        Imgproc.resize(base, resized, Size(tw.toDouble(), th.toDouble()), 0.0, 0.0, interp)
        val m = Core.mean(resized)
        val meanVal = ((m.`val`[0] + m.`val`[1] + m.`val`[2]) / 3.0).coerceAtLeast(1.0)
        scaledTemplates[key] = resized
        templateMeans[key] = meanVal
        return resized to meanVal
    }

    private fun scoreTemplate(
        frameRef: Mat,
        name: String,
        scale: Float,
        coordScale: Double,
    ): MatchResult? {
        val (tpl, tplMean) = getScaledTemplate(name, scale)
        val roiFrac = TEMPLATE_ROIS[name] ?: RoiFrac(0.0, 0.0, 1.0, 1.0)
        val fw = frameRef.cols()
        val fh = frameRef.rows()
        val rx0 = (roiFrac.x0 * fw).toInt().coerceIn(0, fw - 1)
        val ry0 = (roiFrac.y0 * fh).toInt().coerceIn(0, fh - 1)
        val rx1 = (roiFrac.x1 * fw).toInt().coerceIn(rx0 + 1, fw)
        val ry1 = (roiFrac.y1 * fh).toInt().coerceIn(ry0 + 1, fh)
        val rw = rx1 - rx0
        val rh = ry1 - ry0

        if (tpl.cols() > rw || tpl.rows() > rh) {
            return null
        }

        val roi = frameRef.submat(Rect(rx0, ry0, rw, rh))
        val result = Mat(rh - tpl.rows() + 1, rw - tpl.cols() + 1, CvType.CV_32FC1)
        try {
            Imgproc.matchTemplate(roi, tpl, result, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(result)
            val lx = mm.maxLoc.x.toInt().coerceIn(0, rw - tpl.cols())
            val ly = mm.maxLoc.y.toInt().coerceIn(0, rh - tpl.rows())
            val patch = roi.submat(Rect(lx, ly, tpl.cols(), tpl.rows()))
            val brightRatio = try {
                val pm = Core.mean(patch)
                val patchMean = (pm.`val`[0] + pm.`val`[1] + pm.`val`[2]) / 3.0
                patchMean / tplMean
            } finally {
                patch.release()
            }
            val cx = ((rx0 + lx + tpl.cols() / 2.0) * coordScale).toFloat()
            val cy = ((ry0 + ly + tpl.rows() / 2.0) * coordScale).toFloat()
            return MatchResult(
                name = name,
                score = mm.maxVal,
                brightnessRatio = brightRatio,
                centerX = cx,
                centerY = cy,
                scaleUsed = scale,
            )
        } finally {
            result.release()
            roi.release()
        }
    }

    private fun isHit(
        m: MatchResult?,
        threshold: Double,
        brightnessMin: Double,
    ): Boolean = m != null && m.score >= threshold && m.brightnessRatio >= brightnessMin

    private fun detectAtScale(
        frameRef: Mat,
        scale: Float,
        coordScale: Double,
        threshold: Double,
        brightnessMin: Double,
    ): Detection? {
        // Priority 1: 圖4 統率力不足彈窗
        val mStamina = scoreTemplate(frameRef, "dialog_stamina", scale, coordScale)
        if (isHit(mStamina, threshold, brightnessMin)) {
            val yes = scoreTemplate(frameRef, "btn_yes", scale, coordScale)
            val ok = isHit(yes, threshold, brightnessMin)
            return Detection(
                state = GameState.STAMINA_POPUP,
                targetX = if (ok) yes?.centerX else null,
                targetY = if (ok) yes?.centerY else null,
                match = yes,
            )
        }

        // Priority 2: 圖6 EX關卡彈窗
        val mEx = scoreTemplate(frameRef, "dialog_ex_stage", scale, coordScale)
        if (isHit(mEx, threshold, brightnessMin)) {
            val yesEx = scoreTemplate(frameRef, "btn_yes_ex", scale, coordScale)
            val chosen = if (isHit(yesEx, threshold, brightnessMin)) {
                yesEx
            } else {
                scoreTemplate(frameRef, "btn_yes", scale, coordScale)
            }
            val ok = isHit(chosen, threshold, brightnessMin)
            return Detection(
                state = GameState.EX_POPUP,
                targetX = if (ok) chosen?.centerX else null,
                targetY = if (ok) chosen?.centerY else null,
                match = chosen,
            )
        }

        // Priority 3: 圖3 過關獎勵畫面
        val mReward = scoreTemplate(frameRef, "reward_header", scale, coordScale)
        if (isHit(mReward, threshold, brightnessMin)) {
            return Detection(
                state = GameState.VICTORY_REWARD,
                targetX = mReward?.centerX,
                targetY = mReward?.centerY,
                match = mReward,
            )
        }

        // Priority 4: 圖5 過關結算畫面（右上黃色圓形按鈕）
        val mReturn = scoreTemplate(frameRef, "btn_return_map", scale, coordScale)
        if (isHit(mReturn, threshold, brightnessMin)) {
            return Detection(
                state = GameState.VICTORY_MENU,
                targetX = mReturn?.centerX,
                targetY = mReturn?.centerY,
                match = mReturn,
            )
        }

        // Priority 5: 圖1 戰鬥開始畫面
        val mStart = scoreTemplate(frameRef, "btn_battle_start", scale, coordScale)
        if (isHit(mStart, threshold, brightnessMin)) {
            return Detection(
                state = GameState.STAGE_SELECT,
                targetX = mStart?.centerX,
                targetY = mStart?.centerY,
                match = mStart,
            )
        }

        // Priority 6: 圖2 戰鬥中
        val mPause = scoreTemplate(frameRef, "icon_pause", scale, coordScale)
        if (isHit(mPause, threshold, brightnessMin)) {
            return Detection(
                state = GameState.IN_BATTLE,
                targetX = null,
                targetY = null,
                match = mPause,
            )
        }

        return null
    }

    fun detect(
        rgbaFrame: Mat,
        threshold: Double,
        brightnessMin: Double,
    ): Detection {
        val actualW = rgbaFrame.cols()
        val actualH = rgbaFrame.rows()
        if (actualW < actualH || actualH <= 0) {
            return Detection(GameState.UNKNOWN, null, null, null)
        }

        // Resize RGBA down FIRST (5x-9x fewer pixels for cvtColor!), preserving aspect ratio.
        val targetW = (actualW.toDouble() * REF_HEIGHT / actualH).roundToInt().coerceAtLeast(1)
        val coordScale = actualH.toDouble() / REF_HEIGHT

        if (actualH != REF_HEIGHT || actualW != targetW) {
            Imgproc.resize(
                rgbaFrame,
                resizedRgba,
                Size(targetW.toDouble(), REF_HEIGHT.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_LINEAR
            )
            Imgproc.cvtColor(resizedRgba, frameRefBgr, Imgproc.COLOR_RGBA2BGR)
        } else {
            Imgproc.cvtColor(rgbaFrame, frameRefBgr, Imgproc.COLOR_RGBA2BGR)
        }

        // Fast path: once device scale is locked, only check cachedScale on normal & transition frames!
        val primary = detectAtScale(frameRefBgr, cachedScale, coordScale, threshold, brightnessMin)
        if (primary != null) {
            hasLockedScale = true
            consecutiveMisses = 0
            return primary
        }

        consecutiveMisses++

        // Before scale is locked, or after prolonged misses, probe 1 fallback scale per frame (no multi-second freeze!)
        if (!hasLockedScale || consecutiveMisses >= MISSES_BEFORE_RESCAN) {
            val probeScale = CANDIDATE_SCALES[fallbackCursor % CANDIDATE_SCALES.size]
            fallbackCursor++
            if (probeScale != cachedScale) {
                val det = detectAtScale(frameRefBgr, probeScale, coordScale, threshold, brightnessMin)
                if (det != null) {
                    cachedScale = probeScale
                    hasLockedScale = true
                    consecutiveMisses = 0
                    return det
                }
            }
        }

        return Detection(GameState.UNKNOWN, null, null, null)
    }

    fun release() {
        val released = HashSet<Mat>()
        if (::resizedRgba.isInitialized && released.add(resizedRgba)) resizedRgba.release()
        if (::frameRefBgr.isInitialized && released.add(frameRefBgr)) frameRefBgr.release()
        for (m in baseTemplates.values) {
            if (released.add(m)) m.release()
        }
        for (m in scaledTemplates.values) {
            if (released.add(m)) m.release()
        }
        baseTemplates.clear()
        scaledTemplates.clear()
        templateMeans.clear()
    }
}
