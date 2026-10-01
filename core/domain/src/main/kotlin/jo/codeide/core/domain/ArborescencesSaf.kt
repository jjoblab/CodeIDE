package jo.codeide.core.domain

/**
 * Port de décomposition des URI d'arborescence SAF (section 5.6).
 *
 * `core:domain` est un module Kotlin JVM pur : il ne peut pas appeler
 * `DocumentsContract`. Ce port concentre les décompositions dont
 * la validation du dossier de travail a besoin (identifiant du
 * document, URI du document racine), implémenté par `core:storage`
 * sur les vraies API et par un faux déterministe dans `core:testing`.
 *
 * Vocabulaire (le bug v0.43.0 vient de là, il vaut l'insistance) :
 * - l'URI **d'arborescence** (`…/tree/<id>`) désigne le dossier qui
 *   DÉTIENT la permission persistante — pour un projet créé dans le
 *   dossier de travail, c'est l'arbre PARENT, pas le projet ;
 * - l'URI **de document** (`…/tree/<racine>/document/<id>`) désigne
 *   le dossier du projet LUI-MÊME (`StorageLocation.documentUri`).
 *
 * Toute traduction « dossier du projet → chemin réel » DOIT donc
 * passer par l'URI de document, jamais par l'URI d'arborescence.
 */
public interface ArborescencesSaf {
    /**
     * Identifiant de document racine d'une URI d'arborescence
     * (`…/tree/<id>`), décodé — ou `null` si l'URI est illisible.
     */
    public fun idDocument(grantUri: String): String?

    /**
     * Identifiant de document porté par une URI de **document**
     * (`…/tree/<racine>/document/<id>`), décodé — ou `null` si
     * l'URI est illisible (absence du segment `document` comprise :
     * une URI d'arborescence pure n'est PAS une URI de document).
     *
     * C'est LA décomposition du dossier du projet : l'identifiant
     * rendu couvre le chemin complet depuis la racine du volume
     * (ex. `primary:CodeIDE/MonProjet`), là où
     * [idDocument] d'une URI d'arborescence rend l'identifiant de
     * l'ARBRE porteur de la permission — le parent du projet pour
     * un projet créé dans le dossier de travail (correctif du bug
     * « résolution du projet dans le dossier parent », v0.43.0).
     */
    public fun idDocumentDeUriDocument(documentUri: String): String?

    /**
     * URI du document racine correspondant à l'URI d'arborescence
     * (`…/tree/<id>/document/<id>`) — ou `null` si l'URI est
     * illisible.
     */
    public fun uriDocument(grantUri: String): String?

    /**
     * URI du document [idDocument] **à l'intérieur** de l'arborescence
     * [grantUri] (`…/tree/<racine>/document/<id ré-encodé>`) — ou `null`
     * si l'URI d'arborescence est illisible.
     *
     * [idDocument] est l'identifiant **décodé** complet (chemin depuis la
     * racine du volume, ex. `primary:CodeIDE/MonProjet`) : l'implémentation
     * ré-encode chaque segment. Sert à référencer un dossier choisi par le
     * sélecteur SAF **dans** l'arbre d'une permission déjà tenue (dossier
     * de travail) sans prendre de permission supplémentaire (étape 7,
     * ADR 0015) : l'URI reconstruite partage le préfixe de l'arbre, ce que
     * la règle de libération conditionnelle des permissions sait comparer.
     */
    public fun uriDocumentDansArbre(
        grantUri: String,
        idDocument: String,
    ): String?
}
