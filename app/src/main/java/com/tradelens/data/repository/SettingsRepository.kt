package com.tradelens.data.repository

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserSettings(
    val capital: Double = 100_000.0,     // trading capital used for position sizing
    val riskPercent: Double = 1.0,       // % of capital you're willing to lose per trade
    val indiaOnlySearch: Boolean = true,
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private fun load() = UserSettings(
        capital = prefs.getString("capital", null)?.toDoubleOrNull() ?: 100_000.0,
        riskPercent = prefs.getString("risk", null)?.toDoubleOrNull() ?: 1.0,
        indiaOnlySearch = prefs.getBoolean("india_only", true),
    )

    fun update(s: UserSettings) {
        prefs.edit()
            .putString("capital", s.capital.toString())
            .putString("risk", s.riskPercent.toString())
            .putBoolean("india_only", s.indiaOnlySearch)
            .apply()
        _settings.value = s
    }
}
