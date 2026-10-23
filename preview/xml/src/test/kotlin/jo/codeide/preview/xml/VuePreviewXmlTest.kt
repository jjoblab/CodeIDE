package jo.codeide.preview.xml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.DragEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Vue de preview sous Robolectric (PE1) — fumée de dessin : les états ne
 * lèvent pas, le rendu se dessine dans un canvas de sortie.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class VuePreviewXmlTest {
    /**
     * PE11i : le moteur ne dessine AUCUNE couleur sans jetons injectés
     * (ADR 0134 D7) — le helper délègue à la fixture partagée
     * (`FixtureVuePreviewXml` — découpage detekt LargeClass, zéro
     * exemption : [VuePreviewXmlRedimensionnementTest] consomme le MÊME
     * jeu arbitraire DISTINCT ; la parité maquette ↔ ressources vit
     * dans `JetonsCalquesTest` côté feature).
     */
    private fun vue(): VuePreviewXml = vueDeTest()

    @Test
    fun `v0_166_4 - le zoom survit a un nouveau rendu de meme planche`() {
        // Retour propriétaire 2026-10-22 : « le zoom pendant l'édition
        // n'est pas respecté » — AVANT, chaque rendu repassait par
        // l'ajustement (fit) et dézoomait la planche sous les doigts.
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 512)
        // PREMIER rendu : ajustement (comportement inchangé).
        vue.afficherRendu(Bitmap.createBitmap(108, 234, Bitmap.Config.ARGB_8888))
        val echelleAjustee = vue.echelleCourante
        assertTrue("premier rendu ajusté", echelleAjustee > 0f)
        // L'utilisateur zoome.
        vue.zoomer(2f)
        val echelleUtilisateur = vue.echelleCourante
        assertTrue("l'utilisateur a zoomé", echelleUtilisateur > echelleAjustee)
        // NOUVEAU rendu de MÊMES dimensions (le cas de TOUTE édition —
        // la taille vient du profil) : le zoom doit rester.
        vue.afficherRendu(Bitmap.createBitmap(108, 234, Bitmap.Config.ARGB_8888))
        assertEquals(
            "l'édition ne dézoome plus (zoom préservé)",
            echelleUtilisateur,
            vue.echelleCourante,
            0.0001f,
        )
    }

    @Test
    fun `v0_166_4 - le zoom survit aussi quand la planche est retaillee`() {
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 512)
        vue.afficherRendu(Bitmap.createBitmap(108, 234, Bitmap.Config.ARGB_8888))
        vue.zoomer(2f)
        val echelleUtilisateur = vue.echelleCourante
        // Changement de profil/paysage : AUTRES dimensions — le zoom
        // reste, seul le décalage se ré-ancre.
        vue.afficherRendu(Bitmap.createBitmap(234, 108, Bitmap.Config.ARGB_8888))
        assertEquals(
            "zoom conservé sur planche retaillée",
            echelleUtilisateur,
            vue.echelleCourante,
            0.0001f,
        )
    }

    @Test
    fun `v0_166_4 - le premier rendu s ajuste toujours`() {
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 512)
        // Aucun bitmap avant : le PREMIER rendu s'ajuste (fit) —
        // l'arrivée sur l'aperçu reste lisible.
        vue.afficherRendu(Bitmap.createBitmap(108, 234, Bitmap.Config.ARGB_8888))
        assertEquals(512f / 234f, vue.echelleCourante, 0.0001f)
    }

    @Test
    fun `v0_166_5 - le zoom survit au resize du canvas par la feuille`() {
        // Retour propriétaire prolongé : la FEUILLE inférieure retire le
        // bas du canvas à chaque état (repli 78 dp, déploiement jusqu'à
        // l'appbar) — le redimensionnement ne doit pas DÉZOOMER une
        // planche déjà posée, seul le pan se re-borne (bornerPan).
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 512)
        vue.afficherRendu(Bitmap.createBitmap(108, 234, Bitmap.Config.ARGB_8888))
        vue.zoomer(2f)
        val echelleUtilisateur = vue.echelleCourante
        // Le canvas rétrécit (la feuille se déploie)…
        vue.measure(512, 256)
        vue.layout(0, 0, 512, 256)
        assertEquals(
            "le resize du canvas ne dézoome plus",
            echelleUtilisateur,
            vue.echelleCourante,
            0.0001f,
        )
        // …puis se rouvre (la feuille se replie) : l'échelle tient toujours.
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 512)
        assertEquals(echelleUtilisateur, vue.echelleCourante, 0.0001f)
    }

    @Test
    fun `v0_166_5 - le premier dimensionnement d une planche posee s ajuste`() {
        // Le bitmap arrive AVANT le premier layout (vue non mesurée) : le
        // report d'ajustement doit FIT à la première mesure (comportement
        // inchangé par le gardien plancheAjustee).
        val vue = vue()
        vue.afficherRendu(Bitmap.createBitmap(108, 234, Bitmap.Config.ARGB_8888))
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 512)
        assertEquals(512f / 234f, vue.echelleCourante, 0.0001f)
    }

    @Test
    fun `v0_173_0 - le zoom survit a une edition pendant que la feuille totale ecrase le canvas`() {
        // Retour propriétaire : « quand le bottomsheet est expand et que
        // j'édite, le rendu devient petit (de zoomé) » — la feuille TOTALE
        // écrase le canvas à hauteur NULLE, l'édition rend un NOUVEAU
        // bitmap PENDANT que la vue n'est pas mesurée : AVANT, la branche
        // de repli pulvérisait l'échelle (ECHELLE_REPLI = 0,1×) — replier
        // montrait un rendu minuscule. La planche DÉJÀ POSÉE garde son
        // zoom et son pan, la vue se redresse au layout suivant.
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 512)
        vue.afficherRendu(Bitmap.createBitmap(108, 234, Bitmap.Config.ARGB_8888))
        vue.zoomer(2f)
        val echelleUtilisateur = vue.echelleCourante
        // La feuille se déploie TOTALEMENT : le canvas tombe à hauteur 0.
        vue.measure(512, 0)
        vue.layout(0, 0, 512, 0)
        // L'utilisateur édite un attribut : le NOUVEAU rendu (mêmes
        // dimensions de planche) arrive sur la vue non mesurée.
        vue.afficherRendu(Bitmap.createBitmap(108, 234, Bitmap.Config.ARGB_8888))
        assertEquals(
            "l'édition feuille totale ne pulvérise plus le zoom",
            echelleUtilisateur,
            vue.echelleCourante,
            0.0001f,
        )
        // La feuille se replie : le canvas revient — le zoom tient
        // toujours (pas de re-fit, le pan seul se re-borne).
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 512)
        assertEquals(
            "le rendu réapparaît avec son zoom d'origine",
            echelleUtilisateur,
            vue.echelleCourante,
            0.0001f,
        )
    }

    @Test
    fun `v0_173_0 - la premiere planche avant mesure garde le repli`() {
        // Miroir du gardien : un bitmap arrivant AVANT toute mesure (jamais
        // posée) prend toujours l'échelle de repli — la branche ne la garde
        // QUE pour les planches déjà ajustées.
        val vue = vue()
        vue.afficherRendu(Bitmap.createBitmap(108, 234, Bitmap.Config.ARGB_8888))
        assertEquals(VuePreviewXml.ECHELLE_REPLI, vue.echelleCourante, 0.0001f)
    }

    @Test
    fun `mesure layout dessin - aucun lever`() {
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 1024)
        val sortie = Bitmap.createBitmap(512, 1024, Bitmap.Config.ARGB_8888)
        vue.draw(Canvas(sortie))
        assertNotNull(sortie)
    }

    @Test
    fun `etats affiches - rendu reinitialisation - aucun lever`() {
        val vue = vue()
        vue.measure(256, 256)
        vue.layout(0, 0, 256, 256)
        val sortie = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sortie)

        // PE11i (D6) : l'erreur et la frappe ne passent plus par la vue
        // (le bandeau est une vue du chrome) — rendu et réinitialisation
        // restent les seuls états du moteur.
        vue.afficherRendu(Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888))
        vue.draw(canvas)
        vue.reinitialiser()
        vue.draw(canvas)
    }

    /** Arbre minimal : racine(0) → [texte(1), bouton(2)] — bounds séparées. */
    private fun vues(): List<InfoVueRendu> = arbreVuesDeTest()

    @Test
    fun `calques pe6a - selection poignees etiquette parent freres - aucun lever`() {
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 1024)
        val sortie = Bitmap.createBitmap(512, 1024, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sortie)
        // PE6a : rendu AVEC la densité du profil — le calque surligne la
        // vue 1 (frères = 2, parent = 0), l'étiquette porte nom ·
        // dimensions. PE10a : durée et vues partent à la pastille-VUE du
        // chrome (le Canvas du moteur ne les dessine plus).
        vue.afficherRendu(
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888),
            vuesRendues = vues(),
            densiteDpiProfil = 440,
        )
        vue.surlignerVue(1)
        vue.draw(canvas)
        // Zoom et pan en plein calque : les tracés restent écran constants.
        vue.surlignerVue(0)
        vue.draw(canvas)
        vue.surlignerVue(-1)
        vue.draw(canvas)
    }

    @Test
    fun `calques pe6a - metriques par defaut - aucun lever`() {
        val vue = vue()
        vue.measure(256, 256)
        vue.layout(0, 0, 256, 256)
        val sortie = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        // Ancien patron (bitmap seul — tests PE1 inchangés) : pas de
        // calque (aucune sélection).
        vue.afficherRendu(Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888))
        vue.draw(Canvas(sortie))
    }

    // ── Mode d'affichage (PE10d/e — Design, Plan, Les deux) ──

    @Test
    fun `modes d'affichage - plan et les deux se dessinent sans lever`() {
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 1024)
        val sortie = Bitmap.createBitmap(512, 1024, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sortie)
        vue.afficherRendu(
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888),
            vuesRendues = vues(),
            densiteDpiProfil = 440,
        )
        vue.surlignerVue(1)
        // PLAN : le squelette remplace le bitmap (miroir `.screen.bp`).
        vue.modeAffichage = ModeApercu.PLAN
        vue.draw(canvas)
        // LES_DEUX : deux planches côte à côte, le fit double la largeur.
        vue.modeAffichage = ModeApercu.LES_DEUX
        vue.draw(canvas)
        // Le zoom reste borné au contenu COMPOSÉ.
        vue.zoomer(1.25f)
        vue.draw(canvas)
        // Retour Design : l'échelle se recalcule sur UNE planche.
        vue.modeAffichage = ModeApercu.DESIGN
        vue.draw(canvas)
    }

    // ── Zoom exposé (PE6b — boutons + / 100 % / ajuster / − de la maquette) ──

    @Test
    fun `zoomer - facteur amplifie l'echelle et notifie le HUD`() {
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 1024)
        // Bitmap deux fois plus petit que la vue : le fit ≈ 2,56 et le
        // zoom ×1,25 (≈ 3,2) reste SOUS le plafond 6×.
        vue.afficherRendu(Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888))
        var notifiee = 0f
        vue.auChangementEchelle = { echelle -> notifiee = echelle }
        val avant = vue.echelleCourante
        vue.zoomer(1.25f)
        assertEquals(avant * 1.25f, vue.echelleCourante, 0.001f)
        assertEquals(vue.echelleCourante, notifiee, 0.0001f)
    }

    @Test
    fun `zoomer - facteur negatif ou sans bitmap - aucun effet`() {
        val vue = vue()
        vue.zoomer(2f) // pas de bitmap : l'échelle initiale (1f) ne bouge pas.
        assertEquals(1f, vue.echelleCourante, 0.0001f)
        vue.measure(256, 256)
        vue.layout(0, 0, 256, 256)
        vue.afficherRendu(Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888))
        val avant = vue.echelleCourante
        vue.zoomer(-2f)
        assertEquals(avant, vue.echelleCourante, 0.0001f)
    }

    @Test
    fun `zoomer - borne superieure plafonnee a 6x`() {
        val vue = vue()
        vue.measure(256, 256)
        vue.layout(0, 0, 256, 256)
        vue.afficherRendu(Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888))
        repeat(20) { vue.zoomer(1.25f) }
        assertEquals(6f, vue.echelleCourante, 0.0001f)
    }

    @Test
    fun `zoomer inverse - revient a l'echelle d'origine (miroir zPct)`() {
        val vue = vue()
        vue.measure(256, 256)
        vue.layout(0, 0, 256, 256)
        vue.afficherRendu(Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888))
        val origine = vue.echelleCourante
        vue.zoomer(2f)
        // Le bouton « 100 % » : zoom du facteur inverse de l'échelle courante.
        vue.zoomer(1f / vue.echelleCourante)
        assertEquals(1f, vue.echelleCourante, 0.0001f)
        vue.ajuster()
        assertEquals(origine, vue.echelleCourante, 0.001f)
    }

    @Test
    fun `ajuster sans bitmap - aucun lever`() {
        val vue = vue()
        vue.ajuster() // pas de bitmap : ne lève pas.
    }

    // ── Calques à coches (PE6b) + pastilles de problèmes (PE6c) ──

    @Test
    fun `calques pe6b-c - tous actives avec problemes - aucun lever`() {
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 1024)
        val sortie = Bitmap.createBitmap(512, 1024, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sortie)
        vue.grilleAffichee = true
        vue.calquesBornes = true
        vue.calquesContraintes = true
        vue.calquesMargesPadding = true
        vue.calquesProblemes = true
        vue.afficherRendu(
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888),
            vuesRendues = vues(),
            densiteDpiProfil = 440,
        )
        vue.surlignerVue(2)
        vue.afficherProblemes(
            listOf(
                ProblemesPreview.ProblemeApercu(
                    2,
                    ProblemesPreview.Severite.ERREUR,
                    ProblemesPreview.GenreProbleme.CLASSE_ABSENTE,
                ),
                ProblemesPreview.ProblemeApercu(
                    2,
                    ProblemesPreview.Severite.AVERTISSEMENT,
                    ProblemesPreview.GenreProbleme.CIBLE_TACTILE,
                ),
                ProblemesPreview.ProblemeApercu(
                    1,
                    ProblemesPreview.Severite.AVERTISSEMENT,
                    ProblemesPreview.GenreProbleme.CIBLE_TACTILE,
                ),
            ),
        )
        vue.draw(canvas)
        // Désactivation en plein dessin : aucun lever non plus.
        vue.calquesProblemes = false
        vue.calquesMargesPadding = false
        vue.draw(canvas)
    }

    @Test
    fun `calque parent freres - desactive - aucun lever`() {
        val vue = vue()
        vue.measure(256, 256)
        vue.layout(0, 0, 256, 256)
        vue.calquesParentFreres = false
        vue.afficherRendu(
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888),
            vuesRendues = vues(),
            densiteDpiProfil = 440,
        )
        vue.surlignerVue(1)
        vue.draw(Canvas(Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)))
    }

    // ── PE7b — zones de dépôt et glisser-déposer natif ──

    @Test
    fun `drag pe7b - started accepte le contenu, drop remonte le depot, ended remet a zero`() {
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 1024)
        // La racine doit être un CONTENEUR (pe7b : cible de dépôt).
        val arbre =
            listOf(
                InfoVueRendu(0, 0, 100, 200, "FrameLayout", 0, 40, -1, 0, classe = "FrameLayout", conteneur = true),
                InfoVueRendu(10, 80, 90, 120, "Button @+id/envoyer", 24, 38, 0, 1, classe = "Button"),
            )
        vue.afficherRendu(
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888),
            vuesRendues = arbre,
            densiteDpiProfil = 440,
        )
        val contenu = ZonesDepot.ContenuDepot(extraitXml = "<Button />", nomAffiche = "Button")
        // STARTED : accepté, les zones se révèlent.
        assertTrue(vue.traiterEvenementDrag(DragEvent.ACTION_DRAG_STARTED, 0f, 0f, contenu))
        assertTrue(vue.zonesDepotAffichees)
        // LOCATION : la cible suit le point (30, 100 → Button de l'arbre).
        vue.traiterEvenementDrag(DragEvent.ACTION_DRAG_LOCATION, 30f, 100f, contenu)
        assertNotNull(vue.cibleDepot)
        // DROP : le contenu remonte avec les coordonnées BITMAP (zoom/pan inversés).
        var recu: ZonesDepot.ContenuDepot? = null
        var pointX = Float.NaN
        var pointY = Float.NaN
        vue.auDepot = { c, x, y ->
            recu = c
            pointX = x
            pointY = y
        }
        assertTrue(vue.traiterEvenementDrag(DragEvent.ACTION_DROP, 30f, 100f, contenu))
        assertEquals("Button", recu?.nomAffiche)
        assertEquals(30f, pointX * vue.echelleCourante, 0.05f)
        assertEquals(100f, pointY * vue.echelleCourante, 0.05f)
        // ENDED : tout remise à zéro.
        assertTrue(vue.traiterEvenementDrag(DragEvent.ACTION_DRAG_ENDED, 0f, 0f, contenu))
        assertFalse(vue.zonesDepotAffichees)
        assertNull(vue.cibleDepot)
    }

    @Test
    fun `drag pe7b - localState etranger refuse, sans bitmap refuse`() {
        val vue = vue()
        // LocalState étranger (un autre drag) : la vue passe au travers.
        assertFalse(vue.traiterEvenementDrag(DragEvent.ACTION_DRAG_STARTED, 0f, 0f, "texte"))
        // Sans rendu affiché : pas de zones de dépôt.
        assertFalse(
            vue.traiterEvenementDrag(
                DragEvent.ACTION_DRAG_STARTED,
                0f,
                0f,
                ZonesDepot.ContenuDepot("<Button />", "Button"),
            ),
        )
        assertFalse(vue.zonesDepotAffichees)
    }

    @Test
    fun `drag pe7b - drop hors cible ne remonte rien, exited efface la cible`() {
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 1024)
        vue.afficherRendu(
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888),
            vuesRendues = vues(),
            densiteDpiProfil = 440,
        )
        val contenu = ZonesDepot.ContenuDepot(extraitXml = "<Button />", nomAffiche = "Button")
        assertTrue(vue.traiterEvenementDrag(DragEvent.ACTION_DRAG_STARTED, 0f, 0f, contenu))
        var appelle = false
        vue.auDepot = { _, _, _ -> appelle = true }
        // Hors de l'écran rendu (au-delà des bounds de la racine) : cible nulle.
        vue.traiterEvenementDrag(DragEvent.ACTION_DRAG_LOCATION, 511f, 1023f, contenu)
        // La racine couvre tout l'écran (0..100×200)… l'écran SIMULÉ est
        // plus petit que la vue : le point (511, 1023) en bitmap = (99,9;199,8)
        // reste DANS la racine. On passe par EXITED pour effacer.
        vue.traiterEvenementDrag(DragEvent.ACTION_DRAG_EXITED, 0f, 0f, contenu)
        assertNull(vue.cibleDepot)
        assertTrue(vue.traiterEvenementDrag(DragEvent.ACTION_DROP, 30f, 100f, contenu))
        assertFalse(appelle)
    }

    @Test
    fun `zones de depot pe7b - dessin sans lever avec cible et pill`() {
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 1024)
        val sortie = Bitmap.createBitmap(512, 1024, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sortie)
        vue.afficherRendu(
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888),
            vuesRendues = vues(),
            densiteDpiProfil = 440,
        )
        val contenu = ZonesDepot.ContenuDepot(extraitXml = "<Button />", nomAffiche = "Button")
        assertTrue(vue.traiterEvenementDrag(DragEvent.ACTION_DRAG_STARTED, 0f, 0f, contenu))
        vue.traiterEvenementDrag(DragEvent.ACTION_DRAG_LOCATION, 30f, 100f, contenu)
        vue.draw(canvas)
        // Exited puis re-dessin : les zones restent, la pill disparaît.
        vue.traiterEvenementDrag(DragEvent.ACTION_DRAG_EXITED, 0f, 0f, contenu)
        vue.draw(canvas)
        vue.traiterEvenementDrag(DragEvent.ACTION_DRAG_ENDED, 0f, 0f, contenu)
        vue.draw(canvas)
    }

    // ── Redimensionnement par poignée (PE11l — demandé par le propriétaire) ──

    @Test
    fun `plan seul - la planche vit a l origine, les deux la decale`() {
        // PE11l (bug rapporté : « corriger le rendu plan ») : le fit cadre
        // UNE planche en mode Plan — dessinée au-delà de sa propre largeur
        // elle sortait du cadre (écran vide derrière le damier).
        assertEquals(0, CalculsVuePreview.decalagePlancheAffichage(ModeApercu.PLAN, 100, 40))
        assertEquals(140, CalculsVuePreview.decalagePlancheAffichage(ModeApercu.LES_DEUX, 100, 40))
        assertEquals(0, CalculsVuePreview.decalagePlancheAffichage(ModeApercu.DESIGN, 100, 40))
    }
}
