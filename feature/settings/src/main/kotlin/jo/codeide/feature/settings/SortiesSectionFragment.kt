package jo.codeide.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.google.android.material.button.MaterialButtonToggleGroup
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.HorodatageSorties
import jo.codeide.core.model.NiveauDefautLogcat
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.settings.databinding.FragmentSettingsSortiesBinding
import javax.inject.Inject

/** Les presets de POLICE du lecteur (sp — les mêmes bornes que le zoom). */
private const val POLICE_PETITE_SP = 10

/** Preset moyen de la police du lecteur (défaut du composant). */
private const val POLICE_MOYENNE_SP = 12

/** Preset confortable de la police du lecteur. */
private const val POLICE_GRANDE_SP = 16

/** Rétention courte du registre (exécutions). */
private const val RETENTION_COURTE = 5

/** Rétention par défaut du registre (ADR 0161). */
private const val RETENTION_DEFAUT = 10

/** Rétention large du registre. */
private const val RETENTION_LARGE = 30

/** Fenêtre courte des tampons (lignes). */
private const val FENETRE_COURTE = 2_000

/** Fenêtre Logcat par défaut (parité R3). */
private const val FENETRE_LOGCAT_DEFAUT = 5_000

/** Fenêtre Journal par défaut. */
private const val FENETRE_JOURNAL_DEFAUT = 10_000

/** Fenêtre Logcat large. */
private const val FENETRE_LOGCAT_LARGE = 20_000

/** Fenêtre Journal large. */
private const val FENETRE_JOURNAL_LARGE = 30_000

/**
 * Section Sorties (lot L6, M13 — spec SORTIES § 5.10) : les réglages du
 * Centre de sorties, CHACUN avec un effet réel —
 * - horodatage, police, retour à la ligne, couleurs ANSI : la valeur
 *   initiale du lecteur à l'ouverture (et la persistance des gestes
 *   porteurs d'un défaut, `ReglagesLecteurPort`) ;
 * - rétention du registre (ADR 0161) et fenêtres des tampons : bornes
 *   réelles lues à l'écriture et à l'ouverture du lecteur ;
 * - filtre Logcat par défaut : appliqué une fois à l'ouverture, les
 *   lignes masquées restant comptées (règle 14).
 *
 * Le rendu et les branchements passent par des TABLES id↔valeur : une
 * seule lecture de vérité par bouton, aucune répétition de `when` (la
 * complexité des méthodes reste sous les seuils detekt).
 */
@AndroidEntryPoint
class SortiesSectionFragment : BaseFragment<FragmentSettingsSortiesBinding>() {
    private val viewModel: SettingsViewModel by activityViewModels()

    /** Navigation découplée : retour au maître par la flèche système. */
    @Inject
    lateinit var navigator: AppNavigator

    /** Vrai pendant le rendu programmatique — coupe les fausses actions. */
    private var renduEnCours = false

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentSettingsSortiesBinding = FragmentSettingsSortiesBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarsAndImeInsets(top = true, bottom = true)
        binding.settingsToolbar.setNavigationOnClickListener { navigator.goBack() }
        brancher()
        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat.reglage) }
    }

    /** Branchements des contrôles → actions (une seule fois). */
    private fun brancher() {
        brancherChoix(binding.groupeHorodatageSorties) {
            ActionParametres.ChangerSortiesHorodatage(horodatageDeLId(it))
        }
        brancherChoix(binding.groupePoliceSorties) {
            ActionParametres.ChangerSortiesPolice(policeDeLId(it))
        }
        brancherChoix(binding.groupeRetentionSorties) {
            ActionParametres.ChangerSortiesRetention(retentionDeLId(it))
        }
        brancherChoix(binding.groupeFenetreLogcat) {
            ActionParametres.ChangerSortiesFenetreLogcat(fenetreLogcatDeLId(it))
        }
        brancherChoix(binding.groupeFenetreJournal) {
            ActionParametres.ChangerSortiesFenetreJournal(fenetreJournalDeLId(it))
        }
        brancherChoix(binding.groupeNiveauLogcat) {
            ActionParametres.ChangerSortiesNiveauLogcat(niveauDeLId(it))
        }
        binding.rangeeRetourLigneSorties.setSurChangementCoche { actif ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerSortiesRetourLigne(actif))
        }
        binding.rangeeCouleursAnsi.setSurChangementCoche { actives ->
            if (!renduEnCours) viewModel.onAction(ActionParametres.ChangerSortiesCouleursAnsi(actives))
        }
    }

    /** Un groupe de boutons pousse SON action (le rendu coupe l'écho). */
    private fun brancherChoix(groupe: MaterialButtonToggleGroup, action: (Int) -> ActionParametres) {
        groupe.addOnButtonCheckedListener { _, id, coche ->
            if (coche && !renduEnCours) viewModel.onAction(action(id))
        }
    }

    /** Rendu de l'état dans les contrôles (une table par groupe). */
    private fun rendre(reglages: AppSettings) {
        renduEnCours = true
        try {
            cocher(binding.groupeHorodatageSorties, reglages.sortiesHorodatageDefaut, idsHorodatage)
            cocher(binding.groupePoliceSorties, reglages.sortiesTaillePoliceSp, idsPolice)
            cocher(binding.groupeRetentionSorties, reglages.sortiesExecutionsRetenues, idsRetention)
            cocher(binding.groupeFenetreLogcat, reglages.sortiesFenetreLogcat, idsFenetreLogcat)
            cocher(binding.groupeFenetreJournal, reglages.sortiesFenetreJournal, idsFenetreJournal)
            cocher(binding.groupeNiveauLogcat, reglages.sortiesNiveauLogcatDefaut, idsNiveau)
            binding.rangeeRetourLigneSorties.coche = reglages.sortiesRetourLigne
            binding.rangeeCouleursAnsi.coche = reglages.sortiesCouleursAnsi
        } finally {
            renduEnCours = false
        }
    }

    /** Coche LE bouton de la table (aucun si la valeur n'est pas un
     *  preset — le réglage persisté hors presets garde le défaut visuel). */
    private fun cocher(groupe: MaterialButtonToggleGroup, valeur: Any?, table: Map<*, Int>) {
        table[valeur]?.let(groupe::check)
    }
}

/** Tables id de bouton ↔ valeur — la vérité du rendu ET des gestes. */
private val idsHorodatage = mapOf(
    HorodatageSorties.MASQUE to R.id.bouton_horodatage_1,
    HorodatageSorties.RELATIF to R.id.bouton_horodatage_2,
    HorodatageSorties.ABSOLU to R.id.bouton_horodatage_3,
)

private val idsPolice = mapOf(
    POLICE_PETITE_SP to R.id.bouton_police_sorties_1,
    POLICE_MOYENNE_SP to R.id.bouton_police_sorties_2,
    POLICE_GRANDE_SP to R.id.bouton_police_sorties_3,
)

private val idsRetention = mapOf(
    RETENTION_COURTE to R.id.bouton_retention_1,
    RETENTION_DEFAUT to R.id.bouton_retention_2,
    RETENTION_LARGE to R.id.bouton_retention_3,
)

private val idsFenetreLogcat = mapOf(
    FENETRE_COURTE to R.id.bouton_fenetre_logcat_1,
    FENETRE_LOGCAT_DEFAUT to R.id.bouton_fenetre_logcat_2,
    FENETRE_LOGCAT_LARGE to R.id.bouton_fenetre_logcat_3,
)

private val idsFenetreJournal = mapOf(
    FENETRE_COURTE to R.id.bouton_fenetre_journal_1,
    FENETRE_JOURNAL_DEFAUT to R.id.bouton_fenetre_journal_2,
    FENETRE_JOURNAL_LARGE to R.id.bouton_fenetre_journal_3,
)

private val idsNiveau = mapOf(
    NiveauDefautLogcat.TOUT to R.id.bouton_niveau_1,
    NiveauDefautLogcat.AVERTISSEMENT to R.id.bouton_niveau_2,
    NiveauDefautLogcat.ERREUR to R.id.bouton_niveau_3,
)

/** Horodatage du bouton coché (lecture inverse de la table). */
private fun horodatageDeLId(id: Int): HorodatageSorties = cleDeLId(idsHorodatage, id, HorodatageSorties.MASQUE)

/** Police par défaut du bouton coché. */
private fun policeDeLId(id: Int): Int = cleDeLId(idsPolice, id, POLICE_MOYENNE_SP)

/** Rétention du bouton coché. */
private fun retentionDeLId(id: Int): Int = cleDeLId(idsRetention, id, RETENTION_DEFAUT)

/** Fenêtre Logcat du bouton coché. */
private fun fenetreLogcatDeLId(id: Int): Int = cleDeLId(idsFenetreLogcat, id, FENETRE_LOGCAT_DEFAUT)

/** Fenêtre Journal du bouton coché. */
private fun fenetreJournalDeLId(id: Int): Int = cleDeLId(idsFenetreJournal, id, FENETRE_JOURNAL_DEFAUT)

/** Filtre Logcat du bouton coché. */
private fun niveauDeLId(id: Int): NiveauDefautLogcat = cleDeLId(idsNiveau, id, NiveauDefautLogcat.TOUT)

/** Lecture inverse générique d'une table id↔valeur (défaut en repli). */
private inline fun <reified V> cleDeLId(table: Map<V, Int>, id: Int, defaut: V): V =
    table.entries.firstOrNull { it.value == id }?.key ?: defaut
