#!/usr/bin/env bash
# Deploys one image tag of the app on the VPS, and rolls back if it doesn't come up healthy.
#
# Run from the directory holding docker-compose.yml and .env:
#   ./scripts/deploy.sh sha-1a2b3c4      # a commit built by CI
#   ./scripts/deploy.sh latest           # whatever main last published
# The Deploy workflow runs this over SSH; it works the same by hand.
#
# Only the app container is replaced. Postgres and Metabase are left running.
set -euo pipefail

TAG="${1:?usage: deploy.sh <image-tag>  (e.g. sha-1a2b3c4 or latest)}"
SERVICE=app
CONTAINER=lexicon-server
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-180}"

log() { echo "[deploy $(date -u +%H:%M:%S)] $*"; }

[ -f docker-compose.yml ] || { log "docker-compose.yml not found in $(pwd)"; exit 1; }
[ -f .env ] || { log ".env not found in $(pwd)"; exit 1; }

# Keep the compose file and scripts in step with the code being deployed. Refuses (and so stops
# the deploy) when the checkout has local edits, rather than overwriting them.
if [ -d .git ]; then
  log "Updating checkout"
  git pull --ff-only --quiet
fi

# The tag lives in .env, so a later plain `docker compose up -d` keeps running the same build
set_tag() {
  if grep -q '^APP_IMAGE_TAG=' .env; then
    sed -i "s|^APP_IMAGE_TAG=.*|APP_IMAGE_TAG=$1|" .env
  else
    printf '\nAPP_IMAGE_TAG=%s\n' "$1" >> .env
  fi
}

previous="$(grep -oP '^APP_IMAGE_TAG=\K.*' .env || true)"
log "Deploying $TAG (previous: ${previous:-none})"

set_tag "$TAG"
# An older docker-compose.yml without the `image:` line would rebuild/reuse a local image and
# "succeed" without deploying anything; refuse instead
image="$(docker compose config --images "$SERVICE" 2>/dev/null || true)"
if [[ "$image" != *":$TAG" ]]; then
  log "docker-compose.yml resolves $SERVICE to '${image:-nothing}', not a :$TAG image."
  log "Update docker-compose.yml (the app service needs image: ...:\${APP_IMAGE_TAG:-latest})."
  if [ -n "$previous" ]; then set_tag "$previous"; else sed -i '/^APP_IMAGE_TAG=/d' .env; fi
  exit 1
fi
if ! docker compose pull --quiet "$SERVICE"; then
  log "Pull failed; keeping ${previous:-the current container}"
  [ -n "$previous" ] && set_tag "$previous"
  exit 1
fi
docker compose up -d --no-build --no-deps "$SERVICE"

# The image's HEALTHCHECK (GET /api/v1/health) decides when a container is good
wait_for_health() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
  status=starting
  while [ $SECONDS -lt $deadline ]; do
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$CONTAINER" 2>/dev/null || echo missing)"
    case "$status" in
      healthy|unhealthy) return ;;
    esac
    sleep 5
  done
}

wait_for_health

if [ "$status" = healthy ]; then
  revision="$(docker inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$CONTAINER" 2>/dev/null || true)"
  log "Healthy: $TAG is live (commit ${revision:-unknown})"
  docker image prune -f --filter "until=168h" > /dev/null || true
  exit 0
fi

log "New container is $status after ${HEALTH_TIMEOUT_SECONDS}s; last log lines:"
docker logs --tail 40 "$CONTAINER" 2>&1 || true

if [ -n "$previous" ] && [ "$previous" != "$TAG" ]; then
  log "Rolling back to $previous"
  set_tag "$previous"
  docker compose up -d --no-build --no-deps "$SERVICE"
  wait_for_health
  log "Rolled back to $previous: container is $status"
else
  log "No previous tag to roll back to"
fi
exit 1
