#!/usr/bin/env python3
"""Sets up realm finns for core's Keycloak extension, through Keycloak's Admin REST API. Safe to run again.

    ssh -t root@keycloak-1 python3 /root/setup-realm.py [https://test-auth.finnsbeachclub.com] [finns]

Asks for a master-realm admin's username, password and, if they use one, OTP code (which kcadm.sh can't
send). Then:

- adds the finns-core event listener, keeping the realm's others;
- turns admin events on (Keycloak only passes them to listeners then);
- creates the confidential client finns-core-sync, service account only, with realm-management's view-users
  and nothing else, and saves its secret to /root/finns-core-sync.secret (root only) for core's
  FINNS_KEYCLOAK_CLIENT_SECRET.

Only the Python standard library, so it runs on a bare server.
"""
import getpass
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

SERVER = (sys.argv[1] if len(sys.argv) > 1 else "https://test-auth.finnsbeachclub.com").rstrip("/")
REALM = sys.argv[2] if len(sys.argv) > 2 else "finns"
CLIENT_ID = "finns-core-sync"
SECRET_FILE = "/root/finns-core-sync.secret"


def request(method, path, token=None, body=None, form=None):
    headers = {"Accept": "application/json"}
    data = None
    if token:
        headers["Authorization"] = "Bearer " + token
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(SERVER + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            text = response.read().decode()
            return json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {path}: {e.code} {e.read().decode()[:300]}")


def main():
    username = input("Keycloak admin username (realm master): ")
    password = getpass.getpass("Password: ")
    otp = getpass.getpass("OTP code (empty if none): ").strip()
    form = {"grant_type": "password", "client_id": "admin-cli", "username": username, "password": password}
    if otp:
        form["otp"] = otp
        form["totp"] = otp
    token = request("POST", "/realms/master/protocol/openid-connect/token", form=form)["access_token"]
    admin = f"/admin/realms/{REALM}"

    config = request("GET", admin + "/events/config", token)
    listeners = config.get("eventsListeners") or []
    if "finns-core" not in listeners:
        listeners.append("finns-core")
    config["eventsListeners"] = listeners
    config["adminEventsEnabled"] = True
    request("PUT", admin + "/events/config", token, body=config)
    print(f"Event listeners: {listeners}; admin events on")

    def client():
        found = request("GET", admin + "/clients?" + urllib.parse.urlencode({"clientId": CLIENT_ID}), token)
        return found[0] if found else None

    if client() is None:
        request("POST", admin + "/clients", token, body={
            "clientId": CLIENT_ID,
            "protocol": "openid-connect",
            "publicClient": False,
            "serviceAccountsEnabled": True,
            "standardFlowEnabled": False,
            "implicitFlowEnabled": False,
            "directAccessGrantsEnabled": False,
        })
        print(f"Created client {CLIENT_ID}")
    sync = client()

    account = request("GET", f"{admin}/clients/{sync['id']}/service-account-user", token)
    management = request("GET", admin + "/clients?clientId=realm-management", token)[0]
    view_users = request("GET", f"{admin}/clients/{management['id']}/roles/view-users", token)
    request("POST", f"{admin}/users/{account['id']}/role-mappings/clients/{management['id']}", token,
            body=[view_users])
    print(f"{CLIENT_ID} can view users")

    secret = request("GET", f"{admin}/clients/{sync['id']}/client-secret", token)["value"]
    fd = os.open(SECRET_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(secret)
    print(f"Saved {CLIENT_ID}'s secret to {SECRET_FILE}")


if __name__ == "__main__":
    main()
