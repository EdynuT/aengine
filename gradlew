#!/usr/bin/env sh
#
# Copyright 2015 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SPDX-License-Identifier: Apache-2.0
#

##############################################################################
#
#   gradlew startup script para Unix/Linux
#
##############################################################################

# Resolve o diretório real do script, seguindo symlinks
PRG="$0"
while [ -h "$PRG" ]; do
    ls_out=$(ls -ld "$PRG")
    link=$(expr "$ls_out" : '.*-> \(.*\)$')
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=$(dirname "$PRG")/"$link"
    fi
done

APP_HOME=$(cd "$(dirname "$PRG")" > /dev/null 2>&1 && pwd -P)
APP_BASE_NAME=$(basename "$0")

# Opções padrão da JVM (equivalente ao DEFAULT_JVM_OPTS do .bat)
DEFAULT_JVM_OPTS='-Dfile.encoding=UTF-8 -Xmx64m -Xms64m'

# Localiza o executável Java
if [ -n "$JAVA_HOME" ]; then
    # Remove aspas caso existam (equivalente ao JAVA_HOME=%JAVA_HOME:"=% do .bat)
    JAVA_HOME=$(printf '%s' "$JAVA_HOME" | sed 's/"//g')
    JAVA_EXE="$JAVA_HOME/bin/java"
    if [ ! -x "$JAVA_EXE" ]; then
        echo >&2
        echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
        echo >&2
        echo "Please set the JAVA_HOME variable in your environment to match the" >&2
        echo "location of your Java installation." >&2
        exit 1
    fi
else
    JAVA_EXE=java
    if ! command -v "$JAVA_EXE" > /dev/null 2>&1; then
        echo >&2
        echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH." >&2
        echo >&2
        echo "Please set the JAVA_HOME variable in your environment to match the" >&2
        echo "location of your Java installation." >&2
        exit 1
    fi
fi

# Executa o Gradle Wrapper
# shellcheck disable=SC2086 -- expansão intencional de JAVA_OPTS e GRADLE_OPTS
exec "$JAVA_EXE" \
    $DEFAULT_JVM_OPTS \
    $JAVA_OPTS \
    $GRADLE_OPTS \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
    "$@"
