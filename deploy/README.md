# OCI VM deployment

`compose.yaml` runs the Kotlin API and the Python worker as separate containers. Caddy is the only public entry point; the worker has no public port.

## First deployment

1. On the OCI VM, clone this repository and change into it.
2. Find the Docker network used by the existing Caddy container with `docker network ls`. Set its name as `CADDY_NETWORK` in a local `.env` file; the default is `caddy`.
3. Create the runtime secret file with `cp deploy/oci.env.example deploy/oci.env`, then replace every placeholder. Do not commit this file.
4. Add `deploy/Caddyfile.backoffice` to the existing Caddyfile. Set `BACKOFFICE_API_DOMAIN`, point its DNS A record at this VM, and reload Caddy after validating the configuration.
5. Start the API with `docker compose up -d --build backoffice-api`, then confirm `https://<api-domain>/api/health` returns `{"ok":true}`.

## Worker operation

Start the scheduled worker only after reviewing its external publishing settings:

```bash
docker compose --profile worker up -d --build backoffice-worker
docker compose run --rm backoffice-worker --mode keyword
```

Do not run `--mode posting` until the review/approval workflow is connected to PostgreSQL.

## Current data boundary

The OCI profile configures the Supabase JDBC connection for the API. Business data is still stored by the existing JSON and SQLite code, so PostgreSQL migrations and repository replacements are required before Supabase becomes the system of record.
The API deliberately does not launch Python in OCI; use the worker container until a durable job queue is added.
