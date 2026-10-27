"""
EL-AI login alerts: emails the administrator every time someone signs in.

Uses Resend's HTTPS API (Render's free plan blocks SMTP, so Gmail SMTP won't work there).
Never include passwords or tokens in the email.

Setup
1. Create a free account at resend.com using elhamcodes@gmail.com, then create an API key.
2. In Render -> your backend -> Environment, add:
      RESEND_API_KEY = <your key>
      ALERT_TO       = elhamcodes@gmail.com      (optional, this is the default)
3. Copy this file next to your main backend file, then see the usage example at the bottom.
"""
import json
import os
import threading
import urllib.request
from datetime import datetime, timezone

ALERT_TO = os.getenv("ALERT_TO", "elhamcodes@gmail.com")
ALERT_FROM = os.getenv("ALERT_FROM", "EL-AI <onboarding@resend.dev>")


def _post_alert(subject: str, text: str) -> None:
    key = os.getenv("RESEND_API_KEY")
    if not key:
        print("login alert skipped: RESEND_API_KEY is not set")
        return
    payload = json.dumps(
        {"from": ALERT_FROM, "to": [ALERT_TO], "subject": subject, "text": text}
    ).encode()
    req = urllib.request.Request(
        "https://api.resend.com/emails",
        data=payload,
        method="POST",
        headers={
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
            "User-Agent": "elai-backend/1.0",
        },
    )
    try:
        urllib.request.urlopen(req, timeout=15).read()
    except Exception as exc:  # never let an email problem break sign-in
        print("login alert failed:", exc)


def notify_login(email: str, name: str, request, kind: str = "Sign-in") -> None:
    """Call right after a successful login/registration. Runs in the background."""
    fwd = request.headers.get("x-forwarded-for", "")
    ip = fwd.split(",")[0].strip() or (request.client.host if request.client else "unknown")
    agent = request.headers.get("user-agent", "unknown")
    when = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    body = (
        f"{kind}\n\n"
        f"Name:   {name or '-'}\n"
        f"Email:  {email}\n"
        f"Time:   {when}\n"
        f"IP:     {ip}\n"
        f"Device: {agent}\n"
    )
    threading.Thread(
        target=_post_alert, args=(f"EL-AI {kind}: {email}", body), daemon=True
    ).start()


# ---------------------------------------------------------------------------
# Usage (FastAPI example). Add `request: Request` to the route and ONE line
# after the password has been checked:
#
#   from fastapi import Request
#   from login_alerts import notify_login
#
#   @app.post("/auth/login")
#   async def login(data: LoginIn, request: Request):
#       ...your existing checks...
#       notify_login(user.email, user.name, request)           # <- add this
#       return {"access_token": token}
#
# Do the same in /auth/register with kind="New account".
# ---------------------------------------------------------------------------
