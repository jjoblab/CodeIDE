package jo.codeide.preview.xml

import androidx.test.core.app.ApplicationProvider

/**
 * Fixture PARTAGÉE des tests de la [VuePreviewXml] — le découpage detekt
 * LargeClass (zéro exemption, règle du projet) a séparé
 * [VuePreviewXmlRedimensionnementTest] de [VuePreviewXmlTest] : les DEUX
 * consomment la MÊME vue prête à dessiner et le MÊME arbre de rendu.
 *
 * PE11i : le moteur ne dessine AUCUNE couleur sans jetons injectés
 * (ADR 0134 D7) — la fixture pose un jeu ARBITRAIRE mais complet
 * (valeurs DISTINCTES ; la parité maquette ↔ ressources vit dans
 * `JetonsCalquesTest` côté feature, jamais ici).
 */
internal fun vueDeTest(): VuePreviewXml = VuePreviewXml(ApplicationProvider.getApplicationContext()).apply {
    appliquerJetons(jetonsDeTest)
}

/** Un jeu de jetons arbitraire mais complet (21 champs). */
internal val jetonsDeTest =
    JetonsCalques(
        surface2 = 0xFFEEEEF7.toInt(),
        damierCase = 0x21808096,
        accent = 0xFF1E6EC8.toInt(),
        accentDoux = 0x241E6EC8,
        surface = 0xFFFFFFFF.toInt(),
        texte = 0xFF1A1B21.toInt(),
        fond = 0xFFF9F9FF.toInt(),
        texteDoux = 0xFF5B5C66.toInt(),
        marge = 0xFFD9822B.toInt(),
        margeFond = 0x61D9822B,
        remplissage = 0xFF2E8B4B.toInt(),
        remplissageFond = 0x612E8B4B,
        bornes = 0x801E6EC8.toInt(),
        nouveau = 0xFF7C3AED.toInt(),
        nouveauDoux = 0x247C3AED,
        surNouveau = 0xFFFFFFFF.toInt(),
        epingleAvert = 0xFFB8860B.toInt(),
        epingleErreur = 0xFFBA1A1A.toInt(),
        surCalque = 0xFFFFFFFF.toInt(),
        ecranTrait = 0x2E000000.toInt(),
        ecranOmbre = 0x38000000.toInt(),
    )

/** L'arbre de rendu de test (racine + texte + bouton — PE6a, hit-test). */
internal fun arbreVuesDeTest(): List<InfoVueRendu> = listOf(
    InfoVueRendu(0, 0, 100, 200, "FrameLayout", 0, 40, -1, 0),
    InfoVueRendu(10, 10, 90, 60, "TextView @+id/titre", 8, 22, 0, 1),
    InfoVueRendu(10, 80, 90, 120, "Button @+id/envoyer", 24, 38, 0, 1),
)
