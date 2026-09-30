package {{packageName}}

actual fun platformName(): String = "JVM (Java ${System.getProperty("java.version")})"
