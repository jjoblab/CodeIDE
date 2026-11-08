package jo.codeide.feature.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.model.AppSettings
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.FragmentConfigToolingBinding
import java.util.Locale

/**
 * Écran de configuration du tooling Gradle (v3 ; v4, §3.3 : INTÉGRÉ au
 * conteneur de la console — plus de dialogue plein écran au-dessus de
 * l'éditeur), enfant de [PanneauConsoleFragment] : la configuration vit
 * DANS le panneau inférieur, flèche retour en tête — on ne quitte jamais
 * le contexte du build.
 *
 * Mêmes garde-fous que les sections des Paramètres (ADR 0059/0064) :
 * rendu IDEMPOTENT piloté par le DataStore (un interrupteur ne se
 * repositionne jamais sur un tampon local divergent), drapeau
 * [renduEnCours] contre les fausses actions de re-émission, réglages
 * persistés à l'INSTANT (le DataStore confirme le geste, jamais
 * l'inverse).
 *
 * Le champ d'arguments persiste à la fin de saisie (perte de focus ou
 * « Terminé ») : pas d'écriture DataStore par frappe.
 */
@AndroidEntryPoint
class PanneauConfigToolingFragment : Fragment() {
    private var liaisonAmorce: FragmentConfigToolingBinding? = null

    /** Liaison de la vue courante (correctif n°9 : plus de `!!` — un accès
     *  après destruction de la vue échoue avec un diagnostic lisible). */
    private val liaison
        get() =
            checkNotNull(liaisonAmorce) {
                "liaison de la configuration tooling indisponible — vue détruite ?"
            }

    /** ViewModel scopé au fragment (son propre ViewModelStore). */
    private val viewModel: ConfigToolingViewModel by viewModels()

    /** Demande de fermeture à l'hôte (retour à la console) — posée par
     *  [PanneauConsoleFragment] au moment de l'ajout. */
    var surFermeture: (() -> Unit)? = null

    /** Anti-fausses actions : le rendu programme les interrupteurs sans
     *  déclencher leurs écouteurs, et signale ses `setText` du champ
     *  d'arguments à [saisieArguments] (correctif n°10). */
    private var renduEnCours = false

    /** Saisie du champ d'arguments (correctif n°10 : rendu et frappe se
     *  distinguent — plus jamais de drapeau armé par le rendu). */
    private val saisieArguments = SaisieArguments()

    // ---- Phase 2 (INC-31/INC-32) : champs de la sync et du niveau de
    // journal, même discipline de saisie (persistés à la perte de focus /
    // « Terminé », jamais par frappe).

    private val saisieArgumentsSync = SaisieArguments()

    private val saisieNiveauJournal = SaisieArguments()

    // ---- Phase 1 (ADR 0152) : champs du banc, même discipline de saisie
    // (persistés à la perte de focus / « Terminé », jamais par frappe).

    private val saisieBancLignes = SaisieArguments()

    private val saisieBancCadence = SaisieArguments()

    private val saisieBancLongueur = SaisieArguments()

    private val saisieBancContenu = SaisieArguments()

    override fun onCreateView(inflateur: LayoutInflater, conteneur: ViewGroup?, etat: Bundle?): View {
        liaisonAmorce = FragmentConfigToolingBinding.inflate(inflateur, conteneur, false)
        return liaison.root
    }

    override fun onViewCreated(vue: View, etat: Bundle?) {
        // Retour en tête (§3.3) : la flèche ramène à la console du panneau.
        liaison.toolbarConfigTooling.setNavigationOnClickListener { surFermeture?.invoke() }

        liaison.interrupteurHorsLigne.setOnCheckedChangeListener { _, coche ->
            if (!renduEnCours) viewModel.definirHorsLigne(coche)
        }

        // Mission « Exécuter » R2 (ADR 0103) : interrupteur d'injection de
        // la bibliothèque applog-runtime dans les builds debug des projets.
        liaison.interrupteurInjectionApplog.setOnCheckedChangeListener { _, coche ->
            if (!renduEnCours) viewModel.definirInjectionAppLog(coche)
        }

        // Phase 1 (ADR 0152) : instrumentation de latence et banc « socket
        // seul » — s'appliquent à la prochaine vie du process orchestrateur
        // (redémarrage du serveur de build ou de l'app), dit dans les
        // descriptions.
        liaison.interrupteurInstruTransport.setOnCheckedChangeListener { _, coche ->
            if (!renduEnCours) viewModel.definirInstrumentationTransport(coche)
        }
        liaison.interrupteurBanc.setOnCheckedChangeListener { _, coche ->
            if (!renduEnCours) viewModel.definirBancSocketSeul(coche)
        }
        brancherChampBanc(liaison.saisieBancLignes, saisieBancLignes) { valeur ->
            viewModel.definirBancLignes(valeur.toIntOrNull() ?: viewModel.reglages.value.bancLignes)
        }
        brancherChampBanc(liaison.saisieBancCadence, saisieBancCadence) { valeur ->
            viewModel.definirBancCadenceMs(valeur.toIntOrNull() ?: viewModel.reglages.value.bancCadenceMs)
        }
        brancherChampBanc(liaison.saisieBancLongueur, saisieBancLongueur) { valeur ->
            viewModel.definirBancLongueurLigne(valeur.toIntOrNull() ?: viewModel.reglages.value.bancLongueurLigne)
        }
        brancherChampBanc(liaison.saisieBancContenu, saisieBancContenu) { valeur ->
            viewModel.definirBancContenu(valeur.ifBlank { viewModel.reglages.value.bancContenu })
        }

        val saisie = liaison.saisieArguments as TextInputEditText
        saisie.doAfterTextChanged { _ ->
            // Écrit au repos (focus perdu / « Terminé »), jamais par frappe :
            // seule la saisie de l'utilisateur arme la validation (correctif
            // n°10 — un `setText` de rendu déclenche AUSSI ce rappel).
            saisieArguments.surChangementTexte(pendantRendu = renduEnCours)
        }
        saisie.setOnFocusChangeListener { _, aLeFocus ->
            if (!aLeFocus) validerSaisie(saisie)
        }
        saisie.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                saisie.clearFocus()
                true
            } else {
                false
            }
        }

        // Phase 2 (INC-31) : arguments RÉSERVÉS à la sync — même discipline
        // que les arguments généraux (écrit au repos, jamais par frappe).
        brancherChampBanc(liaison.saisieArgumentsSync, saisieArgumentsSync) { valeur ->
            viewModel.definirArgumentsSync(valeur)
        }
        // Phase 2 (INC-32) : niveau de journal du serveur.
        brancherChampBanc(liaison.saisieNiveauJournal, saisieNiveauJournal) { valeur ->
            viewModel.definirNiveauJournal(valeur.trim().uppercase())
        }
        // Phase 2 (INC-33) : redémarrage à la demande — les réglages « à la
        // prochaine vie » s'appliquent aussitôt, la carte État montre la
        // reconnexion en direct.
        liaison.boutonRedemarrer.setOnClickListener { viewModel.redemarrerServeur() }

        viewModel.reglages.collectWithLifecycle(viewLifecycleOwner) { rendreReglages(it) }
        viewModel.etatVivant.collectWithLifecycle(viewLifecycleOwner) { rendreEtatVivant(it) }
    }

    override fun onDestroyView() {
        liaisonAmorce = null
        super.onDestroyView()
    }

    /** Rendu idempotent des réglages (le DataStore est la seule vérité).
     *  Correctif n°10 : le `setText` vit DANS la fenêtre [renduEnCours] —
     *  son rappel `doAfterTextChanged` reconnaît une écriture de rendu et
     *  n'arme pas la validation. */
    private fun rendreReglages(reglages: AppSettings) {
        renduEnCours = true
        try {
            liaison.interrupteurHorsLigne.isChecked = reglages.toolingHorsLigne
            liaison.interrupteurInjectionApplog.isChecked = reglages.injectionAppLog
            liaison.interrupteurInstruTransport.isChecked = reglages.instrumentationTransport
            liaison.interrupteurBanc.isChecked = reglages.bancSocketSeul
            liaison.parametresBanc.isVisible = reglages.bancSocketSeul

            // Le champ ne se réécrit QUE hors saisie : effacer le texte d'un
            // utilisateur en train de taper serait le pire des rendus.
            if (saisieArguments.renduPeutReecrire()) {
                liaison.saisieArguments.setText(reglages.toolingArguments)
            }
            if (saisieArgumentsSync.renduPeutReecrire()) {
                liaison.saisieArgumentsSync.setText(reglages.toolingArgumentsSync)
            }
            if (saisieNiveauJournal.renduPeutReecrire()) {
                liaison.saisieNiveauJournal.setText(reglages.niveauJournalServeur)
            }
            if (saisieBancLignes.renduPeutReecrire()) {
                liaison.saisieBancLignes.setText(
                    String.format(Locale.ROOT, "%d", reglages.bancLignes),
                )
            }
            if (saisieBancCadence.renduPeutReecrire()) {
                liaison.saisieBancCadence.setText(
                    String.format(Locale.ROOT, "%d", reglages.bancCadenceMs),
                )
            }
            if (saisieBancLongueur.renduPeutReecrire()) {
                liaison.saisieBancLongueur.setText(
                    String.format(Locale.ROOT, "%d", reglages.bancLongueurLigne),
                )
            }
            if (saisieBancContenu.renduPeutReecrire()) {
                liaison.saisieBancContenu.setText(reglages.bancContenu)
            }
        } finally {
            renduEnCours = false
        }
    }

    /** État vivant de l'orchestrateur (connexion + tas, en direct). */
    private fun rendreEtatVivant(etat: ConfigToolingViewModel.EtatVivantTooling) {
        liaison.valeurConnexion.text = libelleConnexion(etat.connexion)
        liaison.valeurConnexion.isVisible = true
        liaison.valeurTas.text =
            getString(
                R.string.editor_config_tas_format,
                etat.tas.moUtilises,
                etat.tas.moMax,
            )
        liaison.valeurTas.isVisible = etat.tas.moMax > 0
    }

    /** Persiste la saisie d'arguments (à la perte de focus / « Terminé »). */
    private fun validerSaisie(saisie: TextInputEditText) {
        if (saisieArguments.consommerPourValidation()) {
            viewModel.definirArguments(saisie.text?.toString().orEmpty())
        }
    }

    /**
     * Branche UN champ du banc (Phase 1) : même discipline que le champ
     * d'arguments — écrit au repos (perte de focus / « Terminé »), jamais
     * par frappe, le rendu ne réarme pas la validation.
     */
    private fun brancherChampBanc(saisie: TextInputEditText, etatSaisie: SaisieArguments, valider: (String) -> Unit) {
        saisie.doAfterTextChanged { _ ->
            etatSaisie.surChangementTexte(pendantRendu = renduEnCours)
        }
        saisie.setOnFocusChangeListener { _, aLeFocus ->
            if (!aLeFocus && etatSaisie.consommerPourValidation()) {
                valider(saisie.text?.toString().orEmpty())
            }
        }
        saisie.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                saisie.clearFocus()
                true
            } else {
                false
            }
        }
    }

    /** Libellé localisé de l'état de connexion. */
    private fun libelleConnexion(connexion: EtatConnexion): String = when (connexion) {
        EtatConnexion.CONNECTEE -> getString(R.string.editor_config_connexion_connecte)
        EtatConnexion.EN_CONNEXION -> getString(R.string.editor_config_connexion_demarrage)
        EtatConnexion.DECONNECTEE -> getString(R.string.editor_config_connexion_deconnecte)
        EtatConnexion.ECHOUEE -> getString(R.string.editor_config_connexion_echoue)
    }
}
