#!/usr/bin/env bash
# CodeIDE — variables d'environnement du build.
# Usage : source scripts/env.sh

# JDK (Temurin 21 LTS requis : AGP 9 exige JDK 17 minimum, nous figons la 21).
export JAVA_HOME="${JAVA_HOME:-/home/z/tools/jdk-21}"

# SDK Android (cmdline-tools, plateformes, build-tools).
export ANDROID_HOME="${ANDROID_HOME:-/home/z/android-sdk}"

# Outils sur le PATH.
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# Le build passe toujours par le wrapper Gradle, jamais un Gradle système.
export GRADLE_OPTS="${GRADLE_OPTS:--Dorg.gradle.console=plain}"
