package app.cashbalance

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import java.io.File

@Composable
fun rememberCsvImport(): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<CsvImport?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true
            scope.launch(Dispatchers.Main.immediate) {
                try {
                    pending = withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)!!.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) { val count = input.read(buffer); if (count < 0) break; require(out.size() + count <= 20 * 1024 * 1024) { "File too large" }; out.write(buffer, 0, count) }
                            out.toByteArray() }
                        require(bytes.size <= 20 * 1024 * 1024) { "File too large" }
                        readLedgerCsv(bytes.toString(Charsets.UTF_8))
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Toast.makeText(context, "CSV not imported: ${e.message}", Toast.LENGTH_LONG).show() }
                finally { busy = false }
            }
        }
    }
    pending?.let { imported ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("Replace ledger?") },
            text = { Text("${imported.data.txs.size} transactions, ${imported.data.accounts.size} accounts. This replaces the current ledger; it does not add duplicates. A local backup is saved first." +
                if (imported.complete) "" else "\nOlder CSV: unused accounts/categories, colors, base currency and rates were not exported and cannot be restored. Text protection apostrophes are preserved.") },
            confirmButton = { TextButton(onClick = {
                try {
                    File(context.filesDir, "before-import-${System.currentTimeMillis()}.json").writeText(Store.data.toJson().toString())
                    Store.update { imported.data }
                    pending = null
                    Toast.makeText(context, "Ledger imported", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { Toast.makeText(context, "Import failed", Toast.LENGTH_LONG).show() }
            }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } })
    }
    return { if (!busy) picker.launch(arrayOf("text/*", "application/csv", "application/octet-stream")) }
}
