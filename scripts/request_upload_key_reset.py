"""Drive the Play Console 'Request upload key reset' form for com.localyze.

Pre-conditions:
  - Chrome running on localhost:9222 (bash scripts/start_chrome_for_play_console.sh).
  - Signed into the Google account that owns the Localyze app.
  - Manually navigated to:
      Play Console -> Localyze -> Setup -> App integrity -> App signing
    (We pause and let you click 'Request upload key reset' on the page — the
    button is gated behind a confirmation dialog that varies by account state.)

The script ONLY fills the form. It NEVER submits — the final 'Send' / 'Submit'
click is yours, with the page in front of you, so you can verify the PEM
fingerprint that Google echoes back matches the one in upload-key-info.txt.
"""
from __future__ import annotations

import sys
from pathlib import Path
from playwright.sync_api import sync_playwright, Page, TimeoutError as PWTimeout

ROOT = Path(__file__).resolve().parents[1]
PEM = ROOT / "localyze-upload-cert.pem"
CDP = "http://localhost:9222"

REASON = (
    "Lost access to the original upload key. The original keystore was "
    "generated on a Windows development machine that is no longer "
    "accessible. A new 2048-bit RSA upload certificate (valid until 2053) "
    "is attached. Requesting upload key reset so we can ship the next "
    "version of com.localyze."
)


def attach():
    pw = sync_playwright().start()
    try:
        browser = pw.chromium.connect_over_cdp(CDP)
    except Exception as e:
        print(f"[fatal] Could not connect to Chrome at {CDP}: {e}")
        print("[fatal] Start Chrome first: bash scripts/start_chrome_for_play_console.sh")
        sys.exit(2)
    ctx = browser.contexts[0]
    page = None
    for p in ctx.pages:
        if "play.google.com/console" in (p.url or ""):
            page = p
            break
    if page is None:
        page = ctx.pages[0] if ctx.pages else ctx.new_page()
    page.bring_to_front()
    return pw, browser, page


def pause(msg: str) -> None:
    print()
    print("=" * 72)
    print("HUMAN STEP:", msg)
    print("=" * 72)
    input("Press ENTER here once that is done in the browser... ")


def try_fill(page: Page, selectors, value: str, label: str) -> bool:
    for sel in selectors:
        try:
            el = page.locator(sel).first
            el.wait_for(state="visible", timeout=4_000)
            el.fill(value)
            print(f"  [ok] filled {label} via {sel}")
            return True
        except PWTimeout:
            continue
        except Exception as e:
            print(f"  [warn] {label} via {sel}: {e}")
    print(f"  [skip] no selector matched for {label}")
    return False


def try_upload(page: Page, selectors, files, label: str) -> bool:
    paths = [str(f) for f in files]
    missing = [p for p in paths if not Path(p).exists()]
    if missing:
        print(f"  [fatal] {label}: files not on disk: {missing}")
        return False
    for sel in selectors:
        try:
            el = page.locator(sel).first
            el.wait_for(state="attached", timeout=4_000)
            el.set_input_files(paths)
            print(f"  [ok] uploaded {label}: {paths}")
            return True
        except PWTimeout:
            continue
        except Exception as e:
            print(f"  [warn] {label} via {sel}: {e}")
    print(f"  [skip] no input matched for {label}")
    return False


def main() -> None:
    if not PEM.exists():
        print(f"[fatal] PEM cert missing at {PEM}")
        sys.exit(3)

    pw, browser, page = attach()
    try:
        print("\n[phase] UPLOAD KEY RESET")
        print(f"Current URL: {page.url}")
        pause(
            "Open Play Console -> Localyze -> Setup -> App integrity -> "
            "App signing. Scroll to the 'Upload key certificate' section. "
            "Click 'Request upload key reset' (or 'Use a different upload key' / "
            "'Request a key reset' — wording varies). The reset form should "
            "now be visible with a reason textarea and a file upload field."
        )

        print("\n[step] Reason")
        try_fill(
            page,
            [
                'textarea[aria-label*="reason" i]',
                'textarea[aria-label*="why" i]',
                'textarea[name*="reason" i]',
                'textarea[placeholder*="reason" i]',
                'textarea',
            ],
            REASON,
            "reason",
        )

        print("\n[step] Upload PEM certificate")
        try_upload(
            page,
            [
                'input[type="file"][accept*=".pem" i]',
                'input[type="file"][accept*="x-pem" i]',
                'input[type="file"][accept*="application/x-x509" i]',
                'input[type="file"]',
            ],
            [PEM],
            "PEM certificate",
        )

        print("\n[done] Form filled. The script will NOT click Submit.")
        print()
        print("Now in the browser, do these checks BEFORE clicking submit:")
        print("  - Verify the reason text reads correctly.")
        print("  - Verify Play Console shows the SHA1 / SHA256 from upload-key-info.txt:")
        print("      SHA1:   68:96:20:08:2A:1C:81:E8:F2:DA:BA:A4:DD:BD:89:55:6A:89:B0:EC")
        print("      SHA256: C8:6E:24:41:4B:71:70:B3:DC:C2:9D:7E:08:49:7A:4A:94:D7:B1:A8:CA:33:90:E5:9A:D9:94:41:BF:DA:BE:12")
        print("  - Verify the email Google will reply to is the one you have access to.")
        print("  - When all three look right, click Submit / Send in the browser.")
        print()
        print("Approval usually arrives by email in 1-2 business days.")
    finally:
        browser.close()
        pw.stop()


if __name__ == "__main__":
    main()
