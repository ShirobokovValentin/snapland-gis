-- Seed synthetic areas for performance testing.
--
-- Usage:
--   psql -h localhost -p 5433 -U snapland -d snapland_gis \
--        -v n=10000 -f seed-areas.sql
--
-- Variables (all optional — defaults shown):
--   n        number of areas to insert (default: 1000, max recommended: 50000)
--
-- The script is idempotent: re-running it first removes any previously seeded
-- data for the performance workspace, then reseeds.
--
-- Fixed identifiers used across all runs:
--   Performance workspace:  e0000000-0000-0000-0000-000000000001
--   Performance user:       e0000000-0000-0000-0000-000000000002
--
-- Geographic coverage:
--   Longitude: 10.0 – 15.0 E  (5-degree wide band)
--   Latitude:  47.0 – 52.0 N  (5-degree tall band)
--   Grid:      250 columns × 200 rows = 50 000 cells
--   Cell size: 0.02° × 0.025°  (≈ 1.5 km × 2.8 km near 49 °N)
--   Polygon:   0.016° × 0.020° square centred in each cell
--
-- Cleanup (run before reseeding or to reset):
--   DELETE FROM area_version WHERE area_id IN
--       (SELECT id FROM area WHERE workspace_id = 'e0000000-0000-0000-0000-000000000001');
--   DELETE FROM area         WHERE workspace_id = 'e0000000-0000-0000-0000-000000000001';
--   DELETE FROM workspace_member WHERE workspace_id = 'e0000000-0000-0000-0000-000000000001';
--   DELETE FROM workspace    WHERE id = 'e0000000-0000-0000-0000-000000000001';
--   DELETE FROM users        WHERE id  = 'e0000000-0000-0000-0000-000000000002';

-- Apply default if caller did not pass -v n=…
\if :{?n}
\else
    \set n 1000
\endif

\echo 'Seeding' :n 'areas into performance workspace e0000000-0000-0000-0000-000000000001'

BEGIN;

-- ── 1. Ensure performance user exists ────────────────────────────────────────
INSERT INTO users (id, name, email, created_at)
VALUES (
    'e0000000-0000-0000-0000-000000000002',
    'perf-user',
    'perf@snapland.internal',
    NOW()
)
ON CONFLICT (id) DO NOTHING;

-- ── 2. Ensure performance workspace exists ────────────────────────────────────
INSERT INTO workspace (id, name, created_by, created_at)
VALUES (
    'e0000000-0000-0000-0000-000000000001',
    'Performance Workspace',
    'e0000000-0000-0000-0000-000000000002',
    NOW()
)
ON CONFLICT (id) DO NOTHING;

-- ── 3. Ensure the perf user is a member ──────────────────────────────────────
INSERT INTO workspace_member (id, workspace_id, user_id, role)
VALUES (
    'e0000000-0000-0000-0000-000000000003',
    'e0000000-0000-0000-0000-000000000001',
    'e0000000-0000-0000-0000-000000000002',
    'OWNER'
)
ON CONFLICT (workspace_id, user_id) DO NOTHING;

-- ── 4. Remove any previously seeded areas ────────────────────────────────────
DELETE FROM area_version
WHERE area_id IN (
    SELECT id FROM area
    WHERE workspace_id = 'e0000000-0000-0000-0000-000000000001'
);
DELETE FROM area
WHERE workspace_id = 'e0000000-0000-0000-0000-000000000001';

-- ── 5. Insert :n synthetic areas ─────────────────────────────────────────────
-- Grid layout: 250 columns (longitude) × 200 rows (latitude)
-- Each cell is 0.02° wide × 0.025° tall; polygon occupies the inner 80%.
WITH inserted AS (
    INSERT INTO area (
        id,
        workspace_id,
        name,
        geometry,
        area_km2,
        status,
        current_version,
        created_by,
        created_at
    )
    SELECT
        gen_random_uuid(),
        'e0000000-0000-0000-0000-000000000001',
        'perf-area-' || i,
        ST_GeomFromText(
            'POLYGON(('
                || (10.0 + ((i - 1) % 250) * 0.02)        || ' ' || (47.0 + ((i - 1) / 250) * 0.025)        || ','
                || (10.0 + ((i - 1) % 250) * 0.02 + 0.016) || ' ' || (47.0 + ((i - 1) / 250) * 0.025)        || ','
                || (10.0 + ((i - 1) % 250) * 0.02 + 0.016) || ' ' || (47.0 + ((i - 1) / 250) * 0.025 + 0.020) || ','
                || (10.0 + ((i - 1) % 250) * 0.02)        || ' ' || (47.0 + ((i - 1) / 250) * 0.025 + 0.020) || ','
                || (10.0 + ((i - 1) % 250) * 0.02)        || ' ' || (47.0 + ((i - 1) / 250) * 0.025)
            || '))',
            4326
        ),
        CAST(
            ST_Area(
                ST_GeomFromText(
                    'POLYGON(('
                        || (10.0 + ((i - 1) % 250) * 0.02)        || ' ' || (47.0 + ((i - 1) / 250) * 0.025)        || ','
                        || (10.0 + ((i - 1) % 250) * 0.02 + 0.016) || ' ' || (47.0 + ((i - 1) / 250) * 0.025)        || ','
                        || (10.0 + ((i - 1) % 250) * 0.02 + 0.016) || ' ' || (47.0 + ((i - 1) / 250) * 0.025 + 0.020) || ','
                        || (10.0 + ((i - 1) % 250) * 0.02)        || ' ' || (47.0 + ((i - 1) / 250) * 0.025 + 0.020) || ','
                        || (10.0 + ((i - 1) % 250) * 0.02)        || ' ' || (47.0 + ((i - 1) / 250) * 0.025)
                    || '))',
                    4326
                )::geography
            ) / 1000000.0
        AS NUMERIC(20, 6)),
        'ACTIVE',
        1,
        'e0000000-0000-0000-0000-000000000002',
        NOW()
    FROM generate_series(1, :n) AS s(i)
    -- Return all columns needed for area_version; PostgreSQL CTE RETURNING is
    -- the only way to propagate newly inserted rows to the outer INSERT because
    -- CTE-inserted rows are not visible to a SELECT on the same table within
    -- the same SQL statement.
    RETURNING id, name, geometry, area_km2, created_by, created_at
)
-- ── 6. Insert one CREATED version for each area ──────────────────────────────
INSERT INTO area_version (
    id,
    area_id,
    version,
    name,
    geometry,
    area_km2,
    status,
    change_type,
    created_by,
    created_at
)
SELECT
    gen_random_uuid(),
    ins.id,
    1,
    ins.name,
    ins.geometry,
    ins.area_km2,
    'ACTIVE',
    'CREATED',
    ins.created_by,
    ins.created_at
FROM inserted ins;

COMMIT;

\echo 'Done. Verify with:'
\echo '  SELECT COUNT(*) FROM area        WHERE workspace_id = ''e0000000-0000-0000-0000-000000000001'';'
\echo '  SELECT COUNT(*) FROM area_version WHERE area_id IN (SELECT id FROM area WHERE workspace_id = ''e0000000-0000-0000-0000-000000000001'');'
