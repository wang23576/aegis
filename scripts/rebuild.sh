#!/bin/sh
# Rebuild backend fat jar and restart the local test container.
set -e
MSYS_NO_PATHCONV=1 docker run --rm \
  -v 'D:\github\aegis\backend:/workspace' \
  -v aegis-m2:/root/.m2/repository \
  -v 'D:\github\aegis\docker\maven-settings.xml:/root/.m2/settings.xml:ro' \
  -w /workspace maven:3.9-eclipse-temurin-17 \
  sh -c 'mvn -B -ntp -s /root/.m2/settings.xml -DskipTests clean package' 2>&1 | tail -6
docker rm -f aegis-test >/dev/null 2>&1 || true
# free port 8081 if the compose deployment is running (it owns the same port)
docker compose down >/dev/null 2>&1 || true
MSYS_NO_PATHCONV=1 docker run -d --name aegis-test -p 8081:8081 \
  -v 'D:\github\aegis\backend\target:/app:ro' \
  -v aegis-data:/data \
  -v 'D:\github\aegis\docker\model.env:/model.env:ro' \
  -e AEGIS_DB_PATH=/data/aegis.db \
  -e AEGIS_WORKSPACE_ROOT=/data/workspaces \
  -w /app eclipse-temurin:17-jre \
  sh -c 'set -a; . /model.env; set +a; java -jar aegis-backend-1.0.0.jar' >/dev/null 2>&1
echo "restarted; waiting for boot"
sleep 9
docker logs aegis-test 2>&1 | grep -iE 'ERROR|Exception|Started Aegis' | tail -8
