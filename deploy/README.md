# SecureTalk Deployment Guide (deploy/)

Stack: `api` (FastAPI/uvicorn, non-root) + `postgres:16` + `redis:7` + `nginx` (TLS termination, WSS proxy). See docs/ARCHITECTURE.md §10.

## 1. First-time setup

```bash
cd deploy
cp .env.example .env
# Generate secrets (do this for SECRET_KEY, POSTGRES_PASSWORD, ADMIN_TOKEN):
python -c "import secrets; print(secrets.token_urlsafe(48))"
$EDITOR .env
```

### TLS setup

Test (self-signed):

```bash
mkdir -p certs
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout certs/privkey.pem -out certs/fullchain.pem \
  -subj "/CN=securetalk.local" \
  -addext "subjectAltName=DNS:securetalk.local,IP:127.0.0.1"
```

Production (certbot, HTTP-01 webroot):

```bash
mkdir -p certbot/www
# Set TLS_DOMAIN, start nginx on :80 first (the conf serves ACME challenges)
docker run --rm -v "$PWD/certs:/etc/letsencrypt/out" \
  -v "$PWD/certbot/www:/var/www/certbot" \
  certbot/certbot certonly --webroot -w /var/www/certbot \
  -d "$TLS_DOMAIN" --email admin@example.com --agree-tos --non-interactive
cp certs/live/"$TLS_DOMAIN"/fullchain.pem certs/live/"$TLS_DOMAIN"/privkey.pem certs/
# Renewal: re-run the certonly command monthly (cron) then `docker compose restart nginx`.
```

## 2. Deploy

```bash
docker compose build
docker compose up -d
docker compose exec api alembic upgrade head   # migrations (or use scripts/deploy.sh)
curl -fsS https://<host>/health                # expect 200
```

## 3. Backups (Postgres)

Backup:

```bash
docker compose exec -T postgres \
  pg_dump -U securetalk -Fc securetalk > backup_$(date +%Y%m%d_%H%M%S).dump
```

Restore:

```bash
docker compose stop api
cat backup_YYYYMMDD_HHMMSS.dump | \
  docker compose exec -T postgres pg_restore -U securetalk --clean --if-exists -d securetalk
docker compose start api
```

Note: the DB holds users, channels, security events, and key-envelope metadata only — never audio content (ARCHITECTURE §7). Redis is ephemeral; nothing to back up.

## 4. Rollback

```bash
./scripts/deploy.sh rollback            # previous image tag + alembic downgrade -1
# Manual:
docker compose down api
docker tag securetalk/api:<prev> securetalk/api:latest
docker compose up -d api
docker compose exec api alembic downgrade -1   # only if the previous release requires it
```

Keep the last 2 image tags on the host; never `downgrade` more than one migration without checking the prior release's code.

## 5. Firewall notes

- Expose **only** 80/tcp (ACME + redirect) and 443/tcp. Never expose Postgres (5432) or Redis (6379) — they are on the internal compose network only.
- UFW example: `ufw allow 80,443/tcp && ufw deny 5432 && ufw deny 6379`
- Android LAN-host mode (P2P) traffic does not traverse this server; only HTTPS/WSS relay does.

## 6. Security checklist

- `.env` is chmod 600, gitignored, never committed.
- Rotate `SECRET_KEY` only with a token-invalidation plan (it signs auth tokens).
- Health endpoints: `/health` is public/liveness; `/health/details` is admin-gated (`ADMIN_TOKEN`).
