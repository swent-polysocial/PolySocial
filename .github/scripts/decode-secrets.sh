#!/usr/bin/env bash
# Writes google-services.json and local.properties from the base64-encoded GitHub Actions secrets
# GOOGLE_SERVICES and LOCAL_PROPERTIES. Used by every CI job that builds the app.
#
# This has been taken from the SwEnt bootcamp, credit goes to the team at CS-311
set -euo pipefail

if [ -n "${GOOGLE_SERVICES:-}" ]; then
  echo "$GOOGLE_SERVICES" | base64 --decode > ./app/google-services.json
else
  echo "::warning::GOOGLE_SERVICES secret is not set. google-services.json will not be created."
fi

if [ -n "${LOCAL_PROPERTIES:-}" ]; then
  echo "$LOCAL_PROPERTIES" | base64 --decode > ./local.properties
else
  echo "::warning::LOCAL_PROPERTIES secret is not set. local.properties will not be created."
fi
