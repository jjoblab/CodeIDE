package jo.codeide.feature.editor

import jo.codeide.core.domain.EtatBuild
import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.domain.StatutBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests de la décision de notification du service tooling (étape 32, ADR
 * 0057) : l'état pur se traduit en ce que le service Android doit montrer
 * — en cours pendant l'activité, résultat final au dénouement, rien sinon.
 */
class DecisionNotificationToolingTest {
    @Test
    fun `l etat initial ne montre rien`() {
        assertEquals(DecisionNotificationTooling.Rien, decisionNotificationTooling(EtatGradle()))
    }

    @Test
    fun `la connexion seule ne lance aucune notification`() {
        assertEquals(
            DecisionNotificationTooling.Rien,
            decisionNotificationTooling(EtatGradle(connexion = EtatConnexion.CONNECTEE)),
        )
    }

    @Test
    fun `une sync en vol se notifie en cours sur son canal`() {
        val decision = decisionNotificationTooling(EtatGradle(synchronisationEnCours = true))

        assertEquals(
            DecisionNotificationTooling.EnCours(CanalTooling.SYNC),
            decision,
        )
    }

    @Test
    fun `un build en vol se notifie en cours avec ses taches`() {
        val decision =
            decisionNotificationTooling(
                EtatGradle(
                    statutBuild = StatutBuild.EN_COURS,
                    taches = listOf("assembleDebug", "lintDebug"),
                ),
            )

        assertEquals(
            DecisionNotificationTooling.EnCours(CanalTooling.BUILD, listOf("assembleDebug", "lintDebug")),
            decision,
        )
    }

    @Test
    fun `un listage en vol se notifie en cours sur son canal`() {
        assertEquals(
            DecisionNotificationTooling.EnCours(CanalTooling.TACHES),
            decisionNotificationTooling(EtatGradle(tachesEnCours = true)),
        )
    }

    @Test
    fun `la sync prioritaire gagne l affichage quand le build tourne aussi`() {
        assertEquals(
            DecisionNotificationTooling.EnCours(CanalTooling.SYNC),
            decisionNotificationTooling(
                EtatGradle(synchronisationEnCours = true, statutBuild = StatutBuild.EN_COURS),
            ),
        )
    }

    @Test
    fun `une sync reussie se notifie en resultat avec sa duree`() {
        val decision =
            decisionNotificationTooling(
                EtatGradle(synchronisationReussie = ResultatSynchronisation("/p", reussie = true, dureeMs = 12_000)),
            )

        assertEquals(
            DecisionNotificationTooling.Resultat(canal = CanalTooling.SYNC, reussi = true, dureeMs = 12_000),
            decision,
        )
    }

    @Test
    fun `un echec de sync se notifie en resultat avec son message`() {
        val decision = decisionNotificationTooling(EtatGradle(messageEchecSync = "JDK absent"))

        assertEquals(
            DecisionNotificationTooling.Resultat(
                canal = CanalTooling.SYNC,
                reussi = false,
                messageEchec = "JDK absent",
            ),
            decision,
        )
    }

    @Test
    fun `un build reussi echoue ou annule se notifie en resultat`() {
        assertEquals(
            DecisionNotificationTooling.Resultat(canal = CanalTooling.BUILD, reussi = true, dureeMs = 83_000),
            decisionNotificationTooling(
                EtatGradle(statutBuild = StatutBuild.REUSSI, dureeBuildMs = 83_000),
            ),
        )
        assertEquals(
            DecisionNotificationTooling.Resultat(
                canal = CanalTooling.BUILD,
                reussi = false,
                dureeMs = 4_000,
                messageEchec = "compileKotlin a échoué",
            ),
            decisionNotificationTooling(
                EtatGradle(
                    statutBuild = StatutBuild.ECHOUE,
                    dureeBuildMs = 4_000,
                    messageEchecBuild = "compileKotlin a échoué",
                ),
            ),
        )
        assertEquals(
            DecisionNotificationTooling.Resultat(canal = CanalTooling.BUILD, reussi = false, annule = true),
            decisionNotificationTooling(EtatGradle(statutBuild = StatutBuild.ANNULE)),
        )
    }

    @Test
    fun `un listage termine ne laisse aucune notification - le selecteur est le resultat`() {
        assertNull(null)
        assertEquals(
            DecisionNotificationTooling.Rien,
            decisionNotificationTooling(EtatGradle(tachesEnCours = false, debutTachesMs = 1L)),
        )
    }

    @Test
    fun `l activite en vol prime le dernier resultat`() {
        val decision =
            decisionNotificationTooling(
                EtatGradle(
                    synchronisationEnCours = true,
                    statutBuild = StatutBuild.REUSSI,
                ),
            )

        assertEquals(DecisionNotificationTooling.EnCours(CanalTooling.SYNC), decision)
    }
}
