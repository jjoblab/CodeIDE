package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contrat du VALIDATEUR D'ATTRIBUTS (PE11e — 1re tranche, les couleurs) :
 * un test PAR RÈGLE du `vCol` de la maquette, cas limites inclus (brief
 * PE10a/PE11c : « un test par ligne du tableau et par validateur »).
 * Le module est PUR — JUnit seul, aucun Android.
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
}
