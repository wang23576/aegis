#!/bin/sh
# Prepare agentscope SNAPSHOT jars for a clean local Maven build.
#
# agentscope-java 2.1.0-SNAPSHOT artifacts are not published to Maven Central.
# This script extracts them from an existing local `agentscope-paw:local` image
# (built from agentscope-java/agentscope-examples/agents/agentscope-paw) into
# docker/m2-seed/, which the Dockerfile copies in at build time.
#
# Requires: docker, python3. Run from repository root.

set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/.build-cache"
SEED="$ROOT/docker/m2-seed"
PAW_JAR="$CACHE/paw.jar"

mkdir -p "$CACHE" "$SEED"

if [ ! -f "$PAW_JAR" ]; then
  echo "==> Extracting paw.jar from agentscope-paw:local image"
  CONTAINER=$(docker create agentscope-paw:local)
  docker cp "$CONTAINER:/app/paw.jar" "$PAW_JAR"
  docker rm "$CONTAINER" >/dev/null
fi

echo "==> Extracting dependency jars and building local Maven seed repo"
python3 - "$PAW_JAR" "$SEED" <<'PY'
import zipfile, os, re, shutil, sys
paw_jar, seed = sys.argv[1], sys.argv[2]
libs = os.path.join(os.path.dirname(paw_jar), 'libs')
os.makedirs(libs, exist_ok=True)
z = zipfile.ZipFile(paw_jar)
for n in z.namelist():
    if n.startswith('BOOT-INF/lib/') and n.endswith('.jar'):
        with z.open(n) as src, open(os.path.join(libs, os.path.basename(n)), 'wb') as dst:
            dst.write(src.read())
count = 0
for fn in sorted(os.listdir(libs)):
    if not (fn.startswith('agentscope-') and fn.endswith('.jar')):
        continue
    jar = zipfile.ZipFile(os.path.join(libs, fn))
    entries = [n for n in jar.namelist() if n.endswith('pom.xml') and 'META-INF/maven' in n]
    if not entries:
        continue
    pom = jar.read(entries[0]).decode('utf-8', 'replace')
    gid = re.search(r'<groupId>([^<]+)</groupId>', pom).group(1).strip()
    aid = re.search(r'<artifactId>([^<]+)</artifactId>', pom).group(1).strip()
    ver = re.search(r'<version>([^<]+)</version>', pom).group(1).strip()
    d = os.path.join(seed, *gid.split('.'), aid, ver)
    os.makedirs(d, exist_ok=True)
    shutil.copy(os.path.join(libs, fn), os.path.join(d, fn))
    with open(os.path.join(d, aid + '-' + ver + '.pom'), 'w', encoding='utf-8') as f:
        f.write(pom)
    count += 1
print(f'Seeded {count} agentscope artifacts into {seed}')
PY

echo "==> Done. Now run: docker compose up -d --build"
