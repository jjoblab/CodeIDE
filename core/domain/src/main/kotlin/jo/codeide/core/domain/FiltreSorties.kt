package jo.codeide.core.domain

import java.text.Normalizer
import java.util.regex.Pattern

/**
 * Le filtre unifié des sorties (ADR 0160 § Décision 3, lot L1) — pur JVM,
 * généralise la sémantique éprouvée de [FiltreLogcat] aux TROIS familles
 * du Centre de sorties : niveau (minimal OU ensemble), texte (sous-chaîne,
 * regex, mot entier, casse, accents), étiquettes inclues/exclues, sources,
 * sessions, plage de temps.
 *
 * Héritage de `FiltreLogcat` (mission « Exécuter » R3) :
 * un motif regex INVALIDE est signalé par [erreurMotif] et tombe en repli
 * « aucune correspondance » — JAMAIS d'exception vers l'appelant ; l'écart
 * est honnête (l'interface affiche l'erreur en ligne) plutôt qu'un
 * effacement silencieux.
 *
 * Sémantique de niveau (ADR 0160, échelle [NiveauSortie]) :
 * - `niveauMinimal` : les lignes graduées en dessous sont masquées ;
 *   les lignes [NiveauSortie.INDETERMINE] passent TOUJOURS — une ligne
 *   de flux sans sévérité ne viole pas un plancher (règle 14 : jamais de
 *   perte silencieuse ; le filtre ne masque pas ce qu'il ne sait pas
 *   graduer) ;
 * - `niveaux` (ensemble) : une sélection EXPLICITE — membership stricte ;
 *   quand il est non vide, il PRÉVAUT sur `niveauMinimal` (les deux
 *   contraintes ne se composent pas : ce serait des accents de niveau
 *   difficiles à prédire pour l'utilisateur) ;
 * - `niveaux` vide (`null` ou ensemble vide) : aucune contrainte.
 *
 * Sémantique de texte :
 * - sous-chaîne par défaut, sur le message ET l'étiquette (parité
 *   `FiltreLogcat` : chercher « main » retrouve MainActivity comme les
 *   messages parlant de main) ;
 * - insensible à la casse ET aux accents par défaut (pliage NFD, même
 *   discipline que `FiltrageProjets` et ADR 0025 — « theses » trouve
 *   « Thèses ») ;
 * - `regex` : le texte devient un motif appliqué au message SEUL (parité
 *   `FiltreLogcat`) ; les options casse/accents/motEntier s'appliquent
 *   alors comme des drapeaux de compilation de motif (les accents :
 *   pliage du texte cherché, pas du motif — un pliage de pattern changerait
 *   sa sémantique) ;
 * - `motEntier` (hors regex) : l'occurrence doit être délimitée par des
 *   frontières de mot Unicode (lettres/chiffres).
 *
 * Performance : `accepte` alloue le pliage de la ligne à chaque appel —
 * le lecteur (L2) cache le texte plié de ses lignes pour la recherche à
 * grande échelle (spec SORTIES § 10.2) ; le filtre reste correct seul.
 *
 * @property niveauMinimal sévérité minimale des lignes graduées (`null` :
 *           aucune contrainte).
 * @property niveaux ensemble explicite de niveaux admis (non vide :
 *           PRÉVAUT sur [niveauMinimal] ; vide : aucune contrainte).
 * @property texte motif de recherche (sous-chaîne ou regex).
 * @property regex `true` : [texte] est une expression régulière.
 * @property sensibleCasse `true` : la casse compte (défaut : insensible).
 * @property sensibleAccents `true` : les accents comptent (défaut : pliage
 *           NFD).
 * @property motEntier `true` : occurrence délimitée par frontières de mot
 *           (hors regex — en regex, l'utilisateur écrit ses bornes).
 * @property etiquettesInclues étiquettes à ADMETTRE (exact, sensibles à la
 *           casse — les tags logcat le sont) ; vide : toutes.
 * @property etiquettesExclues étiquettes à MASQUER (exact) ; vide : aucune.
 * @property sources sources admises ; vide : toutes.
 * @property sessions sessions admises (identité de session de journaux OU
 *           identifiant d'exécution Gradle) ; vide : toutes.
 * @property debutMs borne basse de la plage de temps (incluse) ; `null` :
 *           sans borne.
 * @property finMs borne haute de la plage de temps (incluse) ; `null` :
 *           sans borne.
 */
public data class FiltreSorties(
    public val niveauMinimal: NiveauSortie? = null,
    public val niveaux: Set<NiveauSortie>? = null,
    public val texte: String = "",
    public val regex: Boolean = false,
    public val sensibleCasse: Boolean = false,
    public val sensibleAccents: Boolean = false,
    public val motEntier: Boolean = false,
    public val etiquettesInclues: Set<String> = emptySet(),
    public val etiquettesExclues: Set<String> = emptySet(),
    public val sources: Set<SourceSortie> = emptySet(),
    public val sessions: Set<String> = emptySet(),
    public val debutMs: Long? = null,
    public val finMs: Long? = null,
) {
    /** Drapeaux de compilation selon l'option de casse — déclaré AVANT
     *  les motifs : Kotlin initialise les propriétés dans l'ordre de
     *  déclaration, et un motif qui lirait un drapeau pas encore posé
     *  compilerait silencieusement avec des options fausses (0 = sensible
     *  à la casse). */
    private val drapeauxRegex: Int =
        if (sensibleCasse) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE

    /** Texte cherché PLIÉ selon les options (sous-chaîne non bornée) —
     *  avant les motifs, pour la même raison d'ordre d'initialisation. */
    private val aiguille: String = plier(texte)

    /**
     * Motif compilé pour le mode regex (`null` : texte vide, ou motif
     * rejeté — repli signalé par [erreurMotif], jamais d'exception).
     */
    private val motif: Pattern? = if (regex && texte.isNotEmpty()) {
        runCatching {
            Pattern.compile(
                texte,
                drapeauxRegex,
            )
        }.getOrNull()
    } else {
        null
    }

    /** Motif « mot entier » pré-compilé pour le mode sous-chaîne bornée. */
    private val motifMotEntier: Pattern? = if (!regex && motEntier && texte.isNotEmpty()) {
        runCatching {
            Pattern.compile(
                "(?<![\\p{L}\\p{Nd}])" + Pattern.quote(aiguille) + "(?![\\p{L}\\p{Nd}])",
                drapeauxRegex,
            )
        }.getOrNull()
    } else {
        null
    }

    /**
     * `true` quand l'option regex est active mais le motif REJETÉ —
     * l'interface l'affiche en ligne sous le champ (héritage
     * `FiltreLogcat`).
     */
    public val erreurMotif: Boolean
        get() = regex && texte.isNotEmpty() && motif == null

    /**
     * Une ligne passe-t-elle le filtre ? Pur : même liste, même verdict.
     */
    public fun accepte(ligne: LigneSortie): Boolean = niveauPasse(ligne) &&
        textePasse(ligne) &&
        etiquettesPassent(ligne) &&
        sourcePasse(ligne) &&
        sessionPasse(ligne) &&
        plagePasse(ligne)

    /** Filtre une liste entière — l'entrée n'est jamais copiée en amont. */
    public fun filtrer(lignes: List<LigneSortie>): List<LigneSortie> = lignes.filter(::accepte)

    /** Pliage de recherche : NFD puis suppression des marques puis casse. */
    private fun plier(texte: String): String {
        var resultat = texte
        if (!sensibleAccents) {
            resultat = Normalizer.normalize(resultat, Normalizer.Form.NFD)
                .replace(REGEX_MARQUES, "")
        }
        if (!sensibleCasse) {
            resultat = resultat.lowercase()
        }
        return resultat
    }

    /** La contrainte de niveau est-elle satisfaite ? */
    private fun niveauPasse(ligne: LigneSortie): Boolean {
        val ensemble = niveaux
        return when {
            ensemble != null && ensemble.isNotEmpty() -> ligne.niveau in ensemble

            else -> {
                val minimal = niveauMinimal
                minimal == null || ligne.niveau == NiveauSortie.INDETERMINE ||
                    ligne.niveau.ordinal >= minimal.ordinal
            }
        }
    }

    /** La contrainte de texte est-elle satisfaite ? */
    private fun textePasse(ligne: LigneSortie): Boolean {
        if (texte.isEmpty()) {
            return true
        }
        return when {
            regex -> motif?.matcher(ligne.message)?.find() == true
            motEntier -> textePliePasse(ligne) { motifMotEntier?.matcher(it)?.find() == true }
            else -> textePliePasse(ligne) { it.contains(aiguille) }
        }
    }

    /** Sous-chaîne pliée (libre ou bornée par [FiltreSorties.motEntier])
     *  sur le message ET l'étiquette. */
    private fun textePliePasse(ligne: LigneSortie, teste: (String) -> Boolean): Boolean {
        val plieMessage = plier(ligne.message)
        val plieEtiquette = ligne.etiquette?.let { plier(it) }
        return teste(plieMessage) || (plieEtiquette != null && teste(plieEtiquette))
    }

    /** Les contraintes d'étiquettes sont-elles satisfaites ? */
    private fun etiquettesPassent(ligne: LigneSortie): Boolean = (
        etiquettesInclues.isEmpty() ||
            (ligne.etiquette != null && ligne.etiquette in etiquettesInclues)
        ) &&
        (etiquettesExclues.isEmpty() || ligne.etiquette !in etiquettesExclues)

    /** La contrainte de source est-elle satisfaite ? */
    private fun sourcePasse(ligne: LigneSortie): Boolean = sources.isEmpty() || ligne.source in sources

    /** La contrainte de session est-elle satisfaite ? */
    private fun sessionPasse(ligne: LigneSortie): Boolean = sessions.isEmpty() ||
        (ligne.identiteSession != null && ligne.identiteSession in sessions) ||
        (ligne.idExecution != null && ligne.idExecution in sessions)

    /** La contrainte de plage de temps est-elle satisfaite ? */
    private fun plagePasse(ligne: LigneSortie): Boolean = (debutMs == null || ligne.horodatageMs >= debutMs) &&
        (finMs == null || ligne.horodatageMs <= finMs)

    private companion object {
        /** Marques combinantes (accents) supprimées au pliage NFD. */
        private val REGEX_MARQUES = Regex("\\p{M}+")
    }
}
