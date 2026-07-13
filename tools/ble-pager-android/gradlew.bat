@echo off
setlocal
set DIR=%~dp0
if not defined JAVA_HOME (
  echo JAVA_HOME must point to a JDK 17 installation.
  exit /b 1
)
"%JAVA_HOME%\bin\java.exe" -classpath "%DIR%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
