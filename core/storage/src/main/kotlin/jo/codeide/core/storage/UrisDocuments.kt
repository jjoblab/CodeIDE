package jo.codeide.core.storage

import android.net.Uri
import android.provider.DocumentsContract

/**
 * Aides URI du monde SAF (section 5.6, ADR 0003).
 *
 * Tout l'adressage CodeIDE passe par des URI de documents SAF de la
 * forme `content://<autorite>/tree/<arbre>/doc/<id>` : ce composant est
 * l'**unique** endroit qui construit et décompose ces URI, à même les
 * primitives de [DocumentsContract] — le reste du module (et de
 * l'application) ne manipule que des chaînes et des `Uri` opaques.
 */
internal object UrisDocuments {
    /**
     * URI du document identifié par [idDocument] dans l'arborescence
     * portée par [arbreOuDocument] (URI d'arborescence **ou** URI de
     * document — `DocumentsContract` n'extrait que le segment `tree`).
     *
     * @param arbreOuDocument URI porteuse de l'arborescence.
     * @param idDocument identifiant du document visé.
     * @return l'URI de document complète.
     */
    fun uriDocument(
        arbreOuDocument: Uri,
        idDocument: String,
    ): Uri = DocumentsContract.buildDocumentUriUsingTree(arbreOuDocument, idDocument)

    /**
     * URI de requête des **enfants directs** d'un dossier.
     *
     * Le parent est une URI de document complète : son identifiant sert
     * de clé de listing, et son segment `tree` identifie l'arborescence
     * qui détient l'accès — c'est la requête groupée de la section 5.6
     * (un seul `ContentResolver.query` pour tout le dossier, jamais de
     * boucle sur `DocumentFile`).
     *
     * @param dossierParent URI du dossier à lister.
     * @return l'URI de requête des enfants.
     */
    fun uriEnfants(dossierParent: Uri): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(
            dossierParent,
            DocumentsContract.getDocumentId(dossierParent),
        )
}
