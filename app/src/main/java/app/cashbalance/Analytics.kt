@file:OptIn(ExperimentalLayoutApi::class)

package app.cashbalance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun Analytics(d: Data) {
    val cs = MaterialTheme.colorScheme
    var month by remember { mutableStateOf<YearMonth?>(YearMonth.now()) }
    val inPeriod = d.shownTxs().filter { t -> month.let { it == null || YearMonth.from(t.localDate()) == it } }
    val spends = inPeriod.filter { it.type == Type.SPEND }
    fun base(t: Tx) = d.toBase(t.amount, t.currency) ?: 0.0
    val byCategory = spends.groupBy { it.category }
        .map { (c, ts) -> c to ts.groupBy { it.account }.mapValues { (_, l) -> l.sumOf(::base) } }
        .sortedByDescending { it.second.values.sum() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = (month ?: YearMonth.now()).minusMonths(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous")
            }
            Text(
                month?.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.ENGLISH)) ?: "All time",
                Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = { month = (month ?: YearMonth.now()).plusMonths(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = month == YearMonth.now(), onClick = { month = YearMonth.now() }, label = { Text("This month") })
            FilterChip(selected = month == null, onClick = { month = null }, label = { Text("All time") })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Spent, ${d.base}", money(spends.sumOf(::base)), cs.onSurface, Modifier.weight(1f))
            Stat("Added, ${d.base}", money(inPeriod.filter { it.type == Type.INCOME }.sumOf(::base)), cs.primary, Modifier.weight(1f))
        }

        if (spends.isEmpty()) {
            Text("No spending in this period", Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center, color = cs.onSurfaceVariant)
        } else {
            Text("By category", style = MaterialTheme.typography.titleSmall, color = cs.primary)
            val catNames = d.categories.associate { it.id to it.name }
            val colors = d.accounts.associate { it.id to it.color }
            val max = byCategory.first().second.values.sum().coerceAtLeast(0.01)
            byCategory.forEach { (catId, split) ->
                val total = split.values.sum()
                Column {
                    Row {
                        Text(catNames[catId] ?: "No category", Modifier.weight(1f))
                        Text(money(total), fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Bar(split.map { (a, v) -> Color(colors[a] ?: PALETTE[0]) to v }, (total / max).toFloat())
                }
            }
            Text("Bars are split by account color. Other currencies are converted at today's rate.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }

        ExchangeRates(d, inPeriod)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ExchangeRates(d: Data, txs: List<Tx>) {
    val cs = MaterialTheme.colorScheme
    Text("Exchange rates", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, color = cs.primary)
    val pairs = txs.filter { it.type == Type.EXCHANGE }.map { setOf(it.currency, it.toCurrency).sorted() }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    if (pairs.isEmpty()) {
        Text("No exchanges in this period", color = cs.onSurfaceVariant)
        return
    }
    var picked by remember { mutableStateOf(pairs.first()) }
    var flip by remember { mutableStateOf(false) }
    val pair = picked.takeIf { it in pairs } ?: pairs.first()

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        pairs.forEach { p -> FilterChip(selected = p == pair, onClick = { picked = p; flip = false }, label = { Text("${p[0]} ⇄ ${p[1]}") }) }
    }
    // orient as "1 X = r Y" with r >= 1 (1 USD = 95 RUB, not 0.0105), the swap button flips it
    val (b0, s0) = pairStats(txs, pair[0], pair[1])
    val natural = ((b0 + s0).rate ?: 1.0) >= 1
    val (x, y) = if (natural != flip) pair[0] to pair[1] else pair[1] to pair[0]
    val (bought, sold) = pairStats(txs, x, y)
    val all = bought + sold

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("1 $x = ? $y", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { flip = !flip }) { Icon(Icons.Filled.SwapHoriz, "Swap") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Stat("Average", all.rate?.let(::rateStr) ?: "—", cs.primary, Modifier.weight(1f))
        Stat("Online now", d.rate(x, y)?.let(::rateStr) ?: "—", cs.onSurfaceVariant, Modifier.weight(1f))
    }
    RateRow("Bought $x", bought, x, y)
    RateRow("Sold $x", sold, x, y)
    Text(
        "Average is weighted by amount: total $y ÷ total $x over all exchanges of this pair in the period.",
        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
    )
}

@Composable
private fun RateRow(title: String, s: RateStat, x: String, y: String) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainer).padding(16.dp)) {
        Row {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(s.rate?.let(::rateStr) ?: "—", fontWeight = FontWeight.SemiBold, color = cs.primary)
        }
        Text(
            if (s.count == 0) "No exchanges"
            else "${s.count} exchange${if (s.count > 1) "s" else ""} · ${money(s.xAmount)} $x for ${money(s.yAmount)} $y",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.Bold)
    }
}

/** Horizontal bar of [fraction] width, split into colored segments proportional to their values. */
@Composable
private fun Bar(parts: List<Pair<Color, Double>>, fraction: Float) {
    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.fillMaxWidth(fraction.coerceIn(0.01f, 1f)).fillMaxHeight()) {
            parts.filter { it.second > 0 }.forEach { (c, v) -> Box(Modifier.weight(v.toFloat()).fillMaxHeight().background(c)) }
        }
    }
}
