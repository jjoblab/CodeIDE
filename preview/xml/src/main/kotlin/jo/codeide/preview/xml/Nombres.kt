package jo.codeide.preview.xml

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Le FORMAT d'un nombre pour l'écriture et les messages (PE11g-contrôles
 * — miroir le `num + c.u` du `sld` de la maquette : le nombre JS s'imprime
 * SANS zéros de queue — l'entier sans décimales, le décimal au plus
 * court : 16f → « 16 », 0.05f → « 0.05 »). L'arrondi à SIX chiffres
 * significatifs tue le BRUIT DE PAS de la glissière Material (3 × 0.05f
 * en binaire → « 0.15 », jamais « 0.15000002 ») sans toucher aux valeurs
 * saisies à la main.
 */
public fun formaterNombre(nombre: Float): String {
    val arrondi =
        BigDecimal(nombre.toDouble())
            .round(MathContext(CHIFFRES_SIGNIFICATIFS, RoundingMode.HALF_UP))
            .stripTrailingZeros()
    return if (arrondi.scale() <= 0) arrondi.toBigInteger().toString() else arrondi.toString()
}

/** La précision de l'arrondi (chiffres significatifs — un pas de glissière n'en dépasse jamais six). */
private const val CHIFFRES_SIGNIFICATIFS: Int = 6
