package jo.codeide.sorties

import jo.codeide.core.datastore.SettingsDataStore
import jo.codeide.core.domain.FiltreSorties
import jo.codeide.core.domain.NiveauSortie
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.HorodatageSorties
import jo.codeide.core.model.NiveauDefautLogcat
import jo.codeide.feature.sorties.FENETRE_JOURNAL
import jo.codeide.feature.sorties.FENETRE_LOGCAT
import jo.codeide.feature.sorties.FamilleSorties
import jo.codeide.feature.sorties.OptionsRenduRangee
import jo.codeide.feature.sorties.ReglagesLecteurPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * L'implémentation DATASTORE du port des réglages lecteur (lot L6, M13
 * — spec SORTIES § 5.10) : les réglages « Sorties » de [AppSettings]
 * atteignent le composant PAR ICI — `feature:sorties` ne voit PAS
 * `core:datastore` (arêtes propres, ADR 0162), l'impl vit dans :app qui
 * est le carrefour du graphe Hilt.
 *
 * Les lectures sont SYNCHRONES (le VM construit son état au thread
 * principal) et servent un CLICHÉ en mémoire maintenu par la collecte du
 * flot des réglages (jamais une lecture disque bloquante) ; les écritures
 * ([sauvegarderRendu]) sont feu et oubli — une écriture de préférence
 * n'arrête jamais un lecteur.
 *
 * Sémantique assumée : le rendu PERSISTÉ EST le « défaut » — le réglage
 * de la section Réglages et le dernier geste du lecteur écrivent le
 * MÊME état (pas de double source de vérité). `afficheNumeros` et
 * `affichePidTid` restent des choix de SESSION (non persistés, comme
 * les lots L2–L5).
 */
@Singleton
internal class ReglagesLecteurDataStore
@Inject
constructor(private val reglages: SettingsDataStore) :
    ReglagesLecteurPort {
    /** Le cliché en mémoire — les défauts tant que la collecte n'a pas tourné. */
    @Volatile
    private var cliche: AppSettings = AppSettings()

    /** La portée propre du cliché (l'app vit plus longtemps que tout VM). */
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        reglages.observe()
            .onEach { cliche = it }
            .launchIn(portee)
    }

    override fun renduInitial(): OptionsRenduRangee = OptionsRenduRangee(
        afficheHorodatage = cliche.sortiesHorodatageDefaut != HorodatageSorties.MASQUE,
        horodatageAbsolu = cliche.sortiesHorodatageDefaut == HorodatageSorties.ABSOLU,
        retourALaLigne = cliche.sortiesRetourLigne,
        taillePoliceSp = cliche.sortiesTaillePoliceSp,
        couleursAnsi = cliche.sortiesCouleursAnsi,
    )

    override fun capaciteFenetre(famille: FamilleSorties, defaut: Int): Int? = when (famille) {
        FamilleSorties.LOGCAT ->
            cliche.sortiesFenetreLogcat.takeIf { it != FENETRE_LOGCAT }

        FamilleSorties.JOURNAL ->
            cliche.sortiesFenetreJournal.takeIf { it != FENETRE_JOURNAL }

        FamilleSorties.GRADLE -> null
    }

    override fun filtreInitial(famille: FamilleSorties): FiltreSorties? {
        if (famille != FamilleSorties.LOGCAT) return null
        return when (cliche.sortiesNiveauLogcatDefaut) {
            NiveauDefautLogcat.TOUT -> null
            NiveauDefautLogcat.AVERTISSEMENT -> FiltreSorties(niveauMinimal = NiveauSortie.AVERTISSEMENT)
            NiveauDefautLogcat.ERREUR -> FiltreSorties(niveauMinimal = NiveauSortie.ERREUR)
        }
    }

    override fun sauvegarderRendu(options: OptionsRenduRangee) {
        portee.launch {
            reglages.update { actuelles ->
                actuelles.copy(
                    sortiesHorodatageDefaut = quand(
                        options.afficheHorodatage,
                        options.horodatageAbsolu,
                    ),
                    // La taille de police n'est PAS persistée au fil du
                    // zoom : le réglage « Sorties » reste le défaut (un
                    // preset), le zoom reste un geste de session — la
                    // section Réglages ne ment jamais (pas d'option qui
                    // afficherait une valeur qu'elle ne propose pas).
                    sortiesRetourLigne = options.retourALaLigne,
                    sortiesCouleursAnsi = options.couleursAnsi,
                )
            }
        }
    }

    /** Le mode d'horodatage persisté depuis les deux drapeaux de rendu. */
    private fun quand(affiche: Boolean, absolu: Boolean): HorodatageSorties = when {
        !affiche -> HorodatageSorties.MASQUE
        absolu -> HorodatageSorties.ABSOLU
        else -> HorodatageSorties.RELATIF
    }
}
