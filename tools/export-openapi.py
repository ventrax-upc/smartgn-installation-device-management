"""Export and structurally verify the specifications served by the local Docker stack."""
import json
import time
import urllib.request
from pathlib import Path

for service, port, target in [('iam', 8080, '/iam/docs/openapi.json'), ('installation', 8081, '/installation/docs/api/openapi.json')]:
    deadline = time.monotonic() + 120
    while True:
        try:
            with urllib.request.urlopen(f'http://{service}:{port}/v3/api-docs', timeout=30) as response:
                document = json.load(response)
            break
        except OSError:
            if time.monotonic() >= deadline:
                raise
            time.sleep(1)
    paths = document['paths']
    assert 'serviceAuth' in document['components']['securitySchemes']
    if service == 'iam':
        assert document['components']['schemas']['AuthenticatedAccountResource']['properties']['role']['enum'] == ['USER', 'SUPERADMIN']
        for path, method in [('/api/v1/auth/activate-password', 'post'), ('/api/v1/auth/change-password', 'post'), ('/api/v1/profiles/me', 'put'), ('/api/v1/subusers', 'post'), ('/api/v1/subusers/{subuserId}/grants', 'put')]:
            assert method in paths[path], path
        assert '/api/v1/auth/sign-up' not in paths
        assert '/api/v1/subscriptions/me' not in paths
        assert paths['/api/v1/auth/activate-password']['post'].get('security') == []
        assert paths['/internal/v1/sessions/introspect']['post']['security'] == [{'serviceAuth': []}]
    else:
        assert 'post' in paths['/api/v1/installations/{id}/assign']
        assert 'patch' not in paths['/api/v1/installations/{id}/assign']
        assert 'post' in paths['/api/v1/installations/{id}/complete']
        assert '/api/v1/technicians' not in paths
        assert not any('/reports' in path or '/tariffs' in path or '/maintenance' in path for path in paths)
        assert paths['/api/v1/installations']['post'].get('security') == []
    for path, operations in paths.items():
        for method, operation in operations.items():
            if method not in ('get', 'post', 'put', 'patch', 'delete'):
                continue
            assert not any(p['name'] in ('actor', 'correlation') for p in operation.get('parameters', [])), path
    document['servers'] = [{'url': f'http://localhost:{port}', 'description': 'Local Docker endpoint'}]
    output = Path(target)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(document, indent=2) + '\n')
    print(json.dumps({'service': service, 'paths': len(paths), 'status': 'EXPORTED_AND_CHECKED'}))
