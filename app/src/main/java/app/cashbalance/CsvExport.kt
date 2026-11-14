package app.cashbalance

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate

class CsvExport(val inProgress: Boolean, val launch: () -> Unit)

/** Register at app level so switching tabs does not cancel an active export. */
@Composable
fun rememberCsvExport(data: Data): CsvExport {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val snapshot = data
            exporting = true
            scope.launch(Dispatchers.Main.immediate) {
                try {
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open export destination")
                        stream.bufferedWriter(Charsets.UTF_8).use { snapshot.writeCsv(it) }
                    }
                    Toast.makeText(context, "CSV exported", Toast.LENGTH_SHORT).show()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(context, "Couldn't export CSV. Please try again.", Toast.LENGTH_LONG).show()
                } finally {
                    exporting = false
                }
            }
        }
    }
    return CsvExport(exporting) {
        if (!exporting) {
            try {
                launcher.launch("cash-balance-${LocalDate.now()}.csv")
            } catch (e: Exception) {
                Toast.makeText(context, "Couldn't open the file picker", Toast.LENGTH_LONG).show()
            }
        }
    }
}
