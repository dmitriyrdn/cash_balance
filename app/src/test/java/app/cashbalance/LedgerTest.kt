package app.cashbalance

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.util.Calendar
import java.util.TimeZone

class LedgerTest {
    private fun bd(s: String) = BigDecimal(s)

    @Test
    fun balancesLoansAndConversion() {
        var id = 0L
        fun tx(type: Type, acc: Long, cur: String, amt: String, block: Tx.() -> Tx = { this }) =
            Tx(++id, 0, type, acc, cur, bd(amt)).block()

        val d = Data(
            accounts = listOf(Account(1, "Wallet"), Account(2, "Safe")),
            rates = mapOf("USD" to 1.0, "EUR" to 0.5),
            txs = listOf(
                tx(Type.INCOME, 1, "USD", "100"),
                tx(Type.SPEND, 1, "USD", "10.50"),
                tx(Type.MOVE, 1, "USD", "20") { copy(toAccount = 2) },
                tx(Type.EXCHANGE, 2, "USD", "20") { copy(toCurrency = "EUR", toAmount = bd("10")) },
                tx(Type.LEND, 1, "USD", "30") { copy(person = "Bob") },
                tx(Type.REPAY, 1, "USD", "30") { copy(person = "Bob") },
                tx(Type.LEND, 1, "USD", "5") { copy(person = "Ann") },
            ),
        )
        val b = d.balances()
        assertEquals(bd("64.50"), b[1]!!["USD"])
        assertEquals(mapOf("EUR" to bd("10")), b[2]) // USD fully exchanged -> zero dropped
        assertEquals(mapOf("Ann" to mapOf("USD" to bd("5"))), d.loans()) // Bob repaid -> dropped
        assertEquals(20.0, d.toBase(bd("10"), "EUR")!!, 1e-9)
        assertEquals(10.0, d.copy(base = "EUR").toBase(bd("20"), "USD")!!, 1e-9)
        assertEquals(null, d.toBase(bd("1"), "RUB"))
        // order of entries doesn't matter: the balance is a sum over all of them
        assertEquals(b, d.copy(txs = d.txs.reversed()).balances())

        // editing replaces in place, a new entry gets the next id
        val edited = d.save(d.txs[1].copy(amount = bd("0.50")))
        assertEquals(bd("74.50"), edited.balances()[1]!!["USD"])
        assertEquals(8L, d.save(d.txs[0].copy(id = 0)).txs.last().id)
    }

    @Test
    fun exchangeBetweenAccounts() {
        val usd = Account(1, "Wallet", currency = "USD")
        val rub = Account(2, "Rubles", currency = "RUB")
        val d = Data(
            accounts = listOf(usd, rub),
            txs = listOf(
                Tx(1, 0, Type.INCOME, 1, "USD", bd("100")),
                Tx(2, 0, Type.EXCHANGE, 1, "USD", bd("40"), toAccount = 2, toCurrency = "RUB", toAmount = bd("3800")),
            ),
        )
        val b = d.balances()
        assertEquals(bd("60"), b.of(usd))
        assertEquals(bd("3800"), b.of(rub))
        assertEquals(setOf("USD"), b[1]!!.keys) // nothing in a foreign currency left behind
        assertEquals(95.0, pairStats(d.txs, "USD", "RUB").second.rate!!, 1e-9)

        // hiding an account keeps its entries and balance, only filters what's shown
        val h = d.copy(accounts = listOf(usd, rub.copy(hidden = true)))
        assertEquals(listOf(usd), h.visible)
        assertEquals(d.txs, h.shownTxs()) // both touch Wallet
        assertEquals(listOf(d.txs[1]), d.copy(accounts = listOf(usd.copy(hidden = true), rub)).shownTxs()) // only the exchange touches Rubles
        assertEquals(b, h.balances())
        assertEquals(h, Data.fromJson(JSONObject(h.toJson().toString())))
    }

    @Test
    fun averageExchangeRate() {
        fun ex(cur: String, amt: String, to: String, toAmt: String) = Tx(0, 0, Type.EXCHANGE, 1, cur, bd(amt), toCurrency = to, toAmount = bd(toAmt))
        val txs = listOf(
            ex("RUB", "9000", "USD", "100"), // bought USD at 90
            ex("RUB", "10000", "USD", "100"), // bought USD at 100
            ex("USD", "50", "RUB", "4000"), // sold USD at 80
            ex("EUR", "10", "USD", "11"), // other pair, ignored
        )
        val (bought, sold) = pairStats(txs, "USD", "RUB")
        assertEquals(95.0, bought.rate!!, 1e-9)
        assertEquals(2, bought.count)
        assertEquals(80.0, sold.rate!!, 1e-9)
        assertEquals(23000.0 / 250, (bought + sold).rate!!, 1e-9)
        assertNull(pairStats(txs, "USD", "GBP").first.rate)
    }

    @Test
    fun calculator() {
        assertEquals(bd("30.00"), calc("10×3"))
        assertEquals(bd("35.00"), calc("10×3+5"))
        assertEquals(bd("6.25"), calc("12,5÷2"))
        assertEquals(bd("9.00"), calc("(1+2)*3"))
        assertEquals(bd("0.30"), calc("0.1+0.2"))
        assertEquals(bd("3.33"), calc("10÷3"))
        assertNull(calc("10×"))
        assertNull(calc("5÷0"))
        assertNull(calc(""))
        assertNull(calc("1..2"))
        assertEquals("10×", pressKey("10+", '×'))
        assertEquals("", pressKey("", '+'))
        assertEquals("1.5", pressKey("1.5", '.'))
        assertEquals("1.5+2.", pressKey("1.5+2", '.'))
    }

    @Test
    fun jsonRoundTripAndOldFormat() {
        val d = Data(
            accounts = listOf(Account(1, "Wallet", PALETTE[2])),
            categories = listOf(Category(5, "🍕 Pizza")),
            txs = listOf(Tx(1, 7, Type.SPEND, 1, "USD", bd("12.34"), note = "x", category = 5)),
            rates = mapOf("USD" to 1.0),
        )
        assertEquals(d, Data.fromJson(JSONObject(d.toJson().toString())))
        // files written before categories and account colors existed
        val old = """{"base":"EUR","accounts":[{"id":1,"name":"Wallet"},{"id":2,"name":"Safe"}],
            "txs":[{"id":2,"time":5,"type":"INCOME","account":2,"currency":"GBP","amount":"1"},
            {"id":1,"time":5,"type":"INCOME","account":1,"currency":"EUR","amount":"10","toAccount":0,"toCurrency":"","toAmount":"0","person":"","note":""}]}"""
        val o = Data.fromJson(JSONObject(old))
        assertEquals(DEFAULT_CATEGORIES, o.categories)
        assertEquals(listOf(PALETTE[0], PALETTE[1]), o.accounts.map { it.color })
        assertEquals(listOf("EUR", "GBP"), o.accounts.map { it.currency }) // the one each used most
        assertEquals(0L, o.txs[0].category)
        assertEquals("USD", Data.fromJson(JSONObject("""{"accounts":[{"id":1,"name":"A"}],"txs":[]}""")).accounts[0].currency) // unused: base
        assertEquals(bd("10"), o.balances()[1]!!["EUR"])
    }

    @Test
    fun withDateKeepsTimeOfDay() {
        val t = Calendar.getInstance().apply { set(2026, 9, 26, 14, 30, 0) }.timeInMillis
        val jan5 = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, 0, 5) }.timeInMillis
        val r = Calendar.getInstance().apply { timeInMillis = withDate(t, jan5) }
        assertEquals(listOf(2026, 0, 5, 14, 30), listOf(Calendar.YEAR, Calendar.MONTH, Calendar.DAY_OF_MONTH, Calendar.HOUR_OF_DAY, Calendar.MINUTE).map(r::get))
        assertEquals(t, withDate(t, toUtcMidnight(t)))
    }
}
