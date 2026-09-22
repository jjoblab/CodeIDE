package jo.codeide.core.testing

import jo.codeide.core.domain.ArborescencesSaf
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Faux déterministe du port [ArborescencesSaf] pour les tests JVM.
 *
 * Reproduit la sémantique de `DocumentsContract` sur la **forme
 * canonique** des URI d'arborescence (`content://autorite/tree/<id>`,
 * `<id>` percent-encodé) : décodage de l'identifiant, reconstruction de
 * l'URI de document. Le codage couvre les caractères qui apparaissent
 * dans les URI de test (`:`, `/`, espaces) — l'encodage réel de
 * `DocumentsContract` est celui de `Uri.encode`.
 */
public class FakeArborescencesSaf : ArborescencesSaf {
    override fun idDocument(grantUri: String): String? {
        val encode = segmentArbre(grantUri) ?: return null
        return decoder(encode)
    }

    /** Segment d'arborescence percent-encodé, ou `null` s'il est absent ou mal formé. */
    private fun segmentArbre(grantUri: String): String? {
        val debut = grantUri.lastIndexOf(MARQUEUR)
        if (debut < 0) return null
        val encode = grantUri.substring(debut + MARQUEUR.length)
        return encode.takeUnless { it.isEmpty() || it.contains('/') }
    }

    override fun uriDocument(grantUri: String): String? {
        val id = idDocument(grantUri) ?: return null
        return "$grantUri/document/${encoder(id)}"
    }

    /** Décodage en pourcentages ; `null` si la séquence est invalide. */
    private fun decoder(encode: String): String? = runCatching { URLDecoder.decode(encode, UTF8) }.getOrNull()

    /** Encodage en pourcentages des identifiants de test. */
    private fun encoder(id: String): String = URLEncoder.encode(id, UTF8)

    private companion object {
        const val UTF8 = "UTF-8"

        /** Segment d'arborescence dans une URI SAF canonique. */
        const val MARQUEUR = "/tree/"
    }
}
