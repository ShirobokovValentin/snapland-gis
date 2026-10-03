/**
 * k6 load test — viewport query (GET /workspaces/{wid}/areas?bbox=...)
 *
 * Prerequisites:
 *   - k6 installed (brew install k6)
 *   - Backend running and seeded with seed-areas.sql
 *   - A valid JWT token for the performance workspace
 *
 * Required environment variables:
 *   TOKEN        — Bearer JWT obtained via POST /auth/login
 *   WORKSPACE_ID — UUID of the performance workspace
 *                  (e0000000-0000-0000-0000-000000000001 if seeded with defaults)
 *
 * Optional environment variables:
 *   BASE_URL     — Backend base URL (default: http://localhost:8081)
 *
 * Run (smoke — short, low VU count to verify setup):
 *   k6 run --vus 2 --duration 10s \
 *       -e BASE_URL=http://localhost:8081 \
 *       -e WORKSPACE_ID=e0000000-0000-0000-0000-000000000001 \
 *       -e TOKEN=<your-jwt> \
 *       viewport-load.js
 *
 * Run (full load profile):
 *   k6 run \
 *       -e BASE_URL=http://localhost:8081 \
 *       -e WORKSPACE_ID=e0000000-0000-0000-0000-000000000001 \
 *       -e TOKEN=<your-jwt> \
 *       viewport-load.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ── Configuration ─────────────────────────────────────────────────────────────

const BASE_URL    = __ENV.BASE_URL    || 'http://localhost:8081';
const WORKSPACE   = __ENV.WORKSPACE_ID;
const TOKEN       = __ENV.TOKEN;

if (!WORKSPACE || !TOKEN) {
    throw new Error('WORKSPACE_ID and TOKEN environment variables are required.');
}

// ── Load profile ──────────────────────────────────────────────────────────────
//
// Stage design:
//   ramp-up   30 s → 10 VU   — establish baseline
//   hold      60 s at 25 VU  — moderate load
//   ramp      60 s → 50 VU   — approaching realistic concurrent users
//   peak      60 s at 100 VU — stress the spatial index
//   ramp-down 30 s → 0 VU

export const options = {
    stages: [
        { duration: '30s', target: 10  },
        { duration: '60s', target: 25  },
        { duration: '60s', target: 50  },
        { duration: '60s', target: 100 },
        { duration: '30s', target: 0   },
    ],
    thresholds: {
        // Track error rate — not a pass/fail gate, just surfaced in output
        http_req_failed: ['rate<1.0'],
    },
};

// ── Viewport bounding boxes over the seeded region ───────────────────────────
//
// Seeded region: lon 10–15 E, lat 47–52 N
// Viewports simulate different zoom levels and pan positions.

const VIEWPORTS = [
    '10.0,47.0,15.0,52.0',   // full seeded region  (highest result count)
    '10.0,47.0,12.5,49.5',   // SW quadrant
    '12.5,47.0,15.0,49.5',   // SE quadrant
    '10.0,49.5,12.5,52.0',   // NW quadrant
    '12.5,49.5,15.0,52.0',   // NE quadrant
    '11.0,48.0,14.0,51.0',   // central sub-region  (medium result count)
    '10.0,47.0,11.5,48.5',   // small SW corner     (low result count)
    '13.5,50.5,15.0,52.0',   // small NE corner     (low result count)
];

// ── Custom metrics ────────────────────────────────────────────────────────────

const resultCount = new Trend('viewport_result_count');
const emptyViewports = new Counter('viewport_empty_responses');

// ── Virtual user script ───────────────────────────────────────────────────────

export default function () {
    const bbox = VIEWPORTS[Math.floor(Math.random() * VIEWPORTS.length)];
    const url  = `${BASE_URL}/workspaces/${WORKSPACE}/areas?bbox=${bbox}`;

    const res = http.get(url, {
        headers: {
            Authorization: `Bearer ${TOKEN}`,
            Accept: 'application/json',
        },
        tags: { name: 'viewport-query' },
    });

    const ok = check(res, {
        'status is 200': (r) => r.status === 200,
        'body is JSON array': (r) => {
            try {
                const body = JSON.parse(r.body);
                return Array.isArray(body);
            } catch {
                return false;
            }
        },
    });

    if (ok && res.status === 200) {
        try {
            const areas = JSON.parse(res.body);
            resultCount.add(areas.length);
            if (areas.length === 0) {
                emptyViewports.add(1);
            }
        } catch {
            // body parse failed — already captured by check above
        }
    }

    // ~1s think time between iterations to simulate realistic user pacing
    sleep(0.8 + Math.random() * 0.4);
}
