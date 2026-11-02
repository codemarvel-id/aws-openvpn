#!/bin/sh
# vpn-check.sh <resolver-ip> [...] — from inside Tiny/Termux: show the DNS config and test whether the VPC DNS resolvers answer through the tunnel.
# Example: vpn-check.sh 10.0.0.2   (the resolver of a VPC is its network address + 2)
[ $# -gt 0 ] || { echo "usage: vpn-check.sh <vpc-resolver-ip> [more ips]"; exit 1; }
echo "--- resolv.conf"; grep -v '^#' /etc/resolv.conf
echo "--- public DNS"; getent hosts google.com | head -1
for ip in "$@"; do
  python3 - "$ip" <<'PY'
import socket, sys
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(3)
q = b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00\x06amazon\x03com\x00\x00\x01\x00\x01"
try:
    s.sendto(q, (sys.argv[1], 53)); print(sys.argv[1], "DNS reply", len(s.recv(512)), "bytes")
except Exception as e:
    print(sys.argv[1], "no reply:", e)
PY
done
