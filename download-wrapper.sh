#!/bin/sh
# Downloads the Gradle wrapper JAR if it is missing.
# Android Studio normally generates this, but if you are building from CLI
# without Android Studio, run this script first.

WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
GRADLE_VERSION="8.5"

if [ -f "$WRAPPER_JAR" ]; then
    echo "Gradle wrapper JAR already exists."
    exit 0
fi

echo "Downloading Gradle wrapper JAR (v${GRADLE_VERSION})..."
curl -fsSL "https://raw.githubusercontent.com/gradle/gradle/v${GRADLE_VERSION}.0/gradle/wrapper/gradle-wrapper.jar" -o "$WRAPPER_JAR" 2>/dev/null || \
curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle-bin.zip 2>/dev/null && \
    unzip -p /tmp/gradle-bin.zip "gradle-${GRADLE_VERSION}/lib/gradle-wrapper-${GRADLE_VERSION}.jar" > "$WRAPPER_JAR" 2>/dev/null

if [ ! -f "$WRAPPER_JAR" ] || [ ! -s "$WRAPPER_JAR" ]; then
    echo ""
    echo "Could not auto-download the wrapper JAR."
    echo "Please run:  gradle wrapper --gradle-version ${GRADLE_VERSION}"
    echo "Or open the project in Android Studio, which will generate it automatically."
    exit 1
fi

echo "Done: $WRAPPER_JAR"
