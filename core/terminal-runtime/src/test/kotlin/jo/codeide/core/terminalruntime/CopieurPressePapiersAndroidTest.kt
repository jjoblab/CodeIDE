package jo.codeide.core.terminalruntime

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Tests du copieur presse-papiers Android (correctif C3 du prompt
 * Terminal) : `ClientTermux.onCopyTextToClipboard` — l'action « Copier »
 * de la barre native après sélection — était un no-op silencieux ; la
 * vraie écriture vit ici, éprouvée sous Robolectric (ombres système
 * réelles) pour texte normal, vide et nul, avec le Toast de confirmation
 * conditionné à l'API (le système confirme lui-même dès Android 13).
 */
@RunWith(RobolectricTestRunner::class)
class CopieurPressePapiersAndroidTest {
    private fun copieur(): CopieurPressePapiersAndroid {
        val contexte = ApplicationProvider.getApplicationContext<Context>()
        return CopieurPressePapiersAndroid(contexte)
    }

    private fun clipCourant(): CharSequence? {
        val contexte = ApplicationProvider.getApplicationContext<Context>()
        val pressePapiers = contexte.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return pressePapiers.primaryClip?.getItemAt(0)?.coerceToText(contexte)
    }

    @Test
    @Config(sdk = [34])
    fun `un texte normal arrive au presse-papiers`() {
        copieur().copier("grep -rn TODO src/")

        assertEquals("grep -rn TODO src/", clipCourant())
    }

    @Test
    @Config(sdk = [34])
    fun `un texte vide n ecrit rien`() {
        copieur().copier("")

        assertNull("aucun clip ne devait être posé pour un texte vide", clipCourant())
    }

    @Test
    @Config(sdk = [34])
    fun `un texte nul n ecrit rien`() {
        copieur().copier(null)

        assertNull("aucun clip ne devait être posé pour un texte nul", clipCourant())
    }

    @Test
    @Config(sdk = [28])
    fun `sous android 13 le toast de confirmation est presente`() {
        // Avant l'API 33, aucun retour système n'existe : le Toast maison
        // reste utile — Robolectric l'enregistre, compté par ShadowToast.
        copieur().copier("ls -la")

        assertEquals("ls -la", clipCourant())
        assertEquals(
            "un Toast de confirmation devait accompagner la copie (pré-33)",
            1,
            ShadowToast.shownToastCount(),
        )
    }

    @Test
    @Config(sdk = [34])
    fun `a partir d android 13 aucun toast maison ne double la confirmation systeme`() {
        // API 33+ : le système affiche déjà son bandeau à setPrimaryClip —
        // un Toast maison ferait doublon.
        copieur().copier("pwd")

        assertEquals("pwd", clipCourant())
        assertEquals(
            "aucun toast maison ne devait accompagner la confirmation système",
            0,
            ShadowToast.shownToastCount(),
        )
    }

    @Test
    @Config(sdk = [34])
    fun `la copie successive remplace le clip courant`() {
        copieur().copier("premier")
        copieur().copier("second")

        assertEquals("second", clipCourant())
    }
}
