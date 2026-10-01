package jo.codeide.core.testing

import jo.codeide.core.domain.ArborescencesSaf
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Faux déterministe du port [ArborescencesSaf] pour les tests JVM.
 *
 * Reproduit la sémantique de `DocumentsContract` sur la **forme
 * canonique** des URI d'arborescence (`content://autorite/tree/<id>`,
 * `<id>` percent-encodé) et des URI de document
 * (`content://autorite/tree/<id>/document/<id document>`) : décodage des
 * identifiants, reconstruction de l'URI de document. Le codage couvre les
 * caractères qui apparaissent dans les URI de test (`:`, `/`, espaces) —
 * l'encodage réel de `DocumentsContract` est celui de `Uri.encode`.
 */
public class FakeArborescencesSaf : ArborescencesSaf {
    override fun idDocument(grantUri: String): String? {
        val encode = segmentArbre(grantUri) ?: return null
        return decoder(encode)
    }

    override fun idDocumentDeUriDocument(documentUri: String): String? {
        // Miroir du contrat `DocumentsContract.getDocumentId` : sans
        // segment `document`, l'URI d'arborescence pure est REFUSÉE (null) —
        // c'est le comportement réel (IllegalArgumentException avalée en
        // null par SafArborescences) qui protège du bug du dossier parent.
        val encode = segmentDocument(documentUri) ?: return null
        return decoder(encode)
    }

    /** Segment d'arborescence percent-encodé, ou `null` s'il est absent ou mal formé. */
    private fun segmentArbre(grantUri: String): String? {
        val debut = grantUri.lastIndexOf(MARQUEUR)
        if (debut < 0) return null
        val encode = grantUri.substring(debut + MARQUEUR.length)
        return encode.takeUnless { it.isEmpty() || it.contains('/') }
    }

    /** Segment de document percent-encodé (`…/document/<id>`), ou `null`
     *  s'il est absent ou vide. */
    private fun segmentDocument(documentUri: String): String? {
        val debut = documentUri.lastIndexOf(MARQUEUR_DOCUMENT)
        if (debut < 0) return null
        return documentUri
            .substring(debut + MARQUEUR_DOCUMENT.length)
            .takeUnless { it.isEmpty() }
    }

    override fun uriDocument(grantUri: String): String? {
        val id = idDocument(grantUri) ?: return null
        return "$grantUri/document/${encoder(id)}"
    }

    override fun uriDocumentDansArbre(
        grantUri: String,
        idDocument: String,
    ): String? =
        // Clauses de garde : identifiant vide, arborescence illisible.
        if (idDocument.isBlank() || segmentArbre(grantUri) == null) {
            null
        } else {
            "$grantUri/document/${encoder(idDocument)}"
        }

    /** Décodage en pourcentages ; `null` si la séquence est invalide. */
    private fun decoder(encode: String): String? = runCatching { URLDecoder.decode(encode, UTF8) }.getOrNull()

    /** Encodage en pourcentages des identifiants de test. */
    private fun encoder(id: String): String = URLEncoder.encode(id, UTF8)

    private companion object {
        const val UTF8 = "UTF-8"

        /** Segment d'arborescence dans une URI SAF canonique. */
        const val MARQUEUR = "/tree/"

        /** Segment de document dans une URI SAF canonique. */
        const val MARQUEUR_DOCUMENT = "/document/"
    }
}
