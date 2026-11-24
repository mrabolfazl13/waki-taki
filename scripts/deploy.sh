#!/usr/bin/env bash
# SecureTalk deploy script: pull/build, migrate, up, health check, rollback.
# Usage:
#   ./scripts/deploy.sh [deploy|rollback|health]
# Safe mode: exit on error, undefined vars, failed pipelines.
set -euo pipefail

cd "$(dirname "$0")/../deploy"

COMPOSE="docker compose"
HEALTH_URL="${HEALTH_URL:-https://127.0.0.1/health}"
HEALTH_RETRIES="${HEALTH_RETRIES:-12}"
HEALTH_INTERVAL="${HEALTH_INTERVAL:-5}"

log() { echo "[deploy] $(date -u +%H:%M:%SZ) $*"; }

health_check() {
    local i=0
    while [ "$i" -lt "$HEALTH_RETRIES" ]; do
        if curl -kfsS "$HEALTH_URL" >/dev/null 2>&1; then
            log "health check OK"
            return 0
        fi
        i=$((i + 1))
        sleep "$HEALTH_INTERVAL"
    done
    log "health check FAILED after $((HEALTH_RETRIES * HEALTH_INTERVAL))s"
    return 1
}

cmd_deploy() {
    [ -f .env ] || { log "ERROR: deploy/.env missing (copy from .env.example)"; exit 1; }
    log "building images..."
    $COMPOSE build
    log "tagging previous image for rollback..."
    docker image inspect securetalk/api:latest >/dev/null 2>&1 \
        && docker tag securetalk/api:latest securetalk/api:previous || true
    log "pulling latest base images..."
    $COMPOSE pull --ignore-pull-failures || true
    log "running migrations..."
    $COMPOSE up -d postgres redis
    $COMPOSE run --rm api alembic upgrade head
    log "starting stack..."
    $COMPOSE up -d --remove-orphans
    if health_check; then
        log "deploy complete"
    else
        log "health check failed -> automatic rollback"
        cmd_rollback
        exit 1
    fi
}

cmd_rollback() {
    log "rolling back api to securetalk/api:previous..."
    docker image inspect securetalk/api:previous >/dev/null 2>&1 \
        || { log "no previous image available"; exit 1; }
    docker tag securetalk/api:previous securetalk/api:latest
    $COMPOSE up -d api
    log "downgrading one migration step (if the previous release requires it)..."
    $COMPOSE run --rm api alembic downgrade -1 || log "downgrade skipped/failed - verify manually"
    health_check || { log "rollback health check failed - INVESTIGATE"; exit 1; }
    log "rollback complete"
}

cmd_health() {
    health_check
}

case "${1:-deploy}" in
    deploy)   cmd_deploy ;;
    rollback) cmd_rollback ;;
    health)   cmd_health ;;
    *) echo "Usage: $0 [deploy|rollback|health]"; exit 2 ;;
esac
