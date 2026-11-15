package app.cashbalance

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
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
        var replace by remember(imported) { mutableStateOf(false) }
        val merged = remember(imported, Store.data) { Store.data.appendImport(imported.data) }
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("Import CSV") },
            text = { Column {
                Text("${imported.data.txs.size} transactions, ${imported.data.accounts.size} accounts.")
                Row(Modifier.selectable(selected = !replace, role = Role.RadioButton, onClick = { replace = false }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !replace, onClick = null); Text("Add to current ledger")
                }
                Row(Modifier.selectable(selected = replace, role = Role.RadioButton, onClick = { replace = true }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = replace, onClick = null); Text("Replace entire ledger")
                }
                Text(if (replace) "All current accounts, transactions and settings will be replaced."
                    else "${merged.added} transactions to add, ${merged.skipped} exact matches to skip. Current transactions and settings are kept.")
                Text("A local backup is saved before import.")
                if (!imported.complete) Text("Older CSV: unused accounts/categories, colors, base currency and rates were not exported. Text protection apostrophes are preserved.")
            } },
            confirmButton = { TextButton(onClick = {
                try {
                    File(context.filesDir, "before-import-${System.currentTimeMillis()}.json").writeText(Store.data.toJson().toString())
                    Store.update { if (replace) imported.data else it.appendImport(imported.data).data }
                    pending = null
                    Toast.makeText(context, "Ledger imported", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { Toast.makeText(context, "Import failed", Toast.LENGTH_LONG).show() }
            }) { Text(if (replace) "Replace" else "Add") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } })
    }
    return { if (!busy) picker.launch(arrayOf("text/*", "application/csv", "application/octet-stream")) }
}
