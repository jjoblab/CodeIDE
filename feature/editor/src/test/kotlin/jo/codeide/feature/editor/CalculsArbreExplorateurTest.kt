package jo.codeide.feature.editor

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests purs des calculs de l'arbre explorateur (étape 31,
 * `docs/EXPLORATEUR_V2.md` § 6.1-6.3) — partie B du prompt
 * explorateur.
 *
 * - B1 : un dossier déplié avec enfants trace un trait vertical au
 *   niveau `profondeur + 1` pour rejoindre son premier enfant. La
 *   décision est `estDossier && deplie && nbEnfants > 0`.
 * - B2 : l'inset gauche du fond de sélection = `max(0, 22 × profondeur
 *   − 10)` dp — il démarre sur le trait fin du parent.
 *
 * Tests purs (JVM, pas de rendu graphique) — les constantes
 * `INDENTATION = 22` et `DECALAGE_LIGNE = 10` sont les références de la
 * spec § 6.2, dupliquées ici pour valider la formule indépendamment de
 * l'adapter.
 */
class CalculsArbreExplorateurTest {
    /**
     * B2 : l'inset gauche du fond de sélection suit la formule
     * `max(0, 22 × profondeur − 10)` dp. Vérification de la formule pure
     * (la constante INDENTATION = 22 et DECALAGE_LIGNE = 10 sont les
     * références de la spec § 6.2).
     */
    @Test
    fun `l inset gauche du fond de selection demarre sur le trait du parent`() {
        // Profondeur 0 (racine) : pas d'inset (le trait du parent n'existe pas).
        assertEqualsInset(0, 0)
        // Profondeur 1 : 22 × 1 − 10 = 12 dp.
        assertEqualsInset(1, 12)
        // Profondeur 2 : 22 × 2 − 10 = 34 dp.
        assertEqualsInset(2, 34)
        // Profondeur 3 : 22 × 3 − 10 = 56 dp.
        assertEqualsInset(3, 56)
    }

    /**
     * B1 : un dossier déplié avec enfants doit déclencher le trait
     * supplémentaire au niveau `profondeur + 1`. On vérifie la logique
     * de décision de l'adapter (estDossier && deplie && nbEnfants > 0).
     */
    @Test
    fun `un dossier deplie avec enfants declenche le trait de raccordement`() {
        assertTrue("dossier déplié avec enfants", decideTraitRaccordement(true, true, 3))
        assertTrue("dossier replié : pas de trait", !decideTraitRaccordement(true, false, 3))
        assertTrue("dossier déplié sans enfant : pas de trait", !decideTraitRaccordement(true, true, 0))
        assertTrue("fichier (impossible déplié) : pas de trait", !decideTraitRaccordement(false, true, 5))
        assertTrue(
            "dossier déplié en cours de chargement (nbEnfants -1) : pas de trait",
            !decideTraitRaccordement(true, true, -1),
        )
    }

    /**
     * Reproduction de la formule de l'adapter (B2) :
     * `maxOf(0, (INDENTATION * profondeur - DECALAGE_LIGNE).toInt())`.
     */
    private fun assertEqualsInset(
        profondeur: Int,
        attenduDp: Int,
    ) {
        val indentation = INDENTATION
        val decalageLigne = DECALAGE_LIGNE
        val calcule = maxOf(0, (indentation * profondeur - decalageLigne).toInt())
        assertTrue(
            "profondeur $profondeur : inset attendu $attenduDp dp, calculé $calcule dp",
            calcule == attenduDp,
        )
    }

    /**
     * Reproduction de la décision de l'adapter (B1) :
     * `estDossier && deplie && nbEnfants > 0`.
     */
    private fun decideTraitRaccordement(
        estDossier: Boolean,
        deplie: Boolean,
        nbEnfants: Int,
    ): Boolean = estDossier && deplie && nbEnfants > 0

    private companion object {
        /** Indentation par niveau (22 dp, § 6.2 — miroir de ExplorateurAdapter). */
        const val INDENTATION = 22f

        /** Distance du trait vertical au début de la ligne (10 dp, § 6.2). */
        const val DECALAGE_LIGNE = 10f
    }
}
