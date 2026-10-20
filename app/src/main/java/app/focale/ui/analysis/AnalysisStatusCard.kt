package app.focale.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.ml.AnalysisKind
import app.focale.work.AnalysisStatus

/** Carte de l'onglet Recherche : état de l'analyse tant qu'elle n'est pas terminée. */
@Composable
fun AnalysisStatusCard(status: AnalysisStatus, onRunNow: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.extraLarge, color = colors.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(colors.primaryContainer),
                ) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = colors.onPrimaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.search_analysis_title), style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(
                        analysisStatusText(status),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            AnalysisStatusContent(status = status, onRunNow = onRunNow)
        }
    }
}

/**
 * Avancement de chaque analyse (étiquettes et lieux, texte, visages, recherche par le
 * sens) et, tant qu'il reste du travail sans passage en cours, « Analyser maintenant ».
 */
@Composable
fun AnalysisStatusContent(status: AnalysisStatus, onRunNow: () -> Unit, modifier: Modifier = Modifier) {
    // Les analyses s'enchaînent : pendant un passage, seule la première inachevée avance.
    val mainActive = status.running && status.main.analyzed < status.main.total
    val activeStep = if (status.running && !mainActive) status.steps.indexOfFirst { !it.complete } else -1
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnalysisRow(
            label = stringResource(R.string.analysis_step_main),
            done = status.main.analyzed,
            total = status.main.total,
            active = mainActive,
        )
        status.steps.forEachIndexed { index, step ->
            AnalysisRow(label = stringResource(step.kind.label), done = step.done, total = step.total, active = index == activeStep)
        }
        if (!status.complete && !status.running) {
            FilledTonalButton(onClick = onRunNow, shapes = ButtonDefaults.shapes(), modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.analysis_run_now), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** Phrase d'état : terminée, en cours ou en attente (avec sa cause probable). */
@Composable
fun analysisStatusText(status: AnalysisStatus): String = stringResource(
    when {
        status.complete -> R.string.analysis_status_complete
        status.running -> R.string.analysis_status_running
        status.onlyWhileCharging -> R.string.analysis_status_waiting_charging
        else -> R.string.analysis_status_waiting
    },
)

@Composable
private fun AnalysisRow(label: String, done: Int, total: Int, active: Boolean) {
    val fraction = if (total == 0) 1f else (done.toFloat() / total).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                text = if (done >= total) stringResource(R.string.analysis_step_done) else stringResource(R.string.analysis_count, done, total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Ondulée pour l'analyse en cours, plate en attente ou une fois terminée.
        if (active) {
            LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

private val AnalysisKind.label: Int
    get() = when (this) {
        AnalysisKind.TEXT -> R.string.analysis_step_text
        AnalysisKind.FACES -> R.string.analysis_step_faces
        AnalysisKind.SEMANTIC -> R.string.analysis_step_semantic
    }
