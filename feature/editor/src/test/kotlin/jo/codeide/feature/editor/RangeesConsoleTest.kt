package jo.codeide.feature.editor

import jo.codeide.core.domain.EtapeSync
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.domain.StatutTache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du constructeur PUR des rangées de console (v4, §3.3 ; v5 — plan de
 * l'APERÇU) : les deux vues EXCLUSIVES (Sync = l'arbre des SEPT étapes
 * affichées + son pied, Build = les tâches seules + leur synthèse — la
 * chronologie brute et les sorties brutes ne sont plus des écrans), la
 * fusion « Dépendances et modèle IDE », la phase SAUTÉE « en cache », le
 * détail de téléchargement sous l'étape ACTIVE. Ces tests verrouillent la
 * STRUCTURE — les libellés se résolvent au rendu (localisation FR/EN du
 * présentateur).
 */
class RangeesConsoleTest {
    /** Ligne d'étape annoncée par le canal Sync (fabrique du test). */
    private fun ligneEtape(
        id: Long,
        etat: EtapeSyncAffichee,
    ): LigneConsole.Etape = LigneConsole.Etape(id = id, canal = CanalTooling.SYNC, etat = etat)

    /** Tâche du canal Build (fabrique du test). */
    private fun ligneTache(
        id: Long,
        chemin: String,
        statut: StatutTache,
    ): LigneConsole.Tache =
        LigneConsole.Tache(id = id, canal = CanalTooling.BUILD, etat = EtatTacheAffichee(chemin, statut))

    // ---- Vue SYNC : l'arbre des sept étapes ------------------------------

    @Test
    fun `la vue SYNC montre les etapes announcees progressivement - v0_41_1`() {
        val etat =
            EtatGradle(
                synchronisationEnCours = true,
                lignes =
                    listOf(
                        ligneEtape(id = 1, EtapeSyncAffichee(etape = EtapeSync.OUTILS, terminee = true, dureeMs = 800)),
                        ligneEtape(id = 2, EtapeSyncAffichee(etape = EtapeSync.DISTRIBUTION)),
                    ),
            )

        val rangees = construireRangeesConsole(etat, FiltreCanalConsole.SYNC)

        // v0.41.1 : étapes PROGRESSIVES — seules les étapes ANNONCÉES
        // apparaissent. OUTILS (annoncée, terminée) + DISTRIBUTION
        // (annoncée, en cours) = 2 étapes, pas 7.
        val etapes = rangees.filterIsInstance<RangeeConsole.EtapeArbre>()
        assertEquals(2, etapes.size)
        assertEquals(EtapeConsoleSync.OUTILS, etapes[0].etape)
        assertEquals(EtapeConsoleSync.DISTRIBUTION, etapes[1].etape)
        assertTrue(etapes[0].etat?.terminee == true)
        assertTrue(etapes[1].etat?.terminee == false)
    }

    @Test
    fun `l arbre est absent tant qu aucune sync n a ete annonceee`() {
        val rangees = construireRangeesConsole(EtatGradle(), FiltreCanalConsole.SYNC)
        assertTrue("aucun arbre sans annonce — l'état vide parle (v5)", rangees.isEmpty())
    }

    // v6 (prompt de suivi §2) : le test `la distribution en cache est
    // SAUTEE - point en cache, jamais de duree` est SUPPRIMÉ — le drapeau
    // `sautee` n'existe plus. Une distribution en cache n'est pas émise
    // par le serveur, donc pas présente dans l'arbre du client.

    @Test
    fun `la fusion dependances et modele IDE conclut sans telechargement annonce`() {
        // Sync chaude : MODELE_IDE conclue, DEPENDANCES jamais annoncée (aucun
        // téléchargement n'a eu lieu) — la rangée fusionnée conclut quand
        // même (l'ancienne rangée DEPENDANCES restait ○ à vie).
        val etat =
            EtatGradle(
                synchronisationEnCours = true,
                lignes =
                    listOf(
                        ligneEtape(
                            id = 1,
                            EtapeSyncAffichee(etape = EtapeSync.MODELE_TACHES, terminee = true, dureeMs = 800),
                        ),
                        ligneEtape(
                            id = 2,
                            EtapeSyncAffichee(etape = EtapeSync.MODELE_IDE, terminee = true, dureeMs = 600),
                        ),
                    ),
            )

        val fusion =
            construireRangeesConsole(etat, FiltreCanalConsole.SYNC)
                .filterIsInstance<RangeeConsole.EtapeArbre>()
                .first { it.etape == EtapeConsoleSync.DEPENDANCES_MODELE }

        assertTrue("la fusion conclut avec son unique phase annoncée", fusion.etat?.terminee == true)
        assertEquals("durée cumulée des phases terminées", 600L, fusion.etat?.dureeMs)
    }

    @Test
    fun `la fusion cumule les durees et garde le compteur des dependances conclues`() {
        val etat =
            EtatGradle(
                synchronisationEnCours = true,
                lignes =
                    listOf(
                        ligneEtape(
                            id = 1,
                            EtapeSyncAffichee(
                                etape = EtapeSync.DEPENDANCES,
                                terminee = true,
                                dureeMs = 1_400,
                                octetsRecus = 44_040_192L,
                                compteur = 37,
                            ),
                        ),
                        ligneEtape(
                            id = 2,
                            EtapeSyncAffichee(etape = EtapeSync.MODELE_IDE, terminee = true, dureeMs = 2_600),
                        ),
                    ),
            )

        val fusion =
            construireRangeesConsole(etat, FiltreCanalConsole.SYNC)
                .filterIsInstance<RangeeConsole.EtapeArbre>()
                .first { it.etape == EtapeConsoleSync.DEPENDANCES_MODELE }

        assertTrue(fusion.etat?.terminee == true)
        assertEquals("cumul des deux phases terminées", 4_000L, fusion.etat?.dureeMs)
        assertEquals("le compteur final des dépendances reste lisible", 37, fusion.etat?.compteur)
        assertEquals(44_040_192L, fusion.etat?.octetsRecus)
    }

    @Test
    fun `le detail de telechargement s insere sous l etape active seulement`() {
        val etat =
            EtatGradle(
                synchronisationEnCours = true,
                lignes =
                    listOf(
                        ligneEtape(id = 1, EtapeSyncAffichee(etape = EtapeSync.OUTILS, terminee = true, dureeMs = 300)),
                        ligneEtape(
                            id = 2,
                            EtapeSyncAffichee(
                                etape = EtapeSync.DEPENDANCES,
                                octetsRecus = 44_040_192L,
                                octetsTotal = 136_314_880L,
                                element = "kotlin-stdlib.jar",
                                compteur = 3,
                                total = 37,
                            ),
                        ),
                    ),
            )

        val rangees = construireRangeesConsole(etat, FiltreCanalConsole.SYNC)

        val details = rangees.filterIsInstance<RangeeConsole.DetailTelechargement>()
        assertEquals(1, details.size)
        val detail = details.first()
        assertEquals(EtapeSync.DEPENDANCES, detail.phase)
        assertEquals(44_040_192L, detail.octetsRecus)
        assertEquals(136_314_880L, detail.octetsTotal)
        assertEquals("kotlin-stdlib.jar", detail.element)
        assertEquals(3, detail.compteur)
        assertEquals(37, detail.total)
        // Le détail suit IMMÉDIATEMENT la rangée fusionnée active, portant
        // l'état consolidé de sa phase vivante.
        assertEquals(
            RangeeConsole.EtapeArbre(
                EtapeConsoleSync.DEPENDANCES_MODELE,
                EtatEtapeArbre(
                    terminee = false,
                    dureeMs = 0L,
                    octetsRecus = 44_040_192L,
                    octetsTotal = 136_314_880L,
                    element = "kotlin-stdlib.jar",
                    compteur = 3,
                    total = 37,
                ),
            ),
            rangees.first { it is RangeeConsole.EtapeArbre && it.etape == EtapeConsoleSync.DEPENDANCES_MODELE },
        )
    }

    @Test
    fun `la vue SYNC ne melange plus les sorties brutes`() {
        val etat =
            EtatGradle(
                synchronisationEnCours = true,
                lignes =
                    listOf(
                        LigneConsole.Sortie(
                            id = 1,
                            canal = CanalTooling.SYNC,
                            flux = jo.codeide.core.domain.FluxSortieBuild.STDOUT,
                            texte = "sync out",
                        ),
                        LigneConsole.Tache(
                            id = 2,
                            canal = CanalTooling.BUILD,
                            etat = EtatTacheAffichee(chemin = ":app:build", statut = StatutTache.REUSSIE),
                        ),
                        ligneEtape(id = 3, EtapeSyncAffichee(etape = EtapeSync.OUTILS, terminee = true, dureeMs = 100)),
                    ),
            )

        val rangees = construireRangeesConsole(etat, FiltreCanalConsole.SYNC)

        assertTrue(
            "aucune ligne brute dans la vue Sync (v5 — l'aperçu ne les montre pas)",
            rangees.none { it is RangeeConsole.Tache },
        )
    }

    // ---- Pied de conclusion de la sync (v5, aperçu) ----------------------

    @Test
    fun `le pied de sync conclut - a jour quand rien n a ete telecharge`() {
        fun etatAvec(vararg etapes: EtapeSyncAffichee): EtatGradle =
            EtatGradle(
                synchronisationReussie = ResultatSynchronisation(projectDir = "/p", reussie = true),
                lignes = etapes.mapIndexed { index, etat -> ligneEtape(index.toLong() + 1L, etat) },
            )

        // v6 : la distribution en cache n'est pas émise — aucune étape
        // DISTRIBUTION dans les lignes, aucun octet reçu → pied « à jour ».
        val aJour =
            construireRangeesConsole(
                etatAvec(
                    EtapeSyncAffichee(etape = EtapeSync.CLASSPATHS, terminee = true, compteur = 3),
                ),
                FiltreCanalConsole.SYNC,
            ).filterIsInstance<RangeeConsole.SyntheseSync>()
                .single()
        assertTrue("projet à jour, rien à télécharger", aJour.aJour)

        // Sync froide : des octets ont été reçus.
        val froide =
            construireRangeesConsole(
                etatAvec(
                    EtapeSyncAffichee(
                        etape = EtapeSync.DISTRIBUTION,
                        terminee = true,
                        dureeMs = 9_000,
                        octetsRecus = 136_314_880L,
                    ),
                ),
                FiltreCanalConsole.SYNC,
            ).filterIsInstance<RangeeConsole.SyntheseSync>()
                .single()
        assertTrue("des téléchargements ont eu lieu", !froide.aJour)
    }

    @Test
    fun `le pied de sync est absent en vol, a l echec et sans resultat`() {
        val enVol =
            EtatGradle(
                synchronisationEnCours = true,
                synchronisationReussie = ResultatSynchronisation(projectDir = "/p", reussie = true),
                lignes = listOf(ligneEtape(1, EtapeSyncAffichee(etape = EtapeSync.OUTILS))),
            )
        val echec = EtatGradle(messageEchecSync = "réseau coupé")
        val vierge = EtatGradle()

        assertTrue(construireRangeesConsole(enVol, FiltreCanalConsole.SYNC).none { it is RangeeConsole.SyntheseSync })
        assertTrue(construireRangeesConsole(echec, FiltreCanalConsole.SYNC).none { it is RangeeConsole.SyntheseSync })
        assertTrue(construireRangeesConsole(vierge, FiltreCanalConsole.SYNC).none { it is RangeeConsole.SyntheseSync })
    }

    // ---- Vue BUILD : les tâches seules + la synthèse ----------------------

    @Test
    fun `la vue BUILD garde les taches seules et ajoute la synthese au terme`() {
        val lignes =
            listOf(
                LigneConsole.Tache(
                    id = 1,
                    canal = CanalTooling.BUILD,
                    etat =
                        EtatTacheAffichee(
                            chemin = ":app:compileKotlin",
                            statut = StatutTache.REUSSIE,
                            dureeMs = 6_100,
                        ),
                ),
                LigneConsole.Sortie(
                    id = 2,
                    canal = CanalTooling.BUILD,
                    flux = jo.codeide.core.domain.FluxSortieBuild.STDERR,
                    texte = "build err",
                ),
            )
        val etat =
            EtatGradle(
                statutBuild = StatutBuild.REUSSI,
                dureeBuildMs = 12_400,
                lignes = lignes,
            )

        val rangees = construireRangeesConsole(etat, FiltreCanalConsole.BUILD)

        // v0.41.1 : les lignes stdout/stderr brutes de Gradle
        // s'intercalent entre les tâches — la sortie stderr "build err"
        // apparaît entre la tâche et la synthèse.
        assertEquals(3, rangees.size)
        val tache = rangees[0] as RangeeConsole.Tache
        assertEquals(":app:compileKotlin", tache.ligne.etat.chemin)
        assertEquals(6_100L, tache.ligne.etat.dureeMs)
        val ligneGradle = rangees[1] as RangeeConsole.LigneGradle
        assertEquals("build err", ligneGradle.ligne.texte)
        val synthese = rangees.last() as RangeeConsole.SyntheseBuild
        assertEquals(StatutBuild.REUSSI, synthese.statut)
        assertEquals(12_400L, synthese.dureeMs)
        assertEquals(null, synthese.message)
    }

    @Test
    fun `la synthese est absente pendant le build en vol et a l etat vierge`() {
        val enVol =
            EtatGradle(
                statutBuild = StatutBuild.EN_COURS,
                lignes = listOf(ligneTache(id = 1, chemin = ":app:build", statut = StatutTache.EN_COURS)),
            )

        val rangeesVol = construireRangeesConsole(enVol, FiltreCanalConsole.BUILD)
        assertTrue(rangeesVol.none { it is RangeeConsole.SyntheseBuild })

        val rangeesVierge = construireRangeesConsole(EtatGradle(), FiltreCanalConsole.BUILD)
        assertTrue(rangeesVierge.isEmpty())
    }

    @Test
    fun `la synthese d echec porte le message du serveur`() {
        val etat =
            EtatGradle(
                statutBuild = StatutBuild.ECHOUE,
                messageEchecBuild = "tache compileKotlin echouee",
            )

        val synthese =
            construireRangeesConsole(etat, FiltreCanalConsole.BUILD)
                .filterIsInstance<RangeeConsole.SyntheseBuild>()
                .single()

        assertEquals("tache compileKotlin echouee", synthese.message)
        assertEquals(StatutBuild.ECHOUE, synthese.statut)
    }

    // ---- Identités stables (mise à jour en place) ------------------------

    @Test
    fun `les cles des rangees sont stables par etape et par tache`() {
        val etat =
            EtatGradle(
                statutBuild = StatutBuild.REUSSI,
                synchronisationReussie = ResultatSynchronisation(projectDir = "/p", reussie = true),
                lignes =
                    listOf(
                        ligneEtape(id = 7, EtapeSyncAffichee(etape = EtapeSync.DAEMON)),
                        ligneTache(id = 8, chemin = ":app:build", statut = StatutTache.REUSSIE),
                    ),
            )

        val rangeesSync = construireRangeesConsole(etat, FiltreCanalConsole.SYNC)
        // v0.41.1 : étapes PROGRESSIVES — seule l'étape DAEMON a été
        // annoncée (les autres non). L'arbre ne montre que les étapes
        // annoncées + le pied de sync.
        assertEquals(
            listOf(
                "etape-DAEMON",
                "synthese-sync",
            ),
            rangeesSync.map { it.idCle },
        )

        val rangeesBuild = construireRangeesConsole(etat, FiltreCanalConsole.BUILD)
        assertEquals(listOf("tache-8", "synthese-build"), rangeesBuild.map { it.idCle })
    }
}
