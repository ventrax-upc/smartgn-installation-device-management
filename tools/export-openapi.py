from urllib.request import urlopen
from pathlib import Path
import json,os
with urlopen(os.environ.get('OPENAPI_URL','http://localhost:8081/v3/api-docs')) as response:
 data=json.load(response)
Path('docs/openapi.json').write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')
