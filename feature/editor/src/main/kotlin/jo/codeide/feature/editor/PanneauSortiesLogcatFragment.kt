package jo.codeide.feature.editor

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import jo.codeide.core.domain.EtatSessionJournal
import jo.codeide.core.domain.FiltreSorties
import jo.codeide.core.domain.LignesPile
import jo.codeide.core.domain.NiveauJournal
import jo.codeide.core.domain.SessionJournal
import jo.codeide.core.domain.versNiveauSortie
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.FragmentPanneauSortiesLogcatBinding
import jo.codeide.feature.sorties.CollecteurSortiesLogcat
import jo.codeide.feature.sorties.ControleurLecteurSorties
import jo.codeide.feature.sorties.EtatSessionsLogcat
import jo.codeide.feature.sorties.FamilleSorties
import jo.codeide.feature.sorties.FragmentLecteurSorties

/**
 * Onglet Logcat du panneau inférieur (mission « Exécuter » R3/R4, lot L4)
 * — le CHROME de la famille LOGCAT autour du lecteur UNIQUE du Centre de
 * sorties ([FragmentLecteurSorties], famille [FamilleSorties.LOGCAT]) :
 * l'ancien affichage (`PanneauLogcatFragment` + table DiffUtil) est
 * remplacé DANS CE LOT (ADR 0162 : pas de code mort, pas de double
 * affichage) et tout l'état d'affichage vit désormais dans le lecteur
 * (tampon, projection, recherche, pause, effacement, sélection).
 *
 * Ce que le chrome garde (logcat-spécifique, R3/R4) :
 *
 * - la **puce de processus** : sélecteur des sessions (vivantes,
 *   terminées, puis sessions précédentes de l'archive — même libellé et
 *   même ordre que l'ancien menu), ampoule vivante ;
 * - le **filtre de niveau** (Verbose → Assert) — porté par le filtre
 *   unifié `FiltreSorties` (ADR 0160), rien n'est réécrit ;
 * - les **bandeaux** pertes annoncées / arrêt / session précédente ;
 * - **« Expliquer » IA** (pré-remplissage des dernières rangées visibles
 *   du lecteur — jamais auto-envoyé) ;
 * - le **saut au source des traces** (R4) : une ligne portant une pile
 *   ouvre le sélecteur de cadres — branché sur le geste HÔTE du lecteur
 *   (`FragmentLecteurSorties.surLigneSansPosition`, le lecteur ne
 *   connaît pas l'éditeur).
 *
 * Le snackbar « L'application a planté » reste côté activité (le
 * collecteur est process-wide — [CollecteurSortiesLogcat.plantage]).
 */
@Suppress("TooManyFunctions")
@AndroidEntryPoint
class PanneauSortiesLogcatFragment : Fragment() {
    /** Le collecteur LOGCAT process-wide (sessions, sélection, plantage). */
    @Inject
    lateinit var collecteur: CollecteurSortiesLogcat

    /** ViewModel de l'espace (R4 : sauter au fichier source). */
    private val viewModelEditeur: EditorViewModel by viewModels()

    /** Le lecteur enfant (famille LOGCAT) — le même composant partout. */
    private var lecteur: FragmentLecteurSorties? = null

    private var liaisonAmorce: FragmentPanneauSortiesLogcatBinding? = null

    /** Liaison de la vue courante (accès après destruction = diagnostic
     *  lisible, jamais de `!!`). */
    private val liaison: FragmentPanneauSortiesLogcatBinding
        get() =
            checkNotNull(liaisonAmorce) {
                "liaison du panneau sorties-logcat indisponible — vue détruite ?"
            }

    /** Format de l'heure du bandeau « session précédente » (HH:mm). */
    private val formatHeure = SimpleDateFormat("HH:mm", Locale.ROOT)

    override fun onCreateView(inflateur: LayoutInflater, conteneur: ViewGroup?, etat: Bundle?): View {
        liaisonAmorce = FragmentPanneauSortiesLogcatBinding.inflate(inflateur, conteneur, false)
        return liaison.root
    }

    override fun onViewCreated(vue: View, etat: Bundle?) {
        super.onViewCreated(vue, etat)
        brancherLecteur()
        brancherChrome()
    }

    override fun onDestroyView() {
        liaisonAmorce = null
        super.onDestroyView()
    }

    /** Le lecteur enfant (créé UNE fois — les arguments portent la
     *  famille, le ViewModel survit à la rotation). */
    private fun brancherLecteur() {
        val existant = childFragmentManager.findFragmentById(R.id.conteneur_lecteur)
        val hote = (existant as? FragmentLecteurSorties) ?: FragmentLecteurSorties.nouvelle(FamilleSorties.LOGCAT)
        lecteur = hote
        // R4 : le saut au source des traces est le geste HÔTE du lecteur.
        hote.surLigneSansPosition = { texte -> proposerSautSource(texte) }
        if (existant == null) {
            childFragmentManager.commit {
                replace(R.id.conteneur_lecteur, hote)
            }
        }
    }

    /** Le chrome : puce de processus, niveau, IA, bandeaux. */
    private fun brancherChrome() {
        liaison.puceProcessus.setOnClickListener { ouvrirMenuProcessus() }
        liaison.boutonNiveau.setOnClickListener { ouvrirMenuNiveau() }
        liaison.boutonExpliquerIa.setOnClickListener { expliquerAvecIa() }
        collecteur.etatSessions.collectWithLifecycle(viewLifecycleOwner) { etat ->
            rendreChrome(etat)
        }
    }

    /** Rend le chrome : puce (nom + ampoule vivante) et bandeaux. */
    private fun rendreChrome(etat: EtatSessionsLogcat) {
        val sessionAffichee = etat.selectionRegistre?.let { id ->
            etat.sessions.firstOrNull { it.id == id }
        }
        val nom = etat.selectionArchivee?.session?.identite?.nomProcessus
            ?: sessionAffichee?.identite?.nomProcessus
        liaison.nomProcessus.text = nom ?: getString(R.string.logcat_aucun_processus)
        liaison.ampouleProcessus.backgroundTintList =
            ContextCompat.getColorStateList(
                requireContext(),
                if (etat.selectionArchivee == null &&
                    sessionAffichee?.etat == EtatSessionJournal.VIVANTE
                ) {
                    R.color.explorateur_vert
                } else {
                    R.color.explorateur_texte_3
                },
            )

        // Pertes annoncées (R3) — jamais silencieuses.
        liaison.bandeauPertes.isVisible = etat.lignesPerdues > 0L
        if (etat.lignesPerdues > 0L) {
            liaison.bandeauPertes.text =
                resources.getQuantityString(
                    R.plurals.logcat_lignes_perdues,
                    etat.lignesPerdues.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    etat.lignesPerdues,
                )
        }
        // Arrêt du processus (R3) — la raison lisible.
        val fin = etat.fin
        liaison.bandeauArret.isVisible = fin != null
        if (fin != null) {
            liaison.bandeauArret.text = getString(R.string.logcat_arret, fin.raison)
        }
        // Session précédente (R3) — consultable, archive bornée.
        liaison.bandeauPrecedente.isVisible = etat.selectionArchivee != null
        etat.selectionArchivee?.let { archivee ->
            val session = archivee.session
            val heure =
                session.fin?.let { fin -> formatHeure.format(Date(fin.horodatageMs)) }
                    ?: getString(R.string.logcat_precedente_heure_inconnue)
            liaison.bandeauPrecedente.text =
                getString(
                    R.string.logcat_precedente,
                    session.identite.nomProcessus,
                    session.identite.pid,
                    heure,
                )
        }
    }

    /**
     * Sélecteur de cadres cliquables (R4) : la trace arrive en UN message
     * multi-lignes — chaque cadre `.kt`/`.java` listé est un saut
     * potentiel ; un message sans pile ne réagit PAS (le lecteur ne
     * déclenche le geste hôte que sur demande explicite).
     */
    private fun proposerSautSource(texte: String) {
        val emplacements = LignesPile.extraire(texte)
        if (emplacements.isEmpty()) return
        val libelles =
            emplacements.map { "${it.classe}.${it.methode}(${it.fichier}:${it.ligne})" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.logcat_pile_titre)
            .setItems(libelles) { _, choix ->
                val cadre = emplacements[choix]
                viewModelEditeur.onAction(
                    ActionEditor.SauterVersLigneSource(
                        classe = cadre.classe,
                        fichier = cadre.fichier,
                        ligne = cadre.ligne,
                    ),
                )
            }.show()
    }

    /** Sélecteur de processus (R3) : vivantes d'abord, puis terminées du
     *  registre, puis sessions précédentes (archive bornée) — le même
     *  menu que l'ancien onglet, sur le collecteur process-wide. */
    private fun ouvrirMenuProcessus() {
        val etat = collecteur.etatSessions.value
        val vivantes = etat.sessions.filter { it.etat == EtatSessionJournal.VIVANTE }
        val terminees = etat.sessions.filter { it.etat != EtatSessionJournal.VIVANTE }
        val menu = PopupMenu(requireContext(), liaison.puceProcessus)
        vivantes.forEachIndexed { index, session ->
            menu.menu.add(GROUPE_VIVANTES, index + 1, 0, libelleSession(session, R.string.logcat_session_vivante))
        }
        terminees.forEachIndexed { index, session ->
            menu.menu.add(GROUPE_TERMINEES, index + 1, 0, libelleSession(session, R.string.logcat_session_terminee))
        }
        etat.archives.forEachIndexed { index, archivee ->
            menu.menu.add(
                GROUPE_PRECEDENTES,
                index + 1,
                0,
                libelleSession(archivee.session, R.string.logcat_session_precedente),
            )
        }
        menu.setOnMenuItemClickListener { item ->
            when (item.groupId) {
                GROUPE_VIVANTES -> {
                    vivantes.getOrNull(item.itemId - 1)?.let { collecteur.selectionnerSession(it.id) }
                }

                GROUPE_TERMINEES -> {
                    terminees.getOrNull(item.itemId - 1)?.let { collecteur.selectionnerSession(it.id) }
                }

                GROUPE_PRECEDENTES -> {
                    etat.archives.getOrNull(item.itemId - 1)?.let { collecteur.selectionnerArchivee(it.session.id) }
                }

                else -> {
                    Unit
                }
            }
            true
        }
        menu.show()
    }

    /** Filtre de niveau (R3) : masque les niveaux inférieurs — le filtre
     *  unifié (ADR 0160) le porte, aucun réglage spécifique au chrome. */
    private fun ouvrirMenuNiveau() {
        val menu = PopupMenu(requireContext(), liaison.boutonNiveau)
        NiveauJournal.entries.forEachIndexed { index, niveau ->
            menu.menu.add(GROUPE_NIVEAUX, index + 1, 0, libelleNiveau(niveau))
        }
        menu.setOnMenuItemClickListener { item ->
            NiveauJournal.entries.getOrNull(item.itemId - 1)?.let { niveau ->
                controleurLecteur()?.definirFiltre(
                    FiltreSorties(niveauMinimal = niveau.versNiveauSortie()),
                )
            }
            true
        }
        menu.show()
    }

    /** « Expliquer » (mission IA) : les dernières rangées VISIBLES du
     *  lecteur partent en pré-remplissage (jamais auto-envoyé). */
    private fun expliquerAvecIa() {
        val sortie = controleurLecteur()?.texteVisible(LIGNES_EXPLIQUEES_MAX) ?: return
        (activity as? ControleurPanneauEditeur)?.expliquerAvecIa(sortie)
    }

    /** Le contrôleur du lecteur enfant (façade publique — lot L4). */
    private fun controleurLecteur(): ControleurLecteurSorties? = lecteur

    /** Libellé d'une session du menu : « nom (pid) · état ». */
    private fun libelleSession(session: SessionJournal, suffixe: Int): String =
        getString(R.string.logcat_session_menu, session.identite.nomProcessus, session.identite.pid) +
            getString(suffixe)

    /** Libellé localisé d'un niveau du menu. */
    private fun libelleNiveau(niveau: NiveauJournal): Int = when (niveau) {
        NiveauJournal.VERBEUX -> R.string.logcat_niveau_verbeux
        NiveauJournal.DEBOGAGE -> R.string.logcat_niveau_debogage
        NiveauJournal.INFO -> R.string.logcat_niveau_info
        NiveauJournal.AVERTISSEMENT -> R.string.logcat_niveau_avertissement
        NiveauJournal.ERREUR -> R.string.logcat_niveau_erreur
        NiveauJournal.ASSERT -> R.string.logcat_niveau_assert
    }

    public companion object {
        /** Fenêtre glissante d'une sortie à expliquer (lignes — lot IA-5). */
        public const val LIGNES_EXPLIQUEES_MAX = 150

        /** Groupes du menu de processus (vivantes, terminées, précédentes). */
        public const val GROUPE_VIVANTES = 1
        public const val GROUPE_TERMINEES = 2
        public const val GROUPE_PRECEDENTES = 3

        /** Groupe du menu de niveaux. */
        public const val GROUPE_NIVEAUX = 4
    }
}
