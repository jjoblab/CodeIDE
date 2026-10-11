package jo.codeide.core.bootstrap

import jo.codeide.core.testing.FakeProcessEnvironmentProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests des sondes de production de l'environnement (v0.90.1) sous
 * Robolectric : les lectures système réelles du Diagnostic Git — uid,
 * `Os.stat`, table des montages, environnement.
 *
 * Le comportement **sur appareil** (uid applicatif, stat sur FUSE)
 * relève de la validation de l'étape A ; ces tests éprouvent les
 * mêmes appels sur la machine d'exécution (Linux) : un sondeur qui
 * échoue rend `null`, jamais une exception.
 */
@RunWith(RobolectricTestRunner::class)
class SondesEnvironnementLinuxTest {
    @get:Rule
    val dossierTemp = TemporaryFolder()

    private val environnement = FakeProcessEnvironmentProvider()

    private val sondes = SondesEnvironnementLinux(environnement)

    @Test
    fun `l uid effectif est lisible`() {
        assertNotNull(sondes.uidEffectif())
    }

    @Test
    fun `le proprietaire d un dossier existant est lisible sans exception`() {
        val dossier = dossierTemp.newFolder("projet")

        val uid = sondes.uidProprietaire(dossier.absolutePath)

        // Robolectric renvoie un StructStat factice (zéros) : le contrat
        // éprouvé ici est « une valeur, jamais d'exception » — la valeur
        // RÉELLE de st_uid sur FUSE relève de la validation appareil
        // (étape A) : c'est elle qui départage l'hypothèse dubious
        // ownership.
        assertNotNull(uid)
    }

    @Test
    fun `un chemin systeme inaccessible ne casse jamais la sonde`() {
        // Robolectric ne lève pas ErrnoException pour un chemin inexistant
        // (StructStat factice) : le repli null sur ErrnoException est
        // vérifié sur appareil (étape A) ; ici, le contrat testé est
        // « aucune exception », condition du rapport toujours construit.
        val valeur = sondes.uidProprietaire("/chemin/qui/nexiste/pas")

        assertTrue(valeur == null || valeur >= 0L)
    }

    @Test
    fun `la table des montages est lisible`() {
        val contenu = sondes.contenuMonts()

        // /proc/self/mounts existe sur toute machine Linux (CI y
        // compris) ; sur l'appareil aussi (lecture monde).
        assertNotNull(contenu)
        assertTrue(contenu!!.contains(" / "))
    }

    @Test
    fun `l environnement est celui du fournisseur`() {
        environnement.semer(mapOf("HOME" to "/prefix/home", "GIT_CONFIG_GLOBAL" to "/x"))

        assertEquals(mapOf("HOME" to "/prefix/home", "GIT_CONFIG_GLOBAL" to "/x"), sondes.environnement())
    }
}
