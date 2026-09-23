package jo.codeide.feature.install

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Garde-fou du correctif v0.25.0 (rapport 30e81ee0) : un fragment qui
 * obtient un `@HiltViewModel` via `by viewModels()` DOIT porter
 * `@AndroidEntryPoint`, sinon la factory par défaut tente la réflexion
 * sur un constructeur sans argument — `NoSuchMethodException` à
 * l'ouverture de l'écran, **uniquement sur appareil** (les tests JVM du
 * ViewModel construisent la classe directement et ne voient rien).
 *
 * L'annotation a une rétention `CLASS` (invisible par réflexion à
 * l'exécution) : le témoin fiable est la classe **générée**
 * `Hilt_InstallFragment` — Hilt ne la produit que pour les classes
 * annotées, et elle vit sur le classpath de test du module. Sans
 * l'annotation : `ClassNotFoundException`, test rouge — le plantage
 * rejoué en miniature avant l'appareil.
 */
class InstallFragmentHiltTest {
    @Test
    fun `InstallFragment porte AndroidEntryPoint pour la factory Hilt`() {
        val hiltPresent =
            try {
                Class.forName("jo.codeide.feature.install.Hilt_InstallFragment")
                true
            } catch (_: ClassNotFoundException) {
                false
            }

        assertTrue(
            "InstallFragment doit être @AndroidEntryPoint (rapport 30e81ee0) — sans elle, " +
                "Hilt_InstallFragment n'est pas générée et InstallViewModel se construit par " +
                "réflexion sans constructeur vide sur appareil.",
            hiltPresent,
        )
    }
}
