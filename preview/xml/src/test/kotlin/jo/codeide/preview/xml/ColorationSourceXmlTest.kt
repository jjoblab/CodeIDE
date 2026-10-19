package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la COLORATION de l'onglet Source (PE11k —
 * [ColorationSourceXml]) : les RÔLES par plage, la découpe aux SAUTS de
 * ligne (une valeur multi-lignes garde son rôle sur chaque ligne), la
 * ponctuation de fermeture `/>` en BALISE et les documents TRONQUÉS
 * (frappe en cours — jamais de couleur orpheline).
 *
 * Convention : noms en français entre accents graves (ADR 0007) ; les
 * offsets attendus se COMPOSENT depuis les fragments du document (jamais
 * de constantes fragiles).
 */
public class ColorationSourceXmlTest {
    @Test
    public fun `une balise simple est coloree en entier`() {
        val lignes = ColorationSourceXml.colorerParLigne("<LinearLayout>")
        assertEquals(1, lignes.size)
        assertEquals(
            listOf(ColorationSourceXml.Portion(0, "<LinearLayout>".length, ColorationSourceXml.Role.BALISE)),
            lignes[0],
        )
    }

    @Test
    public fun `le nom d attribut et la valeur ont leurs roles`() {
        val balise = "<TextView"
        val attribut = "android:id"
        val valeur = "@+id/btn"
        val document = "$balise $attribut=\"$valeur\" />"
        val portions = ColorationSourceXml.colorerParLigne(document)[0]

        val debutAttribut = balise.length + 1
        val debutValeur = debutAttribut + attribut.length + 2
        assertTrue(
            "le nom de balise est coloré en BALISE",
            portions.any {
                it.role == ColorationSourceXml.Role.BALISE && it.debut == 0 && it.fin == balise.length
            },
        )
        assertTrue(
            "android:id est coloré en ATTRIBUT",
            portions.any {
                it.role == ColorationSourceXml.Role.ATTRIBUT &&
                    it.debut == debutAttribut &&
                    it.fin == debutAttribut + attribut.length
            },
        )
        assertTrue(
            "@+id/btn est coloré en VALEUR",
            portions.any {
                it.role == ColorationSourceXml.Role.VALEUR &&
                    it.debut == debutValeur &&
                    it.fin == debutValeur + valeur.length
            },
        )
        assertTrue(
            "la fermeture /> reste en BALISE (démarrant au /)",
            portions.any {
                it.role == ColorationSourceXml.Role.BALISE &&
                    it.debut == document.length - "/>".length &&
                    it.fin == document.length
            },
        )
    }

    @Test
    public fun `une valeur multi-lignes garde son role sur chaque ligne`() {
        val enteteLigne2 = "  android:text=\""
        val valeurLigne2 = "ligne un"
        val valeurLigne3 = "ligne deux"
        val document = "<TextView\n$enteteLigne2$valeurLigne2\n$valeurLigne3\" />"
        val lignes = ColorationSourceXml.colorerParLigne(document)
        assertEquals(3, lignes.size)

        assertTrue(
            "ligne 1 : le nom de balise",
            lignes[0].any {
                it.role == ColorationSourceXml.Role.BALISE && it.debut == 0 && it.fin == "<TextView".length
            },
        )
        assertTrue(
            "ligne 2 : l'attribut puis la valeur qui s'ouvre",
            lignes[1].any {
                it.role == ColorationSourceXml.Role.ATTRIBUT &&
                    it.debut == "  ".length &&
                    it.fin == "  android:text".length
            },
        )
        assertTrue(
            "ligne 2 : la valeur commence après le guillemet",
            lignes[1].any {
                it.role == ColorationSourceXml.Role.VALEUR &&
                    it.debut == enteteLigne2.length &&
                    it.fin == enteteLigne2.length + valeurLigne2.length
            },
        )
        assertTrue(
            "ligne 3 : la valeur se poursuit sur toute la ligne",
            lignes[2].any {
                it.role == ColorationSourceXml.Role.VALEUR && it.debut == 0 && it.fin == valeurLigne3.length
            },
        )
        assertTrue(
            "ligne 3 : la fermeture /> reste en BALISE",
            lignes[2].any {
                it.role == ColorationSourceXml.Role.BALISE && it.debut == "$valeurLigne3\" ".length
            },
        )
    }

    @Test
    public fun `le texte hors balise n est pas colore`() {
        val document = "<TextView>texte visible<Button>"
        val lignes = ColorationSourceXml.colorerParLigne(document)
        assertEquals(1, lignes.size)
        // Deux runs balise, AUCUN ne couvre le texte entre les balises.
        assertEquals(2, lignes[0].size)
        assertTrue(lignes[0].all { it.role == ColorationSourceXml.Role.BALISE })
    }

    @Test
    public fun `un commentaire est colore en balise en entier`() {
        val commentaire = "<!-- un\ncommentaire -->"
        val document = "$commentaire<TextView>"
        val lignes = ColorationSourceXml.colorerParLigne(document)
        assertEquals(2, lignes.size)
        assertTrue(
            "ligne 1 du commentaire entière en BALISE",
            lignes[0].single().role == ColorationSourceXml.Role.BALISE &&
                lignes[0].single().fin == "<!-- un".length,
        )
        assertTrue(
            "ligne 2 du commentaire entière en BALISE",
            lignes[1].first().role == ColorationSourceXml.Role.BALISE &&
                lignes[1].first().debut == 0 &&
                lignes[1].first().fin == "commentaire -->".length,
        )
    }

    @Test
    public fun `un document tronque en pleine valeur reste coherent`() {
        val document = "<TextView android:id=\"@+id/bt"
        val lignes = ColorationSourceXml.colorerParLigne(document)
        assertEquals(1, lignes.size)
        // La valeur sans guillemet fermant part jusqu'à la fin (frappe
        // en cours) — jamais de couleur orpheline au liage suivant.
        assertTrue(
            lignes[0].any {
                it.role == ColorationSourceXml.Role.VALEUR && it.fin == document.length
            },
        )
    }

    @Test
    public fun `la declaration xml porte ses ponctuations en balise`() {
        val document = "<?xml version=\"1.0\"?>"
        val lignes = ColorationSourceXml.colorerParLigne(document)
        assertEquals(1, lignes.size)
        assertTrue(
            "l'unique attribut est version — le ? fermant n'en est PAS un",
            lignes[0].any {
                it.role == ColorationSourceXml.Role.ATTRIBUT &&
                    it.debut == "<?xml ".length &&
                    it.fin == "<?xml ".length + "version".length
            },
        )
        assertTrue(
            "la fermeture ?> est en BALISE",
            lignes[0].any {
                it.role == ColorationSourceXml.Role.BALISE &&
                    it.debut == document.length - "?>".length &&
                    it.fin == document.length
            },
        )
    }

    @Test
    public fun `une balise fermee apres attribut sans valeur garde le chevron en balise`() {
        val document = "<Button monAttribut>"
        val portions = ColorationSourceXml.colorerParLigne(document)[0]
        assertTrue(
            "monAttribut part en ATTRIBUT",
            portions.any {
                it.role == ColorationSourceXml.Role.ATTRIBUT && it.debut == "<Button ".length
            },
        )
        assertTrue(
            "le chevron fermant reste en BALISE",
            portions.any {
                it.role == ColorationSourceXml.Role.BALISE && it.debut == document.length - 1
            },
        )
    }
}
