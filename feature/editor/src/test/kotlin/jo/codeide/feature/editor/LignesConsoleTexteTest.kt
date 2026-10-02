package jo.codeide.feature.editor

import jo.codeide.core.domain.EtapeSync
import jo.codeide.core.domain.StatutBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des constructeurs de lignes de la console FLUX BRUT (v0.46.0,
 * ADR 0078) : canaux, styles et libellés composés — fonctions PURES, la
 * même discipline que le présentateur (les ressources se résolvent au
 * rendu, seuls leurs identifiants et arguments se vérifient ici).
 */
class LignesConsoleTexteTest {
    @Test
    fun `une etape de sync demarree porte le canal SYNC et un libelle compose`() {
        val ligne = LignesConsoleTexte.etapeSyncDemarree(EtapeSync.DAEMON)

        assertEquals(CanalTooling.SYNC, ligne.canal)
        assertEquals(StyleLigne.ETAPE, ligne.style)
        val libelle = ligne.libelle as TexteTooling.Compose
        assertEquals(
            "le libellé compose l'étiquette de l'étape (ressource du plan v5) et son suffixe de départ",
            2,
            libelle.parties.size,
        )
        assertEquals(
            R.string.editor_console_etape_daemon,
            (libelle.parties.first() as TexteTooling.Ressource).id,
        )
        assertEquals(
            R.string.editor_console_etape_suffixe_demarree,
            (libelle.parties.last() as TexteTooling.Ressource).id,
        )
    }

    @Test
    fun `une etape terminee compose son suffixe selon ses details - octets puis compteur puis nue`() {
        val avecOctets =
            LignesConsoleTexte.etapeSyncTerminee(
                EtapeSyncAffichee(
                    etape = EtapeSync.DEPENDANCES,
                    terminee = true,
                    dureeMs = 34_100,
                    octetsRecus = 129_000_000,
                ),
            )
        assertEquals(
            R.string.editor_console_etape_suffixe_terminee_octets,
            ((avecOctets.libelle as TexteTooling.Compose).parties.last() as TexteTooling.Ressource).id,
        )
        assertEquals(
            listOf("34.1s", "123.0 Mo"),
            ((avecOctets.libelle as TexteTooling.Compose).parties.last() as TexteTooling.Ressource).args,
        )

        val avecCompteur =
            LignesConsoleTexte.etapeSyncTerminee(
                EtapeSyncAffichee(etape = EtapeSync.CLASSPATHS, terminee = true, dureeMs = 800, compteur = 12),
            )
        assertEquals(
            R.string.editor_console_etape_suffixe_terminee_compteur,
            ((avecCompteur.libelle as TexteTooling.Compose).parties.last() as TexteTooling.Ressource).id,
        )

        val nue =
            LignesConsoleTexte.etapeSyncTerminee(
                EtapeSyncAffichee(etape = EtapeSync.OUTILS, terminee = true, dureeMs = 120),
            )
        assertEquals(
            R.string.editor_console_etape_suffixe_terminee,
            ((nue.libelle as TexteTooling.Compose).parties.last() as TexteTooling.Ressource).id,
        )
        assertEquals(
            listOf("120ms"),
            ((nue.libelle as TexteTooling.Compose).parties.last() as TexteTooling.Ressource).args,
        )
    }

    @Test
    fun `un telechargement du build porte le canal BUILD et le volume cumule`() {
        val ligne = LignesConsoleTexte.telechargementBuild("kotlin-stdlib.jar", 1_769_000)

        assertEquals(CanalTooling.BUILD, ligne.canal)
        assertEquals(StyleLigne.TELECHARGEMENT, ligne.style)
        val libelle = ligne.libelle as TexteTooling.Ressource
        assertEquals(R.string.editor_console_telecharge, libelle.id)
        assertEquals(listOf("kotlin-stdlib.jar", "1.7 Mo"), libelle.args)
    }

    @Test
    fun `la conclusion de sync distingue terminee et a jour - avec et sans duree`() {
        val terminee = LignesConsoleTexte.conclusionSync(aJour = false, dureeMs = 8_400).single()
        assertEquals(CanalTooling.SYNC, terminee.canal)
        assertEquals(StyleLigne.SYNTHESE, terminee.style)
        assertEquals(
            R.string.editor_console_synthese_sync_terminee_duree,
            (terminee.libelle as TexteTooling.Ressource).id,
        )

        val aJour = LignesConsoleTexte.conclusionSync(aJour = true, dureeMs = null).single()
        assertEquals(
            R.string.editor_console_synthese_sync_a_jour,
            (aJour.libelle as TexteTooling.Ressource).id,
        )
    }

    @Test
    fun `l annulation du build ecrit SA ligne - les autres statuts s appuient sur Gradle`() {
        val ligne = LignesConsoleTexte.buildAnnule()
        assertEquals(CanalTooling.BUILD, ligne.canal)
        assertEquals(StyleLigne.SYNTHESE, ligne.style)
        assertEquals(
            R.string.editor_console_synthese_annule,
            (ligne.libelle as TexteTooling.Ressource).id,
        )

        assertTrue(LignesConsoleTexte.statutConcluParLigne(StatutBuild.ANNULE))
        assertFalse(LignesConsoleTexte.statutConcluParLigne(StatutBuild.REUSSI))
        assertFalse(LignesConsoleTexte.statutConcluParLigne(StatutBuild.ECHOUE))
        assertFalse(LignesConsoleTexte.statutConcluParLigne(null))
    }

    @Test
    fun `les durees et les octets se formatent lisiblement`() {
        assertEquals("350ms", DureesLisibles.formater(350))
        assertEquals("4.2s", DureesLisibles.formater(4_200))
        assertFalse(DureesLisibles.digne(350))
        assertTrue(DureesLisibles.digne(4_200))

        assertEquals("340 o", OctetsLisibles.formater(340))
        assertEquals("340 Ko", OctetsLisibles.formater(348_160))
        assertEquals("1.7 Mo", OctetsLisibles.formater(1_769_000))
    }

    @Test
    fun `la traduction d etape du tooling conserve les details de progression`() {
        val traduite =
            jo.codeide.core.domain
                .EtapeSyncTooling(
                    etape = EtapeSync.DEPENDANCES,
                    terminee = false,
                    dureeMs = 0,
                    octetsRecus = 42,
                    element = "kotlin-stdlib.jar",
                    compteur = 3,
                    total = 47,
                ).versEtatAffiche()

        assertEquals(EtapeSync.DEPENDANCES, traduite.etape)
        assertEquals(42L, traduite.octetsRecus)
        assertEquals("kotlin-stdlib.jar", traduite.element)
        assertEquals(3, traduite.compteur)
        assertEquals(47, traduite.total)
    }
}
