package jo.codeide.feature.ai

import jo.codeide.core.ai.PieceContexte
import jo.codeide.core.ai.TypePieceContexte
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Maths du PUNAISAGE AU BAS (v0.196.0, retour propriétaire : « l'écran
 * tremble, saccade, ça fige avant de s'afficher les résultats ») —
 * pur JVM : les invariants qui remplacent l'ancienne condition par
 * positions (fausse dès que le message en génération dépasse l'écran).
 *
 * Repère : zone de 1000 px de haut, padding bas de 50 px → le bas
 * VISIBLE se trouve à 950 px.
 */
class PunaisageFluxChatTest {
    private val hauteur = 1000
    private val paddingBas = 50

    @Test
    fun `punaise quand le bas du dernier enfant touche le bas visible`() {
        // Le dernier enfant s'arrête EXACTEMENT au bas visible.
        assertTrue(
            PunaisageFluxChat.estPunaise(
                basDernierEnfant = 950,
                hauteurZone = hauteur,
                paddingBas = paddingBas,
            ),
        )
    }

    @Test
    fun `punaise dans la tolerance — un cran accidentel ne detache pas`() {
        // 8 px de débord : toujours punaisé (tolérance).
        assertTrue(
            PunaisageFluxChat.estPunaise(
                basDernierEnfant = 950 + PunaisageFluxChat.TOLERANCE_PX,
                hauteurZone = hauteur,
                paddingBas = paddingBas,
            ),
        )
    }

    @Test
    fun `detache au-dela de la tolerance — une remontee volontaire arrete le suivi`() {
        // 9 px de débord : la lecture a remonté, plus JAMAIS de « yank ».
        assertFalse(
            PunaisageFluxChat.estPunaise(
                basDernierEnfant = 951 + PunaisageFluxChat.TOLERANCE_PX,
                hauteurZone = hauteur,
                paddingBas = paddingBas,
            ),
        )
    }

    @Test
    fun `reste punaise quand le contenu retracte — le bas vide se recolle`() {
        // Le contenu est plus COURT que la zone : punaisé (il faut
        // REMONTER pour recoller, surplomb négatif).
        assertTrue(
            PunaisageFluxChat.estPunaise(
                basDernierEnfant = 400,
                hauteurZone = hauteur,
                paddingBas = paddingBas,
            ),
        )
    }

    @Test
    fun `surplomb positif quand le contenu deborde — il faut descendre d'exactement ca`() {
        assertEquals(
            120,
            PunaisageFluxChat.surplomb(
                basDernierEnfant = 1070,
                hauteurZone = hauteur,
                paddingBas = paddingBas,
            ),
        )
    }

    @Test
    fun `surplomb negatif quand le contenu retracte — il faut remonter pour recoller`() {
        assertEquals(
            -300,
            PunaisageFluxChat.surplomb(
                basDernierEnfant = 650,
                hauteurZone = hauteur,
                paddingBas = paddingBas,
            ),
        )
    }

    @Test
    fun `surplomb nul quand le bas est colle`() {
        assertEquals(
            0,
            PunaisageFluxChat.surplomb(
                basDernierEnfant = 950,
                hauteurZone = hauteur,
                paddingBas = paddingBas,
            ),
        )
    }
}

/**
 * EMPREINTE des puces (v0.196.0, ANR) : la rangée de puces ne se
 * regonfle QUE si ce que la rangée AFFICHE a changé — les ~20 rendus
 * par seconde du flux ne détruisent plus des vues identiques.
 */
class EmpreintePucesDeTest {
    private val fichier = PieceContexte(TypePieceContexte.FichierActif, "src/Foo.kt", "cle", 10)
    private val mention = PieceContexte(TypePieceContexte.FichierMentionne, "src/Bar.kt", "cle", 20)

    @Test
    fun `meme pieces meme budget — meme empreinte`() {
        assertEquals(
            empreintePucesDe(EtatChatIa(pieces = listOf(fichier, mention), jetonsContexte = 42)),
            empreintePucesDe(EtatChatIa(pieces = listOf(fichier, mention), jetonsContexte = 42)),
        )
    }

    @Test
    fun `un budget different change l'empreinte — la puce jetons se recalcule`() {
        assertNotEquals(
            empreintePucesDe(EtatChatIa(pieces = listOf(fichier), jetonsContexte = 42)),
            empreintePucesDe(EtatChatIa(pieces = listOf(fichier), jetonsContexte = 43)),
        )
    }

    @Test
    fun `un chemin different change l'empreinte — le libelle de la puce change`() {
        val autreMention = PieceContexte(TypePieceContexte.FichierMentionne, "src/Autre.kt", "cle", 20)
        assertNotEquals(
            empreintePucesDe(EtatChatIa(pieces = listOf(mention))),
            empreintePucesDe(EtatChatIa(pieces = listOf(autreMention))),
        )
    }

    @Test
    fun `le reste de l'ecran ne change PAS l'empreinte — le flux passe au travers`() {
        // La SAISIE et les messages changent à chaque fragment du flux :
        // la rangée ne doit JAMAIS se regonfler pour eux.
        assertEquals(
            empreintePucesDe(
                EtatChatIa(pieces = listOf(fichier), jetonsContexte = 7, saisie = "en cours…"),
            ),
            empreintePucesDe(
                EtatChatIa(
                    pieces = listOf(fichier),
                    jetonsContexte = 7,
                    saisie = "",
                    generationEnCours = true,
                ),
            ),
        )
    }
}
