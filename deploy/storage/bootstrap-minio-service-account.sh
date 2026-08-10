#!/bin/sh
set -eu

: "${MINIO_ROOT_USER:?MINIO_ROOT_USER is required}"
: "${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD is required}"
: "${MINIO_ACCESS_KEY:?MINIO_ACCESS_KEY is required}"
: "${MINIO_SECRET_KEY:?MINIO_SECRET_KEY is required}"
: "${MINIO_BUCKET:?MINIO_BUCKET is required}"

case "$MINIO_BUCKET" in
  *[!a-z0-9.-]*|'') echo "MINIO_BUCKET contains unsupported characters" >&2; exit 1 ;;
esac

access_key_length=${#MINIO_ACCESS_KEY}
if [ "$access_key_length" -lt 3 ] || [ "$access_key_length" -gt 20 ]; then
  echo "MINIO_ACCESS_KEY length must be between 3 and 20 characters" >&2
  exit 1
fi

printf '%s\n' \
  '{' \
  '  "Version": "2012-10-17",' \
  '  "Statement": [' \
  '    {' \
  '      "Effect": "Allow",' \
  '      "Action": ["s3:GetBucketLocation", "s3:ListBucket"],' \
  "      \"Resource\": [\"arn:aws:s3:::$MINIO_BUCKET\"]" \
  '    },' \
  '    {' \
  '      "Effect": "Allow",' \
  '      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],' \
  "      \"Resource\": [\"arn:aws:s3:::$MINIO_BUCKET/*\"]" \
  '    }' \
  '  ]' \
  '}' > /tmp/media-policy.json

mc alias set grassland "${MINIO_ENDPOINT:-http://minio:9000}" \
  "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
mc mb --ignore-existing "grassland/$MINIO_BUCKET"

if ! mc admin user svcacct info grassland "$MINIO_ACCESS_KEY" >/dev/null 2>&1; then
  mc admin user svcacct add grassland "$MINIO_ROOT_USER" \
    --access-key "$MINIO_ACCESS_KEY" \
    --secret-key "$MINIO_SECRET_KEY" \
    --policy /tmp/media-policy.json \
    --name grassland-media-runtime >/dev/null
  echo "Created MinIO runtime service account for bucket $MINIO_BUCKET"
else
  echo "MinIO runtime service account already exists for bucket $MINIO_BUCKET"
fi

mc admin user svcacct info grassland "$MINIO_ACCESS_KEY" >/dev/null
