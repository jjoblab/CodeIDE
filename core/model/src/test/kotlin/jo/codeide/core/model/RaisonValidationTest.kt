package jo.codeide.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests du type fermé des raisons de validation (étape 10 — section 12.3) :
 * chaque raison porte exactement son paramètre d'affichage (caractère
 * fautif, segment, motif), l'égalité est par valeur.
 */
class RaisonValidationTest {
    @Test
    fun `les raisons nom portent leur caractère fautif`() {
        assertEquals('?', RaisonValidation.CaractereInterditNom('?').fautif)
        assertNotEquals(RaisonValidation.CaractereInterditNom('?'), RaisonValidation.CaractereInterditNom('*'))
        assertEquals(RaisonValidation.CaractereInterditNom('?'), RaisonValidation.CaractereInterditNom('?'))
    }

    @Test
    fun `les raisons package portent leur segment`() {
        assertEquals("Val", RaisonValidation.SegmentPackageInvalide("Val").segment)
        assertEquals("class", RaisonValidation.MotClePackage("class").segment)
        assertNotEquals(
            RaisonValidation.SegmentPackageInvalide("Val"),
            RaisonValidation.MotClePackage("Val"),
        )
    }

    @Test
    fun `les raisons identifiant portent leur valeur`() {
        assertEquals("1x", RaisonValidation.IdentifiantInvalide("1x").valeur)
        assertEquals("val", RaisonValidation.MotCleIdentifiant("val").valeur)
    }

    @Test
    fun `les raisons regex portent leur motif`() {
        assertEquals(
            "^[a-z]+$",
            RaisonValidation.RegexNonCorrespondance("^[a-z]+$").motif,
        )
    }

    @Test
    fun `les raisons sans charge sont des singletons distincts`() {
        assertEquals(RaisonValidation.LongueurNom, RaisonValidation.LongueurNom)
        assertEquals(RaisonValidation.PointsFictifsNom, RaisonValidation.PointsFictifsNom)
        assertEquals(RaisonValidation.FinNomInterdite, RaisonValidation.FinNomInterdite)
        assertEquals(RaisonValidation.NomReserveWindows, RaisonValidation.NomReserveWindows)
        assertEquals(RaisonValidation.PackageVideOuSegmentVide, RaisonValidation.PackageVideOuSegmentVide)
        assertEquals(RaisonValidation.VersionInvalide, RaisonValidation.VersionInvalide)
        val interdite = RaisonValidation.ValeurInterdite("choix")
        assertEquals("choix", interdite.valeur)
    }
}
