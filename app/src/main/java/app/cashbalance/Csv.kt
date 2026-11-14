package app.cashbalance

import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Export the complete ledger, including hidden accounts, without converting amounts. */
fun Data.writeCsv(writer: Writer, zone: ZoneId = ZoneId.systemDefault()) {
    val accountsById = accounts.associateBy { it.id }
    val categoriesById = categories.associateBy { it.id }
    fun row(cells: List<String>) {
        writer.write(cells.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" })
        writer.write("\r\n")
    }

    // Excel uses the BOM to recognize UTF-8 names, comments and emoji.
    writer.write("\uFEFF")
    row(listOf("transaction_id", "date", "type", "account_id", "account", "account_hidden", "currency", "amount",
        "to_account_id", "to_account", "to_account_hidden", "to_currency", "to_amount", "person", "category_id", "category", "note"))
    for (tx in txs.sortedWith(compareByDescending<Tx> { it.time }.thenByDescending { it.id })) {
        val account = accountsById[tx.account]
        // Old exchanges could convert currency within the same account.
        val targetId = when (tx.type) {
            Type.MOVE -> tx.toAccount
            Type.EXCHANGE -> tx.toAccount.takeIf { it != 0L } ?: tx.account
            else -> null
        }
        val target = accountsById[targetId]
        row(listOf(
            tx.id.toString(),
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(tx.time).atZone(zone)),
            tx.type.name,
            tx.account.toString(),
            csvText(account?.name.orEmpty()),
            account?.hidden?.toString().orEmpty(),
            csvText(tx.currency),
            tx.amount.toPlainString(),
            targetId?.toString().orEmpty(),
            csvText(target?.name.orEmpty()),
            target?.hidden?.toString().orEmpty(),
            csvText(when (tx.type) { Type.MOVE -> tx.currency; Type.EXCHANGE -> tx.toCurrency; else -> "" }),
            when (tx.type) { Type.MOVE -> tx.amount.toPlainString(); Type.EXCHANGE -> tx.toAmount.toPlainString(); else -> "" },
            csvText(tx.person),
            if (tx.type == Type.SPEND) tx.category.toString() else "",
            csvText(if (tx.type == Type.SPEND) categoriesById[tx.category]?.name.orEmpty() else ""),
            csvText(tx.note),
        ))
    }
}

/** Keep user text from being interpreted as spreadsheet formulas. Numeric cells are separate. */
private fun csvText(value: String): String =
    if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') || value.startsWith('\t') || value.startsWith('\r')) "'$value" else value
