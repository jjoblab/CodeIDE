package jo.codeide.feature.terminal

import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.termux.terminal.TerminalSession
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.TerminalSessionSummary
import jo.codeide.core.model.TaillePoliceTerminal
import jo.codeide.core.terminalruntime.TerminalRuntime
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.applySystemBarsAndImeInsets
import jo.codeide.core.ui.applySystemBarsInsets
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.terminal.databinding.ActivityTerminalBinding
import jo.codeide.feature.terminal.databinding.VueOngletSessionBinding
import javax.inject.Inject

/**
 * Écran plein écran du terminal (Terminal T5, prompt Terminal-1,
 * section 5.1) : toolbar (« Terminal » + nouvelle session), onglets de
 * sessions défilants (même langage visuel que les onglets de fichiers de
 * l'éditeur : pastille d'état, libellé court, fermeture, `+` final),
 * **un seul [com.termux.view.TerminalView]** rebranché sur la session
 * active — jamais un par onglet, la mémoire en dépend — et rangée de
 * touches étendues interne ([ClavierEtenduView]).
 *
 * V0.31.5 (retour d'appareil réel) : le rendu suit les sorties au fil
 * de l'eau (signal [TerminalRuntime.observeSorties] → `onScreenUpdated`,
 * même architecture que Termux — c'est l'activité qui repeint), les
 * onglets se resynchronisent **par diff** (plus de reconstruction
 * complète à chaque émission d'état : un tap pendant une commande qui
 * débitait ne pouvait jamais atterrir — les vues d'onglets étaient
 * détruites sous le doigt toutes les 250 ms) et le pincement zoome la
 * police (contrat Termux : le client applique, la vue n'applique
 * jamais — voir [ClientVueTerminal]).
 *
 * Appui long sur un onglet : renommer, dupliquer (même répertoire de
 * travail), fermer. Fermeture : confirmation si une commande semble en
 * cours (heuristique du ViewModel), sinon fermeture directe — le shell
 * est **réellement** terminé (jamais « juste masqué »).
 *
 * Le bouton retour ferme l'écran : les sessions survivent en
 * arrière-plan via le service foreground de `core:terminal-runtime`.
 * La rotation ne perd rien : l'état vit dans le ViewModel et le registre
 * singleton ; le rendu se rebranche à la recréation.
 *
 * Exemption detekt ciblée (règle 16 du prompt maître) : rendu par zone
 * (onglets, état vide, rendu, thème, police) + dialogues — même découpage
 * que les activités/fragments de rendu précédents (`NewProjectFragment`).
 */
@Suppress("TooManyFunctions")
@AndroidEntryPoint
class TerminalActivity : AppCompatActivity() {
    private val viewModel: TerminalViewModel by viewModels()

    @Inject
    lateinit var runtime: TerminalRuntime

    @Inject
    lateinit var navigateur: AppNavigator

    private lateinit var liaison: ActivityTerminalBinding

    /** Identifiant de la session actuellement rendue (anti-rebranchement). */
    private var idSessionRendue: String? = null

    /** Garde anti-réentrance pendant la resynchronisation des onglets. */
    private var renduEnCours = false

    /** Onglet « + » final (créé une fois, jamais recréé). */
    private var ongletPlus: TabLayout.Tab? = null

    /** Un pincement a eu lieu : le réglage ne reprend la main qu'à son
     * prochain changement réel (v0.31.5 — sinon chaque émission d'état
     * écrasait le zoom par la taille du réglage 250 ms plus tard). */
    private var zoomManuel = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        liaison = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(liaison.root)

        liaison.toolbarTerminal.applySystemBarsInsets(top = true, bottom = false)
        liaison.clavierEtendu.applySystemBarsAndImeInsets(top = false, bottom = true)
        setSupportActionBar(liaison.toolbarTerminal)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        liaison.toolbarTerminal.setNavigationOnClickListener { navigateur.goBack() }

        liaison.vueTerminal.setTerminalViewClient(
            ClientVueTerminal(
                vue = liaison.vueTerminal,
                clavier = liaison.clavierEtendu,
                surEmulateurPret = { appliquerThemeRendu() },
                zoomer = ::zoomer,
            ),
        )

        // V0.31.5 — rendu vivant : chaque changement de sortie repeint la vue
        // branchée (l'aperçu throttlé des métadonnées ne suffit pas : le texte
        // n'apparaissait qu'au prochain layout). `onScreenUpdated` est sans
        // effet si l'émulateur n'est pas en place ; repeindre la vue affichée
        // alors que la session changée est une autre est sans coût.
        runtime.observeSorties().collectWithLifecycle(this) {
            liaison.vueTerminal.onScreenUpdated()
        }

        liaison.boutonNouvelleSession.setOnClickListener {
            viewModel.onAction(ActionTerminal.NouvelleSession)
        }
        liaison.boutonCreerSession.setOnClickListener {
            viewModel.onAction(ActionTerminal.NouvelleSession)
        }
        liaison.clavierEtendu.ecouteur =
            ClavierEtenduView.EcouteurClavier { sequence ->
                sessionActive()?.write(sequence)
            }
        liaison.ongletsSessions.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    if (renduEnCours) return
                    val position = tab.position
                    val sessions = viewModel.uiState.value.sessions
                    when {
                        // Onglet « + » final : création, la sélection
                        // repartira sur la nouvelle session active.
                        position >= sessions.size -> {
                            viewModel.onAction(ActionTerminal.NouvelleSession)
                        }

                        else -> {
                            viewModel.onAction(ActionTerminal.OuvrirSession(sessions[position].id))
                        }
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            },
        )

        viewModel.uiState.collectWithLifecycle(this) { etat -> rendre(etat) }
        viewModel.effets.collectWithLifecycle(this) { effet -> traiter(effet) }
    }

    /** Session réellement rendue (objet Termux), ou `null`. */
    private fun sessionActive(): TerminalSession? {
        val id = viewModel.uiState.value.idSessionActive ?: return null
        return runtime.sessionFor(id)
    }

    /** Applique un état complet : onglets, état vide, rendu, police. */
    private fun rendre(etat: EtatTerminal) {
        renduEnCours = true
        synchroniserOnglets(etat)
        liaison.etatVideTerminal.isVisible = etat.sessions.isEmpty()
        liaison.vueTerminal.isVisible = etat.sessions.isNotEmpty()
        brancher(etat.idSessionActive)
        appliquerTaillePolice(etat.taillePolice)
        renduEnCours = false
    }

    /** Reconstruit les onglets **par diff** (v0.31.5).
     *
     * Les aperçus de session changent toutes les 250 ms pendant une
     * commande : l'ancienne reconstruction complète (removeAllTabs +
     * addTab) détruisait les vues d'onglets sous le doigt de
     * l'utilisateur — un tap n'atterrissait jamais (retour d'appareil
     * réel : « je ne peux pas naviguer entre les sessions »). Ici :
     * - les onglets de sessions existants sont MIS À JOUR en place
     *   (libellé, pastille, écouteurs — l'identifiant positionnel peut
     *   avoir glissé après une fermeture) — via
     *   [vueOngletSessionBordable], JAMAIS le « + » (v0.31.6 : binder la
     *   vue d'un onglet qui n'est pas une session plantait — retour
     *   4a4526aa, premier onglet occupé par le « + » seul) ;
     * - les nouveaux sont insérés AVANT le « + » ; les disparus retirés ;
     * - le « + » est créé une fois ;
     * - la sélection ne bouge que si elle diffère de la session active.
     */
    private fun synchroniserOnglets(etat: EtatTerminal) {
        val onglets = liaison.ongletsSessions
        val sessions = etat.sessions

        // Retraits (fin → début : indices stables) — le « + » reste en
        // dernière position, hors de la plage retirée.
        val nombreSessionsAffichees = onglets.tabCount - (if (ongletPlus != null) 1 else 0)
        for (position in (nombreSessionsAffichees - 1) downTo sessions.size) {
            onglets.getTabAt(position)?.let(onglets::removeTab)
        }

        // Ajouts et mises à jour : position par position.
        for ((position, session) in sessions.withIndex()) {
            val vueOnglet =
                vueOngletSessionBordable(onglets, ongletPlus, position)
                    ?: VueOngletSessionBinding.inflate(layoutInflater).also { frais ->
                        onglets.addTab(
                            onglets.newTab().setCustomView(frais.root),
                            position,
                            false,
                        )
                    }
            configurerOnglet(vueOnglet, session, etat)
        }

        // Onglet « + » final : création rapide (icône teintée par le thème).
        if (ongletPlus == null) {
            val plus = ImageView(this)
            plus.setImageResource(R.drawable.ic_nouvelle_session)
            plus.imageTintList =
                ColorStateList.valueOf(
                    MaterialColors.getColor(
                        liaison.ongletsSessions,
                        com.google.android.material.R.attr.colorOnSurface,
                    ),
                )
            val onglet = onglets.newTab().setCustomView(plus)
            ongletPlus = onglet
            onglets.addTab(onglet)
        }

        // Sélection : uniquement si elle diffère (le rappel de sélection
        // programmatique est écarté par le garde renduEnCours).
        val indexActif = sessions.indexOfFirst { it.id == etat.idSessionActive }
        if (indexActif >= 0 && onglets.selectedTabPosition != indexActif) {
            onglets.getTabAt(indexActif)?.select()
        }
    }

    /** Branche le contenu et les écouteurs d'un onglet de session. */
    private fun configurerOnglet(
        vueOnglet: VueOngletSessionBinding,
        session: TerminalSessionSummary,
        etat: EtatTerminal,
    ) {
        vueOnglet.libelleSession.text = session.label
        teinterPastille(vueOnglet.pastilleEtatSession, session.isAlive)
        brancherInteractionsOnglet(
            vueOnglet = vueOnglet,
            ouvrirSession = { viewModel.onAction(ActionTerminal.OuvrirSession(session.id)) },
            fermerSession = { viewModel.onAction(ActionTerminal.FermerSession(session.id)) },
            ouvrirMenuContextuel = { ancre -> menuContextuel(ancre, session.id) },
        )
        // Onglet actif : la sélection du TabLayout suit indexActif ; la
        // vue marque l'état pour l'accessibilité et le contraste.
        vueOnglet.root.isSelected = session.id == etat.idSessionActive
    }

    /** Teinte la pastille d'état (vivante : émeraude ; terminée : grise). */
    private fun teinterPastille(
        pastille: View,
        vivante: Boolean,
    ) {
        val couleur =
            ContextCompat.getColor(
                this,
                if (vivante) R.color.terminal_etat_vivante else R.color.terminal_etat_terminee,
            )
        pastille.background?.setTint(couleur)
    }

    /** Menu contextuel d'un onglet (appui long, section 5). */
    private fun menuContextuel(
        ancre: View,
        sessionId: String,
    ) {
        val menu = PopupMenu(this, ancre)
        menu.menuInflater.inflate(R.menu.menu_session, menu.menu)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_renommer_session -> {
                    dialogueRenommage(sessionId)
                    true
                }

                R.id.action_dupliquer_session -> {
                    viewModel.onAction(ActionTerminal.DupliquerSession(sessionId))
                    true
                }

                R.id.action_fermer_session -> {
                    viewModel.onAction(ActionTerminal.FermerSession(sessionId))
                    true
                }

                else -> {
                    false
                }
            }
        }
        menu.show()
    }

    /** Dialogue de renommage d'une session. */
    private fun dialogueRenommage(sessionId: String) {
        val champ = TextInputEditText(this)
        champ.hint = getString(R.string.terminal_renommer_indice)
        champ.inputType = android.text.InputType.TYPE_CLASS_TEXT
        champ.setText(
            viewModel.uiState.value.sessions
                .firstOrNull { it.id == sessionId }
                ?.label
                .orEmpty(),
        )
        champ.setSelection(champ.text?.length ?: 0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.terminal_renommer_titre)
            .setView(champ)
            .setPositiveButton(R.string.terminal_renommer_valider) { _, _ ->
                viewModel.onAction(ActionTerminal.RenommerSession(sessionId, champ.text?.toString().orEmpty()))
            }.setNegativeButton(R.string.terminal_renommer_annuler, null)
            .show()
    }

    /** Traite un effet ponctuel. */
    private fun traiter(effet: EffetTerminal) {
        when (effet) {
            is EffetTerminal.DemanderConfirmationFermeture -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.terminal_fermer_titre)
                    .setMessage(getString(R.string.terminal_fermer_message, effet.libelle))
                    .setPositiveButton(R.string.terminal_fermer_confirmer) { _, _ ->
                        viewModel.onAction(ActionTerminal.ConfirmerFermeture(effet.sessionId))
                    }.setNegativeButton(R.string.terminal_fermer_annuler, null)
                    .show()
            }
        }
    }

    /**
     * Rebranche le `TerminalView` **unique** sur la session active — le
     * cœur du « un seul rendu » (même principe que l'éditeur). Sans
     * changement d'identifiant, rien à faire : le signal de sorties
     * (observeSorties) repeint le transcript au fil de l'eau.
     */
    @Suppress("ReturnCount") // Clauses de garde : une par cas non rendable (règle 16).
    private fun brancher(idSession: String?) {
        if (idSession == idSessionRendue) return
        idSessionRendue = idSession
        if (idSession == null) {
            liaison.vueTerminal.visibility = View.INVISIBLE
            return
        }
        val session = runtime.sessionFor(idSession)
        if (session == null) {
            // Identifiant inconnu du runtime (session déjà fermée) :
            // l'état vide ou la prochaine émission prennent le relais.
            liaison.vueTerminal.visibility = View.INVISIBLE
            return
        }
        liaison.vueTerminal.attachSession(session)
        appliquerThemeRendu()
        // Le rebranchement doit s'afficher IMMÉDIATEMENT (v0.31.5) :
        // attachSession passe par updateSize → invalidate, mais un
        // repaint explicite garantit le contenu de la nouvelle session
        // dès ce frame — même garantie que le signal de sorties.
        liaison.vueTerminal.onScreenUpdated()
    }

    /**
     * Applique le thème de l'app au rendu : les couleurs courantes vivent
     * dans l'émulateur (`mColors.mCurrentColors`, disposition jackpal —
     * 259 entrées, indices 256/257/258 = premier plan/arrière-plan/curseur)
     * ; la palette par défaut de Termux est sombre, le thème clair de
     * l'application réécrit ces trois entrées (ressources
     * `values`/`values-night`).
     */
    private fun appliquerThemeRendu() {
        val fond = ContextCompat.getColor(this, R.color.terminal_fond)
        val texte = ContextCompat.getColor(this, R.color.terminal_texte)
        liaison.vueTerminal.setBackgroundColor(fond)
        val emulator = liaison.vueTerminal.mEmulator ?: return
        val couleurs = emulator.mColors.mCurrentColors
        couleurs[INDICE_PREMIER_PLAN] = texte
        couleurs[INDICE_ARRIERE_PLAN] = fond
        couleurs[INDICE_CURSEUR] = texte
        liaison.vueTerminal.onScreenUpdated()
    }

    /** Applique la taille de police à chasse fixe (réglage dédié T5).
     *
     * Un zoom manuel (pincement) prend la main jusqu'au prochain
     * changement RÉEL du réglage : les émissions d'état (toutes les
     * 250 ms pendant une commande) ne doivent pas écraser la taille
     * pincée (v0.31.5).
     */
    private fun appliquerTaillePolice(taille: TaillePoliceTerminal) {
        val dp =
            when (taille) {
                TaillePoliceTerminal.PETITE -> POLICE_PETITE_DP
                TaillePoliceTerminal.MOYENNE -> POLICE_MOYENNE_DP
                TaillePoliceTerminal.GRANDE -> POLICE_GRANDE_DP
            }
        val pixels =
            TypedValue
                .applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    dp.toFloat(),
                    resources.displayMetrics,
                ).toInt()
        if (zoomManuel || pixels == tailleRenduePx) return
        tailleRenduePx = pixels
        liaison.vueTerminal.setTextSize(pixels)
    }

    /**
     * Applique le zoom pincé (v0.31.5) — appelé par [ClientVueTerminal]
     * avec le facteur ACCUMULÉ du geste (contrat Termux vérifié sur le
     * bytecode : la vue n'applique jamais elle-même).
     *
     * @param facteur facteur accumulé depuis le début du geste.
     * @return `true` si le facteur a été consommé (le compteur de la vue
     * repart à 1.0f) ; `false` pour le laisser s'accumuler (pincement
     * négligeable, mêmes seuils que Termux).
     */
    private fun zoomer(facteur: Float): Boolean {
        if (facteur in SEUIL_ZOOM_NEGIGEABLE..SEUIL_ZOOM_NOTABLE) return false
        val courante = tailleCourantePx()
        val cible = (courante * facteur).toInt().coerceIn(zoomMinPx, zoomMaxPx)
        if (cible != courante) {
            tailleRenduePx = cible
            zoomManuel = true
            liaison.vueTerminal.setTextSize(cible)
        }
        return true
    }

    /** Taille courante de la vue (réglage ou zoom) — repli moyenne. */
    private fun tailleCourantePx(): Int =
        if (tailleRenduePx == AUCUNE_TAILLE) {
            TypedValue
                .applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    POLICE_MOYENNE_DP.toFloat(),
                    resources.displayMetrics,
                ).toInt()
        } else {
            tailleRenduePx
        }

    /** Borne basse du zoom (pixels, densité courante). */
    private val zoomMinPx: Int
        get() = (ZOOM_MIN_DP * resources.displayMetrics.density).toInt()

    /** Borne haute du zoom (pixels, densité courante). */
    private val zoomMaxPx: Int
        get() = (ZOOM_MAX_DP * resources.displayMetrics.density).toInt()

    private companion object {
        /** Indices de la palette Termux (disposition jackpal, 259 entrées). */
        const val INDICE_PREMIER_PLAN = 256
        const val INDICE_ARRIERE_PLAN = 257
        const val INDICE_CURSEUR = 258

        /** Tailles de police (dp) du réglage dédié minimal. */
        const val POLICE_PETITE_DP = 13
        const val POLICE_MOYENNE_DP = 15
        const val POLICE_GRANDE_DP = 19

        /** Valeur sentinelle « aucune taille appliquée ». */
        const val AUCUNE_TAILLE = -1

        /** Pincements négligeables (facteur accumulé, mêmes bornes que
         * Termux) : en dessous, le facteur continue de s'accumuler. */
        const val SEUIL_ZOOM_NEGIGEABLE = 0.9f
        const val SEUIL_ZOOM_NOTABLE = 1.1f

        /** Bornes du zoom pincé (dp, converties en pixels à l'usage). */
        const val ZOOM_MIN_DP = 10
        const val ZOOM_MAX_DP = 30
    }

    /** Dernière taille de police réellement appliquée à la vue (réglage ou
     * zoom pincé — évite re-créations de fonte et écrasements mutuels). */
    private var tailleRenduePx: Int = AUCUNE_TAILLE
}

/**
 * Vue d'onglet de session **bordable** à la position [position], ou
 * `null` s'il faut en insérer une fraîche (v0.31.6, retour d'appareil
 * réel 4a4526aa).
 *
 * Régression du diff v0.31.5 : la liste des sessions **grandit** alors
 * que le « + » occupe déjà la position visée (cas minimal : zéro
 * session → le « + » seul en position 0 → première création) — l'onglet
 * existant à cette position est le « + », dont la vue est un simple
 * `ImageView`. Binder cette vue en [VueOngletSessionBinding] levait
 * `NullPointerException: Missing required view with ID:
 * bouton_fermer_session` (la vue n'a PAS cet identifiant) — l'écran du
 * terminal plantait 60 ms après « session de terminal créée », à
 * CHAQUE création. La décision « bordable ou non » doit exclure
 * explicitement le « + » : seul un onglet de session porte la vue
 * attendue.
 *
 * @param onglets le TabLayout synchronisé.
 * @param ongletPlus l'onglet « + » (jamais bordable), ou `null` s'il
 * n'existe pas encore.
 * @param position position examinée (indice de session).
 * @return le binding sur la vue existante, ou `null` → insertion fraîche.
 */
@Suppress("ReturnCount") // Clauses de garde : position absente, « + », vue nulle (règle 16).
internal fun vueOngletSessionBordable(
    onglets: TabLayout,
    ongletPlus: TabLayout.Tab?,
    position: Int,
): VueOngletSessionBinding? {
    val onglet = onglets.getTabAt(position) ?: return null
    if (onglet === ongletPlus) return null
    return onglet.customView?.let(VueOngletSessionBinding::bind)
}

/**
 * Branche les interactions d'une vue d'onglet de session : tap (ouvrir
 * la session), appui long (menu contextuel), fermeture (v0.31.7, retour
 * d'appareil réel).
 *
 * Régression corrigée : la racine ne portait qu'un écouteur d'appui LONG
 * (menu renommer/dupliquer/fermer). Or une vue « longClickable » CONSOMME
 * AUSSI les taps simples — `View.onTouchEvent` retourne `true` dès que la
 * vue est clickable **ou** longClickable, et le `performClick()` du tap
 * ne faisait RIEN (aucun écouteur de clic posé) ; le `TabView` parent ne
 * voyait JAMAIS le geste → aucune sélection → « j'appuie sur l'onglet
 * pour changer de session, rien ne se passe ». La racine prend donc son
 * PROPRE écouteur de clic — même architecture que Termux, dont les vues
 * d'onglet gèrent elles-mêmes leur clic (l'écouteur du TabLayout reste
 * pour les sélections extérieures à la vue : appui hors de la zone de la
 * vue personnalisée, navigation clavier).
 *
 * @param vueOnglet liaison de la vue d'onglet.
 * @param ouvrirSession invoqué au tap simple sur l'onglet.
 * @param fermerSession invoqué au tap sur le bouton de fermeture.
 * @param ouvrirMenuContextuel invoqué à l'appui long (reçoit l'ancre).
 */
internal fun brancherInteractionsOnglet(
    vueOnglet: VueOngletSessionBinding,
    ouvrirSession: () -> Unit,
    fermerSession: () -> Unit,
    ouvrirMenuContextuel: (View) -> Unit,
) {
    vueOnglet.root.setOnClickListener { ouvrirSession() }
    vueOnglet.root.setOnLongClickListener {
        ouvrirMenuContextuel(it)
        true
    }
    vueOnglet.boutonFermerSession.setOnClickListener { fermerSession() }
}
