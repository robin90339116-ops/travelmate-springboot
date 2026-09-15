#!/usr/bin/env python3
"""Evaluate the running assistant; real model calls may incur provider charges.
Never equate automatic structure checks with factual accuracy. Uses a dedicated test account.
"""
import argparse, json, os, time, urllib.request, urllib.error
from pathlib import Path
from datetime import datetime, timezone

def assess(case, result):
    failures=[]
    answer=result.get("answer") or {}
    expected=case["expect"]
    if not isinstance(answer.get("text"),str) or not answer.get("text"): failures.append("missing_answer")
    if answer.get("fallback") and expected.get("noFallbackRequired",True): failures.append("fallback")
    for name in expected.get("tools",[]):
        if not any(t.get("name")==name and t.get("status")=="ok" for t in result.get("tools",[])):failures.append("tool:"+name)
    if expected.get("citations") and not answer.get("citations"):failures.append("missing_citations")
    if expected.get("route") and answer.get("route") is None:failures.append("missing_route")
    if expected.get("comparison") and len(answer.get("comparison") or [])<2:failures.append("missing_comparison")
    constraints=result.get("constraints") or {}
    if "durationMinutes" in expected and constraints.get("durationMinutes")!=expected["durationMinutes"]:failures.append("duration_not_updated")
    if "companionsContains" in expected and expected["companionsContains"] not in constraints.get("companions",""):failures.append("companions_not_updated")
    if expected.get("uncertainty") and not any(w in answer.get("text","") for w in ("核实","确认","未知","无法","不能","不支持","不提供","不足")):failures.append("uncertainty_review_needed")
    route=answer.get("route")
    if route:
        ids=[s["id"] for s in route.get("stops",[])]
        if len(ids)!=len(set(ids)):failures.append("duplicate_stops")
        if route.get("timingStatus")=="within_budget":
            total=route.get("totalMinutes")
            if total is None or not 0<=total<=route.get("budgetMinutes",-1):failures.append("invalid_budget_claim")
        elif route.get("timingStatus")=="unverified" and route.get("totalMinutes") is not None:failures.append("unknown_time_claim")
    return failures

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url",default="http://127.0.0.1:8787")
    parser.add_argument("--cases",type=Path,default=Path(__file__).with_name("cases.json"))
    parser.add_argument("--output",type=Path,default=Path("eval/results/latest.json"))
    parser.add_argument("--limit",type=int)
    parser.add_argument("--baseline",type=Path)
    args=parser.parse_args()
    token=os.environ.get("TRAVELMATE_EVAL_TOKEN")
    if not token:parser.error("Set TRAVELMATE_EVAL_TOKEN to a dedicated account access token; do not put it in a command argument.")
    base=args.base_url.rstrip("/")
    if not (base.startswith("http://127.0.0.1:") or base.startswith("http://localhost:") or base.startswith("https://")):
        parser.error("Use localhost HTTP or HTTPS")
    def request(path,method="GET",body=None):
        data=None if body is None else json.dumps(body).encode()
        req=urllib.request.Request(base+path,data,headers={"Authorization":"Bearer "+token,"Content-Type":"application/json"},method=method)
        with urllib.request.urlopen(req,timeout=180) as response: result=json.load(response)
        if result.get("code")!=0:raise RuntimeError(result.get("message","API error"))
        return result["data"]
    cases=json.loads(args.cases.read_text())
    if args.limit is not None:cases=cases[:max(0,args.limit)]
    report={"evaluatedAt":datetime.now(timezone.utc).isoformat(),"mode":"live_endpoint",
        "qualityAccuracy":None,"humanReview":"pending","cases":[]}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    for case in cases:
        session=None;started=time.monotonic();entry={"id":case["id"],"category":case["category"],"humanReview":"pending"}
        try:
            # Stay below the existing per-account POST rate limit.
            time.sleep(2.1)
            session=request("/api/ai/assistant/sessions","POST",{"cityKey":case["cityKey"],"durationMinutes":case["durationMinutes"]})["id"]
            for message in case.get("setup",[])+[case["message"]]:
                time.sleep(2.1)
                result=request(f"/api/ai/assistant/sessions/{session}/messages","POST",{"message":message})
            entry.update(result=result,failures=assess(case,result))
            if result.get("model", "").startswith("fixture-"):report["mode"]="fixture_endpoint_not_model_quality"
        except Exception as exc:
            entry.update(failures=["request_error"],error=type(exc).__name__)
        finally:
            if session:
                try:request(f"/api/ai/assistant/sessions/{session}","DELETE")
                except Exception:entry["cleanupRequired"]=session
        entry["elapsedMs"]=round((time.monotonic()-started)*1000)
        report["cases"].append(entry)
        args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2))
        print(case["id"],"PASS" if not entry["failures"] else ",".join(entry["failures"]),flush=True)
    report["automaticPasses"]=sum(not c["failures"] for c in report["cases"])
    report["total"]=len(report["cases"])
    report["badcases"]=[c["id"] for c in report["cases"] if c["failures"]]
    if args.baseline:
        old={c["id"]:c for c in json.loads(args.baseline.read_text())["cases"]}
        report["comparison"]={"improved":[c["id"] for c in report["cases"] if c["id"] in old and old[c["id"]]["failures"] and not c["failures"]],
            "regressed":[c["id"] for c in report["cases"] if c["id"] in old and not old[c["id"]]["failures"] and c["failures"]]}
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2))
    md=["# AI 应用评测报告", "", "运行模式："+report["mode"]+"。fixture 模式仅验证软件链路，不代表真实模型效果。", "",f"自动规则通过：{report['automaticPasses']}/{report['total']}。真实回答质量仍需人工复核。", "", "| 样例 | 自动检查 | 待核查问题 |", "|---|---|---|"]
    for c in report["cases"]:md.append(f"| {c['id']} | {'通过' if not c['failures'] else '失败'} | {', '.join(c['failures']) or '事实与引用一致性、用户意图'} |")
    args.output.with_suffix(".md").write_text("\n".join(md)+"\n")

if __name__=="__main__": main()
