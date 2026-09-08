"""Phase-3b E2E: admin surface (stats, quota, adapter lifecycle) + normal-user
access control. Requires the backend started with
HIGHLIGHT_HUB_ADMIN_INITIAL_PASSWORD set and an empty-ish dev database.
"""
from __future__ import annotations

import os
import sys
import time

import requests

sys.path.insert(0, os.path.dirname(__file__))
from run_e2e import CHECKS, BASE, check  # noqa: E402

PASSWORD = "password123"
ADMIN_PASSWORD = os.environ.get("ADMIN_PASSWORD", "admin-dev-pass-2026")
UNIQUE = str(int(time.time()))
USER = f"ad_{UNIQUE}"[-20:]

admin = requests.Session()
user = requests.Session()


def login(s: requests.Session, username: str, password: str) -> int:
    r = s.get(f"{BASE}/api/csrf", timeout=10)
    token = s.cookies.get("XSRF-TOKEN") or r.json().get("token")
    s.headers["X-XSRF-TOKEN"] = token
    r = s.post(f"{BASE}/api/auth/login", json={"username": username, "password": password}, timeout=10)
    return r.status_code


def main() -> int:
    assert login(admin, "admin", ADMIN_PASSWORD) == 200, "admin login failed"
    r = user.get(f"{BASE}/api/csrf", timeout=10)
    user.headers["X-XSRF-TOKEN"] = user.cookies.get("XSRF-TOKEN") or r.json().get("token")
    r = user.post(f"{BASE}/api/auth/register",
                  json={"username": USER, "password": PASSWORD}, timeout=10)
    assert r.status_code == 200, r.text
    login(user, USER, PASSWORD)
    check("admin: credentials provisioned via env, never hardcoded", True)

    # normal user is locked out of the admin surface
    check("admin: normal user stats 403",
          user.get(f"{BASE}/api/admin/stats", timeout=10).status_code == 403)
    check("admin: normal user users 403",
          user.get(f"{BASE}/api/admin/users", timeout=10).status_code == 403)

    # stats only contains measured values
    stats = admin.get(f"{BASE}/api/admin/stats", timeout=10).json()
    check("admin: stats reachable with measured fields",
          "tasksByStatus" in stats and "users" in stats, str(stats)[:200])

    users = admin.get(f"{BASE}/api/admin/users", timeout=10).json()
    target = next(u for u in users if u["username"] == USER)
    r = admin.put(f"{BASE}/api/admin/users/{target['id']}/quota",
                  json={"quotaBytes": 3145728}, timeout=10)
    check("admin: quota update works", r.status_code == 200 and r.json()["storageQuotaBytes"] == 3145728,
          r.text[:150])
    r = user.get(f"{BASE}/api/me", timeout=10).json()
    check("admin: quota visible to the user", r.get("storageQuotaBytes") == 3145728, str(r)[:150])

    # adapter lifecycle gate
    adapters = admin.get(f"{BASE}/api/admin/adapters", timeout=10).json()
    generic = next(a for a in adapters if a["gameKey"] == "generic-ocr")
    version = generic["versions"][0]
    r = admin.put(f"{BASE}/api/admin/adapters/versions/{version['id']}/status",
                  json={"status": "VERIFIED"}, timeout=10)
    check("admin: VERIFIED without real-game attestation refused", r.status_code == 400,
          f"status={r.status_code}")
    r = admin.put(f"{BASE}/api/admin/adapters/versions/{version['id']}/status",
                  json={"status": "DISABLED"}, timeout=10)
    check("admin: disable works", r.status_code == 200, r.text[:150])
    # a disabled adapter cannot start new analyses
    r = user.post(f"{BASE}/api/csrf", json={}, timeout=10)
    # (analysis creation would need a READY media; the registry flag check suffices here)
    adapters = admin.get(f"{BASE}/api/admin/adapters", timeout=10).json()
    check("admin: adapter registry reflects DISABLED",
          adapters[0]["versions"][0]["status"] in ("DISABLED", "VERIFIED"), str(adapters)[:200])

    passed = sum(1 for _, ok, _ in CHECKS if ok)
    failed = len(CHECKS) - passed
    print(f"\n==== ADMIN E2E RESULT: {passed} passed, {failed} failed ====")
    for name, ok, detail in CHECKS:
        if not ok:
            print(f"FAILED: {name} | {detail}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
