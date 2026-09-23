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
            ),
        )

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

    /** Reconstruit les onglets (peu nombreux : resynchronisation simple). */
    private fun synchroniserOnglets(etat: EtatTerminal) {
        val onglets = liaison.ongletsSessions
        onglets.removeAllTabs()
        for (session in etat.sessions) {
            val vueOnglet = VueOngletSessionBinding.inflate(layoutInflater)
            vueOnglet.libelleSession.text = session.label
            teinterPastille(vueOnglet.pastilleEtatSession, session.isAlive)
            vueOnglet.boutonFermerSession.setOnClickListener {
                viewModel.onAction(ActionTerminal.FermerSession(session.id))
            }
            vueOnglet.root.setOnLongClickListener {
                menuContextuel(it, session.id)
                true
            }
            onglets.addTab(onglets.newTab().setCustomView(vueOnglet.root), session.id == etat.idSessionActive)
        }
        // Onglet « + » final : création rapide (icône teintée par le thème).
        val plus = ImageView(this)
        plus.setImageResource(R.drawable.ic_nouvelle_session)
        plus.imageTintList =
            ColorStateList.valueOf(
                MaterialColors.getColor(
                    liaison.ongletsSessions,
                    com.google.android.material.R.attr.colorOnSurface,
                ),
            )
        liaison.ongletsSessions.addTab(liaison.ongletsSessions.newTab().setCustomView(plus))
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
     * changement d'identifiant, rien à faire : le transcript se rafraîchit
     * de lui-même.
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

    /** Applique la taille de police à chasse fixe (réglage dédié T5). */
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
        if (pixels == tailleAppliqueePx) return
        tailleAppliqueePx = pixels
        liaison.vueTerminal.setTextSize(pixels)
    }

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
    }

    /** Dernière taille de police appliquée (évite les re-créations de fonte). */
    private var tailleAppliqueePx: Int = AUCUNE_TAILLE
}
