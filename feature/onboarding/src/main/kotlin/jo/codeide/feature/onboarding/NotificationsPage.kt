package jo.codeide.feature.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.onboarding.databinding.PageNotificationsBinding

/**
 * Page Notifications de l'assistant (v0.31.2, ADR 0046 ; stockage
 * révisé v0.31.3, ADR 0047).
 *
 * Explique ce que l'application fait des notifications — le service du
 * terminal reste visible en arrière-plan (une session ouverte survit au
 * retour à l'accueil), les installations longues ne se terminent pas en
 * silence — et demande l'autorisation quand la plateforme la demande.
 *
 * Cible 28 (ADR 0045) : Android 13+ accorde `POST_NOTIFICATIONS` par
 * défaut aux applications ciblant 32 ou moins et le système montre
 * LUI-MÊME le dialogue à la première activité après création du canal ;
 * notre bouton relève l'état réel (`areNotificationsEnabled`, source de
 * vérité quel que soit le chemin) et la requête directe sert de relance
 * — un refus reste réversible par les réglages (bouton de repli).
 *
 * Stockage partagé, OPT-IN (v0.31.3, ADR 0047) : le fonctionnement de
 * base n'exige RIEN — l'environnement Linux vit dans le stockage privé
 * de l'application et le dossier de travail passe par le sélecteur du
 * système (SAF) ; l'accès optionnel au stockage partagé
 * (`/storage/emulated/0`) depuis les sessions du terminal suit le
 * modèle Termux : réglage « Tous les fichiers » sous Android 11+
 * (`isExternalStorageManager`), permissions READ+WRITE sinon (cible 28
 * = stockage legacy). Jamais exigé : « Suivant » passe sans rien
 * accorder, l'état réel fait foi et reste réversible aux réglages.
 *
 * Jamais bloquante : « Suivant » poursuit le parcours quoi qu'il en
 * soit, l'autorisation reste modifiable dans les réglages.
 */
class NotificationsPage : BaseFragment<PageNotificationsBinding>() {
    private val viewModel: OnboardingViewModel by viewModels(ownerProducer = { requireParentFragment() })

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): PageNotificationsBinding = PageNotificationsBinding.inflate(inflater, container, attachToRoot)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.boutonAutoriserNotifications.setOnClickListener {
            viewModel.onAction(ActionOnboarding.DemanderNotifications)
        }
        binding.boutonReglagesNotifications.setOnClickListener {
            viewModel.onAction(ActionOnboarding.DemanderReglagesNotifications)
        }
        binding.boutonAutoriserStockage.setOnClickListener {
            viewModel.onAction(ActionOnboarding.DemanderStockage)
        }
        binding.boutonReglagesStockage.setOnClickListener {
            viewModel.onAction(ActionOnboarding.DemanderReglagesStockage)
        }

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat ->
            rendre(etat.notificationsActivees, etat.stockagePartageActif)
        }
    }

    override fun onResume() {
        super.onResume()
        // Retour possible des réglages (ou dialogue système posé
        // ailleurs) : l'état réel fait foi, pas la mémoire de la page.
        viewModel.onAction(ActionOnboarding.ConsignerNotifications(etatNotifications(requireContext())))
        viewModel.onAction(ActionOnboarding.ConsignerStockage(etatStockagePartage(requireContext())))
    }

    /** Rendu : bouton de demande ou confirmation, repli réglages. */
    private fun rendre(
        activees: Boolean,
        stockageActif: Boolean,
    ) {
        binding.boutonAutoriserNotifications.isVisible = !activees
        binding.texteNotificationsActivees.isVisible = activees
        binding.boutonReglagesNotifications.isVisible = !activees

        binding.boutonAutoriserStockage.isVisible = !stockageActif
        binding.texteStockageActif.isVisible = stockageActif
        binding.boutonReglagesStockage.isVisible = !stockageActif
    }
}
