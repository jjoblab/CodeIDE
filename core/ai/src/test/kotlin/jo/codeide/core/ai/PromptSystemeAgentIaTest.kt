package jo.codeide.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du PROMPT SYSTÈME RICHE de l'agent (rapport IA, chantier
 * IA-CODEIDE, lot 2, A5) : rôle, langue, système de build, arborescence
 * élaguée, fichier actif et sélection, règles d'outils — « lire avant de
 * modifier », « petites modifications ciblées », « terminer par un
 * résumé » — et la consigne « Planifier d'abord » quand le mode est armé.
 *
 * Le prompt vit dans une classe DÉDIÉE et testée : plus de chaîne forgée
 * dans le contrôleur.
 */
class PromptSystemeAgentIaTest {
    private val prompt = PromptSystemeAgentIa()

    private val cheminsGradle =
        listOf(
            "settings.gradle.kts",
            "build.gradle.kts",
            "app/src/main/kotlin/App.kt",
            "app/src/main/kotlin/ui/Ecran.kt",
            "core/src/main/kotlin/Core.kt",
            "README.md",
        )

    @Test
    fun `le prompt porte le role, la langue et les regles de travail`() {
        val texte = prompt.construire(DonneesPromptAgentIa(objectif = "corrige le bug"))
        assertTrue(texte.contains("agent de développement de CodeIDE"))
        assertTrue(texte.contains("TOUJOURS en français"))
        // « Lire avant de modifier », « petites modifications ciblées », « résumé ».
        assertTrue(texte.contains("LIS avant de modifier"))
        assertTrue(texte.contains("Petites modifications CIBLÉES"))
        assertTrue(texte.contains("Termine par un RÉSUMÉ"))
        // L'objectif de l'utilisateur est encadré à la fin.
        assertTrue(texte.endsWith("Objectif de l'utilisateur :\ncorrige le bug\n"))
    }

    @Test
    fun `le prompt rappelle les regles d'outils et le confinement`() {
        val texte = prompt.construire(DonneesPromptAgentIa(objectif = "x"))
        assertTrue(texte.contains("list_files"))
        assertTrue(texte.contains("read_lines"))
        assertTrue(texte.contains("search_text"))
        assertTrue(texte.contains("diagnostics_lsp"))
        assertTrue(texte.contains("RIEN n'est écrit"))
        assertTrue(texte.contains("sans la revue de l'utilisateur"))
        assertTrue(texte.contains("run_gradle"))
    }

    @Test
    fun `le systeme de build est detecte depuis les chemins et encadre`() {
        assertEquals("Gradle (Kotlin DSL)", PromptSystemeAgentIa.detecterSystemeBuild(cheminsGradle))
        assertEquals("Gradle (Groovy DSL)", PromptSystemeAgentIa.detecterSystemeBuild(listOf("build.gradle")))
        assertEquals("Maven", PromptSystemeAgentIa.detecterSystemeBuild(listOf("pom.xml", "src/A.java")))
        assertEquals("npm", PromptSystemeAgentIa.detecterSystemeBuild(listOf("package.json")))
        assertNull(PromptSystemeAgentIa.detecterSystemeBuild(listOf("src/a.txt")))

        val texte = prompt.construire(DonneesPromptAgentIa(objectif = "x", chemins = cheminsGradle))
        assertTrue(texte.contains("Système de build du projet : Gradle (Kotlin DSL)."))
    }

    @Test
    fun `l'arborescence est elaguee au premier niveau et bornee`() {
        val arborescence = PromptSystemeAgentIa.elaguerArborescence(cheminsGradle)
        // Les dossiers de premier niveau portent leur nombre de fichiers,
        // les fichiers racines sont listés seuls.
        assertTrue(arborescence.orEmpty().contains("app/ (2 fichiers)"))
        assertTrue(arborescence.orEmpty().contains("core/ (1 fichier)"))
        assertTrue(arborescence.orEmpty().contains("README.md"))
        assertFalse(arborescence.orEmpty().contains("App.kt"))

        // Bornée : au-delà de 30 entrées, le reste est compté.
        val nombreux = (1..60).map { "d$it/f$it.kt" }
        val bornee = PromptSystemeAgentIa.elaguerArborescence(nombreux).orEmpty()
        assertTrue(bornee.contains("(+30 autres entrées)"))

        // La section ne part QUE si la liste existe.
        val texte = prompt.construire(DonneesPromptAgentIa(objectif = "x", chemins = emptyList()))
        assertFalse(texte.contains("Arborescence du projet"))
    }

    @Test
    fun `le fichier actif et la selection sont encadres, la selection est bornee`() {
        val texte =
            prompt.construire(
                DonneesPromptAgentIa(
                    objectif = "x",
                    cheminFichierActif = "app/src/main/kotlin/App.kt",
                    selection = "val x = 1",
                ),
            )
        assertTrue(texte.contains("Fichier actif dans l'éditeur : app/src/main/kotlin/App.kt."))
        assertTrue(texte.contains("Sélection de l'utilisateur : « val x = 1 »."))

        // Une sélection de 10 000 caractères est bornée à 400.
        val longue = "a".repeat(10_000)
        val texteLong =
            prompt.construire(
                DonneesPromptAgentIa(objectif = "x", cheminFichierActif = "a.kt", selection = longue),
            )
        assertTrue(texteLong.contains(longue.take(PromptSystemeAgentIa.SELECTION_MAX)))
        assertFalse(texteLong.contains(longue.take(PromptSystemeAgentIa.SELECTION_MAX + 1)))

        // Sans fichier actif : aucune section vide.
        val texteSansActif = prompt.construire(DonneesPromptAgentIa(objectif = "x"))
        assertFalse(texteSansActif.contains("Fichier actif"))
    }

    @Test
    fun `la consigne Planifier d'abord ne part QUE si le mode est arme`() {
        val textePlan =
            prompt.construire(DonneesPromptAgentIa(objectif = "refonte", planPremier = true))
        assertTrue(textePlan.contains("MODE PLANIFIER D'ABORD"))
        assertTrue(textePlan.contains("plan en étapes numérotées"))
        assertTrue(textePlan.contains("AUCUNE modification"))

        val texteNormal = prompt.construire(DonneesPromptAgentIa(objectif = "refonte", planPremier = false))
        assertFalse(texteNormal.contains("MODE PLANIFIER D'ABORD"))
    }
}
