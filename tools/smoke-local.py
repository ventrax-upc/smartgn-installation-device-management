from pathlib import Path
import runpy,sys
p=Path(__file__).resolve().parents[2]/'smartgn-support-maintenance'/'tools'/'smoke-local.py'
sys.argv=[str(p),str(p.parents[2]/'smartgn-payments-billing'/'.env')]
runpy.run_path(str(p),run_name='__main__')
