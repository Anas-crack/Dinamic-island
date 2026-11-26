package com.dynamicisland.redminote8

import android.content.Context
import android.graphics.Color

data class IslandSettings(
    val enabled: Boolean = false,
    val cutoutType: String = "notch",
    val coverStatusBar: Boolean = true,
    val autoHideEnabled: Boolean = true,
    val autoHideSeconds: Int = 30,
    val notchGapDp: Int = 46,
    val barHeightDp: Int = 42,
    val topOffsetDp: Int = 0,
    val compactWidthDp: Int = 300,
    val cornerRadiusDp: Int = 24,
    val themeId: String = "black",
    val userName: String = "أنس محمد",
    val avatarUri: String? = null,
    val use24Hour: Boolean = false,
    val showStatusIcons: Boolean = true,
    val githubRepo: String = "",
    val autoCheckUpdate: Boolean = true
)

object IslandConfig {
    private const val PREF_NAME = "dynamic_island_prefs_v5"

    fun load(context: Context): IslandSettings {
        val p = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return IslandSettings(
            enabled = p.getBoolean("enabled", false),
            cutoutType = p.getString("cutoutType", "notch") ?: "notch",
            coverStatusBar = p.getBoolean("coverStatusBar", true),
            autoHideEnabled = p.getBoolean("autoHideEnabled", true),
            autoHideSeconds = p.getInt("autoHideSeconds", 30),
            notchGapDp = p.getInt("notchGapDp", 46),
            barHeightDp = p.getInt("barHeightDp", 42),
            topOffsetDp = p.getInt("topOffsetDp", 0),
            compactWidthDp = p.getInt("compactWidthDp", 300),
            cornerRadiusDp = p.getInt("cornerRadiusDp", 24),
            themeId = p.getString("themeId", "black") ?: "black",
            userName = p.getString("userName", "أنس محمد") ?: "أنس محمد",
            avatarUri = p.getString("avatarUri", null),
            use24Hour = p.getBoolean("use24Hour", false),
            showStatusIcons = p.getBoolean("showStatusIcons", true),
            githubRepo = p.getString("githubRepo", "") ?: "",
            autoCheckUpdate = p.getBoolean("autoCheckUpdate", true)
        )
    }

    fun save(context: Context, s: IslandSettings) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", s.enabled)
            .putString("cutoutType", s.cutoutType)
            .putBoolean("coverStatusBar", s.coverStatusBar)
            .putBoolean("autoHideEnabled", s.autoHideEnabled)
            .putInt("autoHideSeconds", s.autoHideSeconds)
            .putInt("notchGapDp", s.notchGapDp)
            .putInt("barHeightDp", s.barHeightDp)
            .putInt("topOffsetDp", s.topOffsetDp)
            .putInt("compactWidthDp", s.compactWidthDp)
            .putInt("cornerRadiusDp", s.cornerRadiusDp)
            .putString("themeId", s.themeId)
            .putString("userName", s.userName)
            .putString("avatarUri", s.avatarUri)
            .putBoolean("use24Hour", s.use24Hour)
            .putBoolean("showStatusIcons", s.showStatusIcons)
            .putString("githubRepo", s.githubRepo)
            .putBoolean("autoCheckUpdate", s.autoCheckUpdate)
            .apply()
    }

    fun themeSolidColor(themeId: String): Int {
        return when (themeId) {
            "charcoal" -> Color.rgb(18, 22, 30)
            "navy" -> Color.rgb(8, 18, 38)
            "light" -> Color.rgb(245, 247, 250)
            else -> Color.BLACK
        }
    }

    fun isLightTheme(themeId: String): Boolean = themeId == "light"
}
