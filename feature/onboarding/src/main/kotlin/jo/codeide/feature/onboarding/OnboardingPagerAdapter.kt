package jo.codeide.feature.onboarding

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * Adaptateur du pager de l'assistant : une page par [PageOnboarding],
 * dans l'ordre du parcours.
 *
 * Le pager lui-même est **non swipable** (`isUserInputEnabled = false`
 * sur le `ViewPager2` de l'hôte) : la navigation ne se fait qu'aux
 * boutons, pour que chaque étape — notamment le dossier de travail et
 * son test d'écriture — soit un geste délibéré (étape 5 du plan).
 */
class OnboardingPagerAdapter(
    fragment: Fragment,
) : FragmentStateAdapter(fragment) {
    override fun getItemCount(): Int = PageOnboarding.entries.size

    override fun createFragment(position: Int): Fragment =
        when (PageOnboarding.entries[position]) {
            PageOnboarding.BIENVENUE -> BienvenuePage()
            PageOnboarding.DOSSIER -> DossierPage()
            PageOnboarding.TERMINAL -> TerminalPage()
            PageOnboarding.APPARENCE -> ApparencePage()
            PageOnboarding.PROFIL -> ProfilPage()
            PageOnboarding.TERMINE -> TerminePage()
        }
}
