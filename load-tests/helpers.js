import http from 'k6/http';
import { check, fail } from 'k6';

export const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';

export function headers(token, key) {
    const result = { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
    if (key) result['Idempotency-Key'] = key;
    return { headers: result };
}

export function merchant() {
    const suffix = `${Date.now()}-${__VU}-${Math.random().toString(36).slice(2)}`;
    const email = `load-${suffix}@nexora.test`;
    const password = `Load-${suffix}`;
    const registered = http.post(`${baseUrl}/api/auth/register`, JSON.stringify({
        email, password, companyName: `Load merchant ${suffix}`, registrationNumber: suffix,
    }), { headers: { 'Content-Type': 'application/json' } });
    if (!check(registered, { 'merchant registered': (r) => r.status === 201 })) fail('Registration failed');
    const loggedIn = http.post(`${baseUrl}/api/auth/login`, JSON.stringify({ email, password }), {
        headers: { 'Content-Type': 'application/json' },
    });
    if (!check(loggedIn, { 'merchant authenticated': (r) => r.status === 200 })) fail('Login failed');
    return loggedIn.json('accessToken');
}
