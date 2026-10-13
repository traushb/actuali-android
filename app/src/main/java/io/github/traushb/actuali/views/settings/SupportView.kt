package io.github.traushb.actuali.views.settings

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.services.diagnostics.DiagnosticLog
import io.github.traushb.actuali.services.diagnostics.DiagnosticReport
import io.github.traushb.actuali.services.diagnostics.DiagnosticReportBuilder
import io.github.traushb.actuali.services.diagnostics.DiagnosticReportEnvironment
import io.github.traushb.actuali.services.diagnostics.DiagnosticSettingsSnapshot
import io.github.traushb.actuali.views.LocalBudgetStore
import io.github.traushb.actuali.views.LocalNavStack
import io.github.traushb.actuali.views.Route
import io.github.traushb.actuali.views.components.AppTopBar
import io.github.traushb.actuali.views.components.DelayedLoadingIndicator
import io.github.traushb.actuali.views.components.FormColumn
import io.github.traushb.actuali.views.components.FormIcon
import io.github.traushb.actuali.views.components.FormRow
import io.github.traushb.actuali.views.components.FormSection
import io.github.traushb.actuali.views.components.RefreshBox
import kotlinx.coroutines.launch

private const val SUPPORT_SITE_URL = "https://github.com/traushb/actuali-android/issues"
private const val GITHUB_URL = "https://github.com/traushb/actuali-android"

@Composable
fun SupportView() {
    val context = LocalContext.current
    val nav = LocalNavStack.current
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Scaffold(topBar = { AppTopBar(L("Support")) }) { padding ->
        FormColumn(padding) {
            FormSection(L("Help & Links")) {
                FormRow(L("Help & FAQ"), Modifier.testTag("support.website"), leading = { FormIcon(Icons.AutoMirrored.Filled.HelpOutline) }, onClick = { open(SUPPORT_SITE_URL) })
                FormRow(L("GitHub"), Modifier.testTag("support.github"), leading = { FormIcon(Icons.Filled.Code) }, onClick = { open(GITHUB_URL) })
                FormRow(
                    L("Diagnostic Report"),
                    Modifier.testTag("support.diagnosticReport"),
                    leading = { FormIcon(Icons.Filled.Description) },
                    navigates = true,
                    onClick = { nav.push(Route.DiagnosticReport) },
                )
            }
        }
    }
}

/**
 * The privacy-safe support report: settings, device, connection and sync
 * state, and the session's diagnostic events — shared as a text file or
 * copied, never sent anywhere by the app itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticReportView() {
    val store = LocalBudgetStore.current
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    var report by remember { mutableStateOf<DiagnosticReport?>(null) }
    var copied by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(generation) {
        report = DiagnosticReportBuilder.make(DiagnosticLog.shared.snapshot(), DiagnosticSettingsSnapshot.capture(store), DiagnosticReportEnvironment.capture(context))
        copied = false
        refreshing = false
    }

    Scaffold(
        topBar = {
            AppTopBar(L("Diagnostic Report"), actions = {
                report?.let { current ->
                    IconButton(onClick = {
                        // As a text file attachment where the target takes one, plain text otherwise.
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, current.filename).putExtra(Intent.EXTRA_TEXT, current.text)
                        context.startActivity(Intent.createChooser(send, L("Share Diagnostic Report")))
                    }, modifier = Modifier.testTag("diagnosticReport.share")) { Icon(Icons.Filled.Share, contentDescription = L("Share Diagnostic Report")) }
                    IconButton(onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(current.filename, current.text)))
                            copied = true
                        }
                    }, modifier = Modifier.testTag("diagnosticReport.copy")) {
                        Icon(
                            if (copied) Icons.Outlined.CheckCircle else Icons.Filled.ContentCopy,
                            contentDescription = L(if (copied) "Diagnostic Report Copied" else "Copy Diagnostic Report"),
                        )
                    }
                }
            })
        },
    ) { padding ->
        RefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                generation++
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            val current = report
            if (current == null) {
                DelayedLoadingIndicator(Modifier.fillMaxSize(), contained = true)
            } else {
                SelectionContainer {
                    Text(
                        current.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(16.dp)
                            .testTag("diagnosticReport.text"),
                    )
                }
            }
        }
    }
}
