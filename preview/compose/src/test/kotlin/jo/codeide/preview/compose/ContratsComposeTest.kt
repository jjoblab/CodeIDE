package jo.codeide.preview.compose

import android.graphics.Bitmap
import android.os.Parcel
import android.os.Parcelable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contrats de la frontière AIDL (C1) — aller-retours Parcel des
 * parcelables composables : la requête (avec le programme ARBRE clos),
 * le résultat (bitmap ashmem + catalogue d'erreurs typées), les nœuds
 * et arguments du programme.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ContratsComposeTest {
    /** Aller-retour Parcel générique. */
    private inline fun <reified T : Parcelable> allerRetour(source: T, creator: Parcelable.Creator<T>): T {
        val parcel = Parcel.obtain()
        source.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        return creator.createFromParcel(parcel)
    }

    /** Le programme de fixture : une Column contenant un Text("Bonjour"). */
    private fun programmeImbrique(): ProgrammeCompose = ProgrammeCompose(
        racine =
        listOf(
            NoeudProgramme(
                genre = GenreNoeudCompose.COLONNE,
                nom = "Column",
                ligne = 12,
                arguments = emptyList(),
                contenu =
                listOf(
                    NoeudProgramme(
                        genre = GenreNoeudCompose.TEXTE,
                        nom = "Text",
                        ligne = 13,
                        arguments =
                        listOf(
                            ArgumentProgramme(
                                nom = "text",
                                genre = GenreArgumentCompose.LITTERAL_CHAINE,
                                valeur = "Bonjour",
                            ),
                        ),
                        contenu = emptyList(),
                    ),
                ),
            ),
        ),
        coupureBudget = false,
        digeste = "abc",
    )

    @Test
    fun `requete - aller-retour avec programme imbriqué`() {
        val requete =
            RequeteRenduCompose(
                identite = "file:///x.kt#Apercu#0",
                programme = programmeImbrique(),
                largeurDp = 393,
                hauteurDp = 851,
                densiteDpi = 440,
                nuit = true,
            )
        val relue = allerRetour(requete, RequeteRenduCompose.CREATOR)
        assertEquals(requete, relue)
        assertEquals(
            GenreNoeudCompose.COLONNE,
            relue.programme.racine
                .single()
                .genre,
        )
        assertEquals(
            "Bonjour",
            relue.programme.racine
                .single()
                .contenu
                .single()
                .arguments
                .single()
                .valeur,
        )
        assertTrue(relue.nuit)
    }

    @Test
    fun `resultat - aller-retour avec bitmap et erreurs typées`() {
        val resultat =
            ResultatRenduCompose(
                ok = true,
                message = null,
                bitmap = Bitmap.createBitmap(10, 20, Bitmap.Config.ARGB_8888),
                erreurs =
                arrayOf(
                    ErreurCompose(GenreErreurCompose.COMPOSABLE_INCONNU, "Carte", 7),
                    ErreurCompose(GenreErreurCompose.BUDGET_INTERPRETATION, null, 0, "détail"),
                ),
                metriques = MetriquesRenduCompose(dureeMs = 42L, nombreNoeuds = 3, largeur = 10, hauteur = 20),
            )
        val relue = allerRetour(resultat, ResultatRenduCompose.CREATOR)
        assertTrue(relue.ok)
        assertEquals(resultat, relue)
        assertTrue("le bitmap survit au parcel", relue.bitmap!!.sameAs(resultat.bitmap))
        assertEquals(2, relue.erreurs.size)
        assertEquals(GenreErreurCompose.COMPOSABLE_INCONNU, relue.erreurs[0].genre)
        assertEquals("les métriques regroupées survivent au parcel", 42L, relue.metriques.dureeMs)
        assertEquals(3, relue.metriques.nombreNoeuds)
    }

    @Test
    fun `resultat - échec sans bitmap`() {
        val resultat =
            ResultatRenduCompose(
                ok = false,
                message = "processus indisponible",
                bitmap = null,
                erreurs = arrayOf(ErreurCompose(GenreErreurCompose.PROCESSUS_INDISPONIBLE, null, 0)),
                metriques = MetriquesRenduCompose.NULLES,
            )
        val relue = allerRetour(resultat, ResultatRenduCompose.CREATOR)
        assertEquals(resultat, relue)
        assertTrue(!relue.ok)
    }

    @Test
    fun `evenement tactile C3 - aller-retour avec horodatages et pression`() {
        val evenement =
            EvenementTactileCompose(
                action = android.view.MotionEvent.ACTION_MOVE,
                xPx = 12.5f,
                yPx = 40.25f,
                downTimeMs = 100L,
                eventTimeMs = 133L,
                pression = 0.75f,
            )
        val relu = allerRetour(evenement, EvenementTactileCompose.CREATOR)
        assertEquals(evenement, relu)
    }

    @Test
    fun `identite - format uri#nom#index`() {
        assertEquals(
            "file:///apercu.kt#ApercuCarte#1",
            RequeteRenduCompose.identiteDe("file:///apercu.kt", "ApercuCarte", 1),
        )
    }

    @Test
    fun `digeste - relecture parcel du programme préserve l'empreinte`() {
        val racine = listOf(NoeudProgramme(GenreNoeudCompose.TEXTE, "Text", 3, emptyList(), emptyList()))
        val programme =
            ProgrammeCompose(
                racine = racine,
                coupureBudget = false,
                digeste = ProgrammeCompose.digesteDe(racine, false),
            )
        val relue = allerRetour(programme, ProgrammeCompose.CREATOR)
        assertEquals(programme.digeste, ProgrammeCompose.digesteDe(relue.racine, relue.coupureBudget))
    }

    // ── C2 : le PROJET de la requête et les géométries du résultat ───

    @Test
    fun `requete C2 - le projet (dossier res, langue, thème) survit au parcel`() {
        val requete =
            RequeteRenduCompose(
                identite = "file:///x.kt#Apercu#0",
                programme = programmeImbrique(),
                largeurDp = 393,
                hauteurDp = 851,
                densiteDpi = 440,
                nuit = false,
                dossierRes = "/proj/app/src/main/res",
                langue = "fr",
                themeName = "@style/Theme.MonApp",
            )
        val relue = allerRetour(requete, RequeteRenduCompose.CREATOR)
        assertEquals("/proj/app/src/main/res", relue.dossierRes)
        assertEquals("fr", relue.langue)
        assertEquals("@style/Theme.MonApp", relue.themeName)
        assertEquals(requete, relue)
    }

    @Test
    fun `requete C2 - sans projet, les défauts null (comportement C1)`() {
        val requete =
            RequeteRenduCompose(
                identite = "file:///x.kt#Apercu#0",
                programme = programmeImbrique(),
                largeurDp = 393,
                hauteurDp = 851,
                densiteDpi = 440,
                nuit = false,
            )
        val relue = allerRetour(requete, RequeteRenduCompose.CREATOR)
        assertEquals(null, relue.dossierRes)
        assertEquals(null, relue.langue)
        assertEquals(null, relue.themeName)
    }

    @Test
    fun `resultat C2 - les géométries extraites survivent au parcel`() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF112233.toInt())
        val noeuds =
            arrayOf(
                InfoNoeudCompose(
                    ligne = 12,
                    genre = GenreNoeudCompose.COLONNE,
                    gauche = 0,
                    haut = 0,
                    droite = 1080,
                    bas = 800,
                ),
                InfoNoeudCompose(
                    ligne = 13,
                    genre = GenreNoeudCompose.TEXTE,
                    gauche = 22,
                    haut = 44,
                    droite = 300,
                    bas = 90,
                ),
            )
        val resultat =
            ResultatRenduCompose(
                ok = true,
                message = null,
                bitmap = bitmap,
                erreurs = emptyArray(),
                metriques = MetriquesRenduCompose(11L, 2, 4, 4),
                noeuds = noeuds,
            )
        val relue = allerRetour(resultat, ResultatRenduCompose.CREATOR)
        assertEquals(2, relue.noeuds.size)
        assertEquals(13, relue.noeuds[1].ligne)
        assertEquals(GenreNoeudCompose.TEXTE, relue.noeuds[1].genre)
        assertEquals(22, relue.noeuds[1].gauche)
        assertEquals(90, relue.noeuds[1].bas)
        assertEquals(resultat, relue)
    }

    @Test
    fun `info noeud - aller-retour parcel`() {
        val info =
            InfoNoeudCompose(
                ligne = 7,
                genre = GenreNoeudCompose.ERREUR_EXPRESSION,
                gauche = 1,
                haut = 2,
                droite = 3,
                bas = 4,
            )
        val relue = allerRetour(info, InfoNoeudCompose.CREATOR)
        assertEquals(info, relue)
    }
}
