package jo.codeide.feature.preview

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Test de parité des JETONS de l'aperçu (PE11a — `docs/PREVIEW_UI.md` § 12.1,
 * ADR 0131) : la JVM pure lit les XML du dépôt et échoue si un jeton dérive
 * de la maquette. Aucun Android, aucun Robolectric — `javax.xml` seulement.
 *
 * Il garantit trois invariants :
 * 1. chaque jeton de `docs/mockups/ref/jetons-maquette.json` existe dans
 *    `values/colors.xml` ET `values-night/colors.xml`, à la valeur EXACTE
 *    (comparaison ARGB) — clair et nuit ;
 * 2. chaque attribut de thème `apercu*` déclaré dans `values/attrs.xml` est
 *    câblé dans `values/themes.xml` (sinon un layout lit un attribut sans
 *    valeur et l'écran dérive) ;
 * 3. les couleurs de marque de `:core:ui` (clair ET nuit) restent égales aux
 *    jetons primaires/erreur de la maquette — l'aperçu partage la marque.
 */
public class JetonsMaquetteTest {
    /** Racine du dépôt (celle qui contient `docs/mockups/ref`). */
    private val racineDepot: File = trouverRacineDepot()

    /** Les 39 jetons mesurés dans la maquette (clair + nuit). */
    private val jetonsMaquette: JSONObject =
        JSONObject(File(racineDepot, "docs/mockups/ref/jetons-maquette.json").readText())

    @Test
    public fun `tous les jetons de la maquette existent en clair avec la valeur exacte`() {
        verifierDeclinaison(
            fichier = File(racineDepot, CHEMIN_COLORS_CLAIR),
            declinaison = "clair",
        )
    }

    @Test
    public fun `tous les jetons de la maquette existent en nuit avec la valeur exacte`() {
        verifierDeclinaison(
            fichier = File(racineDepot, CHEMIN_COLORS_NUIT),
            declinaison = "nuit",
        )
    }

    @Test
    public fun `chaque attribut apercu de attrs est cable dans themes`() {
        val attrs = nomsAttrs(File(racineDepot, CHEMIN_ATTRS))
        val themes = nomsItemsStyle(File(racineDepot, CHEMIN_THEMES), "Theme.CodeIDE.Apercu")
        val manquants = attrs.filter { it !in themes }
        assertTrue(
            "Attributs de thème NON câblés dans Theme.CodeIDE.Apercu : $manquants",
            manquants.isEmpty(),
        )
    }

    @Test
    public fun `les couleurs de marque de core ui restent egales aux jetons de la maquette`() {
        // Point 4 de PREVIEW_UI.md § 12.1 : primaires/erreur identiques
        // clair ET nuit (l'aperçu partage la marque de l'application).
        val paires =
            listOf(
                "codeide_primary" to "apercu_primaire",
                "codeide_on_primary" to "apercu_sur_primaire",
                "codeide_primary_container" to "apercu_primaire_conteneur",
                "codeide_error" to "apercu_erreur",
            )
        val noyauClair = couleurs(File(racineDepot, CHEMIN_CORE_UI_CLAIR))
        val noyauNuit = couleurs(File(racineDepot, CHEMIN_CORE_UI_NUIT))
        for (declinaison in listOf("clair", "nuit")) {
            val noyau = if (declinaison == "clair") noyauClair else noyauNuit
            for ((nomNoyau, nomJeton) in paires) {
                val jeton = jetonsMaquette.getJSONObject(nomJeton).getString(declinaison)
                assertEquals(
                    "La marque $declinaison dérive : $nomNoyau != $nomJeton",
                    normaliserCouleur(jeton),
                    noyau[nomNoyau],
                )
            }
        }
    }

    // ── Aides (aucune valeur de design codée ici : tout vient du JSON) ──

    /** Compare chaque jeton du JSON à sa déclinaison de colors.xml. */
    private fun verifierDeclinaison(fichier: File, declinaison: String) {
        val couleurs = couleurs(fichier)
        val jetons = jetonsMaquette.keys().asSequence().toList()
        val manquants = jetons.filter { it !in couleurs }
        assertTrue(
            "Jetons $declinaison absents de ${fichier.path} : $manquants",
            manquants.isEmpty(),
        )
        for (nom in jetons) {
            val attendu = normaliserCouleur(jetonsMaquette.getJSONObject(nom).getString(declinaison))
            assertEquals(
                "Le jeton $nom dérive en $declinaison (maquette != colors.xml)",
                attendu,
                couleurs[nom],
            )
        }
    }

    /**
     * Lit `name="…" → valeur` de colors.xml — les alias (`@color/…`) sont
     * résolus SANS suivre la référence (le test de parité ne concerne que
     * les jetons du JSON, tous littéraux).
     */
    private fun couleurs(fichier: File): Map<String, Int> {
        val document = document(fichier)
        val noeuds = document.getElementsByTagName("color")
        val resultat = mutableMapOf<String, Int>()
        for (indice in 0 until noeuds.length) {
            val element = noeuds.item(indice) as Element
            val nom = element.getAttribute("name")
            val valeur = element.textContent.trim()
            if (valeur.startsWith("@color/")) continue
            resultat[nom] = normaliserCouleur(valeur)
        }
        return resultat
    }

    /** Les noms d'attributs `apercu*` déclarés dans attrs.xml. */
    private fun nomsAttrs(fichier: File): List<String> {
        val document = document(fichier)
        val noeuds = document.getElementsByTagName("attr")
        return (0 until noeuds.length)
            .map { (noeuds.item(it) as Element).getAttribute("name") }
            .filter { it.startsWith("apercu") }
    }

    /** Les items `name="…"` d'un style donné dans themes.xml. */
    private fun nomsItemsStyle(fichier: File, nomStyle: String): Set<String> {
        val document = document(fichier)
        val noeuds = document.getElementsByTagName("style")
        for (indice in 0 until noeuds.length) {
            val style = noeuds.item(indice) as Element
            if (style.getAttribute("name") == nomStyle) {
                val items = style.getElementsByTagName("item")
                return (0 until items.length)
                    .map { (items.item(it) as Element).getAttribute("name") }
                    .toSet()
            }
        }
        throw AssertionError("Style « $nomStyle » introuvable dans ${fichier.path}")
    }

    /** `#RRGGBB` ou `#AARRGGBB` (mêmes arguments que Color.parseColor). */
    private fun normaliserCouleur(valeur: String): Int {
        val hex = valeur.removePrefix("#")
        val opaque = if (hex.length == 6) "FF$hex" else hex
        return opaque.toLong(16).toInt()
    }

    private fun document(fichier: File) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(fichier)

    /** Remonte depuis le répertoire de travail jusqu'à trouver la racine. */
    private fun trouverRacineDepot(): File {
        val depart = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var repertoire: File? = depart
        repeat(6) {
            val courant = repertoire ?: return@repeat
            if (File(courant, "docs/mockups/ref/jetons-maquette.json").isFile) return courant
            repertoire = courant.parentFile
        }
        throw AssertionError("Racine du dépôt introuvable depuis $depart")
    }

    private companion object {
        private const val CHEMIN_COLORS_CLAIR = "feature/preview/src/main/res/values/colors.xml"
        private const val CHEMIN_COLORS_NUIT = "feature/preview/src/main/res/values-night/colors.xml"
        private const val CHEMIN_ATTRS = "feature/preview/src/main/res/values/attrs.xml"
        private const val CHEMIN_THEMES = "feature/preview/src/main/res/values/themes.xml"
        private const val CHEMIN_CORE_UI_CLAIR = "core/ui/src/main/res/values/colors.xml"
        private const val CHEMIN_CORE_UI_NUIT = "core/ui/src/main/res/values-night/colors.xml"
    }
}
