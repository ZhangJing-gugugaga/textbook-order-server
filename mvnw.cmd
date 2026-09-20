@REM Maven Start Up Batch script for Windows (cmd / PowerShell)
@REM 本项目使用 Maven Wrapper 3.2.0（SPEC §1：mvnw 入库）
@echo off
setlocal

set PROJECTBASEDIR=%~dp0
if "%PROJECTBASEDIR:~-1%"=="\" set PROJECTBASEDIR=%PROJECTBASEDIR:~0,-1%

if defined JAVA_HOME (
  set JAVACMD=%JAVA_HOME%\bin\java.exe
) else (
  set JAVACMD=java.exe
)

if not exist "%PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar" (
  echo ERROR: %PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar is missing.
  exit /b 1
)

"%JAVACMD%" ^
  -classpath "%PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar" ^
  "-Dmaven.multiModuleProjectDirectory=%PROJECTBASEDIR%" ^
  org.apache.maven.wrapper.MavenWrapperMain %*
