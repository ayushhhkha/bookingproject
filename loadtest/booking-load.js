import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'admin@example.com';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'admin12345';
const USERS = 50;
const SLOTS = 400;

http.setResponseCallback(http.expectedStatuses(200, 201, 409));

const bookingsCreated = new Counter('bookings_created');
const bookingsRejected = new Counter('bookings_rejected_409');

export const options = {
  setupTimeout: '180s',
  scenarios: {
    browse: {
      executor: 'ramping-arrival-rate',
      startRate: 20, timeUnit: '1s', preAllocatedVUs: 50, maxVUs: 200,
      stages: [
        { target: 150, duration: '20s' },
        { target: 150, duration: '30s' },
        { target: 0, duration: '10s' },
      ],
      exec: 'browse',
    },
    contention: {
      executor: 'per-vu-iterations',
      vus: 50, iterations: 1, startTime: '10s', maxDuration: '30s',
      exec: 'contention',
    },
    booking: {
      executor: 'constant-arrival-rate',
      rate: 10, timeUnit: '1s', duration: '40s', preAllocatedVUs: 30, maxVUs: 100, startTime: '5s',
      exec: 'bookDistinctSlots',
    },
  },
  thresholds: {
    'http_req_duration{scenario:browse}': ['p(95)<500'],
    'http_req_duration{scenario:booking}': ['p(95)<800'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

function headers(token) {
  const h = { 'Content-Type': 'application/json' };
  if (token) h.Authorization = `Bearer ${token}`;
  return { headers: h };
}

function post(path, body, token) {
  return http.post(`${BASE}${path}`, JSON.stringify(body), headers(token));
}

export function setup() {
  const adminLogin = post('/api/auth/login', { email: ADMIN_EMAIL, password: ADMIN_PASSWORD });
  if (adminLogin.status !== 200) {
    throw new Error(`Admin login failed (${adminLogin.status}): ${adminLogin.body}. ` +
      'If this is 429, start the app with LOGIN_LIMIT=100000 (see the top of this file).');
  }
  const admin = adminLogin.json('token');

  const resource = post('/api/resources', { name: `Load test court ${Date.now()}` }, admin).json();
  const firstSlot = Date.now() + 2 * 24 * 3600 * 1000;   // start two days from now
  const slotAt = (i) => {
    const start = new Date(firstSlot + i * 3600 * 1000);
    const end = new Date(start.getTime() + 3600 * 1000);
    return post(`/api/resources/${resource.id}/slots`,
      { startTime: start.toISOString(), endTime: end.toISOString() }, admin).json('id');
  };

  const contentionSlot = slotAt(0);
  const slotIds = [];
  for (let i = 1; i <= SLOTS; i++) slotIds.push(slotAt(i));

  const run = Date.now();
  const tokens = [];
  for (let i = 0; i < USERS; i++) {
    const email = `load-${run}-${i}@example.com`;
    post('/api/auth/register', { email, password: 'password123', name: `Load user ${i}` });
    tokens.push(post('/api/auth/login', { email, password: 'password123' }).json('token'));
  }
  return { resourceId: resource.id, contentionSlot, slotIds, tokens };
}

export function browse(data) {
  const res = http.get(`${BASE}/api/resources/${data.resourceId}/slots/available`);
  check(res, { 'availability is 200': (r) => r.status === 200 });
}

export function contention(data) {
  const token = data.tokens[exec.vu.idInTest % data.tokens.length];
  const res = post('/api/bookings', { slotId: data.contentionSlot }, token);
  check(res, { 'contention: 201 or 409': (r) => r.status === 201 || r.status === 409 });
  if (res.status === 201) bookingsCreated.add(1);
  if (res.status === 409) bookingsRejected.add(1);
}

export function bookDistinctSlots(data) {
  const i = exec.scenario.iterationInTest;
  const token = data.tokens[i % data.tokens.length];
  const res = post('/api/bookings', { slotId: data.slotIds[i % data.slotIds.length] }, token);
  check(res, { 'booking: 201 or 409': (r) => r.status === 201 || r.status === 409 });
  if (res.status === 201) bookingsCreated.add(1);
  if (res.status === 409) bookingsRejected.add(1);
}

export function teardown() {
  console.log('Now verify that NO slot was double-booked (this query must return 0 rows):');
  console.log("docker exec -it booking-postgres psql -U booking -d booking -c \"SELECT slot_id, count(*) FROM bookings WHERE status = 'CONFIRMED' GROUP BY slot_id HAVING count(*) > 1;\"");
}