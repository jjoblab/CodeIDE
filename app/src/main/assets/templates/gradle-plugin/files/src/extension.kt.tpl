package {{packageName}}

import org.gradle.api.provider.Property

/**
 * {{t:extension.kdoc}}
 */
abstract class GreetingExtension {
    /** {{t:extension.message.kdoc}} */
    abstract val message: Property<String>
}
