package app.focale.backup

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException

/**
 * Dossier choisi avec le sélecteur système (`ACTION_OPEN_DOCUMENT_TREE`), parcouru et écrit par
 * DocumentsContract avec l'autorisation persistante accordée à Focale : stockage de l'appareil,
 * carte SD, clé USB ou fournisseur d'une autre application.
 */
internal class BackupFolder(private val resolver: ContentResolver, private val tree: Uri) {

    /** Document du dossier ; [lastModified] vaut 0 si le fournisseur ne la donne pas. */
    class Entry(val id: String, val name: String, val lastModified: Long)

    private val treeId: String = DocumentsContract.getTreeDocumentId(tree)

    /** Autorisation d'écriture toujours accordée (elle se perd avec les données de Focale ou du fournisseur). */
    fun permitted(): Boolean = resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    /**
     * Documents du dossier.
     *
     * @throws Exception dossier inaccessible : supprimé, support retiré, autorisation révoquée…
     */
    fun list(): List<Entry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        // Requête par Bundle : la forme que tous les fournisseurs de documents comprennent.
        val cursor = resolver.query(children, PROJECTION, null, null) ?: throw FileNotFoundException("Dossier illisible")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    add(Entry(id, it.getString(1).orEmpty(), if (it.isNull(2)) 0 else it.getLong(2)))
                }
            }
        }
    }

    /**
     * Crée le document [name], vide ; le fournisseur le renomme si le nom est pris
     * (« nom (1).focale »).
     *
     * @throws Exception création impossible.
     */
    fun create(name: String): Uri =
        DocumentsContract.createDocument(resolver, document(treeId), PasswordArchive.MIME_TYPE, name)
            ?: throw FileNotFoundException("Création impossible dans le dossier")

    /** Supprime le document [id] ; faux s'il est resté en place. */
    fun delete(id: String): Boolean = DocumentsContract.deleteDocument(resolver, document(id))

    /** Nom du dossier tel que l'affiche le fournisseur, null s'il ne peut pas être lu. */
    fun displayName(): String? = runCatching {
        resolver.query(document(treeId), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Le dossier accepte de nouveaux documents (il n'est ni en lecture seule ni illisible). */
    fun canCreate(): Boolean = runCatching {
        resolver.query(document(treeId), arrayOf(Document.COLUMN_FLAGS), null, null)?.use {
            it.moveToFirst() && (it.getInt(0) and Document.FLAG_DIR_SUPPORTS_CREATE) != 0
        }
    }.getOrNull() == true

    private fun document(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    companion object {
        /** Lecture et écriture, gardées après le redémarrage de l'appareil. */
        const val PERMISSIONS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        private val PROJECTION = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED)

        /** Identifiant du document [uri], tel que le donne la liste du dossier. */
        fun documentId(uri: Uri): String = DocumentsContract.getDocumentId(uri)

        /** Arbre de documents valide, tel que le rend le sélecteur de dossier. */
        fun isTree(uri: Uri): Boolean = DocumentsContract.isTreeUri(uri)

        /** Document du dossier [tree] lui-même : emplacement initial du sélecteur, qui n'accepte pas l'arbre seul. */
        fun folderDocument(tree: Uri): Uri? = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        }.getOrNull()
    }
}
