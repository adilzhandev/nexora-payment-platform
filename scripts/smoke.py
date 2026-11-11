#!/usr/bin/env python3
import base64
import json
import os
import secrets
import time
import urllib.error
import urllib.request
import uuid

API = os.environ.get('BASE_URL', 'http://localhost:8080')
AUDIT = os.environ.get('AUDIT_BASE_URL', 'http://localhost:8081')


def request(url, method='GET', body=None, token=None, key=None, basic=None, expected=200):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if key:
        headers['Idempotency-Key'] = key
    if basic:
        headers['Authorization'] = 'Basic ' + base64.b64encode(basic.encode()).decode()
    data = None if body is None else json.dumps(body).encode()
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data, headers, method=method), timeout=10) as response:
            if response.status != expected:
                raise AssertionError(f'{method} {url}: expected {expected}, received {response.status}')
            payload = response.read()
            return json.loads(payload) if payload else None
    except urllib.error.HTTPError as error:
        raise AssertionError(f'{method} {url}: HTTP {error.code}') from None


def wait_for_health(base_url):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            if request(base_url + '/actuator/health/liveness')['status'] == 'UP':
                return
        except (AssertionError, OSError):
            pass
        time.sleep(1)
    raise AssertionError('Application did not become healthy: ' + base_url)


def main():
    credentials = os.environ['AUDIT_USERNAME'] + ':' + os.environ['AUDIT_PASSWORD']
    wait_for_health(API)
    wait_for_health(AUDIT)
    suffix = str(uuid.uuid4())
    email = 'smoke-' + suffix + '@nexora.test'
    password = secrets.token_urlsafe(24)
    user = request(API + '/api/auth/register', 'POST', {
        'email': email, 'password': password, 'companyName': 'Smoke merchant', 'registrationNumber': suffix,
    }, expected=201)
    token = request(API + '/api/auth/login', 'POST', {'email': email, 'password': password})['accessToken']
    body = {'amount': '100.00', 'currency': 'KZT', 'scenario': 'DUPLICATE_NOTIFICATION'}
    payment = request(API + '/api/payments', 'POST', body, token, 'create-' + suffix, expected=201)
    replay = request(API + '/api/payments', 'POST', body, token, 'create-' + suffix, expected=201)
    assert payment == replay, 'Idempotent creation changed the response'
    payment_url = API + '/api/payments/' + payment['id']
    confirmed = request(payment_url + '/confirm', 'POST', token=token, key='confirm-' + suffix)
    assert confirmed['status'] == 'SUCCEEDED', 'Payment did not settle'
    request(payment_url + '/refunds', 'POST', {'amount': '25.00'}, token, 'partial-' + suffix, expected=201)
    request(payment_url + '/refunds', 'POST', {}, token, 'full-' + suffix, expected=201)
    assert request(payment_url, token=token)['refundedAmount'] == 100, 'Refund total is incorrect'
    assert request(API + '/api/ledger/balances', token=token)[0]['balance'] == 0, 'Ledger did not return to zero'
    audit_url = AUDIT + '/api/v1/audit/events?companyId=' + user['companyId']
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        events = request(audit_url, basic=credentials)
        types = [event['eventType'] for event in events]
        if len(events) == 5 and types.count('RefundCompleted') == 2:
            assert set(types) == {'PaymentCreated', 'PaymentProcessing', 'PaymentSucceeded', 'RefundCompleted'}
            assert len({event['eventId'] for event in events}) == 5, 'Duplicate audit events'
            print('Smoke passed: payment, replay, duplicate notification, partial/full refunds, balanced ledger, 5 audit events')
            return
        time.sleep(0.5)
    raise AssertionError('Expected financial events did not reach the audit service')


if __name__ == '__main__':
    main()
