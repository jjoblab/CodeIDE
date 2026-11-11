package jo.codeide.feature.sorties

import jo.codeide.core.domain.FiltreSorties

/**
 * Le PORT des réglages « Sorties » côté composant (lot L6, M13 — spec
 * SORTIES § 5.10) : la SEULE porte par laquelle les réglages persistés
 * de l'application (AppSettings, DataStore) atteignent le lecteur — le
 * module `feature:sorties` ne dépend PAS de `core:datastore` (l'arête
 * de compilation reste propre, ADR 0162), l'implémentation réelle est
 * liée côté :app (le graphe Hilt est global).
 *
 * Chaque méthode a UN effet réel (aucun réglage décoratif) :
 * - [renduInitial] pose les options de rendu à la construction du VM
 *   (horodatage, police, retour à la ligne, couleurs ANSI) ;
 * - [capaciteFenetre] réforme la fenêtre du tampon à l'ouverture (les
 *   pertes éventuelles restent comptées — règle 14) ;
 * - [filtreInitial] applique le filtre par défaut à l'ouverture (les
 *   lignes masquées restent comptées et annoncées) ;
 * - [sauvegarderRendu] persiste le rendu au fil des gestes (le lecteur
 *   se rouvre comme il a été laissé — réglage persistant M13).
 */
public interface ReglagesLecteurPort {
    /** Les options de rendu initiales (les défauts constants si le
     *  réglage n'existe pas encore — la lecture est SYNCHRONE et
     *  jamais bloquante : l'implémentation sert un cliché en mémoire). */
    public fun renduInitial(): OptionsRenduRangee

    /**
     * La capacité de fenêtre voulue pour [famille], ou `null` pour
     * garder le défaut de la source (les constantes de parité).
     *
     * @param defaut la capacité par défaut de la source (parité R3/R4).
     */
    public fun capaciteFenetre(famille: FamilleSorties, defaut: Int): Int?

    /** Le filtre appliqué à l'ouverture, ou `null` pour « tout ». */
    public fun filtreInitial(famille: FamilleSorties): FiltreSorties?

    /** Persiste les options de rendu au fil des gestes (feu et oubli —
     *  une écriture de préférence, jamais une erreur fatale au lecteur). */
    public fun sauvegarderRendu(options: OptionsRenduRangee)
}

/**
 * L'implémentation PAR DÉFAUT du port — les défauts constants du
 * composant, sans persistance : elle sert aux tests et à tout hôte qui
 * n'a pas branché les réglages. Le comportement du lecteur est EXACTEMENT
 * celui des lots L2–L5 (aucun réglage n'invente un effet).
 */
public class ReglagesLecteurDefaut
    @javax.inject.Inject
    constructor() : ReglagesLecteurPort {
    override fun renduInitial(): OptionsRenduRangee = OptionsRenduRangee()

    override fun capaciteFenetre(famille: FamilleSorties, defaut: Int): Int? = null

    override fun filtreInitial(famille: FamilleSorties): FiltreSorties? = null

    override fun sauvegarderRendu(options: OptionsRenduRangee) = Unit
}
