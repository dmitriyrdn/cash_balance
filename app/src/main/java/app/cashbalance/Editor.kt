@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package app.cashbalance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal

private fun exprOf(v: BigDecimal) = if (v.signum() == 0) "" else v.stripTrailingZeros().toPlainString()

@Composable
fun Editor(
    d: Data,
    tx: Tx,
    onClose: () -> Unit,
    onSave: (Tx) -> Unit,
    onDelete: () -> Unit,
    onAddCategory: (String) -> Long,
) {
    BackHandler(onBack = onClose)
    val cs = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    var type by remember { mutableStateOf(tx.type) }
    var account by remember { mutableLongStateOf(tx.account) }
    var toAccount by remember { mutableLongStateOf(tx.toAccount) }
    var expr by remember { mutableStateOf(exprOf(tx.amount)) }
    var toExpr by remember { mutableStateOf(exprOf(tx.toAmount)) }
    var active by remember { mutableIntStateOf(0) } // which amount the keypad types into
    var person by remember { mutableStateOf(tx.person) }
    var cat by remember { mutableLongStateOf(tx.category) }
    var time by remember { mutableLongStateOf(tx.time) }
    var note by remember { mutableStateOf(tx.note) }
    var typing by remember { mutableStateOf(setOf<String>()) } // focused text fields hide the keypad
    var pickDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf(false) }

    // balances as if this entry didn't exist, so editing it can reuse the money it already took
    val others = remember(d, tx.id) { d.copy(txs = d.txs.filter { it.id != tx.id }) }
    val bal = remember(others) { others.balances() }
    val loans = remember(others) { others.loans() }
    // hidden accounts aren't offered, except the ones this entry already uses
    val pickable = d.accounts.filter { !it.hidden || it.id == tx.account || it.id == tx.toAccount }
    val acc = pickable.firstOrNull { it.id == account } ?: pickable.first()
    val currency = acc.currency
    // Move stays in one currency, Exchange goes to an account in another one
    val targets = when (type) {
        Type.MOVE -> pickable.filter { it.id != acc.id && it.currency == currency }
        Type.EXCHANGE -> pickable.filter { it.currency != currency }
        else -> emptyList()
    }
    LaunchedEffect(targets) { if (targets.none { it.id == toAccount }) toAccount = targets.firstOrNull()?.id ?: 0L }
    val to = targets.firstOrNull { it.id == toAccount }
    val toCurrency = to?.currency.orEmpty()
    val owed = loans[person.trim()]?.get(currency) ?: BigDecimal.ZERO
    val amount = calc(expr)
    val toAmount = calc(toExpr)

    val error = when {
        amount == null || amount.signum() <= 0 -> ""
        (type == Type.MOVE || type == Type.EXCHANGE) && to == null -> "Pick an account to put it in"
        type == Type.EXCHANGE && (toAmount == null || toAmount.signum() <= 0) -> "Enter how much you got"
        (type == Type.LEND || type == Type.REPAY) && person.isBlank() -> "Enter a name"
        type == Type.REPAY && amount > owed -> "${person.trim()} owes ${money(owed)} $currency"
        else -> null
    }

    fun build() = Tx(
        id = tx.id,
        time = time,
        type = type,
        account = acc.id,
        currency = currency,
        amount = amount!!,
        toAccount = if (type == Type.MOVE || type == Type.EXCHANGE) to!!.id else 0,
        toCurrency = if (type == Type.EXCHANGE) toCurrency else "",
        toAmount = if (type == Type.EXCHANGE) toAmount!! else BigDecimal.ZERO,
        person = if (type == Type.LEND || type == Type.REPAY) person.trim() else "",
        note = note.trim(),
        category = if (type == Type.SPEND) cat else 0,
    )

    Column(Modifier.fillMaxSize().background(cs.background).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
            Text(if (tx.id == 0L) "New entry" else "Entry", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if (tx.id != 0L) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Type.entries.forEach { t ->
                FilterChip(selected = type == t, onClick = { type = t; active = 0 }, label = { Text("${t.icon} ${t.label}") })
            }
        }

        val color = when (type) {
            Type.INCOME, Type.REPAY -> cs.primary
            Type.MOVE -> cs.secondary
            else -> cs.onSurface
        }
        if (type == Type.EXCHANGE) {
            AmountBox("You give", expr, currency, active == 0, cs.onSurface) { focus.clearFocus(); active = 0 }
            AmountBox("You get", toExpr, toCurrency, active == 1, cs.primary) { focus.clearFocus(); active = 1 }
            val rateLine = if (amount != null && toAmount != null && amount.signum() > 0 && toAmount.signum() > 0 && toCurrency.length == 3) {
                rateText(currency, toCurrency, toAmount.toDouble() / amount.toDouble())
            } else {
                "Rate is calculated from the two amounts"
            }
            val online = d.rate(currency, toCurrency)?.let { "  ·  online ${rateText(currency, toCurrency, it)}" }.orEmpty()
            Text(rateLine + online, Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            AmountBox(null, expr, currency, true, color) { focus.clearFocus(); active = 0 }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (type == Type.LEND || type == Type.REPAY) {
                FieldLabel(if (type == Type.LEND) "To whom" else "Who repaid")
                OutlinedTextField(
                    person, { person = it },
                    Modifier.fillMaxWidth().onFocusChanged { typing = if (it.isFocused) typing + "person" else typing - "person" },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                val known = (loans.keys + d.txs.filter { it.type == Type.LEND }.map { it.person }).distinct()
                if (known.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    known.forEach { p -> FilterChip(selected = person.trim() == p, onClick = { person = p }, label = { Text(p) }) }
                }
            }

            FieldLabel(when (type) { Type.MOVE, Type.EXCHANGE -> "From"; Type.INCOME, Type.REPAY -> "Into account"; else -> "From account" })
            AccountChips(pickable, bal, acc.id) { account = it }
            if (type == Type.MOVE || type == Type.EXCHANGE) {
                FieldLabel("To")
                if (targets.isEmpty()) {
                    Text(
                        if (type == Type.MOVE) "No other $currency account — add one in Settings"
                        else "No account in another currency — add one in Settings",
                        color = cs.error,
                    )
                }
                AccountChips(targets, bal, toAccount) { toAccount = it }
            }

            if (type == Type.SPEND) {
                FieldLabel("Category")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.categories.forEach { c ->
                        FilterChip(selected = cat == c.id, onClick = { cat = if (cat == c.id) 0 else c.id }, label = { Text(c.name) })
                    }
                    AssistChip(onClick = { newCategory = true }, label = { Text("New") }, leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) })
                }
            }

            AssistChip(
                onClick = { pickDate = true },
                label = { Text(dayLabel(tx.copy(time = time).localDate())) },
                leadingIcon = { Icon(Icons.Filled.DateRange, null, Modifier.size(18.dp)) },
            )
            OutlinedTextField(
                note, { note = it },
                Modifier.fillMaxWidth().onFocusChanged { typing = if (it.isFocused) typing + "note" else typing - "note" },
                label = { Text("Note") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Spacer(Modifier.height(4.dp))
        }

        if (typing.isEmpty()) Keypad(
            onKey = { k -> if (active == 0) expr = pressKey(expr, k) else toExpr = pressKey(toExpr, k) },
            onClear = { if (active == 0) expr = "" else toExpr = "" },
        )
        if (!error.isNullOrEmpty()) Text(error, Modifier.padding(start = 20.dp, top = 8.dp), color = cs.error, style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = { onSave(build()) },
            enabled = error == null,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
        ) { Text("Save", style = MaterialTheme.typography.titleMedium) }
    }

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = toUtcMidnight(time))
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { time = withDate(time, it) }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this entry?") },
            text = { Text("Balances are recalculated as if it never happened.") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Delete", color = cs.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    if (newCategory) {
        EditDialog(
            title = "New category",
            initial = "",
            hint = "Can start with an emoji: 🍕 Pizza",
            onDismiss = { newCategory = false },
            onDelete = null,
            onSave = { name, _ -> cat = onAddCategory(name); newCategory = false },
        )
    }
}

/** Amount display; the keypad types into it. Shows the result once the expression has operators. */
@Composable
private fun AmountBox(label: String?, expr: String, cur: String, active: Boolean, color: Color, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .border(1.dp, if (active && label != null) cs.primary else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label != null) Text(label, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            val shown = expr.ifEmpty { "0" }
            val big = if (label == null) 44.sp else 32.sp
            Row(verticalAlignment = Alignment.Bottom) {
                Text(shown, fontSize = if (shown.length > 12) big * 0.7f else big, fontWeight = FontWeight.Bold, maxLines = 1, color = color)
                Text(" ${cur.ifEmpty { "…" }}", Modifier.padding(bottom = 6.dp), color = cs.onSurfaceVariant, fontSize = 18.sp)
            }
            if (hasOps(expr)) Text(calc(expr)?.let { "= " + money(it) } ?: "…", color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AccountChips(accounts: List<Account>, bal: Map<Long, Map<String, BigDecimal>>, selected: Long, onClick: (Long) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        accounts.forEach { a ->
            val c = Color(a.color)
            FilterChip(
                selected = a.id == selected,
                onClick = { onClick(a.id) },
                label = { Text("${a.name} · ${money(bal.of(a))} ${a.currency}") },
                leadingIcon = { Dot(a.color) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.copy(alpha = 0.3f), selectedLabelColor = Color.White),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true, selected = a.id == selected, selectedBorderColor = c, selectedBorderWidth = 1.dp,
                ),
            )
        }
    }
}

@Composable
private fun Keypad(onKey: (Char) -> Unit, onClear: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 12.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("789÷", "456×", "123−", ".0⌫+").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { k ->
                    val op = !k.isDigit() && k != '.'
                    Box(
                        Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (op) cs.surfaceContainerHighest else cs.surfaceContainerHigh)
                            .combinedClickable(onLongClick = { if (k == '⌫') onClear() }, onClick = { onKey(k) }),
                        contentAlignment = Alignment.Center,
                    ) { Text(k.toString(), fontSize = 22.sp, color = if (op) cs.primary else cs.onSurface) }
                }
            }
        }
    }
}
