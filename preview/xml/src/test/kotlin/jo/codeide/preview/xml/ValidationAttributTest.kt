package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrat du VALIDATEUR D'ATTRIBUTS (PE11e — couleurs ; PE11f —
 * ressources ; PE11g-contrôles — curseur et ratio) : un test PAR RÈGLE
 * des `vCol`/`vRes`/`sld`/`ratio` de la maquette, cas limites inclus
 * (brief PE10a/PE11c : « un test par ligne du tableau et par
 * validateur »). Le module est PUR — JUnit seul, aucun Android.
 */
public class ValidationAttributTest {
    /** Les couleurs connues du faux projet. */
    private val couleurs = setOf("primary", "surface")

    /** Les drawables connus du faux projet. */
    private val drawables = setOf("logo")

    @Test
    public fun `le vide est valide - il retirera l attribut a l ecriture`() {
        // Miroir `vCol(!x)` → {} : le VIDE n'est jamais une erreur.
        assertEquals(ValidationAttribut.Resultat.Valide, ValidationAttribut.couleur(null, couleurs, drawables))
        assertEquals(ValidationAttribut.Resultat.Valide, ValidationAttribut.couleur("", couleurs, drawables))
    }

    @Test
    public fun `les formes hexadr decimales sont valides`() {
        // Miroir `colorCss` : #RGB, #ARGB, #RRGGBB, #AARRGGBB, casse libre.
        for (hex in listOf("#F00", "#0F0A", "#FF0000", "#80FF0000", "#fF3aB7")) {
            assertEquals(
                "hex $hex",
                ValidationAttribut.Resultat.Valide,
                ValidationAttribut.couleur(hex, couleurs, drawables),
            )
        }
    }

    @Test
    public fun `un hexa malforme est une erreur`() {
        // 2, 5 ou 7 chiffres, caractère hors hexa : la maquette refuse.
        for (hex in listOf("#FF", "#FFFFF", "#FFFFFFF", "#GGG", "#FF00ZZ", "#")) {
            assertEquals(
                "hex $hex",
                ValidationAttribut.Resultat.Erreur(ValidationAttribut.GenreMessage.COULEUR_INVALIDE),
                ValidationAttribut.couleur(hex, couleurs, drawables),
            )
        }
    }

    @Test
    public fun `un color du projet est valide`() {
        assertEquals(
            ValidationAttribut.Resultat.Valide,
            ValidationAttribut.couleur("@color/primary", couleurs, drawables),
        )
    }

    @Test
    public fun `un color introuvable est un avertissement qui ecrit quand meme`() {
        // Miroir `{warn:'@color introuvable…'}` — la valeur s'ÉCRIT.
        assertEquals(
            ValidationAttribut.Resultat.Avertissement(ValidationAttribut.GenreMessage.COULEUR_INTROUVABLE),
            ValidationAttribut.couleur("@color/absente", couleurs, drawables),
        )
    }

    @Test
    public fun `un drawable du projet est valide - tolerance de la maquette`() {
        // vCol partage par android:background : @drawable/… est toléré.
        assertEquals(
            ValidationAttribut.Resultat.Valide,
            ValidationAttribut.couleur("@drawable/logo", couleurs, drawables),
        )
    }

    @Test
    public fun `un drawable introuvable est un avertissement`() {
        assertEquals(
            ValidationAttribut.Resultat.Avertissement(ValidationAttribut.GenreMessage.DRAWABLE_INTROUVABLE),
            ValidationAttribut.couleur("@drawable/absent", couleurs, drawables),
        )
    }

    @Test
    public fun `les references de theme et systeme sont valides`() {
        // Miroir `^\?(android:)?attr/\w+$` et `^@android:` : la maquette
        // ne peut PAS les vérifier, le RENDU tranchera.
        for (valeur in listOf("?attr/colorPrimary", "?android:attr/colorAccent", "@android:color/holo_red_dark")) {
            assertEquals(
                "référence $valeur",
                ValidationAttribut.Resultat.Valide,
                ValidationAttribut.couleur(valeur, couleurs, drawables),
            )
        }
    }

    @Test
    public fun `un nom de ressource illegal est une erreur`() {
        // `@color/a-b` ne matche pas `\w+` : retombe dans l'erreur de forme.
        assertEquals(
            ValidationAttribut.Resultat.Erreur(ValidationAttribut.GenreMessage.COULEUR_INVALIDE),
            ValidationAttribut.couleur("@color/a-b", couleurs, drawables),
        )
        assertEquals(
            ValidationAttribut.Resultat.Erreur(ValidationAttribut.GenreMessage.COULEUR_INVALIDE),
            ValidationAttribut.couleur("@color/", couleurs, drawables),
        )
    }

    @Test
    public fun `un mot quelconque est une erreur`() {
        assertEquals(
            ValidationAttribut.Resultat.Erreur(ValidationAttribut.GenreMessage.COULEUR_INVALIDE),
            ValidationAttribut.couleur("rouge", couleurs, drawables),
        )
        assertEquals(
            ValidationAttribut.Resultat.Erreur(ValidationAttribut.GenreMessage.COULEUR_INVALIDE),
            ValidationAttribut.couleur("FF0000", couleurs, drawables),
        )
    }

    // ── PE11f : le contrôle RESSOURCE (miroir `vRes`) ─────────────────

    /** L'index du faux projet : un drawable, un string, RIEN pour layout. */
    private val index: Map<String, Set<String>> =
        mapOf(
            "drawable" to setOf("logo"),
            "string" to setOf("app_nom"),
        )

    /** Les types acceptés par l'attribut testé (miroir `c.ty`). */
    private val typesDessinables: List<String> = listOf("drawable")

    @Test
    public fun `le vide et les attr de theme sont des ressources valides`() {
        assertEquals(
            ValidationAttribut.Resultat.Valide,
            ValidationAttribut.ressource(null, typesDessinables, index),
        )
        assertEquals(
            ValidationAttribut.Resultat.Valide,
            ValidationAttribut.ressource("", typesDessinables, index),
        )
        assertEquals(
            ValidationAttribut.Resultat.Valide,
            ValidationAttribut.ressource("?attr/colorPrimary", typesDessinables, index),
        )
        assertEquals(
            ValidationAttribut.Resultat.Valide,
            ValidationAttribut.ressource("?android:attr/colorAccent", typesDessinables, index),
        )
    }

    @Test
    public fun `une ressource du bon type est valide`() {
        assertEquals(
            ValidationAttribut.Resultat.Valide,
            ValidationAttribut.ressource("@drawable/logo", typesDessinables, index),
        )
    }

    @Test
    public fun `une ressource absente est un avertissement qui ecrit quand meme`() {
        assertEquals(
            ValidationAttribut.Resultat.Avertissement(
                ValidationAttribut.GenreMessage.RESSOURCE_INTROUVABLE,
                listOf("drawable", "absent"),
            ),
            ValidationAttribut.ressource("@drawable/absent", typesDessinables, index),
        )
    }

    @Test
    public fun `un type refuse est une erreur qui n ecrit rien`() {
        // Miroir `{err:'Type @x refusé ici, attendu : @drawable.'}` — la
        // liste des acceptés suit le CATALOGUE.
        assertEquals(
            ValidationAttribut.Resultat.Erreur(
                ValidationAttribut.GenreMessage.TYPE_REFUSE,
                listOf("string", "@drawable"),
            ),
            ValidationAttribut.ressource("@string/app_nom", typesDessinables, index),
        )
    }

    @Test
    public fun `une forme cassee est l erreur de reference`() {
        // Miroir `{err:'Référence attendue : @ty[0]/nom.'}` — le nom nu
        // est COMPLÉTÉ en amont (controle.completer), le validateur ne
        // voit que des candidats de référence.
        assertEquals(
            ValidationAttribut.Resultat.Erreur(
                ValidationAttribut.GenreMessage.REFERENCE_ATTENDUE,
                listOf("drawable"),
            ),
            ValidationAttribut.ressource("logo", typesDessinables, index),
        )
        assertEquals(
            ValidationAttribut.Resultat.Erreur(
                ValidationAttribut.GenreMessage.REFERENCE_ATTENDUE,
                listOf("drawable"),
            ),
            ValidationAttribut.ressource("@", typesDessinables, index),
        )
        assertEquals(
            ValidationAttribut.Resultat.Erreur(
                ValidationAttribut.GenreMessage.REFERENCE_ATTENDUE,
                listOf("drawable"),
            ),
            ValidationAttribut.ressource("@drawable/", typesDessinables, index),
        )
    }

    @Test
    public fun `un type sans index ne peut pas etre verifie - pas d avertissement`() {
        // Miroir `set` nul : l'index du projet n'a pas ce type → le
        // RENDU tranchera, le validateur SE TAIT.
        assertEquals(
            ValidationAttribut.Resultat.Valide,
            ValidationAttribut.ressource("@layout/main", typesDessinables + "layout", emptyMap()),
        )
    }

    @Test
    public fun `la completion du nom nu suit la maquette`() {
        val controle =
            CatalogueAttributs.ControleAttribut.Ressource(
                "android:src",
                CatalogueAttributs.Etiquette.SOURCE,
                listOf("drawable", "mipmap"),
            )
        assertEquals("Le nom nu reçoit le PREMIER type", "@drawable/ic_logo", controle.completer("ic_logo"))
        assertEquals("Une référence passe telle quelle", "@drawable/x", controle.completer("@drawable/x"))
        assertEquals("Un attr de thème passe tel quel", "?attr/x", controle.completer("?attr/x"))
        assertEquals("Le vide passe tel quel (retrait)", "", controle.completer(""))
    }

    @Test
    public fun `le curseur accepte la saisie dans les bornes - bords compris`() {
        // PE11g-contrôles — miroir le `commit` du `sld` : les bornes
        // sont INCLUSIVES (`num < c.min || num > c.max` seul refuse).
        assertEquals(ValidationAttribut.Resultat.Valide, ValidationAttribut.curseur("0.5", 0f, 1f))
        assertEquals(ValidationAttribut.Resultat.Valide, ValidationAttribut.curseur("0", 0f, 1f))
        assertEquals(ValidationAttribut.Resultat.Valide, ValidationAttribut.curseur("1", 0f, 1f))
        assertEquals(ValidationAttribut.Resultat.Valide, ValidationAttribut.curseur("-180", -180f, 180f))
    }

    @Test
    public fun `le curseur hors bornes ou non numerique est une erreur qui n ecrit rien`() {
        // Miroir `isNaN(num) || num < c.min || num > c.max` →
        // « Valeur entre min et max. » ; les bornes du message sont
        // FORMATÉES (miroir `'… ' + c.min + ' …'` — jamais « 1.0 »).
        val verdict = ValidationAttribut.curseur("1.2", 0f, 1f)
        assertTrue(verdict is ValidationAttribut.Resultat.Erreur)
        assertEquals(
            ValidationAttribut.GenreMessage.VALEUR_BORNEE,
            (verdict as ValidationAttribut.Resultat.Erreur).genre,
        )
        assertEquals("Les bornes du message sont formatées", listOf("0", "1"), verdict.arguments)
        assertTrue(
            "Sous la borne basse",
            ValidationAttribut.curseur("-0.1", 0f, 1f) is ValidationAttribut.Resultat.Erreur,
        )
        assertTrue(
            "Non numérique",
            ValidationAttribut.curseur("plein", 0f, 1f) is ValidationAttribut.Resultat.Erreur,
        )
        assertEquals(
            "Le message porte aussi les bornes à décimales",
            listOf("0.1", "3"),
            (ValidationAttribut.curseur("5", 0.1f, 3f) as ValidationAttribut.Resultat.Erreur).arguments,
        )
    }

    @Test
    public fun `le ratio exige deux nombres positifs - le reste est une erreur`() {
        // Miroir le `put` du `ratio` : `!(+x > 0 && +y > 0)` →
        // « Deux nombres positifs, par exemple 16 et 9. » ; les deux
        // champs VIDES ne passent PAS par ici (c'est le RETRAIT, réglé
        // au liage).
        assertEquals(ValidationAttribut.Resultat.Valide, ValidationAttribut.ratio("16", "9"))
        assertEquals(ValidationAttribut.Resultat.Valide, ValidationAttribut.ratio("1.5", "2"))
        assertEquals(
            ValidationAttribut.Resultat.Erreur(ValidationAttribut.GenreMessage.RATIO_INVALIDE),
            ValidationAttribut.ratio("0", "9"),
        )
        assertTrue("Négatif", ValidationAttribut.ratio("-1", "9") is ValidationAttribut.Resultat.Erreur)
        assertTrue("Non numérique", ValidationAttribut.ratio("a", "9") is ValidationAttribut.Resultat.Erreur)
        assertTrue("Un champ vide", ValidationAttribut.ratio("16", "") is ValidationAttribut.Resultat.Erreur)
    }

    @Test
    public fun `le nom d attribut libre exige un espace de noms et un identifiant`() {
        // PE11h — miroir `/^(android|app|tools):[A-Za-z_]\w*$/.test(nm)` de
        // la rangée d'ajout de la maquette : les TROIS préfixes passent, le
        // nom commence par une LETTRE ou un _ ; tout le reste est une
        // ERREUR qui n'écrit RIEN (le vide compris — la rangée exige un
        // nom ET une valeur).
        assertEquals(ValidationAttribut.Resultat.Valide, ValidationAttribut.nomAttributLibre("android:transitionName"))
        assertEquals(
            ValidationAttribut.Resultat.Valide,
            ValidationAttribut.nomAttributLibre("app:layout_goneMarginTop"),
        )
        assertEquals(ValidationAttribut.Resultat.Valide, ValidationAttribut.nomAttributLibre("tools:ignore"))
        assertEquals(ValidationAttribut.Resultat.Valide, ValidationAttribut.nomAttributLibre("android:_prive"))
        assertEquals(
            "Les blancs extrêmes se retirent",
            ValidationAttribut.Resultat.Valide,
            ValidationAttribut.nomAttributLibre(" android:tag "),
        )
        assertEquals(
            ValidationAttribut.Resultat.Erreur(ValidationAttribut.GenreMessage.NOM_ATTRIBUT_LIBRE),
            ValidationAttribut.nomAttributLibre("transitionName"),
        )
        assertTrue(
            "Préfixe inconnu",
            ValidationAttribut.nomAttributLibre("foo:bar") is ValidationAttribut.Resultat.Erreur,
        )
        assertTrue(
            "Chiffre en tête",
            ValidationAttribut.nomAttributLibre("android:9patch") is ValidationAttribut.Resultat.Erreur,
        )
        assertTrue(
            "Tiret dans le nom",
            ValidationAttribut.nomAttributLibre("android:mon-nom") is ValidationAttribut.Resultat.Erreur,
        )
        assertTrue(
            "Double préfixe",
            ValidationAttribut.nomAttributLibre("android:app:x") is ValidationAttribut.Resultat.Erreur,
        )
        assertTrue(
            "Nom seul avec deux-points",
            ValidationAttribut.nomAttributLibre("android:") is ValidationAttribut.Resultat.Erreur,
        )
        assertTrue("Vide", ValidationAttribut.nomAttributLibre("") is ValidationAttribut.Resultat.Erreur)
    }

    @Test
    fun `le nom d activite de tools context suit la maquette`() {
        // PE11 § 7 — miroir `/^[.\w]+$/` du `C.str('tools:context', …)` :
        // `.MainActivity`, la forme PLEINEMENT qualifiée et le simple nom
        // passent ; le VIDE reste VALIDE (c'est le RETRAIT) ; espaces,
        // tirets et slashs sont des ERREURS qui n'écrivent RIEN.
        assertTrue("Vide = retrait", ValidationAttribut.nomActivite("") is ValidationAttribut.Resultat.Valide)
        assertTrue(
            "Point + classe",
            ValidationAttribut.nomActivite(".MainActivity") is ValidationAttribut.Resultat.Valide,
        )
        assertTrue(
            "Pleinement qualifie",
            ValidationAttribut.nomActivite("com.exemple.ConnexionActivity") is ValidationAttribut.Resultat.Valide,
        )
        assertTrue("Nom nu", ValidationAttribut.nomActivite("MainActivity") is ValidationAttribut.Resultat.Valide)
        assertTrue(
            "L espace est refuse",
            ValidationAttribut.nomActivite(".Main Activity") is ValidationAttribut.Resultat.Erreur,
        )
        assertTrue(
            "Le tiret est refuse",
            ValidationAttribut.nomActivite("com.exemple-activite") is ValidationAttribut.Resultat.Erreur,
        )
        assertTrue(
            "Le slash est refuse",
            ValidationAttribut.nomActivite("com/exemple") is ValidationAttribut.Resultat.Erreur,
        )
    }

    @Test
    fun `le nom de methode java de onclick suit la maquette`() {
        // PE11 § 7 — miroir `/^[A-Za-z_$][\w$]*$/` du `C.str('android:onClick',
        // …)` : un identifiant Java nu (le `$` compris) ; le VIDE reste
        // VALIDE (retrait) ; chiffre en tête, point, espaces = ERREURS.
        assertTrue("Vide = retrait", ValidationAttribut.nomMethodeJava("") is ValidationAttribut.Resultat.Valide)
        assertTrue("Nom simple", ValidationAttribut.nomMethodeJava("onConnexion") is ValidationAttribut.Resultat.Valide)
        assertTrue("Soulignement", ValidationAttribut.nomMethodeJava("_prive1") is ValidationAttribut.Resultat.Valide)
        assertTrue("Dollar", ValidationAttribut.nomMethodeJava("\$cached") is ValidationAttribut.Resultat.Valide)
        assertTrue(
            "Chiffre en tete refuse",
            ValidationAttribut.nomMethodeJava("1clic") is ValidationAttribut.Resultat.Erreur,
        )
        assertTrue(
            "Le point du paquet est refuse",
            ValidationAttribut.nomMethodeJava("com.exemple.clic") is ValidationAttribut.Resultat.Erreur,
        )
        assertTrue(
            "L espace est refuse",
            ValidationAttribut.nomMethodeJava("on clic") is ValidationAttribut.Resultat.Erreur,
        )
    }
}
