package com.tradelens.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tradelens.analysis.Signal
import com.tradelens.analysis.Verdict
import com.tradelens.ui.theme.LocalMarketColors
import com.tradelens.ui.theme.Num
import java.util.Locale

object Fmt {
    fun currencySymbol(currency: String) = when (currency) {
        "INR" -> "₹"; "USD" -> "$"; "EUR" -> "€"; "GBP" -> "£"; else -> "$currency "
    }
    fun num(v: Double, decimals: Int = 2): String = String.format(Locale.US, "%,.${decimals}f", v)
    fun price(v: Double, currency: String = "INR", index: Boolean = false) =
        if (index) num(v) else currencySymbol(currency) + num(v)
    fun signed(v: Double) = (if (v >= 0) "+" else "") + num(v)
    fun pct(v: Double) = (if (v >= 0) "+" else "") + String.format(Locale.US, "%.2f%%", v)
    fun volume(v: Long): String = when {
        v >= 10_000_000 -> String.format(Locale.US, "%.2f Cr", v / 1e7)
        v >= 100_000 -> String.format(Locale.US, "%.2f L", v / 1e5)
        v >= 1_000 -> String.format(Locale.US, "%.1f K", v / 1e3)
        else -> v.toString()
    }
    fun ticker(symbol: String) = symbol.removeSuffix(".NS").removeSuffix(".BO")
}

/** ViewModel with constructor args, without a DI framework. */
@Composable
inline fun <reified VM : ViewModel> rememberVm(key: String? = null, crossinline create: () -> VM): VM =
    viewModel(key = key, factory = viewModelFactory { initializer { create() } })

@Composable
fun changeColor(v: Double): Color {
    val mc = LocalMarketColors.current
    return when {
        v > 0 -> mc.gain
        v < 0 -> mc.loss
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
fun verdictColor(v: Verdict): Color {
    val mc = LocalMarketColors.current
    return when (v) {
        Verdict.BULLISH -> mc.gain
        Verdict.BEARISH -> mc.loss
        Verdict.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
fun signalColor(s: Signal): Color {
    val mc = LocalMarketColors.current
    return when (s) {
        Signal.STRONG_BUY, Signal.BUY -> mc.gain
        Signal.SELL, Signal.STRONG_SELL -> mc.loss
        Signal.HOLD -> mc.neutral
    }
}

@Composable
fun ChangeText(change: Double, pct: Double, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    Text(
        "${Fmt.signed(change)} (${Fmt.pct(pct)})",
        color = changeColor(change),
        style = style.merge(Num),
        modifier = modifier,
    )
}

@Composable
fun SignalPill(signal: Signal, modifier: Modifier = Modifier) {
    val color = signalColor(signal)
    Surface(color = color.copy(alpha = 0.14f), contentColor = color, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            signal.label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Score from −100 to +100 shown as a red→amber→green track with a marker. */
@Composable
fun ScoreGauge(score: Int, modifier: Modifier = Modifier) {
    val mc = LocalMarketColors.current
    val track = MaterialTheme.colorScheme.onSurface
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val h = 8.dp.toPx()
            val top = (size.height - h) / 2
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(mc.loss, mc.neutral, mc.gain)),
                topLeft = Offset(0f, top), size = Size(size.width, h), cornerRadius = CornerRadius(h / 2),
            )
            val x = ((score + 100) / 200f) * size.width
            drawCircle(Color.White, radius = size.height / 2, center = Offset(x.coerceIn(size.height / 2, size.width - size.height / 2), size.height / 2))
            drawCircle(track, radius = size.height / 2 - 3.dp.toPx(), center = Offset(x.coerceIn(size.height / 2, size.width - size.height / 2), size.height / 2))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("Bearish", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("Bullish", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
fun KeyValue(label: String, value: String, valueColor: Color = Color.Unspecified, emphasize: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            value,
            style = (if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium).merge(Num),
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Medium,
            color = valueColor,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
fun CenterMessage(text: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun QuoteRow(
    title: String,
    subtitle: String,
    price: String,
    change: Double,
    pct: Double,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(price, style = MaterialTheme.typography.titleSmall.merge(Num), fontWeight = FontWeight.SemiBold)
            ChangeText(change, pct, style = MaterialTheme.typography.bodySmall)
        }
        trailing?.invoke()
    }
}
