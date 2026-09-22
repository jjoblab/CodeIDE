package jo.codeide.feature.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import jo.codeide.core.ui.BaseFragment
import jo.codeide.feature.onboarding.databinding.PageBienvenueBinding

/**
 * Page 1 — Bienvenue : présentation courte de CodeIDE (étape 5).
 *
 * Page purement statique : l'état vit dans le ViewModel partagé, la
 * barre de l'hôte porte le bouton « Commencer ».
 */
class BienvenuePage : BaseFragment<PageBienvenueBinding>() {
    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): PageBienvenueBinding = PageBienvenueBinding.inflate(inflater, container, attachToRoot)
}
