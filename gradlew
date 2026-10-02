#!/bin/sh
# Gradle wrapper script. Uses the system gradle if the wrapper JAR is missing.

APP_HOME="$(cd "$(dirname "$0")" && pwd)"

if [ -f "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" ]; then
    exec java -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
else
    echo "gradle-wrapper.jar not found — falling back to system gradle."
    echo "Run ./download-wrapper.sh first, or:  gradle wrapper --gradle-version 8.5"
    exec gradle "$@"
fi
