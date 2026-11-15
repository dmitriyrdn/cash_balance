package app.cashbalance

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.StringWriter
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var backDispatcher: OnBackPressedDispatcher

    @Before fun start() {
        val now = LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        Store.init(ApplicationProvider.getApplicationContext())
        Store.update {
            Data(
                accounts = listOf(Account(1, "Wallet"), Account(2, "Safe")),
                categories = listOf(Category(1, "Food")),
                rates = mapOf("USD" to 1.0), ratesTime = System.currentTimeMillis(),
                txs = listOf(Tx(100, now - 100_000, Type.INCOME, 2, "USD", BigDecimal("1000"))) +
                    (1L..80L).map { id -> Tx(id, now - id * 1000, Type.SPEND, 2, "USD", BigDecimal.ONE, category = 1, note = "Expense $id") },
            )
        }
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            MaterialTheme { App() }
        }
    }

    @Test fun newEntryUsesHistoryFilterAndCloseKeepsIt() {
        compose.onNodeWithText("Safe").performClick()
        compose.onNodeWithContentDescription("Add").performClick()
        compose.onNodeWithText("💸  Spend").performClick()
        compose.onNodeWithText("Safe · 920.00 USD").assertIsSelected()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithContentDescription("Add").performClick()
        compose.onNodeWithText("💵  Add").performClick()
        compose.onNodeWithText("Safe · 920.00 USD").assertIsSelected()
    }

    @Test fun historyRestoresScrollAfterCloseAndSave() {
        compose.onNodeWithText("Safe").performClick()
        val row = "Safe · Expense 40"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(row))
        val top = compose.onNodeWithText(row).fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithText(row).performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        assertEquals(top, compose.onNodeWithText(row).fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithText(row).performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(top, compose.onNodeWithText(row).fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun categoryInheritsFilterAndRestoresScrollAfterEditing() {
        compose.onNodeWithText("Analytics").performClick()
        compose.onNodeWithText("All time").performClick()
        compose.onNodeWithText("Safe").performClick()
        compose.onNodeWithText("Food").performScrollTo().performClick()
        compose.onNodeWithText("Safe").assertIsSelected()
        compose.onNodeWithText("All time").assertExists()
        val row = "Safe · Expense 40"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(row))
        val top = compose.onNodeWithText(row).fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithText(row).performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(top, compose.onNodeWithText(row).fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithText("Safe").assertIsSelected()
        compose.onNodeWithText("Wallet").performClick()
        compose.onNodeWithText("No spending in this category for this account and period").assertExists()
        compose.onNodeWithContentDescription("Back to analytics").performClick()
        compose.onNodeWithText("Wallet").assertIsSelected()
        compose.onNode(hasText("All time") and hasClickAction()).assertIsSelected()
    }

    @Test fun systemBackAndDeletionReturnToCategoryListPosition() {
        compose.onNodeWithText("Analytics").performClick()
        compose.onNodeWithText("Food").performScrollTo().performClick()
        val row = "Safe · Expense 40"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(row))
        val top = compose.onNodeWithText(row).fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithText(row).performClick()
        compose.runOnIdle { backDispatcher.onBackPressed() }
        assertEquals(top, compose.onNodeWithText(row).fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithText(row).performClick()
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithContentDescription("Back to analytics").assertExists()
        compose.onNodeWithText(row).assertDoesNotExist()
        compose.onNodeWithText("Safe · Expense 39").assertIsDisplayed()
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.onNodeWithText("By category").assertExists()
    }

    @Test fun settingsExportsCsvToChosenDocument() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Export CSV"))
        compose.onNodeWithText("Export CSV").performClick()
        val activity = shadowOf(compose.activity)
        val request = activity.nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
        assertEquals("text/csv", request.intent.type)
        assertEquals("cash-balance-${LocalDate.now()}.csv", request.intent.getStringExtra(Intent.EXTRA_TITLE))
        val file = File(compose.activity.cacheDir, "ledger.csv")
        compose.runOnIdle {
            activity.receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
        }
        compose.waitUntil(10_000) { compose.runOnIdle { ShadowToast.getTextOfLatestToast() == "CSV exported" } }
        assertEquals(StringWriter().also { Store.data.writeCsv(it) }.toString(), file.readText(Charsets.UTF_8))
        compose.onNodeWithText("Export CSV").assertExists()
    }

    @Test fun cancellingCsvPickerLeavesSettingsReady() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Export CSV"))
        compose.onNodeWithText("Export CSV").performClick()
        val activity = shadowOf(compose.activity)
        val request = activity.nextStartedActivityForResult
        compose.runOnIdle { activity.receiveResult(request.intent, Activity.RESULT_CANCELED, null) }
        compose.onNodeWithText("Export CSV").assertExists()
        assertEquals(null, ShadowToast.getTextOfLatestToast())
        assertEquals(81, Store.data.txs.size)
    }

    @Test fun failedCsvWriteReportsFailureWithoutChangingLedger() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Export CSV"))
        compose.onNodeWithText("Export CSV").performClick()
        val activity = shadowOf(compose.activity)
        val request = activity.nextStartedActivityForResult
        // A directory cannot be opened as an output file.
        compose.runOnIdle {
            activity.receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(compose.activity.cacheDir)))
        }
        compose.waitUntil(10_000) { compose.runOnIdle { ShadowToast.getTextOfLatestToast() == "Couldn't export CSV. Please try again." } }
        compose.onNodeWithText("Export CSV").assertExists()
        assertEquals(81, Store.data.txs.size)
    }
    @Test fun csvImportRequiresConfirmationAndRestoresLedger() {
        val original = Store.data
        val file = File(compose.activity.cacheDir, "restore.csv")
        file.writeText(StringWriter().also { original.writeCsv(it) }.toString())
        compose.runOnIdle { Store.update { it.copy(txs = emptyList()) } }
        compose.onNodeWithText("Settings").performClick()
        fun chooseFile() {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Import CSV"))
            compose.onNodeWithText("Import CSV").performClick()
            val activity = shadowOf(compose.activity)
            val request = activity.nextStartedActivityForResult
            compose.runOnIdle { activity.receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Replace entire ledger").fetchSemanticsNodes().isNotEmpty() }
        }
        chooseFile()
        assertEquals(0, Store.data.txs.size)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, Store.data.txs.size)
        chooseFile()
        compose.onNodeWithText("Replace entire ledger").performClick()
        compose.onNodeWithText("Replace").performClick()
        compose.runOnIdle { assertEquals(original, Store.data) }
        chooseFile()
        compose.onNodeWithText("Add to current ledger").assertIsSelected()
        compose.onNodeWithText("Add").performClick()
        compose.runOnIdle { assertEquals(original, Store.data) }
    }

    @Test fun spendingFromEmptyAccountCanCreateNegativeBalance() {
        compose.onNodeWithContentDescription("Add").performClick()
        compose.onNodeWithText("💸  Spend").performClick()
        compose.onNodeWithText("1").performClick()
        compose.onNodeWithText("0").performClick()
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(BigDecimal("-10.00"), Store.data.balances()[1L]!!["USD"]) }
    }

}
