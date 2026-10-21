package app.focale.vault

import android.Manifest
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import org.robolectric.Robolectric

/**
 * Fournisseur de documents adossé à un dossier du disque, comme celui du stockage de l'appareil :
 * le dossier choisi avec le sélecteur système, dans les tests. Les documents y sont nommés comme
 * chez Android (« nom (1).ext » si le nom est pris). [denied] simule un fournisseur qui refuse
 * désormais l'accès (support retiré, autorisation révoquée de son côté), [readOnly] un dossier
 * où rien ne peut être créé.
 */
class FolderDocumentsProvider : DocumentsProvider() {

    lateinit var directory: File

    @Volatile
    var denied = false

    @Volatile
    var readOnly = false

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT)
            .add(Root.COLUMN_DOCUMENT_ID, ROOT)
            .add(Root.COLUMN_TITLE, "Stockage de test")
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { add(this, fileOf(documentId)) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val parent = fileOf(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("Dossier absent : $parentDocumentId")
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { parent.listFiles()?.forEach { add(this, it) } }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileOf(parentDocumentId)
        if (!parent.isDirectory || readOnly) throw FileNotFoundException("Création impossible dans : $parentDocumentId")
        val base = displayName.substringBeforeLast('.')
        val extension = displayName.substringAfterLast('.', "")
        var file = File(parent, displayName)
        var copy = 1
        while (file.exists()) file = File(parent, "$base (${copy++})${if (extension.isEmpty()) "" else ".$extension"}")
        if (!file.createNewFile()) throw FileNotFoundException("Création impossible : $displayName")
        return idOf(file)
    }

    override fun deleteDocument(documentId: String) {
        if (!fileOf(documentId).delete()) throw FileNotFoundException("Suppression impossible : $documentId")
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == ROOT || documentId.startsWith("$parentDocumentId/")

    private fun add(cursor: MatrixCursor, file: File) {
        if (!file.exists()) throw FileNotFoundException("Document absent : ${file.name}")
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, idOf(file))
            .add(Document.COLUMN_DISPLAY_NAME, if (file == directory) directory.name else file.name)
            .add(Document.COLUMN_MIME_TYPE, if (file.isDirectory) Document.MIME_TYPE_DIR else VaultArchive.MIME_TYPE)
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_SIZE, file.length())
            .add(
                Document.COLUMN_FLAGS,
                when {
                    readOnly -> 0
                    file.isDirectory -> Document.FLAG_DIR_SUPPORTS_CREATE
                    else -> Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
                },
            )
    }

    private fun fileOf(id: String): File {
        if (denied) throw SecurityException("Accès refusé par le fournisseur")
        return if (id == ROOT) directory else File(directory, id.removePrefix("$ROOT/"))
    }

    private fun idOf(file: File): String = if (file == directory) ROOT else "$ROOT/${file.relativeTo(directory).path}"

    companion object {
        const val AUTHORITY = "app.focale.test.documents"
        const val ROOT = "racine"

        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS,
        )

        /** Arbre de documents du dossier entier, tel que le rend `ACTION_OPEN_DOCUMENT_TREE`. */
        val TREE: Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT)

        /** Enregistre le fournisseur, servant [directory], auprès du ContentResolver de Robolectric. */
        fun install(directory: File): FolderDocumentsProvider {
            val info = ProviderInfo().apply {
                authority = AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = Manifest.permission.MANAGE_DOCUMENTS
                writePermission = Manifest.permission.MANAGE_DOCUMENTS
            }
            return Robolectric.buildContentProvider(FolderDocumentsProvider::class.java).create(info).get().also {
                it.directory = directory
            }
        }
    }
}
