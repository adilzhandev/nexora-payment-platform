import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl, headers, merchant } from './helpers.js';

export const options = {
    vus: Number(__ENV.VUS || 3),
    duration: __ENV.DURATION || '20s',
    thresholds: { http_req_failed: ['rate<0.01'], checks: ['rate==1'] },
};

let token;

export default function () {
    if (!token) token = merchant();
    const key = `duplicate-${__VU}-${__ITER}-${Date.now()}`;
    const payload = JSON.stringify({ amount: '10.00', currency: 'KZT', scenario: 'SUCCESS' });
    const requests = Array.from({ length: 5 }, () => ['POST', `${baseUrl}/api/payments`, payload, headers(token, key)]);
    const responses = http.batch(requests);
    check(responses, {
        'every retry succeeds': (results) => results.every((r) => r.status === 201),
        'all retries return one payment': (results) => new Set(results.map((r) => r.json('id'))).size === 1,
    });
    sleep(0.2);
}
