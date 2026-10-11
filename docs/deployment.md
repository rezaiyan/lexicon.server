# Deployment

Production runs on a VPS with Docker Compose (Postgres, the app, Metabase) behind Caddy.

```
PR ──► CI: build, detekt, tests, coverage, Docker build check, workflow/script lint
main ─► CI: same, then publish ghcr.io/rezaiyan/lexicon.server:sha-<commit> and :latest
     └► Deploy (once enabled): SSH to the VPS, scripts/deploy.sh sha-<commit>
           pull image → restart app → wait for /api/v1/health → roll back if unhealthy
tag v1.2.3 ─► Release: GitHub release with the jar, image :1.2.3 / :v1.2.3
```

The VPS never runs Gradle: it pulls the image CI built and tested.

## One-time VPS setup

1. **Clone the repo** where the stack lives (keeps `docker-compose.yml` and `scripts/` in step;
   `deploy.sh` runs `git pull --ff-only` before each deploy):
   ```
   git clone https://github.com/rezaiyan/lexicon.server.git /opt/lexicon && cd /opt/lexicon
   cp env.example .env   # then fill it in, including APP_SECURITY_ADMINAPIKEY
   ```
   Moving an existing setup: copy its `.env` here; the Docker volumes (database, JWT keys,
   avatars) are found by name as long as the directory name stays the same, so either keep
   the old directory name or run `git clone` into the old directory.
2. **Let the VPS pull the image.** New GHCR packages are private. Either make the package public
   (GitHub → your profile → Packages → lexicon.server → Package settings → Change visibility),
   or log in once on the VPS with a token that has only `read:packages`:
   ```
   echo <token> | docker login ghcr.io -u rezaiyan --password-stdin
   ```
3. **First deploy by hand:**
   ```
   ./scripts/deploy.sh latest
   ```

## Turning on automatic deploys

1. On the VPS, create a deploy user that can run Docker, and authorize a new SSH key for it:
   ```
   ssh-keygen -t ed25519 -f deploy_key -N "" -C github-deploy
   # append deploy_key.pub to ~deploy/.ssh/authorized_keys
   ssh-keyscan -t ed25519 <vps-host>        # output = DEPLOY_KNOWN_HOSTS
   ```
2. GitHub → repo → Settings → Environments → **New environment `production`**. Optionally add
   yourself as a required reviewer to approve every deploy. Add:
   - Secrets: `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY` (contents of `deploy_key`),
     `DEPLOY_KNOWN_HOSTS` (the `ssh-keyscan` line)
   - Variable: `DEPLOY_PATH` (e.g. `/opt/lexicon`)
3. Settings → Secrets and variables → Actions → Variables: **`DEPLOY_ENABLED` = `true`**.

From then on every green main commit deploys itself. Set `DEPLOY_ENABLED` to anything else to
pause automatic deploys.

## Rolling back

Actions → Deploy → Run workflow → tag `sha-<good commit>` (or a release tag such as `v1.2.3`).
Or on the VPS: `./scripts/deploy.sh sha-<good commit>`. Image tags are listed under the repo's
Packages.

`deploy.sh` rolls back by itself when a new container doesn't become healthy within 3 minutes.

## Backups

`scripts/backup-db.sh` writes a gzipped `pg_dump` to `backups/` and keeps 14 days. Schedule it
nightly on the VPS (`crontab -e`):
```
15 3 * * * cd /opt/lexicon && ./scripts/backup-db.sh >> backups/backup.log 2>&1
```
The dumps sit on the same disk as the database: also copy `backups/` off the server
(rclone/rsync to object storage) so losing the VPS doesn't lose the data.

Restore:
```
gunzip -c backups/lexicondb-YYYYmmdd-HHMMSS.sql.gz | \
  docker compose exec -T postgres psql -U lexicon_user -d lexicondb
```
The dump drops and recreates its tables, so it restores over an existing schema.
