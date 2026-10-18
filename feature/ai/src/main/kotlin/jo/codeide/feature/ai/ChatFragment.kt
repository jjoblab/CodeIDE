package jo.codeide.feature.ai

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.AppNavigator
import jo.codeide.feature.ai.databinding.FragmentIaChatBinding
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Onglet Chat du tiroir IA (mission IA, lot IA-3, spec E1) : liste des
 * messages (DiffUtil, rendu incrémental du message en génération),
 * états transverses (IA non activée → consentement ; conversation vide),
 * compositeur avec bascule Envoyer ↔ Arrêter (l'annulation ferme la
 * requête — critère d'acceptation IA-3), effets presse-papiers.
 */
@AndroidEntryPoint
public class ChatFragment : Fragment() {
    /** Navigation découplée (consentement). */
    @Inject
    lateinit var navigator: AppNavigator

    private val viewModel: ChatViewModel by viewModels()

    private var liaison: FragmentIaChatBinding? = null

    private var adaptateur: AdapterMessages? = null

    /** Garde anti-écho : l'écriture programmatique du brouillon ne
     * doit pas repartir vers le ViewModel comme une frappe. */
    private var synchronisationSaisie = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val liaisonLocale = FragmentIaChatBinding.inflate(inflater, container, false)
        liaison = liaisonLocale
        return liaisonLocale.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val liaisonLocale = liaison ?: return
        val adaptateurLocal =
            AdapterMessages(
                onCopierMessage = { identifiant -> viewModel.onAction(ActionChatIa.CopierMessage(identifiant)) },
                onCopierBloc = { code -> viewModel.onAction(ActionChatIa.CopierBlocCode(code)) },
                onInsererBloc = { code -> viewModel.onAction(ActionChatIa.InsererBlocCode(code)) },
            )
        adaptateur = adaptateurLocal
        liaisonLocale.listeMessages.adapter = adaptateurLocal
        liaisonLocale.listeMessages.layoutManager = LinearLayoutManager(requireContext())

        liaisonLocale.boutonEnvoyer.setOnClickListener { viewModel.onAction(ActionChatIa.Envoyer) }
        liaisonLocale.boutonArreter.setOnClickListener { viewModel.onAction(ActionChatIa.Arreter) }
        liaisonLocale.boutonConsentement.setOnClickListener { viewModel.onAction(ActionChatIa.OuvrirConsentement) }
        // Le brouillon vit dans le ViewModel (survit à la rotation) : la
        // frappe y est répercutée, l'écriture programmatique (effacement
        // après envoi, restauration) est gardée par le drapeau anti-écho.
        liaisonLocale.saisieIa.doAfterTextChanged { texte ->
            if (!synchronisationSaisie) {
                viewModel.onAction(ActionChatIa.ChangerSaisie(texte?.toString().orEmpty()))
            }
        }

        // Liens du Markdown : ouverture sur initiative utilisateur (ADR
        // 0121 point 3) via le callback testable [LienIa.ouverture].
        LienIa.ouverture = { url -> ouvrirUrl(url) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.etat.collect { etat -> rendre(etat, liaisonLocale, adaptateurLocal) }
                }
                launch {
                    viewModel.effets.collect { effet -> rendreEffet(effet) }
                }
            }
        }
    }

    override fun onDestroyView() {
        LienIa.ouverture = null
        adaptateur = null
        liaison = null
        super.onDestroyView()
    }

    private fun rendre(
        etat: EtatChatIa,
        liaisonLocale: FragmentIaChatBinding,
        adaptateurLocal: AdapterMessages,
    ) {
        adaptateurLocal.submitList(etat.messages)
        // Le brouillon vit dans le ViewModel : effacement après envoi et
        // restauration post-rotation passent ICI (drapeau anti-écho — la
        // frappe utilisateur, elle, ne repasse jamais par ce chemin).
        if (liaisonLocale.saisieIa.text.toString() != etat.saisie) {
            synchronisationSaisie = true
            liaisonLocale.saisieIa.setText(etat.saisie)
            liaisonLocale.saisieIa.setSelection(etat.saisie.length)
            synchronisationSaisie = false
        }
        // Bouton principal : Envoyer (repos) ↔ Arrêter (génération en cours).
        liaisonLocale.boutonEnvoyer.isVisible = !etat.generationEnCours
        liaisonLocale.boutonArreter.isVisible = etat.generationEnCours

        // Défilement automatique vers le bas pendant la génération.
        if (etat.generationEnCours && etat.messages.isNotEmpty()) {
            liaisonLocale.listeMessages.scrollToPosition(etat.messages.lastIndex)
        }

        // États transverses : IA non activée > vide > contenu.
        val etatTransverseVisible = !etat.active || etat.messages.isEmpty()
        liaisonLocale.etatTransverse.isVisible = etatTransverseVisible
        if (!etat.active) {
            liaisonLocale.etatTitre.setText(R.string.ia_ia_inactive_titre)
            liaisonLocale.etatTexte.setText(R.string.ia_ia_inactive_texte)
            liaisonLocale.boutonConsentement.isVisible = true
            liaisonLocale.zoneChat.isVisible = false
        } else if (etat.messages.isEmpty()) {
            liaisonLocale.etatTitre.setText(R.string.ia_chat_vide_titre)
            liaisonLocale.etatTexte.setText(R.string.ia_chat_vide_texte)
            liaisonLocale.boutonConsentement.isVisible = false
            liaisonLocale.zoneChat.isVisible = true
        } else {
            liaisonLocale.zoneChat.isVisible = true
        }
    }

    private fun rendreEffet(effet: EffetChatIa) {
        when (effet) {
            is EffetChatIa.Copier -> {
                copier(effet.texte)
            }

            EffetChatIa.OuvrirConsentement -> {
                navigator.openAiConsentement()
            }

            is EffetChatIa.Message -> {
                val texte =
                    effet.argument?.let { requireContext().getString(effet.ressource, it) }
                        ?: requireContext().getString(effet.ressource)
                Toast.makeText(requireContext(), texte, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Presse-papiers (même API que le tiroir terminal). */
    private fun copier(texte: String) {
        val gestionnaire =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        gestionnaire.setPrimaryClip(ClipData.newPlainText(getString(R.string.ia_tiroir_titre), texte))
        Toast.makeText(requireContext(), R.string.ia_copie_effectuee, Toast.LENGTH_SHORT).show()
    }

    /** Ouvre un lien Markdown dans le navigateur — action utilisateur
     * explicite ; sans navigateur sur l'appareil : sans effet (donnée
     * du modèle, jamais un lien critique du flux de travail). */
    private fun ouvrirUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (ignore: ActivityNotFoundException) {
            // Aucun navigateur : l'ouverture est volontairement sans effet.
        }
    }
}
