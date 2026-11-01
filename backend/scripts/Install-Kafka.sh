#!/usr/bin/env bash
set -euo pipefail
# Run inside WSL as root; fixed official archive and SHA-512 verification.
version=4.1.2
archive="kafka_2.13-${version}.tgz"
mkdir -p /opt/banking-kafka-download
cd /opt/banking-kafka-download
base="https://downloads.apache.org/kafka/${version}"
curl -fL --retry 2 -o kafka.tgz "$base/$archive" || {
  base="https://archive.apache.org/dist/kafka/${version}"
  curl -fL --retry 2 -o kafka.tgz "$base/$archive"
}
curl -fL --retry 2 -o kafka.tgz.sha512 "$base/${archive}.sha512"
expected=$(sed 's/^[^:]*://' kafka.tgz.sha512 | tr -d '[:space:]' | tr 'A-F' 'a-f')
actual=$(sha512sum kafka.tgz | awk '{print $1}')
[[ ${#expected} == 128 && "$expected" == "$actual" ]] || { echo 'Kafka SHA-512 mismatch' >&2; exit 1; }
tar -xzf kafka.tgz -C /opt
"/opt/kafka_2.13-${version}/bin/kafka-topics.sh" --version
