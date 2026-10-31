"""
EL-AI email helpers: admin sign-in alerts and user emails (password-reset codes).

Sends over HTTPS APIs (Render's free plan blocks SMTP, so Gmail SMTP will not work there).
Pick ONE provider by setting its variables in Render -> Environment:

  Option A - Brevo (no domain needed, free 300 emails/day)
     BREVO_API_KEY   your Brevo API key (SMTP & API -> API keys)
     SENDER_EMAIL    a sender you verified in Brevo (Senders & IP -> Senders)
     SENDER_NAME     optional, default "EL-AI"

  Option B - Resend (needs a domain you own, verified in Resend)
     RESEND_API_KEY  your Resend API key
     ALERT_FROM      e.g. "EL-AI <no-reply@yourdomain.com>"

  Both
     ALERT_TO        where sign-in alerts go (default elhamcodes@gmail.com)

Never put passwords or tokens in an email.
"""
import json
import os
import threading
import urllib.error
import urllib.request
from datetime import datetime, timezone

ALERT_TO = os.getenv("ALERT_TO", "elhamcodes@gmail.com")


def email_provider() -> str:
    if os.getenv("BREVO_API_KEY") and os.getenv("SENDER_EMAIL"):
        return "brevo"
    if os.getenv("RESEND_API_KEY"):
        return "resend"
    return "none"


def _post(url: str, payload: dict, headers: dict) -> bool:
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        method="POST",
        headers={"Content-Type": "application/json", "User-Agent": "elai-backend/1.0", **headers},
    )
    try:
        urllib.request.urlopen(req, timeout=20).read()
        return True
    except urllib.error.HTTPError as exc:
        body = exc.read()[:300].decode("utf-8", "ignore")
        print(f"email failed: HTTP {exc.code} {body}")  # visible in Render logs
    except Exception as exc:
        print("email failed:", exc)
    return False


def _send_now(to: str, subject: str, text: str) -> bool:
    provider = email_provider()
    if provider == "brevo":
        return _post(
            "https://api.brevo.com/v3/smtp/email",
            {
                "sender": {"name": os.getenv("SENDER_NAME", "EL-AI"), "email": os.environ["SENDER_EMAIL"]},
                "to": [{"email": to}],
                "subject": subject,
                "textContent": text,
            },
            {"api-key": os.environ["BREVO_API_KEY"], "Accept": "application/json"},
        )
    if provider == "resend":
        return _post(
            "https://api.resend.com/emails",
            {
                "from": os.getenv("ALERT_FROM", "EL-AI <onboarding@resend.dev>"),
                "to": [to],
                "subject": subject,
                "text": text,
            },
            {"Authorization": f"Bearer {os.environ['RESEND_API_KEY']}"},
        )
    print("email skipped: no email provider is configured")
    return False


def send_email(to: str, subject: str, text: str) -> None:
    """Send an email in the background (never slows or breaks the request)."""
    threading.Thread(target=_send_now, args=(to, subject, text), daemon=True).start()


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
