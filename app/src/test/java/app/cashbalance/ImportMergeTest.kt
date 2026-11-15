package app.cashbalance
import org.junit.Test
import org.junit.Assert.*
import java.math.BigDecimal
class ImportMergeTest {
 private fun tx(id: Long, amount: String = "10") = Tx(id,0,Type.SPEND,1,"USD",BigDecimal(amount))
 @Test fun collisionsRemapAccountsCategoriesAndTransactionsWithoutOverwriting() {
  val current=Data(accounts=listOf(Account(1,"Local")),categories=listOf(Category(1,"Food")),txs=listOf(tx(1)),base="EUR")
  val incoming=Data(accounts=listOf(Account(1,"Imported"),Account(2,"Target")),categories=listOf(Category(1,"Travel")),txs=listOf(tx(1).copy(type=Type.MOVE,toAccount=2),tx(2).copy(category=1)))
  val r=current.appendImport(incoming)
  assertEquals(2,r.added);assertEquals("EUR",r.data.base);assertEquals(current.txs.first(),r.data.txs.first())
  assertEquals(3,r.data.accounts.size);assertEquals(3,r.data.txs.map { it.id }.distinct().size)
  assertEquals("Imported",r.data.accounts.find { it.id==r.data.txs[1].account }!!.name)
  assertEquals("Target",r.data.accounts.find { it.id==r.data.txs[1].toAccount }!!.name)
  assertEquals("Travel",r.data.categories.find { it.id==r.data.txs[2].category }!!.name)
  assertEquals(r.data,r.data.appendImport(incoming).data)
 }
 @Test fun preservesRepeatedPurchasesButSkipsMatchingOccurrences() {
  val d=Data(accounts=listOf(Account(1,"Wallet")),txs=listOf(tx(1,"10.00")))
  val incoming=d.copy(txs=listOf(tx(80),tx(81)))
  val r=d.appendImport(incoming)
  assertEquals(1,r.added);assertEquals(1,r.skipped);assertEquals(2,r.data.txs.size)
  assertEquals(r.data,r.data.appendImport(incoming).data)
 }
 @Test fun negativeBalancesWorkForEveryOutgoingType() {
  for(type in listOf(Type.SPEND,Type.MOVE,Type.EXCHANGE,Type.LEND)) {
   val d=Data(accounts=listOf(Account(1,"Wallet"),Account(2,"Target")),txs=listOf(tx(1).copy(type=type,toAccount=2,toCurrency="EUR",toAmount=BigDecimal.ONE)))
   assertEquals(BigDecimal("-10"),d.balances()[1L]!!["USD"])
  }
 }
}
