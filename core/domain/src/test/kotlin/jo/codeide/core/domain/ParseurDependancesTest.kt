package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du [ParseurDependances] (P3, ADR 0095).
 */
class ParseurDependancesTest {
    private val parseur = ParseurDependances()

    @Test
    fun `parse une implementation groupe nom version`() {
        val scripts =
            listOf(
                ScriptDeBuild(
                    cheminRelatif = "build.gradle.kts",
                    contenu =
                        """
                        plugins { kotlin("jvm") }
                        dependencies {
                            implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
                        }
                        """.trimIndent(),
                    tailleOctets = 100,
                ),
            )
        val dependances = parseur.parser(scripts)
        assertEquals(1, dependances.size)
        val dep = dependances[0]
        assertEquals("org.jetbrains.kotlin", dep.groupe)
        assertEquals("kotlin-stdlib", dep.nom)
        assertEquals("2.2.10", dep.version)
        assertEquals("implementation", dep.configuration)
        assertEquals(TypeDependance.BIBLIOTHEQUE, dep.type)
        assertEquals("org.jetbrains.kotlin:kotlin-stdlib:2.2.10", dep.coordonnes)
    }

    @Test
    fun `parse plusieurs configurations differentes`() {
        val scripts =
            listOf(
                ScriptDeBuild(
                    cheminRelatif = "app/build.gradle.kts",
                    contenu =
                        """
                        dependencies {
                            implementation("androidx.core:core-ktx:1.13.1")
                            api("com.squareup.retrofit2:retrofit:2.11.0")
                            testImplementation("junit:junit:4.13.2")
                        }
                        """.trimIndent(),
                    tailleOctets = 200,
                ),
            )
        val dependances = parseur.parser(scripts)
        assertEquals(3, dependances.size)
        // Triées par configuration puis nom.
        assertEquals("api", dependances[0].configuration)
        assertEquals("com.squareup.retrofit2", dependances[0].groupe)
        assertEquals("implementation", dependances[1].configuration)
        assertEquals("androidx.core", dependances[1].groupe)
        assertEquals("testImplementation", dependances[2].configuration)
        assertEquals("junit", dependances[2].groupe)
    }

    @Test
    fun `parse un projet frere`() {
        val scripts =
            listOf(
                ScriptDeBuild(
                    cheminRelatif = "build.gradle.kts",
                    contenu =
                        """
                        dependencies {
                            implementation(project(":core:domain"))
                        }
                        """.trimIndent(),
                    tailleOctets = 80,
                ),
            )
        val dependances = parseur.parser(scripts)
        assertEquals(1, dependances.size)
        val dep = dependances[0]
        assertEquals(TypeDependance.MODULE, dep.type)
        assertEquals(":core:domain", dep.coordonnes)
        assertEquals("implementation", dep.configuration)
    }

    @Test
    fun `ignore les lignes sans declaration`() {
        val scripts =
            listOf(
                ScriptDeBuild(
                    cheminRelatif = "build.gradle.kts",
                    contenu =
                        """
                        plugins {
                            kotlin("jvm")
                        }

                        repositories {
                            mavenCentral()
                        }
                        """.trimIndent(),
                    tailleOctets = 150,
                ),
            )
        // Aucune ligne `implementation(...)` ou similaire : aucune
        // dépendance extraite.
        val dependances = parseur.parser(scripts)
        assertTrue(dependances.isEmpty())
    }

    @Test
    fun `retourne vide pour un script sans dependances`() {
        val scripts =
            listOf(
                ScriptDeBuild(
                    cheminRelatif = "settings.gradle.kts",
                    contenu = """rootProject.name = "test"""",
                    tailleOctets = 30,
                ),
            )
        val dependances = parseur.parser(scripts)
        assertTrue(dependances.isEmpty())
    }

    @Test
    fun `parse depuis plusieurs scripts`() {
        val scripts =
            listOf(
                ScriptDeBuild(
                    cheminRelatif = "build.gradle.kts",
                    contenu = """implementation("a:b:1.0")""",
                    tailleOctets = 30,
                ),
                ScriptDeBuild(
                    cheminRelatif = "app/build.gradle.kts",
                    contenu = """implementation("c:d:2.0")""",
                    tailleOctets = 30,
                ),
            )
        val dependances = parseur.parser(scripts)
        assertEquals(2, dependances.size)
        assertEquals("a", dependances[0].groupe)
        assertEquals("c", dependances[1].groupe)
    }
}
