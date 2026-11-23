import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080/v1';
const PLAYERS = parseInt(__ENV.PLAYERS || '500');
const JSON_HDR = { 'Content-Type': 'application/json' };

http.setResponseCallback(http.expectedStatuses(200, 201));

export const options = {
    scenarios: {
        mixed: {
            executor: 'ramping-arrival-rate',
            startRate: 50, timeUnit: '1s',
            preAllocatedVUs: 100, maxVUs: 1000,
            stages: [
                { target: 200, duration: '20s' },
                { target: 500, duration: '40s' },
                { target: 500, duration: '30s' },
            ],
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<250', 'p(99)<500'],
    },
};

const player = n => `load-${n}`;
const post = (path, body, key) =>
    http.post(`${BASE}/${path}`, JSON.stringify(body),
        { headers: { ...JSON_HDR, 'Idempotency-Key': key }, tags: { name: path } });

// Fund every player up front so purchases don't hit "insufficient funds".
export function setup() {
    for (let i = 0; i < PLAYERS; i++) {
        post('grants', { playerId: player(i), amount: 10000000, gameId: 'fc26', region: 'NA' },
            `setup-${Date.now()}-${i}`);
    }
}

export default function () {
    const p = player(Math.floor(Math.random() * PLAYERS));
    const key = `${__VU}-${__ITER}-${Date.now()}-${Math.random()}`;
    const roll = Math.random();
    let res;
    if (roll < 0.80) {
        res = post('purchases', { playerId: p, amount: 1, itemId: 'sword', gameId: 'fc26', region: 'NA' }, key);
    } else if (roll < 0.95) {
        res = post('grants', { playerId: p, amount: 10, gameId: 'fc26', region: 'NA' }, key);
    } else {
        res = post('transfers', { fromPlayerId: p, toPlayerId: `load-recv-${__VU % 10}`, amount: 1,
            gameId: 'fc26', region: 'NA' }, key);
    }
    check(res, { 'status ok': r => r.status === 201 });
}