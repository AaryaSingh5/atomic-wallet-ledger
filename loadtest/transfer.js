// k6 load test: many independent sender/receiver pairs transferring 1 paise each.
//   k6 run loadtest/transfer.js
//   k6 run -e BASE_URL=http://localhost:8080 loadtest/transfer.js
//
// Each VU uses its own pair, so this measures throughput WITHOUT hot-row contention.
// To see the worst case (everyone hammering one wallet) set PAIRS=1.
import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const PAIRS = parseInt(__ENV.PAIRS || '20');
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export const options = {
  vus: 20,
  duration: '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<300'],
  },
};

function signup(label) {
  const email = `${label}-${uuidv4()}@load.test`;
  http.post(`${BASE}/api/auth/register`,
    JSON.stringify({ name: label, email, password: 'password123' }), { headers: JSON_HEADERS });
  const res = http.post(`${BASE}/api/auth/login`,
    JSON.stringify({ email, password: 'password123' }), { headers: JSON_HEADERS });
  return { email, token: res.json('token') };
}

export function setup() {
  const pairs = [];
  for (let i = 0; i < PAIRS; i++) {
    const sender = signup('sender');
    const receiver = signup('receiver');
    http.post(`${BASE}/api/wallet/topup`, JSON.stringify({ amount: 50000000 }), {
      headers: { ...JSON_HEADERS, Authorization: `Bearer ${sender.token}`, 'Idempotency-Key': uuidv4() },
    });
    pairs.push({ sender, receiver });
  }
  return pairs;
}

export default function (pairs) {
  const pair = pairs[(__VU - 1) % pairs.length];
  const res = http.post(`${BASE}/api/wallet/transfer`,
    JSON.stringify({ receiverEmail: pair.receiver.email, amount: 1 }), {
      headers: { ...JSON_HEADERS, Authorization: `Bearer ${pair.sender.token}`, 'Idempotency-Key': uuidv4() },
    });
  check(res, { 'transfer created': (r) => r.status === 201 });
}

export function teardown(pairs) {
  const res = http.get(`${BASE}/api/reconcile`, {
    headers: { Authorization: `Bearer ${pairs[0].sender.token}` },
  });
  check(res, { 'ledger balanced after load': (r) => r.json('balanced') === true });
}
