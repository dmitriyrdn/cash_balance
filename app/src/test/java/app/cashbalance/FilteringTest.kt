package app.cashbalance

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class FilteringTest {
    private fun entry(id: Long, account: Long, type: Type = Type.SPEND, day: String = "2026-11-14", category: Long = 1, to: Long = 0) =
        Tx(id, LocalDate.parse(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            type, account, "USD", BigDecimal.TEN, toAccount = to, category = category)

    private val data = Data(
        accounts = listOf(Account(1, "Wallet"), Account(2, "Safe"), Account(3, "Hidden", hidden = true)),
        txs = listOf(
            entry(1, 1), entry(2, 2), entry(3, 1, day = "2026-10-31"),
            entry(4, 1, category = 0), entry(5, 1, Type.INCOME),
            entry(6, 2, Type.MOVE, to = 1), entry(7, 3),
            entry(8, 3, Type.EXCHANGE, to = 1),
        ),
    )

    @Test fun accountMatchesBothSidesAndExcludesHiddenOnlyEntries() {
        assertEquals(listOf(8L, 6L, 5L, 4L, 2L, 1L, 3L), data.filteredTxs().map { it.id })
        assertEquals(listOf(8L, 6L, 5L, 4L, 1L, 3L), data.filteredTxs(accountId = 1).map { it.id })
        assertEquals(listOf(6L, 2L), data.filteredTxs(accountId = 2).map { it.id })
    }

    @Test fun categoryDrilldownKeepsAccountAndLocalMonthAndOnlyShowsSpending() {
        val november = YearMonth.of(2026, 11)
        assertEquals(listOf(1L), data.filteredTxs(1, november, 1).map { it.id })
        assertEquals(listOf(2L), data.filteredTxs(2, november, 1).map { it.id })
        assertEquals(listOf(2L, 1L), data.filteredTxs(null, november, 1).map { it.id })
        assertEquals(listOf(1L, 3L), data.filteredTxs(1, null, 1).map { it.id })
        assertEquals(listOf(4L), data.filteredTxs(1, november, 0).map { it.id })
        assertTrue(data.filteredTxs(2, november, 0).isEmpty())
    }

    @Test fun newEntryUsesSelectedVisibleAccountAndItsCurrency() {
        val d = data.copy(accounts = data.accounts + Account(4, "Euro", currency = "EUR"))
        for (type in Type.entries) {
            val tx = d.newEntry(type, 4)!!
            assertEquals(4L, tx.account)
            assertEquals("EUR", tx.currency)
            assertEquals(type, tx.type)
            assertEquals(0L, tx.id)
        }
        assertEquals(1L, d.newEntry(Type.SPEND)!!.account)
        assertEquals(1L, d.newEntry(Type.SPEND, 3)!!.account) // hidden
        assertEquals(1L, d.newEntry(Type.SPEND, 99)!!.account) // deleted
        val repayment = d.newEntry(Type.REPAY, 1, "Ann", "EUR")!!
        assertEquals(4L, repayment.account)
        assertEquals("Ann", repayment.person)
        assertNull(Data().newEntry(Type.SPEND))
    }
}
