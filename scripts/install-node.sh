#!/usr/bin/env bash
# Minimal repair-node installer for Linux/macOS.
# Verifies checksum/signature, copies to an absolute destination, creates the
# state directory, and prints the join command. Does not enroll, download
# Runner, or change firewall policy.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: install-node.sh --archive <path.tar.gz> --checksum <path.sha256> --dest <absolute-dir> [--signature <path.sig>] [--state-dir <absolute-dir>]

Options:
  --archive     Release archive (required)
  --checksum    SHA-256 file produced by the release job (required)
  --dest        Absolute install directory for binaries (required)
  --signature   Optional detached signature to verify with cosign/openssl
  --state-dir   Absolute state directory (default: /var/lib/repair-node)
EOF
}

ARCHIVE=""
CHECKSUM=""
DEST=""
SIGNATURE=""
STATE_DIR="/var/lib/repair-node"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --archive) ARCHIVE="${2:-}"; shift 2 ;;
    --checksum) CHECKSUM="${2:-}"; shift 2 ;;
    --dest) DEST="${2:-}"; shift 2 ;;
    --signature) SIGNATURE="${2:-}"; shift 2 ;;
    --state-dir) STATE_DIR="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ -z "${ARCHIVE}" || -z "${CHECKSUM}" || -z "${DEST}" ]]; then
  usage >&2
  exit 2
fi

if [[ "${DEST}" != /* || "${STATE_DIR}" != /* ]]; then
  echo "error: --dest and --state-dir must be absolute paths" >&2
  exit 2
fi

if [[ ! -f "${ARCHIVE}" || ! -f "${CHECKSUM}" ]]; then
  echo "error: archive or checksum file not found" >&2
  exit 1
fi

echo "Verifying SHA-256 checksum..."
expected="$(awk '{print $1}' "${CHECKSUM}" | head -n 1 | tr '[:upper:]' '[:lower:]')"
if command -v sha256sum >/dev/null 2>&1; then
  actual="$(sha256sum "${ARCHIVE}" | awk '{print $1}')"
else
  actual="$(shasum -a 256 "${ARCHIVE}" | awk '{print $1}')"
fi
if [[ "${actual}" != "${expected}" ]]; then
  echo "error: checksum mismatch: expected ${expected} got ${actual}" >&2
  exit 1
fi

if [[ -n "${SIGNATURE}" ]]; then
  if [[ ! -f "${SIGNATURE}" ]]; then
    echo "error: signature file not found: ${SIGNATURE}" >&2
    exit 1
  fi
  if command -v cosign >/dev/null 2>&1; then
    cosign verify-blob --signature "${SIGNATURE}" "${ARCHIVE}"
  elif command -v openssl >/dev/null 2>&1 && [[ -n "${REPAIR_NODE_SIGNING_CERT:-}" ]]; then
    openssl dgst -sha256 -verify "${REPAIR_NODE_SIGNING_CERT}" -signature "${SIGNATURE}" "${ARCHIVE}"
  else
    echo "error: signature provided but no verifier available (cosign or openssl+REPAIR_NODE_SIGNING_CERT)" >&2
    exit 1
  fi
fi

tmpdir="$(mktemp -d)"
cleanup() { rm -rf "${tmpdir}"; }
trap cleanup EXIT

tar -xzf "${ARCHIVE}" -C "${tmpdir}"
pkg_dir="$(find "${tmpdir}" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
if [[ -z "${pkg_dir}" ]]; then
  echo "error: archive did not contain a package directory" >&2
  exit 1
fi

mkdir -p "${DEST}"
install -m 0755 "${pkg_dir}/repair-node" "${DEST}/repair-node"
if [[ -f "${pkg_dir}/repair-executor" ]]; then
  install -m 0755 "${pkg_dir}/repair-executor" "${DEST}/repair-executor"
fi
if [[ -f "${pkg_dir}/config.sample.yaml" ]]; then
  install -m 0644 "${pkg_dir}/config.sample.yaml" "${DEST}/config.sample.yaml"
fi
if [[ -f "${pkg_dir}/repair-node.service" ]]; then
  install -m 0644 "${pkg_dir}/repair-node.service" "${DEST}/repair-node.service"
fi
if [[ -f "${pkg_dir}/com.company.repair-node.plist" ]]; then
  install -m 0644 "${pkg_dir}/com.company.repair-node.plist" "${DEST}/com.company.repair-node.plist"
fi
if [[ -f "${pkg_dir}/LICENSE" ]]; then
  install -m 0644 "${pkg_dir}/LICENSE" "${DEST}/LICENSE"
fi

mkdir -p "${STATE_DIR}"
chmod 0700 "${STATE_DIR}"

cat <<EOF
Installed repair-node to ${DEST}
State directory: ${STATE_DIR}

Next step (manual enrollment only):
  ${DEST}/repair-node join --server <control-plane-url> --code <one-time-invite> --state-dir ${STATE_DIR}

This installer does not enroll the node, download GitLab Runner, or change firewall policy.
EOF
