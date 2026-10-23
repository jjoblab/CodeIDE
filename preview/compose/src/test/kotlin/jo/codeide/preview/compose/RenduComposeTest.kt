package jo.codeide.preview.compose

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Exécuteur off-screen (C1 — le cœur du spike C0 devenu production) :
 * un programme interprété se COMPOSE et se DESSINE réellement, sans
 * fenêtre, sur le fil de composition — l'attente de stabilisation est
 * SUSPENDANTE (jamais de blocage du fil qui pompe la recomposition).
 *
 * Le fil principal Robolectric est POMPÉ par le test pendant que le
 * rendu bloque UN FIL DÉDIÉ (miroir du fil Binder en production) : la
 * composition, le recomposer et le pompier de frames vivent sur le
 * looper principal.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class RenduComposeTest {
    private val contexte: Context = ApplicationProvider.getApplicationContext()

    /** Journal capturé (règle 14 : JAMAIS println — les pannes restent lisibles au débogage). */
    private val journalCapte = mutableListOf<String>()

    /** L'exécuteur : le VRAI main executor (le looper est pompé par le test). */
    private fun executeur(): RenduCompose = RenduCompose(
        contexte = contexte,
        executeur = ContextCompat.getMainExecutor(contexte),
        dispatcheurComposition = kotlinx.coroutines.Dispatchers.Main,
        journal = { journalCapte += it },
    )

    /** Appelle rendre() SUR UN FIL DÉDIÉ et pompe le looper principal. */
    private fun rendreSync(rendu: RenduCompose, requete: RequeteRenduCompose): ResultatRenduCompose {
        val referent = AtomicReference<ResultatRenduCompose>()
        val fil =
            Thread(
                { referent.set(rendu.rendre(requete)) },
                "test-binder-compose",
            )
        fil.start()
        val ombre = Shadows.shadowOf(Looper.getMainLooper())
        val echeance = System.currentTimeMillis() + 30_000
        while (fil.isAlive && System.currentTimeMillis() < echeance) {
            ombre.idleFor(Duration.ofMillis(16))
        }
        assertTrue("le rendu doit se terminer (budget borné)", !fil.isAlive)
        fil.join(5_000)
        return referent.get() ?: throw AssertionError("le rendu n'a pas rendu de résultat")
    }

    private fun requete(
        identite: String,
        vararg noeuds: NoeudProgramme,
        nuit: Boolean = false,
        densiteDpi: Int = 440,
    ): RequeteRenduCompose = RequeteRenduCompose(
        identite = identite,
        programme =
        ProgrammeCompose(
            racine = noeuds.toList(),
            coupureBudget = false,
            digeste = "d",
        ),
        largeurDp = 393,
        hauteurDp = 851,
        densiteDpi = densiteDpi,
        nuit = nuit,
    )

    private fun texte(chaine: String): NoeudProgramme = NoeudProgramme(
        genre = GenreNoeudCompose.TEXTE,
        nom = "Text",
        ligne = 3,
        arguments =
        listOf(
            ArgumentProgramme(
                nom = "text",
                genre = GenreArgumentCompose.LITTERAL_CHAINE,
                valeur = chaine,
            ),
        ),
        contenu = emptyList(),
    )

    @Test
    fun `rendu nominal - bitmap aux dimensions du profil, densité FLOTTANTE`() {
        val rendu = executeur()
        // 393 dp × 2,75 (440 dpi / 160) = 1080 px ; 851 dp × 2,75 = 2340 px.
        // (La division ENTIÈRE donnerait 786×1702 — le piège 440/160=2.)
        val resultat = rendreSync(rendu, requete("file:///a.kt#Apercu#0", texte("Bonjour le rendu")))
        assertTrue("rendu attendu réussi : ${resultat.message}", resultat.ok)
        assertNotNull(resultat.bitmap)
        assertEquals(1080, resultat.metriques.largeur)
        assertEquals(2340, resultat.metriques.hauteur)
        assertEquals(1080, resultat.bitmap!!.width)
        assertEquals(2340, resultat.bitmap!!.height)
        assertEquals(1, resultat.metriques.nombreNoeuds)
        assertTrue(resultat.metriques.dureeMs >= 0)
        rendu.fermer()
    }

    @Test
    fun `pastille d'erreur - bande colorée rendue (pixels non fond)`() {
        val rendu = executeur()
        // La pastille d'un nœud INCONNU est une BANDE PLEINE largeur de
        // couleur errorContainer — preuve PIXEL indépendante des glyphes
        // (le rendu de texte sous Robolectric dépend du moteur natif).
        val inconnu =
            NoeudProgramme(
                genre = GenreNoeudCompose.INCONNU,
                nom = "CarteInconnue",
                ligne = 9,
                arguments = emptyList(),
                contenu = emptyList(),
            )
        val resultat = rendreSync(rendu, requete("file:///a.kt#Apercu#0", inconnu))
        assertTrue(resultat.ok)
        val bitmap = resultat.bitmap!!
        val fond = 0xFFFFFFFF.toInt()
        var colore = false
        for (y in intArrayOf(8, 16, 32)) {
            for (x in intArrayOf(8, 200, 540, 800, 1000)) {
                if (bitmap.getPixel(x, y) != fond) {
                    colore = true
                }
            }
        }
        assertTrue("la pastille doit peindre la bande supérieure", colore)
        rendu.fermer()
    }

    @Test
    fun `session persistante - même identité, deux rendus, UNE session`() {
        val rendu = executeur()
        rendreSync(rendu, requete("file:///a.kt#Apercu#0", texte("Premier")))
        val deuxieme =
            rendreSync(
                rendu,
                requete("file:///a.kt#Apercu#0", texte("Deuxième (édité)")),
            )
        assertTrue(deuxieme.ok)
        assertEquals("la composition PERSISTE entre les rendus", 1, rendu.sessionsVivantes())
        rendu.fermer()
    }

    @Test
    fun `identités distinctes - une session par preview`() {
        val rendu = executeur()
        rendreSync(rendu, requete("file:///a.kt#Apercu#0", texte("A")))
        rendreSync(rendu, requete("file:///a.kt#Apercu#1", texte("B")))
        assertEquals(2, rendu.sessionsVivantes())
        rendu.fermer()
    }

    @Test
    fun `nuit - re-theme par recomposition SANS nouvelle session`() {
        val rendu = executeur()
        val jour = rendreSync(rendu, requete("file:///a.kt#Apercu#0", texte("Jour"), nuit = false))
        val nuit = rendreSync(rendu, requete("file:///a.kt#Apercu#0", texte("Jour"), nuit = true))
        assertTrue(jour.ok)
        assertTrue("re-theme attendu : ${nuit.message}", nuit.ok)
        assertEquals(
            "le régime nuit SUIT la session vivante (jamais gelé à la création)",
            1,
            rendu.sessionsVivantes(),
        )
        rendu.fermer()
    }

    @Test
    fun `nœud inconnu - pastille isolée, le rendu RÉUSSIT quand même`() {
        val rendu = executeur()
        val inconnu =
            NoeudProgramme(
                genre = GenreNoeudCompose.INCONNU,
                nom = "CartePersonnalisee",
                ligne = 9,
                arguments = emptyList(),
                contenu = emptyList(),
            )
        val resultat = rendreSync(rendu, requete("file:///a.kt#Apercu#0", texte("Avant"), inconnu, texte("Après")))
        assertTrue("le nœud fautif est ISOLÉ, le reste se rend : ${resultat.message}", resultat.ok)
        assertEquals(3, resultat.metriques.nombreNoeuds)
        rendu.fermer()
    }

    @Test
    fun `colonne et espace - emboîtements et dp réels`() {
        val rendu = executeur()
        val colonne =
            NoeudProgramme(
                genre = GenreNoeudCompose.COLONNE,
                nom = "Column",
                ligne = 5,
                arguments = emptyList(),
                contenu =
                listOf(
                    texte("Haut"),
                    NoeudProgramme(
                        genre = GenreNoeudCompose.ESPACE,
                        nom = "Spacer",
                        ligne = 6,
                        arguments =
                        listOf(
                            ArgumentProgramme(
                                nom = "height",
                                genre = GenreArgumentCompose.VALEUR_DP,
                                valeur = "24",
                            ),
                        ),
                        contenu = emptyList(),
                    ),
                    texte("Bas"),
                ),
            )
        val resultat = rendreSync(rendu, requete("file:///a.kt#Apercu#0", colonne))
        assertTrue(resultat.ok)
        assertEquals(4, resultat.metriques.nombreNoeuds)
        rendu.fermer()
    }

    @Test
    fun `fermeture - les sessions se vident`() {
        val rendu = executeur()
        rendreSync(rendu, requete("file:///a.kt#Apercu#0", texte("A")))
        assertTrue(rendu.sessionsVivantes() == 1)
        rendu.fermer()
        // fermer() passe par l'exécuteur principal : pomper le looper
        // laisse le nettoyage s'exécuter.
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, rendu.sessionsVivantes())
    }

    @Test
    fun `programme vide - rendu honnête (bitmap de fond)`() {
        val rendu = executeur()
        val resultat = rendreSync(rendu, requete("file:///a.kt#Apercu#0"))
        assertTrue("un corps vide rend une planche vide, pas un échec : ${resultat.message}", resultat.ok)
        assertEquals(0, resultat.metriques.nombreNoeuds)
        rendu.fermer()
    }
}
