#!/usr/bin/env python3
"""Local deterministic transport fixture, NOT a language model or a quality evaluation.
Run only for UI/HTTP pipeline checks. Binds loopback, never calls external services.
"""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

def response(messages):
    question=next((m["content"] for m in reversed(messages) if m["role"]=="user"),"")
    last=messages[-1]
    if last["role"]=="tool":
        return {"role":"assistant","content":json.dumps({"answer":"这是本地测试响应，用于验证交互链路。景点资料待核实，步行时间尚未验证。","citationIds":[]},ensure_ascii=False)}
    if "比较" in question or "对比" in question:
        name,args="compare_spots",{"spotIds":["1","2"]}
    elif "替换" in question or "第二站" in question:
        name,args="plan_route",{"spotIds":["1","3"]}
    elif "路线" in question:
        name,args="plan_route",{"spotIds":["1","2"]}
    else:
        return {"role":"assistant","content":json.dumps({"answer":"本地测试响应：资料不足，需进一步核实。这不是模型生成内容。","citationIds":[]},ensure_ascii=False)}
    return {"role":"assistant","tool_calls":[{"id":"fixture-call","type":"function","function":{"name":name,"arguments":json.dumps(args)}}]}

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        try:
            payload=bytearray()
            if self.headers.get("Transfer-Encoding", "").lower()=="chunked":
                while True:
                    size=int(self.rfile.readline().split(b";")[0].strip(),16)
                    if size==0:
                        self.rfile.readline()
                        break
                    if len(payload)+size>100000:raise ValueError()
                    payload.extend(self.rfile.read(size));self.rfile.read(2)
            else:
                size=int(self.headers.get("Content-Length","0"))
                if size>100000:raise ValueError()
                payload.extend(self.rfile.read(size))
            body=json.loads(payload)
            data=json.dumps({"choices":[{"message":response(body["messages"])}]}).encode()
            self.send_response(200);self.send_header("Content-Type","application/json");self.send_header("Content-Length",str(len(data)));self.end_headers();self.wfile.write(data)
        except Exception:self.send_error(400)
    def log_message(self,*args):pass
if __name__=="__main__":
    print("Fixture provider on 127.0.0.1:18796 - no real AI calls",flush=True)
    ThreadingHTTPServer(("127.0.0.1",18796),Handler).serve_forever()
