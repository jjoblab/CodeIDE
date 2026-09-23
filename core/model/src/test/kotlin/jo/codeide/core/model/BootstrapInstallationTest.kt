package jo.codeide.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des types d'affichage de l'installation du bootstrap : chaque
 * variante est construite, déconstruite et comparée (couverture du
 * contrat public, même en l'absence de logique — cohérent avec les
 * autres types du module).
 */
class BootstrapInstallationTest {
    @Test
    fun `les étapes de progression se construisent et se distinguent`() {
        val verification = EtapeInstallation.VerificationEspaceDisque
        val telechargement = EtapeInstallation.Telechargement(octetsRecus = 512, octetsTotaux = 2048)
        val indetermine = EtapeInstallation.Telechargement(octetsRecus = 1, octetsTotaux = null)
        val extraction = EtapeInstallation.Extraction(entreesTraitees = 3)
        val liens = EtapeInstallation.LiensSymboliques
        val bascule = EtapeInstallation.BasculeVersPrefixe
        val secondStage = EtapeInstallation.SecondStage
        val configurationApt = EtapeInstallation.ConfigurationApt
        val miseAJour = EtapeInstallation.MiseAJourApt
        val paquets = EtapeInstallation.InstallationPaquets(paquet = "openjdk-17", index = 1, total = 2)

        assertEquals(512L, telechargement.octetsRecus)
        assertEquals(2048L, telechargement.octetsTotaux)
        assertEquals(null, indetermine.octetsTotaux)
        assertEquals(3, extraction.entreesTraitees)
        assertEquals("openjdk-17", paquets.paquet)
        val ensemble =
            setOf(
                verification,
                telechargement,
                extraction,
                liens,
                bascule,
                secondStage,
                configurationApt,
                miseAJour,
                paquets,
            )
        assertEquals(9, ensemble.size)
    }

    @Test
    fun `la machine à états de l installation couvre ses cinq états`() {
        val nonDemarree: EtatInstallationBootstrap = EtatInstallationBootstrap.NonDemarree
        val enCours = EtatInstallationBootstrap.EnCours(EtapeInstallation.SecondStage)
        val terminee =
            EtatInstallationBootstrap.Terminee(
                listOf(
                    OutilResume(paquet = "openjdk-17", installe = true),
                    OutilResume(paquet = "git", installe = false),
                ),
            )
        val echouee =
            EtatInstallationBootstrap.Echouee(
                AppError.Bootstrap(AppError.BootstrapReason.ReseauIndisponible, "HTTP 404"),
            )
        val annulee: EtatInstallationBootstrap = EtatInstallationBootstrap.Annulee

        assertTrue(nonDemarree is EtatInstallationBootstrap.NonDemarree)
        assertEquals(EtapeInstallation.SecondStage, enCours.etape)
        assertEquals(2, terminee.outils.size)
        assertFalse(terminee.outils[1].installe)
        assertEquals(AppError.BootstrapReason.ReseauIndisponible, (echouee.erreur as AppError.Bootstrap).reason)
        assertTrue(annulee is EtatInstallationBootstrap.Annulee)
        assertNotEquals(nonDemarree, annulee)
    }

    @Test
    fun `l erreur Bootstrap porte sa raison et ses détails`() {
        val erreur = AppError.Bootstrap(AppError.BootstrapReason.EmpreinteInvalide, "SHA-256 obtenue abc")
        assertEquals(AppError.BootstrapReason.EmpreinteInvalide, erreur.reason)
        assertEquals("SHA-256 obtenue abc", erreur.details)
        assertEquals(erreur, AppError.Bootstrap(AppError.BootstrapReason.EmpreinteInvalide, "SHA-256 obtenue abc"))
    }
}
