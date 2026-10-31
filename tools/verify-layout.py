from pathlib import Path
import json,re
root=Path(__file__).resolve().parents[1]
config=json.loads((root/'tools/service.json').read_text())
base=config['package']
for source in ['main','test']:
 for file in (root/f'src/{source}/java').rglob('*.java'):
  text=file.read_text(encoding='utf-8')
  match=re.search(r'^package ([\w.]+);',text,re.M)
  assert match and (match.group(1)==base or match.group(1).startswith(base+'.')),file
  assert file.parent.relative_to(root/f'src/{source}/java').as_posix()==match.group(1).replace('.','/'),file
required=['.mvn/wrapper/maven-wrapper.properties','docs/architecture.md','docs/contracts.md','docs/local-development.md','docs/openapi.json','docs/service-overview.md','scripts/prepare-local.ps1','scripts/start-local.ps1','scripts/test.ps1','scripts/smoke-local.ps1','tools/health.sh','compose.yml','compose.integration.yml','Dockerfile','.env.example','README.md','LICENSE','mvnw','mvnw.cmd']
for name in required:assert (root/name).is_file(),name
print(config['service']+': package paths and shared layout valid')
