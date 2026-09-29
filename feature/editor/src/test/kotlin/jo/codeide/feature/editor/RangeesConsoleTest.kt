package jo.codeide.feature.editor

import jo.codeide.core.domain.EtapeSync
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.domain.StatutBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du constructeur PUR des rangées de console (v4, §3.3) : le filtre
 * de canal, l'arbre des étapes (✓ / spinner / ○ — les 8 phases du plan,
 * jamais supprimées), le détail de téléchargement sous l'étape ACTIVE, la
 * synthèse de fin de build. Ces tests verrouillent la STRUCTURE — les
 * libellés se résolvent au rendu (localisation FR/EN du présentateur).
 */
class RangeesConsoleTest {
    /** Ligne d'étape annoncée par le canal Sync (fabric du test). */
    private fun ligneEtape(
        id: Long,
        etat: EtapeSyncAffichee,
    ): LigneConsole.Etape = LigneConsole.Etape(id = id, canal = CanalTooling.SYNC, etat = etat)

    // ---- Filtre TOUS : chronologie brute --------------------------------

    @Test
    fun `le filtre TOUS rend la chronologie brute sans transformation`() {
        val lignes =
            listOf(
                LigneConsole.Sortie(
                    id = 1,
                    canal = CanalTooling.SYNC,
                    flux = jo.codeide.core.domain.FluxSortieBuild.STDOUT,
                    texte = "ligne sync",
                ),
                LigneConsole.Tache(
                    id = 2,
                    canal = CanalTooling.BUILD,
                    etat =
                        EtatTacheAffichee(
                            chemin = ":app:build",
                            statut = jo.codeide.core.domain.StatutTache.REUSSIE,
                        ),
                ),
            )
        val etat = EtatGradle(synchronisationEnCours = false, lignes = lignes)

        val rangees = construireRangeesConsole(etat, FiltreCanalConsole.TOUS)

        assertEquals(
            listOf<RangeeConsole>(
                RangeeConsole.Ligne(lignes[0]),
                RangeeConsole.Ligne(lignes[1]),
            ),
            rangees,
        )
    }

    // ---- Filtre SYNC : arbre des étapes ---------------------------------

    @Test
    fun `le filtre SYNC montre les huit phases du plan meme non annoncees`() {
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

        // L'arbre couvre TOUTES les phases (les non-annoncées restent en
        // attente ○ — le chemin complet reste visible, §3.3).
        val etapes = rangees.filterIsInstance<RangeeConsole.EtapeArbre>()
        assertEquals(EtapeSync.entries.size, etapes.size)
        assertEquals(
            EtapeSync.entries.map { it.name },
            etapes.map { it.phase.name },
        )
        assertTrue(etapes.first { it.phase == EtapeSync.OUTILS }.annoncee?.terminee == true)
        assertEquals(null, etapes.first { it.phase == EtapeSync.DAEMON }.annoncee)
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
        // Le détail suit IMMÉDIATEMENT l'étape active dans la liste.
        assertEquals(
            RangeeConsole.EtapeArbre(EtapeSync.DEPENDANCES, etat.etapesAffichees.lastOrNull()),
            rangees.first { it is RangeeConsole.EtapeArbre && it.phase == EtapeSync.DEPENDANCES },
        )
    }

    @Test
    fun `le filtre SYNC garde les sorties brutes du canal et ecarte le build`() {
        val lignes =
            listOf(
                LigneConsole.Sortie(
                    id = 1,
                    canal = CanalTooling.SYNC,
                    flux = jo.codeide.core.domain.FluxSortieBuild.STDOUT,
                    texte = "sync out",
                ),
                LigneConsole.Sortie(
                    id = 2,
                    canal = CanalTooling.BUILD,
                    flux = jo.codeide.core.domain.FluxSortieBuild.STDERR,
                    texte = "build err",
                ),
                LigneConsole.Etape(
                    id = 3,
                    canal = CanalTooling.SYNC,
                    etat = EtapeSyncAffichee(etape = EtapeSync.OUTILS, terminee = true, dureeMs = 100),
                ),
            )
        val etat = EtatGradle(synchronisationEnCours = true, lignes = lignes)

        val rangees = construireRangeesConsole(etat, FiltreCanalConsole.SYNC)

        val lignesRendues = rangees.filterIsInstance<RangeeConsole.Ligne>()
        assertEquals(1, lignesRendues.size)
        assertEquals("sync out", (lignesRendues.first().ligne as LigneConsole.Sortie).texte)
    }

    // ---- Filtre BUILD : tâches + synthèse --------------------------------

    @Test
    fun `le filtre BUILD garde les lignes du canal build et ajoute la synthese au terme`() {
        val lignes =
            listOf(
                LigneConsole.Tache(
                    id = 1,
                    canal = CanalTooling.BUILD,
                    etat =
                        EtatTacheAffichee(
                            chemin = ":app:compileKotlin",
                            statut = jo.codeide.core.domain.StatutTache.REUSSIE,
                            dureeMs = 6_100,
                        ),
                ),
                LigneConsole.Sortie(
                    id = 2,
                    canal = CanalTooling.SYNC,
                    flux = jo.codeide.core.domain.FluxSortieBuild.STDOUT,
                    texte = "sync out",
                ),
            )
        val etat =
            EtatGradle(
                statutBuild = StatutBuild.REUSSI,
                dureeBuildMs = 12_400,
                lignes = lignes,
            )

        val rangees = construireRangeesConsole(etat, FiltreCanalConsole.BUILD)

        assertEquals(2, rangees.size)
        assertTrue(rangees[0] is RangeeConsole.Ligne)
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
                lignes =
                    listOf(
                        LigneConsole.Tache(
                            id = 1,
                            canal = CanalTooling.BUILD,
                            etat =
                                EtatTacheAffichee(
                                    chemin = ":app:build",
                                    statut = jo.codeide.core.domain.StatutTache.EN_COURS,
                                ),
                        ),
                    ),
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
    fun `les cles des rangees sont stables par phase et par ligne`() {
        val etat =
            EtatGradle(
                statutBuild = StatutBuild.REUSSI,
                synchronisationReussie = ResultatSynchronisation(projectDir = "/p", reussie = true),
                lignes =
                    listOf(
                        ligneEtape(id = 7, EtapeSyncAffichee(etape = EtapeSync.DAEMON)),
                        LigneConsole.Tache(
                            id = 8,
                            canal = CanalTooling.BUILD,
                            etat =
                                EtatTacheAffichee(
                                    chemin = ":app:build",
                                    statut = jo.codeide.core.domain.StatutTache.REUSSIE,
                                ),
                        ),
                    ),
            )

        val rangeesSync = construireRangeesConsole(etat, FiltreCanalConsole.SYNC)
        assertEquals(
            listOf(
                "etape-OUTILS",
                "etape-DISTRIBUTION",
                "etape-DAEMON",
                "etape-CONFIGURATION",
                "etape-MODELE_TACHES",
                "etape-MODELE_IDE",
                "etape-DEPENDANCES",
                "etape-CLASSPATHS",
            ),
            rangeesSync.map { it.idCle },
        )

        val rangeesBuild = construireRangeesConsole(etat, FiltreCanalConsole.BUILD)
        assertEquals(listOf("ligne-8", "synthese-build"), rangeesBuild.map { it.idCle })
    }
}
