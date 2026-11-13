package jo.codeide.feature.editor

import org.junit.Assert.assertNotNull
import org.junit.Test
import javax.inject.Singleton

/**
 * Le contrat de SCOPE du câble du Centre de sorties (retour d'appareil
 * du 2026-11-11 : « la console n'affiche pas les sorties du gradle
 * tooling en direct ») — le collecteur du flux réel
 * ([CollecteurSortiesGradle]) est injecté aux DEUX BOUTS du câble : la
 * pompe qui le NOURRIT ([PompeBuildTooling] — `demarrerBuild`,
 * `surLigneBuild` sur le canal process-wide) et le flux que le lecteur
 * LIT ([FluxSortiesCentre] — `lots`, `clicheSession`).
 *
 * Sans scope sur la classe, Hilt fabrique une instance PAR POINT
 * D'INJECTION : la pompe arme sa copie, le lecteur écoute une AUTRE
 * copie jamais alimentée — console muette pour tout le câble tooling,
 * ni direct ni fin de build, SANS exception ni journal (le défaut le
 * plus dur à voir : les tests assemblent le collecteur à la main, le
 * banc passe par la source factice).
 *
 * Le test verrouille le câble là où il vit : les TROIS classes du
 * chemin portent `@Singleton` — une seule instance process-wide,
 * partagée par la pompe et le lecteur. Retirer l'annotation du
 * collecteur (ou scopé différemment un consommateur) fait rougir ce
 * test AVANT le premier appareil.
 */
class CableSortiesScopeTest {

    @Test
    fun `le collecteur du câble est process wide`() {
        assertNotNull(
            "CollecteurSortiesGradle DOIT porter @Singleton : la pompe " +
                "(PompeBuildTooling) et le flux du lecteur (FluxSortiesCentre) " +
                "l'injectent tous les deux — sans scope, Hilt fabrique deux " +
                "instances et la console ne voit jamais une ligne du câble.",
            CollecteurSortiesGradle::class.java.getAnnotation(Singleton::class.java),
        )
    }

    @Test
    fun `la pompe qui nourrit le câble est process wide`() {
        assertNotNull(
            "PompeBuildTooling DOIT porter @Singleton : la vidange des " +
                "canaux survit aux écrans (ADR 0080) et arme LE collecteur " +
                "partagé — une seconde instance armerait un câble que " +
                "personne ne lit.",
            PompeBuildTooling::class.java.getAnnotation(Singleton::class.java),
        )
    }

    @Test
    fun `le flux que lit le lecteur est process wide`() {
        assertNotNull(
            "FluxSortiesCentre DOIT porter @Singleton : le lecteur du " +
                "Centre (console GRADLE) consomme l'unique instance du " +
                "collecteur nourrie par la pompe — un second flux lirait " +
                "un câble vide.",
            FluxSortiesCentre::class.java.getAnnotation(Singleton::class.java),
        )
    }
}
