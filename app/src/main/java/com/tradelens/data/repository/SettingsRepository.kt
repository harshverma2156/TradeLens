package com.tradelens.data.repository

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserSettings(
    val capital: Double = 100_000.0,     // trading capital used for position sizing
    val riskPercent: Double = 1.0,       // % of capital you're willing to lose per trade
    val maxPositionPercent: Double = 20.0, // most of the capital one position may use (exposure cap)
    val indiaOnlySearch: Boolean = true,
    /** Phase 8: buy only this far below fair value (25 = 25%). Drives the accumulation zones. */
    val marginOfSafetyPercent: Double = 25.0,
    /** Synthetic prices for offline demos. Always shown with a DEMO badge; never real market data. */
    val demoMode: Boolean = false,
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private fun load() = UserSettings(
        capital = prefs.getString("capital", null)?.toDoubleOrNull() ?: 100_000.0,
        riskPercent = prefs.getString("risk", null)?.toDoubleOrNull() ?: 1.0,
        maxPositionPercent = prefs.getString("max_position", null)?.toDoubleOrNull() ?: 20.0,
        indiaOnlySearch = prefs.getBoolean("india_only", true),
        marginOfSafetyPercent = prefs.getString("margin_of_safety", null)?.toDoubleOrNull() ?: 25.0,
        demoMode = prefs.getBoolean("demo_mode", false),
    )

    fun update(s: UserSettings) {
        prefs.edit()
            .putString("capital", s.capital.toString())
            .putString("risk", s.riskPercent.toString())
            .putString("max_position", s.maxPositionPercent.toString())
            .putBoolean("india_only", s.indiaOnlySearch)
            .putString("margin_of_safety", s.marginOfSafetyPercent.toString())
            .putBoolean("demo_mode", s.demoMode)
            .apply()
        _settings.value = s
    }
}
