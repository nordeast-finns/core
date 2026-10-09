# Keycloak: installing core's extension by hand

core keeps an exact copy of each customer's name and email (see [Keycloak sync](../README.md#keycloak-sync)).
For that, Keycloak (realm `finns`) runs this extension, the `finns-core` event listener, which tells core
which user changed, and core reads users through a service account. This is how to set it up, or
update it, on a server or on your own machine.

What's involved:

| Where | What |
|---|---|
| Keycloak server | the extension jar in `providers/`, and its `url` and `token` options |
| Realm `finns` | the `finns-core` event listener, admin events on, and the client `finns-core-sync` |
| core | `FINNS_KEYCLOAK_WEBHOOK_TOKEN` (the same `token`) and `FINNS_KEYCLOAK_CLIENT_SECRET` |

## 1. Build the jar

The extension is a plain Maven project inside core's repo, built separately from core:

```sh
cd core
./mvnw -f keycloak-extension/pom.xml package
# → keycloak-extension/target/finns-core-keycloak-1.0.0.jar
```

Set `keycloak.version` in `keycloak-extension/pom.xml` to the server's version first
(`/opt/keycloak/bin/kc.sh --version`). The jar bundles nothing, so it's only a few kilobytes.

## 2. Install it on keycloak-1

Keycloak 26.8.0 runs on the DigitalOcean droplet `keycloak-1` (`ssh root@keycloak-1`, over Tailscale), with
Docker Compose in `/opt/keycloak`:

- `Dockerfile`: a builder stage runs `kc.sh build`, and Keycloak starts with `start --optimized`.
- `compose.yaml`: services `keycloak` and `caddy`. Caddy ends TLS for `test-auth.finnsbeachclub.com`.
- `.env`: the variables `compose.yaml` uses.

`install.sh` puts the jar in `providers/` and copies it into the image's builder stage. It also gives the
`keycloak` service the extension's options from `.env`: `FINNS_CORE_URL`, and a random
`FINNS_CORE_TOKEN` it creates the first time. Then it rebuilds the image and restarts Keycloak, so sign-in
is down for about a minute. Every file it changes is backed up as `*.bak-<timestamp>`, and running it again
only replaces the jar. From your machine, in `core`, `deploy.sh` copies the jar, `install.sh` and `setup-realm.py`
to `/root` and runs `install.sh` there:

```sh
bash keycloak-extension/deploy.sh                      # root@keycloak-1, https://test-api.finnsbali.com
bash keycloak-extension/deploy.sh root@other-host https://core.example.com
```

It ends by printing Keycloak's `finns-core: telling https://test-api.finnsbali.com about users of realm
finns` log line. If the options don't reach Keycloak, the log says
`finns-core: url and token … must be set` instead. The extension then does nothing, but Keycloak still
runs.

## 3. Install it on another Keycloak

For Keycloak 26 laid out differently, do by hand what `install.sh` does:

1. Copy the jar into Keycloak's `providers/` directory (`/opt/keycloak/providers/` in the official image).
2. Give the extension its options, as environment variables of the Keycloak process. The token is at
   least 32 characters (`openssl rand -hex 32`), and core gets the same one:

   ```sh
   KC_SPI_EVENTS_LISTENER__FINNS_CORE__URL=https://test-api.finnsbali.com   # core's origin
   KC_SPI_EVENTS_LISTENER__FINNS_CORE__TOKEN=<the token>
   # KC_SPI_EVENTS_LISTENER__FINNS_CORE__REALM=finns                       # the default
   ```

   `url` must be https; plain http is accepted only for `localhost`, `127.0.0.1` and
   `host.docker.internal`, for local runs.
3. If Keycloak starts with `--optimized`, run `kc.sh build` again. Then restart Keycloak.
4. Check the log for `finns-core: telling https://… about users of realm finns`. If you see
   `finns-core: url and token … must be set` instead, the options didn't reach Keycloak. In that case the
   extension does nothing, but Keycloak still runs.

## 4. Set up realm `finns`

On keycloak-1, `setup-realm.py` (copied in step 2) does all of this through Keycloak's Admin REST API.
It asks for a master-realm admin's username, password and OTP code (`kcadm.sh` can't send an OTP code),
so it needs a terminal (`-t`):

```sh
ssh -t root@keycloak-1 python3 /root/setup-realm.py
```

It saves the client secret to `/root/finns-core-sync.secret` (readable by root only). Running it again
changes nothing. By hand, in the admin console:

1. **Realm settings → Events → Event listeners**: add `finns-core`. Keep `jboss-logging`.
2. **Realm settings → Events → Admin events settings**: turn **Save events** on. Keycloak only passes admin
   events (staff editing or deleting users) to listeners when they're on.
3. **Clients → Create client** `finns-core-sync`, OpenID Connect:
   - **Client authentication** on.
   - **Service accounts roles** on.
   - Every other flow off.
4. **Clients → finns-core-sync → Service accounts roles → Assign role**: filter by clients, then pick
   `realm-management` `view-users`. Nothing else.
5. **Clients → finns-core-sync → Credentials**: copy the **Client secret**.

Admin events are saved in Keycloak's database while they're on. Set an expiration under **Admin events
settings** if they shouldn't be kept forever.

## 5. Give core its two secrets

On DigitalOcean, App Platform → app `core-api` → Settings → environment variables (encrypted):

- `FINNS_KEYCLOAK_WEBHOOK_TOKEN`: `FINNS_CORE_TOKEN` from `/opt/keycloak/.env` on keycloak-1.
- `FINNS_KEYCLOAK_CLIENT_SECRET`: the client secret, in `/root/finns-core-sync.secret` on keycloak-1.

Core reads users from the realm in `FINNS_AUTH_ISSUER`, which is already set. Redeploy core. It refuses to
start without both secrets.

## 6. Check it works

- **New sign-up:** register a test user, or create one in the admin console. Within a second they appear
  in the Admin Console's Customers screen, with their name and email.
- **Name change:** change their name. The Customers screen shows the new one.
- **Sign-out:** sign out of the app. The name stays.
- **Deletion:** delete the user. They show as **Deleted account**.
- **Logs:**
  - Keycloak logs `finns-core: notice failed …` when core can't be reached. It retries after 1, 5 and
    30 seconds.
  - Core logs `keycloak.sync_failed` when it can't read Keycloak.
  - Core logs `keycloak.reconciled users=… missing=…` after each reconciliation, every 10 minutes and
    1 minute after startup. The first run also fills in customers from before the sync existed.

Nothing is lost if core or Keycloak is down for a while: reconciliation copies every user again.

## Updating the extension

1. Rebuild the jar (step 1).
2. Run `deploy.sh` again (step 2). Only the jar changes, and Keycloak restarts.

The realm settings, the token and core's secrets stay as they are.

## On your own machine

To try the whole flow locally, run Keycloak 26 with the jar mounted, and point it at core's dev mode:

```sh
docker run --name keycloak -p 8180:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  -e KC_SPI_EVENTS_LISTENER__FINNS_CORE__URL=http://host.docker.internal:8080 \
  -e KC_SPI_EVENTS_LISTENER__FINNS_CORE__TOKEN=dev-only-keycloak-token-not-a-secret-0 \
  -v "$PWD/keycloak-extension/target/finns-core-keycloak-1.0.0.jar:/opt/keycloak/providers/finns-core-keycloak.jar" \
  quay.io/keycloak/keycloak:26.8.0 start-dev
```

Then set up realm `finns` as in step 4, against `http://localhost:8180`, and start core with it:

```sh
FINNS_KEYCLOAK_CLIENT_SECRET=<secret> FINNS_KEYCLOAK_RECONCILE_EVERY=1m \
  ./mvnw quarkus:dev -D%dev.finns.keycloak.issuer=http://localhost:8180/realms/finns
```

(`dev-only-keycloak-token-not-a-secret-0` is core's dev token, `%dev.finns.keycloak.webhook-token`.)
