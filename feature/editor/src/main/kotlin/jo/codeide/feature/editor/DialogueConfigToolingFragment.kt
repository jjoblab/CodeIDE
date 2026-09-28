package jo.codeide.feature.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.model.AppSettings
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.FragmentConfigToolingBinding

/**
 * Écran de configuration du tooling Gradle (v3) — plein écran AU-DESSUS de
 * l'espace de travail, ouvert par l'engrenage de l'onglet Sortie
 * (on reste dans le contexte du build, contrairement à une navigation vers
 * les Paramètres qui quitterait l'éditeur).
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
class DialogueConfigToolingFragment : DialogFragment() {
    private var liaisonAmorce: FragmentConfigToolingBinding? = null

    /** Liaison de la vue courante (correctif n°9 : plus de `!!` — un accès
     *  après destruction de la vue échoue avec un diagnostic lisible). */
    private val liaison
        get() =
            checkNotNull(liaisonAmorce) {
                "liaison de la configuration tooling indisponible — vue détruite ?"
            }

    /** ViewModel scopé au dialogue (son propre ViewModelStore). */
    private val viewModel: ConfigToolingViewModel by viewModels()

    /** Anti-fausses actions : le rendu programme les interrupteurs sans
     *  déclencher leurs écouteurs, et signale ses `setText` du champ
     *  d'arguments à [saisieArguments] (correctif n°10). */
    private var renduEnCours = false

    /** Saisie du champ d'arguments (correctif n°10 : rendu et frappe se
     *  distinguent — plus jamais de drapeau armé par le rendu). */
    private val saisieArguments = SaisieArguments()

    override fun onCreate(etat: Bundle?) {
        // Plein écran au-dessus de l'espace de travail : fenêtre non
        // flottante sur le thème de l'app — l'éditeur reste dessous à la
        // fermeture, on ne quitte JAMAIS le contexte du build.
        super.onCreate(etat)
        setStyle(STYLE_NORMAL, jo.codeide.core.ui.R.style.Theme_CodeIDE_PleinEcran)
    }

    override fun onCreateView(
        inflateur: LayoutInflater,
        conteneur: ViewGroup?,
        etat: Bundle?,
    ): View {
        liaisonAmorce = FragmentConfigToolingBinding.inflate(inflateur, conteneur, false)
        return liaison.root
    }

    override fun onViewCreated(
        vue: View,
        etat: Bundle?,
    ) {
        liaison.toolbarConfigTooling.setNavigationOnClickListener { dismiss() }

        liaison.interrupteurAfficherTaches.setOnCheckedChangeListener { _, coche ->
            if (!renduEnCours) viewModel.definirAfficherTaches(coche)
        }
        liaison.interrupteurHorsLigne.setOnCheckedChangeListener { _, coche ->
            if (!renduEnCours) viewModel.definirHorsLigne(coche)
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

        viewModel.reglages.collectWithLifecycle(viewLifecycleOwner) { rendreReglages(it) }
        viewModel.etatVivant.collectWithLifecycle(viewLifecycleOwner) { rendreEtatVivant(it) }
    }

    /** Rendu idempotent des réglages (le DataStore est la seule vérité).
     *  Correctif n°10 : le `setText` vit DANS la fenêtre [renduEnCours] —
     *  son rappel `doAfterTextChanged` reconnaît une écriture de rendu et
     *  n'arme pas la validation. */
    private fun rendreReglages(reglages: AppSettings) {
        renduEnCours = true
        try {
            liaison.interrupteurAfficherTaches.isChecked = reglages.toolingAfficherTaches
            liaison.interrupteurHorsLigne.isChecked = reglages.toolingHorsLigne

            // Le champ ne se réécrit QUE hors saisie : effacer le texte d'un
            // utilisateur en train de taper serait le pire des rendus.
            if (saisieArguments.renduPeutReecrire()) {
                liaison.saisieArguments.setText(reglages.toolingArguments)
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

    /** Libellé localisé de l'état de connexion. */
    private fun libelleConnexion(connexion: EtatConnexion): String =
        when (connexion) {
            EtatConnexion.CONNECTEE -> getString(R.string.editor_config_connexion_connecte)
            EtatConnexion.EN_CONNEXION -> getString(R.string.editor_config_connexion_demarrage)
            EtatConnexion.DECONNECTEE -> getString(R.string.editor_config_connexion_deconnecte)
            EtatConnexion.ECHOUEE -> getString(R.string.editor_config_connexion_echoue)
        }

    override fun onDestroyView() {
        liaisonAmorce = null
        super.onDestroyView()
    }
}
