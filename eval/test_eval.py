import unittest
from run_eval import assess
class EvaluationRulesTest(unittest.TestCase):
    def result(self):return {"answer":{"text":"需要核实","citations":[],"fallback":False},"constraints":{},"tools":[]}
    def test_missing_evidence_fails(self):self.assertIn("missing_citations",assess({"expect":{"citations":True}},self.result()))
    def test_fallback_is_not_quality_success(self):
        r=self.result();r["answer"]["fallback"]=True;self.assertIn("fallback",assess({"expect":{}},r))
    def test_over_budget_claim_fails(self):
        r=self.result();r["answer"]["route"]={"stops":[],"timingStatus":"within_budget","totalMinutes":130,"budgetMinutes":120}
        self.assertIn("invalid_budget_claim",assess({"expect":{}},r))
    def test_unknown_time_not_zero(self):
        r=self.result();r["answer"]["route"]={"stops":[],"timingStatus":"unverified","totalMinutes":0}
        self.assertIn("unknown_time_claim",assess({"expect":{}},r))
    def test_successful_tool_required(self):
        r=self.result();r["tools"]=[{"name":"plan_route","status":"error"}]
        self.assertIn("tool:plan_route",assess({"expect":{"tools":["plan_route"]}},r))
    def test_context_checked(self):self.assertIn("duration_not_updated",assess({"expect":{"durationMinutes":30}},self.result()))
if __name__=="__main__":unittest.main()
