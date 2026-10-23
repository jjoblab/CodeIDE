package jo.codeide.feature.editor

import jo.codeide.core.domain.CapaciteLsp
import jo.codeide.core.domain.LangageLsp
import jo.codeide.core.domain.MoteursLsp
import jo.codeide.core.domain.PhaseMoteurLsp
import jo.codeide.core.domain.RaisonRefusVidageLsp
import jo.codeide.core.domain.ResultatVidageCacheLsp
import jo.codeide.lsp.host.BilanDernierPassageIndexation
import jo.codeide.lsp.host.EtatIndexation
import jo.codeide.lsp.host.PhaseIndexation
import jo.codeide.lsp.host.RaisonRefusVidage
import jo.codeide.lsp.host.ResultatVidageCache
import jo.codeide.lsp.host.SessionLsp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests du port des moteurs LSP (lot LSP-3, ADR 0128 §4) : PARITÉ des
 * miroirs domaine ↔ hôte (le test d'architecture « liste des
 * interrupteurs = capacités annoncées » s'appuie dessus), projection de
 * l'état Java, refus de vidage en DONNÉE, et comportement SANS session.
 */
class PortLspEspaceTest {
    @get:Rule
    val dossierTemporaire = TemporaryFolder()

    // ------------------------------------------------------------------
    // Parité des miroirs — le tableau des interrupteurs en dépend
    // ------------------------------------------------------------------

    @Test
    fun `les trois langages du domaine se miroitent vers l'hôte`() {
        assertEquals(3, LangageLsp.entries.size)
        LangageLsp.entries.forEach { domaine ->
            assertEquals(domaine.name, domaine.versLangageHote().name)
        }
    }

    @Test
    fun `les dix capacités du domaine se miroitent vers l'hôte`() {
        assertEquals(10, CapaciteLsp.entries.size)
        jo.codeide.lsp.host.CapaciteLsp.entries.forEach { hote ->
            assertEquals(hote.name, hote.versCapaciteDomaine().name)
        }
    }

    @Test
    fun `capacités annoncées - Java les dix, Kotlin les dix depuis le 1-3-0, XML huit sans signatures ni indices`() {
        // Le tableau attendu EXPLICITE (revue §1, verrouillé par la parité
        // du frère) : c'est LUI qui alimente les interrupteurs de l'écran L2.
        // codeide-lsp 1.3.0 (v0.168.0) : Kotlin annonce le FORMATAGE et les
        // INDICES EN LIGNE (références déjà servies), XML les RÉFÉRENCES —
        // la table se miroit, l'écran L2 gagne les interrupteurs effectifs
        // sans changement de code (dérivation [TableCapacitesLspHote]).
        val attendues =
            mapOf(
                LangageLsp.JAVA to capacites(*CapaciteLsp.entries.toTypedArray()),
                LangageLsp.KOTLIN to capacites(*CapaciteLsp.entries.toTypedArray()),
                LangageLsp.XML to
                    capacites(
                        CapaciteLsp.COMPLETION,
                        CapaciteLsp.SURVOL,
                        CapaciteLsp.DIAGNOSTICS,
                        CapaciteLsp.DEFINITION,
                        CapaciteLsp.REFERENCES,
                        CapaciteLsp.RENOMMAGE,
                        CapaciteLsp.ACTIONS_RAPIDES,
                        CapaciteLsp.FORMATAGE,
                    ),
            )

        val table = TableCapacitesLspHote()
        LangageLsp.entries.forEach { langage ->
            assertEquals("annoncées pour $langage", attendues.getValue(langage), table.capacitesAnnoncees(langage))
        }
    }

    // ------------------------------------------------------------------
    // Projection de l'état Java (pure)
    // ------------------------------------------------------------------

    @Test
    fun `les cinq phases se miroitent vers le domaine`() {
        PhaseIndexation.entries.forEach { hote ->
            assertEquals(hote.name, hote.versPhaseDomaine().name)
        }
        assertEquals(5, PhaseMoteurLsp.entries.size)
    }

    @Test
    fun `la construction porte son avancement, le reste n'affiche AUCUN pourcentage`() {
        val moitie =
            EtatIndexation(
                phase = PhaseIndexation.CONSTRUCTION,
                faits = 62,
                total = 100,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = emptyList(),
            )
        assertEquals(62, moitie.versEtatMoteurLsp().avancementPourCent)

        val indeterminee =
            EtatIndexation(
                phase = PhaseIndexation.CONSTRUCTION,
                faits = 3,
                total = 0,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = emptyList(),
            )
        assertNull(indeterminee.versEtatMoteurLsp().avancementPourCent)

        val prete =
            EtatIndexation(
                phase = PhaseIndexation.PRET,
                faits = 191,
                total = 191,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = emptyList(),
            )
        assertNull(prete.versEtatMoteurLsp().avancementPourCent)
        assertEquals(191, prete.versEtatMoteurLsp().fichiersIndexes)
    }

    @Test
    fun `comptes, messages TRONQUÉS à 20 et bilan du dernier passage passent tels quels`() {
        val etat =
            EtatIndexation(
                phase = PhaseIndexation.PARTIEL,
                faits = 7,
                total = 9,
                entreeCourante = null,
                avertissements = List(3) { "avertissement $it" },
                erreurs = List(25) { "erreur $it" },
                bilanDernierPassage =
                BilanDernierPassageIndexation(
                    dureeMs = 72_000,
                    artefactsTotal = 9,
                    artefactsRepris = 0,
                    artefactsReconstruits = 0,
                    fichiersParcourus = 191,
                    fichiersReanalyses = 0,
                    indexeurPlusCouteux = null,
                    horodatageMs = 1_700_000_000_000,
                ),
            )

        val projetee = etat.versEtatMoteurLsp()

        assertEquals(PhaseMoteurLsp.PARTIEL, projetee.phase)
        assertEquals(3, projetee.avertissements)
        assertEquals(25, projetee.erreurs)
        assertEquals(ERREURS_LSP_MAX, projetee.messagesErreur.size)
        assertEquals("erreur 19", projetee.messagesErreur.last())
        assertEquals(72_000L, projetee.dureeDernierPassageMs)
        assertEquals(1_700_000_000_000L, projetee.horodatageDernierPassageMs)
        assertTrue(projetee.constructionActive.not())
    }

    @Test
    fun `la construction active est portée par la phase en cours`() {
        assertTrue(
            EtatIndexation(
                phase = PhaseIndexation.CONSTRUCTION,
                faits = 0,
                total = 0,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = emptyList(),
            ).versEtatMoteurLsp().constructionActive,
        )
        assertFalse(
            EtatIndexation(
                phase = PhaseIndexation.PRET,
                faits = 0,
                total = 0,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = emptyList(),
            ).versEtatMoteurLsp().constructionActive,
        )
    }

    // ------------------------------------------------------------------
    // Refus de vidage en DONNÉE
    // ------------------------------------------------------------------

    @Test
    fun `le vidage et son refus se miroitent en données, jamais en exception`() {
        val videe = ResultatVidageCache.Videe(segmentsSupprimes = 4, octetsLiberes = 1_000)
        assertEquals(ResultatVidageCacheLsp.Videe(4, 1_000), videe.versResultatDomaine())

        val refusee = ResultatVidageCache.Refusee(raison = RaisonRefusVidage.INDEXATION_EN_COURS)
        assertEquals(
            ResultatVidageCacheLsp.Refusee(RaisonRefusVidageLsp.INDEXATION_EN_COURS),
            refusee.versResultatDomaine(),
        )
    }

    // ------------------------------------------------------------------
    // Port vivant / port sans session (comportement, session RÉELLE)
    // ------------------------------------------------------------------

    @Test
    fun `sans session le port observe l'inactivité et refuse tout par l'absence`() = runTest {
        val port = portSansSession()

        assertEquals(MoteursLsp.INACTIFS, port.moteurs.first())
        assertNull(port.tailleCacheOctets())
        assertFalse(port.reindexer())
        assertNull(port.viderCache())
    }

    @Test
    fun `la demande de détails est un état - posée elle survit, consommée elle retombe`() {
        val port = portSansSession()

        // Défaut : aucune demande en attente.
        assertFalse(port.demandeDetailsIndexation.value)

        // Posée (le panneau vit côté éditeur, la session n'y change rien) :
        // l'état TIENT jusqu'à consommation — même espacée, l'utilisateur
        // remonte l'espace quand il veut (lot LSP-4, ADR 0117).
        port.demanderDetailsIndexation()
        port.demanderDetailsIndexation()
        assertTrue(port.demandeDetailsIndexation.value)

        // Consommée par l'espace AU MOMENT d'ouvrir le panneau.
        port.consommerDemandeDetailsIndexation()
        assertFalse(port.demandeDetailsIndexation.value)
    }

    @Test
    fun `une session vivante alimente l'état, les capacités et les actions`() = runTest {
        val racine = dossierTemporaire.newFolder("projet")
        val stockage = dossierTemporaire.newFolder("index")
        val session = SessionLsp(racineProjet = racine, stockageIndex = stockage)
        val port =
            PortLspEspace(
                sessionJava = MutableStateFlow(session),
                sessionKotlin = MutableStateFlow(null),
                sessionXml = MutableStateFlow(null),
                portee = backgroundScope,
            )

        // La session n'a encore RIEN indexé : phase INITIALE, cache vide.
        val moteurs = port.moteurs.first { it.java != null }
        assertEquals(PhaseMoteurLsp.INITIAL, moteurs.java?.phase)
        assertFalse(moteurs.kotlin)
        assertFalse(moteurs.xml)
        assertEquals(10, TableCapacitesLspHote().capacitesAnnoncees(LangageLsp.JAVA).size)
        assertEquals(0L, port.tailleCacheOctets())

        // Les actions sont ancrées : reindexer s'exécute (no-op sans
        // vue alignée), vider un stockage vide VIDE (données).
        assertTrue(port.reindexer())
        assertNotNull(port.viderCache())

        session.close()
    }

    private fun portSansSession(): PortLspEspace = PortLspEspace(
        sessionJava = MutableStateFlow(null),
        sessionKotlin = MutableStateFlow(null),
        sessionXml = MutableStateFlow(null),
        portee = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
    )

    private fun capacites(vararg valeurs: CapaciteLsp) = valeurs.toSet()
}
