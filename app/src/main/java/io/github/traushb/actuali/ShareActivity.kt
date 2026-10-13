package io.github.traushb.actuali

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.traushb.actuali.services.BudgetStore
import io.github.traushb.actuali.services.PendingImportStore
import io.github.traushb.actuali.services.SharedTextImport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Receives a bank message from the share sheet and queues it without opening
 * the app, so the person stays in the messages app; a toast confirms.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        lifecycleScope.launch {
            val message = try {
                val pending = SharedTextImport.queue(text, BudgetStore.shared, PendingImportStore.shared)
                SharedTextImport.confirmationText(pending.amount, pending.payee)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.localizedMessage
            }
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
