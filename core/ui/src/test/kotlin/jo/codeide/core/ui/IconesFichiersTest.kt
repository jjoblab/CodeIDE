package jo.codeide.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests du mapping nom → icône de l'explorateur (étape 14, élargie à
 * l'étape 31 par la spécification `docs/EXPLORATEUR_V2.md` § 8, puis C0
 * v0.63.0 : icônes officielles Android Studio / IntelliJ) : les
 * extensions au moins exigées par la spécification (Kotlin, Java, Gradle,
 * XML, Markdown, JSON), la marque script des noms sans extension, la
 * marque git des fichiers cachés, le repli texte des extensions inconnues,
 * les dossiers selon leur contexte, l'insensibilité à la casse, et les
 * noms spécifiques Android Studio (build.gradle.kts vs *.kts,
 * AndroidManifest.xml vs *.xml, ProGuard).
 */
class IconesFichiersTest {
    @Test
    fun `les extensions exigees ont leur icone dediee`() {
        assertEquals(R.drawable.ic_fichier_kotlin, IconesFichiers.pourNom("Main.kt"))
        assertEquals(R.drawable.ic_fichier_java, IconesFichiers.pourNom("Greeter.java"))
        assertEquals(R.drawable.ic_fichier_xml, IconesFichiers.pourNom("layout.xml"))
        assertEquals(R.drawable.ic_fichier_markdown, IconesFichiers.pourNom("README.md"))
        assertEquals(R.drawable.ic_fichier_json, IconesFichiers.pourNom("project.json"))
    }

    @Test
    fun `C0 - gradle kts vs gradle groovy vs kts brut`() {
        // *.gradle.kts → ic_fichier_gradle_kts (kotlinGradleScript IntelliJ)
        assertEquals(R.drawable.ic_fichier_gradle_kts, IconesFichiers.pourNom("build.gradle.kts"))
        assertEquals(R.drawable.ic_fichier_gradle_kts, IconesFichiers.pourNom("settings.gradle.kts"))
        // *.gradle (Groovy) → ic_fichier_gradle (fileTypes/gradle IntelliJ)
        assertEquals(R.drawable.ic_fichier_gradle, IconesFichiers.pourNom("build.gradle"))
        // *.kts brut (hors gradle) → ic_fichier_gradle_kts aussi (script Kotlin)
        assertEquals(R.drawable.ic_fichier_gradle_kts, IconesFichiers.pourNom("init.gradle.kts"))
    }

    @Test
    fun `C0 - AndroidManifest xml a son icone dediee`() {
        assertEquals(R.drawable.ic_fichier_manifest, IconesFichiers.pourNom("AndroidManifest.xml"))
        assertEquals(R.drawable.ic_fichier_manifest, IconesFichiers.pourNom("androidmanifest.xml"))
        // Un autre fichier XML garde l'icône xml générique.
        assertEquals(R.drawable.ic_fichier_xml, IconesFichiers.pourNom("layout.xml"))
    }

    @Test
    fun `C0 - proguard et fichiers pro portent l icone config`() {
        assertEquals(R.drawable.ic_fichier_config, IconesFichiers.pourNom("proguard-rules.pro"))
        assertEquals(R.drawable.ic_fichier_config, IconesFichiers.pourNom("proguard.pro"))
        assertEquals(R.drawable.ic_fichier_config, IconesFichiers.pourNom("ProGuard.pro"))
    }

    @Test
    fun `la casse et le chemin complet ne changent pas l icone`() {
        assertEquals(R.drawable.ic_fichier_kotlin, IconesFichiers.pourNom("SRC/MAIN/KT/UTIL.KT"))
        assertEquals(R.drawable.ic_fichier_markdown, IconesFichiers.pourNom("docs/GUIDE.MD"))
    }

    @Test
    fun `les noms sans extension portent la marque script`() {
        assertEquals(R.drawable.ic_fichier_script, IconesFichiers.pourNom("gradlew"))
        assertEquals(R.drawable.ic_fichier_script, IconesFichiers.pourNom("LICENSE"))
        assertEquals(R.drawable.ic_fichier_script, IconesFichiers.pourNom("sansnom"))
    }

    @Test
    fun `les noms caches portent la marque git`() {
        assertEquals(R.drawable.ic_fichier_git, IconesFichiers.pourNom(".gitignore"))
        assertEquals(R.drawable.ic_fichier_git, IconesFichiers.pourNom(".gitattributes"))
    }

    @Test
    fun `une extension inconnue reple sur le fichier texte`() {
        assertEquals(R.drawable.ic_fichier_texte, IconesFichiers.pourNom("notes.zorglub"))
    }

    // v0.80.2 : parité avec les fileTypes d'IntelliJ New UI — archives,
    // images, scripts web, données et polices (retour utilisateur :
    // « ajoute d'autres icônes pour d'autres types de fichiers comme zip
    // »). Le jar et l'apk sont des zip : IntelliJ leur donne l'icône
    // archive.
    @Test
    fun `v0_80_2 - les archives ont leur icone dediee`() {
        assertEquals(R.drawable.ic_fichier_archive, IconesFichiers.pourNom("sources.zip"))
        assertEquals(R.drawable.ic_fichier_archive, IconesFichiers.pourNom("bibliotheque.jar"))
        assertEquals(R.drawable.ic_fichier_archive, IconesFichiers.pourNom("app.apk"))
        assertEquals(R.drawable.ic_fichier_archive, IconesFichiers.pourNom("paquet.7z"))
        assertEquals(R.drawable.ic_fichier_archive, IconesFichiers.pourNom("archive.tar.gz"))
        assertEquals(R.drawable.ic_fichier_archive, IconesFichiers.pourNom("bundle.tgz"))
    }

    @Test
    fun `v0_80_2 - les images les scripts web et les donnees ont leur icone`() {
        assertEquals(R.drawable.ic_fichier_image, IconesFichiers.pourNom("photo.png"))
        assertEquals(R.drawable.ic_fichier_image, IconesFichiers.pourNom("icone.svg"))
        assertEquals(R.drawable.ic_fichier_html, IconesFichiers.pourNom("index.html"))
        assertEquals(R.drawable.ic_fichier_css, IconesFichiers.pourNom("styles.scss"))
        assertEquals(R.drawable.ic_fichier_js, IconesFichiers.pourNom("script.mjs"))
        assertEquals(R.drawable.ic_fichier_yaml, IconesFichiers.pourNom("pipeline.yml"))
        assertEquals(R.drawable.ic_fichier_shell, IconesFichiers.pourNom("script.sh"))
        assertEquals(R.drawable.ic_fichier_sql, IconesFichiers.pourNom("requetes.sql"))
        assertEquals(R.drawable.ic_fichier_csv, IconesFichiers.pourNom("table.csv"))
    }

    @Test
    fun `v0_80_2 - polices et binaires ont leur icone`() {
        assertEquals(R.drawable.ic_fichier_police, IconesFichiers.pourNom("titre.ttf"))
        assertEquals(R.drawable.ic_fichier_police, IconesFichiers.pourNom("variable.woff2"))
        assertEquals(R.drawable.ic_fichier_binaire, IconesFichiers.pourNom("native.so"))
        assertEquals(R.drawable.ic_fichier_binaire, IconesFichiers.pourNom("classes.dex"))
    }

    @Test
    fun `les dossiers ont leur icone selon le contexte`() {
        assertEquals(R.drawable.ic_dossier, IconesFichiers.pourDossier())
        assertEquals(R.drawable.ic_dossier_prive, IconesFichiers.pourDossier(prive = true))
    }
}
