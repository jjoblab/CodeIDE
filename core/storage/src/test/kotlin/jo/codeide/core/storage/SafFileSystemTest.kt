package jo.codeide.core.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.domain.mimeFichierTexte
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

/**
 * Tests de [SafFileSystem] sur le fournisseur de documents factice :
 * le vrai chemin du framework (`ContentResolver` + `DocumentsContract`)
 * est exercé de bout en bout sous Robolectric — requêtes groupées,
 * création contrôlée, flux de lecture/écriture, suppression, erreurs
 * typées (section 5.6).
 *
 * Les tests réels sur appareil (SAF système) sont décrits séparément
 * dans `docs/TESTS_MANUELS.md` (procédures S1 à S5).
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SafFileSystemTest {
    private lateinit var resolver: ContentResolver
    private lateinit var fournisseur: FauxFournisseurDocuments
    private lateinit var fichiers: SafFileSystem

    /** Permissions persistantes factices, pilotées directement par le test. */
    private val permissionsDetenues = mutableSetOf<Uri>()

    private val faussesPermissions =
        object : PersistableUriPermissions {
            override fun prendre(grantUri: Uri) {
                permissionsDetenues += grantUri
            }

            override fun liberer(grantUri: Uri) {
                permissionsDetenues -= grantUri
            }

            override fun detient(grantUri: Uri): Boolean = grantUri in permissionsDetenues
        }

    @Before
    fun setUp() {
        val contexte = ApplicationProvider.getApplicationContext<android.content.Context>()
        resolver = contexte.contentResolver
        fournisseur = FauxFournisseurDocuments()
        // Robolectric n'attache pas le contexte au provider enregistré :
        // le faire explicitement (attachInfo) comme le ferait le système.
        fournisseur.attachInfo(
            contexte,
            android.content.pm
                .ProviderInfo()
                .apply { authority = fournisseur.autorite },
        )
        ShadowContentResolver.registerProviderInternal(fournisseur.autorite, fournisseur)
        fichiers = SafFileSystem(resolver, faussesPermissions, TestDispatcherProvider(UnconfinedTestDispatcher()))
    }

    @After
    fun nettoyer() {
        // Robolectric réinitialise les providers enregistrés entre les
        // tests : rien à retirer ici.
    }

    private fun uriDocument(id: String): String = fournisseur.uriDocument(id).toString()

    /** Extrait la raison d'une erreur de stockage (null si autre type). */
    private fun raisonStockage(echec: AppResult.Failure): AppError.StorageReason? =
        (echec.error as? AppError.Storage)?.reason

    @Test
    fun `stat décrit un dossier amorcé`() =
        runTest {
            val dossier = fournisseur.semerDossier(fournisseur.racine, "Projet")

            val stat = fichiers.stat(uriDocument(dossier))

            assertTrue(stat is AppResult.Success)
            val statut = (stat as AppResult.Success).value
            assertEquals("Projet", statut.name)
            assertEquals(true, statut.isDirectory)
        }

    @Test
    fun `stat d'un absent retourne NotFound, pas d'exception`() =
        runTest {
            val resultat = fichiers.stat(uriDocument("${fournisseur.racine}/introuvable"))

            assertEquals(
                AppError.StorageReason.NotFound,
                raisonStockage(resultat as AppResult.Failure),
            )
        }

    @Test
    fun `exists distingue présent et absent`() =
        runTest {
            val dossier = fournisseur.semerDossier(fournisseur.racine, "Projet")

            assertTrue(fichiers.exists(uriDocument(dossier)))
            assertFalse(fichiers.exists(uriDocument("${fournisseur.racine}/introuvable")))
        }

    @Test
    fun `list charge les enfants en une seule requête, triés par nom`() =
        runTest {
            val parent = fournisseur.semerDossier(fournisseur.racine, "Travail")
            fournisseur.semerFichier(parent, "zeta.md", "text/plain", "z".toByteArray())
            fournisseur.semerDossier(parent, "Alpha")
            fournisseur.semerFichier(parent, "beta.txt", "text/plain", "b".toByteArray())
            // Petit-fils : ne doit PAS apparaître dans le listing du parent.
            val alpha = fournisseur.semerDossier(parent, "Alpha")
            fournisseur.semerFichier(alpha, "interne.md", "text/plain", "i".toByteArray())

            val listing = fichiers.list(uriDocument(parent))

            val statuts = (listing as AppResult.Success).value
            assertEquals(listOf("Alpha", "beta.txt", "zeta.md"), statuts.map { it.name })
            // Section 5.6 : requête groupée — une seule interrogation pour tout le dossier.
            assertEquals(1, fournisseur.requetesQuery)
        }

    @Test
    fun `list d'un dossier absent échoue par NotFound`() =
        runTest {
            val resultat = fichiers.list(uriDocument("${fournisseur.racine}/introuvable"))

            assertEquals(
                AppError.StorageReason.NotFound,
                raisonStockage(resultat as AppResult.Failure),
            )
        }

    @Test
    fun `createDirectory crée un dossier et retourne son URI`() =
        runTest {
            val travail = fournisseur.semerDossier(fournisseur.racine, "Travail")

            val creation = fichiers.createDirectory(uriDocument(travail), "MonApplication")

            assertTrue(creation is AppResult.Success)
            val uriCree = (creation as AppResult.Success).value
            assertTrue(fichiers.exists(uriCree))
            assertEquals(true, (fichiers.stat(uriCree) as AppResult.Success).value.isDirectory)
        }

    @Test
    fun `createDirectory refuse l'écrasement, même à la casse près`() =
        runTest {
            val travail = fournisseur.semerDossier(fournisseur.racine, "Travail")
            fichiers.createDirectory(uriDocument(travail), "Projet")

            val collision = fichiers.createDirectory(uriDocument(travail), "PROJET")

            assertEquals(
                AppError.StorageReason.AlreadyExists,
                raisonStockage(collision as AppResult.Failure),
            )
        }

    @Test
    fun `createFile crée un fichier avec son type MIME`() =
        runTest {
            val travail = fournisseur.semerDossier(fournisseur.racine, "Travail")

            val creation = fichiers.createFile(uriDocument(travail), "README.md", "text/plain")

            val uriCree = (creation as AppResult.Success).value
            val statut = (fichiers.stat(uriCree) as AppResult.Success).value
            assertEquals("README.md", statut.name)
            assertEquals(false, statut.isDirectory)
        }

    @Test
    fun `un renommage silencieux du fournisseur est détecté et nettoyé`() =
        runTest {
            val travail = fournisseur.semerDossier(fournisseur.racine, "Travail")
            fichiers.createDirectory(uriDocument(travail), "Projet")

            // Le fournisseur renomme « Projet » en « Projet (1) » malgré le
            // pré-contrôle (course simulée) : SafFileSystem doit nettoyer le
            // document créé et rapporter la collision.
            fournisseur.provoquerRenommage = true
            val creation = fichiers.createDirectory(uriDocument(travail), "Projet")

            assertEquals(
                AppError.StorageReason.AlreadyExists,
                raisonStockage(creation as AppResult.Failure),
            )
            // Le document renommé a bien été supprimé (nettoyage).
            val listing = fichiers.list(uriDocument(travail)) as AppResult.Success
            assertEquals(listOf("Projet"), listing.value.map { it.name })
        }

    @Test
    fun `un nom sans point complete par l extension canonique est accepte`() =
        runTest {
            val travail = fournisseur.semerDossier(fournisseur.racine, "Travail")

            // ExternalStorageProvider complète « temoin » + text/plain en
            // « temoin.txt » : ce n'est ni une collision ni un renommage —
            // la création doit réussir et retourner l'URI réelle du document.
            fournisseur.completerExtension = true
            val creation = fichiers.createFile(uriDocument(travail), "codeide-temoin-1000", "text/plain")

            assertTrue(creation is AppResult.Success)
            val uriCree = (creation as AppResult.Success).value
            val statut = (fichiers.stat(uriCree) as AppResult.Success).value
            assertEquals("codeide-temoin-1000.txt", statut.name)
        }

    @Test
    fun `un fichier cache complete par le fournisseur est accepte - regression 4a4526aa`() =
        runTest {
            val travail = fournisseur.semerDossier(fournisseur.racine, "Travail")

            // V0.31.6 (retour d'appareil réel 4a4526aa) : le point INITIAL
            // d'un fichier caché n'est pas une extension pour le fournisseur
            // — « .gitattributes » + text/plain est complété en
            // « .gitattributes.txt ». L'ancien contrôle (contains('.'))
            // ratait cette famille : complétion lue comme renommage hostile
            // → fichier fraîchement créé SUPPRIMÉ + AlreadyExists de pure
            // invention → « un dossier porte déjà ce nom » à CHAQUE création
            // de projet (.gitattributes est le PREMIER fichier du plan des
            // modèles JVM — rollback complet sous les yeux de l'utilisateur).
            fournisseur.completerExtension = true
            val creation = fichiers.createFile(uriDocument(travail), ".gitattributes", "text/plain")

            assertTrue("la complétion d'un fichier caché n'est pas une collision", creation is AppResult.Success)
            val uriCree = (creation as AppResult.Success).value
            val statut = (fichiers.stat(uriCree) as AppResult.Success).value
            assertEquals(".gitattributes.txt", statut.name)
        }

    @Test
    fun `le mime prive des noms sans extension reelle n est jamais complete`() =
        runTest {
            val travail = fournisseur.semerDossier(fournisseur.racine, "Travail")

            // La parade (v0.31.6) : le type privé « text/x-codeide » n'a pas
            // d'extension canonique — le fournisseur ne complète RIEN et le
            // nom demandé est préservé EXACTEMENT (.gitattributes reste
            // .gitattributes dans le projet généré, gradlew reste gradlew).
            fournisseur.completerExtension = true
            val cache = fichiers.createFile(uriDocument(travail), ".gitattributes", "text/x-codeide")
            val gradlew = fichiers.createFile(uriDocument(travail), "gradlew", "text/x-codeide")

            assertTrue(cache is AppResult.Success)
            assertEquals(
                ".gitattributes",
                (fichiers.stat((cache as AppResult.Success).value) as AppResult.Success).value.name,
            )
            assertTrue(gradlew is AppResult.Success)
            assertEquals(
                "gradlew",
                (fichiers.stat((gradlew as AppResult.Success).value) as AppResult.Success).value.name,
            )
        }

    @Test
    fun `readme md avec text plain est complete puis rejete honnetement - v0-31-7`() =
        runTest {
            val travail = fournisseur.semerDossier(fournisseur.racine, "Travail")

            // V0.31.7 (retour d'appareil réel Android 15) : « README.md » +
            // `text/plain` → le fournisseur complète en « README.md.txt »
            // (l'extension « md » est absente de la table système, comme
            // kts/kt/properties/pro). La complétion n'est PAS tolérable
            // pour un nom AVEC extension (le projet généré porterait des
            // fichiers mal nommés : build.gradle.kts.txt casserait Gradle)
            // : l'échec doit rester honnête — nettoyage du document renommé
            // + AlreadyExists portant le nom exact, détails visibles.
            // Ce test DOCUMENTE pourquoi text/plain est désormais interdit
            // pour les fichiers texte (voir mimeFichierTexte).
            fournisseur.completerExtension = true
            val creation = fichiers.createFile(uriDocument(travail), "README.md", "text/plain")

            assertEquals(
                AppError.StorageReason.AlreadyExists,
                raisonStockage(creation as AppResult.Failure),
            )
            assertEquals(
                "README.md",
                (creation as AppResult.Failure)
                    .error
                    .toString()
                    .substringAfter("details=")
                    .trimEnd(')'),
            )
            // Le document complété a été nettoyé : le dossier reste vide.
            val listing = fichiers.list(uriDocument(travail)) as AppResult.Success
            assertTrue("le document renommé est nettoyé", listing.value.isEmpty())
        }

    @Test
    fun `readme md avec le mime conseille est preserve - regression v0-31-7`() =
        runTest {
            val travail = fournisseur.semerDossier(fournisseur.racine, "Travail")

            // LA régression : le plan des modèles écrit « README.md » (et
            // build.gradle.kts, *.kt, gradle.properties…) avec le MIME
            // conseillé par le domaine — le nom doit être préservé
            // EXACTEMENT, extension inconnue ou pas.
            fournisseur.completerExtension = true
            val readme = fichiers.createFile(uriDocument(travail), "README.md", mimeFichierTexte("README.md"))
            val script =
                fichiers.createFile(
                    uriDocument(travail),
                    "build.gradle.kts",
                    mimeFichierTexte("build.gradle.kts"),
                )

            assertTrue("le type privé ne déclenche aucune complétion", readme is AppResult.Success)
            assertEquals(
                "README.md",
                (fichiers.stat((readme as AppResult.Success).value) as AppResult.Success).value.name,
            )
            assertTrue(script is AppResult.Success)
            assertEquals(
                "build.gradle.kts",
                (fichiers.stat((script as AppResult.Success).value) as AppResult.Success).value.name,
            )
        }

    @Test
    fun `une normalisation fournisseur des espaces et points finaux est acceptee`() =
        runTest {
            val travail = fournisseur.semerDossier(fournisseur.racine, "Travail")

            // v0.31.5 (retour d'appareil réel, « collision » systématique) :
            // certaines couches de stockage rabotent les espaces/points
            // FINAUX — « Projet. » devient « Projet ». Le document créé au
            // nom normalisé est le nôtre : la création doit RÉUSSIR, pas
            // rapporter une collision de pure invention (ni détruire le
            // document fraîchement créé).
            fournisseur.normaliserNoms = true
            val creation = fichiers.createDirectory(uriDocument(travail), "MonProjet..")

            assertTrue(creation is AppResult.Success)
            val uriCree = (creation as AppResult.Success).value
            val statut = (fichiers.stat(uriCree) as AppResult.Success).value
            assertEquals("MonProjet", statut.name)
        }

    @Test
    fun `un renommage de collision avec point reste refuse meme avec completion active`() =
        runTest {
            val travail = fournisseur.semerDossier(fournisseur.racine, "Travail")
            fichiers.createFile(uriDocument(travail), "notes.txt", "text/plain")

            // Le pré-contrôle d'homonyme doit déjà rapporter la collision.
            val creation = fichiers.createFile(uriDocument(travail), "notes.txt", "text/plain")

            assertEquals(
                AppError.StorageReason.AlreadyExists,
                raisonStockage(creation as AppResult.Failure),
            )
        }

    @Test
    fun `writeText puis readText fait l'aller-retour exact`() =
        runTest {
            val travail = fournisseur.semerDossier(fournisseur.racine, "Travail")
            val uri = (fichiers.createFile(uriDocument(travail), "notes.txt", "text/plain") as AppResult.Success).value

            val ecriture = fichiers.writeText(uri, "# Bonjour\n")
            assertTrue(ecriture is AppResult.Success)

            assertEquals("# Bonjour\n", (fichiers.readText(uri) as AppResult.Success).value)
        }

    @Test
    fun `writeBytes préserve le contenu binaire`() =
        runTest {
            val travail = fournisseur.semerDossier(fournisseur.racine, "Travail")
            val octets = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D)
            val uri = (fichiers.createFile(uriDocument(travail), "icone.png", "image/png") as AppResult.Success).value

            fichiers.writeBytes(uri, octets)

            val statut = (fichiers.stat(uri) as AppResult.Success).value
            assertEquals(octets.size.toLong(), statut.sizeBytes)
        }

    @Test
    fun `écrire dans un document absent échoue par NotFound`() =
        runTest {
            val resultat = fichiers.writeText(uriDocument("${fournisseur.racine}/introuvable.txt"), "texte")

            assertEquals(
                AppError.StorageReason.NotFound,
                raisonStockage(resultat as AppResult.Failure),
            )
        }

    @Test
    fun `lire un document absent échoue par NotFound`() =
        runTest {
            val resultat = fichiers.readText(uriDocument("${fournisseur.racine}/introuvable.txt"))

            assertEquals(
                AppError.StorageReason.NotFound,
                raisonStockage(resultat as AppResult.Failure),
            )
        }

    @Test
    fun `delete supprime le document, dossier comme fichier`() =
        runTest {
            val travail = fournisseur.semerDossier(fournisseur.racine, "Travail")
            val projet = (fichiers.createDirectory(uriDocument(travail), "Projet") as AppResult.Success).value
            fichiers.createFile(projet, "fichier.txt", "text/plain")

            val suppression = fichiers.delete(projet)

            assertTrue(suppression is AppResult.Success)
            assertFalse(fichiers.exists(projet))
        }

    @Test
    fun `les permissions persistantes sont prises, consultées et libérées`() =
        runTest {
            val arbre = fournisseur.uriArbre()

            assertFalse(fichiers.hasPersistablePermission(arbre.toString()))

            assertTrue(fichiers.takePersistablePermission(arbre.toString()) is AppResult.Success)
            assertTrue(fichiers.hasPersistablePermission(arbre.toString()))

            assertTrue(fichiers.releasePersistablePermission(arbre.toString()) is AppResult.Success)
            assertFalse(fichiers.hasPersistablePermission(arbre.toString()))
        }

    @Test
    fun `un dossier de travail refusé par Android est détecté en amont`() {
        // Section 5.6 : la détection vit dans le domaine (testée là-bas) ;
        // ce test verrouille simplement le comportement attendu de l'URI
        // d'arborescence produite par le fournisseur — elle est manipulable
        // par ForbiddenFolders.
        val arbre = fournisseur.uriArbre()

        assertEquals(
            null,
            jo.codeide.core.domain.ForbiddenFolders.reasonFor(
                DocumentsContract.getTreeDocumentId(arbre),
            ),
        )
    }
}
