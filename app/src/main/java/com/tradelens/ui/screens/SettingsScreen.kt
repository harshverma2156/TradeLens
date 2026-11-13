package com.tradelens.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradelens.AppContainer
import com.tradelens.ui.components.Fmt
import com.tradelens.ui.components.KeyValue
import com.tradelens.ui.components.Section

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val current by container.settings.settings.collectAsStateWithLifecycle()
    var capital by remember { mutableStateOf(current.capital.toLong().toString()) }
    var risk by remember { mutableStateOf(current.riskPercent.toString()) }
    var maxPos by remember { mutableStateOf(current.maxPositionPercent.toString()) }
    var marginSafety by remember { mutableStateOf(current.marginOfSafetyPercent.toString()) }
    var indiaOnly by remember { mutableStateOf(current.indiaOnlySearch) }
    var demo by remember { mutableStateOf(current.demoMode) }
    val capitalV = capital.toDoubleOrNull()
    val riskV = risk.toDoubleOrNull()
    val maxPosV = maxPos.toDoubleOrNull()
    val marginV = marginSafety.toDoubleOrNull()
    val valid = capitalV != null && capitalV > 0 && riskV != null && riskV > 0 && riskV <= 10 && maxPosV != null && maxPosV > 0 && maxPosV <= 100 &&
        marginV != null && marginV >= 0 && marginV <= 60
    var saved by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Section("Position sizing") {
                OutlinedTextField(
                    value = capital,
                    onValueChange = { capital = it.filter(Char::isDigit).take(12); saved = false },
                    label = { Text("Trading capital (₹)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = risk,
                    onValueChange = { v -> risk = v.filter { it.isDigit() || it == '.' }.take(5); saved = false },
                    label = { Text("Risk per trade (% of capital)") },
                    singleLine = true,
                    isError = riskV != null && (riskV <= 0 || riskV > 10),
                    supportingText = { Text("Most traders risk 0.5–2%. Allowed range: up to 10%.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = maxPos,
                    onValueChange = { v -> maxPos = v.filter { it.isDigit() || it == '.' }.take(5); saved = false },
                    label = { Text("Max position size (% of capital)") },
                    singleLine = true,
                    isError = maxPosV != null && (maxPosV <= 0 || maxPosV > 100),
                    supportingText = { Text("Caps how much one stock can take, even when the stop-loss is tight. Allowed range: up to 100%.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = marginSafety,
                    onValueChange = { v -> marginSafety = v.filter { it.isDigit() || it == '.' }.take(4); saved = false },
                    label = { Text("Margin of safety (% below fair value)") },
                    singleLine = true,
                    isError = marginV != null && (marginV < 0 || marginV > 60),
                    supportingText = { Text("The discount you want before buying. Valuation zones and the buy-below price use it. Allowed range: 0–60%.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (valid) {
                    Text(
                        "If a stop-loss is hit you lose at most ${Fmt.price(capitalV!! * riskV!! / 100)} on that trade, and no position uses more than ${Fmt.price(capitalV * maxPosV!! / 100)}. Suggested quantities are calculated from this.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Section("Search") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show only NSE and BSE stocks", modifier = Modifier.weight(1f))
                    Switch(checked = indiaOnly, onCheckedChange = { indiaOnly = it; saved = false })
                }
            }
            Section("Market data") {
                val info = container.market.source
                KeyValue("Provider", info.name)
                KeyValue("Quotes", if (info.realtime) "Real-time" else info.mode.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
                val tfs = com.tradelens.marketdata.Timeframe.entries.filter { info.supports(it) }.joinToString(" ") { it.label }
                KeyValue("Intervals", tfs.ifBlank { "none" })
                KeyValue("Corporate actions", if (info.capabilities.corporateActions) "Yes" else "No")
                KeyValue("Licensed for redistribution", if (info.licensedForDisplay) "Yes" else "No")
                Text(info.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Demo data mode")
                        Text("Synthetic prices for offline demos. Clearly marked DEMO; alerts are paused.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = demo, onCheckedChange = { demo = it; saved = false })
                }
            }
            Button(
                onClick = {
                    container.settings.update(current.copy(capital = capitalV!!, riskPercent = riskV!!, maxPositionPercent = maxPosV!!, indiaOnlySearch = indiaOnly, marginOfSafetyPercent = marginV!!, demoMode = demo))
                    saved = true
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text(if (saved) "Saved" else "Save settings") }
        }
    }
}
