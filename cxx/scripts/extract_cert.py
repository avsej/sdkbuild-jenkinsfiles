#!/usr/bin/env python3
"""Extract a cert (and optionally a key) from a cbdinocluster JSON blob.

Reads cbdinocluster's `--json` output from stdin (shape: {"cert": "...",
"key": "..."}), writes the .cert value to --cert-out, and if --key-out is
provided also writes the .key value to that path. Asserts each requested
field is non-empty so an upstream schema change or empty response surfaces
immediately here instead of as a downstream TLS handshake error 20 minutes
later.

Run by the integration-test stages in cxx-scripted-build-pipeline.groovy.
Three call patterns are exercised:

    # Cluster CA (TLS-only, docker or Capella)
    cbdinocluster certificates get-ca --json <id> \\
        | extract_cert.py --cert-out ca.pem

    # Dino CA (cert auth)
    cbdinocluster certificates get-dino-ca --json \\
        | extract_cert.py --cert-out ca.pem

    # Client cert + key (cert auth)
    cbdinocluster certificates get-client-cert --json Administrator \\
        | extract_cert.py --cert-out client.pem --key-out client.key

Note: get-client-cert mints a fresh keypair per invocation, so cert and key
*must* be extracted from a single stdin read. This script enforces that by
construction — there's no way to fetch only one of them in two separate runs
and get a matching pair.
"""
import argparse
import json
import sys


def write_field(data: dict, field: str, out_path: str) -> None:
    value = data.get(field)
    if not value:
        sys.exit(f"extract_cert: empty or missing .{field} in cbdinocluster JSON")
    with open(out_path, "w") as f:
        f.write(value)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Extract cert(+key) from cbdinocluster JSON on stdin."
    )
    parser.add_argument(
        "--cert-out",
        required=True,
        help="Path to write the .cert value (PEM).",
    )
    parser.add_argument(
        "--key-out",
        help="Path to write the .key value (PEM). Omit for cert-only outputs.",
    )
    args = parser.parse_args()

    data = json.load(sys.stdin)
    write_field(data, "cert", args.cert_out)
    if args.key_out:
        write_field(data, "key", args.key_out)


if __name__ == "__main__":
    main()
