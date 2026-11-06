#!/usr/bin/env bash
# 关锁卫士 —— 免 Gradle 一键打包
#
# 为什么不用 Gradle：本机没有 Gradle（VS Code 的 vscjava.vscode-gradle 扩展只带
# tooling-api，不含 Gradle 本体），且 gradle 发行包下载要走 GitHub，当前代理会 502。
# 而 aapt2 / d8 / apksigner 随 Android SDK 已经装好，直接拼装更快。
#
# 需要 Kotlin 编译器（一次性准备，约 60MB，如已存在会跳过）：
#   bash tools/fetch-kotlinc.sh
#
# 用法：  bash tools/build-apk.sh
# 产物：  dist/BikeLockGuard.apk
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Git Bash 的 /c/... 路径 Java 工具链不认，必须转成 C:/...
winpath() { command -v cygpath >/dev/null 2>&1 && cygpath -m "$1" || echo "$1"; }
ROOTW="$(winpath "$ROOT")"

SDK="${ANDROID_SDK:-C:/Users/Tesla/AppData/Local/Android/Sdk}"
BT="$SDK/build-tools/36.1.0"
PLATFORM="$SDK/platforms/android-36/android.jar"
LIB="$ROOTW/buildtools/lib"

[ -f "$PLATFORM" ] || { echo "缺少 android.jar: $PLATFORM"; exit 1; }
if [ ! -f "$LIB/kotlin-compiler-embeddable-2.0.21.jar" ]; then
  echo "缺少 Kotlin 编译器，请先执行: bash tools/fetch-kotlinc.sh"
  exit 1
fi

OUT="$ROOTW/build"
DIST="$ROOTW/dist"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$DIST"

echo "==> 1/6 编译资源 (aapt2)"
# 链接资源，同时生成 R.java
"$BT/aapt2.exe" compile --dir app/src/main/res -o "$OUT/res.zip"
"$BT/aapt2.exe" link \
  -I "$PLATFORM" \
  --manifest app/src/main/AndroidManifest.xml \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code 1 --version-name "2.0" \
  -o "$OUT/base.apk" \
  "$OUT/res.zip"

echo "==> 2/6 编译 Kotlin"
# 注意：Windows 的 classpath 用分号分隔，必须整体加引号，否则 bash 会把分号当命令分隔
KCP="$LIB/kotlin-compiler-embeddable-2.0.21.jar;$LIB/kotlin-stdlib-2.0.21.jar;$LIB/kotlin-reflect-1.9.24.jar;$LIB/kotlin-script-runtime-2.0.21.jar;$LIB/kotlin-daemon-embeddable-2.0.21.jar;$LIB/annotations-13.0.jar;$LIB/trove4j-1.0.20200330.jar;$LIB/kotlinx-coroutines-core-jvm-1.6.4.jar"
# 源码列表也必须写 Windows 路径
find app/src/main/java -name "*.kt" | sed "s|^|$ROOTW/|" > "$OUT/kt-src.txt"
java -cp "$KCP" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -jvm-target 17 \
  -classpath "$PLATFORM;$LIB/kotlin-stdlib-2.0.21.jar" \
  -d "$OUT/classes" \
  @"$OUT/kt-src.txt" 2>&1 | grep -v "unable to find kotlin" | tee "$OUT/kt.log" || true

# 关键：编译器报错后不能继续往下走，否则会产出一个"能装但功能残缺"的 APK
if grep -q "error:" "$OUT/kt.log"; then
  echo
  echo "!! Kotlin 编译失败，已中止（错误见上）"
  exit 1
fi

echo "==> 3/6 编译 R.java"
javac -source 17 -target 17 -nowarn -encoding UTF-8 \
  -classpath "$PLATFORM" -d "$OUT/classes" \
  "$OUT/gen/com/bikeguard/R.java" 2>/dev/null || true

echo "==> 4/6 打包 class -> dex (d8)"
# 把 kotlin-stdlib 一起打进去：零第三方依赖指的是不引 AndroidX，
# 但 Kotlin 标准库是语言运行时，必须内嵌。
# Windows 命令行长度有限，走 argfile；路径统一用 / 分隔的 Windows 形式。
find "$OUT/classes" -name "*.class" > "$OUT/classes.txt"
echo "$LIB/kotlin-stdlib-2.0.21.jar" >> "$OUT/classes.txt"
"$BT/d8.bat" --release --min-api 26 --lib "$PLATFORM" \
  --output "$OUT/dex" @"$OUT/classes.txt"

echo "==> 5/6 合并 dex 进 APK"
# 本机 Git Bash 没有 zip 命令，用 JDK 自带的 jar 工具追加（jar uf 对 zip 格式等价）
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && jar uf "$OUT/unsigned.apk" classes.dex)
echo "    已并入: $(unzip -l "$OUT/unsigned.apk" 2>/dev/null | grep -c 'classes.*dex') 个 dex 条目"

echo "==> 6/6 签名"
KS="$ROOT/buildtools/bikeguard.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v \
    -keystore "$KS" -alias bikeguard -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass bikeguard -keypass bikeguard \
    -dname "CN=BikeLockGuard, OU=self, O=self, L=, S=, C=CN" >/dev/null 2>&1
  echo "    已生成自用签名: $KS"
fi
"$BT/apksigner.bat" sign \
  --ks "$KS" --ks-pass pass:bikeguard --key-pass pass:bikeguard \
  --ks-key-alias bikeguard \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --min-sdk-version 26 \
  --out "$DIST/BikeLockGuard.apk" "$OUT/unsigned.apk"
"$BT/apksigner.bat" verify --print-certs "$DIST/BikeLockGuard.apk" | head -4

echo
echo "构建完成: $DIST/BikeLockGuard.apk"
ls -lh "$DIST/BikeLockGuard.apk"
