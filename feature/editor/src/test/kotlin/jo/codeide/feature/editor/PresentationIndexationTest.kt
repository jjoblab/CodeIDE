package jo.codeide.feature.editor

import jo.codeide.lsp.host.BilanDernierPassageIndexation
import jo.codeide.lsp.host.BreakdownIndexeurIndexation
import jo.codeide.lsp.host.EntreeTravailIndexation
import jo.codeide.lsp.host.EtatEntreeTravailIndexation
import jo.codeide.lsp.host.EtatIndexation
import jo.codeide.lsp.host.PhaseIndexation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests purs du présentateur [PresentationIndexation] (Phase B prompt 2,
 * ADR 0117 §4.4 — lot B4 ; v0.178.0, ADR 0145 : MULTI-MOTEURS ; v0.180.0,
 * ADR 0146 : états RICHES des trois langues).
 * Valide l'agrégat Java + Kotlin + XML : pastille (pire phase), panneau
 * de détail TOUTES sections (en-tête + pourcentage SOMMÉ, progression
 * construction seule, reprises SOMMÉES, moteurs avec compteurs riches
 * ou dégradation honnête des packs anciens, écartées/bloquantes
 * FUSIONNÉES, bilan le plus récent, histogramme des coûts, actions —
 * « Annuler » ENFIN émis en construction (D5 livrée)). Convention : noms
 * en français entre accents graves (ADR 0007).
 */
class PresentationIndexationTest {
    // ============================================================
    // Agrégat [EtatsMoteursIndexation] — pire phase + état Java
    // ============================================================

    @Test
    fun `agregat vide — pire phase INITIAL`() {
        assertEquals(PhaseIndexation.INITIAL, EtatsMoteursIndexation().pirePhase)
    }

    @Test
    fun `agregat — la pire phase des moteurs present l emporte`() {
        // PRET (1) < CONSTRUCTION (2) < PARTIEL (3) < ERREUR (4).
        val pretPlusConstruction =
            EtatsMoteursIndexation(
                listOf(
                    MoteurIndexationPresent(MoteurIndexation.JAVA, PhaseIndexation.PRET, null),
                    MoteurIndexationPresent(MoteurIndexation.KOTLIN, PhaseIndexation.CONSTRUCTION, null),
                ),
            )
        assertEquals(PhaseIndexation.CONSTRUCTION, pretPlusConstruction.pirePhase)

        val pretPlusPartiel =
            EtatsMoteursIndexation(
                listOf(
                    MoteurIndexationPresent(MoteurIndexation.JAVA, PhaseIndexation.PARTIEL, null),
                    MoteurIndexationPresent(MoteurIndexation.XML, PhaseIndexation.PRET, null),
                ),
            )
        assertEquals(PhaseIndexation.PARTIEL, pretPlusPartiel.pirePhase)

        val constructionPlusErreur =
            EtatsMoteursIndexation(
                listOf(
                    MoteurIndexationPresent(MoteurIndexation.JAVA, PhaseIndexation.CONSTRUCTION, null),
                    MoteurIndexationPresent(MoteurIndexation.KOTLIN, PhaseIndexation.ERREUR, null),
                ),
            )
        assertEquals(PhaseIndexation.ERREUR, constructionPlusErreur.pirePhase)
    }

    @Test
    fun `agregat — etat riche Java expose seulement pour Java`() {
        val riche = etatRiche(PhaseIndexation.PRET)
        val avec =
            EtatsMoteursIndexation(
                listOf(
                    MoteurIndexationPresent(MoteurIndexation.JAVA, PhaseIndexation.PRET, riche),
                    MoteurIndexationPresent(MoteurIndexation.KOTLIN, PhaseIndexation.PRET, null),
                ),
            )
        assertEquals(riche, avec.etatJava)

        val sansJava =
            EtatsMoteursIndexation(
                listOf(MoteurIndexationPresent(MoteurIndexation.KOTLIN, PhaseIndexation.PRET, null)),
            )
        assertNull(sansJava.etatJava)
    }

    // ============================================================
    // Agrégat VIDE (aucun moteur vivant)
    // ============================================================

    @Test
    fun `agregat vide — pastille masquee etroit, panneau vide`() {
        val modele = PresentationIndexation.afficher(EtatsMoteursIndexation())
        assertEquals(EtatPastilleIndexation.INITIAL, modele.pastille.etat)
        assertNull(modele.pastille.pourcentage)
        assertTrue(modele.pastille.masqueeEtroit)
        assertNull(modele.panneau.listeTravail)
        assertNull(modele.panneau.moteurs)
        assertNull(modele.panneau.bilan)
        assertTrue(modele.panneau.actions.isEmpty())
        assertNull(modele.panneau.noteImpact)
        assertNull(modele.panneau.resumeReprises)
        assertTrue(modele.panneau.entreesEcartees.isEmpty())
        assertTrue(modele.panneau.erreursBloquantes.isEmpty())
        assertTrue(modele.panneau.couts.isEmpty())
    }

    // ============================================================
    // CONSTRUCTION — moteur Java riche
    // ============================================================

    @Test
    fun `construction Java riche — pastille avec pourcentage et reprises`() {
        val modele =
            PresentationIndexation.afficher(
                agregatJava(
                    etatRiche(
                        PhaseIndexation.CONSTRUCTION,
                        faits = 119,
                        total = 191,
                        reprises = 96,
                    ),
                ),
            )
        assertEquals(EtatPastilleIndexation.CONSTRUCTION, modele.pastille.etat)
        // 119/191 = 62,3 % → 62.
        assertEquals(62, modele.pastille.pourcentage)
        assertFalse(modele.pastille.masqueeEtroit)
        assertEquals(62, modele.panneau.entete.pourcentageDroite)
        assertNotNull(modele.panneau.resumeReprises)
    }

    @Test
    fun `construction Java riche — decomptes de reprises en entiers (regression rapport 032311ee)`() {
        val modele =
            PresentationIndexation.afficher(
                agregatJava(
                    etatRiche(
                        PhaseIndexation.CONSTRUCTION,
                        faits = 119,
                        total = 191,
                        reprises = 96,
                    ),
                ),
            )
        // editor_indexation_reprises = « … reprises %1$d, reconstruites
        // %2$d. » — v0.178.1 interpolait les décomptes en String et
        // Resources.getString levait IllegalFormatConversionException
        // (rapport 032311ee, plantage à l'ouverture du panneau) : les
        // arguments doivent rester des Int.
        assertEquals(
            TexteIndexation.Ressource(R.string.editor_indexation_reprises, listOf(96, 23)),
            modele.panneau.resumeReprises,
        )
    }

    @Test
    fun `construction Java riche — progression determinee visible avec compteur`() {
        val modele =
            PresentationIndexation.afficher(
                agregatJava(
                    etatRiche(PhaseIndexation.CONSTRUCTION, faits = 118, total = 191),
                ),
            )
        assertTrue(modele.panneau.progression.visible)
        assertFalse(modele.panneau.progression.indeterminee)
        assertEquals(118f / 191f, modele.panneau.progression.fraction, 0.001f)
        assertNotNull(modele.panneau.progression.compteur)
    }

    @Test
    fun `construction Java total inconnu — barre indeterminee, pas de pourcentage`() {
        val modele =
            PresentationIndexation.afficher(
                agregatJava(etatRiche(PhaseIndexation.CONSTRUCTION, faits = 0, total = 0)),
            )
        assertEquals(EtatPastilleIndexation.CONSTRUCTION, modele.pastille.etat)
        assertNull(modele.pastille.pourcentage)
        assertNull(modele.panneau.entete.pourcentageDroite)
        assertTrue(modele.panneau.progression.visible)
        assertTrue(modele.panneau.progression.indeterminee)
        assertNull(modele.panneau.progression.compteur)
    }

    @Test
    fun `construction — note info, action ANNULER enfin emise (DEMANDES D5 livree)`() {
        val modele =
            PresentationIndexation.afficher(
                agregatJava(
                    etatRiche(PhaseIndexation.CONSTRUCTION, faits = 0, total = 100),
                ),
            )
        assertNotNull(modele.panneau.noteImpact)
        assertEquals(NiveauNoteImpact.INFO, modele.panneau.noteImpact!!.niveau)
        // La maquette « Annuler le passage » revit : le point d'annulation
        // COOPÉRATIF est livré côté frère v1.8.0 (docs/DEMANDES-
        // _CODEIDE_LSP.md D5, ADR 0214) — plus de bouton mort.
        assertEquals(listOf(ActionDetailIndexation.ANNULER), modele.panneau.actions)
    }

    @Test
    fun `construction avec liste de travail Java — entrees traduites`() {
        val etat =
            etatRiche(PhaseIndexation.CONSTRUCTION, faits = 2, total = 3).copy(
                listeTravail =
                listOf(
                    EntreeTravailIndexation("androidx.core 1.19.0", EtatEntreeTravailIndexation.REPRIS),
                    EntreeTravailIndexation("material 1.14.0", EtatEntreeTravailIndexation.ACTIF),
                    EntreeTravailIndexation("okio 3.9.0", EtatEntreeTravailIndexation.EN_ATTENTE),
                ),
            )
        val modele = PresentationIndexation.afficher(agregatJava(etat))
        assertNotNull(modele.panneau.listeTravail)
        assertEquals(3, modele.panneau.listeTravail!!.size)
        assertEquals(EtatEntreeDetail.REPRIS, modele.panneau.listeTravail!![0].etat)
        assertEquals(EtatEntreeDetail.ACTIF, modele.panneau.listeTravail!![1].etat)
        assertEquals(EtatEntreeDetail.EN_ATTENTE, modele.panneau.listeTravail!![2].etat)
    }

    // ============================================================
    // CONSTRUCTION — moteurs DIVERGENTS (multi-moteurs)
    // ============================================================

    @Test
    fun `construction divergente — trois rangees moteurs, degradees honnetement`() {
        val etats =
            EtatsMoteursIndexation(
                listOf(
                    MoteurIndexationPresent(
                        MoteurIndexation.JAVA,
                        PhaseIndexation.CONSTRUCTION,
                        etatRiche(PhaseIndexation.CONSTRUCTION, faits = 118, total = 191),
                    ),
                    MoteurIndexationPresent(MoteurIndexation.KOTLIN, PhaseIndexation.PRET, null),
                    MoteurIndexationPresent(MoteurIndexation.XML, PhaseIndexation.INITIAL, null),
                ),
            )
        val modele = PresentationIndexation.afficher(etats)
        // Java construit → la pastille globale est CONSTRUCTION.
        assertEquals(EtatPastilleIndexation.CONSTRUCTION, modele.pastille.etat)
        val moteurs = modele.panneau.moteurs!!
        assertEquals(3, moteurs.size)
        assertEquals(MoteurIndexation.JAVA.nomRes, moteurs[0].nom)
        assertEquals(EtatMoteurDetail.CONSTRUCTION, moteurs[0].etat)
        assertEquals(MoteurIndexation.KOTLIN.nomRes, moteurs[1].nom)
        assertEquals(EtatMoteurDetail.PRET, moteurs[1].etat)
        assertEquals(MoteurIndexation.XML.nomRes, moteurs[2].nom)
        assertEquals(EtatMoteurDetail.INITIAL, moteurs[2].etat)
    }

    @Test
    fun `moteur synthetise SEUL en construction — indetermine, sans compteurs Java`() {
        val etats =
            EtatsMoteursIndexation(
                listOf(MoteurIndexationPresent(MoteurIndexation.KOTLIN, PhaseIndexation.CONSTRUCTION, null)),
            )
        val modele = PresentationIndexation.afficher(etats)
        assertEquals(EtatPastilleIndexation.CONSTRUCTION, modele.pastille.etat)
        assertNull(modele.pastille.pourcentage)
        // Barre indéterminée (aucun compteur publié — pack ancien).
        assertTrue(modele.panneau.progression.visible)
        assertTrue(modele.panneau.progression.indeterminee)
        // Une rangée UNIQUE (plus de Java codé en dur).
        val moteurs = modele.panneau.moteurs!!
        assertEquals(1, moteurs.size)
        assertEquals(MoteurIndexation.KOTLIN.nomRes, moteurs[0].nom)
        assertEquals(EtatMoteurDetail.CONSTRUCTION, moteurs[0].etat)
        // Pas d'état riche : pas de liste de travail ni de reprises.
        assertNull(modele.panneau.listeTravail)
        assertNull(modele.panneau.resumeReprises)
        assertNotNull(modele.panneau.noteImpact)
        // L'annulation reste OFFERTE (D5) — le pack ancien répondra
        // `false` (rien en vol), l'offre est défensive.
        assertEquals(listOf(ActionDetailIndexation.ANNULER), modele.panneau.actions)
    }

    // ============================================================
    // CONSTRUCTION — moteurs RICHES Kotlin/XML (D3 livrée, v0.180.0)
    // ============================================================

    @Test
    fun `construction Kotlin riche — compteurs, reprises, liste et action annuler`() {
        val etatKotlin =
            etatRiche(PhaseIndexation.CONSTRUCTION, faits = 30, total = 47, reprises = 20)
                .copy(
                    listeTravail =
                    listOf(
                        EntreeTravailIndexation("kotlin-stdlib-2.4.0.kidx", EtatEntreeTravailIndexation.REPRIS),
                        EntreeTravailIndexation("material-1.14.0.aar", EtatEntreeTravailIndexation.ACTIF),
                    ),
                )
        val modele =
            PresentationIndexation.afficher(
                EtatsMoteursIndexation(
                    listOf(
                        MoteurIndexationPresent(MoteurIndexation.KOTLIN, PhaseIndexation.CONSTRUCTION, etatKotlin),
                    ),
                ),
            )
        // 30/47 = 63,8 % → 63 — le moteur Kotlin publie ENFIN ses compteurs.
        assertEquals(63, modele.pastille.pourcentage)
        assertFalse(modele.panneau.progression.indeterminee)
        assertEquals(30f / 47f, modele.panneau.progression.fraction, 0.001f)
        assertNotNull(modele.panneau.listeTravail)
        assertEquals(2, modele.panneau.listeTravail!!.size)
        assertEquals(
            TexteIndexation.Ressource(R.string.editor_indexation_reprises, listOf(20, 10)),
            modele.panneau.resumeReprises,
        )
        assertEquals(listOf(ActionDetailIndexation.ANNULER), modele.panneau.actions)
        // La rangée moteur porte le COMPTEUR riche (plus le verbe
        // synthétique « En construction » réservé aux packs anciens).
        val moteurs = modele.panneau.moteurs!!
        assertEquals(EtatMoteurDetail.CONSTRUCTION, moteurs[0].etat)
    }

    @Test
    fun `construction multi-moteurs riche — compteurs SOMMES toutes langues`() {
        val java = etatRiche(PhaseIndexation.CONSTRUCTION, faits = 118, total = 191, reprises = 96)
        val kotlin = etatRiche(PhaseIndexation.CONSTRUCTION, faits = 2, total = 9, reprises = 1)
        // XML en fenêtre seule (pack ancien) : total inconnu — il
        // n'entre PAS dans la somme, sans casser la barre déterminée.
        val xml = etatRiche(PhaseIndexation.CONSTRUCTION, faits = 0, total = 0)
        val modele =
            PresentationIndexation.afficher(
                EtatsMoteursIndexation(
                    listOf(
                        MoteurIndexationPresent(MoteurIndexation.JAVA, PhaseIndexation.CONSTRUCTION, java),
                        MoteurIndexationPresent(MoteurIndexation.KOTLIN, PhaseIndexation.CONSTRUCTION, kotlin),
                        MoteurIndexationPresent(MoteurIndexation.XML, PhaseIndexation.CONSTRUCTION, xml),
                    ),
                ),
            )
        // (118 + 2) / (191 + 9) = 120/200 = 60 %.
        assertEquals(60, modele.pastille.pourcentage)
        assertEquals(60, modele.panneau.entete.pourcentageDroite)
        assertEquals(120f / 200f, modele.panneau.progression.fraction, 0.001f)
        // Reprises sommées : 96 + 1 = 97 reprises, (120 − 97) reconstruites.
        assertEquals(
            TexteIndexation.Ressource(R.string.editor_indexation_reprises, listOf(97, 23)),
            modele.panneau.resumeReprises,
        )
    }

    // ============================================================
    // PARTIEL Kotlin (D3 livrée — les écarts hors Java ENFIN visibles)
    // ============================================================

    @Test
    fun `partiel Kotlin avec avertissements — ecarts visibles, reessayer echecs offre`() {
        val etat =
            etatRiche(PhaseIndexation.PARTIEL, faits = 45, total = 47)
                .copy(avertissements = listOf("kotlinx-serialization 1.7.3 : segment kidx corrompu"))
        val modele =
            PresentationIndexation.afficher(
                EtatsMoteursIndexation(
                    listOf(MoteurIndexationPresent(MoteurIndexation.KOTLIN, PhaseIndexation.PARTIEL, etat)),
                ),
            )
        assertEquals(EtatPastilleIndexation.PARTIEL, modele.pastille.etat)
        assertEquals(1, modele.panneau.entreesEcartees.size)
        assertEquals(
            listOf(ActionDetailIndexation.REESSAYER_ECHECS, ActionDetailIndexation.REINDEXER),
            modele.panneau.actions,
        )
        assertEquals(NiveauNoteImpact.AVERTISSEMENT, modele.panneau.noteImpact!!.niveau)
        // La rangée moteur porte ENFIN « Partiel » (v0.178.0 : un moteur
        // synthétisé ne savait JAMAIS être partiel).
        assertEquals(EtatMoteurDetail.PARTIEL, modele.panneau.moteurs!![0].etat)
    }

    // ============================================================
    // PRET — tous les moteurs
    // ============================================================

    @Test
    fun `pret global — pastille coche, action reindexer, barre masquee`() {
        val modele =
            PresentationIndexation.afficher(
                EtatsMoteursIndexation(
                    listOf(
                        MoteurIndexationPresent(
                            MoteurIndexation.JAVA,
                            PhaseIndexation.PRET,
                            etatRiche(PhaseIndexation.PRET),
                        ),
                        MoteurIndexationPresent(MoteurIndexation.KOTLIN, PhaseIndexation.PRET, null),
                        MoteurIndexationPresent(MoteurIndexation.XML, PhaseIndexation.PRET, null),
                    ),
                ),
            )
        assertEquals(EtatPastilleIndexation.PRET, modele.pastille.etat)
        assertNull(modele.pastille.pourcentage)
        assertTrue(modele.pastille.masqueeEtroit)
        assertEquals(listOf(ActionDetailIndexation.REINDEXER), modele.panneau.actions)
        // Maquette : pas de barre au repos.
        assertFalse(modele.panneau.progression.visible)
        assertNull(modele.panneau.entete.pourcentageDroite)
        assertNull(modele.panneau.noteImpact)
        assertNull(modele.panneau.resumeReprises)
    }

    @Test
    fun `pret avec bilan — panneau affiche le bilan et l histogramme des couts`() {
        val bilan =
            BilanDernierPassageIndexation(
                dureeMs = 4_200,
                artefactsTotal = 191,
                artefactsRepris = 188,
                artefactsReconstruits = 3,
                fichiersParcourus = 214,
                fichiersReanalyses = 12,
                indexeurPlusCouteux = "noms-classes-java",
                horodatageMs = 1_726_000_000_000,
            )
        val breakdown =
            listOf(
                BreakdownIndexeurIndexation("ressources", "Ressources Android", 600, 8),
                BreakdownIndexeurIndexation("noms", "Noms de classes Java", 1_800, 120),
                BreakdownIndexeurIndexation("appelables", "Appelables Kotlin", 1_100, 71),
            )
        val etat =
            etatRiche(PhaseIndexation.PRET, faits = 191, total = 191)
                .copy(bilanDernierPassage = bilan, breakdownIndexeurs = breakdown, settled = true)
        val modele = PresentationIndexation.afficher(agregatJava(etat))
        val rendu = modele.panneau.bilan!!
        assertEquals(4_200, rendu.dureeMs)
        assertEquals(191, rendu.artefactsTotal)
        assertEquals(188, rendu.artefactsRepris)
        assertEquals(3, rendu.artefactsReconstruits)
        assertEquals(214, rendu.fichiersParcourus)
        assertEquals(12, rendu.fichiersReanalyses)
        assertEquals("noms-classes-java", rendu.indexeurPlusCouteux)
        // Histogramme : 3 indexeurs triés par coût décroissant, largeur
        // RELATIVE au plus cher (maquette §4).
        val couts = modele.panneau.couts
        assertEquals(3, couts.size)
        assertEquals("Noms de classes Java", couts[0].libelle)
        assertEquals(1.0f, couts[0].fraction, 0.001f)
        assertEquals("Appelables Kotlin", couts[1].libelle)
        assertEquals(1_100f / 1_800f, couts[1].fraction, 0.001f)
        assertEquals("Ressources Android", couts[2].libelle)
        assertEquals(600f / 1_800f, couts[2].fraction, 0.001f)
    }

    @Test
    fun `pret sans breakdown — pas d histogramme`() {
        val modele =
            PresentationIndexation.afficher(
                agregatJava(etatRiche(PhaseIndexation.PRET, faits = 191, total = 191)),
            )
        assertTrue(modele.panneau.couts.isEmpty())
    }

    // ============================================================
    // PARTIEL — entrées écartées
    // ============================================================

    @Test
    fun `partiel Java avec avertissements — ecartees, note warn, actions`() {
        val etat =
            etatRiche(PhaseIndexation.PARTIEL, faits = 188, total = 191)
                .copy(
                    avertissements =
                    listOf("legacy-utils.jar : jar illisible", "play-core.aar : AAR sans jar interne"),
                )
        val modele = PresentationIndexation.afficher(agregatJava(etat))
        assertEquals(EtatPastilleIndexation.PARTIEL, modele.pastille.etat)
        assertFalse(modele.pastille.masqueeEtroit)
        assertEquals(2, modele.panneau.entreesEcartees.size)
        assertEquals("legacy-utils.jar : jar illisible", modele.panneau.entreesEcartees[0].texte)
        assertTrue(modele.panneau.erreursBloquantes.isEmpty())
        assertNotNull(modele.panneau.noteImpact)
        assertEquals(NiveauNoteImpact.AVERTISSEMENT, modele.panneau.noteImpact!!.niveau)
        assertEquals(
            listOf(ActionDetailIndexation.REESSAYER_ECHECS, ActionDetailIndexation.REINDEXER),
            modele.panneau.actions,
        )
    }

    @Test
    fun `partiel sans avertissements — action reindexer seule, pas de note`() {
        val modele =
            PresentationIndexation.afficher(
                agregatJava(etatRiche(PhaseIndexation.PARTIEL, faits = 191, total = 191)),
            )
        assertEquals(listOf(ActionDetailIndexation.REINDEXER), modele.panneau.actions)
        assertNull(modele.panneau.noteImpact)
    }

    // ============================================================
    // ERREUR — erreurs bloquantes
    // ============================================================

    @Test
    fun `erreur Java — bloquantes, note erreur, action reindexer`() {
        val etat =
            etatRiche(PhaseIndexation.ERREUR)
                .copy(
                    erreurs =
                    listOf(
                        "Segment n°3 corrompu (checksum invalide)",
                        "Échec écriture /index/.lock (Permission denied)",
                    ),
                )
        val modele = PresentationIndexation.afficher(agregatJava(etat))
        assertEquals(EtatPastilleIndexation.ERREUR, modele.pastille.etat)
        assertFalse(modele.pastille.masqueeEtroit)
        assertEquals(2, modele.panneau.erreursBloquantes.size)
        assertEquals("Segment n°3 corrompu (checksum invalide)", modele.panneau.erreursBloquantes[0].texte)
        assertTrue(modele.panneau.entreesEcartees.isEmpty())
        assertNotNull(modele.panneau.noteImpact)
        assertEquals(NiveauNoteImpact.ERREUR, modele.panneau.noteImpact!!.niveau)
        assertEquals(listOf(ActionDetailIndexation.REINDEXER), modele.panneau.actions)
        // Maquette : pas de barre en erreur.
        assertFalse(modele.panneau.progression.visible)
    }

    // ============================================================
    // MULTI-MOTEURS — bilan le plus récent + erreurs fusionnées
    // ============================================================

    @Test
    fun `pret multi-moteurs — bilan du PLUS RECENT passage gagne`() {
        val bilanVieux =
            BilanDernierPassageIndexation(
                dureeMs = 9_000,
                artefactsTotal = 191,
                artefactsRepris = 190,
                artefactsReconstruits = 1,
                fichiersParcourus = 214,
                fichiersReanalyses = 4,
                indexeurPlusCouteux = "noms-classes-java",
                horodatageMs = 1_000,
            )
        val bilanJeune =
            BilanDernierPassageIndexation(
                dureeMs = 2_500,
                artefactsTotal = 47,
                artefactsRepris = 46,
                artefactsReconstruits = 1,
                fichiersParcourus = 12,
                fichiersReanalyses = 2,
                indexeurPlusCouteux = "appelables-kotlin",
                horodatageMs = 2_000,
            )
        val modele =
            PresentationIndexation.afficher(
                EtatsMoteursIndexation(
                    listOf(
                        MoteurIndexationPresent(
                            MoteurIndexation.JAVA,
                            PhaseIndexation.PRET,
                            etatRiche(PhaseIndexation.PRET).copy(bilanDernierPassage = bilanVieux),
                        ),
                        MoteurIndexationPresent(
                            MoteurIndexation.KOTLIN,
                            PhaseIndexation.PRET,
                            etatRiche(PhaseIndexation.PRET).copy(bilanDernierPassage = bilanJeune),
                        ),
                    ),
                ),
            )
        // Le bilan affiché est celui du passage le plus RÉCENT (Kotlin,
        // horodatage 2 000 > 1 000) — pas toujours celui de Java.
        assertEquals(2_500, modele.panneau.bilan!!.dureeMs)
        assertEquals(47, modele.panneau.bilan!!.artefactsTotal)
        assertEquals("appelables-kotlin", modele.panneau.bilan!!.indexeurPlusCouteux)
    }

    @Test
    fun `erreur multi-moteurs — bloquantes FUSIONNEES toutes langues`() {
        val java =
            etatRiche(PhaseIndexation.ERREUR)
                .copy(erreurs = listOf("Segment n°3 corrompu (checksum invalide)"))
        val kotlin =
            etatRiche(PhaseIndexation.ERREUR)
                .copy(erreurs = listOf("kotlin-appelables/stdlib.kidx : checksum invalide"))
        val modele =
            PresentationIndexation.afficher(
                EtatsMoteursIndexation(
                    listOf(
                        MoteurIndexationPresent(MoteurIndexation.JAVA, PhaseIndexation.ERREUR, java),
                        MoteurIndexationPresent(MoteurIndexation.KOTLIN, PhaseIndexation.ERREUR, kotlin),
                    ),
                ),
            )
        assertEquals(EtatPastilleIndexation.ERREUR, modele.pastille.etat)
        assertEquals(2, modele.panneau.erreursBloquantes.size)
        assertEquals("Segment n°3 corrompu (checksum invalide)", modele.panneau.erreursBloquantes[0].texte)
        assertEquals("kotlin-appelables/stdlib.kidx : checksum invalide", modele.panneau.erreursBloquantes[1].texte)
        assertEquals(listOf(ActionDetailIndexation.REINDEXER), modele.panneau.actions)
    }

    // ============================================================
    // Fabriques (lisibilité des scénarios)
    // ============================================================

    /** État riche Java — moteurs synthétisés absents. */
    private fun agregatJava(etat: EtatIndexation): EtatsMoteursIndexation = EtatsMoteursIndexation(
        listOf(MoteurIndexationPresent(MoteurIndexation.JAVA, etat.phase, etat)),
    )

    /**
     * Fabrique d'un état RICHE standard (les avertissements/erreurs
     * vides) — utilisé pour TOUT moteur (Java, Kotlin, XML : v0.180.0
     * toutes les langues publient le même type, ADR 0146).
     */
    private fun etatRiche(phase: PhaseIndexation, faits: Int = 0, total: Int = 0, reprises: Int = 0): EtatIndexation =
        EtatIndexation(
            phase = phase,
            faits = faits,
            total = total,
            entreeCourante = null,
            avertissements = emptyList(),
            erreurs = emptyList(),
            reprises = reprises,
        )
}
