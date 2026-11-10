#!/usr/bin/env bash

# Sourced by Product G3/G4/SD1 runners. The cache contract remains owned by
# product_sim_cache.sh; this adapter only binds a caller's explicit simulator
# profile and provides a verified identity without hashing all RTL sources a
# second time. It does not trust a directory, filename or model ticket alone.
_product_model_cache_script="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/product_sim_cache.sh"

product_model_cache() {
  if (( $# != 7 )); then
    echo 'product_model_cache expects ACTION VERSION SETTLE BUILD_DIR TOP ELABORATE FLAGS' >&2
    return 2
  fi
  local action="$1" version="$2" settle="$3"
  shift 3
  case "$action" in fingerprint|record|verify) ;; *)
    echo "invalid Product model cache operation: $action" >&2
    return 2
    ;;
  esac
  # Never inherit the environment of an unrelated earlier model profile.
  AETHERCORE_VERILATOR_VERSION="$version" SIM_SETTLE_MODE="$settle" \
    bash "$_product_model_cache_script" "$action" "$@"
}

product_model_fingerprint() { product_model_cache fingerprint "$@"; }
product_model_verify() { product_model_cache verify "$@"; }
product_model_record() { product_model_cache record "$@"; }

# `verify` already computes the source/toolchain identity and compares both
# it and the executable SHA against the persisted manifest. Repeating an
# expensive full tree hash immediately afterward was redundant; extract the
# very identity that passed verification from its one exact success record.
product_model_verified_fingerprint() {
  local verified identity
  verified="$(product_model_verify "$@")" || return
  case "$verified" in
    'PRODUCT_MODEL_CACHE_VERIFIED identity='*)
      identity="${verified#PRODUCT_MODEL_CACHE_VERIFIED identity=}"
      ;;
    *)
      echo 'Product cache verification returned no exact identity' >&2
      return 7
      ;;
  esac
  if [[ ! "$identity" =~ ^[[:xdigit:]]{64}$ ]]; then
    echo 'Product cache verification returned a malformed identity' >&2
    return 7
  fi
  printf '%s\n' "$identity"
}
