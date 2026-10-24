package jo.codeide.preview.compose

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.testing.TestDispatcherProvider
import jo.lspkotlin.api.AppelFonctionKotlin
import jo.lspkotlin.api.ArgumentAppelKotlin
import jo.lspkotlin.api.GenreArgumentAppelKotlin
import jo.lspkotlin.api.PreviewKotlin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Moteur de preview Compose (C1) sous Robolectric, transport FAUX —
 * l'orchestration sur l'horloge virtuelle : branchement immédiat,
 * debounce des frappes, diff d'identité par digeste (anti-boucle),
 * re-rendu des paramètres, état typé de l'indisponibilité.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MoteurPreviewComposeTest {
    /** Faux transport : enregistre les requêtes, répond à la demande. */
    private class FauxTransport : TransportCompose {
        var requetes: MutableList<RequeteRenduCompose> = mutableListOf()
        var echecs: Int = 0

        /** C3 — touches transmises (identité + événement). */
        var touches: MutableList<Pair<String, EvenementTactileCompose>> = mutableListOf()

        /** C3 — la session touchée existe-t-elle ? */
        var sessionTouchable: Boolean = true

        var fournitSucces: Boolean = true

        /** C3 — durée des frames Sucess (pour la cadence adaptative). */
        var dureeFrame: Long = 3

        override suspend fun rendre(requete: RequeteRenduCompose): ResultatTransportCompose {
            requetes.add(requete)
            return if (fournitSucces && echecs == 0) {
                ResultatTransportCompose.Succes(
                    bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888),
                    metriques = MetriquesRenduCompose(dureeMs = dureeFrame, nombreNoeuds = 1, largeur = 4, hauteur = 4),
                )
            } else {
                echecs = (echecs - 1).coerceAtLeast(0)
                ResultatTransportCompose.Erreur(
                    message = "processus indisponible",
                    erreurs = listOf(ErreurCompose(GenreErreurCompose.PROCESSUS_INDISPONIBLE, null, 0)),
                )
            }
        }

        override suspend fun transmettre(identite: String, evenement: EvenementTactileCompose): Boolean {
            touches.add(identite to evenement)
            return sessionTouchable
        }

        override fun close() = Unit
    }

    private fun previewTexte(nom: String, chaine: String): PreviewKotlin = PreviewKotlin(
        nom = nom,
        paquet = "com.exemple",
        debutNom = 0,
        finNom = 0,
        parametres = emptyList(),
        corps =
        listOf(
            AppelFonctionKotlin(
                nom = "Text",
                recepteur = null,
                ligne = 5,
                arguments =
                listOf(
                    ArgumentAppelKotlin(
                        nom = "text",
                        genre = GenreArgumentAppelKotlin.LITTERAL,
                        texte = "\"$chaine\"",
                    ),
                ),
                contenu = emptyList(),
            ),
        ),
    )

    @Test
    fun `brancher - rendu immédiat, une entrée par preview`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FauxTransport()
        val moteur = moteur(dispatcher, transport)
        moteur.brancher("file:///a.kt") {
            listOf(previewTexte("ApercuUn", "Un"), previewTexte("ApercuDeux", "Deux"))
        }
        advanceUntilIdle()

        val etat = moteur.etat.value
        assertTrue("état attendu RenduValide, trouvé $etat", etat is EtatPreviewCompose.RenduValide)
        etat as EtatPreviewCompose.RenduValide
        assertEquals("les DEUX previews rendues", 2, etat.rendus.size)
        assertEquals("ApercuUn", etat.rendus[0].nom)
        assertEquals("ApercuDeux", etat.rendus[1].nom)
        assertEquals("l'indice suit l'ordre du document", 1, etat.rendus[1].index)
        assertTrue(etat.rendus[0].bitmap != null)
        assertEquals(2, transport.requetes.size)
        assertEquals("file:///a.kt#ApercuUn#0", transport.requetes[0].identite)
        moteur.close()
    }

    @Test
    fun `frappes - debounce 400 ms, fournisseur tiré APRÈS`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FauxTransport()
        var tirages: Int = 0
        val moteur = moteur(dispatcher, transport)
        moteur.brancher("file:///a.kt") {
            tirages += 1
            listOf(previewTexte("Apercu", "Premier"))
        }
        advanceUntilIdle()
        assertEquals(1, tirages)
        // TROIS frappes rapprochées = UN seul parse + UN seul rendu
        // (le texte est tiré à la demande, jamais par frappe). PAS de
        // advanceUntilIdle avant l'assertion : il franchirait LE
        // debounce lui-même.
        moteur.soumettre()
        moteur.soumettre()
        moteur.soumettre()
        advanceTimeBy(100)
        assertEquals("rien avant le debounce", 1, tirages)
        advanceTimeBy(400)
        advanceUntilIdle()
        assertEquals("le fournisseur est tiré APRÈS le debounce", 2, tirages)
        // Le contenu est INCHANGÉ : le diff d'identité SAUVE l'appel
        // Binder (requetes reste 1) — c'est l'OBJET du test dédié
        // « digeste inchangé » ; ici n'est éprouvé que le MÉCANISME
        // du debounce (parse différé, jamais par frappe).
        assertEquals("contenu inchangé → le rendu est reporté", 1, transport.requetes.size)
        moteur.close()
    }

    @Test
    fun `digeste inchangé - le rendu précédent est REPORTÉ (zéro appel)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FauxTransport()
        val moteur = moteur(dispatcher, transport)
        moteur.brancher("file:///a.kt") { listOf(previewTexte("Apercu", "Pareil")) }
        advanceUntilIdle()
        assertEquals(1, transport.requetes.size)
        // Le même contenu re-soumis (bascule d'onglet retour, rafraîchissement) :
        // le digeste est IDENTIQUE, la session vivante garde tout.
        moteur.soumettreImmediat()
        advanceUntilIdle()
        assertEquals("le diff d'identité SAUVE l'appel Binder", 1, transport.requetes.size)
        val etat = moteur.etat.value as EtatPreviewCompose.RenduValide
        assertTrue("le rendu précédent reste disponible", etat.rendus[0].bitmap != null)
        moteur.close()
    }

    @Test
    fun `contenu changé - re-rendu de la preview éditée`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FauxTransport()
        var chaine = "Avant"
        val moteur = moteur(dispatcher, transport)
        moteur.brancher("file:///a.kt") { listOf(previewTexte("Apercu", chaine)) }
        advanceUntilIdle()
        assertEquals(1, transport.requetes.size)
        chaine = "Après (édité)"
        moteur.soumettreImmediat()
        advanceUntilIdle()
        assertEquals("le nouveau digeste traverse", 2, transport.requetes.size)
        assertTrue(moteur.etat.value is EtatPreviewCompose.RenduValide)
        moteur.close()
    }

    @Test
    fun `paramètre nuit - re-rendu complet malgré le digeste inchangé`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FauxTransport()
        val moteur = moteur(dispatcher, transport)
        moteur.brancher("file:///a.kt") { listOf(previewTexte("Apercu", "Pareil")) }
        advanceUntilIdle()
        assertEquals(1, transport.requetes.size)
        moteur.definirNuit(true)
        advanceUntilIdle()
        assertEquals("le régime nuit change le rendu (re-theme)", 2, transport.requetes.size)
        assertTrue(transport.requetes[1].nuit)
        moteur.close()
    }

    @Test
    fun `plus aucune preview - signal discret, les rendus restent`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FauxTransport()
        var vivantes = true
        val moteur = moteur(dispatcher, transport)
        moteur.brancher("file:///a.kt") {
            if (vivantes) listOf(previewTexte("Apercu", "X")) else emptyList()
        }
        advanceUntilIdle()
        assertTrue(moteur.etat.value is EtatPreviewCompose.RenduValide)
        // L'utilisateur efface sa preview (frappe en cours) : signal
        // discret — le DERNIER rendu reste affiché (contrat).
        vivantes = false
        moteur.soumettreImmediat()
        advanceUntilIdle()
        assertTrue(
            "le dernier rendu valide RESTE (contrat jamais de carte vide)",
            moteur.etat.value is EtatPreviewCompose.RenduValide,
        )
        moteur.close()
    }

    @Test
    fun `erreur de transport - entrée typée sans bitmap, pas d'exception`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport =
            FauxTransport().apply {
                echecs = 1
            }
        val moteur = moteur(dispatcher, transport)
        moteur.brancher("file:///a.kt") { listOf(previewTexte("Apercu", "X")) }
        advanceUntilIdle()
        val etat = moteur.etat.value
        assertTrue("état RenduValide (découverte réussie), trouvé $etat", etat is EtatPreviewCompose.RenduValide)
        etat as EtatPreviewCompose.RenduValide
        assertEquals("bitmap absent sur l'échec local", null, etat.rendus[0].bitmap)
        assertEquals("processus indisponible", etat.rendus[0].messageErreur)
        assertEquals(
            GenreErreurCompose.PROCESSUS_INDISPONIBLE,
            etat.rendus[0]
                .erreurs
                .last()
                .genre,
        )
        moteur.close()
    }

    @Test
    fun `fournisseur hostile - ErreurRendu défensif, le moteur survit`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FauxTransport()
        val moteur = moteur(dispatcher, transport)
        moteur.brancher("file:///a.kt") { throw IllegalStateException("parse hostile") }
        advanceUntilIdle()
        val etat = moteur.etat.value
        assertTrue("état défensif ErreurRendu, trouvé $etat", etat is EtatPreviewCompose.ErreurRendu)
        moteur.close()
    }

    // ── C2 : le PROJET suit la requête (dossier res, langue, thème) ──

    @Test
    fun `definirProjet - un CHANGEMENT re-rend malgré le digeste inchangé`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FauxTransport()
        val moteur = moteur(dispatcher, transport)
        moteur.brancher("file:///a.kt") { listOf(previewTexte("Apercu", "Un")) }
        advanceUntilIdle()
        assertEquals(1, transport.requetes.size)
        assertEquals(null, transport.requetes[0].dossierRes)

        // Le PROJET arrive APRÈS le premier rendu : même document, même
        // digeste — mais les paramètres ONT changé : re-rendu COMPLET
        // (miroir definirNuit/definirProfil du frère XML).
        moteur.definirProjet("/proj/app/src/main/res", "fr", "Theme.MonApp")
        advanceUntilIdle()
        assertEquals("un changement de projet RE-REND", 2, transport.requetes.size)
        assertEquals("/proj/app/src/main/res", transport.requetes[1].dossierRes)
        assertEquals("fr", transport.requetes[1].langue)
        assertEquals("Theme.MonApp", transport.requetes[1].themeName)

        // Le MÊME projet re-posé : rien (anti-boucle honnête).
        moteur.definirProjet("/proj/app/src/main/res", "fr", "Theme.MonApp")
        advanceUntilIdle()
        assertEquals("projet inchangé → aucun re-rendu", 2, transport.requetes.size)

        // Retour SANS projet : re-rendu vers les défaults du runtime.
        moteur.definirProjet(null)
        advanceUntilIdle()
        assertEquals(3, transport.requetes.size)
        assertEquals(null, transport.requetes[2].dossierRes)
        moteur.close()
    }

    @Test
    fun `geometries C2 - les noeuds du transport nourrissent l'etat par onglet`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport =
            object : TransportCompose by FauxTransport() {
                override suspend fun rendre(requete: RequeteRenduCompose): ResultatTransportCompose =
                    ResultatTransportCompose.Succes(
                        bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888),
                        metriques = MetriquesRenduCompose(1L, 1, 4, 4),
                        noeuds = listOf(
                            InfoNoeudCompose(
                                ligne = 5,
                                genre = GenreNoeudCompose.TEXTE,
                                gauche = 0,
                                haut = 0,
                                droite = 4,
                                bas = 4,
                            ),
                        ),
                    )
            }
        val moteur = moteur(dispatcher, transport)
        moteur.brancher("file:///a.kt") { listOf(previewTexte("Apercu", "Un")) }
        advanceUntilIdle()
        val etat = moteur.etat.value as EtatPreviewCompose.RenduValide
        assertEquals("les géométries traversent l'état", 1, etat.rendus.single().noeuds.size)
        assertEquals(5, etat.rendus.single().noeuds.single().ligne)
        moteur.close()
    }

    // ── C3 : l'horloge interactive et le forwarding ─────────────────

    @Test
    fun `C3 horloge - le pompier REJOUE la requete de l identite a cadence`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FauxTransport()
        val moteur = moteur(dispatcher, transport)
        moteur.brancher("file:///a.kt") { listOf(previewTexte("Apercu", "Un")) }
        advanceUntilIdle()
        val apresRendu = transport.requetes.size
        assertEquals(1, apresRendu)

        // Carte OUVERTE sur l'onglet 0 : le pompier re-rend la MEME
        // requete (digeste inchangé POURTOUANT rendu — c'est l'HORLOGE
        // qui avance, PREVIEW.md §4.4 C3), frames à ~33 ms.
        moteur.definirInteractif("file:///a.kt#Apercu#0", 0)
        // AVANCE BORNÉE uniquement : le pompier armé replanifie SANS FIN,
        // advanceUntilIdle ne reviendrait JAMAIS (c'est le contrat même
        // d'une horloge vivante).
        advanceTimeBy(33L * 5 + 10)
        assertTrue("le pompier a rejoue la requete", transport.requetes.size > apresRendu + 3)
        assertEquals("la MEME requete est rejouee", "file:///a.kt#Apercu#0", transport.requetes.last().identite)
        val etat = moteur.etat.value as EtatPreviewCompose.RenduValide
        assertTrue("l'etat suit les frames", etat.rendus[0].bitmap != null)
        moteur.close()
        advanceUntilIdle()
    }

    @Test
    fun `C3 horloge - definirInteractif null ETEINT le pompier`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FauxTransport()
        val moteur = moteur(dispatcher, transport)
        moteur.brancher("file:///a.kt") { listOf(previewTexte("Apercu", "Un")) }
        advanceUntilIdle()
        moteur.definirInteractif("file:///a.kt#Apercu#0", 0)
        advanceTimeBy(200)
        val eteint = transport.requetes.size
        moteur.definirInteractif(null, 0)
        advanceTimeBy(400)
        assertEquals("plus AUCUN appel apres l'extinction", eteint, transport.requetes.size)
        moteur.close()
        advanceUntilIdle()
    }

    @Test
    fun `C3 cadence - une frame LENTE ralentit le pompier (degradation honnete)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val rapide = FauxTransport().apply { dureeFrame = 1 }
        val lent = FauxTransport().apply { dureeFrame = 120 }
        val moteurRapide = moteur(dispatcher, rapide)
        moteurRapide.brancher("file:///a.kt") { listOf(previewTexte("Apercu", "Un")) }
        advanceUntilIdle()
        moteurRapide.definirInteractif("file:///a.kt#Apercu#0", 0)
        advanceTimeBy(1000)
        moteurRapide.close()

        val moteurLent = moteur(dispatcher, lent)
        moteurLent.brancher("file:///a.kt") { listOf(previewTexte("Apercu", "Un")) }
        advanceUntilIdle()
        moteurLent.definirInteractif("file:///a.kt#Apercu#0", 0)
        advanceTimeBy(1000)
        moteurLent.close()

        // Le pompier rapide tient ~30 i/s (≈ 30 frames/s de fenêtre) ; le
        // pompier lent DÉGRADE (33 → 66 → 132 → 250 ms de palier) : il
        // rende MOINS de frames sur la MÊME fenêtre — jamais de sautes
        // silencieuses, la cadence suit le budget mesuré.
        assertTrue(
            "frames lentes < frames rapides sur 1 s (${lent.requetes.size} vs ${rapide.requetes.size})",
            lent.requetes.size < rapide.requetes.size,
        )
    }

    @Test
    fun `C3 horloge - trois frames ECHOUEES eteignent le pompier, le dernier rendu reste`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FauxTransport()
        val moteur = moteur(dispatcher, transport)
        moteur.brancher("file:///a.kt") { listOf(previewTexte("Apercu", "Un")) }
        advanceUntilIdle()
        // Le rendu INITIAL réussit : l'onglet porte un bitmap. Les frames
        // du pompier échouent TOUTES ensuite (processus :preview mort).
        transport.echecs = Int.MAX_VALUE
        val etatInitial = moteur.etat.value as EtatPreviewCompose.RenduValide
        assertTrue(etatInitial.rendus[0].bitmap != null)

        moteur.definirInteractif("file:///a.kt#Apercu#0", 0)
        advanceTimeBy(2000)
        advanceUntilIdle()
        val eteint = transport.requetes.size
        advanceTimeBy(600)
        advanceUntilIdle()
        assertEquals("le pompier s'est eteint apres ses 3 echecs", eteint, transport.requetes.size)
        val etat = moteur.etat.value as EtatPreviewCompose.RenduValide
        assertTrue("le dernier rendu VALIDE reste affiche", etat.rendus[0].bitmap != null)
        moteur.close()
    }

    @Test
    fun `C3 forwarding - transmettreTouch rejoint la session interactive, no-op sans arment`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FauxTransport()
        val moteur = moteur(dispatcher, transport)
        moteur.brancher("file:///a.kt") { listOf(previewTexte("Apercu", "Un")) }
        advanceUntilIdle()

        // Sans session armée : la touche ne part JAMAIS (la carte ne
        // transmet que ce qu'elle a armé).
        moteur.transmettreTouch(EvenementTactileCompose(0, 10f, 10f, 1L, 1L, 1f))
        advanceUntilIdle()
        assertTrue(transport.touches.isEmpty())

        moteur.definirInteractif("file:///a.kt#Apercu#0", 0)
        moteur.transmettreTouch(EvenementTactileCompose(0, 10f, 10f, 1L, 5L, 1f))
        // runCurrent : le relais part MAINTENANT ; le pompier armé garde
        // sa prochaine frame en attente (jamais d'idle infini).
        runCurrent()
        assertEquals(1, transport.touches.size)
        assertEquals("file:///a.kt#Apercu#0", transport.touches.single().first)
        assertEquals(10f, transport.touches.single().second.xPx, 0.01f)
        assertEquals(5L, transport.touches.single().second.eventTimeMs)
        moteur.close()
    }

    private fun moteur(dispatcher: TestDispatcher, transport: TransportCompose): MoteurPreviewCompose {
        val contexte: Context = ApplicationProvider.getApplicationContext()
        return MoteurPreviewCompose(
            contexte = contexte,
            dispatchers = TestDispatcherProvider(dispatcher),
            journal = null,
            scope = TestScope(dispatcher),
            transport = transport,
        )
    }
}
