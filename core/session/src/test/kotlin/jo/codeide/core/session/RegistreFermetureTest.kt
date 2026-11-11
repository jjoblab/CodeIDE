package jo.codeide.core.session

import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.LogLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests du [RegistreFermeture] — le cœur des exigences I2/I7 de l'étape B
 * (prompt 3 §6-Étape B) : **ordre inverse**, **idempotence**, **panne
 * partielle**, **dépassement de délai**, **annulation** (éléments non
 * annulables sauvés), désenregistrement, refus d'enregistrement pendant
 * la fermeture, journalisation du rapport.
 *
 * Le temps est VIRTUEL (`runTest`) : les `delay` des doubles avancent
 * l'horloge injectée — les durées du rapport s'assertissent exactement.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RegistreFermetureTest {
    @Test
    fun `ordre inverse du demarrage`() = runTest {
        val ordre = mutableListOf<String>()
        val registre = RegistreFermeture(horloge = TimeProvider { testScheduler.currentTime })
        val a = FermableEspion("a", ordre)
        val b = FermableEspion("b", ordre)
        val c = FermableEspion("c", ordre)
        registre.enregistrer(a, ms(1_000))
        registre.enregistrer(b, ms(1_000))
        registre.enregistrer(c, ms(1_000))

        val rapport = registre.fermerTout(ContexteFermeture(RaisonFermeture.Nettoyage))

        // I2 : ce qui a démarré en DERNIER meurt en PREMIER
        assertEquals(listOf("c", "b", "a"), ordre)
        assertEquals(1, a.terminees)
        assertEquals(1, b.terminees)
        assertEquals(1, c.terminees)
        assertEquals(3, rapport.nbFermes)
        assertEquals(0, rapport.nbEchecs)
        // le rapport suit l'ordre d'exécution de la fermeture
        assertEquals(listOf("c", "b", "a"), rapport.elements.map { it.nom })
    }

    @Test
    fun `duree mesuree par element et duree totale`() = runTest {
        val ordre = mutableListOf<String>()
        val registre = RegistreFermeture(horloge = TimeProvider { testScheduler.currentTime })
        registre.enregistrer(FermableEspion("rapide", ordre, dureeTravail = ms(5)), ms(1_000))
        registre.enregistrer(FermableEspion("lente", ordre, dureeTravail = ms(7)), ms(1_000))

        val rapport = registre.fermerTout(ContexteFermeture(RaisonFermeture.Utilisateur))

        // fermeture en ordre inverse : lente (7 ms) puis rapide (5 ms)
        assertEquals(ms(7), rapport.elements[0].duree)
        assertEquals(ms(5), rapport.elements[1].duree)
        assertEquals(ms(12), rapport.dureeTotale)
        assertEquals(RaisonFermeture.Utilisateur, rapport.raison)
    }

    @Test
    fun `idempotence — seconde fermeture referme un registre vide`() = runTest {
        val ordre = mutableListOf<String>()
        val registre = RegistreFermeture(horloge = TimeProvider { testScheduler.currentTime })
        val unique = FermableEspion("unique", ordre)
        registre.enregistrer(unique, ms(1_000))

        val premiere = registre.fermerTout(ContexteFermeture(RaisonFermeture.Utilisateur))
        val seconde = registre.fermerTout(ContexteFermeture(RaisonFermeture.Nettoyage))

        assertEquals(1, unique.appels)
        assertEquals(1, premiere.nbFermes)
        assertTrue(seconde.elements.isEmpty())
        assertEquals(ms(0), seconde.dureeTotale)
    }

    @Test
    fun `panne partielle — l echec d un element n arrete jamais les autres`() = runTest {
        val ordre = mutableListOf<String>()
        val journal = JournalEspion()
        val registre = RegistreFermeture(journal = journal, horloge = TimeProvider { testScheduler.currentTime })
        val saine = FermableEspion("saine", ordre)
        val cassee = FermableEspion("cassee", ordre, panne = RuntimeException("boom"))
        registre.enregistrer(saine, ms(1_000))
        registre.enregistrer(cassee, ms(1_000))
        // la troisième prouve que la fermeture CONTINUE après la panne
        val derniere = FermableEspion("derniere", ordre)
        registre.enregistrer(derniere, ms(1_000))

        val rapport = registre.fermerTout(ContexteFermeture(RaisonFermeture.Utilisateur))

        // ordre inverse : derniere (fermée), cassee (panne), saine (fermée)
        assertEquals(listOf("derniere", "cassee", "saine"), ordre)
        assertEquals(1, saine.terminees)
        assertEquals(1, derniere.terminees)
        assertEquals(0, cassee.terminees)
        assertEquals(2, rapport.nbFermes)
        val echec = rapport.elements.first { it.nom == "cassee" }.resultat
        assertTrue(echec is ResultatFermetureElement.Echec)
        assertEquals("boom", (echec as ResultatFermetureElement.Echec).details)
        // I2 : la panne est journalisée (WARN), jamais muette
        assertTrue(journal.avertissements("RegistreFermeture").any { it.contains("cassee") && it.contains("boom") })
    }

    @Test
    fun `depassement de delai — element abandonne, les autres ferment`() = runTest {
        val ordre = mutableListOf<String>()
        val journal = JournalEspion()
        val registre = RegistreFermeture(journal = journal, horloge = TimeProvider { testScheduler.currentTime })
        val lente = FermableEspion("lente", ordre, dureeTravail = ms(500))
        val rapide = FermableEspion("rapide", ordre, dureeTravail = ms(5))
        registre.enregistrer(lente, ms(50)) // délai volontairement dépassable
        registre.enregistrer(rapide, ms(1_000))

        val rapport = registre.fermerTout(ContexteFermeture(RaisonFermeture.Utilisateur))

        // ordre inverse : rapide d'abord, puis lente (abandonnée à 50 ms)
        assertEquals(listOf("rapide", "lente"), ordre)
        assertEquals(1, rapide.terminees)
        assertEquals(1, lente.appels)
        assertEquals(0, lente.terminees)
        val verdictLente = rapport.elements.first { it.nom == "lente" }.resultat
        assertSame(ResultatFermetureElement.DelaiDepasse, verdictLente)
        // I2 : le blocage est journalisé (WARN) — critère n° 6 du prompt
        assertTrue(journal.avertissements("RegistreFermeture").any { it.contains("lente") && it.contains("délai") })
    }

    @Test
    fun `le delai borne aussi les elements non annulables`() = runTest {
        val ordre = mutableListOf<String>()
        val registre = RegistreFermeture(horloge = TimeProvider { testScheduler.currentTime })
        val critique = FermableEspion("critique", ordre, dureeTravail = ms(500))
        registre.enregistrer(critique, ms(50), annulable = false)

        val rapport = registre.fermerTout(ContexteFermeture(RaisonFermeture.Utilisateur))

        // « ne pas annuler » protège de l'ANNULATION, pas du DÉLAI (I2
        // borne la fermeture) — l'étape C choisira des délais généreux
        // pour les flush
        assertEquals(0, critique.terminees)
        val verdict = rapport.elements.single().resultat
        assertSame(ResultatFermetureElement.DelaiDepasse, verdict)
    }

    @Test
    fun `annulation de l appelant — les non annulables ferment quand meme (I7)`() = runTest {
        val ordre = mutableListOf<String>()
        val registre = RegistreFermeture(horloge = TimeProvider { testScheduler.currentTime })
        // enregistrées DANS cet ordre → fermeture inverse : bloquant PUIS critique
        val critique = FermableEspion("critique", ordre, dureeTravail = ms(5))
        val bloquant = FermableEspion("bloquant", ordre, attente = CompletableDeferred())
        registre.enregistrer(critique, ms(60_000), annulable = false)
        registre.enregistrer(bloquant, ms(60_000), annulable = true)
        val capture = AtomicReference<FermetureInterrompue?>(null)

        val appelant =
            launch {
                try {
                    registre.fermerTout(ContexteFermeture(RaisonFermeture.Utilisateur))
                } catch (interrompue: FermetureInterrompue) {
                    capture.set(interrompue)
                }
            }
        // la fermeture démarre : bloquant entre puis suspend sur le signal
        runCurrent()
        appelant.cancelAndJoin()

        // le non-annulable est allé au bout, l'annulable est resté en route
        assertEquals(1, critique.terminees)
        assertEquals(1, bloquant.appels)
        assertEquals(0, bloquant.terminees)
        val interrompue = capture.get()
        assertNotNull("la fermeture interrompue doit être relancée (règle projet)", interrompue)
        val rapport = interrompue?.rapport
        assertEquals(2, rapport?.elements?.size)
        assertEquals(1, rapport?.nbFermes)
        assertEquals(1, rapport?.nbAnnules)
        // bloquant (fermé en premier, ordre inverse) : Annulé
        assertSame(ResultatFermetureElement.Annule, rapport?.elements?.first()?.resultat)
    }

    @Test
    fun `enregistrement refuse pendant la fermeture`() = runTest {
        val ordre = mutableListOf<String>()
        val registre = RegistreFermeture(horloge = TimeProvider { testScheduler.currentTime })
        val refuses = mutableListOf<AppResult<Desenregistrement>>()
        // une ressource tente d'enregistrer une LATE pendant sa propre
        // libération : le registre doit refuser (elle ne serait fermée
        // par personne) — sans interblocage (enregistrer n'attend pas le
        // verrou de fermeture)
        val tentateur =
            object : Fermable {
                override val nom: String = "tentateur"

                override suspend fun fermer(contexte: ContexteFermeture) {
                    ordre += nom
                    refuses += registre.enregistrer(FermableEspion("tardive", ordre), ms(1_000))
                }
            }
        registre.enregistrer(tentateur, ms(1_000))

        val rapport = registre.fermerTout(ContexteFermeture(RaisonFermeture.Utilisateur))

        assertEquals(1, refuses.size)
        val echec = refuses.single()
        assertTrue(echec is AppResult.Failure)
        assertTrue((echec as AppResult.Failure).error is AppError.Session)
        assertEquals(
            AppError.SessionReason.FermetureEnCours,
            (echec.error as AppError.Session).reason,
        )
        // la « tardive » n'a été fermée par personne
        assertEquals(listOf("tentateur"), ordre)
        assertEquals(1, rapport.elements.size)
    }

    @Test
    fun `desenregistrement — la fermeture n appelle pas l element retire`() = runTest {
        val ordre = mutableListOf<String>()
        val registre = RegistreFermeture(horloge = TimeProvider { testScheduler.currentTime })
        val gardee = FermableEspion("gardee", ordre)
        val retiree = FermableEspion("retiree", ordre)
        registre.enregistrer(gardee, ms(1_000))
        val poignee = registre.enregistrer(retiree, ms(1_000))
        assertTrue(poignee is AppResult.Success)

        (poignee as AppResult.Success).value.desenregistrer()
        poignee.value.desenregistrer() // idempotente

        val rapport = registre.fermerTout(ContexteFermeture(RaisonFermeture.Utilisateur))

        assertEquals(listOf("gardee"), ordre)
        assertEquals(0, retiree.appels)
        assertEquals(1, rapport.nbFermes)
        // après la fermeture, désenregistrer reste un no-op sans erreur
        poignee.value.desenregistrer()
    }

    @Test
    fun `delai strictement positif exige`() {
        val registre = RegistreFermeture()
        assertThrows(IllegalArgumentException::class.java) {
            registre.enregistrer(FermableEspion("nulle", mutableListOf()), ms(0))
        }
    }

    @Test
    fun `resume journalise au niveau INFO (rapport complet)`() = runTest {
        val ordre = mutableListOf<String>()
        val journal = JournalEspion()
        val registre = RegistreFermeture(journal = journal, horloge = TimeProvider { testScheduler.currentTime })
        registre.enregistrer(FermableEspion("unique", ordre), ms(1_000))

        registre.fermerTout(ContexteFermeture(RaisonFermeture.Nettoyage))

        val resume = journal.informations("RegistreFermeture").single()
        assertTrue(resume.contains("1 élément(s)"))
        assertTrue(resume.contains("1 fermés"))
        assertTrue(resume.contains("Nettoyage"))
        // règle 15 : identifiants seuls — le bilan par élément (DEBUG) porte
        // le nom de la ressource, jamais un chemin ni un nom de projet
        assertTrue(journal.entrees.any { it.niveau == LogLevel.DEBUG && it.message.contains("unique") })
    }

    @Test
    fun `fermetures concurrentes — seconde attend la premiere puis referme un vide`() = runTest {
        val ordre = mutableListOf<String>()
        val registre = RegistreFermeture(horloge = TimeProvider { testScheduler.currentTime })
        val unique = FermableEspion("unique", ordre, dureeTravail = ms(50))
        registre.enregistrer(unique, ms(1_000))

        val premiere = async { registre.fermerTout(ContexteFermeture(RaisonFermeture.Utilisateur)) }
        val seconde = async { registre.fermerTout(ContexteFermeture(RaisonFermeture.Nettoyage)) }
        val rapports = listOf(premiere.await(), seconde.await())

        assertEquals(1, unique.appels)
        assertEquals(1, rapports[0].nbFermes)
        assertTrue(rapports[1].elements.isEmpty())
    }
}
