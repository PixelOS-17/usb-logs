#!/bin/bash
# Builds PixelOS Logs with the AOSP prebuilts only. Usage: build.sh [pixelos-root]
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
top=${1:-$here/../../pixelos}
api=36
min_api=33
version_code=2
version_name=2.0

jdk=$top/prebuilts/jdk/jdk21/linux-x86/bin
tools=$top/prebuilts/sdk/tools/linux
android_jar=$top/prebuilts/sdk/36.1/public/android.jar
src=$here/src
out=$here/out
key=$here/key/usblogs.jks

rm -rf "$out"
mkdir -p "$out/classes" "$out/dex"

if [ ! -f "$key" ]; then
	mkdir -p "$(dirname "$key")"
	"$jdk/keytool" -genkeypair -keystore "$key" -storepass usblogs -keypass usblogs \
		-alias usblogs -keyalg RSA -keysize 2048 -validity 10000 \
		-dname "CN=PixelOS USB Logs" >/dev/null
fi

"$jdk/javac" -source 8 -target 8 -bootclasspath "$android_jar:$tools/bin/core-lambda-stubs.jar" -Xlint:-options \
	-d "$out/classes" $(find "$src/java" -name '*.java')

"$jdk/java" -cp "$top/prebuilts/r8/r8.jar" com.android.tools.r8.D8 --release \
	--min-api "$min_api" --lib "$android_jar" --output "$out/dex" \
	$(find "$out/classes" -name '*.class')

"$tools/bin/aapt2" link -o "$out/unaligned.apk" -I "$android_jar" \
	--manifest "$src/AndroidManifest.xml" -A "$src/assets" \
	--min-sdk-version "$min_api" --target-sdk-version "$api" \
	--version-code "$version_code" --version-name "$version_name"

python3 -I -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1], "a", zipfile.ZIP_DEFLATED).write(sys.argv[2], "classes.dex")' \
	"$out/unaligned.apk" "$out/dex/classes.dex"

"$tools/bin/zipalign" -p -f 4 "$out/unaligned.apk" "$out/aligned.apk"

"$jdk/java" -jar "$tools/lib/apksigner.jar" sign --ks "$key" --ks-pass pass:usblogs \
	--out "$out/pixelos-logs.apk" "$out/aligned.apk"

"$jdk/java" -jar "$tools/lib/apksigner.jar" verify --print-certs "$out/pixelos-logs.apk"
