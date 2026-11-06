#!/usr/bin/env bash
# 一次性准备 Kotlin 编译器（约 60MB），之后 build-apk.sh 可以反复用。
# 只有在你需要「重新打包」时才要跑这个；只是想装 APK 的话完全不需要。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Git Bash 的 /c/... 路径 Java 认不了，转成 C:/...
winpath() { command -v cygpath >/dev/null 2>&1 && cygpath -m "$1" || echo "$1"; }
LIB="$(winpath "$ROOT")/buildtools/lib"
mkdir -p "$LIB"
cd "$LIB"

M=https://repo1.maven.org/maven2
get() { # $1=相对路径  $2=文件名
  [ -f "$2" ] && { echo "  已存在 $2"; return; }
  echo "  下载 $2"
  curl -sL --max-time 300 -o "$2" "$M/$1" || { echo "  !! 失败 $2"; exit 1; }
  [ -s "$2" ] || { echo "  !! 空文件 $2"; exit 1; }
}

get org/jetbrains/kotlin/kotlin-compiler-embeddable/2.0.21/kotlin-compiler-embeddable-2.0.21.jar kotlin-compiler-embeddable-2.0.21.jar
get org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.jar                               kotlin-stdlib-2.0.21.jar
get org/jetbrains/kotlin/kotlin-reflect/1.9.24/kotlin-reflect-1.9.24.jar                            kotlin-reflect-1.9.24.jar
get org/jetbrains/kotlin/kotlin-script-runtime/2.0.21/kotlin-script-runtime-2.0.21.jar              kotlin-script-runtime-2.0.21.jar
get org/jetbrains/kotlin/kotlin-daemon-embeddable/2.0.21/kotlin-daemon-embeddable-2.0.21.jar        kotlin-daemon-embeddable-2.0.21.jar
get org/jetbrains/annotations/13.0/annotations-13.0.jar                                             annotations-13.0.jar
get org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar                       trove4j-1.0.20200330.jar
get org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.jar   kotlinx-coroutines-core-jvm-1.6.4.jar

# 顺手校验一下编译器能跑
CP="$LIB/kotlin-compiler-embeddable-2.0.21.jar;$LIB/kotlin-stdlib-2.0.21.jar;$LIB/kotlin-reflect-1.9.24.jar;$LIB/kotlin-script-runtime-2.0.21.jar;$LIB/kotlin-daemon-embeddable-2.0.21.jar;$LIB/annotations-13.0.jar;$LIB/trove4j-1.0.20200330.jar;$LIB/kotlinx-coroutines-core-jvm-1.6.4.jar"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -version 2>&1 | head -1

echo
echo "就绪。现在可以运行: bash tools/build-apk.sh"
