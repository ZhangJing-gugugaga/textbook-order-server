#!/bin/sh
# ----------------------------------------------------------------------------
# Maven Start Up Batch script for POSIX systems (Unix / macOS / Git Bash / MSYS)
# 本项目使用 Maven Wrapper 3.2.0（SPEC §1：mvnw 入库）
# ----------------------------------------------------------------------------

# OS specific support.
cygwin=false;
darwin=false;
mingw=false
case "$(uname)" in
  CYGWIN*) cygwin=true ;;
  Darwin*) darwin=true ;;
  MSYS* | MINGW*) mingw=true ;;
esac

# Resolve links: $0 may be a link
PRG="$0"
while [ -h "$PRG" ]; do
  ls=$(ls -ld "$PRG")
  link=$(expr "$ls" : '.*-> \(.*\)$')
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=$(dirname "$PRG")"/$link"
  fi
done

SAVED="$(pwd)"
cd "$(dirname "$PRG")/" >/dev/null
PROJECTBASEDIR=$(pwd)
cd "$SAVED" >/dev/null

# Determine the Java command to use to start the JVM
if [ -n "$JAVA_HOME" ]; then
  if [ -x "$JAVA_HOME/jre/sh/java" ]; then
    JAVACMD="$JAVA_HOME/jre/sh/java"
  else
    JAVACMD="$JAVA_HOME/bin/java"
  fi
  if [ ! -x "$JAVACMD" ]; then
    echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
    exit 1
  fi
else
  JAVACMD="java"
  if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH." >&2
    exit 1
  fi
fi

# For Cygwin, switch paths to Windows format before running java
if $cygwin; then
  [ -n "$JAVA_HOME" ] && JAVA_HOME=$(cygpath --unix "$JAVA_HOME")
fi

# The maven-wrapper.jar bootstraps the Maven distribution declared in
# .mvn/wrapper/maven-wrapper.properties (downloaded to ~/.m2/wrapper on first run).
WRAPPER_JAR="$PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
  echo "ERROR: $WRAPPER_JAR is missing." >&2
  exit 1
fi

exec "$JAVACMD" \
  -classpath "$WRAPPER_JAR" \
  "-Dmaven.multiModuleProjectDirectory=${PROJECTBASEDIR}" \
  org.apache.maven.wrapper.MavenWrapperMain "$@"
