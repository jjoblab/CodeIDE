package jo.codeide.core.domain

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Extraction des emplacements de pile cliquables d'un message de journal
 * (mission « Exécuter » R4, spec EXECUTER.md § 4.3) : une trace Java/Kotlin
 * s'imprime en lignes `at paquet.Classe.methode(Fichier.kt:12)`.
 *
 * Règle d'ouverture (honnêteté) : seuls les fichiers **texte** que l'IDE
 * sait éditer sont cliquables — `.kt` et `.java`. Les cadres « Unknown
 * source », « Native Method » ou autres extensions (images, jar…) sont
 * ignorés SILENCIEUSEMENT : la trace reste lisible, seule la navigation
 * saute ces cadres.
 *
 * Pur et défensif : le texte vient d'une application externe, aucune
 * exception ne sort d'ici.
 *
 * Détection de plantage (R4) : le pont de l'application exécutée signale
 * une exception non interceptée par une ligne d'étiquette « Plantage »
 * (voir `GestionnaireCrash` côté applog-runtime) — [estPlantage] la
 * reconnaît pour le snackbar « L'app a planté ».
 */
public object LignesPile {
    /** Étiquette des lignes de plantage du pont (applog-runtime). */
    public const val ETIQUETTE_PLANTAGE: String = "Plantage"

    /** Préfixe du message d'annonce d'une exception non interceptée. */
    private const val PREFIXE_EXCEPTION = "Exception non interceptée"

    /**
     * Cadre de pile : `at classe.methode(fichier:ligne)`. Le groupe
     * `fichier` ne capture QUE les noms terminant par `.kt` ou `.java`
     * (fichiers texte éditables), la ligne doit être numérique.
     */
    private val CADRE: Pattern =
        Pattern.compile(
            "\\bat\\s+(?<classe>[\\w.$]+)\\.(?<methode>[\\w$<>]+)" +
                "\\(\\s*(?<fichier>[\\w.$-]+\\.(?:kt|java)):(?<ligne>\\d+)\\s*\\)",
        )

    /**
     * Tous les emplacements cliquables d'un texte (trace complète, message
     * simple, n'importe quoi) — dans l'ordre d'impression, doublons compris
     * (la pile peut répéter un emplacement, c'est la trace qui parle).
     *
     * @param texte message de journal (peut être multi-lignes : la trace
     *        du plantage arrive en UN message).
     */
    public fun extraire(texte: String): List<EmplacementPile> {
        val accords = CADRE.matcher(texte)
        val emplacements = ArrayList<EmplacementPile>()
        while (accords.find() && emplacements.size < BORNE_EMPACEMENTS) {
            ajouterSiValide(accords, emplacements)
        }
        return emplacements
    }

    /** Ajoute le cadre courant s'il est exploitable (ligne positive).
     *  Exemption detekt ciblée (règle 16) : ReturnCount — une clause de
     *  garde par rejet (ligne absente, ligne non positive), la forme
     *  plate reste la plus lisible pour un analyseur défensif. */
    @Suppress("ReturnCount")
    private fun ajouterSiValide(
        accords: Matcher,
        emplacements: MutableList<EmplacementPile>,
    ) {
        val ligne = accords.group("ligne")?.toIntOrNull() ?: return
        if (ligne <= 0) return
        emplacements.add(
            EmplacementPile(
                classe = accords.group("classe").orEmpty(),
                methode = accords.group("methode").orEmpty(),
                fichier = accords.group("fichier").orEmpty(),
                ligne = ligne,
            ),
        )
    }

    /**
     * Cette ligne de journal annonce-t-elle un PLANTAGE de l'application
     * exécutée (exception non interceptée, signalée par le pont) ?
     */
    public fun estPlantage(ligne: LigneJournal): Boolean =
        ligne.etiquette == ETIQUETTE_PLANTAGE && ligne.message.startsWith(PREFIXE_EXCEPTION)

    /** Nombre d'emplacements retenus (une trace mémorisée n'est pas infinie). */
    private const val BORNE_EMPACEMENTS = 200
}

/**
 * Un cadre de pile cliquable : la classe qualifiée, la méthode, le fichier
 * source et la ligne — assez pour ouvrir l'emplacement dans l'éditeur.
 *
 * @property classe classe qualifiée (`com.exemple.MainActivity`)
 * @property methode méthode du cadre (`onCreate`)
 * @property fichier nom de fichier du cadre (`MainActivity.kt`)
 * @property ligne numéro de ligne (1-based)
 */
public data class EmplacementPile(
    public val classe: String,
    public val methode: String,
    public val fichier: String,
    public val ligne: Int,
)
