@rem Gradle wrapper script for Windows. Uses system gradle if wrapper JAR is missing.
@echo off
setlocal
set APP_HOME=%~dp0
if exist "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" (
    java -classpath "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
) else (
    echo gradle-wrapper.jar not found — falling back to system gradle.
    gradle %*
)
