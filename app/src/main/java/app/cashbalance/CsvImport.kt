package app.cashbalance

import org.json.JSONObject
import java.io.StringWriter
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.Base64

/** Strict CSV parser: quoted multiline fields and doubled quotes are preserved. */
fun parseCsv(source: String): List<List<String>> {
    val s = source.removePrefix("\uFEFF")
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var closed = false
    var i = 0
    fun endCell() { row.add(cell.toString()); cell.clear(); closed = false }
    while (i < s.length) {
        val c = s[i++]
        if (quoted) {
            if (c == '"') {
                if (i < s.length && s[i] == '"') { cell.append('"'); i++ } else { quoted = false; closed = true }
            } else cell.append(c)
        } else when (c) {
            '"' -> { require(cell.isEmpty() && !closed) { "Invalid CSV quote" }; quoted = true }
            ',' -> endCell()
            '\r', '\n' -> { if (c == '\r' && i < s.length && s[i] == '\n') i++; endCell(); rows.add(row); row = mutableListOf() }
            else -> { require(!closed) { "Unexpected text after quote" }; cell.append(c) }
        }
    }
    require(!quoted) { "Unclosed CSV quote" }
    if (cell.isNotEmpty() || row.isNotEmpty() || closed) { endCell(); rows.add(row) }
    return rows
}

data class CsvImport(val data: Data, val complete: Boolean)

fun readLedgerCsv(text: String): CsvImport {
    val rows = parseCsv(text)
    val header = parseCsv(StringWriter().also { Data().writeCsv(it, includeSnapshot = false) }.toString()).single()
    require(rows.firstOrNull() == header) { "Not a Cash Balance CSV" }
    require(rows.all { it.size == header.size }) { "Invalid column count" }
    val snapshots = rows.drop(1).filter { it[0] == "cash_balance_snapshot_v1" }
    require(snapshots.size <= 1) { "Duplicate snapshot" }
    if (snapshots.isNotEmpty()) {
        val snapshot = snapshots.single()
        require(rows.last() == snapshot) { "Snapshot must be the last row" }
        val data = Data.fromJson(JSONObject(String(Base64.getDecoder().decode(snapshot[1]), Charsets.UTF_8)))
        validateLedger(data)
        // Compare timestamps as instants: the file may have been exported in another timezone.
        fun normalized(rs: List<List<String>>) = rs.drop(1).map { r -> r.toMutableList().also { it[1] = OffsetDateTime.parse(it[1]).toInstant().toString() } }
        val expected = parseCsv(StringWriter().also { data.writeCsv(it, includeSnapshot = false) }.toString())
        require(normalized(rows.dropLast(1)) == normalized(expected)) { "CSV rows differ from the recovery snapshot" }
        return CsvImport(data, true)
    }
    val accounts = linkedMapOf<Long, Account>()
    val categories = linkedMapOf<Long, Category>()
    // Older exports cannot distinguish an original apostrophe from formula protection; preserve text.
    fun account(id: Long, name: String, hidden: String, currency: String) {
        val a = Account(id, name, currency = currency, hidden = hidden.toBooleanStrict())
        require(accounts[id] == null || accounts[id] == a) { "Conflicting account definitions" }
        accounts[id] = a
    }
    val txs = rows.drop(1).map { r ->
        val type = Type.valueOf(r[2]); val id = r[3].toLong()
        account(id, r[4], r[5], r[6])
        val to = r[8].takeIf { it.isNotEmpty() }?.toLong() ?: 0L
        if (to != 0L) account(to, r[9], r[10], r[11])
        val cat = r[14].takeIf { it.isNotEmpty() }?.toLong() ?: 0L
        if (cat != 0L) {
            val c = Category(cat, r[15]); require(categories[cat] == null || categories[cat] == c) { "Conflicting categories" }; categories[cat] = c
        }
        Tx(r[0].toLong(), OffsetDateTime.parse(r[1]).toInstant().toEpochMilli(), type, id, r[6], BigDecimal(r[7]),
            toAccount = to, toCurrency = if (type == Type.EXCHANGE) r[11] else "",
            toAmount = if (type == Type.EXCHANGE) BigDecimal(r[12]) else BigDecimal.ZERO, person = r[13], category = cat, note = r[16])
    }
    val data = Data(accounts = accounts.values.toList(), categories = categories.values.toList(), txs = txs)
    validateLedger(data)
    return CsvImport(data, false)
}

private fun validateLedger(d: Data) {
    require(d.accounts.map { it.id }.distinct().size == d.accounts.size && d.accounts.all { it.id > 0 }) { "Invalid account IDs" }
    require(d.categories.map { it.id }.distinct().size == d.categories.size && d.categories.all { it.id > 0 }) { "Invalid category IDs" }
    require(d.txs.map { it.id }.distinct().size == d.txs.size && d.txs.all { it.id > 0 }) { "Duplicate or invalid transaction IDs" }
    require(d.base.matches(Regex("[A-Z]{3}")) && d.rates.values.all { it.isFinite() && it > 0 }) { "Invalid currency rates" }
    for (t in d.txs) {
        require(d.accounts.any { it.id == t.account } && t.amount.signum() > 0) { "Invalid transaction account or amount" }
        require(t.category == 0L || d.categories.any { it.id == t.category }) { "Unknown category" }
        if (t.type == Type.MOVE || t.type == Type.EXCHANGE) require((t.type == Type.EXCHANGE && t.toAccount == 0L) || d.accounts.any { it.id == t.toAccount }) { "Unknown destination" }
        if (t.type == Type.EXCHANGE) require(t.toAmount.signum() > 0) { "Invalid exchange amount" }
    }
}
