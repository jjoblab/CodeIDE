package jo.codeide.core.domain.templates

/**
 * Filtres de substitution des templates (étape 8 — section 11).
 *
 * Rôle : **la saisie de l'utilisateur ne doit jamais casser le code généré**.
 * Le concepteur d'un modèle choisit le filtre adapté au contexte du fichier
 * (`{{projectName|kotlinString}}` dans une chaîne Kotlin, `|xml` dans un
 * layout…) ; les entrées hostiles (guillemets, `\`, `$`, `</project>`,
 * retours à la ligne, emojis, Unicode) ressortent inoffensives.
 *
 * Filtres d'échappement : `kotlinString`, `javaString`, `xml`, `json`,
 * `tomlString`, `md` ; filtres de transformation : `slug`, `lower`,
 * `upper`, `packagePath`.
 *
 * Le filtre `md` échappe `\ ` ` * _ { } [ ] < > # + ! | ~` — les points,
 * tirets et parenthèses sont laissés intacts (inoffensifs en milieu de
 * ligne, et leur échappement rendrait le texte illisible).
 */
internal object TemplateFilters {
    /** Noms de filtres enregistrés (tout autre nom = échec explicite). */
    val NOMS: Set<String> =
        setOf(
            "kotlinString",
            "javaString",
            "xml",
            "json",
            "tomlString",
            "md",
            "slug",
            "lower",
            "upper",
            "packagePath",
        )

    /** Point de sortie Unicode pour les contrôles (échappement `\uXXXX`). */
    private const val SEUIL_CONTROLE = 0x20

    /** Point du caractère DEL (contrôle, échappé hors SEUIL_CONTROLE). */
    private const val POINT_DEL = 0x7F

    /** Première lettre ASCII (fin de repli des accents NFD). */
    private const val PREMIERE_LETTRE_ASCII = 0x80

    /** Catégories Unicode des marques combinantes (accents après NFD). */
    private val MARQUES_COMBINANTES =
        setOf(
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
        )

    /**
     * Applique le filtre [nom] à [entree] ; [ligne] sert au message d'erreur.
     *
     * @return la valeur filtrée.
     * @throws TemplateRenderException si le filtre est inconnu.
     */
    fun appliquer(
        nom: String,
        entree: String,
        ligne: Int,
    ): String =
        when (nom) {
            "kotlinString" -> echapperChaineCode(entree, echapperDollar = true)
            "javaString" -> echapperChaineCode(entree, echapperDollar = false)
            "xml" -> echapperXml(entree)
            "json" -> echapperJson(entree)
            "tomlString" -> echapperToml(entree)
            "md" -> echapperMarkdown(entree)
            "slug" -> slug(entree)
            "lower" -> entree.lowercase()
            "upper" -> entree.uppercase()
            "packagePath" -> entree.replace(".", "/")
            else -> throw TemplateRenderException(ligne, "filtre inconnu « $nom »")
        }

    /** Échappement d'un littéral chaîne Kotlin ou Java. */
    private fun echapperChaineCode(
        entree: String,
        echapperDollar: Boolean,
    ): String {
        val sortie = StringBuilder(entree.length)
        for (caractere in entree) {
            when (caractere) {
                '\\' -> {
                    sortie.append("\\\\")
                }

                '"' -> {
                    sortie.append("\\\"")
                }

                '$' -> {
                    if (echapperDollar) sortie.append("\\$") else sortie.append('$')
                }

                '\n' -> {
                    sortie.append("\\n")
                }

                '\r' -> {
                    sortie.append("\\r")
                }

                '\t' -> {
                    sortie.append("\\t")
                }

                else -> {
                    if (caractere.code < SEUIL_CONTROLE || caractere.code == POINT_DEL) {
                        sortie.append("\\u%04x".format(caractere.code))
                    } else {
                        sortie.append(caractere)
                    }
                }
            }
        }
        return sortie.toString()
    }

    /** Échappement d'un contenu XML (`& < > " '`). */
    private fun echapperXml(entree: String): String =
        buildString(entree.length) {
            for (caractere in entree) {
                when (caractere) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(caractere)
                }
            }
        }

    /** Échappement d'un littéral JSON (RFC 8259). */
    private fun echapperJson(entree: String): String =
        buildString(entree.length) {
            for (caractere in entree) {
                when (caractere) {
                    '"' -> {
                        append("\\\"")
                    }

                    '\\' -> {
                        append("\\\\")
                    }

                    '\n' -> {
                        append("\\n")
                    }

                    '\r' -> {
                        append("\\r")
                    }

                    '\t' -> {
                        append("\\t")
                    }

                    '\b' -> {
                        append("\\b")
                    }

                    '\u000C' -> {
                        append("\\f")
                    }

                    else -> {
                        if (caractere.code < SEUIL_CONTROLE) {
                            append("\\u%04x".format(caractere.code))
                        } else {
                            append(caractere)
                        }
                    }
                }
            }
        }

    /** Échappement d'une chaîne basique TOML (contrôles interdits en brut). */
    private fun echapperToml(entree: String): String =
        buildString(entree.length) {
            for (caractere in entree) {
                when (caractere) {
                    '"' -> {
                        append("\\\"")
                    }

                    '\\' -> {
                        append("\\\\")
                    }

                    '\n' -> {
                        append("\\n")
                    }

                    '\r' -> {
                        append("\\r")
                    }

                    '\t' -> {
                        append("\\t")
                    }

                    else -> {
                        if (caractere.code < SEUIL_CONTROLE || caractere.code == POINT_DEL) {
                            append("\\u%04X".format(caractere.code))
                        } else {
                            append(caractere)
                        }
                    }
                }
            }
        }

    /** Échappement Markdown des caractères structurants. */
    private fun echapperMarkdown(entree: String): String {
        val echappes = "\\`*_{}[]<>#+!|~"
        return buildString(entree.length) {
            for (caractere in entree) {
                if (caractere in echappes) append('\\')
                append(caractere)
            }
        }
    }

    /**
     * Normalise un libellé en slug : minuscules sans accents, séparateurs
     * non alphanumériques réduits à un tiret unique, tirets de bord retirés.
     *
     * Une entrée sans aucun caractère alphanumérique produit une chaîne
     * vide — le validateur du paramètre en aval signale alors la valeur.
     */
    @Suppress("CyclomaticComplexMethod") // Catégories Unicode : une classe de caractère par branche.
    fun slug(entree: String): String {
        val decomposee =
            java.text.Normalizer
                .normalize(entree, java.text.Normalizer.Form.NFD)
        val construite = StringBuilder(decomposee.length)
        var tiretEnAttente = false
        for (caractere in decomposee) {
            when {
                caractere.isLetter() && caractere.code < PREMIERE_LETTRE_ASCII -> {
                    // Lettres ASCII après NFD : les accents sont devenus des
                    // marques combinantes, éliminées par la branche suivante.
                    if (tiretEnAttente && construite.isNotEmpty()) appendTiret(construite)
                    tiretEnAttente = false
                    construite.append(caractere.lowercaseChar())
                }

                caractere.isDigit() -> {
                    if (tiretEnAttente && construite.isNotEmpty()) appendTiret(construite)
                    tiretEnAttente = false
                    construite.append(caractere)
                }

                caractere.isLetter() -> {
                    // Lettre non latine (galique, cyrillique…) : conservée
                    // minuscule, elle est valide dans un slug lisible.
                    if (tiretEnAttente && construite.isNotEmpty()) appendTiret(construite)
                    tiretEnAttente = false
                    construite.append(caractere.lowercaseChar())
                }

                else -> {
                    when (Character.getType(caractere)) {
                        // Marque combinante (accent décomposé par NFD) :
                        // éliminée, elle n'est pas un séparateur.
                        in MARQUES_COMBINANTES -> Unit

                        else -> tiretEnAttente = true
                    }
                }
            }
        }
        return construite.toString()
    }

    private fun appendTiret(sortie: StringBuilder) {
        sortie.append('-')
    }
}
