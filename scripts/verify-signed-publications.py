# Copyright 2025-2026 @aalsanie. SPDX-License-Identifier: Apache-2.0

from __future__ import annotations

import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile

from verification_support import ROOT, gradle, project_version, run


def main() -> None:
    for executable in ("gpg", "gpgconf"):
        if shutil.which(executable) is None:
            raise RuntimeError(f"{executable} must be available on PATH")
    report = ROOT / "build/reports/library-signing/result.json"
    report.unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix="shamash-signing-") as directory:
        work = Path(directory)
        keyring = work / "gnupg"
        keyring.mkdir(mode=0o700)
        env = os.environ.copy()
        env["GNUPGHOME"] = str(keyring)
        passphrase = secrets.token_urlsafe(32)
        gpg = ["gpg", "--batch", "--pinentry-mode", "loopback", "--passphrase-fd", "0"]
        try:
            subprocess.run(
                gpg + ["--quick-generate-key", "Shamash publication verification <verification@invalid.example>", "rsa3072", "sign", "1d"],
                input=passphrase + "\n", env=env, text=True, capture_output=True, check=True,
            )
            listing = subprocess.check_output(["gpg", "--batch", "--with-colons", "--list-secret-keys"], env=env, text=True)
            fingerprints = [line.split(":")[9] for line in listing.splitlines() if line.startswith("fpr:")]
            if len(fingerprints) != 1:
                raise ValueError("Expected exactly one temporary signing key")
            fingerprint = fingerprints[0]
            secret_key = subprocess.check_output(
                gpg + ["--armor", "--export-secret-keys", fingerprint], input=passphrase + "\n", env=env, text=True,
            )
            if "BEGIN PGP PRIVATE KEY BLOCK" not in secret_key:
                raise ValueError("Temporary signing key export failed")
            signing_env = env.copy()
            signing_env.update({
                "ORG_GRADLE_PROJECT_signingInMemoryKey": secret_key,
                "ORG_GRADLE_PROJECT_signingInMemoryKeyId": fingerprint[-8:],
                "ORG_GRADLE_PROJECT_signingInMemoryKeyPassword": passphrase,
            })
            repository = work / "repository"
            gradle(
                "-PshamashRequireSigning=true", f"-PshamashTestRepository={repository}",
                "publishLibrariesToTestRepository", env=signing_env,
            )
            run([
                sys.executable, str(ROOT / "scripts/verify-publications.py"), str(repository), project_version(),
                "--signed", "--signer-fingerprint", fingerprint,
            ], env=env)
            report.parent.mkdir(parents=True, exist_ok=True)
            report.write_text(json.dumps({"status": "passed", "temporary_signer": fingerprint}, indent=2) + "\n", encoding="utf-8")
        finally:
            subprocess.run(["gpgconf", "--homedir", str(keyring), "--kill", "gpg-agent"], env=env, check=False, capture_output=True)
    print("All library artifacts were signed locally and verified with the temporary key.")


if __name__ == "__main__":
    main()
