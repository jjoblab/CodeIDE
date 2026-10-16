package jo.codeide.preview.xml

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contrat PARCELABLE d'[InfoVueRendu] (PE6c, maquette : « les écrire et
 * les lire dans le même ordre dans le constructeur Parcel et
 * writeToParcel ») — aller-retour intégral : tout champ omis ou lu dans
 * un autre ordre se voit immédiatement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class InfoVueRenduTest {
    @Test
    fun `aller-retour parcel - tous les champs enrichis`() {
        val original =
            InfoVueRendu(
                gauche = 12,
                haut = 34,
                droite = 156,
                bas = 130,
                libelle = "Button @+id/btn",
                debutElement = 40,
                finElement = 120,
                indiceParent = 3,
                profondeur = 2,
                // PE6c — un champ par type de donnée, valeurs NON ambiguës
                // (pas de 0/false/"" qui passeraient par symétrie d'un oubli).
                classe = "MaterialButton",
                idRes = "btn_connexion",
                margesGauche = 8,
                margesHaut = 16,
                margesDroite = 8,
                margesBas = 20,
                paddingGauche = 24,
                paddingHaut = 1,
                paddingDroite = 24,
                paddingBas = 0,
                cliquable = true,
                conteneur = true,
                orientation = InfoVueRendu.ORIENTATION_VERTICALE,
            )

        val tampon = Parcel.obtain()
        try {
            original.writeToParcel(tampon, 0)
            tampon.setDataPosition(0)
            val relu = InfoVueRendu.CREATOR.createFromParcel(tampon)
            assertEquals(original, relu)
            assertEquals(original.hashCode(), relu.hashCode())
            // Le tampon est lu JUSQU'AU BOUT (aucun champ sauté, aucun
            // octet de travers) — le contrat d'ordre de la maquette.
            assertEquals(tampon.dataSize(), tampon.dataPosition())
        } finally {
            tampon.recycle()
        }
    }

    @Test
    fun `aller-retour parcel - valeurs par defaut (vue sans id ni orientation)`() {
        val original =
            InfoVueRendu(
                gauche = 0,
                haut = 0,
                droite = 100,
                bas = 50,
                libelle = "FrameLayout",
                debutElement = InfoVueRendu.SANS_ELEMENT,
                finElement = InfoVueRendu.SANS_ELEMENT,
                indiceParent = -1,
                profondeur = 0,
            )
        val tampon = Parcel.obtain()
        try {
            original.writeToParcel(tampon, 0)
            tampon.setDataPosition(0)
            val relu = InfoVueRendu.CREATOR.createFromParcel(tampon)
            assertEquals(original, relu)
            assertEquals(InfoVueRendu.ORIENTATION_SANS_OBJET, relu.orientation)
            assertEquals("", relu.classe)
            assertEquals("", relu.idRes)
            assertFalse(relu.cliquable)
            assertFalse(relu.conteneur)
        } finally {
            tampon.recycle()
        }
    }

    @Test
    fun `aller-retour parcel - tableau traverse la frontiere AIDL (typedList)`() {
        val vues =
            arrayOf(
                InfoVueRendu(
                    gauche = 0,
                    haut = 0,
                    droite = 40,
                    bas = 40,
                    libelle = "MockView",
                    debutElement = 0,
                    finElement = 9,
                    indiceParent = -1,
                    profondeur = 0,
                    classe = InfoVueRendu.CLASSE_REMPLACEMENT,
                    cliquable = true,
                    orientation = InfoVueRendu.ORIENTATION_HORIZONTALE,
                ),
            )
        val tampon = Parcel.obtain()
        try {
            tampon.writeTypedArray(vues, 0)
            tampon.setDataPosition(0)
            val relus = tampon.createTypedArray(InfoVueRendu.CREATOR)
            assertEquals(1, relus!!.size)
            assertEquals(vues[0], relus[0])
        } finally {
            tampon.recycle()
        }
    }

    @Test
    fun `dimensions rendues - largeur et hauteur depuis les bounds`() {
        val vue =
            InfoVueRendu(
                gauche = 10,
                haut = 20,
                droite = 130,
                bas = 116,
                libelle = "TextView",
                debutElement = 0,
                finElement = 9,
                indiceParent = -1,
                profondeur = 0,
            )
        assertEquals(120, vue.largeurPx)
        assertEquals(96, vue.hauteurPx)
    }
}
