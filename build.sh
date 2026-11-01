#!/bin/bash
# MeridianFix build script
#
# 需要先修改下面三个路径，指向你本地的 SDK：
#   BT       = Android build-tools 目录（含 aapt2 / d8 / zipalign）
#   PLATFORM = Android platform 的 android.jar
#   D8JAR    = build-tools 里的 d8.jar
#
# 用法：
#   KS_PASS=你的密码 bash build.sh
#
set -e

BT=${BT:-$HOME/Android/Sdk/build-tools/35.0.0}
PLATFORM=${PLATFORM:-$HOME/Android/Sdk/platforms/android-35/android.jar}
D8JAR=${D8JAR:-$BT/lib/d8.jar}

# aapt2 / zipalign 在 arm64 主机上是 x86-64 ELF，需要经 qemu 运行。
# 若你的 build-tools 是原生 aarch64 版本，把 QX 留空即可。
QX=${QX:-}

KEYSTORE=${KS_PATH:-mykey.jks}

if [ ! -f "$KEYSTORE" ]; then
    echo "[!] 未找到 keystore: $KEYSTORE"
    echo "    先生成一个："
    echo "    keytool -genkeypair -v -keystore mykey.jks -alias meridianfix \"
    echo "        -keyalg RSA -keysize 2048 -validity 10000"
    exit 1
fi

echo "[1/7] javac 编译..."
rm -rf out && mkdir -p out
javac -nowarn -d out $(find stub -name "*.java") src/com/meridian/fix/MeridianFix.java

echo "[2/7] d8 转 dex（只转 out/com，不含 stub）..."
rm -rf dexout && mkdir -p dexout
java -cp "$D8JAR" com.android.tools.r8.D8 --min-api 21 \
    --output dexout $(find out/com -name "*.class") \
    2>&1 | grep -v "was not found" || true

echo "[3/7] aapt2 编译资源..."
rm -rf res_compiled && mkdir -p res_compiled
$QX "$BT/aapt2" compile --dir res -o res_compiled/

echo "[4/7] aapt2 链接..."
$QX "$BT/aapt2" link -o base.apk -I "$PLATFORM" \
    --manifest AndroidManifest.xml -A assets \
    res_compiled/values_arrays.arsc.flat --auto-add-overlay

echo "[5/7] 塞入 dex..."
cp -f base.apk unsigned.apk
(cd dexout && zip -q ../unsigned.apk classes.dex)

echo "[6/7] zipalign..."
$QX "$BT/zipalign" -f 4 unsigned.apk aligned.apk

echo "[7/7] 签名..."
java -jar "$BT/lib/apksigner.jar" sign \
    --ks "$KEYSTORE" \
    ${KS_PASS:+--ks-pass pass:$KS_PASS} \
    ${KEY_PASS:+--key-pass pass:$KEY_PASS} \
    --out MeridianFix.apk aligned.apk

rm -rf out dexout res_compiled base.apk unsigned.apk aligned.apk MeridianFix.apk.idsig

echo ""
echo "构建完成: $(pwd)/MeridianFix.apk"
ls -lh MeridianFix.apk
