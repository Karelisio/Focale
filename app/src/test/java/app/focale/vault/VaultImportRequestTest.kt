package app.focale.vault

import app.focale.data.OperationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Demandes d'import confiées à WorkManager : elles survivent telles quelles à la fin du processus. */
class VaultImportRequestTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `chaque demande se relit à l'identique, y compris une très grande sélection`() {
        val requests = listOf(
            VaultImportRequest.Selection(List(5_000) { it * 3L + 1 }, albumId = 7, createdAlbumId = 7),
            VaultImportRequest.Selection(listOf(42L), albumId = null),
            VaultImportRequest.Folder(bucketId = -540_528_482L, name = "WhatsApp Images"),
            VaultImportRequest.UserAlbum(albumId = 3, vaultAlbumId = 9, createdAlbumId = 9),
            VaultImportRequest.NewFromSource(vaultAlbumId = 4),
        )
        for (request in requests) {
            val data = VaultImports.encode(request, folder.root)
            // Données de travail sous la limite de WorkManager, quelle que soit la sélection.
            assertTrue(data.toByteArray().size < 10 * 1024)
            assertEquals(request, VaultImports.decode(data))
        }
    }

    @Test
    fun `une sélection dont le fichier a disparu n'est pas importée`() {
        val data = VaultImports.encode(VaultImportRequest.Selection(listOf(1L, 2L), albumId = null), folder.root)
        folder.root.listFiles()!!.forEach { it.delete() }
        assertNull(VaultImports.decode(data))
    }

    @Test
    fun `le bilan de l'import se relit à l'identique`() {
        val results = listOf(
            OperationResult.Success(12),
            OperationResult.Partial(10, 2),
            OperationResult.Cancelled,
        )
        results.forEach { assertEquals(it, VaultImports.resultOf(VaultImports.outputOf(it))) }
        assertTrue(VaultImports.resultOf(VaultImports.outputOf(OperationResult.Failure())) is OperationResult.Failure)
    }
}
