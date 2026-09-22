package jo.codeide.core.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File

/**
 * Fournisseur de documents SAF **de test** : une arborescence en mémoire
 * adossée à des fichiers temporaires pour les flux, branchée sur
 * `ShadowContentResolver.registerProvider`.
 *
 * Il respecte les chemins d'appel **réels** du framework :
 * - `query` sur une URI de document (`…/tree/T/doc/D`) décrit D ;
 * - `query` sur une URI d'enfants (`…/tree/T/children/P`) liste les
 *   enfants directs de P ;
 * - `call("android:createDocument", …)` crée un enfant (c'est le chemin
 *   utilisé par `DocumentsContract.createDocument`) et retourne l'URI
 *   dans `DocumentsContract.EXTRA_URI` ;
 * - `call("android:deleteDocument", …)` supprime le document ;
 * - `openFile` sert les octets du fichier temporaire sous-jacent, en
 *   lecture comme en écriture tronquée.
 *
 * Le drapeau [provoquerRenommage] simule le renommage silencieux d'
 * `createDocument` en collision (section 5.6) : le document créé reçoit
 * un suffixe — de quoi éprouver le contrôle du nom retourné.
 */
class FauxFournisseurDocuments : ContentProvider() {
    /** Autorité du fournisseur de test. */
    val autorite = "jo.codeide.test.documents"

    /** Noeud de l'arborescence : dossier ou fichier adossé à un fichier temporaire. */
    data class Noeud(
        val parent: String,
        val nom: String,
        val estDossier: Boolean,
        val mime: String,
        val fichier: File?,
    )

    /** Racine de l'arborescence, déjà présente. */
    val racine = "racine"

    private val noeuds = LinkedHashMap<String, Noeud>()

    /** Compteur d'horodatage croissant : dates de modification distinctes. */
    private var horloge = 0L

    /** Quand `true`, `createDocument` renomme en cas de collision (suffixe « (1) »). */
    var provoquerRenommage = false

    /** Nombre d'appels `call` reçus (assertions de la section 5.6 : requêtes groupées). */
    var appelsCall = 0
        private set

    /** Nombre de requêtes `query` reçues. */
    var requetesQuery = 0
        private set

    private fun dossierTravail(): File = File(context!!.cacheDir, "faux-documents").apply { mkdirs() }

    init {
        noeuds[racine] = Noeud(racine, "racine", true, DocumentsContract.Document.MIME_TYPE_DIR, null)
    }

    /** URI d'arborescence de la racine (à présenter au sélecteur fictif). */
    fun uriArbre(): Uri = DocumentsContract.buildTreeDocumentUri(autorite, racine)

    /** URI de document de la racine. */
    fun uriDocument(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(uriArbre(), id)

    /** Amorce un dossier (arbre initial du test). */
    fun semerDossier(
        parent: String,
        nom: String,
    ): String {
        val id = "$parent/$nom"
        noeuds[id] = Noeud(parent, nom, true, DocumentsContract.Document.MIME_TYPE_DIR, null)
        return id
    }

    /** Amorce un fichier avec son contenu (arbre initial du test). */
    fun semerFichier(
        parent: String,
        nom: String,
        mime: String,
        octets: ByteArray,
    ): String {
        val id = "$parent/$nom"
        val fichier = File(dossierTravail(), "semis-${noeuds.size}-$nom")
        fichier.writeBytes(octets)
        noeuds[id] = Noeud(parent, nom, false, mime, fichier)
        return id
    }

    /** Contenu courant d'un fichier amorcé ou créé. */
    fun octetsDe(id: String): ByteArray? = noeuds[id]?.fichier?.readBytes()

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        requetesQuery++
        val colonnes = projection ?: PROJECTION_DEFAUT

        // Formes modernes (API 26+) :
        //   document : …/tree/<arbre>/document/<id>…
        //   enfants  : …/tree/<arbre>/document/<id…>/children
        val description = decrireRequete(uri) ?: return null
        return when (description) {
            is Requete.Description -> {
                MatrixCursor(colonnes).apply {
                    noeuds[description.id]?.let { ajouterLigne(it, uriDocument(description.id).toString()) }
                }
            }

            is Requete.Enfants -> {
                val parent = noeuds[description.idParent]
                if (parent == null || !parent.estDossier) {
                    // Dossier disparu : le vrai fournisseur échoue en
                    // FileNotFoundException, pas un listing vide.
                    throw java.io.FileNotFoundException(description.idParent)
                }
                MatrixCursor(colonnes).apply {
                    noeuds.values
                        .filter { it.parent == description.idParent }
                        .forEach { noeud ->
                            ajouterLigne(noeud, uriDocument("${noeud.parent}/${noeud.nom}").toString())
                        }
                }
            }
        }
    }

    /** Nature d'une requête entrante : description unitaire ou enfants. */
    private sealed interface Requete {
        /** Décrit le document [id]. */
        data class Description(
            val id: String,
        ) : Requete

        /** Liste les enfants du dossier [idParent]. */
        data class Enfants(
            val idParent: String,
        ) : Requete
    }

    @Suppress("ReturnCount") // Double de test : clauses de garde d'analyse d'URI.
    private fun decrireRequete(uri: Uri): Requete? {
        val segments = uri.pathSegments ?: return null
        if (segments.size < 4 || segments[0] != SEGMENT_TREE || segments[2] != SEGMENT_DOCUMENT) return null
        val estEnfants = segments.last() == SEGMENT_CHILDREN
        val idBrut =
            if (estEnfants) {
                segments.subList(3, segments.size - 1).joinToString("/")
            } else {
                segments.subList(3, segments.size).joinToString("/")
            }
        return if (estEnfants) Requete.Enfants(idBrut) else Requete.Description(idBrut)
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    @Suppress("ReturnCount") // Double de test : le protocole d'appel se lit en clauses de garde.
    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        appelsCall++
        if (extras == null) return null

        // Protocole d'appel réel du framework (vérifié sur le bytecode
        // d'android-all) : la cible voyage dans les extras sous la clé
        // "uri" ; les noms de méthodes et de colonnes sont des littéraux
        // cachés du SDK, dupliqués ici à l'identique.
        val cible =
            extras.getParcelable(EXTRA_URI_PROTOCOLE, Uri::class.java) ?: return null
        val description = decrireRequete(cible) as? Requete.Description ?: return null
        val idCible = description.id

        return when (method) {
            METHODE_CREATION -> creerDocument(idCible, extras)
            METHODE_SUPPRESSION -> supprimerDocument(idCible)
            else -> null
        }
    }

    /** Crée un enfant de [idCible] selon les extras du protocole createDocument. */
    @Suppress("ReturnCount") // Double de test : clauses de garde du protocole.
    private fun creerDocument(
        idCible: String,
        extras: Bundle,
    ): Bundle? {
        val nomDemande = extras.getString(COLONNE_NOM_AFFICHE) ?: return null
        val mime = extras.getString(COLONNE_TYPE_MIME) ?: return null

        // Renommage silencieux simulé : le fournisseur choisit un autre
        // nom en cas de collision (section 5.6).
        val nomFinal =
            if (provoquerRenommage && existeEnfant(idCible, nomDemande)) {
                "$nomDemande (1)"
            } else {
                nomDemande
            }
        if (!provoquerRenommage) {
            require(!existeEnfant(idCible, nomDemande)) {
                "Collision : $nomDemande existe déjà dans $idCible."
            }
        }

        val id = "$idCible/$nomFinal"
        val fichier =
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                null
            } else {
                File(dossierTravail(), "cree-${noeuds.size}-$nomFinal")
            }
        noeuds[id] = Noeud(idCible, nomFinal, mime == DocumentsContract.Document.MIME_TYPE_DIR, mime, fichier)

        return Bundle().apply {
            putParcelable(EXTRA_URI_PROTOCOLE, uriDocument(id))
        }
    }

    /** Supprime en cascade le document [idCible] (protocole deleteDocument). */
    private fun supprimerDocument(idCible: String): Bundle? {
        supprimerEnCascade(idCible)
        return null
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? {
        val description =
            decrireRequete(uri) as? Requete.Description
                ?: throw java.io.FileNotFoundException(uri.toString())
        val id = description.id
        val noeud = noeuds[id] ?: throw java.io.FileNotFoundException(id)

        val fichier = noeud.fichier ?: throw java.io.FileNotFoundException("$id est un dossier.")
        fichier.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(fichier, ParcelFileDescriptor.parseMode(mode))
    }

    override fun openAssetFile(
        uri: Uri,
        mode: String,
    ): AssetFileDescriptor? =
        openFile(uri, mode)?.let {
            AssetFileDescriptor(it, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }

    private fun existeEnfant(
        parent: String,
        nom: String,
    ): Boolean = noeuds.values.any { it.parent == parent && it.nom.equals(nom, ignoreCase = true) }

    private fun supprimerEnCascade(id: String) {
        val cibles = noeuds.keys.filter { it == id || it.startsWith("$id/") }
        cibles.forEach { cle ->
            noeuds.remove(cle)?.fichier?.delete()
        }
    }

    private fun MatrixCursor.ajouterLigne(
        noeud: Noeud,
        uri: String,
    ) {
        addRow(
            arrayOf(
                DocumentsContract.getDocumentId(Uri.parse(uri)),
                noeud.nom,
                noeud.mime,
                (noeud.fichier?.length() ?: 0L).coerceAtLeast(0L),
                ++horloge,
            ),
        )
    }

    private companion object {
        private val PROJECTION_DEFAUT =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )

        private const val SEGMENT_TREE = "tree"
        private const val SEGMENT_DOCUMENT = "document"
        private const val SEGMENT_CHILDREN = "children"

        // Protocole d'appel caché du SDK (vérifié sur le bytecode d'
        // android-all — DocumentsContract.createDocument/deleteDocument).
        private const val EXTRA_URI_PROTOCOLE = "uri"
        private const val COLONNE_NOM_AFFICHE = "_display_name"
        private const val COLONNE_TYPE_MIME = "mime_type"
        private const val METHODE_CREATION = "android:createDocument"
        private const val METHODE_SUPPRESSION = "android:deleteDocument"
    }
}
