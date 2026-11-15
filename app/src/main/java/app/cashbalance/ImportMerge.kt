package app.cashbalance

/** Append without overwriting local data. Match occurrences, not just a set, to retain repeated purchases. */
data class ImportMerge(val data: Data, val added: Int, val skipped: Int)

fun Data.appendImport(incoming: Data): ImportMerge {
    val accounts = accounts.toMutableList()
    val categories = categories.toMutableList()
    val usedAccounts = mutableSetOf<Long>()
    val usedCategories = mutableSetOf<Long>()
    fun nextId(ids: List<Long>): Long = Math.addExact(ids.maxOrNull() ?: 0L, 1L)
    val accountMap = incoming.accounts.associate { a ->
        val matches = accounts.filter { it.name == a.name && it.currency == a.currency && it.id !in usedAccounts }
        val match = matches.find { it.id == a.id } ?: matches.singleOrNull()
        val id = match?.id ?: (a.id.takeIf { id -> accounts.none { it.id == id } } ?: nextId(accounts.map { it.id })).also { accounts.add(a.copy(id = it)) }
        usedAccounts.add(id)
        a.id to id
    }
    val categoryMap = incoming.categories.associate { c ->
        val matches = categories.filter { it.name == c.name && it.id !in usedCategories }
        val match = matches.find { it.id == c.id } ?: matches.singleOrNull()
        val id = match?.id ?: (c.id.takeIf { id -> categories.none { it.id == id } } ?: nextId(categories.map { it.id })).also { categories.add(c.copy(id = it)) }
        usedCategories.add(id)
        c.id to id
    }
    fun fingerprint(t: Tx) = t.copy(id = 0, amount = t.amount.stripTrailingZeros(), toAmount = t.toAmount.stripTrailingZeros())
    val remaining = txs.groupingBy(::fingerprint).eachCount().toMutableMap()
    val result = txs.toMutableList()
    val ids = txs.map { it.id }.toMutableSet()
    var skipped = 0
    for (t in incoming.txs) {
        val mapped = t.copy(account = accountMap.getValue(t.account),
            toAccount = if (t.toAccount == 0L) 0 else accountMap.getValue(t.toAccount),
            category = if (t.category == 0L) 0 else categoryMap.getValue(t.category))
        val key = fingerprint(mapped)
        val count = remaining[key] ?: 0
        if (count > 0) { remaining[key] = count - 1; skipped++; continue }
        val id = mapped.id.takeIf { it !in ids } ?: nextId(ids.toList())
        result.add(mapped.copy(id = id)); ids.add(id)
    }
    return ImportMerge(copy(accounts = accounts, categories = categories, txs = result), incoming.txs.size - skipped, skipped)
}
