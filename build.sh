#!/bin/sh
# ============================================================
#  SignageTV 命令行编译脚本（Git Bash / MSYS / Linux / macOS）
#  用法： sh build.sh
#
#  下面几个路径都有默认值，需要时用环境变量顶掉：
#    JAVA_HOME          JDK 17（必须是完整版，要有 jlink）
#    ANDROID_HOME       Android SDK
#    GRADLE_BIN         要用的那个 gradle 可执行文件
#    GRADLE_USER_HOME   依赖缓存放哪（默认放 D 盘，避免写满 C 盘）
# ============================================================

JDK="${JAVA_HOME:-/d/jdk17/jdk-17.0.20.1+1}"
GRADLE="${GRADLE_BIN:-/d/gradle-8.7/bin/gradle}"
SDK="${ANDROID_HOME:-D:/android-sdk}"
CACHE="${GRADLE_USER_HOME:-D:/gradle-home}"

# 编译时不要走代理（沙箱/环境里可能注入了 127.0.0.1 的代理，会让 gradle 拉不到依赖）
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY

export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export GRADLE_USER_HOME="$CACHE"

cd "$(dirname "$0")" || exit 1

sh "$GRADLE" -g "$CACHE" --console=plain :app:assembleDebug "$@"
status=$?

if [ $status -eq 0 ]; then
  echo
  echo "编译成功，产物（文件名带版本号）："
  ls -lh app/build/outputs/apk/debug/*.apk
fi

exit $status
