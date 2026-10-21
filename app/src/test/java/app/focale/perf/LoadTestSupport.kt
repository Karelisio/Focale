package app.focale.perf

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import app.focale.core.database.DatabaseMigrations
import app.focale.core.database.FocaleDatabase
import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue

/**
 * Base des tests de charge, ouverte comme en production (`AppModule.database`) : fichier sur
 * disque (journal WAL), migrations, exécuteurs de Room ; pas de requêtes sur le fil principal.
 */
fun openLoadTestDatabase(context: Context, name: String = "focale-perf.db"): FocaleDatabase {
    context.deleteDatabase(name)
    return Room.databaseBuilder(context, FocaleDatabase::class.java, name)
        .addMigrations(*DatabaseMigrations.ALL)
        .build()
}

/**
 * Écrit l'index des médias de [library] par les DAO de l'application, en une transaction (mise en
 * place des tests ; la synchronisation elle-même est mesurée par `MediaSyncLoadTest`).
 */
suspend fun FocaleDatabase.insertMedia(library: SyntheticLibrary) = withTransaction {
    library.media.chunked(1_000).forEach { chunk -> mediaDao().upsert(chunk) }
}

/**
 * Écrit les résultats d'analyse de [library] par les DAO de l'application, en une transaction :
 * analyse principale et étiquettes (lots de 500, limite de paramètres SQLite), texte, visages,
 * personnes et favoris.
 */
suspend fun FocaleDatabase.insertAnalysis(library: SyntheticLibrary) = withTransaction {
    val labelsByMedia = library.labels.groupBy { it.mediaId }
    library.analyses.chunked(500).forEach { chunk ->
        analysisDao().save(chunk, chunk.flatMap { labelsByMedia[it.mediaId].orEmpty() })
    }
    library.texts.chunked(1_000).forEach { chunk -> textDao().upsert(chunk) }
    library.persons.forEach { faceDao().insertPerson(it) }
    library.faces.chunked(1_000).forEach { faceDao().insertFaces(it) }
    library.faceScans.chunked(1_000).forEach { faceDao().upsertScans(it) }
    favoriteDao().insert(library.favorites)
}

/**
 * Mesures d'un test de charge : durées en millisecondes, résumées par leur médiane (la machine
 * peut être chargée par ailleurs) et comparées à un budget large, plusieurs fois la mesure
 * locale, pour détecter une régression sans rendre la CI instable.
 *
 * Chaque mesure est affichée et ajoutée à `build/reports/performance/mesures.tsv`.
 */
class LoadReport(private val suite: String) {

    /** Durées de [repeat] exécutions de [block] (en ms), après [warmup] exécutions non comptées. */
    inline fun samples(repeat: Int, warmup: Int = 1, block: (Int) -> Unit): DoubleArray {
        repeat(warmup) { block(-1 - it) }
        return DoubleArray(repeat) { index ->
            val start = System.nanoTime()
            block(index)
            (System.nanoTime() - start) / 1e6
        }
    }

    /** Durée (ms) d'une seule exécution de [block]. */
    inline fun once(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1e6
    }

    /** Consigne la médiane de [samples] et vérifie qu'elle tient dans [budgetMs]. */
    fun check(name: String, samples: DoubleArray, budgetMs: Double, detail: String = ""): Double {
        val sorted = samples.sorted()
        val median = sorted[sorted.size / 2]
        val p90 = sorted[((sorted.size - 1) * 0.9).toInt()]
        record(name, median, budgetMs, "n=${sorted.size} min=${format(sorted.first())} p90=${format(p90)} max=${format(sorted.last())} total=${format(sorted.sum())} $detail")
        assertTrue("$suite / $name : médiane ${format(median)} ms au-delà du budget de ${format(budgetMs)} ms", median <= budgetMs)
        return median
    }

    /** Consigne une durée unique et vérifie qu'elle tient dans [budgetMs]. */
    fun check(name: String, millis: Double, budgetMs: Double, detail: String = ""): Double {
        record(name, millis, budgetMs, detail)
        assertTrue("$suite / $name : ${format(millis)} ms au-delà du budget de ${format(budgetMs)} ms", millis <= budgetMs)
        return millis
    }

    /** Consigne une valeur sans budget (estimation, information). */
    fun note(name: String, value: Double, unit: String = "ms", detail: String = "") {
        line("$suite\t$name\t${format(value)}\t$unit\t-\t$detail")
    }

    private fun record(name: String, millis: Double, budgetMs: Double, detail: String) {
        line("$suite\t$name\t${format(millis)}\tms\t${format(budgetMs)}\t$detail")
    }

    private fun line(text: String) {
        println("PERF\t$text")
        runCatching {
            OUTPUT.parentFile?.mkdirs()
            OUTPUT.appendText("${System.currentTimeMillis()}\t$text\n")
        }
    }

    companion object {
        /** Dossier courant des tests : le module app. */
        val OUTPUT = File("build/reports/performance/mesures.tsv")

        fun format(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
    }
}
