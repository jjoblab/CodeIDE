package jo.codeide.feature.ai.agent

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.feature.ai.IaTiroirFragment
import jo.codeide.feature.ai.R
import jo.codeide.feature.ai.databinding.FragmentIaAgentBinding
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Écran Agent (IA-7b, spec E2 ; v0.195.0 — retour propriétaire) :
 * objectif (commit VIVANT — chaque frappe part au contrôleur, plus de
 * « objectif vide » avec un champ plein : le bouton Lancer committe
 * AVANT de lancer), bandeau de statut avec progression, étapes
 * numérotées, carte d'approbation EXÉCUTION, carte des changements
 * proposés, carte réponse finale et bandeau « Annuler cette
 * exécution ». AUCUNE logique ici : tout vit dans [ControleurAgentIa]
 * via [AgentViewModel] — la vue rend l'état.
 */
@AndroidEntryPoint
// Exemption detekt ciblée (règle 16) : TooManyFunctions — un rendu par
// SECTION de l'état UDF (statut, approbation, changements, réponse,
// pré-vérifications) : la surface de câblage de l'écran Agent.
@Suppress("TooManyFunctions")
public class AgentFragment : Fragment() {
    /** Navigation des actions de remédiation (A3 — pattern du chat). */
    @Inject
    internal lateinit var navigator: jo.codeide.core.ui.AppNavigator

    private val modele: AgentViewModel by viewModels()
    private var liaison: FragmentIaAgentBinding? = null

    /** Garde anti-écho : la restauration programmatique de l'objectif
     * ne doit pas repartir au contrôleur comme une frappe. */
    private var synchronisationObjectif = false

    /** Garde anti-écho du mode « Planifier d'abord » (A5 — miroir de l'objectif). */
    private var synchronisationPlan = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        liaison = FragmentIaAgentBinding.inflate(inflater, container, false)
        return liaison!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val liaison = liaison ?: return
        val etapes = AdapterEtapesAgent()
        liaison.listeEtapes.layoutManager = LinearLayoutManager(requireContext())
        liaison.listeEtapes.adapter = etapes

        // v0.195.0 : le champ commit à CHAQUE frappe (drapeau anti-écho
        // pour la restauration programmatique) — l'état du contrôleur
        // est TOUJOURS synchronisé avec ce qui est tapé.
        liaison.champObjectif.doAfterTextChanged { texte ->
            if (!synchronisationObjectif) {
                modele.definirObjectif(texte?.toString().orEmpty())
            }
        }
        // Touche « Terminé » du clavier : commit + clavier refermé.
        liaison.champObjectif.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                cacherClavier(liaison.champObjectif)
                liaison.champObjectif.clearFocus()
            }
            false
        }
        // LE correctif « objectif vide » (v0.195.0) : en mode tactile,
        // appuyer sur Lancer ne retire PAS le focus du champ — l'ancien
        // code ne commitait qu'à la PERTE de focus, l'objectif tapé ne
        // partait jamais : le contrôleur répondait « objectif vide ».
        // Le bouton committe le champ AVANT de lancer.
        liaison.boutonLancer.setOnClickListener {
            modele.definirObjectif(liaison.champObjectif.text?.toString().orEmpty())
            cacherClavier(liaison.champObjectif)
            modele.lancer()
        }
        // A5 (rapport IA lot 2) : mode « Planifier d'abord » + carte du
        // plan proposé — l'agent produit D'ABORD un plan, la validation
        // précède TOUTE exécution.
        installerActionsPlan(liaison)
        // Rapport IA, lot 3 : mode « revue » — l'exécution S'ARRÊTE à
        // chaque proposition d'écriture, l'utilisateur tranche, puis
        // « Continuer » reprend (miroir du mode « Planifier d'abord »).
        installerActionsRevue(liaison)
        liaison.boutonVoirDiff.setOnClickListener { basculerApercuDiff(liaison) }
        liaison.boutonArreter.setOnClickListener { modele.arreter() }
        liaison.boutonContinuer.setOnClickListener { modele.continuer() }
        liaison.boutonRefuser.setOnClickListener {
            modele.etat.value.demande
                ?.let { modele.refuser(it.id) }
        }
        liaison.boutonAutoriser.setOnClickListener {
            modele.etat.value.demande
                ?.let { modele.approuver(it.id) }
        }
        liaison.boutonExaminer.setOnClickListener {
            modele.ouvrirRevue { prete ->
                if (prete) {
                    (parentFragment as? IaTiroirFragment)?.ouvrirRevueChangements()
                }
            }
        }
        liaison.boutonToutAppliquer.setOnClickListener { modele.appliquerTout() }
        liaison.boutonRejeter.setOnClickListener { modele.rejeterChangements() }
        liaison.boutonAnnulerExecution.setOnClickListener { modele.annulerExecution() }
        // A3 (rapport IA lot 2) : l'action de remédiation de la dernière
        // erreur — Réessayer relance, les autres ouvrent les réglages IA
        // (l'action lue est CELLE de l'état au moment du clic).
        liaison.boutonActionErreur.setOnClickListener {
            modele.etat.value.actionErreur?.let(::appliquerActionErreur)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // A2 (rapport IA lot 2) : les puces se recalculent à
                // CHAQUE arrivée sur l'écran — l'état des réglages, de
                // la clé et du projet a pu changer pendant l'arrêt.
                modele.rafraichirPreVerifications()
                modele.etat.collect { etat -> rendre(etat, liaison, etapes) }
            }
        }
    }

    override fun onDestroyView() {
        liaison = null
        super.onDestroyView()
    }

    /** Câblage du mode « Planifier d'abord » et de la carte plan (A5). */
    private fun installerActionsPlan(liaison: FragmentIaAgentBinding) {
        liaison.interrupteurPlan.setOnCheckedChangeListener { _, coche ->
            if (!synchronisationPlan) {
                modele.basculerPlanPremier(coche)
            }
        }
        liaison.boutonValiderPlan.setOnClickListener { modele.validerPlan() }
        liaison.boutonRejeterPlan.setOnClickListener { modele.rejeterPlan() }
    }

    /** Garde anti-écho du mode « revue » (rapport IA, lot 3 — miroir du plan). */
    private var synchronisationRevue = false

    /** Câblage du mode « revue » (rapport IA, lot 3 — d'après la maquette). */
    private fun installerActionsRevue(liaison: FragmentIaAgentBinding) {
        liaison.interrupteurRevue.setOnCheckedChangeListener { _, coche ->
            if (!synchronisationRevue) {
                modele.basculerModeRevue(coche)
            }
        }
    }

    /** « Voir le diff » (rapport IA, lot 3 — d'après la maquette) : l'aperçu
     * INLINE par fichier se replie/déplie — une ligne par fichier, teintes
     * du thème (primaire pour les ajouts, erreur pour les retraits). */
    private fun basculerApercuDiff(liaison: FragmentIaAgentBinding) {
        liaison.texteDiffChangements.isVisible = !liaison.texteDiffChangements.isVisible
    }

    private fun rendre(etat: EtatAgentIa, liaison: FragmentIaAgentBinding, etapes: AdapterEtapesAgent) {
        if (liaison.champObjectif.text?.toString() != etat.objectif) {
            synchronisationObjectif = true
            liaison.champObjectif.setText(etat.objectif)
            synchronisationObjectif = false
        }
        val enCours = etat.statut == StatutAgentIa.EN_COURS
        liaison.boutonLancer.isVisible = !enCours
        liaison.boutonArreter.isVisible = enCours
        liaison.boutonContinuer.isVisible = etat.statut == StatutAgentIa.INTERROMPU
        // A2 (rapport IA lot 2) : Lancer désactivé tant qu'une puce est
        // en échec — les puces portent le motif, le message d'état le
        // précise au premier problème.
        val preVerifications = etat.preVerifications
        liaison.boutonLancer.isEnabled = enCours || preVerifications.pret
        rendrePreVerifications(preVerifications, liaison)
        liaison.texteLimites.text =
            getString(R.string.agent_limites, etat.etapes.size, etat.etapesMax, etat.modele)
        // Le champ verrouillé pendant l'exécution : l'objectif en cours
        // n'est plus modifiable à mi-parcours.
        liaison.champObjectif.isEnabled = !enCours

        rendreStatut(etat, liaison)
        rendreTempsReel(etat, liaison)
        rendrePlan(etat, liaison)
        rendreJetons(etat, liaison)
        etapes.submitList(etat.etapes)

        rendreApprobation(etat, liaison)

        rendreChangementsEtReponse(etat, liaison, enCours)

        // Bannière localisée (v0.195.0) : teinte d'erreur quand le
        // statut est ÉCHOUÉ — jamais de texte brut perdu au milieu.
        // A3 : bouton d'action de remédiation — visible quand l'état
        // en porte une (l'erreur typée a une remédiation), jamais de
        // bouton périmé (effacé à chaque nouvelle tentative).
        val actionErreur = etat.actionErreur
        liaison.boutonActionErreur.isVisible = actionErreur != null
        if (actionErreur != null) {
            liaison.boutonActionErreur.setText(actionErreur.libelleRes)
        }

        val message = etat.message
        liaison.texteMessage.isVisible = message != null
        if (message != null) {
            val texte =
                message.argument?.let { getString(message.ressource, it) }
                    ?: getString(message.ressource)
            liaison.texteMessage.text = texte
            val enEchec = etat.statut == StatutAgentIa.ECHOUE
            val contexte = liaison.root.context
            if (enEchec) {
                liaison.texteMessage.setBackgroundResource(R.drawable.fond_erreur_ia)
                liaison.texteMessage.setTextColor(couleurTheme(contexte, androidx.appcompat.R.attr.colorError))
            } else {
                liaison.texteMessage.setBackgroundResource(0)
                liaison.texteMessage.setTextColor(
                    couleurTheme(contexte, com.google.android.material.R.attr.colorOnSurface),
                )
            }
        }
    }

    /** Couleur d'attribut de thème résolue (le clair/sombre suit le thème). */
    private fun couleurTheme(contexte: Context, attribut: Int): Int {
        val valeur = android.util.TypedValue()
        contexte.theme.resolveAttribute(attribut, valeur, true)
        return valeur.data
    }

    /** Bandeau de statut (v0.195.0) : phase lisible + progression. */
    private fun rendreStatut(etat: EtatAgentIa, liaison: FragmentIaAgentBinding) {
        val visible = etat.statut != StatutAgentIa.INACTIF
        liaison.carteStatut.isVisible = visible
        if (!visible) {
            return
        }
        liaison.texteStatut.setText(
            when (etat.statut) {
                StatutAgentIa.EN_COURS -> R.string.agent_statut_en_cours
                StatutAgentIa.INTERROMPU -> R.string.agent_statut_interrompu
                StatutAgentIa.TERMINE -> R.string.agent_statut_termine
                StatutAgentIa.ECHOUE -> R.string.agent_statut_echoue
                StatutAgentIa.ANNULE -> R.string.agent_statut_annule
                StatutAgentIa.PLAN_ATTENDU -> R.string.agent_statut_plan_attendu
                StatutAgentIa.INACTIF -> R.string.agent_statut_en_cours
            },
        )
        liaison.progressionStatut.isVisible = etat.statut == StatutAgentIa.EN_COURS || etat.enApplication
    }

    /** Carte des changements proposés + aperçu du diff + bandeau
     * d'annulation + réponse finale (rapport IA, lot 3 — d'après la
     * maquette : « N fichiers · +A −S · C conflits » et « Voir le diff »
     * INLINE par fichier). */
    private fun rendreChangementsEtReponse(etat: EtatAgentIa, liaison: FragmentIaAgentBinding, enCours: Boolean) {
        val revue = etat.revue
        val changementsVisibles = revue != null && !revue.vide && etat.execution == null
        liaison.carteChangements.isVisible = changementsVisibles
        if (revue != null && changementsVisibles) {
            liaison.texteChangements.text =
                getString(
                    R.string.agent_changements_resumes,
                    revue.fichiers.size,
                    revue.lignesAjoutees,
                    revue.lignesSupprimees,
                    revue.conflits.size,
                )
            liaison.boutonToutAppliquer.isEnabled = revue.applicables.isNotEmpty()
            // Aperçu INLINE (rapport IA, lot 3) : une ligne par fichier,
            // SPANNÉE par teinte du thème (primaire = ajout, erreur =
            // retrait) — les couleurs suivent le clair/sombre.
            liaison.texteDiffChangements.text = apercuDiffSpanne(liaison, revue)
        } else {
            liaison.texteDiffChangements.isVisible = false
        }

        val execution = etat.execution
        liaison.bandeauAnnulation.isVisible = execution != null
        liaison.indicateur.isVisible = enCours || etat.enApplication

        // Réponse finale (v0.195.0) : carte dédiée — du contenu, pas un
        // message d'état.
        liaison.carteReponse.isVisible = !etat.reponse.isNullOrBlank()
        liaison.texteReponse.text = etat.reponse.orEmpty()
    }

    /**
     * Aperçu du diff par fichier (rapport IA, lot 3 — d'après la
     * maquette) : une ligne MONOSPACE par fichier — `＋ chemin (N
     * lignes)` en PRIMAIRE, `− chemin (M lignes)` en ERREUR —
     * [android.text.SpannableStringBuilder] avec les teintes du THÈME
     * (jamais de couleur en dur), plafonné au premier fichier porteur
     * de lignes.
     */
    private fun apercuDiffSpanne(
        liaison: FragmentIaAgentBinding,
        revue: jo.codeide.core.ai.RevueChangeSetIa,
    ): CharSequence {
        val contexte = liaison.root.context
        val couleurAjout = couleurTheme(contexte, androidx.appcompat.R.attr.colorPrimary)
        val couleurRetrait = couleurTheme(contexte, androidx.appcompat.R.attr.colorError)
        val texte = android.text.SpannableStringBuilder()
        for (fichier in revue.fichiers) {
            if (texte.isNotEmpty()) {
                texte.append('\n')
            }
            if (fichier.lignesAjoutees > 0) {
                val debut = texte.length
                texte
                    .append('＋')
                    .append(fichier.cheminRelatif)
                    .append(" (")
                    .append(fichier.lignesAjoutees.toString())
                    .append(')')
                texte.setSpan(
                    android.text.style.ForegroundColorSpan(couleurAjout),
                    debut,
                    texte.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            if (fichier.lignesSupprimees > 0) {
                if (fichier.lignesAjoutees > 0) {
                    texte.append('\n')
                }
                val debut = texte.length
                texte
                    .append('−')
                    .append(fichier.cheminRelatif)
                    .append(" (")
                    .append(fichier.lignesSupprimees.toString())
                    .append(')')
                texte.setSpan(
                    android.text.style.ForegroundColorSpan(couleurRetrait),
                    debut,
                    texte.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return texte
    }

    /**
     * Temps réel de l'exécution (A4, rapport IA lot 2) : « Étape 4 / 25 ·
     * 18 s » (outil en cours en suffixe) + aperçu de la RÉFLEXION du
     * modèle (2 lignes, italique — présentation pure). La durée se
     * calcule au rendu (l'état porte le début).
     */
    private fun rendreTempsReel(etat: EtatAgentIa, liaison: FragmentIaAgentBinding) {
        val enCours = etat.statut == StatutAgentIa.EN_COURS
        val visible = enCours && etat.etapeActive > 0
        liaison.progressionTempsReel.isVisible = visible
        if (visible) {
            val secondes =
                ((System.currentTimeMillis() - etat.debutExecutionMs) / MILLIS_PAR_SECONDE).coerceAtLeast(0L)
            val outil = etat.outilEnCours
            liaison.progressionTempsReel.text =
                if (outil.isNullOrBlank()) {
                    getString(R.string.agent_progression, etat.etapeActive, etat.etapesMax, secondes)
                } else {
                    getString(R.string.agent_progression_outil, etat.etapeActive, etat.etapesMax, secondes, outil)
                }
        }

        liaison.texteReflexion.isVisible = enCours && etat.reflexion.isNotBlank()
        if (liaison.texteReflexion.isVisible) {
            liaison.texteReflexion.text = etat.reflexion
        }
    }

    /** Carte d'approbation EXÉCUTION (ADR 0125) — outil + raison. */
    private fun rendreApprobation(etat: EtatAgentIa, liaison: FragmentIaAgentBinding) {
        val demande = etat.demande
        liaison.carteApprobation.isVisible = demande != null
        if (demande != null) {
            liaison.texteApprobationOutil.text = demande.outil
            liaison.texteApprobationRaison.text = demande.raison
        }
    }

    /**
     * Carte du PLAN PROPOSÉ (A5, rapport IA lot 2) : visible quand le
     * statut attend la validation — l'interrupteur suit l'état ARMÉ
     * (garde anti-écho) et se verrouille pendant l'exécution.
     */
    private fun rendrePlan(etat: EtatAgentIa, liaison: FragmentIaAgentBinding) {
        val plan = etat.plan
        liaison.cartePlan.isVisible = etat.statut == StatutAgentIa.PLAN_ATTENDU && !plan.isNullOrBlank()
        if (!plan.isNullOrBlank()) {
            liaison.textePlan.text = plan
        }
        if (liaison.interrupteurPlan.isChecked != etat.modePlanPremier) {
            synchronisationPlan = true
            liaison.interrupteurPlan.isChecked = etat.modePlanPremier
            synchronisationPlan = false
        }
        liaison.interrupteurPlan.isEnabled = etat.statut != StatutAgentIa.EN_COURS

        // Mode « revue » (rapport IA, lot 3) : miroir exact du plan —
        // l'état ARMÉ suit, l'interrupteur se VERROUILLE en cours
        // d'exécution (l'option est un choix AVANT le lancement).
        if (liaison.interrupteurRevue.isChecked != etat.modeRevue) {
            synchronisationRevue = true
            liaison.interrupteurRevue.isChecked = etat.modeRevue
            synchronisationRevue = false
        }
        liaison.interrupteurRevue.isEnabled = etat.statut != StatutAgentIa.EN_COURS
    }

    /**
     * Coût de la dernière exécution (A5, rapport IA lot 2) : compteurs
     * du fournisseur quand il les rapporte, estimation LOCALE honnête
     * sinon — jamais une facturation.
     */
    private fun rendreJetons(etat: EtatAgentIa, liaison: FragmentIaAgentBinding) {
        val jetons = etat.jetons
        val visible = jetons != null && (jetons.entree > 0L || jetons.sortie > 0L || jetons.estimes > 0L)
        liaison.texteJetons.isVisible = visible
        if (jetons == null || !visible) {
            return
        }
        liaison.texteJetons.text =
            if (jetons.entree > 0L || jetons.sortie > 0L) {
                getString(R.string.agent_jetons, jetons.entree, jetons.sortie, jetons.estimes)
            } else {
                getString(R.string.agent_jetons_estimes, jetons.estimes)
            }
    }

    /**
     * Bandeau de pré-vérifications (A2, rapport IA lot 2) : une puce par
     * condition — le LIBELLÉ porte l'état (pas de faux « tout va bien »),
     * la couleur suit les jetons du thème (primaire = prêt, erreur = à
     * corriger). Le bandeau vit au-dessus du bouton Lancer, jamais au
     * milieu des étapes d'une exécution en cours.
     */
    private fun rendrePreVerifications(preVerifications: PreVerificationsAgentIa, liaison: FragmentIaAgentBinding) {
        val contexte = liaison.root.context
        val couleurPret = couleurTheme(contexte, androidx.appcompat.R.attr.colorPrimary)
        val couleurEchec = couleurTheme(contexte, androidx.appcompat.R.attr.colorError)

        fun puce(
            vue: com.google.android.material.textview.MaterialTextView,
            ok: Boolean,
            ressourceOk: Int,
            ressourceKo: Int,
        ) {
            vue.setText(if (ok) ressourceOk else ressourceKo)
            vue.setTextColor(if (ok) couleurPret else couleurEchec)
        }

        puce(
            liaison.puceProjet,
            preVerifications.projetOuvert,
            R.string.agent_puce_projet_ok,
            R.string.agent_puce_projet_ko,
        )
        puce(liaison.puceCle, preVerifications.clePresente, R.string.agent_puce_cle_ok, R.string.agent_puce_cle_ko)
        puce(
            liaison.puceModele,
            preVerifications.modeleChoisi,
            R.string.agent_puce_modele_ok,
            R.string.agent_puce_modele_ko,
        )
    }

    /**
     * Action de remédiation de la carte d'erreur (A3, rapport IA lot 2 —
     * miroir du chat) : Réessayer relance l'exécution, les réglages IA
     * portent la clé, le consentement et le modèle.
     */
    private fun appliquerActionErreur(action: jo.codeide.feature.ai.ActionErreurIa) {
        when (action) {
            jo.codeide.feature.ai.ActionErreurIa.REESSAYER -> modele.continuer()

            jo.codeide.feature.ai.ActionErreurIa.OUVRIR_REGLAGES ->
                navigator.openSettingsSection(jo.codeide.core.ui.SectionParametres.IA)

            jo.codeide.feature.ai.ActionErreurIa.CHANGER_MODELE ->
                navigator.openSettingsSection(jo.codeide.core.ui.SectionParametres.IA)
        }
    }

    private companion object {
        /** Conversion ms → s de la durée affichée (A4). */
        private const val MILLIS_PAR_SECONDE = 1000L
    }

    private fun cacherClavier(champ: View) {
        val clavier = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        clavier?.hideSoftInputFromWindow(champ.windowToken, 0)
    }
}
