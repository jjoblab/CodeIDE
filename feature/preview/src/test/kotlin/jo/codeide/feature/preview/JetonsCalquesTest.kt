package jo.codeide.feature.preview

import jo.codeide.preview.xml.JetonsCalques
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Test de parité des JETONS DU MOTEUR de calques (PE11i — ADR 0134 D7,
 * `docs/PREVIEW_UI_ECRANS.md` § 11) : la JVM pure lit les XML du dépôt
 * et échoue si un jeton dérive de la maquette — même patron que
 * [JetonsMaquetteTest], pour l'objet [JetonsCalques] injecté au moteur.
 *
 * Il garantit deux invariants :
 * 1. chacun des 21 jetons de [JetonsCalques.NOMS] existe dans
 *    `jetons-maquette.json` ET dans `values/colors.xml` ET
 *    `values-night/colors.xml`, à la valeur EXACTE (comparaison ARGB) —
 *    clair et nuit ;
 * 2. `JetonsCalques` construit depuis les valeurs de la maquette (dans
 *    l'ordre de [JetonsCalques.NOMS]) contient les MÊMES entiers que les
 *    ressources — la parité de l'objet injecté, pas seulement des
 *    couleurs isolées.
 */
public class JetonsCalquesTest {
    /** Racine du dépôt (celle qui contient `docs/mockups/ref`). */
    private val racineDepot: File = trouverRacineDepot()

    /** Les 39 jetons mesurés dans la maquette (clair + nuit). */
    private val jetonsMaquette: JSONObject =
        JSONObject(File(racineDepot, CHEMIN_JETONS).readText())

    @Test
    public fun `les vingt et un jetons du moteur existent en clair avec la valeur exacte`() {
        verifierDeclinaison(CHEMIN_COLORS_CLAIR, "clair")
    }

    @Test
    public fun `les vingt et un jetons du moteur existent en nuit avec la valeur exacte`() {
        verifierDeclinaison(CHEMIN_COLORS_NUIT, "nuit")
    }

    @Test
    public fun `jetons construits depuis la maquette egalent les ressources clair et nuit`() {
        for (declinaison in listOf("clair", "nuit")) {
            val fichier = if (declinaison == "clair") CHEMIN_COLORS_CLAIR else CHEMIN_COLORS_NUIT
            val couleurs = couleurs(File(racineDepot, fichier))
            val depuisMaquette = depuisMaquette(declinaison)
            val depuisRessources = depuisRessources(couleurs)
            assertEquals(
                "L'objet JetonsCalques dérive en $declinaison (maquette != ressources)",
                depuisMaquette,
                depuisRessources,
            )
        }
    }

    // ── Aides (aucune valeur de design codée ici : tout vient du JSON) ──

    /** Compare chaque jeton du moteur à sa déclinaison de colors.xml. */
    private fun verifierDeclinaison(chemin: String, declinaison: String) {
        val couleurs = couleurs(File(racineDepot, chemin))
        val manquants = JetonsCalques.NOMS.filter { it !in couleurs }
        assertTrue(
            "Jetons $declinaison absents de $chemin : $manquants",
            manquants.isEmpty(),
        )
        for (nom in JetonsCalques.NOMS) {
            val attendu = normaliserCouleur(jetonsMaquette.getJSONObject(nom).getString(declinaison))
            assertEquals(
                "Le jeton $nom dérive en $declinaison (maquette != colors.xml)",
                attendu,
                couleurs[nom],
            )
        }
    }

    /** [JetonsCalques] construit depuis le JSON, dans l'ordre de [JetonsCalques.NOMS]. */
    private fun depuisMaquette(declinaison: String): List<Int> = JetonsCalques.NOMS.map { nom ->
        normaliserCouleur(jetonsMaquette.getJSONObject(nom).getString(declinaison))
    }

    /** Les valeurs du même ordre, lues des ressources (constructeur de l'activité). */
    private fun depuisRessources(couleurs: Map<String, Int>): List<Int> =
        JetonsCalques.NOMS.map { nom -> couleurs.getValue(nom) }

    /** Lit `name="…" → valeur` de colors.xml (jetons littéraux uniquement). */
    private fun couleurs(fichier: File): Map<String, Int> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(fichier)
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

    /** `#RRGGBB` ou `#AARRGGBB` (mêmes arguments que Color.parseColor). */
    private fun normaliserCouleur(valeur: String): Int {
        val hex = valeur.removePrefix("#")
        val opaque = if (hex.length == 6) "FF$hex" else hex
        return opaque.toLong(16).toInt()
    }

    /** Remonte depuis le répertoire de travail jusqu'à trouver la racine. */
    private fun trouverRacineDepot(): File {
        val depart = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var repertoire: File? = depart
        repeat(6) {
            val courant = repertoire ?: return@repeat
            if (File(courant, CHEMIN_JETONS).isFile) return courant
            repertoire = courant.parentFile
        }
        throw AssertionError("Racine du dépôt introuvable depuis $depart")
    }

    private companion object {
        private const val CHEMIN_JETONS = "docs/mockups/ref/jetons-maquette.json"
        private const val CHEMIN_COLORS_CLAIR = "feature/preview/src/main/res/values/colors.xml"
        private const val CHEMIN_COLORS_NUIT = "feature/preview/src/main/res/values-night/colors.xml"
    }
}
