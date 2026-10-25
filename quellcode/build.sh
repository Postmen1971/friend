#!/bin/bash
set -e
cd "$(dirname "$0")"
T=/tmp/claude-0/b
AAPT2=$T/apktool/brut.apktool/apktool-lib/src/main/resources/prebuilt/linux/aapt2
AJAR=$T/plat/android-28/android.jar
DX=$T/dx.jar

rm -rf out && mkdir -p out/classes res/drawable

# Icon + Banner erzeugen
python3 - <<'PY'
from PIL import Image, ImageDraw, ImageFont
def font(sz):
    for f in ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"]:
        try: return ImageFont.truetype(f, sz)
        except: pass
    return ImageFont.load_default()
b = Image.new("RGB",(320,180),(14,15,19)); d = ImageDraw.Draw(b)
d.rounded_rectangle([18,40,98,140], 16, fill=(255,106,61))
d.polygon([(46,68),(46,112),(80,90)], fill=(14,15,19))
d.text((116,66),"TV Start", font=font(38), fill=(242,243,245))
b.save("res/drawable/banner.png")
i = Image.new("RGBA",(192,192),(0,0,0,0)); d = ImageDraw.Draw(i)
d.rounded_rectangle([8,8,184,184], 40, fill=(255,106,61))
d.polygon([(72,52),(72,140),(144,96)], fill=(14,15,19))
i.save("res/drawable/icon.png")
PY

$AAPT2 compile --dir res -o out/res.zip
$AAPT2 link -o out/base.apk -I "$AJAR" --manifest AndroidManifest.xml -A assets out/res.zip

javac -nowarn -source 8 -target 8 -bootclasspath "$AJAR" -d out/classes $(find src -name '*.java') 2>&1 | grep -v -e '^Picked up' -e 'warning:' || true
java -jar "$DX" --dex --min-sdk-version=21 --output=out/classes.dex out/classes 2>&1 | grep -v '^Picked up' || true

cp out/base.apk out/unsigned.apk
(cd out && zip -q -j unsigned.apk classes.dex)

[ -f tvstart.keystore ] || keytool -genkeypair -keystore tvstart.keystore -storepass tvstart123 -keypass tvstart123 \
  -alias tvstart -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=TV Start" 2>&1 | grep -v '^Picked up' || true

jarsigner -keystore tvstart.keystore -storepass tvstart123 -sigalg SHA256withRSA -digestalg SHA-256 \
  -signedjar out/TV-Start.apk out/unsigned.apk tvstart 2>&1 | grep -v '^Picked up' || true
echo BUILD OK
