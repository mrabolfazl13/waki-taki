# SecureTalk web dashboard — multi-stage: node build -> nginx static serve.
# NOTE: authored but NOT verified locally (no Docker daemon); validated in CI (docker.yml).

# ---- build ----
FROM node:20-alpine AS build

WORKDIR /build

COPY package.json package-lock.json ./
# npm ci occasionally hangs on GH runners ("Exit handler never called!");
# npm install honours the lockfile but tolerates registry hiccups better.
ENV npm_config_fetch_retries=5 npm_config_fetch_timeout=60000
RUN npm install --no-audit --no-fund

COPY . .
RUN npm run build

# ---- serve ----
FROM nginx:1.27-alpine AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q --spider http://127.0.0.1/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
