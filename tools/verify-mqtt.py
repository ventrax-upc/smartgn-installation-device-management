"""Replay committed Support completions over authenticated MQTT; preserve all application data."""
from pathlib import Path
import os,subprocess,time
root=Path(__file__).resolve().parents[1]
compose=['docker','compose','--env-file','../smartgn-payments-billing/.env','-f','compose.integration.yml']
def run(args,env=None):
 result=subprocess.run(compose+args,cwd=root,env=env,capture_output=True,text=True,check=True)
 return result.stdout.strip()
def billing(query):
 return int(run(['exec','-T','db','psql','-U','smartgn_billing','-d','billing_db','-At','-c',query]))
conflicts="select count(*) from billing.mqtt_inbox_errors where event_type='TechnicalVisitCompleted' and reason='EVENT_CONFLICT'"
before=billing('select count(*) from billing.coverage_decisions');errors=billing(conflicts)
enabled=dict(os.environ,SUPPORT_MQTT_URL='tcp://broker:1883')
try:
 run(['up','-d','--no-deps','--wait','support'],enabled)
 for _ in range(30):
  pending=int(run(['exec','-T','support-db','psql','-U','smartgn_support','-d','smartgn_support_db','-At','-c','select count(*) from support_outbox where mqtt_delivered_at is null']))
  if not pending:break
  time.sleep(1)
 else:raise AssertionError('MQTT completion outbox did not drain')
 time.sleep(3)
 assert before==billing('select count(*) from billing.coverage_decisions'),'Duplicate financial coverage'
 assert errors==billing(conflicts),'HTTP/MQTT replay conflict'
 print('PASS Authenticated MQTT replay: outbox delivered, no duplicate coverage or monetary conflicts')
finally:
 restored=dict(os.environ);restored.pop('SUPPORT_MQTT_URL',None)
 run(['up','-d','--no-deps','--wait','support'],restored)
