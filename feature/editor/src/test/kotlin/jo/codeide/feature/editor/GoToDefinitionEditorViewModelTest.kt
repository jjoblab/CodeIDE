package jo.codeide.feature.editor

import jo.codeide.core.domain.ResolveurCheminFuse
import jo.codeide.core.testing.FakeFileSystem
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.182.0 — Go-to cross-fichier : les cibles de définition rendues par
 * le serveur LSP (fichier projet `file://…` ou type binaire
 * `jdt://decompiled/…`) ouvrent ENFIN un onglet côté espace de travail.
 *
 * <p>Avant ce lot, la bibliothèque cel appelait
 * {@code onDefinitionRequested} pour toute cible hors fichier courant —
 * destinataire jamais posé par l'activité : le go-to cross-fichier était
 * un no-op silencieux (« cliquer AppCompatActivity ne fait rien », retour
 * propriétaire).</p>
 */
class GoToDefinitionEditorViewModelTest : BaseEditorViewModelTest() {
    /** Effets reçus du ViewModel (collecte en arrière-plan). */
    private val effetsRecus = mutableListOf<EffetEditor>()

    private fun avancer() {
        regleMain.dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `un go-to vers un fichier projet ouvre l onglet et saute a l offset`() = runTest {
        val id = ajouterProjet("ProjetGoTo")
        // Session LSP ouverte : la racine FUSE est résoluble — le chemin
        // cible du serveur sera relativisé CONTRE elle.
        val viewModel = viewModel(id, ResolveurCheminFuse { "/fuse/projets/ProjetGoTo" })
        collecterEffets(viewModel, effetsRecus)
        avancer()

        // Le fichier cible vit dans l'arbre du projet (chemin SAF
        // hiérarchique sous la racine document du PROJET — convention
        // sauterVersLigneSource : uriRacineDe = document du projet).
        val uriGreeter = "content://autorite/tree/ProjetGoTo/doc" +
            "/app/src/main/java/com/example/Greeter.java"
        fichiers.seedDocument(
            uriGreeter,
            FakeFileSystem.Document(
                name = "Greeter.java",
                isDirectory = false,
                bytes = "package com.example;\npublic class Greeter { void saluer() {} }".toByteArray(),
            ),
        )
        avancer()

        // Le serveur rend une Location FUSE : chemin + offset de la
        // déclaration dans le fichier CIBLE.
        viewModel.onAction(
            ActionEditor.OuvrirCibleDefinition(
                cheminCible = "file:///fuse/projets/ProjetGoTo/app/src/main/java/com/example/Greeter.java",
                offset = 42,
                nomAffiche = "Greeter",
            ),
        )
        avancer()

        val onglet = viewModel.etat.value.onglets.singleOrNull { it.nom == "Greeter.java" }
        assertNotNull("l'onglet du fichier cible doit s'ouvrir", onglet)
        assertEquals("java", onglet?.langage)
        assertFalse("un fichier projet n'est JAMAIS virtuel", onglet?.virtuelle == true)
        assertTrue(
            "la session suit l'onglet ouvert",
            viewModel.sessionDe(onglet!!.uri) != null,
        )
        assertEquals(
            "l'onglet ouvert devient actif",
            viewModel.etat.value.onglets[viewModel.etat.value.indexOngletActif].uri,
            onglet.uri,
        )
        // Le saut à l'offset part vers l'écran (l'onglet vient d'être ouvert).
        assertTrue(
            "l'effet DefilementVersOffset doit partir",
            effetsRecus.any { it is EffetEditor.DefilementVersOffset && it.uri == onglet.uri },
        )
    }

    @Test
    fun `un go-to vers un type binaire ouvre un onglet virtuel lecture seule`() = runTest {
        val id = ajouterProjet("ProjetBinaire")
        val viewModel = viewModel(id, ResolveurCheminFuse { "/fuse/projets/ProjetBinaire" })
        avancer()

        val uriJdt = "jdt://decompiled/androidx/appcompat/app/AppCompatActivity.java"
        val texte = "// Decompiled from androidx.appcompat.app.AppCompatActivity\n"
        viewModel.onAction(
            ActionEditor.OuvrirBufferLectureSeule(
                uri = uriJdt,
                nom = "AppCompatActivity.java",
                texte = texte,
            ),
        )
        avancer()

        val onglet = viewModel.etat.value.onglets.singleOrNull { it.uri == uriJdt }
        assertNotNull("l'onglet virtuel doit s'ouvrir", onglet)
        assertTrue("l'onglet est VIRTUEL (lecture seule)", onglet!!.virtuelle)
        assertEquals("AppCompatActivity.java", onglet.nom)
        assertEquals("java", onglet.langage)
        assertEquals("décompilé/AppCompatActivity.java", onglet.cheminRelatif)
        assertEquals(texte, viewModel.sessionDe(uriJdt)?.text)

        // Ré-ouvrir le MÊME type sélectionne l'onglet existant (pas de doublon).
        viewModel.onAction(
            ActionEditor.OuvrirBufferLectureSeule(uriJdt, "AppCompatActivity.java", texte),
        )
        avancer()
        assertEquals(
            "un second go-to sur le même type ne duplique PAS l'onglet",
            1,
            viewModel.etat.value.onglets.count { it.uri == uriJdt },
        )
    }

    @Test
    fun `un onglet virtuel n est jamais sali ni enregistre ni persiste`() = runTest {
        val id = ajouterProjet("ProjetVirtuel")
        val viewModel = viewModel(id, ResolveurCheminFuse { "/fuse/projets/ProjetVirtuel" })
        avancer()

        viewModel.onAction(
            ActionEditor.OuvrirBufferLectureSeule(
                uri = "jdt://decompiled/java/util/HashMap.java",
                nom = "HashMap.java",
                texte = "public class HashMap {}",
            ),
        )
        avancer()
        // Un fichier PROJET à côté : la persistance ne retient QUE lui.
        val uriMain = "content://autorite/tree/ProjetVirtuel/doc/Main.kt"
        fichiers.seedDocument(
            uriMain,
            FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "fun main()".toByteArray()),
        )
        viewModel.onAction(ActionEditor.OuvrirFichier(uriMain))
        avancer()

        // 1. Jamais sali : l'onglet virtuel reste propre quoi qu'il arrive.
        val virtuel = viewModel.etat.value.onglets.single { it.virtuelle }
        assertFalse(virtuel.isDirty)

        // 2. L'enregistrement est un no-op honnête (pas d'erreur).
        viewModel.onAction(ActionEditor.Enregistrer)
        avancer()
        assertFalse(viewModel.etat.value.onglets.single { it.virtuelle }.isDirty)

        // 3. La fermeture ne demande JAMAIS confirmation (jamais sale).
        viewModel.onAction(ActionEditor.FermerOnglet(virtuel.uri))
        avancer()
        assertTrue(
            "l'onglet virtuel refermé sans confirmation",
            viewModel.etat.value.onglets.none { it.uri == virtuel.uri },
        )
    }
}
