"""Isolated real MySQL/RabbitMQ audit; model is a local deterministic HTTP fixture.
Requires compose project travelmate-reliability from scripts/infra-test.yml.
Only touches that test project's broker and disposable test rows.
"""
import base64, concurrent.futures, json, os, pathlib, shutil, subprocess, threading, time
import urllib.request, urllib.error, urllib.parse
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
ROOT=pathlib.Path(__file__).resolve().parent.parent
JAVA=str(pathlib.Path(os.environ['JAVA_HOME'])/'bin/java')
DOCKER=os.environ.get('DOCKER_BIN',shutil.which('docker') or '/Applications/Docker.app/Contents/Resources/bin/docker')
processes=[];logs=[];checks=[]
class Provider(BaseHTTPRequestHandler):
    count=0;failing=False;block=False;release=threading.Event();lock=threading.Lock()
    def do_POST(self):
        try:
            if self.headers.get('Transfer-Encoding','').lower()=='chunked':
                while True:
                    size=int(self.rfile.readline().split(b';')[0].strip(),16)
                    if not size:self.rfile.readline();break
                    self.rfile.read(size);self.rfile.read(2)
            else:self.rfile.read(int(self.headers.get('Content-Length','0')))
            with Provider.lock:Provider.count+=1
            if Provider.block:Provider.release.wait(25)
            data=json.dumps({'choices':[{'message':{'content':'本机可靠性测试响应'}}]}).encode()
            self.send_response(503 if Provider.failing else 200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
        except (BrokenPipeError,ConnectionResetError):pass
    def log_message(self,*args):pass

def req(port,method,path,body=None,token=None,key=None):
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    if key:headers['Idempotency-Key']=key
    r=urllib.request.Request(f'http://127.0.0.1:{port}'+path,json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
    try:
        with urllib.request.urlopen(r,timeout=10) as s:return s.status,json.load(s)
    except urllib.error.HTTPError as e:return e.code,json.load(e)

def rabbit(method,path,body=None):
    h={'Authorization':'Basic '+base64.b64encode(b'travelmate:validation-only').decode(),'Content-Type':'application/json'}
    r=urllib.request.Request('http://127.0.0.1:25673/api'+path,json.dumps(body).encode() if body is not None else None,headers=h,method=method)
    with urllib.request.urlopen(r,timeout=5) as s:
        raw=s.read();return json.loads(raw) if raw else None

def wait(predicate,seconds=45):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        try:
            value=predicate()
            if value:return value
        except (OSError,KeyError):pass
        time.sleep(.25)
    raise RuntimeError('condition timed out')

def check(name,value):
    checks.append({'test':name,'pass':bool(value)});print(json.dumps(checks[-1]),flush=True)
    if not value:raise AssertionError(name)

def start(port):
    log=open(ROOT/f'reliability-{port}.log','w');logs.append(log)
    env={**os.environ,'JWT_SECRET':'isolated-reliability-secret-at-least-32-bytes',
        'DATASOURCE_URL':'jdbc:mysql://127.0.0.1:23306/travelmate?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',
        'DATASOURCE_USER':'travelmate','DATASOURCE_PASSWORD':'validation-only','REDIS_PORT':'26379',
        'RABBITMQ_PORT':'25672','RABBITMQ_USER':'travelmate','RABBITMQ_PASSWORD':'validation-only',
        'DASHSCOPE_API_KEY':'fixture-only','AI_BASE_URL':'http://127.0.0.1:18890','AMAP_WEB_KEY':''}
    p=subprocess.Popen([JAVA,'-jar',str(ROOT/'target/travelmate-backend-1.0.0.jar'),'--spring.profiles.active=prod,mq,redis',f'--server.port={port}','--server.address=127.0.0.1'],env=env,stdout=log,stderr=log)
    processes.append(p);wait(lambda:req(port,'GET','/api/health')[0]==200);return p

def stop(p):
    p.terminate()
    try:p.wait(timeout=10)
    except subprocess.TimeoutExpired:p.kill();p.wait()

def compose(action):
    subprocess.run([DOCKER,'compose','-p','travelmate-reliability','-f',str(ROOT/'scripts/infra-test.yml'),action,'rabbitmq'],check=True,stdout=subprocess.DEVNULL)

def sql(statement):
    return subprocess.check_output([DOCKER,'compose','-p','travelmate-reliability','-f',str(ROOT/'scripts/infra-test.yml'),'exec','-T','mysql',
        'mysql','-utravelmate','-pvalidation-only','-N','travelmate','-e',statement],text=True,stderr=subprocess.DEVNULL).strip()

provider=ThreadingHTTPServer(('127.0.0.1',18890),Provider);threading.Thread(target=provider.serve_forever,daemon=True).start()
binding=None
try:
    a=start(18788);b=start(18789)
    user=req(18788,'POST','/api/auth/register',{'phone':'17'+str(int(time.time()))[-9:],'password':'isolated-test-password'})[1]['data'];token=user['accessToken']
    def state(id):return req(18789,'GET','/api/ai/jobs/'+id,token=token)[1]['data']['status']
    def submit(key=None):return req(18788,'POST','/api/ai/explanations/async',{'spotId':'1'},token,key)
    def complete(id):return wait(lambda:state(id)=='completed')
    before=Provider.count
    with concurrent.futures.ThreadPoolExecutor(2) as pool:
        results=list(pool.map(lambda port:req(port,'POST','/api/ai/explanations/async',{'spotId':'1'},token,'cross-instance-key'),[18788,18789]))
    ids=[r[1]['data']['jobId'] for r in results]
    check('cross-instance idempotent request',all(r[0]==200 for r in results) and len(set(ids))==1)
    complete(ids[0]);check('one model invocation for duplicate requests',Provider.count-before==1)
    check('confirmed outbox event persisted',sql("select status from guide_outbox where job_id='"+ids[0]+"'")=='sent')
    check('same key with changed payload rejected',req(18788,'POST','/api/ai/explanations/async',{'spotId':'2'},token,'cross-instance-key')[0]==409)
    Provider.failing=True
    id=submit()[1]['data']['jobId'];wait(lambda:state(id)=='failed')
    check('bounded retries exhausted',sql("select attempts from guide_job where id='"+id+"'")=='3')
    wait(lambda:rabbit('GET','/queues/%2F/guide.job.dlq').get('messages',0)>0)
    check('terminal failure delivered to dead-letter queue',True)
    Provider.failing=False
    check('owner can redrive failed job',req(18788,'POST','/api/ai/jobs/'+id+'/retry',{},token)[0]==200)
    complete(id);check('redrive completes with a new generation',int(sql("select generation from guide_job where id='"+id+"'"))==3)
    # Mandatory return: broker ACK alone must not mark an unroutable message sent.
    bindings=rabbit('GET','/bindings/%2F/e/guide.exchange/q/guide.job.queue')
    binding=next(x for x in bindings if x['routing_key']=='guide.job')
    rabbit('DELETE','/bindings/%2F/e/guide.exchange/q/guide.job.queue/'+urllib.parse.quote(binding['properties_key'],safe=''))
    before=Provider.count;id=submit()[1]['data']['jobId']
    wait(lambda:int(sql("select publish_attempts from guide_outbox where job_id='"+id+"'"))>=1)
    check('unroutable message remains recoverable',state(id)=='queued' and Provider.count==before)
    rabbit('POST','/bindings/%2F/e/guide.exchange/q/guide.job.queue',{'routing_key':'guide.job','arguments':{}});binding=None
    complete(id);check('restored routing recovers pending outbox',True)
    compose('stop');id=submit()[1]['data']['jobId']
    check('broker outage still accepts durable task',state(id)=='queued')
    compose('start');wait(lambda:rabbit('GET','/overview'));complete(id)
    check('broker restart recovers delivery',True)
    # Crash both workers while provider is blocked; force expiration of only this test job.
    Provider.block=True;Provider.release.clear();id=submit()[1]['data']['jobId'];wait(lambda:state(id)=='running')
    a.kill();b.kill();a.wait();b.wait();Provider.block=False;Provider.release.set()
    sql("update guide_job set lease_until=UTC_TIMESTAMP()-INTERVAL 1 SECOND where id='"+id+"'")
    a=start(18788);b=start(18789);complete(id)
    check('worker process crash recovered by lease',sql("select attempts from guide_job where id='"+id+"'")=='2')
finally:
    Provider.release.set()
    if binding:
        try:rabbit('POST','/bindings/%2F/e/guide.exchange/q/guide.job.queue',{'routing_key':'guide.job','arguments':{}})
        except Exception:pass
    for p in processes:
        if p.poll() is None:stop(p)
    for log in logs:log.close()
    provider.shutdown();provider.server_close()
    (ROOT/'docs/reliability-infrastructure-results.json').write_text(json.dumps({'mode':'real MySQL/RabbitMQ, two Java processes, local model fixture','checks':checks},ensure_ascii=False,indent=2))
