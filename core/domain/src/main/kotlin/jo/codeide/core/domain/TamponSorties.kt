package jo.codeide.core.domain

/**
 * État immuable d'un [TamponSorties] à un instant — les trois listes sont
 * ALIGNÉES par construction (même indice = même ligne) et publiées sous un
 * même verrou : un lecteur qui consomme cet état ne peut jamais voir une
 * ligne dont le pliage manquerait.
 *
 * @property lignes lignes de la fenêtre, de la plus ancienne à la plus
 *           récente (tête tronquée).
 * @property messagesPlies pliage par défaut des messages (même indice que
 *           [lignes]) — le cache de recherche à grande échelle.
 * @property etiquettesPlies pliage par défaut des étiquettes (`null` quand
 *           la ligne n'en porte pas — même indice que [lignes]).
 * @property pertes compteur de lignes perdues par troncature de tête
 *           depuis la dernière vidange (règle 14 — jamais silencieux).
 */
public data class EtatTamponSorties(
    public val lignes: List<LigneSortie>,
    public val messagesPlies: List<String>,
    public val etiquettesPlies: List<String?>,
    public val pertes: Long,
)

/**
 * Le tampon borné du Centre de sorties (ADR 0159 décision 2, ADR 0162 :
 * « la logique de tampon/projection est dans `core:domain`, pas dans la
 * feature », lot L2) — accumulateur de [LigneSortie] à fenêtre
 * TÊTE-tronquée. Une instance par famille de source (spec SORTIES § 3.1 :
 * console, Logcat et journal portent chacun leur fenêtre maximale).
 *
 * Contrats portés par cette classe :
 * - **Honnêteté des pertes** (règle 14) : toute ligne tombée de la tête
 *   est COMPTÉE dans [pertes] — le lecteur l'affiche (bandeau), jamais
 *   absorbée en silence ;
 * - **Horodatage à l'ingestion** (ADR 0160, décision du lot L2) : une
 *   ligne sans instant d'émission (`horodatageMs == 0`) reçoit l'instant
 *   de RÉCEPTION de l'horloge injectée ; une ligne qui porte l'instant
 *   d'émission du câble (orchestrateur `BuildOutput.timestampMs`,
 *   v0.43.0) le GARDE — le câble fait foi quand il parle, la réception
 *   sinon ; l'UI n'horodate JAMAIS ;
 * - **Cache des textes pliés** : le pliage par défaut de recherche
 *   ([PliageTexte], options par défaut du filtre) est calculé UNE fois à
 *   l'ingestion pour le message ET l'étiquette — la recherche à grande
 *   échelle du lecteur (spec SORTIES § 10.2 : 100 000 lignes < 2 s hors
 *   thread) ne réalloue plus le pliage par ligne (cf. KDoc
 *   [FiltreSorties]).
 *
 * Les listes internes ne SORTENT jamais : [instantane] publie un état
 * immuable cohérent. L'ingestion est séquentielle (un collecteur) ; les
 * lectures viennent du thread principal et du worker de recherche — d'où
 * la synchronisation par verrou.
 *
 * @property fenetreMax capacité maximale de la fenêtre (lignes) ;
 *           injectée par la source (console : `NB_LIGNES_MAX` actuel ;
 *           Logcat : `CAPACITE_TAMPON` ; banc : fenêtre de la séquence).
 * @property horloge horloge injectée pour l'horodatage à la réception.
 */
public class TamponSorties(private val fenetreMax: Int, private val horloge: TimeProvider) {
    private val verrou = Any()
    private val lignesInternes = ArrayList<LigneSortie>(capaciteInitiale())
    private val messagesPliesInternes = ArrayList<String>(capaciteInitiale())
    private val etiquettesPliesInternes = ArrayList<String?>(capaciteInitiale())
    private var pertesInternes: Long = 0L

    /** Nombre de lignes présentes dans la fenêtre. */
    public val taille: Int
        get() = synchronized(verrou) { lignesInternes.size }

    /**
     * Lignes perdues par troncature de tête depuis la dernière vidange —
     * la vérité du bandeau « pertes » du lecteur (règle 14).
     */
    public val pertes: Long
        get() = synchronized(verrou) { pertesInternes }

    /**
     * Ajoute UNE ligne : horodate si le câble n'a pas parlé, plie le
     * message et l'étiquette, puis applique la fenêtre.
     *
     * @return le nombre de lignes tombées de la tête PAR CET APPEL
     *         (0 en régime sous la capacité) — le lecteur en déduit le
     *         décalage de ses index de projection.
     */
    public fun ajouter(ligne: LigneSortie): Int = synchronized(verrou) {
        inserer(horodater(ligne))
    }

    /**
     * Ajoute UN LOT de lignes — le chemin chaud du lecteur (héritage
     * ADR 0074 : append par lot, une mesure d'horloge par lot, pas par
     * ligne pour les lignes sans émission).
     *
     * @return le nombre de lignes tombées de la tête PAR CET APPEL.
     */
    public fun ajouterLot(lignesNouvelles: List<LigneSortie>): Int = synchronized(verrou) {
        val reception = horloge.nowMillis()
        var tombees = 0
        lignesNouvelles.forEach { ligne -> tombees += inserer(horodater(ligne, reception)) }
        tombees
    }

    /**
     * Vide le tampon (effacement explicite du lecteur, nouveau build) —
     * lignes, pliés et compteur de pertes repartent de zéro : le compteur
     * porte la session COURANTE (sémantique `Vider` de la console
     * actuelle).
     */
    public fun vider() {
        synchronized(verrou) {
            lignesInternes.clear()
            messagesPliesInternes.clear()
            etiquettesPliesInternes.clear()
            pertesInternes = 0L
        }
    }

    /**
     * État immuable et cohérent du tampon — la SEULE sortie des données
     * (les listes internes ne sont jamais partagées).
     */
    public fun instantane(): EtatTamponSorties = synchronized(verrou) {
        EtatTamponSorties(
            lignes = ArrayList(lignesInternes),
            messagesPlies = ArrayList(messagesPliesInternes),
            etiquettesPlies = ArrayList(etiquettesPliesInternes),
            pertes = pertesInternes,
        )
    }

    /**
     * Insertion réelle (verrou tenu) : pliage, fenêtre TÊTE-tronquée,
     * comptage des pertes.
     *
     * @return le nombre de lignes tombées de la tête par cet appel.
     */
    private fun inserer(ligne: LigneSortie): Int {
        lignesInternes.add(ligne)
        messagesPliesInternes.add(
            PliageTexte.plier(ligne.message, sensibleCasse = false, sensibleAccents = false),
        )
        etiquettesPliesInternes.add(
            ligne.etiquette?.let { PliageTexte.plier(it, sensibleCasse = false, sensibleAccents = false) },
        )
        val surplus = lignesInternes.size - fenetreMax
        if (surplus > 0) {
            // Retrait de tête EN BLOC (subList.clear) — removeAt(0) en
            // boucle serait O(n²) sur le chemin chaud.
            lignesInternes.subList(0, surplus).clear()
            messagesPliesInternes.subList(0, surplus).clear()
            etiquettesPliesInternes.subList(0, surplus).clear()
            pertesInternes += surplus
        }
        return surplus.coerceAtLeast(0)
    }

    /**
     * Horodatage à l'ingestion : le câble fait foi (`horodatageMs > 0`),
     * l'instant de réception sinon.
     */
    private fun horodater(ligne: LigneSortie, reception: Long = horloge.nowMillis()): LigneSortie =
        if (ligne.horodatageMs > 0L) ligne else ligne.copy(horodatageMs = reception)

    private fun capaciteInitiale(): Int = fenetreMax.coerceAtLeast(1)
}
