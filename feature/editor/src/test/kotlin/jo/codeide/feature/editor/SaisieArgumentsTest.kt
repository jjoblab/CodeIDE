package jo.codeide.feature.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de [SaisieArguments] — régression du correctif n°10 du prompt
 * « tooling Gradle professionnel » : le `doAfterTextChanged` du champ
 * d'arguments s'armait AUSSI sur le `setText` programmatique du rendu —
 * le champ n'était plus jamais resynchronisé et une valeur périmée était
 * ré-écrite à la prochaine perte de focus.
 */
class SaisieArgumentsTest {
    @Test
    fun `un setText de rendu n arme jamais la validation`() {
        val saisie = SaisieArguments()

        // Premier rendu : setText programmatique → rappel textuel, rendu en cours.
        saisie.surChangementTexte(pendantRendu = true)

        assertTrue(saisie.renduPeutReecrire())
        assertFalse(saisie.consommerPourValidation())
    }

    @Test
    fun `le rendu suit les reecritures sans armer - le champ reste synchronise`() {
        val saisie = SaisieArguments()

        // Plusieurs rendus consécutifs (DataStore réémis après un autre réglage) :
        // chacun réécrit le champ, AUCUN n'arme la validation.
        repeat(3) { saisie.surChangementTexte(pendantRendu = true) }

        assertTrue(saisie.renduPeutReecrire())
        assertFalse(saisie.consommerPourValidation())
    }

    @Test
    fun `une frappe utilisateur arme la validation et gele la reecriture`() {
        val saisie = SaisieArguments()

        saisie.surChangementTexte(pendantRendu = false)

        assertFalse(saisie.renduPeutReecrire())
        assertTrue(saisie.consommerPourValidation())
    }

    @Test
    fun `la validation consomme le drapeau - le rendu reprend la main ensuite`() {
        val saisie = SaisieArguments()

        saisie.surChangementTexte(pendantRendu = false)
        assertTrue(saisie.consommerPourValidation())

        // Après validation, une deuxième perte de focus ne ré-écrit RIEN
        // (une validation n'arme pas la suivante)…
        assertFalse(saisie.consommerPourValidation())
        // …et le rendu peut de nouveau resynchroniser le champ.
        assertTrue(saisie.renduPeutReecrire())
    }

    @Test
    fun `une frappe apres un rendu arme normalement - les sources se distinguent`() {
        val saisie = SaisieArguments()

        saisie.surChangementTexte(pendantRendu = true)
        saisie.surChangementTexte(pendantRendu = false)

        assertFalse(saisie.renduPeutReecrire())
        assertTrue(saisie.consommerPourValidation())
    }
}
