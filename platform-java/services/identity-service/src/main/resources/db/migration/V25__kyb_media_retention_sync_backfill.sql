-- Current attachment references remain live and are renewed by the reconciler.
INSERT INTO kyb_media_retention_sync(
    media_reference_id, reference_id, organization_id, reference_type, desired_state)
SELECT media_reference_id, id, organization_id, 'attachment', 'live'
FROM merchant_attachment
ON CONFLICT (media_reference_id, reference_id) DO NOTHING;

-- Review snapshots have independent tokens. Legacy snapshots stored attachment IDs
-- as JSON strings; resolve those through merchant_attachment before writing media IDs.
-- Historical terminal reviews get a conservative policy deadline; future decisions
-- use configurable application policy.
WITH review_material AS (
    SELECT request.id AS request_id, request.organization_id, request.status, request.updated_at,
           CASE
               WHEN jsonb_typeof(material.value) = 'object'
                    AND material.value ? 'mediaReferenceId'
                    AND material.value->>'mediaReferenceId'
                        ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                   THEN CAST(material.value->>'mediaReferenceId' AS uuid)
               WHEN jsonb_typeof(material.value) = 'string'
                    AND material.value #>> '{}'
                        ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                   THEN legacy.media_reference_id
               ELSE NULL
           END AS media_reference_id
    FROM kyb_verification_request request
    CROSS JOIN LATERAL jsonb_array_elements(
        CASE WHEN jsonb_typeof(request.materials) = 'array' THEN request.materials ELSE '[]'::jsonb END
    ) AS material(value)
    LEFT JOIN merchant_attachment legacy
      ON jsonb_typeof(material.value) = 'string'
     AND legacy.organization_id = request.organization_id
     AND legacy.id = CASE
         WHEN material.value #>> '{}'
              ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
         THEN CAST(material.value #>> '{}' AS uuid)
         ELSE NULL
     END
)
INSERT INTO kyb_media_retention_sync(
    media_reference_id, reference_id, organization_id, reference_type,
    desired_state, retain_until)
SELECT material.media_reference_id, material.request_id, material.organization_id,
       'review_request',
       CASE WHEN material.status IN ('approved', 'rejected') THEN 'sealed' ELSE 'live' END,
       CASE
           WHEN material.status = 'approved' THEN GREATEST(material.updated_at, now()) + interval '2555 days'
           WHEN material.status = 'rejected' THEN GREATEST(material.updated_at, now()) + interval '365 days'
           ELSE NULL
       END
FROM review_material material
WHERE material.media_reference_id IS NOT NULL
ON CONFLICT (media_reference_id, reference_id) DO NOTHING;
