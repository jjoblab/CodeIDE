package jo.codeide.templates

import jo.codeide.BuildConfig
import jo.codeide.core.domain.templates.GeneratorVersion
import javax.inject.Inject

/**
 * Version du générateur inscrite dans `.codeide/project.json` (étape 8 —
 * section 11 : forme `CodeIDE <version>`).
 *
 * Source unique : le `BuildConfig` du module `app` — la même version que
 * celle portée par `version.properties` (script `bump-version.sh`), sans
 * duplication manuelle.
 */
public class GeneratorVersionImpl
    @Inject
    constructor() : GeneratorVersion {
        override val value: String = "CodeIDE ${BuildConfig.VERSION_NAME}"
    }
