"""Docker acceptance: real IAM, Installation and EMQX; labeled future-service doubles."""
import concurrent.futures
import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from datetime import datetime, timezone
from mqtt_client import Mqtt5

ENV = dict(line.split('=', 1) for line in Path('/config/integration.env').read_text(encoding='utf-8-sig').splitlines() if '=' in line and not line.startswith('#'))
URL = dict(iam='http://iam:8080', inst='http://installation:8081', fixture='http://future-services:8091', telemetry='http://telemetry:8090')
COUNT = 0


def call(service, path, token=None, body=None, method=None, expected=200):
    global COUNT
    method = method or ('POST' if body is not None else 'GET')
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(URL[service] + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            status, content = response.status, response.read()
    except urllib.error.HTTPError as response:
        status, content = response.code, response.read()
    COUNT += 1
    if status != expected:
        problem = json.loads(content) if content else {}
        raise AssertionError(f'{method} {service}{path}: HTTP {status}, expected {expected}; code={problem.get("code", problem.get("title", "unknown"))}')
    return json.loads(content) if content else None


def eventually(fn, accept, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = fn()
        if accept(result):
            return result
        time.sleep(.3)
    raise AssertionError('Expected confirmation did not arrive')


def uid():
    return str(uuid.uuid4())


def now():
    return datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')


def login(email, password, expected=200):
    return call('iam', '/api/v1/auth/sign-in', body=dict(email=email, password=password), expected=expected)


def delivered(email, purpose):
    rows = eventually(lambda: call('fixture', '/fixtures/messages?email=' + urllib.parse.quote(email), ENV['FIXTURE_ADMIN_TOKEN']), lambda rows: any(v['purpose'] == purpose for v in rows))
    return [v for v in rows if v['purpose'] == purpose][-1]['token']


def activate(email):
    password = 'Acceptance-' + uid()
    token = delivered(email, 'ACTIVATION')
    body = dict(activationToken=token, newPassword=password)
    call('iam', '/api/v1/auth/activate-password', body=body, expected=204)
    call('iam', '/api/v1/auth/activate-password', body=body, expected=400)
    return password, login(email, password)


def point(token, label):
    p = call('inst', '/api/v1/properties', token, dict(name=label, address='Local acceptance', propertyType='HOUSE'), expected=201)
    m = call('inst', f'/api/v1/properties/{p["id"]}/supply-points', token, dict(serialNumber=uid(), locationName=label), expected=201)
    return p, m


def main():
    for service_name in ('iam', 'inst'):
        deadline = time.monotonic() + 120
        while True:
            try:
                if call(service_name, '/actuator/health').get('status') == 'UP':
                    break
            except (OSError, AssertionError):
                pass
            if time.monotonic() >= deadline:
                raise AssertionError(service_name + ' did not become healthy')
            time.sleep(1)
    admin = login(ENV['BOOTSTRAP_SUPERADMIN_EMAIL'], ENV['BOOTSTRAP_SUPERADMIN_PASSWORD'])['token']
    service, fixture = ENV['INTERNAL_SERVICE_TOKEN'], ENV['FIXTURE_ADMIN_TOKEN']
    email = 'acceptance-' + uid() + '@example.test'
    request = call('inst', '/api/v1/installations', body=dict(contact=dict(name='Local Acceptance', email=email, phone='123456789'), address='Local acceptance'), expected=201)
    assert request['status'] == 'PROVISIONAL' and request['accountId'] is None
    login(email, 'not-active', 401)
    call('iam', '/api/v1/auth/sign-up', body=dict(email=email, password='Arbitrary123', role='SUPERADMIN'), expected=401)
    call('iam', '/api/v1/auth/activate-password', body=dict(activationToken='invalid', newPassword='Arbitrary123'), expected=400)
    purchase = dict(eventId=uid(), purchaseOrderId=uid(), email=email, firstName='Local', lastName='Acceptance', confirmed=False)
    call('iam', '/internal/v1/accounts/purchase-confirmations', service, purchase, expected=409)
    call('iam', '/internal/v1/accounts/purchase-confirmations', None, purchase, expected=401)
    purchase['confirmed'] = True
    call('fixture', '/fixtures/notification-failure', fixture, dict(enabled=True))
    try:
        account = call('iam', '/internal/v1/accounts/purchase-confirmations', service, purchase)
        time.sleep(1.5)
        assert call('fixture', '/fixtures/messages?email='+urllib.parse.quote(email), fixture) == []
    finally:
        call('fixture', '/fixtures/notification-failure', fixture, dict(enabled=False))
    assert account['status'] == 'PENDING_ACTIVATION' and account['role'] == 'USER'
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        replays = list(pool.map(lambda _: call('iam', '/internal/v1/accounts/purchase-confirmations', service, purchase), range(4)))
    assert {v['accountId'] for v in replays} == {account['accountId']}
    assert call('iam', '/internal/v1/accounts/purchase-confirmations', service, purchase)['accountId'] == account['accountId']
    login(email, 'not-active', 401)
    password, session = activate(email)
    print('Checkpoint: principal activation', flush=True)
    owner = session['token']
    assert 'plan' not in session and session['accountKind'] == 'PRINCIPAL'
    call('iam', '/api/v1/profiles/me', owner, dict(firstName='Updated', lastName='Acceptance', phone='123456789'), 'PUT')
    pa, a = point(owner, 'A')
    pb, b = point(owner, 'B')
    call('inst', '/api/v1/properties/' + pa['id'], owner, dict(name='Stale', address='Local', propertyType='HOUSE', version=99), 'PUT', 409)
    call('inst', '/api/v1/properties', expected=401)
    call('inst', '/api/v1/properties', owner + 'tampered', expected=401)
    for path in ('/api/v1/reports', '/api/v1/maintenance', '/api/v1/technicians'):
        call('inst', path, admin, expected=404)
    call('iam', '/api/v1/subscriptions/me', owner, expected=404)
    sub_email = 'delegate-' + uid() + '@example.test'
    sub = call('iam', '/api/v1/subusers', owner, dict(email=sub_email, firstName='Delegated', lastName='Acceptance'), expected=201)
    _, delegated_session = activate(sub_email)
    delegated = delegated_session['token']
    assert call('inst', '/api/v1/meters', delegated) == []
    call('iam', '/api/v1/subusers', delegated, dict(email=uid()+'@example.test', firstName='No', lastName='Access'), expected=403)
    call('inst', '/api/v1/properties', delegated, dict(name='Denied', address='Denied', propertyType='HOUSE'), expected=403)
    grants = f'/api/v1/subusers/{sub["accountId"]}/grants'
    call('iam', grants, owner, dict(meterId=a['id'], permissions=['VIEW_CONSUMPTION', 'USE_TICKETS']), 'PUT', 204)
    call('iam', grants, owner, dict(meterId=b['id'], permissions=['VIEW_ALERTS', 'USE_REPORTS']), 'PUT', 204)
    call('iam', grants, owner, dict(meterId=b['id'], permissions=['PAYMENTS']), 'PUT', 400)
    assert len(call('inst', '/api/v1/meters', delegated)) == 2
    foreign_email = 'foreign-' + uid() + '@example.test'
    foreign_purchase = dict(purchase, eventId=uid(), purchaseOrderId=uid(), email=foreign_email)
    call('iam', '/internal/v1/accounts/purchase-confirmations', service, foreign_purchase)
    _, foreign_session = activate(foreign_email)
    foreign_owner = foreign_session['token']
    _, foreign_meter = point(foreign_owner, 'Foreign')
    call('iam', grants, owner, dict(meterId=foreign_meter['id'], permissions=['USE_TICKETS']), 'PUT', 403)
    call('inst', '/api/v1/properties/' + pa['id'], foreign_owner, expected=403)
    call('inst', '/api/v1/devices?pointId='+a['id']+'&function=VIEW_CONSUMPTION', delegated)
    call('inst', '/api/v1/devices?pointId='+b['id']+'&function=VIEW_CONSUMPTION', delegated, expected=403)
    call('inst', '/api/v1/devices?pointId='+a['id'], delegated, expected=403)
    check = dict(accountId=sub['accountId'], meterId=a['id'], function='USE_TICKETS', requiresPro=False)
    assert call('iam', '/internal/v1/authorizations/check', service, check)['allowed']
    assert not call('iam', '/internal/v1/authorizations/check', service, dict(check, function='USE_VISITS'))['allowed']
    call('iam', grants, owner, dict(meterId=a['id'], permissions=['USE_TICKETS']), 'PUT', 204)
    call('inst', '/api/v1/devices?pointId='+a['id']+'&function=VIEW_CONSUMPTION', delegated, expected=403)
    assert call('iam', grants+'/'+a['id'], owner) == ['USE_TICKETS']
    assert set(call('iam', grants+'/'+b['id'], owner)) == {'VIEW_ALERTS', 'USE_REPORTS'}
    pro = dict(check, meterId=b['id'], function='USE_REPORTS', requiresPro=True)
    assert not call('iam', '/internal/v1/authorizations/check', service, pro)['allowed']
    call('fixture', '/fixtures/entitlements', fixture, dict(accountId=account['accountId'], proActive=True))
    assert call('iam', '/internal/v1/authorizations/check', service, pro)['allowed']
    call('iam', grants+'/'+a['id'], owner, method='DELETE', expected=204)
    assert len(call('inst', '/api/v1/meters', delegated)) == 1
    print('Checkpoint: current delegated permissions', flush=True)
    data = dict(firstName='External', lastName='Installer', identification=uid(), phone='123456789', email='external@example.test', accreditationCode=uid(), available=True)
    installer = call('inst', '/api/v1/installers', admin, data, expected=201)
    call('inst', '/api/v1/installers', owner, expected=403)
    path = '/api/v1/installations/'+request['id']
    call('inst', path+'/assign', admin, dict(installerId=installer['id']), expected=409)
    payment = dict(eventId=uid(), purchaseOrderId=purchase['purchaseOrderId'], installationId=request['id'], customerAccountId=account['accountId'], confirmed=True)
    paid = call('inst', '/internal/v1/installations/payment-confirmations', service, payment)
    assert paid['status'] == 'PENDING'
    assert call('inst', '/internal/v1/installations/payment-confirmations', service, payment)['version'] == paid['version']
    call('inst', '/internal/v1/installations/payment-confirmations', service, dict(payment, purchaseOrderId=uid()), expected=409)
    call('inst', path+'/target', owner, dict(propertyId=pa['id'], supplyPointId=a['id'], version=paid['version']), 'PUT')
    installation = eventually(lambda: call('inst', path, owner)['installation'], lambda v: v['status']=='ASSIGNED')
    print('Checkpoint: paid automatic assignment', flush=True)
    data.update(identification=uid(), accreditationCode=uid())
    second = call('inst', '/api/v1/installers', admin, data, expected=201)
    call('inst', path+'/reassign', admin, dict(installerId=second['id'], version=installation['version'], reason='Local acceptance'), 'PATCH')
    call('inst', path+'/reassign', admin, dict(installerId=installer['id'], version=0, reason='Stale'), 'PATCH', 409)
    call('inst', path+'/status', admin, dict(status='IN_PROGRESS'), 'PATCH')
    device = uid()
    registration = dict(deviceId=device, installationId=request['id'], supplyPointId=a['id'], installerId=second['id'], serialNumber='acceptance-'+uid(), location='Kitchen', installedAt=now())
    call('inst', '/api/v1/devices', admin, dict(registration, supplyPointId=b['id']), expected=409)
    receipt = call('inst', '/api/v1/devices', admin, registration, expected=202)
    current = lambda: next(row['device'] for row in call('inst', '/api/v1/devices?limit=100', admin) if row['device']['id'] == device)
    eventually(current, lambda v: v['status']=='ACTIVE')
    assert call('inst',f'/internal/v1/devices/{device}/authorization?associationVersion=1',service)['authorized']
    assert not call('inst',f'/internal/v1/devices/{device}/authorization?associationVersion=999',service)['authorized']
    print('Checkpoint: broker and telemetry provisioning', flush=True)
    client = Mqtt5('broker', 1883, receipt['mqttUsername'], receipt['oneTimeCredential'])
    assert client.reason == 0 and client.publish(f'smartgn/devices/{device}/telemetry', dict(fixture='LOCAL_BROKER_ACCEPTANCE')) < 128
    assert client.publish(f'smartgn/devices/{uid()}/telemetry', dict(fixture='FORBIDDEN_TOPIC_ACCEPTANCE')) >= 128
    client.close()
    call('inst', path+'/complete', admin, {}, expected=409)
    call('telemetry', '/fixtures/device-status', fixture, dict(accountId=account['accountId'], deviceId=device, connectionState='CONNECTED', lastContact=now()))
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
        closed = list(pool.map(lambda _: call('inst', path+'/complete', admin, {}), range(10)))
    assert all(v['status']=='COMPLETED' for v in closed) and len({v['completedAt'] for v in closed}) == 1
    assert call('inst', path+'/complete', admin, {})['completedAt'] == closed[0]['completedAt']
    print('Checkpoint: concurrent installation closure', flush=True)
    rotated = call('inst', f'/api/v1/devices/{device}/credentials/rotate', admin, {}, expected=202)
    eventually(current, lambda v: v['status']=='ACTIVE')
    print('Checkpoint: credential rotation', flush=True)
    rejected = Mqtt5('broker', 1883, receipt['mqttUsername'], receipt['oneTimeCredential'])
    assert rejected.reason != 0
    rejected.close()
    active = Mqtt5('broker', 1883, rotated['mqttUsername'], rotated['oneTimeCredential'])
    assert active.reason == 0
    start = time.monotonic()
    call('inst', f'/api/v1/devices/{device}/credentials/revoke', admin, {}, expected=202)
    eventually(current, lambda v: v['status']=='REVOKED' and v['brokerConfirmed'], 30)
    elapsed = time.monotonic()-start
    assert not call('inst',f'/internal/v1/devices/{device}/authorization?associationVersion=1',service)['authorized']
    print('Checkpoint: broker-confirmed revocation', flush=True)
    try:
        active.send(0xc0, b'')
        header, _ = active.read()
        assert header != 0xd0, 'Revoked session still answers keepalive'
    except (OSError, EOFError):
        pass
    active.close()
    rejected = Mqtt5('broker', 1883, rotated['mqttUsername'], rotated['oneTimeCredential'])
    assert rejected.reason != 0
    rejected.close()
    call('iam', '/api/v1/auth/change-password', owner, dict(currentPassword='wrong', newPassword='Changed-'+uid()), expected=403)
    changed = 'Changed-'+uid()
    call('iam', '/api/v1/auth/change-password', owner, dict(currentPassword=password, newPassword=changed), expected=204)
    call('inst', '/api/v1/properties', owner, expected=401)
    owner = login(email, changed)['token']
    call('iam', '/api/v1/auth/forgot-password', body=dict(email=email), expected=202)
    call('iam', '/api/v1/auth/forgot-password', body=dict(email='absent-'+uid()+'@example.test'), expected=202)
    recovery = delivered(email, 'RECOVERY')
    call('iam', '/api/v1/auth/activate-password', body=dict(activationToken=recovery, newPassword=changed), expected=400)
    call('iam', '/api/v1/auth/reset-password', body=dict(token=recovery, newPassword=changed+'R'), expected=204)
    call('iam', '/api/v1/auth/reset-password', body=dict(token=recovery, newPassword=changed+'R'), expected=400)
    call('inst', '/api/v1/properties', owner, expected=401)
    assert login(email, changed+'R')['role']=='USER'
    print('Checkpoint: password recovery and session invalidation', flush=True)
    events = eventually(lambda: call('fixture', '/fixtures/events', fixture), lambda rows: any(v.get('event_type')=='InstallationCompleted' and v.get('aggregate_id')==request['id'] for v in rows))
    assert sum(v.get('event_type')=='InstallationCompleted' and v.get('aggregate_id')==request['id'] for v in events)==1
    assert not any('password' in json.dumps(v).lower() or 'onetimecredential' in json.dumps(v).lower() for v in events)
    print(json.dumps(dict(status='PASS', httpChecks=COUNT, revocationSeconds=round(elapsed, 3), iam='REAL', installation='REAL', broker='REAL_EMQX', futureServices='LOCAL_CONTRACT_DOUBLES', telemetryIngestionVerified=False)))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        import traceback
        print(json.dumps(dict(status='FAIL', type=type(error).__name__, line=traceback.extract_tb(error.__traceback__)[-1].lineno, reason=str(error) if isinstance(error, AssertionError) else 'Local acceptance execution failed')))
        raise SystemExit(1)
