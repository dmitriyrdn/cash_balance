package app.cashbalance

import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.math.BigDecimal
import java.time.ZoneId
import java.util.Locale

class CsvTest {
    private fun export(data: Data, zone: ZoneId = ZoneId.of("UTC")) = StringWriter().also { data.writeCsv(it, zone, includeSnapshot = false) }.toString()

    @Test fun emptyLedgerHasUtf8BomAndHeader() {
        assertEquals(
            "\uFEFF\"transaction_id\",\"date\",\"type\",\"account_id\",\"account\",\"account_hidden\",\"currency\",\"amount\"," +
                "\"to_account_id\",\"to_account\",\"to_account_hidden\",\"to_currency\",\"to_amount\",\"person\",\"category_id\",\"category\",\"note\"\r\n",
            export(Data()),
        )
    }

    @Test fun escapesTextAndIncludesHiddenAccountsWithExactDecimalAmounts() {
        val data = Data(
            accounts = listOf(Account(1, "Кошелёк, \"основной\"", hidden = true)),
            categories = listOf(Category(7, "🍕 Еда")),
            txs = listOf(Tx(1, 0, Type.SPEND, 1, "RUB", BigDecimal("1234567890.1200"), category = 7, note = "Обед, чай\r\n\"Спасибо\"")),
        )
        val oldLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ru-RU"))
            assertEquals(
                "\"1\",\"1970-01-01T03:00:00+03:00\",\"SPEND\",\"1\",\"Кошелёк, \"\"основной\"\"\",\"true\",\"RUB\",\"1234567890.1200\"," +
                    "\"\",\"\",\"\",\"\",\"\",\"\",\"7\",\"🍕 Еда\",\"Обед, чай\r\n\"\"Спасибо\"\"\"\r\n",
                export(data, ZoneId.of("+03:00")).substringAfter("\r\n"),
            )
        } finally { Locale.setDefault(oldLocale) }
    }

    @Test fun exportsEveryTypeAndBothSidesWithoutBaseCurrencyConversion() {
        val data = Data(
            accounts = listOf(Account(1, "Wallet"), Account(2, "Safe", hidden = true)),
            base = "EUR", rates = mapOf("USD" to 1.0, "EUR" to 0.5),
            txs = Type.entries.mapIndexed { index, type ->
                Tx(index + 1L, 0, type, 1, "USD", BigDecimal("10.00"), toAccount = 2, toCurrency = "EUR", toAmount = BigDecimal("9.50"), person = "Ann")
            },
        )
        val rows = export(data).split("\r\n").drop(1).filter { it.isNotEmpty() }
        assertEquals(6, rows.size)
        assertEquals(listOf("REPAY", "LEND", "EXCHANGE", "MOVE", "INCOME", "SPEND"), rows.map { it.split(",")[2].trim('"') })
        assertTrue(rows.all { it.contains("\"USD\",\"10.00\"") })
        assertTrue(rows[2].contains("\"2\",\"Safe\",\"true\",\"EUR\",\"9.50\""))
        assertTrue(rows[3].contains("\"2\",\"Safe\",\"true\",\"USD\",\"10.00\""))
        assertTrue(rows[0].contains("\"\",\"\",\"\",\"\",\"\",\"Ann\""))
        val legacy = data.copy(txs = listOf(data.txs.first { it.type == Type.EXCHANGE }.copy(toAccount = 0)))
        assertTrue(export(legacy).contains("\"1\",\"Wallet\",\"false\",\"EUR\",\"9.50\""))
    }

    @Test fun userTextIsNotInterpretedAsSpreadsheetFormula() {
        for (note in listOf("=1+1", "+SUM(A1)", "-1+2", "@SUM(A1)", "  =1", "\tformula", "\rformula")) {
            val data = Data(accounts = listOf(Account(1, "=account")), categories = listOf(Category(1, "+category")),
                txs = listOf(Tx(1, 0, Type.SPEND, 1, "USD", BigDecimal.ONE, person = "@person", category = 1, note = note)))
            val csv = export(data)
            assertTrue(csv.contains("\"'=account\""))
            assertTrue(csv.contains("\"'+category\""))
            assertTrue(csv.contains("\"'@person\""))
            assertTrue(csv.endsWith("\"'$note\"\r\n"))
        }
    }
}
