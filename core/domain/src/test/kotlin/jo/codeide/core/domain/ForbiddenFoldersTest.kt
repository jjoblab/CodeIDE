package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests de [ForbiddenFolders] : la logique pure de détection des dossiers
 * refusés par Android 11+ (section 5.6), indépendante du fournisseur.
 */
class ForbiddenFoldersTest {
    @Test
    fun `la racine du stockage principal est refusée`() {
        assertEquals(ForbiddenFolders.Reason.STORAGE_ROOT, ForbiddenFolders.reasonFor("primary:"))
        // Variante avec slash parasite : même décision.
        assertEquals(ForbiddenFolders.Reason.STORAGE_ROOT, ForbiddenFolders.reasonFor("primary:/"))
    }

    @Test
    fun `la racine d'une carte SD est refusée`() {
        assertEquals(ForbiddenFolders.Reason.STORAGE_ROOT, ForbiddenFolders.reasonFor("1A2B-3C4D:"))
    }

    @Test
    fun `Download à la racine est refusé`() {
        assertEquals(ForbiddenFolders.Reason.DOWNLOADS, ForbiddenFolders.reasonFor("primary:Download"))
        assertEquals(ForbiddenFolders.Reason.DOWNLOADS, ForbiddenFolders.reasonFor("1A2B-3C4D:Download"))
    }

    @Test
    fun `un Download imbriqué plus profond est autorisé`() {
        assertNull(ForbiddenFolders.reasonFor("primary:CodeIDE/Download"))
        assertNull(ForbiddenFolders.reasonFor("primary:Téléchargements"))
    }

    @Test
    fun `Android data et obb sont refusés`() {
        assertEquals(ForbiddenFolders.Reason.ANDROID_DATA, ForbiddenFolders.reasonFor("primary:Android/data"))
        assertEquals(ForbiddenFolders.Reason.ANDROID_OBB, ForbiddenFolders.reasonFor("primary:Android/obb"))
        assertEquals(
            ForbiddenFolders.Reason.ANDROID_DATA,
            ForbiddenFolders.reasonFor("primary:Android/data/com.exemple"),
        )
        assertEquals(
            ForbiddenFolders.Reason.ANDROID_OBB,
            ForbiddenFolders.reasonFor("primary:Android/obb/com.exemple"),
        )
    }

    @Test
    fun `le dossier Android seul n'est pas refusé`() {
        // « Android » sans deuxième segment : pas une sous-arborescence
        // protégée — la racine est déjà couverte par STORAGE_ROOT.
        assertNull(ForbiddenFolders.reasonFor("primary:Android"))
    }

    @Test
    fun `la casse et les slashs parasites sont tolérés`() {
        assertEquals(ForbiddenFolders.Reason.DOWNLOADS, ForbiddenFolders.reasonFor("primary:download"))
        assertEquals(ForbiddenFolders.Reason.ANDROID_DATA, ForbiddenFolders.reasonFor("primary:android/DATA/"))
    }

    @Test
    fun `la forme brute raw est ramenée au chemin relatif du volume`() {
        assertEquals(
            ForbiddenFolders.Reason.STORAGE_ROOT,
            ForbiddenFolders.reasonFor("raw:/storage/emulated/0"),
        )
        assertEquals(
            ForbiddenFolders.Reason.DOWNLOADS,
            ForbiddenFolders.reasonFor("raw:/storage/emulated/0/Download"),
        )
        assertEquals(
            ForbiddenFolders.Reason.ANDROID_DATA,
            ForbiddenFolders.reasonFor("raw:/storage/emulated/0/Android/data"),
        )
    }

    @Test
    fun `les identifiants opaques ou vides sont réputés autorisés`() {
        assertNull(ForbiddenFolders.reasonFor("un-identifiant-opaque"))
        assertNull(ForbiddenFolders.reasonFor(""))
        assertNull(ForbiddenFolders.reasonFor("   "))
        assertNull(ForbiddenFolders.reasonFor("raw:/autre/point/de/montage"))
    }

    @Test
    fun `un dossier de travail légitime est autorisé`() {
        assertNull(ForbiddenFolders.reasonFor("primary:CodeIDE"))
        assertNull(ForbiddenFolders.reasonFor("primary:Documents/Projets"))
        assertNull(ForbiddenFolders.reasonFor("1A2B-3C4D:AndroidProjets"))
    }
}
