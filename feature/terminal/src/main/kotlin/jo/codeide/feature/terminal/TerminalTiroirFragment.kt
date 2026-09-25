package jo.codeide.feature.terminal

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.TerminalSessionSummary
import jo.codeide.core.terminalruntime.TerminalRuntime
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.ControleurTerminalTiroir
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.terminal.databinding.FragmentTerminalTiroirBinding
import jo.codeide.feature.terminal.databinding.VueCarteSessionTerminalBinding
import jo.codeide.feature.terminal.databinding.VuePanneauTerminalBinding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Fragment **Terminal du tiroir** (v0.32.2, ADR 0053) : entête à la
 * maquette (emblème violet, « Terminal », sous-titre « N sessions
 * actives · termux », ligne d'actions : nouvelle session + modes) et
 * corps à deux visages —
 * - **liste** : une carte par session (chemin · heure, badge d'état,
 *   boutons « agrandir dans le tiroir » et « plein écran », toucher la
 *   carte l'agrandit) ;
 * - **rendu réel** : un panneau interactif ([com.termux.view.TerminalView])
 *   par session affichée — split vertical (empilé), split en colonnes
 *   (côte à côte) ou plein écran **dans le tiroir** (une session, retour
 *   liste). Le clavier étendu partagé écrit dans le panneau focalisé.
 *
 * Contrairement à la v0.32.0 (carte de métadonnées seules), le tiroir
 * rend de vraies sessions : le fragment vit donc dans `feature:terminal`
 * — seul feature autorisé à dépendre du runtime — et l'activité d'édition
 * le crée via [jo.codeide.core.ui.FabriqueFragmentTerminalTiroir]. Les
 * commandes qui réclament l'état de l'espace de travail (créer dans le
 * dossier du projet, plein écran, installation) partent vers le
 * [ControleurTerminalTiroir] résolu sur l'activité hôte en `onAttach`.
 *
 * Mémoire : un panneau par session **affichée** (jamais par session
 * existante — la liste reste des cartes légères), et l'écran plein écran
 * garde son TerminalView unique rebranché. Les reconstructions ne
 * surviennent que sur changement de mode ou de liste de sessions (les
 * émissions throttlées de sortie ne reconstruisent rien : l'empreinte
 * compare identifiants et libellés, jamais les aperçus de sortie).
 *
 * Exemption detekt ciblée (règle 16 du prompt maître) : rendu par zone
 * (entête, cartes, panneaux, clavier) — même découpage que les autres
 * fragments de rendu.
 */
@Suppress("TooManyFunctions")
@AndroidEntryPoint
class TerminalTiroirFragment : Fragment() {
    private var liaisonAmorce: FragmentTerminalTiroirBinding? = null
    private val liaison get() = liaisonAmorce!!

    /** ViewModel du tiroir (mode d'affichage + sessions du registre). */
    private val viewModel: TerminalTiroirViewModel by viewModels()

    /** Sessions réelles Termux (rebranchement des panneaux). */
    @Inject
    lateinit var runtime: TerminalRuntime

    /** Navigation plein écran de repli si l'hôte n'est pas contrôleur. */
    @Inject
    lateinit var navigateur: AppNavigator

    /** Commandes de l'hôte (créer dans le projet, plein écran, install). */
    private var controleur: ControleurTerminalTiroir? = null

    /** Panneaux vivants, indexés par identifiant de session. */
    private val panneaux = LinkedHashMap<String, PanneTerminal>()

    /** Empreinte du dernier rendu des cartes (ids + libellés + états). */
    private var empreinteCartes: String? = null

    /** Empreinte du dernier rendu des panneaux (mode + ids + libellés). */
    private var empreintePanneaux: String? = null

    override fun onAttach(contexte: Context) {
        super.onAttach(contexte)
        controleur = contexte as? ControleurTerminalTiroir
    }

    override fun onCreateView(
        inflateur: LayoutInflater,
        conteneur: ViewGroup?,
        etat: Bundle?,
    ): View {
        liaisonAmorce = FragmentTerminalTiroirBinding.inflate(inflateur, conteneur, false)
        return liaison.root
    }

    override fun onViewCreated(
        vue: View,
        etat: Bundle?,
    ) {
        brancherEntete()
        brancherEtatsVides()
        liaison.clavierEtenduTiroir.ecouteur =
            ClavierEtenduView.EcouteurClavier { sequence -> ecrireSessionFocalisee(sequence) }
        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { rendre(it) }

        // Rendu vivant : chaque sortie repeint les panneaux branchés (et
        // rattrape un attachement manqué — même garantie que l'écran
        // plein écran, v0.31.5).
        runtime.observeSorties().collectWithLifecycle(viewLifecycleOwner) { repeindrePanneaux() }
    }

    override fun onDestroyView() {
        panneaux.clear()
        empreintePanneaux = null
        empreinteCartes = null
        liaisonAmorce = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------------
    // Entête : nouvelle session, modes d'affichage.
    // ------------------------------------------------------------------

    /** Branche les boutons de l'entête (§ maquette : ligne d'actions). */
    private fun brancherEntete() {
        liaison.boutonNouvelleSessionTiroir.setOnClickListener {
            controleur?.creerSessionProjet() ?: navigateur.openTerminal(null)
        }
        liaison.boutonModeListe.setOnClickListener {
            viewModel.onAction(ActionTerminalTiroir.ChoisirMode(ModeTerminalTiroir.Liste))
        }
        liaison.boutonModeSplitVertical.setOnClickListener {
            viewModel.onAction(ActionTerminalTiroir.ChoisirMode(ModeTerminalTiroir.SplitVertical))
        }
        liaison.boutonModeSplitColonnes.setOnClickListener {
            viewModel.onAction(ActionTerminalTiroir.ChoisirMode(ModeTerminalTiroir.SplitColonnes))
        }
    }

    /** Branche les états vides (aucune session / non installé). */
    private fun brancherEtatsVides() {
        liaison.boutonCreerSessionTiroir.setOnClickListener {
            controleur?.creerSessionProjet()
        }
        liaison.boutonInstallerTerminalTiroir.setOnClickListener {
            controleur?.ouvrirInstallationBootstrap() ?: navigateur.openBootstrapInstall()
        }
    }

    // ------------------------------------------------------------------
    // Rendu : entête + aiguillage liste / panneaux.
    // ------------------------------------------------------------------

    /** Applique un état complet : sous-titre, modes, corps, clavier. */
    private fun rendre(etat: EtatTerminalTiroir) {
        val vivantes = etat.sessions.count { it.isAlive }
        liaison.sousTitreTerminalTiroir.text =
            resources.getQuantityString(
                R.plurals.tiroir_terminal_sessions_actives,
                vivantes,
                vivantes,
            )

        rendreBoutonsModes(etat)

        val rendu =
            etat.bootstrapInstalle &&
                etat.sessions.isNotEmpty() &&
                etat.mode != ModeTerminalTiroir.Liste
        liaison.zoneListeSessions.isVisible = !rendu
        liaison.conteneurSplit.isVisible = rendu
        liaison.clavierEtenduTiroir.isVisible = rendu

        liaison.blocNonInstalle.isVisible = !etat.bootstrapInstalle
        liaison.blocAucuneSession.isVisible = etat.bootstrapInstalle && etat.sessions.isEmpty()
        if (rendu) {
            rendrePanneaux(etat)
        } else {
            rendreCartes(etat)
        }
    }

    /** Sélection, teinte et disponibilité des boutons de mode. */
    private fun rendreBoutonsModes(etat: EtatTerminalTiroir) {
        val plusieurs = etat.sessions.size >= SEUIL_SESSIONS_SPLIT
        val accent = ColorStateList.valueOf(couleur(R.color.tiroir_terminal_accent))
        val neutre = ColorStateList.valueOf(couleur(R.color.tiroir_terminal_texte_2))

        liaison.boutonModeListe.isSelected = etat.mode == ModeTerminalTiroir.Liste
        liaison.boutonModeSplitVertical.isSelected = etat.mode == ModeTerminalTiroir.SplitVertical
        liaison.boutonModeSplitColonnes.isSelected = etat.mode == ModeTerminalTiroir.SplitColonnes
        liaison.boutonModeListe.imageTintList = if (liaison.boutonModeListe.isSelected) accent else neutre
        liaison.boutonModeSplitVertical.imageTintList =
            if (liaison.boutonModeSplitVertical.isSelected) accent else neutre
        liaison.boutonModeSplitColonnes.imageTintList =
            if (liaison.boutonModeSplitColonnes.isSelected) accent else neutre

        // Le split n'a de sens qu'avec plusieurs sessions : boutons
        // atténués + indice explicite sinon (la liste reste disponible).
        for (bouton in listOf(liaison.boutonModeSplitVertical, liaison.boutonModeSplitColonnes)) {
            bouton.isEnabled = plusieurs
            bouton.alpha = if (plusieurs) ALPHA_ACTIF else ALPHA_ATTENUEE
        }
        liaison.hintSplitIndisponible.isVisible =
            !plusieurs && etat.bootstrapInstalle && etat.sessions.isNotEmpty()
    }

    // ------------------------------------------------------------------
    // Mode liste : cartes de sessions.
    // ------------------------------------------------------------------

    /** Reconstruit les cartes si l'empreinte (ids + libellés + états) a changé. */
    private fun rendreCartes(etat: EtatTerminalTiroir) {
        val empreinte =
            etat.sessions.joinToString(SEPARATEUR_EMPREINTE) { session ->
                "${session.id}=${session.label}=${session.isAlive}=${session.workingDirectoryPath}"
            }
        if (empreinte == empreinteCartes) return
        empreinteCartes = empreinte

        liaison.listeSessions.removeAllViews()
        for (session in etat.sessions) {
            val carte =
                VueCarteSessionTerminalBinding.inflate(
                    layoutInflater,
                    liaison.listeSessions,
                    true,
                )
            remplirCarte(carte, session)
        }
    }

    /** Remplit une carte de session (libellé, détail, badge, actions). */
    private fun remplirCarte(
        carte: VueCarteSessionTerminalBinding,
        session: TerminalSessionSummary,
    ) {
        carte.libelleSessionCarte.text = session.label
        carte.detailSessionCarte.text =
            getString(
                R.string.tiroir_terminal_detail_session,
                session.workingDirectoryPath,
                heure(session.createdAt),
            )
        carte.root.contentDescription =
            getString(R.string.tiroir_terminal_carte_cd, session.label)

        val vivante = session.isAlive
        carte.badgeEtatSession.text =
            getString(
                if (vivante) {
                    R.string.tiroir_terminal_session_vivante
                } else {
                    R.string.tiroir_terminal_session_terminee
                },
            )
        carte.badgeEtatSession.setTextColor(
            couleur(
                if (vivante) {
                    R.color.terminal_etat_vivante
                } else {
                    R.color.terminal_etat_terminee
                },
            ),
        )
        carte.badgeEtatSession.backgroundTintList =
            ColorStateList.valueOf(
                couleur(
                    if (vivante) {
                        R.color.tiroir_terminal_vert_doux
                    } else {
                        R.color.tiroir_terminal_gris_doux
                    },
                ),
            )

        // Toucher la carte l'agrandit dans le tiroir ; les boutons
        // explicites font l'un (agrandir) ou l'autre (plein écran).
        val agrandir = {
            viewModel.onAction(
                ActionTerminalTiroir.ChoisirMode(ModeTerminalTiroir.PleinEcranDansTiroir(session.id)),
            )
        }
        carte.root.setOnClickListener { agrandir() }
        carte.boutonAgrandirSession.setOnClickListener { agrandir() }
        carte.boutonAgrandirSession.contentDescription =
            getString(R.string.tiroir_terminal_agrandir_dans_tiroir_cd, session.label)
        carte.boutonPleinEcranSession.setOnClickListener { ouvrirPleinEcran() }
        carte.boutonPleinEcranSession.contentDescription =
            getString(R.string.tiroir_terminal_plein_ecran_cd, session.label)
    }

    // ------------------------------------------------------------------
    // Modes de rendu : panneaux (split vertical, colonnes, plein écran).
    // ------------------------------------------------------------------

    /** Reconstruit les panneaux si le mode ou la liste de sessions a changé. */
    private fun rendrePanneaux(etat: EtatTerminalTiroir) {
        val mode = etat.mode
        val pleinEcran = mode is ModeTerminalTiroir.PleinEcranDansTiroir
        val sessions =
            if (pleinEcran) {
                etat.sessions.filter { it.id == mode.sessionId }
            } else {
                etat.sessions
            }
        val empreinte =
            mode::class.simpleName +
                SEPARATEUR_EMPREINTE +
                (mode as? ModeTerminalTiroir.PleinEcranDansTiroir)?.sessionId +
                SEPARATEUR_EMPREINTE +
                sessions.joinToString(SEPARATEUR_EMPREINTE) { "${it.id}=${it.label}" }
        if (empreinte == empreintePanneaux) return
        empreintePanneaux = empreinte

        val vertical = mode != ModeTerminalTiroir.SplitColonnes
        liaison.conteneurSplit.orientation =
            if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        liaison.conteneurSplit.removeAllViews()
        panneaux.clear()
        for (session in sessions) {
            val panne = creerPanneau(session, pleinEcranDansTiroir = pleinEcran)
            val parametres =
                if (vertical) {
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    )
                } else {
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f,
                    )
                }
            liaison.conteneurSplit.addView(panne.liaison.root, parametres)
        }
    }

    /** Crée un panneau interactif branché sur sa session. */
    private fun creerPanneau(
        session: TerminalSessionSummary,
        pleinEcranDansTiroir: Boolean,
    ): PanneTerminal {
        val liaisonPanne =
            VuePanneauTerminalBinding.inflate(layoutInflater, liaison.conteneurSplit, false)
        liaisonPanne.libellePanneau.text = session.label

        liaisonPanne.boutonRetourListePanneau.isVisible = pleinEcranDansTiroir
        liaisonPanne.boutonAgrandirPanneau.isVisible = !pleinEcranDansTiroir
        liaisonPanne.boutonRetourListePanneau.setOnClickListener {
            viewModel.onAction(ActionTerminalTiroir.ChoisirMode(ModeTerminalTiroir.Liste))
        }
        liaisonPanne.boutonAgrandirPanneau.setOnClickListener {
            viewModel.onAction(
                ActionTerminalTiroir.ChoisirMode(
                    ModeTerminalTiroir.PleinEcranDansTiroir(session.id),
                ),
            )
        }
        liaisonPanne.boutonPleinEcranPanneau.setOnClickListener { ouvrirPleinEcran() }
        liaisonPanne.boutonAgrandirPanneau.contentDescription =
            getString(R.string.tiroir_terminal_agrandir_dans_tiroir_cd, session.label)
        liaisonPanne.boutonPleinEcranPanneau.contentDescription =
            getString(R.string.tiroir_terminal_plein_ecran_cd, session.label)

        val vue = liaisonPanne.vueTerminalPanneau
        val panne = PanneTerminal(session.id, liaisonPanne)
        vue.setTerminalViewClient(
            ClientVueTerminal(
                vue = vue,
                lireCtrl = { liaisonAmorce?.clavierEtenduTiroir?.ctrlActif ?: false },
                lireAlt = { liaisonAmorce?.clavierEtenduTiroir?.altActif ?: false },
                surEmulateurPret = { vue.appliquerThemeRendu() },
                zoomer = { facteur -> zoomerPanneau(panne, facteur) },
            ),
        )
        brancherPanneau(panne)
        return panne
    }

    /** Branche (et rebranche) un panneau sur sa session Termux. */
    private fun brancherPanneau(panne: PanneTerminal) {
        val vue = panne.liaison.vueTerminalPanneau
        val session = runtime.sessionFor(panne.sessionId) ?: return
        vue.attachSession(session)
        vue.appliquerThemeRendu()
        if (panne.tailleRenduePx == AUCUNE_TAILLE) {
            panne.tailleRenduePx = policeInitialePx()
        }
        vue.setTextSize(panne.tailleRenduePx)
        vue.onScreenUpdated()
    }

    /** Repeint les panneaux branchés ; rattrape les attachements manqués. */
    private fun repeindrePanneaux() {
        for (panne in panneaux.values) {
            val vue = panne.liaison.vueTerminalPanneau
            if (vue.mEmulator == null) {
                brancherPanneau(panne)
            } else {
                vue.onScreenUpdated()
            }
        }
    }

    /** Zoom pincé d'un panneau (contrat Termux : le client applique). */
    private fun zoomerPanneau(
        panne: PanneTerminal,
        facteur: Float,
    ): Boolean {
        if (facteur in SEUIL_ZOOM_NEGIGEABLE..SEUIL_ZOOM_NOTABLE) return false
        val courante = panne.tailleRenduePx
        val cible = (courante * facteur).toInt().coerceIn(zoomMinPx, zoomMaxPx)
        if (cible != courante) {
            panne.tailleRenduePx = cible
            panne.liaison.vueTerminalPanneau.setTextSize(cible)
        }
        return true
    }

    // ------------------------------------------------------------------
    // Clavier étendu : la séquence part au panneau focalisé.
    // ------------------------------------------------------------------

    /** Écrit la séquence dans la session du panneau qui a le focus. */
    private fun ecrireSessionFocalisee(sequence: String) {
        val cible = panneaux.values.firstOrNull { it.liaison.vueTerminalPanneau.hasFocus() } ?: return
        runtime.sessionFor(cible.sessionId)?.write(sequence)
    }

    // ------------------------------------------------------------------
    // Divers.
    // ------------------------------------------------------------------

    /** Ouvre l'écran plein écran (répertoire projet résolu par l'hôte). */
    private fun ouvrirPleinEcran() {
        controleur?.ouvrirEcranTerminal() ?: navigateur.openTerminal(null)
    }

    /** Couleur locale du module. */
    private fun couleur(ressource: Int): Int = ContextCompat.getColor(requireContext(), ressource)

    /** Heure de création d'une session (HH:mm, fuseau de l'appareil). */
    private fun heure(instant: Instant): String =
        DateTimeFormatter
            .ofPattern(FORMAT_HEURE)
            .withZone(ZoneId.systemDefault())
            .format(instant)

    /** Taille initiale des panneaux (réglage « moyenne », 15 dp). */
    private fun policeInitialePx(): Int = (POLICE_INITIALE_DP * resources.displayMetrics.density).toInt()

    /** Borne basse du zoom (pixels, densité courante). */
    private val zoomMinPx: Int
        get() = (ZOOM_MIN_DP * resources.displayMetrics.density).toInt()

    /** Borne haute du zoom (pixels, densité courante). */
    private val zoomMaxPx: Int
        get() = (ZOOM_MAX_DP * resources.displayMetrics.density).toInt()

    /** Panneau de rendu : liaison + identifiant de session + taille de police. */
    private class PanneTerminal(
        val sessionId: String,
        val liaison: VuePanneauTerminalBinding,
        var tailleRenduePx: Int = AUCUNE_TAILLE,
    )

    private companion object {
        /** Sessions minimum pour activer les splits (§ retour v0.32.2). */
        const val SEUIL_SESSIONS_SPLIT = 2

        /** Séparateur d'empreinte de rendu. */
        const val SEPARATEUR_EMPREINTE = "|"

        /** Alpha des boutons de split inactifs (pas assez de sessions). */
        const val ALPHA_ACTIF = 1f

        /** Alpha des boutons de split atténués. */
        const val ALPHA_ATTENUEE = 0.38f

        /** Taille initiale des panneaux (dp — réglage « moyenne »). */
        const val POLICE_INITIALE_DP = 15

        /** Pincements négligeables (facteur accumulé, bornes Termux). */
        const val SEUIL_ZOOM_NEGIGEABLE = 0.9f

        /** Pincements notables (facteur accumulé, bornes Termux). */
        const val SEUIL_ZOOM_NOTABLE = 1.1f

        /** Bornes du zoom pincé (dp, converties en pixels à l'usage). */
        const val ZOOM_MIN_DP = 10

        /** Borne haute du zoom pincé (dp). */
        const val ZOOM_MAX_DP = 30

        /** Format de l'heure du détail de carte. */
        const val FORMAT_HEURE = "HH:mm"

        /** Valeur sentinelle « aucune taille appliquée ». */
        const val AUCUNE_TAILLE = -1
    }
}
