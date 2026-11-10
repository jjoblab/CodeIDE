package jo.codeide.feature.sorties

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.FiltreSorties
import jo.codeide.core.domain.RegistrePortSautSource
import jo.codeide.core.ui.BandeauSorties
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.NiveauEtat
import jo.codeide.core.ui.Retour
import jo.codeide.core.ui.SimpleTextWatcher
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.sorties.databinding.FragmentLecteurSortiesBinding
import kotlinx.coroutines.launch

/**
 * Le lecteur du Centre de sorties (ADR 0159/0162, lots L2-L3) — le fragment
 * qui assemble les ZONES de spec SORTIES § 2 : barre d'outils unifiée,
 * bandeaux d'état (honnêteté règle 14), lecteur virtualisé avec poignée
 * à repères, bouton « ⇣ suivre » du suivi honnête ; lot L3 : bascule
 * brut ↔ regroupé, carte de résumé, en-tête collant de la tâche courante,
 * bandeau de consultation (ADR 0161.5) et liens source cliquables.
 *
 * Le fragment ne fait que RENDRE l'état du ViewModel et relayer les
 * gestes (règle 1 : aucune logique métier ici — la mécanique vit dans
 * `core:domain` et dans [LecteurSortiesViewModel]). Les surfaces (onglet
 * de la zone centrale, plein écran, panneau compact, volet tablette —
 * lots L5) l'embarquent comme UNE fenêtre sur le MÊME composant.
 *
 * Recherche : le champ déclenche [LecteurSortiesViewModel.definirRecherche]
 * (hors thread principal, anti-rebond de la frappe) ; un motif regex
 * invalide est AFFICHÉ en ligne (héritage `FiltreLogcat` — jamais
 * d'exception) et les correspondances masquées sont comptées dans N mais
 * leur saut est sauté.
 *
 * Sélection : appui long = ouvrir/étendre la sélection (toujours
 * explicite — spec § 3.6) ; bouton « copier » = copie du texte pur
 * (contrôleurs ANSI purgés) dans le presse-papiers. Une ligne PORTANT
 * une position cliquable s'ouvre au simple appui hors sélection (liens
 * source, spec § 4.3).
 */
@AndroidEntryPoint
@Suppress("TooManyFunctions")
public class FragmentLecteurSorties :
    BaseFragment<FragmentLecteurSortiesBinding>(),
    ControleurLecteurSorties {

    /** Le ViewModel du lecteur — scopé AU FRAGMENT (lot L4) : plusieurs
     *  lecteurs simultanés (onglet Logcat et onglet Journal du même
     *  panneau, banc) ont chacun LEUR instance et leur tampon. La famille
     *  arrive par les arguments (survivent à la rotation). */
    internal val viewModel: LecteurSortiesViewModel by viewModels()
    private lateinit var lecteur: LecteurSorties

    /** Le registre du port de saut au source (liens source — l'éditeur
     *  actif s'y enregistre, le lecteur ne le connaît pas, patron ADR
     *  0122). Injecté : la MÊME instance que celle de l'éditeur. */
    @javax.inject.Inject
    lateinit var registreSaut: RegistrePortSautSource

    /** Le geste HÔTE d'une ligne appuyée SANS position source (lot L4) —
     *  le chrome logcat y branche le saut au source des traces (R4) : le
     *  lecteur ne connaît ni l'éditeur ni les piles ; l'hôte décide du
     *  geste. `null` : le clic hors sélection ne fait rien (Gradle). */
    public var surLigneSansPosition: ((texte: String) -> Unit)? = null

    /** Anti-rebond de la frappe (la relance du ViewModel annule et
     *  relance la recherche — le champ reste maître, spec § 3.4). */
    private val antiRebond = Handler(Looper.getMainLooper())
    private var rechercheEnCours: Runnable? = null
    private var regexActif = false

    private val champRecherche: EditText
        get() = binding.champRecherche

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentLecteurSortiesBinding = FragmentLecteurSortiesBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        brancherLecteur()
        brancherBarre()
        brancherEtats()
        brancherRegroupement()
        // Le mode REGROUPÉ existe pour GRADLE (tâches, lot L3) et LOGCAT
        // (×N, traces — lot L5) ; la bascule disparaît ailleurs (JOURNAL)
        // : jamais une bascule qui prétend regrouper sans le faire
        // (honnêteté règle 14).
        binding.boutonRegrouper.isGone = !viewModel.modeDisponible
        // Les gestes PAR FAMILLE (honnêteté — jamais un bouton mort) :
        // les étiquettes existent pour Logcat et Journal (tags), le
        // pid/tid n'existe que pour le Logcat (spec SORTIES § 5.4).
        binding.boutonEtiquettes.isGone = viewModel.famille == FamilleSorties.GRADLE
        binding.boutonPidTid.isGone = viewModel.famille != FamilleSorties.LOGCAT
        // État vide honnête PAR FAMILLE (règle 14 — chaque état vide dit
        // pourquoi et propose la suite : logcat « sans adb », journal,
        // Gradle par défaut) ; il suit la taille du tampon affiché.
        binding.etatVideTitre.text = getString(titreVideDe(viewModel.famille))
        binding.etatVideMessage.text = getString(messageVideDe(viewModel.famille))
        viewModel.projection.collectWithLifecycle(viewLifecycleOwner) { projection ->
            binding.etatVide.isGone = projection.tailleTampon > 0
        }
        // Rotation : retrouver la position retenue (spec SORTIES § 3.11 —
        // pivoter ne perd ni position, ni filtre, ni recherche : le
        // ViewModel survit, la position est repoussée au nouveau lecteur).
        lecteur.positionRetenue = viewModel.positionRetenue
    }

    override fun onStop() {
        super.onStop()
        viewModel.positionRetenue = lecteur.premiereRangeeVisible()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        antiRebond.removeCallbacksAndMessages(null)
    }

    /** Le moteur de lecture (contrat [LecteurSorties], implémenté sur
     *  RecyclerView — ADR 0159) et ses gestes. */
    private fun brancherLecteur() {
        val adapter = LignesSortiesAdapter(
            surLigneAppuyee = { indice ->
                // Appui simple : étendre la sélection SI le mode est ouvert
                // (la copie reste toujours explicite — spec § 3.6). Hors
                // sélection : le geste HÔTE (logcat — le saut au source des
                // traces, R4), sinon rien (jamais un geste perdu).
                if (viewModel.selection.plage.value != null) {
                    viewModel.selection.selectionner(indice)
                } else {
                    val texte =
                        (viewModel.projection.value.rangees.getOrNull(indice) as? RangeeLecteur.Ligne)
                            ?.rangee?.textePur
                    if (texte != null) surLigneSansPosition?.invoke(texte)
                }
            },
            surLigneLongueAppuyee = { indice ->
                viewModel.selection.selectionner(indice)
            },
            surPositionSource = { position ->
                // Liens source (spec SORTIES § 4.3) : le geste se RELAYE
                // au ViewModel — la surface reste un rendu d'état.
                viewModel.demanderOuverture(position)
            },
            surEnteteAppuyee = { indiceGroupe ->
                viewModel.basculerRepli(indiceGroupe)
            },
        )
        lecteur = LecteurSortiesRecycler(
            liste = binding.listeSorties,
            poignee = binding.poigneeSorties,
            adapter = adapter,
            surSuiviChange = { suit -> binding.boutonSuivre.isGone = suit },
            surZoomPolice = { taille ->
                viewModel.regler { options -> options.copy(taillePoliceSp = taille) }
            },
        )
        viewModel.projection.collectWithLifecycle(viewLifecycleOwner) { projection ->
            lecteur.afficherProjection(projection)
            majCollant()
        }
        viewModel.reglages.collectWithLifecycle(viewLifecycleOwner) { options ->
            lecteur.definirReglages(options, viewModel.projection.value.origineMs)
        }
        viewModel.selection.plage.collectWithLifecycle(viewLifecycleOwner) { plage ->
            lecteur.definirSelection(plage)
            binding.boutonCopier.isVisible = plage != null
        }
        viewModel.marques.collectWithLifecycle(viewLifecycleOwner) { marques ->
            lecteur.definirMarques(marques)
            // Les boutons du saut d'erreur n'existent que s'il y a des
            // erreurs (jamais un geste mort — spec SORTIES § 5.4).
            binding.boutonErreurPrecedente.isGone = marques.erreurs.isEmpty()
            binding.boutonErreurSuivante.isGone = marques.erreurs.isEmpty()
        }
        viewModel.allerA.collectWithLifecycle(viewLifecycleOwner) { indice ->
            lecteur.allerALaRangee(indice)
        }
        // L'en-tête collant suit le défilement (une écoute LOCALE — le
        // moteur du lot L2 reste inchangé).
        binding.listeSorties.addOnScrollListener(
            object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                    majCollant()
                }
            },
        )
        binding.boutonSuivre.setOnClickListener { lecteur.reprendreSuivi() }
    }

    /** Les bascules de la barre d'outils (recherche, regroupement, pause,
     *  réglages). */
    private fun brancherBarre() {
        champRecherche.addTextChangedListener(
            object : SimpleTextWatcher() {
                override fun afterTextChanged(texte: Editable?) {
                    rechercheEnCours?.let { antiRebond.removeCallbacks(it) }
                    val relance = Runnable {
                        viewModel.definirRecherche(champRecherche.text.toString(), regexActif)
                    }
                    rechercheEnCours = relance
                    antiRebond.postDelayed(relance, ANTI_REBOND_MS)
                }
            },
        )
        binding.boutonRegex.setOnClickListener {
            regexActif = !regexActif
            // Bascule visuelle par accent (même discipline que le filtre
            // Logcat — l'état ne dépend jamais d'une couleur seule :
            // TalkBack annonce via la description du bouton).
            val attributAccent = if (regexActif) {
                androidx.appcompat.R.attr.colorPrimary
            } else {
                com.google.android.material.R.attr.colorOnSurfaceVariant
            }
            binding.boutonRegex.setTextColor(
                MaterialColors.getColor(binding.boutonRegex, attributAccent, 0),
            )
            viewModel.definirRecherche(champRecherche.text.toString(), regexActif)
        }
        binding.boutonCorrespondancePrecedente.setOnClickListener { viewModel.naviguerCorrespondance(-1) }
        binding.boutonCorrespondanceSuivante.setOnClickListener { viewModel.naviguerCorrespondance(+1) }
        // Saut « erreur précédente / suivante » (spec SORTIES § 5.4, lot
        // L5) : le départ est la première rangée VISIBLE du lecteur — le
        // parcours boucle sur les repères d'erreurs (jamais un geste mort).
        binding.boutonErreurPrecedente.setOnClickListener {
            viewModel.naviguerErreur(-1, lecteur.premiereRangeeVisible())
        }
        binding.boutonErreurSuivante.setOnClickListener {
            viewModel.naviguerErreur(+1, lecteur.premiereRangeeVisible())
        }
        binding.boutonEtiquettes.setOnClickListener { ouvrirMenuEtiquettes() }
        binding.boutonPidTid.setOnClickListener {
            // Option d'affichage pid/tid (spec SORTIES § 5.4) — l'état
            // actif s'accentue (même discipline que le bouton regex :
            // jamais la couleur seule, TalkBack lit la description).
            val actif = !viewModel.reglages.value.affichePidTid
            viewModel.regler { options -> options.copy(affichePidTid = actif) }
            val attributAccent = if (actif) {
                androidx.appcompat.R.attr.colorPrimary
            } else {
                com.google.android.material.R.attr.colorOnSurfaceVariant
            }
            binding.boutonPidTid.setTextColor(
                MaterialColors.getColor(binding.boutonPidTid, attributAccent, 0),
            )
        }
        binding.boutonRegrouper.setOnClickListener {
            // Bascule brut ↔ regroupé (spec SORTIES § 4) : la bascule est
            // symétrique, le tampon ne bouge jamais.
            viewModel.definirMode(
                if (viewModel.mode.value == ModeLecture.REGROUPE) ModeLecture.BRUT else ModeLecture.REGROUPE,
            )
        }
        binding.boutonPause.setOnClickListener { viewModel.basculerPause() }
        binding.boutonHorodatage.setOnClickListener {
            // Relatif → absolu → masqué (trois états, un bouton).
            val actuelles = viewModel.reglages.value
            when {
                !actuelles.afficheHorodatage ->
                    viewModel.regler { it.copy(afficheHorodatage = true, horodatageAbsolu = false) }

                !actuelles.horodatageAbsolu ->
                    viewModel.regler { it.copy(horodatageAbsolu = true) }

                else ->
                    viewModel.regler { it.copy(afficheHorodatage = false, horodatageAbsolu = false) }
            }
        }
        binding.boutonNumeros.setOnClickListener {
            viewModel.regler { it.copy(afficheNumeros = !it.afficheNumeros) }
        }
        binding.boutonRetourLigne.setOnClickListener {
            viewModel.regler { it.copy(retourALaLigne = !it.retourALaLigne) }
        }
        binding.boutonSelection.setOnClickListener {
            // Le mode sélection se quitte par le même bouton (les taps
            // étendent la sélection pendant qu'il est ouvert).
            if (viewModel.selection.plage.value != null) {
                viewModel.selection.quitter()
            }
        }
        binding.boutonCopier.setOnClickListener {
            val texte = viewModel.selection.texteDe(viewModel.projection.value.rangees)
            if (texte != null) {
                val pressePapiers =
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                pressePapiers.setPrimaryClip(ClipData.newPlainText("sorties", texte))
                viewModel.selection.quitter()
                Retour.snackbar(binding.root, requireContext().getString(R.string.sorties_copie_effectuee))
            }
        }
        binding.boutonEffacer.setOnClickListener { viewModel.effacer() }
    }

    /** Les états publiés : bandeaux d'honnêteté, compteur n/N, pause. */
    private fun brancherEtats() {
        viewModel.projection.collectWithLifecycle(viewLifecycleOwner) { projection ->
            val pertes = projection.pertes
            binding.bandeauPertes.isGone = pertes <= 0L
            if (pertes > 0L) {
                binding.bandeauPertes.setEtat(NiveauEtat.ERREUR)
                binding.bandeauPertes.titre = getString(R.string.sorties_bandeau_pertes, pertes)
            }
            val masquees = projection.masquees
            binding.bandeauMasquees.isGone = masquees <= 0
            if (masquees > 0) {
                binding.bandeauMasquees.setEtat(NiveauEtat.AVERTISSEMENT)
                binding.bandeauMasquees.titre = getString(R.string.sorties_bandeau_masquees, masquees)
            }
        }
        viewModel.recherche.collectWithLifecycle(viewLifecycleOwner) { etat ->
            binding.erreurMotif.isVisible = etat?.motifInvalide == true
            binding.compteurRecherche.isGone = etat == null
            if (etat != null) {
                binding.compteurRecherche.text = getString(
                    R.string.sorties_recherche_compteur,
                    etat.rang + 1,
                    etat.correspondances.size,
                )
            }
            binding.boutonCorrespondancePrecedente.isGone = etat == null
            binding.boutonCorrespondanceSuivante.isGone = etat == null
        }
        viewModel.enPauseDirect.collectWithLifecycle(viewLifecycleOwner) { enPause ->
            binding.bandeauPause.isGone = !enPause
            binding.boutonPause.contentDescription = if (enPause) {
                getString(R.string.sorties_reprise)
            } else {
                getString(R.string.sorties_pause)
            }
            binding.boutonPause.setIconResource(
                if (enPause) R.drawable.ic_reprise else R.drawable.ic_pause_sorties,
            )
        }
        viewModel.demandeOuverture.collectWithLifecycle(viewLifecycleOwner) { position ->
            // Liens source : la demande va au port de l'éditeur ACTIF
            // (RegistrePortSautSource — le lecteur ne connaît pas
            // l'éditeur, patron ADR 0122). Port absent : honnête.
            val port = registreSaut.actif()
            if (port != null) {
                port.ouvrir(position.fichier, position.ligne, position.colonne)
            } else {
                Retour.snackbar(
                    binding.root,
                    requireContext().getString(R.string.sorties_saut_sans_editeur),
                )
            }
        }
    }

    /** La carte de résumé (mode regroupé — l'état ÉVÉNEMENTS fait foi,
     *  spec SORTIES § 4.1 ; jamais compté sur le texte seul). */
    private fun majResume(regroupe: jo.codeide.core.domain.EtatRegroupement?) {
        val resume = regroupe?.resume
        binding.carteResume.isGone = resume == null
        if (resume == null) return
        val verdict = when (resume.verdict) {
            jo.codeide.core.domain.VerdictExecution.REUSSI -> getString(R.string.sorties_resume_reussi)
            jo.codeide.core.domain.VerdictExecution.ECHOUE -> getString(R.string.sorties_resume_echoue)
            jo.codeide.core.domain.VerdictExecution.ANNULE -> getString(R.string.sorties_resume_annule)
            null -> getString(R.string.sorties_resume_en_cours)
        }
        val duree =
            resume.dureeMs?.let { getString(R.string.sorties_resume_duree, it / DUREE_SECONDE.toDouble()) } ?: ""
        binding.resumeVerdict.text = listOf(verdict, duree).filter { it.isNotBlank() }.joinToString(separator = " · ")
        binding.resumeCompteurs.text = texteCompteurs(resume)
        binding.resumeGravites.text = getString(
            R.string.sorties_resume_gravites,
            resume.erreurs,
            resume.avertissements,
            resume.totalLignes,
        )
    }

    /** La ligne des compteurs de tâches (les `null` disparaissent — un
     *  compteur inconnu ne s'affiche jamais comme zéro, règle 14). */
    private fun texteCompteurs(resume: jo.codeide.core.domain.ResumeExecution): String {
        val compteurs = resume.compteurs ?: return getString(R.string.sorties_resume_taches_aucune)
        val morceaux = ArrayList<String>()
        compteurs.actionnables?.let { morceaux.add(getString(R.string.sorties_resume_taches, it)) }
        compteurs.executees?.let { morceaux.add(getString(R.string.sorties_resume_executees, it)) }
        compteurs.aJour?.let { morceaux.add(getString(R.string.sorties_resume_a_jour, it)) }
        compteurs.depuisCache?.let { morceaux.add(getString(R.string.sorties_resume_cache, it)) }
        compteurs.sautees?.let { morceaux.add(getString(R.string.sorties_resume_sautees, it)) }
        return if (morceaux.isEmpty()) {
            getString(
                R.string.sorties_resume_taches_aucune,
            )
        } else {
            morceaux.joinToString(separator = " · ")
        }
    }

    /** L'en-tête COLLANT : la tâche de la première rangée visible (le
     *  regroupement et les rangées publiées sont les seuls détenteurs). */
    private fun majCollant() {
        val regroupe = viewModel.regroupement.value ?: return
        if (viewModel.mode.value != ModeLecture.REGROUPE) {
            binding.collantTache.isGone = true
            return
        }
        val projection = viewModel.projection.value
        val premiere = if (projection.rangees.isEmpty()) -1 else lecteur.premiereRangeeVisible()
        val groupe = if (premiere < 0 || premiere >= projection.rangees.size) {
            regroupe.groupes.lastOrNull()
        } else {
            when (val rangee = projection.rangees[premiere]) {
                is RangeeLecteur.EnteteTache -> rangee.groupe

                // Le Logcat regroupé n'a pas d'en-tête collant (pas de
                // tâche — le résumé du regroupement reste `null`).
                is RangeeLecteur.EnteteLogcat -> null

                is RangeeLecteur.Ligne -> groupeDe(regroupe, projection, premiere)
            }
        }
        binding.collantTache.isGone = groupe == null
        groupe?.let { binding.collantTache.text = it.chemin }
    }

    /** Le groupe qui PORTE la rangée visible [rang] — retrouvé par la
     *  POSITION DE TAMPON de la ligne (vérité d'index ; les lignes hors
     *  groupe n'ont pas d'en-tête collant). */
    private fun groupeDe(
        regroupe: jo.codeide.core.domain.EtatRegroupement,
        projection: ProjectionLecteur,
        rang: Int,
    ): jo.codeide.core.domain.GroupeTache? {
        val position = (projection.rangees.getOrNull(rang) as? RangeeLecteur.Ligne)?.position ?: return null
        return regroupe.groupes.firstOrNull { groupe -> position in groupe.debut..groupe.fin }
    }

    /** Les états L3 du lecteur : mode (bascule/collant), résumé
     *  regroupé, consultation. Les liens source sont traités UNE fois
     *  (brancherEtats — le double traitement de L3 publiait la demande
     *  deux fois : attrapé au lot L4). */
    private fun brancherRegroupement() {
        viewModel.mode.collectWithLifecycle(viewLifecycleOwner) { mode ->
            // La bascule s'accorde : en mode BRUT le bouton PROPOSE le
            // regroupement ; en REGROUPE il propose le retour au brut.
            // L'en-tête COLLANT est un concept de TÂCHES (GRADLE) — le
            // regroupement Logcat (×N, traces) n'en a pas.
            binding.boutonRegrouper.text = getString(
                if (mode == ModeLecture.REGROUPE) R.string.sorties_mode_brut else R.string.sorties_mode_regroupe,
            )
            binding.collantTache.isGone =
                mode != ModeLecture.REGROUPE || viewModel.famille != FamilleSorties.GRADLE
            majCollant()
        }
        viewModel.regroupement.collectWithLifecycle(viewLifecycleOwner) { regroupe ->
            majResume(regroupe)
            majCollant()
        }
        viewModel.enConsultation.collectWithLifecycle(viewLifecycleOwner) { enConsultation ->
            binding.bandeauConsultation.isGone = !enConsultation
        }
    }

    public companion object {
        /** Délai d'anti-rebond de la recherche (ms). */
        private const val ANTI_REBOND_MS = 300L

        /** Millisecondes par seconde (durée du résumé). */
        private const val DUREE_SECONDE = 1_000L

        /** Identifiant de l'entrée d'aide du menu d'étiquettes. */
        private const val ID_AIDE_ETIQUETTES = 0

        /** Crée un lecteur pour la famille [famille] — l'argument du
         *  fragment porte la famille au ViewModel ([CLE_FAMILLE] via
         *  `SavedStateHandle`) et SURVIT à la rotation. */
        public fun nouvelle(famille: FamilleSorties): FragmentLecteurSorties = FragmentLecteurSorties().apply {
            arguments = Bundle().apply { putString(CLE_FAMILLE, famille.name) }
        }
    }

    // ---- Façade publique (ControleurLecteurSorties) — les hôtes (écran
    // Journaux, panneau) commandent le lecteur par CE contrat. ----

    override fun definirRecherche(texte: String, regex: Boolean) {
        champRecherche.setText(texte)
        regexActif = regex
        viewModel.definirRecherche(texte, regex)
    }

    override fun definirFiltre(filtre: FiltreSorties) {
        viewModel.definirFiltre(filtre)
    }

    override fun effacer() {
        viewModel.effacer()
    }

    override fun basculerPause() {
        viewModel.basculerPause()
    }

    override fun texteVisible(dernieres: Int): String? {
        val lignes = viewModel.projection.value.rangees
            .filterIsInstance<RangeeLecteur.Ligne>()
            .takeLast(dernieres)
            .map { it.rangee.textePur }
        return lignes.joinToString(separator = "\n").ifEmpty { null }
    }

    /** Les libellés d'état vide PAR FAMILLE (honnêteté — chaque famille
     *  dit son périmètre, jamais un message de Gradle sur le Logcat). */
    private fun titreVideDe(famille: FamilleSorties): Int = when (famille) {
        FamilleSorties.GRADLE -> R.string.sorties_vide_titre
        FamilleSorties.LOGCAT -> R.string.sorties_vide_titre_logcat
        FamilleSorties.JOURNAL -> R.string.sorties_vide_titre_journal
    }

    private fun messageVideDe(famille: FamilleSorties): Int = when (famille) {
        FamilleSorties.GRADLE -> R.string.sorties_vide_message
        FamilleSorties.LOGCAT -> R.string.sorties_vide_message_logcat
        FamilleSorties.JOURNAL -> R.string.sorties_vide_message_journal
    }

    /**
     * Le menu « # » des puces d'étiquettes (spec SORTIES § 5.4, lot L5)
     * — les tags de la fenêtre avec leur COMPTE, chaque puce cycle
     * inclure / exclure / neutre ([LecteurSortiesViewModel.basculerEtiquette]).
     * Le menu SE ROUVRE après chaque bascule (les états sont relus du
     * filtre — la vérité du filtre) ; « retour » le referme. Comptage au
     * GESTE (hors chemin chaud — un passage sur la fenêtre, jamais à
     * chaque lot). Les masquées restent comptées par le bandeau du
     * filtre : les compteurs ici disent le CONTENU de la fenêtre.
     */
    private fun ouvrirMenuEtiquettes() {
        viewLifecycleOwner.lifecycleScope.launch {
            val suggestions = viewModel.etiquettesComptees()
            if (suggestions.isEmpty()) {
                Retour.snackbar(
                    binding.root,
                    requireContext().getString(R.string.sorties_etiquettes_aucune),
                )
            } else {
                montrerMenuEtiquettes(suggestions)
            }
        }
    }

    /** Le menu lui-même (reconstruit À CHAQUE bascule — les états des
     *  puces viennent des suggestions RELUES, jamais mémorisés). */
    private fun montrerMenuEtiquettes(suggestions: List<EtiquetteComptee>) {
        val menu = PopupMenu(requireContext(), binding.boutonEtiquettes)
        // L'aide du cycle en tête (désactivée — jamais cliquée).
        menu.menu.add(Menu.NONE, ID_AIDE_ETIQUETTES, 0, R.string.sorties_etiquettes_aide)
            .apply { isEnabled = false }
        suggestions.forEachIndexed { index, suggestion ->
            val libelle = when (suggestion.etat) {
                EtatPuceEtiquette.EXCLUE -> getString(
                    R.string.sorties_etiquette_exclue,
                    suggestion.etiquette,
                    suggestion.compte,
                )

                else -> getString(
                    R.string.sorties_etiquette_compte,
                    suggestion.etiquette,
                    suggestion.compte,
                )
            }
            menu.menu.add(Menu.NONE, index + 1, Menu.NONE, libelle).apply {
                isCheckable = true
                isChecked = suggestion.etat == EtatPuceEtiquette.INCLUSE
            }
        }
        menu.setOnMenuItemClickListener { item ->
            if (item.itemId == ID_AIDE_ETIQUETTES) return@setOnMenuItemClickListener true
            val suggestion = suggestions.getOrNull(item.itemId - 1)
                ?: return@setOnMenuItemClickListener true
            viewModel.basculerEtiquette(suggestion.etiquette)
            // Le menu se rouvre : les états viennent d'être cyclés — le
            // comptage est relu au geste (le tampon n'a pas bougé ; le
            // verrou sérialise avec la bascule).
            viewLifecycleOwner.lifecycleScope.launch {
                montrerMenuEtiquettes(viewModel.etiquettesComptees())
            }
            true
        }
        menu.show()
    }
}
