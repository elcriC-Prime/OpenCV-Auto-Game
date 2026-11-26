package com.battlecats.autobot

import android.content.Context
import android.content.SharedPreferences

class BotConfig(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("battle_cats_bot_prefs", Context.MODE_PRIVATE)

    var point1XRatio: Float
        get() = prefs.getFloat("p1_x_ratio", 0.30f)
        set(value) = prefs.edit().putFloat("p1_x_ratio", value.coerceIn(0.02f, 0.98f)).apply()

    var point1YRatio: Float
        get() = prefs.getFloat("p1_y_ratio", 0.88f)
        set(value) = prefs.edit().putFloat("p1_y_ratio", value.coerceIn(0.02f, 0.98f)).apply()

    var point2XRatio: Float
        get() = prefs.getFloat("p2_x_ratio", 0.40f)
        set(value) = prefs.edit().putFloat("p2_x_ratio", value.coerceIn(0.02f, 0.98f)).apply()

    var point2YRatio: Float
        get() = prefs.getFloat("p2_y_ratio", 0.88f)
        set(value) = prefs.edit().putFloat("p2_y_ratio", value.coerceIn(0.02f, 0.98f)).apply()

    var battleIntervalMs: Long
        get() = prefs.getLong("battle_interval_ms", 200L)
        set(value) = prefs.edit().putLong("battle_interval_ms", value.coerceIn(50L, 5000L)).apply()

    var rewardWaitMs: Long
        get() = prefs.getLong("reward_wait_ms", 500L)
        set(value) = prefs.edit().putLong("reward_wait_ms", value.coerceIn(0L, 5000L)).apply()

    var postClickCooldownMs: Long
        get() = prefs.getLong("post_click_cooldown_ms", 600L)
        set(value) = prefs.edit().putLong("post_click_cooldown_ms", value.coerceIn(100L, 5000L)).apply()

    var matchThreshold: Float
        get() = prefs.getFloat("match_threshold", 0.74f)
        set(value) = prefs.edit().putFloat("match_threshold", value.coerceIn(0.50f, 0.98f)).apply()

    var brightnessRatioMin: Float
        get() = prefs.getFloat("brightness_ratio_min", 0.70f)
        set(value) = prefs.edit().putFloat("brightness_ratio_min", value.coerceIn(0.30f, 0.95f)).apply()

    var jitterPx: Int
        get() = prefs.getInt("jitter_px", 4)
        set(value) = prefs.edit().putInt("jitter_px", value.coerceIn(0, 20)).apply()
}
