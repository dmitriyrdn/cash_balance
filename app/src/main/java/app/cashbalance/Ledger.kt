package app.cashbalance

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Calendar
import java.util.TimeZone

enum class Type(val label: String) {
    SPEND("Spend"), INCOME("Add"), MOVE("Move"), EXCHANGE("Exchange"), LEND("Lend"), REPAY("Repaid")
}

/** [hidden]: left out of totals, lists and pickers; its entries stay in the ledger. */
data class Account(val id: Long, val name: String, val color: Int = PALETTE[0], val currency: String = "USD", val hidden: Boolean = false)
data class Category(val id: Long, val name: String)

/** One ledger entry. Balances are never stored, always derived from the list of these. */
data class Tx(
    val id: Long,
    val time: Long,
    val type: Type,
    val account: Long,
    val currency: String,
    val amount: BigDecimal,
    val toAccount: Long = 0, // MOVE, EXCHANGE
    val toCurrency: String = "", // EXCHANGE
    val toAmount: BigDecimal = BigDecimal.ZERO, // EXCHANGE
    val person: String = "", // LEND, REPAY
    val note: String = "",
    val category: Long = 0, // SPEND, 0 = none
)

val DEFAULT_CATEGORIES = listOf(
    "🛒 Groceries", "🍽 Restaurants", "🚕 Transport", "🏠 Home", "👕 Clothes",
    "🎉 Fun", "💊 Health", "✈️ Travel", "🎁 Gifts", "📦 Other",
).mapIndexed { i, n -> Category(i + 1L, n) }

val PALETTE = listOf(
    0xFF3DDC84, 0xFF4FC3F7, 0xFFFFB74D, 0xFFF06292, 0xFFBA68C8,
    0xFFFFF176, 0xFF4DB6AC, 0xFFE57373, 0xFF9575CD, 0xFFA1887F,
).map { it.toInt() }

data class Data(
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = DEFAULT_CATEGORIES,
    val txs: List<Tx> = emptyList(),
    val base: String = "USD",
    val rates: Map<String, Double> = emptyMap(), // units of currency per 1 USD
    val ratesTime: Long = 0,
) {
    /** account id -> currency -> amount (zero entries dropped) */
    fun balances(): Map<Long, Map<String, BigDecimal>> {
        val m = Sums<Long>()
        for (t in txs) when (t.type) {
            Type.INCOME, Type.REPAY -> m.add(t.account, t.currency, t.amount)
            Type.SPEND, Type.LEND -> m.add(t.account, t.currency, -t.amount)
            Type.MOVE -> {
                m.add(t.account, t.currency, -t.amount)
                m.add(t.toAccount, t.currency, t.amount)
            }
            Type.EXCHANGE -> {
                m.add(t.account, t.currency, -t.amount)
                // entries from before one-currency accounts exchanged within the same account
                m.add(if (t.toAccount != 0L) t.toAccount else t.account, t.toCurrency, t.toAmount)
            }
        }
        return m.result()
    }

    val visible get() = accounts.filter { !it.hidden }

    /** Entries touching at least one visible account. */
    fun shownTxs(): List<Tx> {
        val ids = visible.map { it.id }.toSet()
        return txs.filter { it.account in ids || it.toAccount in ids }
    }

    /** person -> currency -> amount they still owe you */
    fun loans(): Map<String, Map<String, BigDecimal>> {
        val m = Sums<String>()
        for (t in txs) when (t.type) {
            Type.LEND -> m.add(t.person, t.currency, t.amount)
            Type.REPAY -> m.add(t.person, t.currency, -t.amount)
            else -> {}
        }
        return m.result()
    }

    /** Value in the base currency, null when there is no rate for [cur]. */
    fun toBase(amount: BigDecimal, cur: String): Double? {
        if (cur == base) return amount.toDouble()
        val r = rate(cur, base) ?: return null
        return amount.toDouble() * r
    }

    /** 1 [from] = x [to] */
    fun rate(from: String, to: String): Double? {
        val f = rates[from] ?: return null
        val t = rates[to] ?: return null
        return t / f
    }

    /** Inserts (id == 0) or replaces a transaction. */
    fun save(tx: Tx): Data =
        if (tx.id == 0L) copy(txs = txs + tx.copy(id = (txs.maxOfOrNull { it.id } ?: 0) + 1))
        else copy(txs = txs.map { if (it.id == tx.id) tx else it })

    fun toJson(): JSONObject = JSONObject()
        .put("base", base)
        .put("ratesTime", ratesTime)
        .put("rates", JSONObject(rates))
        .put("accounts", JSONArray(accounts.map { JSONObject().put("id", it.id).put("name", it.name).put("color", it.color).put("currency", it.currency).put("hidden", it.hidden) }))
        .put("categories", JSONArray(categories.map { JSONObject().put("id", it.id).put("name", it.name) }))
        .put(
            "txs",
            JSONArray(
                txs.map { t ->
                    JSONObject()
                        .put("id", t.id).put("time", t.time).put("type", t.type.name)
                        .put("account", t.account).put("currency", t.currency).put("amount", t.amount.toPlainString())
                        .put("toAccount", t.toAccount).put("toCurrency", t.toCurrency)
                        .put("toAmount", t.toAmount.toPlainString())
                        .put("person", t.person).put("note", t.note).put("category", t.category)
                },
            ),
        )

    companion object {
        fun fromJson(j: JSONObject): Data {
            val d = parse(j)
            // accounts saved before they had one currency get the one they used most
            val accounts = j.getJSONArray("accounts").objects().mapIndexed { i, it ->
                val id = it.getLong("id")
                val used = d.txs.filter { t -> t.account == id }.groupingBy { t -> t.currency }.eachCount().maxByOrNull { e -> e.value }?.key
                Account(id, it.getString("name"), it.optInt("color", PALETTE[i % PALETTE.size]), it.optString("currency").ifEmpty { used ?: d.base }, it.optBoolean("hidden"))
            }
            return d.copy(accounts = accounts)
        }

        private fun parse(j: JSONObject) = Data(
            base = j.optString("base", "USD"),
            ratesTime = j.optLong("ratesTime"),
            rates = j.optJSONObject("rates")?.let { r -> r.keys().asSequence().associateWith { r.getDouble(it) } }.orEmpty(),
            categories = j.optJSONArray("categories")?.objects()?.map { Category(it.getLong("id"), it.getString("name")) } ?: DEFAULT_CATEGORIES,
            txs = j.getJSONArray("txs").objects().map {
                Tx(
                    id = it.getLong("id"),
                    time = it.getLong("time"),
                    type = Type.valueOf(it.getString("type")),
                    account = it.getLong("account"),
                    currency = it.getString("currency"),
                    amount = BigDecimal(it.getString("amount")),
                    toAccount = it.optLong("toAccount"),
                    toCurrency = it.optString("toCurrency"),
                    toAmount = BigDecimal(it.optString("toAmount", "0")),
                    person = it.optString("person"),
                    note = it.optString("note"),
                    category = it.optLong("category"),
                )
            },
        )

        private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    }
}

/** What [a] holds in its own currency, from [Data.balances]. */
fun Map<Long, Map<String, BigDecimal>>.of(a: Account): BigDecimal = this[a.id]?.get(a.currency) ?: BigDecimal.ZERO

/** Exchanges between a pair, seen as "1 [x] = rate [y]": [xAmount] of x traded for [yAmount] of y over [count] exchanges. */
data class RateStat(val xAmount: BigDecimal, val yAmount: BigDecimal, val count: Int) {
    val rate: Double? get() = if (xAmount.signum() == 0) null else yAmount.toDouble() / xAmount.toDouble()
    operator fun plus(o: RateStat) = RateStat(xAmount + o.xAmount, yAmount + o.yAmount, count + o.count)
}

/** Volume-weighted average rate of exchanges that bought [x] (paid in [y]) and that sold [x] (for [y]). */
fun pairStats(txs: List<Tx>, x: String, y: String): Pair<RateStat, RateStat> {
    val ex = txs.filter { it.type == Type.EXCHANGE }
    fun stat(l: List<Tx>, xOf: (Tx) -> BigDecimal, yOf: (Tx) -> BigDecimal) =
        RateStat(l.sumOf(xOf), l.sumOf(yOf), l.size)
    val bought = ex.filter { it.currency == y && it.toCurrency == x }
    val sold = ex.filter { it.currency == x && it.toCurrency == y }
    return stat(bought, { it.toAmount }, { it.amount }) to stat(sold, { it.amount }, { it.toAmount })
}

private const val OPS = "+−×÷"

/** Keypad input with operators: no leading or doubled operator, one dot per number. */
fun pressKey(expr: String, k: Char): String = when {
    k == '⌫' -> expr.dropLast(1)
    k in OPS -> when {
        expr.isEmpty() -> expr
        expr.last() in OPS -> expr.dropLast(1) + k
        else -> expr + k
    }
    k == '.' && '.' in expr.takeLastWhile { it !in OPS } -> expr
    else -> expr + k
}

fun hasOps(expr: String) = expr.any { it in OPS }

/** Arithmetic like "10×3+5", "12,5÷2", "(1+2)*3", rounded to cents. Null if incomplete or invalid. */
fun calc(expr: String): BigDecimal? {
    val s = expr.replace(',', '.').replace('×', '*').replace('÷', '/').replace('−', '-').filterNot { it.isWhitespace() }
    if (s.isEmpty()) return null
    var i = 0
    fun peek() = s.getOrNull(i)
    lateinit var sum: () -> BigDecimal?
    fun factor(): BigDecimal? {
        if (peek() == '-') { i++; return factor()?.negate() }
        if (peek() == '(') {
            i++
            val v = sum() ?: return null
            if (peek() != ')') return null
            i++
            return v
        }
        val start = i
        while (peek()?.let { it.isDigit() || it == '.' } == true) i++
        return s.substring(start, i).toBigDecimalOrNull()
    }
    fun product(): BigDecimal? {
        var v = factor() ?: return null
        while (peek() == '*' || peek() == '/') {
            val op = s[i++]
            val r = factor() ?: return null
            v = if (op == '*') v * r else if (r.signum() == 0) return null else v.divide(r, MathContext.DECIMAL64)
        }
        return v
    }
    sum = {
        var v = product()
        while (v != null && (peek() == '+' || peek() == '-')) {
            val op = s[i++]
            val r = product()
            v = if (r == null) null else if (op == '+') v + r else v - r
        }
        v
    }
    val v = sum() ?: return null
    return if (i == s.length) v.setScale(2, RoundingMode.HALF_UP) else null
}

/** [time] moved to the calendar day of [utcMidnight] (what a date picker returns), keeping its local time of day. */
fun withDate(time: Long, utcMidnight: Long): Long {
    val day = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnight }
    return Calendar.getInstance().apply {
        timeInMillis = time
        set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** Local calendar day of [time] as UTC midnight, the form a date picker expects. */
fun toUtcMidnight(time: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = time }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

private class Sums<K> {
    private val m = mutableMapOf<K, MutableMap<String, BigDecimal>>()

    fun add(key: K, cur: String, v: BigDecimal) {
        val c = m.getOrPut(key) { sortedMapOf() }
        c[cur] = (c[cur] ?: BigDecimal.ZERO) + v
    }

    fun result() = m.mapValues { (_, c) -> c.filterValues { it.signum() != 0 } }.filterValues { it.isNotEmpty() }
}
