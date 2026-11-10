package jo.codeide.feature.sorties

import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.RechercheSorties
import jo.codeide.core.domain.RequeteRecherche
import jo.codeide.core.domain.ResultatRechercheSorties

/**
 * L'état de recherche PUBLIÉ par le lecteur (lot L2) — le compteur
 * « n/N » et les repères bleus de la poignée (spec SORTIES § 3.4).
 *
 * @property requete la requête courante.
 * @property correspondances positions de TAMPON des lignes qui passent,
 *           croissantes.
 * @property rang rang courant (0-based, dans [correspondances]) —
 *           l'affichage est « rang+1 / N ».
 * @property motifInvalide `true` : le motif regex est rejeté — affiché
 *           en ligne (repli « aucune correspondance », jamais
 *           d'exception, héritage `FiltreLogcat`).
 * @property interrompue `true` : la recherche a été annulée en cours —
 *           le résultat partiel est affiché tel quel (jamais un faux
 *           « aucune correspondance » silencieux).
 */
internal data class EtatRechercheLecteur(
    val requete: RequeteRecherche,
    val correspondances: List<Int>,
    val rang: Int,
    val motifInvalide: Boolean,
    val interrompue: Boolean,
)

/**
 * L'état de RECHERCHE du lecteur (lot L2) — détenu par le ViewModel sous
 * son verrou : la requête courante, les positions de tampon des
 * correspondances, le rang courant. La classe porte la DISCIPLINE de
 * l'état :
 *
 * - les correspondances vivent en positions de TAMPON — une troncature
 *   de tête les décale ([decaler] : les tombées emportent leurs
 *   correspondances, le rang glisse avec elles) ;
 * - la navigation ([naviguer]) ne pose l'utilisateur que sur une
 *   correspondance VISIBLE (les lignes masquées par le filtre restent
 *   comptées dans N — leur saut est sauté) ;
 * - un motif invalide est PORTÉ, jamais avalé
 *   ([EtatRechercheLecteur.motifInvalide]).
 */
internal class RechercheLecteur {
    private var requeteCourante: RequeteRecherche? = null
    private var resultat: ResultatRechercheSorties? = null
    private var rang: Int = 0

    /** L'état publié courant, ou `null` hors recherche. */
    public fun etat(): EtatRechercheLecteur? {
        val requete = requeteCourante ?: return null
        val resultatLu = resultat
        return EtatRechercheLecteur(
            requete = requete,
            correspondances = resultatLu?.correspondances ?: emptyList(),
            rang = if (resultatLu == null) 0 else rangRang(resultatLu.correspondances.size),
            motifInvalide = resultatLu?.motifInvalide ?: false,
            interrompue = resultatLu?.interrompue ?: false,
        )
    }

    /** Le rang publié, plié au nombre de correspondances (jamais hors
     *  bornes — une liste vide ramène au rang 0). */
    private fun rangRang(taille: Int): Int = if (taille <= 0) 0 else rang.coerceIn(0, taille - 1)

    /**
     * Lance la requête sur un état de tampon FIGÉ (le ViewModel fige via
     * [TamponSorties.instantane]) et repart du rang 0.
     *
     * @param visiblesRang l'index de RANGÉE d'une position de tampon, ou
     *           `null` si la ligne est masquée — sert au saut initial
     *           (première correspondance visible).
     * @return la rangée à joindre après publication, ou `null` si aucune
     *           correspondance visible n'existe.
     */
    public fun definir(
        requete: RequeteRecherche,
        lignes: List<LigneSortie>,
        messagesPlies: List<String>,
        etiquettesPlies: List<String?>,
        visiblesRang: (Int) -> Int?,
    ): Int? {
        requeteCourante = requete
        rang = 0
        resultat = if (requete.estVide) {
            requeteCourante = null
            null
        } else {
            RechercheSorties.rechercher(lignes, messagesPlies, etiquettesPlies, requete)
        }
        return premiereVisible(visiblesRang)
    }

    /**
     * Décale l'état après une troncature de tête : les tombées emportent
     * leurs correspondances, le rang glisse avec elles (il reste posé sur
     * la MÊME correspondance si elle survit).
     */
    public fun decaler(troncatureTete: Int) {
        val resultatLu = resultat ?: return
        resultat = resultatLu.decaler(troncatureTete)
        if (rang >= resultatLu.correspondances.size) {
            rang = (resultatLu.correspondances.size - 1).coerceAtLeast(0)
        }
    }

    /**
     * Navigue ([pas] = +1 suivant, −1 précédent) vers la prochaine
     * correspondance VISIBLE — les masquées sont comptées dans N mais
     * leur saut est sauté (le lecteur ne pose jamais l'utilisateur sur
     * une ligne qu'il ne montre pas).
     *
     * @param visiblesRang l'index de rangée d'une position de tampon ou
     *           `null` si masquée.
     * @return la rangée à joindre, ou `null` quand aucune correspondance
     *           visible n'est atteignable dans cette direction.
     */
    public fun naviguer(pas: Int, visiblesRang: (Int) -> Int?): Int? {
        val resultatLu = resultat
        if (resultatLu == null || resultatLu.correspondances.isEmpty()) return null
        rang = rang.coerceIn(0, resultatLu.correspondances.size - 1)
        var cible: Int? = null
        var curseur = rang
        repeat(resultatLu.correspondances.size) {
            if (cible == null) {
                curseur += pas
                if (curseur >= 0 && curseur < resultatLu.correspondances.size) {
                    rang = curseur
                    cible = visiblesRang(resultatLu.correspondances[curseur])
                }
            }
        }
        return cible
    }

    /** Efface l'état (effacement du tampon, requête vide). */
    public fun effacer() {
        requeteCourante = null
        resultat = null
        rang = 0
    }

    /** La première correspondance VISIBLE — le saut initial. */
    private fun premiereVisible(visiblesRang: (Int) -> Int?): Int? {
        val resultatLu = resultat ?: return null
        val candidates = resultatLu.correspondances.mapIndexed { indice, position ->
            Triple(indice, position, visiblesRang(position))
        }
        val premiere = candidates.firstOrNull { (_, _, rangRangee) -> rangRangee != null }
        if (premiere != null) {
            rang = premiere.first
        }
        return premiere?.third
    }
}
