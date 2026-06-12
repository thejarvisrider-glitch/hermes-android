#!/bin/sh

##############################################################################
# Simplified Gradle wrapper - avoids broken sed parsing
##############################################################################

APP_HOME=$( cd "${APP_HOME:-./}" > /dev/null && pwd -P ) || exit
APP_BASE_NAME=${0##*/}
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Determine Java
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD=$JAVA_HOME/bin/java
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    fi
else
    JAVACMD=command -v java >/dev/null 2>&1 || { echo "ERROR: JAVA_HOME not set and no 'java' found" >&2; exit 1; }
fi

exec "$JAVACMD" \
    ${DEFAULT_JVM_OPTS:--Xmx64m -Xms64m} \
    $JAVA_OPTS $GRADLE_OPTS \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"