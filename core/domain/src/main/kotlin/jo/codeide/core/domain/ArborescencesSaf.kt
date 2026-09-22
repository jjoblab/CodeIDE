package jo.codeide.core.domain

/**
 * Port de décomposition des URI d'arborescence SAF (section 5.6).
 *
 * `core:domain` est un module Kotlin JVM pur : il ne peut pas appeler
 * `DocumentsContract`. Ce port concentre les deux décompositions dont
 * la validation du dossier de travail a besoin (identifiant du
 * document, URI du document racine), implémenté par `core:storage`
 * sur les vraies API et par un faux déterministe dans `core:testing`.
 */
public interface ArborescencesSaf {
    /**
     * Identifiant de document racine d'une URI d'arborescence
     * (`…/tree/<id>`), décodé — ou `null` si l'URI est illisible.
     */
    public fun idDocument(grantUri: String): String?

    /**
     * URI du document racine correspondant à l'URI d'arborescence
     * (`…/tree/<id>/document/<id>`) — ou `null` si l'URI est
     * illisible.
     */
    public fun uriDocument(grantUri: String): String?
}
