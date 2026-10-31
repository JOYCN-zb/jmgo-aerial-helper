#!/bin/bash
# 编译并签名「Aerial 开机修复」辅助应用
# 用法：TC=<工具目录> KS=<密钥库> KS_PASS=<密码> KS_ALIAS=<别名> ./build.sh
# 工具目录需含：jdk-17*/、android-13/android.jar（platform-33）、android-14/（build-tools 34：d8 aapt2 zipalign apksigner）
# 签名：用你自己的密钥库。注意：签名不同的包不能覆盖安装 Release 里的安装包，需先卸载。
# 生成自己的密钥：keytool -genkeypair -keystore my.keystore -alias kiro -keyalg RSA -keysize 2048 -validity 10000
set -euo pipefail
D="$(cd "$(dirname "$0")" && pwd)"
TC="${TC:?请设置 TC 为编译工具目录}"
KS="${KS:?请设置 KS 为密钥库路径}"
KS_PASS="${KS_PASS:?请设置 KS_PASS 为密钥库密码}"
KS_ALIAS="${KS_ALIAS:?请设置 KS_ALIAS 为密钥别名}"
OUT="${OUT:-${D}/aerialbootfix.apk}"
export JAVA_HOME="$(ls -d "${TC}"/jdk-17*/Contents/Home 2>/dev/null | head -1)"
[ -z "${JAVA_HOME}" ] && export JAVA_HOME="$(ls -d "${TC}"/jdk-17* | head -1)"
export PATH="${JAVA_HOME}/bin:${PATH}"
AJ="${TC}/android-13/android.jar"
BT="${TC}/android-14"
B="$(mktemp -d)"
mkdir -p "${B}/cls" "${B}/dex"
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "${AJ}" -d "${B}/cls" "${D}"/src/app/kiro/aerialboot/*.java
"${BT}/d8" --min-api 26 --lib "${AJ}" --output "${B}/dex" $(find "${B}/cls" -name '*.class')
"${BT}/aapt2" link -o "${B}/base.apk" -I "${AJ}" --manifest "${D}/AndroidManifest.xml" --min-sdk-version 21 --target-sdk-version 33
(cd "${B}/dex" && zip -q "${B}/base.apk" classes.dex)
"${BT}/zipalign" -f 4 "${B}/base.apk" "${B}/aligned.apk"
"${BT}/apksigner" sign --ks "${KS}" --ks-pass "pass:${KS_PASS}" --ks-key-alias "${KS_ALIAS}" --out "${OUT}" "${B}/aligned.apk"
echo "已生成 ${OUT}"
