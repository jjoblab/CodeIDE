package jo.codeide.feature.onboarding

import android.app.NotificationManager
import android.content.Context
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
 * Page Notifications de l'assistant (v0.31.2, ADR 0046).
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
 * La page explique aussi pourquoi **aucune permission de stockage**
 * n'est demandée : l'environnement Linux vit dans le stockage privé de
 * l'application, le dossier de travail passe par le sélecteur du
 * système (SAF) — `READ/WRITE_EXTERNAL_STORAGE` comme
 * `MANAGE_EXTERNAL_STORAGE` restent inutiles (ADR 0003/0034).
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

        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { etat -> rendre(etat.notificationsActivees) }
    }

    override fun onResume() {
        super.onResume()
        // Retour possible des réglages (ou dialogue système posé
        // ailleurs) : l'état réel fait foi, pas la mémoire de la page.
        viewModel.onAction(ActionOnboarding.ConsignerNotifications(notificationsActivees()))
    }

    /** Rendu : bouton de demande ou confirmation, repli réglages. */
    private fun rendre(activees: Boolean) {
        binding.boutonAutoriserNotifications.isVisible = !activees
        binding.texteNotificationsActivees.isVisible = activees
        binding.boutonReglagesNotifications.isVisible = !activees
    }

    /** État réel de l'autorisation auprès du système (source de vérité). */
    private fun notificationsActivees(): Boolean {
        val gestionnaire =
            requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return gestionnaire.areNotificationsEnabled()
    }
}
