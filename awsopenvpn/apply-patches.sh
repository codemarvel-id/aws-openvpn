#!/bin/sh
# Applies AWSOpenVPN's OpenVPN 2 patch to the openvpn submodule (run once after `git submodule update --init --recursive`).
# The patch raises the TLS channel / username-password / option-parameter / management line limits so the ~10 KB AWS SAML response fits.
set -e
cd "$(dirname "$0")/.."
SUB=main/src/main/cpp/openvpn
if git -C "$SUB" apply --reverse --check ../../../../../awsopenvpn/patches/openvpn-aws-saml-buffers.patch 2>/dev/null; then
  echo "openvpn patch already applied"
else
  git -C "$SUB" apply ../../../../../awsopenvpn/patches/openvpn-aws-saml-buffers.patch
  echo "openvpn patch applied"
fi
