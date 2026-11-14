package app.cashbalance

import android.content.Context
import android.os.Bundle
import android.text.format.DateUtils
import android.util.AtomicFile
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.math.MathContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

private val Colors = darkColorScheme(
    primary = Color(0xFF3DDC84), onPrimary = Color(0xFF003919),
    primaryContainer = Color(0xFF1E6B45), onPrimaryContainer = Color(0xFFB9F6D2),
    secondary = Color(0xFF9CD4B4), onSecondary = Color(0xFF06371F),
    secondaryContainer = Color(0xFF234D38), onSecondaryContainer = Color(0xFFCDEFD9),
    background = Color(0xFF0E1411), onBackground = Color(0xFFE1EAE4),
    surface = Color(0xFF0E1411), onSurface = Color(0xFFE1EAE4),
    surfaceVariant = Color(0xFF24322B), onSurfaceVariant = Color(0xFF9DB0A5),
    surfaceContainerLowest = Color(0xFF0A0F0C), surfaceContainerLow = Color(0xFF121A16),
    surfaceContainer = Color(0xFF16201B), surfaceContainerHigh = Color(0xFF1C2822),
    surfaceContainerHighest = Color(0xFF24322B),
    outline = Color(0xFF4A5E53), outlineVariant = Color(0xFF2C3A33),
    error = Color(0xFFFF8A80),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(bars, bars)
        Store.init(this)
        setContent {
            MaterialTheme(colorScheme = Colors) {
                Surface(color = MaterialTheme.colorScheme.background) { App() }
            }
        }
    }
}

object Store {
    private lateinit var file: AtomicFile
    var data by mutableStateOf(Data())
        private set

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        file = AtomicFile(File(ctx.filesDir, "data.json"))
        if (!file.baseFile.exists()) {
            data = Data(accounts = listOf(Account(1, "Wallet")))
            return
        }
        val bytes = file.readFully()
        data = try {
            Data.fromJson(JSONObject(String(bytes)))
        } catch (e: Exception) {
            // keep the unreadable file around instead of silently overwriting it on the next save
            File(ctx.filesDir, "data-broken-${System.currentTimeMillis()}.json").writeBytes(bytes)
            Data(accounts = listOf(Account(1, "Wallet")))
        }
    }

    // ponytail: rewrites the whole file on the main thread per change; fine for thousands of entries, move to IO if it ever lags
    fun update(f: (Data) -> Data) {
        val updated = f(data)
        val out = file.startWrite()
        try {
            out.write(updated.toJson().toString().toByteArray())
            file.finishWrite(out)
            data = updated
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }
}

private suspend fun fetchRates(): Pair<Map<String, Double>, Long> = withContext(Dispatchers.IO) {
    val c = URL("https://open.er-api.com/v6/latest/USD").openConnection() as HttpURLConnection
    c.connectTimeout = 10_000
    c.readTimeout = 10_000
    val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    val r = j.getJSONObject("rates")
    r.keys().asSequence().associateWith { r.getDouble(it) } to j.getLong("time_last_update_unix") * 1000
}

fun money(v: BigDecimal, plus: Boolean = false): String =
    (if (v.signum() < 0) "−" else if (plus && v.signum() > 0) "+" else "") + "%,.2f".format(Locale.US, v.abs())

fun money(v: Double, plus: Boolean = false) = money(BigDecimal(v), plus)
fun rateStr(r: Double): String = BigDecimal(r).round(MathContext(6)).stripTrailingZeros().toPlainString()

/** "1 A = r B", flipped so the number is >= 1. */
fun rateText(a: String, b: String, r: Double) = if (r >= 1) "1 $a = ${rateStr(r)} $b" else "1 $b = ${rateStr(1 / r)} $a"

fun currencyName(code: String): String = runCatching { Currency.getInstance(code).getDisplayName(Locale.ENGLISH) }.getOrDefault("")
fun Data.sumBase(m: Map<String, BigDecimal>) = m.entries.sumOf { toBase(it.value, it.key) ?: 0.0 }

/** Currencies this ledger has ever touched, base first. */
fun Data.knownCurrencies() = (listOf(base) + txs.flatMap { listOf(it.currency, it.toCurrency) }).filter { it.length == 3 }.distinct()

fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "EEEE, MMMM d" else "EEEE, MMMM d, yyyy", Locale.ENGLISH))
    }
}

/** Emoji prefix of a category name like "🍕 Pizza", if any. */
fun iconOf(name: String): String? = name.substringBefore(' ').takeIf { it != name && it.none(Char::isLetterOrDigit) }
fun withoutIcon(name: String) = name.removePrefix(iconOf(name).orEmpty()).trim()

@Composable
fun App() {
    val d = Store.data
    val csvExport = rememberCsvExport(d)
    val csvImport = rememberCsvImport()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var historyAccount by rememberSaveable { mutableStateOf<Long?>(null) }
    val screenState = rememberSaveableStateHolder()
    // The editor removes the tab from composition. Keep its filters and scroll state here.
    val activeHistoryAccount = historyAccount?.takeIf { id -> d.visible.any { it.id == id } }
    LaunchedEffect(activeHistoryAccount) { historyAccount = activeHistoryAccount }
    var editing by remember { mutableStateOf<Tx?>(null) }
    var fabOpen by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    fun refresh() {
        if (loading) return
        loading = true
        scope.launch {
            runCatching { fetchRates() }
                .onSuccess { (r, t) -> Store.update { it.copy(rates = r, ratesTime = t) } }
                .onFailure { Toast.makeText(ctx, "Couldn't load rates", Toast.LENGTH_SHORT).show() }
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (System.currentTimeMillis() - d.ratesTime > 6 * 3600_000L) refresh() }

    editing?.let { tx ->
        Editor(
            d = d,
            tx = tx,
            onClose = { editing = null },
            onSave = { t -> Store.update { it.save(t) }; editing = null },
            onDelete = { Store.update { x -> x.copy(txs = x.txs.filter { it.id != tx.id }) }; editing = null },
            onAddCategory = { name ->
                val id = (Store.data.categories.maxOfOrNull { it.id } ?: 0) + 1
                Store.update { x -> x.copy(categories = x.categories + Category(id, name)) }
                id
            },
        )
        return
    }

    fun new(type: Type, person: String = "", currency: String = "") {
        editing = d.newEntry(type, if (tab == 0) activeHistoryAccount else null, person, currency)
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                listOf(
                    "History" to Icons.AutoMirrored.Filled.List,
                    "Balances" to Icons.Filled.AccountBalanceWallet,
                    "Analytics" to Icons.Filled.BarChart,
                    "Settings" to Icons.Filled.Settings,
                ).forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        },
        floatingActionButton = {
            if (tab < 2 && d.visible.isNotEmpty()) AddFab(fabOpen, { fabOpen = it }) { new(it) }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            screenState.SaveableStateProvider(tab) {
                when (tab) {
                    0 -> History(d, loading, activeHistoryAccount, onFilter = { historyAccount = it }, onRefresh = ::refresh, onOpen = { editing = it })
                    1 -> Balances(d, onRepay = { p, c -> new(Type.REPAY, p, c) })
                    2 -> Analytics(d, onOpen = { editing = it })
                    else -> Settings(d, loading, onRefresh = ::refresh, csvExport = csvExport, csvImport = csvImport)
                }
            }
            AnimatedVisibility(fabOpen && tab < 2, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { fabOpen = false })
            }
        }
    }
}

@Composable
fun AddFab(open: Boolean, setOpen: (Boolean) -> Unit, onPick: (Type) -> Unit) {
    val angle by animateFloatAsState(if (open) 45f else 0f, label = "fab")
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedVisibility(
            open,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(Type.LEND, Type.EXCHANGE, Type.MOVE, Type.INCOME, Type.SPEND).forEach { t ->
                    ExtendedFloatingActionButton(
                        onClick = { setOpen(false); onPick(t) },
                        modifier = Modifier.widthIn(min = 140.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) { Text("${t.icon}  ${t.label}", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
        FloatingActionButton(onClick = { setOpen(!open) }, containerColor = MaterialTheme.colorScheme.primary) {
            Icon(Icons.Filled.Add, "Add", Modifier.rotate(angle))
        }
    }
}

val Type.icon
    get() = when (this) {
        Type.SPEND -> "💸"
        Type.INCOME -> "💵"
        Type.MOVE -> "🔁"
        Type.EXCHANGE -> "💱"
        Type.LEND -> "🤝"
        Type.REPAY -> "💰"
    }

@Composable
fun Dot(color: Int, size: Int = 10) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color(color)))
}

/** Circle whose ring is split into the colors of the accounts involved. */
@Composable
fun Avatar(colors: List<Color>, icon: String) {
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(bg)
            val w = 3.dp.toPx()
            inset(w / 2) {
                val sweep = 360f / colors.size
                val gap = if (colors.size > 1) 6f else 0f
                colors.forEachIndexed { i, c ->
                    drawArc(c, -90f + i * sweep + gap / 2, sweep - gap, useCenter = false, style = Stroke(w))
                }
            }
        }
        Text(icon, fontSize = 18.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun History(d: Data, loading: Boolean, filter: Long?, onFilter: (Long?) -> Unit, onRefresh: () -> Unit, onOpen: (Tx) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val bal = remember(d.txs) { d.balances() }
    val loans = remember(d.txs) { d.loans() }
    val shownBal = d.visible.mapNotNull { bal[it.id] }
    val cash = shownBal.sumOf { d.sumBase(it) }
    val lent = loans.values.sumOf { d.sumBase(it) }
    val missing = (shownBal + loans.values).flatMap { it.keys }.filter { d.toBase(BigDecimal.ONE, it) == null }.distinct()
    val list = d.filteredTxs(accountId = filter)

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Column(
                Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF1E6B45), Color(0xFF0F3322))))
                    .padding(20.dp),
            ) {
                Text("Cash on hand", color = Color(0xFFB9F6D2), style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(money(cash), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Text(" ${d.base}", Modifier.padding(bottom = 6.dp), color = Color(0xFFB9F6D2), fontSize = 18.sp)
                }
                if (lent != 0.0) Text("+ ${money(lent)} lent  ·  ${money(cash + lent)} total", color = Color(0xFFB9F6D2), fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val label = if (d.ratesTime == 0L) "No exchange rates yet" else "Rates ${DateUtils.getRelativeTimeSpanString(d.ratesTime)}"
                    Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    IconButton(onRefresh, Modifier.size(32.dp)) {
                        if (loading) CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Refresh, "Refresh rates", Modifier.size(16.dp), tint = Color.White.copy(alpha = 0.6f))
                    }
                }
                if (missing.isNotEmpty()) Text("No rate for ${missing.joinToString()}, not counted", color = cs.error, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.visible.forEach { a ->
                        val sel = filter == a.id
                        Row(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(Color(a.color).copy(alpha = if (sel) 0.45f else 0.16f))
                                .border(1.dp, if (sel) Color(a.color) else Color.Transparent, RoundedCornerShape(50))
                                .clickable { onFilter(if (sel) null else a.id) }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Dot(a.color, 8)
                            Spacer(Modifier.width(6.dp))
                            Text(a.name, color = Color.White, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(6.dp))
                            Text("${money(bal.of(a))} ${a.currency}", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        if (list.isEmpty()) item {
            Text("No transactions yet — tap +", Modifier.fillMaxWidth().padding(32.dp), color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        transactionDays(d, list, filter, onOpen)
    }
}

/** Shared history/category rows, including stable keys for scroll restoration after edits. */
fun LazyListScope.transactionDays(d: Data, list: List<Tx>, filter: Long?, onOpen: (Tx) -> Unit) {
    list.groupBy { it.localDate() }.forEach { (day, txs) ->
        item(key = "d$day") {
            val cs = MaterialTheme.colorScheme
            val spent = txs.filter { it.type == Type.SPEND }.sumOf { d.toBase(it.amount, it.currency) ?: 0.0 }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                Text(dayLabel(day), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                if (spent > 0) Text("${money(-spent)} ${d.base}", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            }
        }
        items(txs, key = { it.id }) { t -> TxRow(t, d, filter) { onOpen(t) } }
    }
}

@Composable
fun TxRow(t: Tx, d: Data, filter: Long?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val acc = d.accounts.associateBy { it.id }
    val name = acc[t.account]?.name ?: "?"
    val category = d.categories.find { it.id == t.category }?.name
    val title = when (t.type) {
        Type.SPEND -> category?.let(::withoutIcon) ?: "Spent"
        Type.INCOME -> "Added"
        Type.MOVE -> "Moved"
        Type.EXCHANGE -> "Exchanged"
        Type.LEND -> "Lent to ${t.person}"
        Type.REPAY -> "${t.person} repaid"
    }
    val sub = when (t.type) {
        Type.MOVE -> "$name → ${acc[t.toAccount]?.name ?: "?"}"
        Type.EXCHANGE -> "$name${acc[t.toAccount]?.let { " → " + it.name }.orEmpty()} · ${rateText(t.currency, t.toCurrency, t.toAmount.toDouble() / t.amount.toDouble())}"
        else -> name
    }
    val sign = when (t.type) {
        Type.INCOME, Type.REPAY -> 1
        Type.MOVE -> when (filter) { t.account -> -1; t.toAccount -> 1; else -> 0 }
        else -> -1
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ring = listOfNotNull(acc[t.account], acc[t.toAccount]).map { Color(it.color) }
        Avatar(ring.ifEmpty { listOf(cs.outline) }, if (t.type == Type.SPEND) category?.let(::iconOf) ?: t.type.icon else t.type.icon)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOf(sub, t.note).filter { it.isNotBlank() }.joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (sign == 0) money(t.amount) else money(t.amount * sign.toBigDecimal(), plus = true)) + " ${t.currency}",
                color = when (sign) { 1 -> cs.primary; 0 -> cs.secondary; else -> cs.onSurface },
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge,
            )
            if (t.type == Type.EXCHANGE)
                Text("+${money(t.toAmount)} ${t.toCurrency}", color = cs.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Block(title: String, color: Int?, total: String, onClick: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            color?.let { Dot(it, 12); Spacer(Modifier.width(10.dp)) }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(total, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun AmountRow(cur: String, v: BigDecimal, d: Data) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(cur, Modifier.width(52.dp), color = cs.onSurfaceVariant)
        Text(money(v), Modifier.weight(1f), color = if (v.signum() < 0) cs.error else cs.onSurface)
        if (cur != d.base) d.toBase(v, cur)?.let { Text("≈ ${money(it)}", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun Balances(d: Data, onRepay: (String, String) -> Unit) {
    val bal = remember(d.txs) { d.balances() }
    val loans = remember(d.txs) { d.loans() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)) {
        item { SectionTitle("Accounts") }
        items(d.visible, key = { "a${it.id}" }) { a ->
            val v = bal.of(a)
            Block(a.name, a.color, "${money(v)} ${a.currency}", null) {
                if (a.currency != d.base) d.toBase(v, a.currency)?.let {
                    Text("≈ ${money(it)} ${d.base}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                // only entries from before one-currency accounts can leave other currencies here
                bal[a.id].orEmpty().filterKeys { it != a.currency }.forEach { (cur, x) -> AmountRow(cur, x, d) }
            }
        }
        item { SectionTitle("Lent") }
        if (loans.isEmpty()) item {
            Text("Nobody owes you cash", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(loans.entries.toList(), key = { "p${it.key}" }) { (person, m) ->
            Block(person, null, "${money(d.sumBase(m))} ${d.base}", { onRepay(person, m.keys.first()) }) {
                m.forEach { (cur, v) -> AmountRow(cur, v, d) }
                Text("Tap to record a repayment", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun Settings(d: Data, loading: Boolean, onRefresh: () -> Unit, csvExport: CsvExport, csvImport: () -> Unit) {
    var account by remember { mutableStateOf<Account?>(null) }
    var category by remember { mutableStateOf<Category?>(null) }
    var basePicker by remember { mutableStateOf(false) }
    var hiding by remember { mutableStateOf(false) }
    val bal = remember(d.txs) { d.balances() }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionTitle("Accounts") }
        items(d.accounts, key = { "a${it.id}" }) { a ->
            ListItem(
                headlineContent = { Text(a.name, color = if (a.hidden) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified) },
                supportingContent = if (a.hidden) ({ Text("Hidden") }) else null,
                leadingContent = { Dot(a.color, 18) },
                trailingContent = { Text("${money(bal.of(a))} ${a.currency}") },
                modifier = Modifier.clickable { account = a },
            )
        }
        item {
            TextButton(onClick = {
                val color = PALETTE.firstOrNull { c -> d.accounts.none { it.color == c } } ?: PALETTE[d.accounts.size % PALETTE.size]
                account = Account(0, "", color, d.base)
            }, Modifier.padding(horizontal = 8.dp)) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Add account") }
        }
        item {
            ListItem(
                headlineContent = { Text("Shown accounts") },
                supportingContent = { Text("Hidden ones are left out of the total, the main page and analytics. Their entries are kept.") },
                trailingContent = { Text("${d.visible.size} of ${d.accounts.size}", color = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { hiding = true },
            )
        }
        item { SectionTitle("Spending categories") }
        items(d.categories, key = { "c${it.id}" }) { c ->
            ListItem(headlineContent = { Text(c.name) }, modifier = Modifier.clickable { category = c })
        }
        item {
            TextButton(onClick = { category = Category(0, "") }, Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Add category")
            }
        }
        item { SectionTitle("Currency") }
        item {
            ListItem(
                headlineContent = { Text("Show totals in") },
                trailingContent = { Text(d.base, color = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { basePicker = true },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Refresh exchange rates") },
                supportingContent = {
                    Text(if (d.ratesTime == 0L) "Not loaded yet" else "Updated ${DateUtils.getRelativeTimeSpanString(d.ratesTime)} · open.er-api.com")
                },
                trailingContent = { if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
                modifier = Modifier.clickable(onClick = onRefresh),
            )
        }
        item {
            Text(
                "Everything is stored only on this phone. Online rates are used for totals only — your exchanges keep the rate you actually got.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { SectionTitle("Export / Import") }
        item { ListItem(headlineContent = { Text("Import CSV") }, supportingContent = { Text("Restore a ledger from a Cash Balance CSV") }, modifier = Modifier.clickable(onClick = csvImport)) }
        item {
            ListItem(
                headlineContent = { Text(if (csvExport.inProgress) "Exporting CSV…" else "Export CSV") },
                supportingContent = { Text("All transactions, including hidden accounts. Choose where to save the file.") },
                trailingContent = { if (csvExport.inProgress) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
                modifier = Modifier.clickable(enabled = !csvExport.inProgress, onClick = csvExport.launch),
            )
        }
    }

    account?.let { a ->
        val used = d.txs.any { it.account == a.id || it.toAccount == a.id }
        var currency by remember(a) { mutableStateOf(a.currency) }
        var picking by remember { mutableStateOf(false) }
        if (picking) CurrencyPicker(d, onDismiss = { picking = false }) { currency = it }
        EditDialog(
            title = if (a.id == 0L) "New account" else "Account",
            initial = a.name,
            color = a.color,
            hint = "e.g. Wallet, Safe, Travel",
            deleteBlocked = if (used) "Has transactions, so it can't be deleted or change currency" else null,
            onDismiss = { account = null },
            extra = {
                OutlinedButton(onClick = { picking = true }, enabled = !used) { Text("Currency: $currency  ${currencyName(currency)}") }
            },
            onDelete = if (a.id == 0L) null else ({ Store.update { x -> x.copy(accounts = x.accounts.filter { it.id != a.id }) }; account = null }),
            onSave = { name, color ->
                Store.update { x ->
                    if (a.id == 0L) x.copy(accounts = x.accounts + Account((x.accounts.maxOfOrNull { it.id } ?: 0) + 1, name, color!!, currency))
                    else x.copy(accounts = x.accounts.map { if (it.id == a.id) it.copy(name = name, color = color!!, currency = currency) else it })
                }
                account = null
            },
        )
    }
    category?.let { c ->
        EditDialog(
            title = if (c.id == 0L) "New category" else "Category",
            initial = c.name,
            hint = "Can start with an emoji: 🍕 Pizza",
            onDismiss = { category = null },
            onDelete = if (c.id == 0L) null else ({
                Store.update { x ->
                    x.copy(
                        categories = x.categories.filter { it.id != c.id },
                        txs = x.txs.map { if (it.category == c.id) it.copy(category = 0) else it },
                    )
                }
                category = null
            }),
            onSave = { name, _ ->
                Store.update { x ->
                    if (c.id == 0L) x.copy(categories = x.categories + Category((x.categories.maxOfOrNull { it.id } ?: 0) + 1, name))
                    else x.copy(categories = x.categories.map { if (it.id == c.id) it.copy(name = name) else it })
                }
                category = null
            },
        )
    }
    if (hiding) HideDialog(d, bal) { hiding = false }
    if (basePicker) CurrencyPicker(d, onDismiss = { basePicker = false }) { c -> Store.update { it.copy(base = c) } }
}

/** Checkbox list of accounts; unchecked ones become hidden on Save. */
@Composable
private fun HideDialog(d: Data, bal: Map<Long, Map<String, BigDecimal>>, onDismiss: () -> Unit) {
    var shown by remember { mutableStateOf(d.visible.map { it.id }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shown accounts") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(d.accounts, key = { it.id }) { a ->
                    val on = a.id in shown
                    Row(
                        Modifier.fillMaxWidth().clickable { shown = if (on) shown - a.id else shown + a.id }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(on, { shown = if (on) shown - a.id else shown + a.id })
                        Dot(a.color, 12)
                        Text(a.name, Modifier.weight(1f).padding(start = 10.dp))
                        Text("${money(bal.of(a))} ${a.currency}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Store.update { x -> x.copy(accounts = x.accounts.map { it.copy(hidden = it.id !in shown) }) }
                onDismiss()
            }, enabled = shown.isNotEmpty()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Name editor, optionally with a color picker (when [color] != null). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String, Int?) -> Unit,
    onDelete: (() -> Unit)?,
    color: Int? = null,
    hint: String? = null,
    deleteBlocked: String? = null,
    extra: @Composable () -> Unit = {},
) {
    var name by remember { mutableStateOf(initial) }
    var col by remember { mutableStateOf(color) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, placeholder = hint?.let { { Text(it) } })
                extra()
                col?.let { selected ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PALETTE.forEach { c ->
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(Color(c))
                                    .border(3.dp, if (c == selected) Color.White else Color.Transparent, CircleShape)
                                    .clickable { col = c },
                            )
                        }
                    }
                }
                if (onDelete != null && deleteBlocked != null)
                    Text(deleteBlocked, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), col) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null && deleteBlocked == null)
                    TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Searchable list of every currency (the online rate list, or the JDK's when rates never loaded). */
@Composable
fun CurrencyPicker(d: Data, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val all = remember(d.rates) {
        (d.knownCurrencies() + d.rates.keys.sorted().ifEmpty { Currency.getAvailableCurrencies().map { it.currencyCode }.sorted() }).distinct()
    }
    val shown = all.filter { q.isBlank() || it.contains(q, true) || currencyName(it).contains(q, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Currency") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search") })
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown) { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(c); onDismiss() }.padding(vertical = 12.dp),
                        ) {
                            Text(c, Modifier.width(52.dp), color = if (c == d.base) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text(currencyName(c), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}
