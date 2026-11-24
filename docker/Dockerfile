# syntax=docker/dockerfile:1
# Aegis production image. Build context = repository root (aegis/).
# Builds frontend + backend using a pre-seeded Maven repo (agentscope SNAPSHOTs
# are not on public Maven Central; see README / scripts for preparing aegis-m2).

# ---------- Stage 1: frontend ----------
FROM node:20-alpine AS frontend
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci --registry=https://registry.npmmirror.com || npm install --registry=https://registry.npmmirror.com
COPY frontend/ ./
RUN npm run build

# ---------- Stage 2: backend ----------
FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /build
COPY docker/maven-settings.xml /root/.m2/settings.xml
COPY backend/ ./backend/
# Bundle the built SPA into the backend jar so a single jar serves both API and UI.
COPY --from=frontend /build/frontend/dist ./backend/src/main/resources/static/
# Agentscope SNAPSHOT jars are supplied via the aegis-m2 cache mount at build time.
RUN --mount=type=cache,target=/root/.m2/repository,id=aegis-m2 \
    --mount=type=bind,source=docker/m2-seed,target=/m2-seed \
    sh -c 'cp -a /m2-seed/. /root/.m2/repository/ && \
           cd backend && mvn -B -ntp -s /root/.m2/settings.xml -DskipTests clean package'

# ---------- Stage 3: runtime ----------
FROM eclipse-temurin:17-jre
ENV AEGIS_PORT=8081 \
    AEGIS_DB_PATH=/data/aegis.db \
    AEGIS_WORKSPACE_ROOT=/data/workspaces
WORKDIR /app
COPY --from=backend /build/backend/target/aegis-backend-1.0.0.jar /app/aegis.jar
RUN mkdir -p /data/workspaces
EXPOSE 8081
VOLUME ["/data"]
ENTRYPOINT ["java", "-jar", "/app/aegis.jar"]
