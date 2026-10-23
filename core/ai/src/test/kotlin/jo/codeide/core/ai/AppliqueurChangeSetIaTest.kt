package jo.codeide.core.ai

import jo.codeide.core.domain.EntreeHistorique
import jo.codeide.core.domain.FileStat
import jo.codeide.core.domain.FileSystem
import jo.codeide.core.domain.HistoriqueLocal
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.domain.TypeEntreeHistorique
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'application du ChangeSet (IA-7b, ADR 0125 « Réversibilité ») :
 * conflit détecté avant écriture, garde-fou historique, annulation exacte.
 */
class AppliqueurChangeSetIaTest {
    // ------------------------------------------------------------------
    // Faux en mémoire — arborescence SAF simulée par chemins (« / » racine).
    // ------------------------------------------------------------------

    /** FileSystem mémoire : URI = « / » + chemin relatif. */
    private class FauxFileSystem : FileSystem {
        val dossiers = mutableSetOf("/")
        val fichiers = mutableMapOf<String, String>()

        var echoueReadText: (uri: String) -> Boolean = { false }

        fun pose(chemin: String, contenu: String) {
            fichiers["/$chemin"] = contenu
            // Les dossiers parents sont enregistrés (arborescence cohérente).
            var courant = ""
            for (segment in chemin.split('/').dropLast(1)) {
                courant = "$courant/$segment"
                dossiers.add(courant)
            }
        }

        fun lu(chemin: String): String? = fichiers["/$chemin"]

        private fun statDe(uri: String, dossier: Boolean): FileStat =
            FileStat(uri, uri.substringAfterLast('/'), dossier, 0L, 0L)

        private fun enfants(uri: String): List<FileStat> {
            val prefixe = if (uri == "/") "/" else "$uri/"
            return (
                dossiers
                    .filter { it != "/" && it.startsWith(prefixe) && !it.removePrefix(prefixe).contains('/') } +
                    fichiers.keys.filter { it.startsWith(prefixe) && !it.removePrefix(prefixe).contains('/') }
                ).map { plein ->
                statDe(plein, dossiers.contains(plein))
            }.sortedBy { it.name }
        }

        override suspend fun exists(documentUri: String): Boolean = documentUri in dossiers || documentUri in fichiers

        override suspend fun stat(documentUri: String): AppResult<FileStat> = when {
            documentUri in dossiers -> AppResult.Success(statDe(documentUri, true))
            documentUri in fichiers -> AppResult.Success(statDe(documentUri, false))
            else -> AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound))
        }

        override suspend fun list(directoryUri: String): AppResult<List<FileStat>> = if (directoryUri in dossiers) {
            AppResult.Success(enfants(directoryUri))
        } else {
            AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound))
        }

        override suspend fun createDirectory(parentDirectoryUri: String, name: String): AppResult<String> {
            val uri = if (parentDirectoryUri == "/") "/$name" else "$parentDirectoryUri/$name"
            if (uri in dossiers || uri in fichiers) {
                return AppResult.Failure(AppError.Storage(AppError.StorageReason.AlreadyExists))
            }
            dossiers.add(uri)
            return AppResult.Success(uri)
        }

        override suspend fun createFile(
            parentDirectoryUri: String,
            name: String,
            mimeType: String,
        ): AppResult<String> {
            val uri = if (parentDirectoryUri == "/") "/$name" else "$parentDirectoryUri/$name"
            if (uri in dossiers || uri in fichiers) {
                return AppResult.Failure(AppError.Storage(AppError.StorageReason.AlreadyExists))
            }
            fichiers[uri] = ""
            return AppResult.Success(uri)
        }

        override suspend fun writeText(documentUri: String, text: String): AppResult<Unit> =
            if (documentUri in fichiers) {
                fichiers[documentUri] = text
                AppResult.Success(Unit)
            } else {
                AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound))
            }

        override suspend fun readText(documentUri: String): AppResult<String> = when {
            echoueReadText(documentUri) -> AppResult.Failure(AppError.Storage(AppError.StorageReason.Io))
            documentUri in fichiers -> AppResult.Success(fichiers[documentUri] ?: "")
            else -> AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound))
        }

        override suspend fun delete(documentUri: String): AppResult<Unit> {
            fichiers.remove(documentUri)
            dossiers.remove(documentUri)
            return AppResult.Success(Unit)
        }

        override suspend fun rename(documentUri: String, nouveauNom: String): AppResult<String> =
            AppResult.Failure(AppError.Storage(AppError.StorageReason.Io))

        override suspend fun writeBytes(documentUri: String, bytes: ByteArray): AppResult<Unit> =
            AppResult.Failure(AppError.Storage(AppError.StorageReason.Io))

        override suspend fun readBytes(documentUri: String): AppResult<ByteArray> =
            AppResult.Failure(AppError.Storage(AppError.StorageReason.Io))

        override suspend fun takePersistablePermission(grantUri: String): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun releasePersistablePermission(grantUri: String): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun hasPersistablePermission(grantUri: String): Boolean = true
    }

    /** Historique mémoire : étiquettes posées tracées, échec injectable. */
    private class FauxHistorique : HistoriqueLocal {
        val etiquettes = mutableListOf<String>()
        var echec: Boolean = false
        private var compteur = 0L

        override suspend fun enregistrer(
            cheminRelatif: String,
            type: TypeEntreeHistorique,
            contenu: String?,
            tailleOctets: Long,
            libelle: String?,
        ): EntreeHistorique? = null

        override suspend fun listerRevisions(cheminRelatif: String): List<EntreeHistorique> = emptyList()

        override suspend fun listerRevisionsSous(cheminDossier: String, limite: Int): List<EntreeHistorique> =
            emptyList()

        override suspend fun lireContenu(id: Long): String? = null

        override suspend fun derniereEmpreinte(cheminRelatif: String): String? = null

        override suspend fun etiqueter(nom: String, cheminRelatif: String?): EntreeHistorique? {
            if (echec) return null
            etiquettes.add(nom)
            compteur += 1
            return EntreeHistorique(compteur, cheminRelatif ?: "", TypeEntreeHistorique.ETIQUETTE, 0L, null, 0L, nom)
        }

        override suspend fun purger() = Unit

        override suspend fun empreinteOctets(): Long = 0L

        override suspend fun effacerTout() = Unit
    }

    private class Horloge : TimeProvider {
        override fun nowMillis(): Long = 1_700_000_000_000L
    }

    private val fichiers = FauxFileSystem()
    private val historique = FauxHistorique()
    private val appliqueur = AppliqueurChangeSetIa(fichiers, historique, Horloge())

    private fun empreinte(contenu: String): String = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(contenu.toByteArray(Charsets.UTF_8))
        .joinToString("") { octet -> "%02x".format(octet) }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    fun `une modification sans divergence est appliquée et étiquetée`() = runTest {
        fichiers.pose("src/App.kt", "original")
        val changeSet =
            ChangeSetIa() +
                ChangementIa("src/App.kt", TypeChangementIa.MODIFIER, "réécrit", empreinte("original"), "test")
        val (execution, resultat) = appliqueur.appliquer(changeSet, "/")
        assertTrue(resultat is ResultatApplicationIa.Succes)
        assertEquals("réécrit", fichiers.lu("src/App.kt"))
        assertEquals(1, historique.etiquettes.size)
        assertTrue(historique.etiquettes[0].startsWith("IA · "))
        assertEquals(1, execution?.fichiers?.size)
        assertEquals("original", execution?.fichiers?.first()?.contenuAvant)
    }

    @Test
    fun `un fichier modifié depuis la proposition est en conflit et jamais écrasé`() = runTest {
        fichiers.pose("src/App.kt", "CHANGÉ ENTRE-TEMPS")
        val changeSet =
            ChangeSetIa() +
                ChangementIa("src/App.kt", TypeChangementIa.MODIFIER, "réécrit", empreinte("original"), "test")
        val revue = appliqueur.preparer(changeSet, "/")
        assertTrue(revue.fichiers.first().conflit)
        val (execution, resultat) = appliqueur.appliquer(changeSet, "/")
        assertTrue(resultat is ResultatApplicationIa.Echec)
        assertEquals(null, execution)
        assertEquals("CHANGÉ ENTRE-TEMPS", fichiers.lu("src/App.kt"))
    }

    @Test
    fun `une création d'un fichier déjà existant est en conflit`() = runTest {
        fichiers.pose("nouveau.kt", "existant")
        val changeSet =
            ChangeSetIa() + ChangementIa("nouveau.kt", TypeChangementIa.CREER, "écrasé ?", null, "test")
        val revue = appliqueur.preparer(changeSet, "/")
        assertTrue(revue.fichiers.first().conflit)
    }

    @Test
    fun `historique en échec — application refusée sans aucune écriture`() = runTest {
        historique.echec = true
        fichiers.pose("src/App.kt", "original")
        val changeSet =
            ChangeSetIa() +
                ChangementIa("src/App.kt", TypeChangementIa.MODIFIER, "réécrit", empreinte("original"), "test")
        val (execution, resultat) = appliqueur.appliquer(changeSet, "/")
        assertTrue(resultat is ResultatApplicationIa.Echec)
        assertTrue((resultat as ResultatApplicationIa.Echec).motif.contains("historique"))
        assertEquals("original", fichiers.lu("src/App.kt"))
        assertEquals(null, execution)
    }

    @Test
    fun `seuls les fichiers retenus sont appliqués`() = runTest {
        fichiers.pose("a.kt", "A")
        fichiers.pose("b.kt", "B")
        val changeSet =
            ChangeSetIa() +
                ChangementIa("a.kt", TypeChangementIa.MODIFIER, "A2", empreinte("A"), "test") +
                ChangementIa("b.kt", TypeChangementIa.MODIFIER, "B2", empreinte("B"), "test")
        val (execution, resultat) = appliqueur.appliquer(changeSet, "/", retenus = setOf("a.kt"))
        assertTrue(resultat is ResultatApplicationIa.Succes)
        assertEquals("A2", fichiers.lu("a.kt"))
        assertEquals("B", fichiers.lu("b.kt"))
        assertEquals(1, execution?.fichiers?.size)
    }

    @Test
    fun `l'annulation restaure exactement création modification et suppression`() = runTest {
        fichiers.pose("modifie.kt", "avant")
        fichiers.pose("supprime.kt", "perdu ?")
        val changeSet =
            ChangeSetIa() +
                ChangementIa("cree.kt", TypeChangementIa.CREER, "né de l'agent", null, "test") +
                ChangementIa("modifie.kt", TypeChangementIa.MODIFIER, "après", empreinte("avant"), "test") +
                ChangementIa("supprime.kt", TypeChangementIa.SUPPRIMER, "", empreinte("perdu ?"), "test")
        val (execution, resultat) = appliqueur.appliquer(changeSet, "/")
        assertTrue(resultat is ResultatApplicationIa.Succes)
        assertEquals("né de l'agent", fichiers.lu("cree.kt"))
        assertEquals("après", fichiers.lu("modifie.kt"))
        assertEquals(null, fichiers.lu("supprime.kt"))

        val annulation = appliqueur.annuler(execution!!)
        assertTrue(annulation is ResultatApplicationIa.Succes)
        assertEquals(null, fichiers.lu("cree.kt"))
        assertEquals("avant", fichiers.lu("modifie.kt"))
        assertEquals("perdu ?", fichiers.lu("supprime.kt"))
        assertTrue(historique.etiquettes.any { it.contains("annulation") })
    }

    @Test
    fun `une création dans un dossier absent crée les parents`() = runTest {
        val changeSet =
            ChangeSetIa() +
                ChangementIa("src/nouveau/Dossier/Texte.kt", TypeChangementIa.CREER, "contenu", null, "test")
        val (execution, resultat) = appliqueur.appliquer(changeSet, "/")
        assertTrue(resultat is ResultatApplicationIa.Succes)
        assertEquals("contenu", fichiers.lu("src/nouveau/Dossier/Texte.kt"))
        assertEquals(1, execution?.fichiers?.size)
    }

    @Test
    fun `une suppression de fichier non textuel est refusée sans suppression`() = runTest {
        fichiers.pose("binaire.dat", "octets")
        fichiers.echoueReadText = { uri -> uri.endsWith("binaire.dat") }
        val changeSet =
            ChangeSetIa() + ChangementIa("binaire.dat", TypeChangementIa.SUPPRIMER, "", empreinte("octets"), "test")
        val (execution, resultat) = appliqueur.appliquer(changeSet, "/")
        assertTrue(resultat is ResultatApplicationIa.Echec)
        assertFalse(fichiers.fichiers.isEmpty() || !fichiers.fichiers.containsKey("/binaire.dat"))
        assertEquals(null, execution)
    }

    @Test
    fun `une racine absente refuse l'application`() = runTest {
        val changeSet =
            ChangeSetIa() + ChangementIa("a.kt", TypeChangementIa.CREER, "x", null, "test")
        val (_, resultat) = appliqueur.appliquer(changeSet, null)
        assertTrue(resultat is ResultatApplicationIa.Echec)
    }
}
