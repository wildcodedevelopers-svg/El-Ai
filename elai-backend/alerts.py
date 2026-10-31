"""
EL-AI email helpers (admin sign-in alerts + user emails such as password-reset codes).

Uses Resend's HTTPS API (Render's free plan blocks SMTP). Never put passwords or tokens in an email.

Render environment variables:
  RESEND_API_KEY  your Resend API key
  ALERT_TO        where sign-in alerts go (default elhamcodes@gmail.com)
  ALERT_FROM      sender, e.g. "EL-AI <no-reply@yourdomain.com>"
                  (default onboarding@resend.dev can only deliver to your own Resend account email;
                   to email other users you must verify a domain in Resend)
"""
import json
import os
import threading
import urllib.request
from datetime import datetime, timezone

ALERT_TO = os.getenv("ALERT_TO", "elhamcodes@gmail.com")
ALERT_FROM = os.getenv("ALERT_FROM", "EL-AI <onboarding@resend.dev>")


def _post_email(to: str, subject: str, text: str) -> None:
    key = os.getenv("RESEND_API_KEY")
    if not key:
        print("email skipped: RESEND_API_KEY is not set")
        return
    payload = json.dumps(
        {"from": ALERT_FROM, "to": [to], "subject": subject, "text": text}
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
    except Exception as exc:  # an email problem must never break the app
        print("email failed:", exc)


def send_email(to: str, subject: str, text: str) -> None:
    """Send an email in the background."""
    threading.Thread(target=_post_email, args=(to, subject, text), daemon=True).start()


def notify_login(email: str, name: str, request, kind: str = "Sign-in") -> None:
    """Tell the administrator about a sign-in. Call after a successful login/registration."""
    fwd = request.headers.get("x-forwarded-for", "")
    ip = fwd.split(",")[0].strip() or (request.client.host if request.client else "unknown")
    agent = request.headers.get("user-agent", "unknown")
    when = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    body = (
        f"{kind}\n\nName:   {name or '-'}\nEmail:  {email}\nTime:   {when}\n"
        f"IP:     {ip}\nDevice: {agent}\n"
    )
    send_email(ALERT_TO, f"EL-AI {kind}: {email}", body)
