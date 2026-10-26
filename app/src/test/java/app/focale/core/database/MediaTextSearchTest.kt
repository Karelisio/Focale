package app.focale.core.database

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import app.focale.core.model.MediaQuery
import app.focale.core.model.SearchTerm
import app.focale.ml.ImageAnalyzer
import app.focale.ml.LabelDictionary
import app.focale.ml.text.RecognizedText
import app.focale.ml.text.TextRecognitionStep
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Texte reconnu cherché par l'index plein texte (`media_text_fts`, [MediaTexts]) sur un corpus de
 * textes lus dans des photos et des captures (tickets, factures, réseaux Wi-Fi, billets, panneaux,
 * notifications) : mêmes résultats que l'ancien `LIKE '%mot%'` pour les cas courants (mots
 * entiers, débuts de mots, accents, majuscules, nombres, mots collés à la ponctuation), et la seule
 * différence restante, un mot trouvé au milieu d'un autre quand d'autres textes ont un mot qui
 * commence ainsi (docs/PERFORMANCE.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaTextSearchTest {

    private lateinit var database: FocaleDatabase

    private val texts = listOf(
        "FACTURE N° 2024-118\nDate : 12/03/2024\nTotal TTC : 42,00 €\nTVA 20 % : 7,00 €",
        "Réseau Wi-Fi : Livebox-A1B2\nMot de passe : 7F3K9QZ",
        "WIFI gratuit\nCode : CAFE2024",
        "MENU DU JOUR\nPlat · Dessert · Café\nEntrée + plat 14,90€",
        "Crème brûlée maison",
        "TICKET DE CAISSE\nCARREFOUR MARKET\nBAGUETTE 1,10\nLAIT DEMI-ÉCRÉMÉ 0,95\nTOTAL 2,05 EUR\nCB ****1234",
        "Rendez-vous chez le dentiste lundi 14h30",
        "(Facture) acquittée le 05/01/2023",
        "Total: 18,50€ — Merci de votre visite !",
        "L'été à Nice : 25°C, ensoleillé",
        "Envoyer un e-mail à contact@exemple.fr",
        "www.sncf-connect.com\nBillet TGV INOUI Paris → Nantes\nVoiture 12 Place 45",
        "Tél. 06 12 34 56 78",
        "#vacances2024 #plage @alice",
        "Attestation d'assurance habitation 2024-2025",
        "RIB\nIBAN FR76 3000 6000 0112 3456 7890 189\nBIC AGRIFRPP",
        "Sortie de secours",
        "PARKING — Niveau -2\nPlace B17",
        "Notifications\nBatterie faible : 15 %\nMise à jour disponible",
        "Paramètres > Wi-Fi > Réseaux enregistrés",
        "Pharmacie de garde\nOuvert 24h/24",
        "Ordonnance du Dr Martin\nDoliprane 1000 mg",
        "Departure 10:45 Gate B12\nBoarding pass\nFlight AF1234",
        "Welcome to London\nPlease mind the gap",
        "Facturation : vos factures de mars sont disponibles",
        "N°20240512 — Commande expédiée",
        "Réf. AB2024-77 Quantité 3",
        "Horaires d'ouverture : 9h-12h / 14h-19h",
        "Bon anniversaire Léa !",
        "Œuf à la coque, pâtes à l'italienne",
        "Musée d'Orsay — Entrée gratuite le 1er dimanche",
        "Code promo: SUMMER24",
        "Surface habitable : 72 m²",
        "Départ 8h15 Quai 3 Voie B",
        "Fichier téléchargé : rapport_final.pdf",
        "Message de Maman : on arrive à 19h",
        "Le chat est sur le canapé",
        "Livraison prévue demain entre 8h et 13h",
        "",
        "Dossier de candidature — Pièces justificatives",
    )

    /** Clés de recherche des textes, par média (identifiant = rang + 1), comme les écrit l'étape « texte ». */
    private val keys: Map<Long, String> = texts.withIndex().associate { (index, text) -> index + 1L to RecognizedText.searchKey(text) }

    @Before
    fun open() = runBlocking<Unit> {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FocaleDatabase::class.java).build()
        database.textDao().upsert(
            texts.mapIndexed { index, text -> MediaTextEntity(index + 1L, 0, TextRecognitionStep.VERSION, text, RecognizedText.searchKey(text)) },
        )
        // Médias de l'index : la condition porte sur m.id.
        database.mediaDao().upsert(texts.indices.map { media(it + 1L) })
    }

    @After
    fun close() = database.close()

    @Test
    fun `les cas courants trouvent les mêmes photos qu'avant`() = runBlocking<Unit> {
        // Mots entiers et débuts de mots (trois lettres ou plus) des textes, et saisies telles qu'un
        // utilisateur les tape : majuscules, accents, nombres, mots collés à la ponctuation.
        val tokens = keys.values.flatMap { it.split(' ') }.filter { it.isNotEmpty() }.toSet()
        val beginnings = tokens.flatMap { token -> (3..token.length).map { token.take(it) } }.toSet()
        val typed = listOf(
            "FACTURÉ", "Facture", "factures", "Été", "ete", "CAFÉ", "BRÛLÉE", "Œuf", "Réseau", "wi-fi", "WiFi",
            "2024", "2023", "118", "2024118", "0612345678", "14h30", "af1234", "summer24",
            "(facture)", "total:", "l'été", "e-mail", "contact@exemple.fr", "#plage", "@alice", "sncf", "livebox",
        )
        val queries = (tokens + beginnings + typed.map(LabelDictionary::normalize)).filter { it.isNotEmpty() }.toSortedSet()
        assertTrue(queries.size > 300)
        for (query in typed.map(LabelDictionary::normalize)) assertEquals(query, like(query), found(query))
        // Seule différence : un mot ou un début de mot contenu aussi au milieu d'un mot d'un autre
        // texte, dont la photo n'est plus trouvée quand des textes ont un mot qui commence ainsi.
        val changed = queries.filter { query -> found(query) != like(query) }
        println("MOTS\t${queries.size} requêtes, ${changed.size} changées : $changed")
        assertEquals(EXPECTED_CHANGES, changed.filter { it.length >= 4 })
    }

    @Test
    fun `début de mot par l'index, sinon n'importe où comme avant`() = runBlocking<Unit> {
        // Toutes les sous-chaînes des mots des textes, de 1 à 6 caractères : par début de mot si un
        // texte en a un, sinon LIKE ; jamais plus que LIKE.
        val substrings = keys.values.flatMap { it.split(' ') }.flatMap { token ->
            (token.indices).flatMap { from -> (from + 1..minOf(token.length, from + 6)).map { token.substring(from, it) } }
        }.toSortedSet()
        for (query in substrings + listOf("wi fi", "wi f", "mot de pa", "zzz", "fact 2")) {
            val prefix = keys.filterValues { key -> (" $key").contains(" $query") }.keys.sorted()
            assertEquals(query, prefix.ifEmpty { like(query) }, found(query))
            assertTrue(query, like(query).containsAll(found(query)))
        }
        // « fi » : le mot « fi » de « Wi-Fi », pas « wifi » ; « ture » : n'importe où, aucun mot ne commençant ainsi.
        assertEquals(listOf(2L, 20L, 35L), found("fi"))
        assertEquals(listOf(1L, 8L, 12L, 23L, 25L, 28L, 40L), found("ture"))
    }

    @Test
    fun `le repli sur LIKE ne sert qu'aux termes que rien d'autre ne trouve`() = runBlocking<Unit> {
        // « ture » seul : aucun mot ne commence ainsi, d'où le LIKE.
        assertEquals(listOf(1L, 8L, 12L, 23L, 25L, 28L, 40L), found("ture"))
        // Une étiquette du terme répond (photo sans texte) : plus de repli, le terme ne trouve qu'elle.
        database.analysisDao().save(
            listOf(MediaAnalysisEntity(39, 0, ImageAnalyzer.VERSION, null, null, null, null, null, null, null)),
            listOf(MediaLabelEntity(39, "Receipt", 0.9f)),
        )
        assertEquals(listOf(39L), found(SearchTerm(labels = listOf("Receipt"), text = "ture")))
        // Étiquette d'un média dans la corbeille seulement : rien d'autre ne répond, repli.
        database.mediaDao().upsert(listOf(media(39).copy(isTrashed = true)))
        assertEquals(listOf(1L, 8L, 12L, 23L, 25L, 28L, 40L), found(SearchTerm(labels = listOf("Receipt"), text = "ture")))
    }

    @Test
    fun `l'index suit les textes relus, réécrits et purgés`() = runBlocking<Unit> {
        assertEquals(listOf(1L, 8L, 25L), found("factur"))
        val dao = database.textDao()
        dao.upsert(listOf(MediaTextEntity(8, 1, TextRecognitionStep.VERSION, "Reçu", RecognizedText.searchKey("Reçu"))))
        dao.upsert(listOf(MediaTextEntity(41, 1, TextRecognitionStep.VERSION, "Facture EDF", RecognizedText.searchKey("Facture EDF"))))
        database.mediaDao().upsert(listOf(media(41)))
        assertEquals(listOf(1L, 25L, 41L), found("factur"))
        assertEquals(listOf(8L), found("recu"))
        database.mediaDao().deleteByIds(listOf(1L, 41L))
        database.mediaDao().deleteOrphanTexts()
        assertEquals(listOf(25L), found("factur"))
        assertEquals(0L, ids("SELECT docid FROM media_text_fts WHERE media_text_fts MATCH '\"edf*\"'").size.toLong())
    }

    /**
     * Médias trouvés pour le mot [key] (déjà normalisé), par identifiant croissant : leurs noms ne
     * contiennent ni lettre ni chiffre, seul compte le texte reconnu.
     */
    private suspend fun found(key: String): List<Long> = found(SearchTerm(text = key))

    private suspend fun found(term: SearchTerm): List<Long> =
        database.mediaDao().ids(MediaQueryBuilder.ids(MediaQuery(terms = listOf(term)))).sorted()

    /** Médias dont la clé contient [key] selon `LIKE`, comme avant l'index. */
    private suspend fun like(key: String): List<Long> = database.mediaDao().ids(
        SimpleSQLiteQuery(
            "SELECT media_id FROM media_text WHERE text_key LIKE ? ESCAPE '\\' ORDER BY media_id",
            arrayOf(MediaQueryBuilder.likePattern(key)),
        ),
    )

    private suspend fun ids(sql: String): List<Long> = database.mediaDao().ids(SimpleSQLiteQuery(sql))

    private fun media(id: Long) = MediaEntity(
        id = id, mediaType = 1, mimeType = "image/jpeg", displayName = "§", relativePath = "DCIM/Camera/",
        bucketId = 42, bucketName = "Camera", dateTaken = id * 1_000, dateAdded = id * 1_000, dateModified = 1,
        generation = id, size = 1, width = 1, height = 1, orientation = 0, duration = 0, volumeName = "external_primary",
        isScreenshot = false, isGif = false, isTrashed = false, dateExpires = 0,
    )

    private companion object {
        /**
         * Mots et débuts de mots d'au moins quatre caractères dont le résultat change : « 1234 » et
         * « 3456 » au milieu d'un numéro, « atte » dans « batterie », « crème » dans « écrémé »,
         * « emai » dans « demain », « nive » dans « anniversaire ».
         */
        val EXPECTED_CHANGES = listOf("1234", "3456", "atte", "crem", "creme", "emai", "nive")
    }
}
