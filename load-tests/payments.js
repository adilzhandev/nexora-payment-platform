import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { baseUrl, headers, merchant } from './helpers.js';

export const options = {
    vus: Number(__ENV.VUS || 5),
    duration: __ENV.DURATION || '30s',
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<2000'],
        checks: ['rate==1'],
    },
};

let token;

export default function () {
    if (!token) token = merchant();
    const key = `payment-${__VU}-${__ITER}-${Date.now()}`;
    const created = http.post(`${baseUrl}/api/payments`, JSON.stringify({
        amount: '100.00', currency: 'KZT', description: 'Virtual load-test purchase', scenario: 'SUCCESS',
    }), headers(token, key));
    if (!check(created, { 'payment created': (r) => r.status === 201 })) fail('Create failed');
    const id = created.json('id');
    const confirmed = http.post(`${baseUrl}/api/payments/${id}/confirm`, null, headers(token, `${key}-confirm`));
    check(confirmed, { 'payment succeeded': (r) => r.status === 200 && r.json('status') === 'SUCCEEDED' });
    const refunded = http.post(`${baseUrl}/api/payments/${id}/refunds`, JSON.stringify({ amount: '25.00' }),
        headers(token, `${key}-refund`));
    check(refunded, { 'partial refund completed': (r) => r.status === 201 && Number(r.json('amount')) === 25 });
    const payment = http.get(`${baseUrl}/api/payments/${id}`, headers(token));
    check(payment, { 'refund total recorded': (r) => r.status === 200 && Number(r.json('refundedAmount')) === 25 });
    sleep(0.2);
}
