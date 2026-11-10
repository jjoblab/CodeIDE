package jo.codeide.feature.sorties

import jo.codeide.core.domain.GroupeTache

/**
 * LE mode de lecture du Centre de sorties (spec SORTIES § 4 : « une
 * liste, deux projections », lot L3) — le tampon est UNIQUE, la
 * projection est une dérivation (ADR 0078) :
 *
 * - [BRUT] : le flux au fil de l'eau, une rangée par ligne (la projection
 *   du lot L2) ;
 * - [REGROUPE] : résumé en tête, rangées par tâche (contenu repliable),
 *   bloc d'échec épinglé — la dérivation
 *   [jo.codeide.core.domain.ProjectionRegroupee].
 */
public enum class ModeLecture {
    /** Le flux brut (une rangée par ligne, fil de l'eau). */
    BRUT,

    /** La vue regroupée (résumé, tâches repliables, bloc d'échec épinglé). */
    REGROUPE,
}

/**
 * UNE rangée AFFICHABLE du lecteur (lot L3) — soit une ligne de tampon
 * (la rangée de rendu du lot L2 : segments SGR pré-parsés), soit
 * l'en-tête SYNTHÉTIQUE d'un groupe de tâches (jamais une ligne : le
 * tampon reste unique, l'en-tête est une dérivation de l'index).
 */
public sealed interface RangeeLecteur {

    /**
     * Une ligne du tampon, à sa rangée de rendu.
     *
     * @property position la POSITION de tampon de la ligne (l'index de
     *           projection — l'en-tête collant et la sélection s'y
     *           retrouvent ; la rangée de rendu, elle, ne porte que le
     *           contenu).
     */
    public data class Ligne(public val position: Int, public val rangee: RangeeSortie) : RangeeLecteur

    /**
     * L'en-tête d'une rangée de tâche de la vue regroupée.
     *
     * @property indiceGroupe l'indice du groupe dans l'état dérivé — LA
     *           clé du repli (stable tant que le regroupement n'a pas
     *           été re-dérivé).
     * @property groupe le groupe dérivé (chemin, statut, durée, compteurs).
     * @property lignesVisibles lignes VISIBLES sous l'en-tête (le compteur
     *           honnête — les masquées restent comptées par le filtre).
     * @property replie le groupe est-il replié (son contenu est omis de
     *           CETTE projection — jamais du tampon).
     */
    public data class EnteteTache(
        public val indiceGroupe: Int,
        public val groupe: GroupeTache,
        public val lignesVisibles: Int,
        public val replie: Boolean,
    ) : RangeeLecteur
}
