package app.cashbalance
import org.junit.Test
import org.junit.Assert.*
import java.io.StringWriter
import java.math.BigDecimal
class CsvImportTest {
    private val data = Data(accounts = listOf(Account(1,"=Wallet", hidden=true), Account(2,"Unused", color=123)), categories=listOf(Category(1,"Unused")), txs=listOf(Tx(1,123,Type.INCOME,1,"USD",BigDecimal("10.1200"),note="'=text,\n\"x\"")), base="EUR", rates=mapOf("USD" to 1.0,"EUR" to 0.9),ratesTime=123)
    @Test fun fullRoundTripIsExactAndRepeatable() {
        val csv=StringWriter().also { data.writeCsv(it) }.toString()
        val first=readLedgerCsv(csv); assertTrue(first.complete); assertEquals(data,first.data)
        assertEquals(first,readLedgerCsv(csv))
        assertEquals(Data(),readLedgerCsv(StringWriter().also { Data().writeCsv(it) }.toString()).data)
    }
    @Test fun oldExportRestoresTransactionsWithExplicitLimitedMode() {
        val result=readLedgerCsv(StringWriter().also { data.writeCsv(it,includeSnapshot=false) }.toString())
        assertFalse(result.complete); assertEquals(data.txs,result.data.txs)
    }
    @Test fun changedRowsAndBrokenCsvAreRejected() {
        val csv=StringWriter().also { data.writeCsv(it) }.toString()
        for (bad in listOf(csv.replace("10.1200","11.1200"),"\"broken",csv.replace("transaction_id","other"))) {
            try { readLedgerCsv(bad); fail("Accepted invalid CSV") } catch (expected: IllegalArgumentException) {}
        }
    }
}
