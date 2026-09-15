package com.travelmate.assistant;

import com.fasterxml.jackson.databind.*;
import com.travelmate.ai.QwenClient;
import com.travelmate.assistant.AssistantDtos.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:assistant;MODE=MySQL","app.map.amap-web-key="})
@AutoConfigureMockMvc
class AssistantIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired KnowledgeService knowledge;
    @Autowired AssistantSessionRepository sessions;
    @Autowired AssistantStore store;
    @Autowired AssistantTraceRepository traces;
    @MockBean QwenClient model;
    String token;
    @BeforeEach void login() throws Exception {
        when(model.textModel()).thenReturn("mock-evaluation");
        String phone="15"+String.format("%09d",java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000_000));
        token=json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("phone",phone,"code","246810"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).at("/data/accessToken").asText();
    }
    String create() throws Exception {return json.readTree(mvc.perform(post("/api/ai/assistant/sessions").header("Authorization","Bearer "+token)
        .contentType(MediaType.APPLICATION_JSON).content("{\"cityKey\":\"beijing\",\"durationMinutes\":120}"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).at("/data/id").asText();}
    JsonNode turn(String id,String message) throws Exception {return json.readTree(mvc.perform(post("/api/ai/assistant/sessions/"+id+"/messages")
        .header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("message",message))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data");}
    Map<String,Object> finish(String text){return Map.of("role","assistant","content","{\"answer\":\""+text+"\",\"citationIds\":[]}");}
    Map<String,Object> call(String name,String args){return Map.of("role","assistant","tool_calls",List.of(Map.of("id",UUID.randomUUID().toString(),"type","function","function",Map.of("name",name,"arguments",args))));}
    @Test void multiTurnPreservesConstraintsAndCards() throws Exception {
        String id=create();
        when(model.complete(anyList(),anyList())).thenReturn(call("update_constraints","{\"companions\":\"带孩子\"}"))
            .thenReturn(call("plan_route","{\"spotIds\":[\"1\",\"2\"]}")).thenReturn(finish("演示路线，步行时间未验证"));
        var first=turn(id,"想看建筑，带孩子");assertEquals("带孩子",first.at("/constraints/companions").asText());assertEquals("unverified",first.at("/answer/route/timingStatus").asText());
        when(model.complete(anyList(),anyList())).thenAnswer(inv->{
            String payload=json.writeValueAsString(inv.getArgument(0));assertTrue(payload.contains("带孩子"));assertTrue(payload.contains("currentRouteIds"));assertTrue(payload.contains("想看建筑"));
            return call("plan_route","{\"spotIds\":[\"1\",\"3\"]}");
        }).thenReturn(finish("已替换第二站，时间仍待核实"));
        var second=turn(id,"第二站换成前门");assertEquals("3",second.at("/answer/route/stops/1/id").asText());assertEquals(120,second.at("/constraints/durationMinutes").asInt());
        assertEquals("带孩子",second.at("/constraints/companions").asText());
    }
    @Test void updatingBudgetRevalidatesExistingRoute() throws Exception {
        String id=create();when(model.complete(anyList(),anyList())).thenReturn(call("plan_route","{\"spotIds\":[\"2\"]}")).thenReturn(finish("演示资料"));turn(id,"去天坛");
        when(model.complete(anyList(),anyList())).thenReturn(call("update_constraints","{\"durationMinutes\":30}")).thenReturn(finish("已修改预算"));
        var result=turn(id,"只有30分钟");assertEquals("infeasible",result.at("/answer/route/timingStatus").asText());
    }
    @Test void foreignSessionAndTraceAreHidden() throws Exception {
        String id=create();when(model.complete(anyList(),anyList())).thenReturn(finish("资料不足"));String trace=turn(id,"你好").path("traceId").asText();
        login();mvc.perform(get("/api/ai/assistant/sessions/"+id).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/ai/assistant/traces/"+trace).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/ai/assistant/sessions/"+id).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
    }
    @Test void invalidToolsReturnControlledFeedback() throws Exception {
        String id=create();when(model.complete(anyList(),anyList())).thenReturn(call("plan_route","{\"spotIds\":[\"999999\"]}"))
            .thenReturn(call("exec_shell","{}" )).thenReturn(finish("无法完成该请求"));
        var result=turn(id,"测试无效工具");assertEquals("error",result.at("/tools/0/status").asText());assertEquals("error",result.at("/tools/1/status").asText());assertTrue(result.at("/answer/route").isNull());
    }
    @Test void runawayToolsStopAtBudget() throws Exception {
        when(model.complete(anyList(),anyList())).thenReturn(call("search_spots","{\"query\":\"\"}"));
        var result=turn(create(),"一直查询");assertTrue(result.at("/answer/fallback").asBoolean());assertEquals(8,result.path("tools").size());verify(model,times(9)).complete(anyList(),anyList());
    }
    @Test void malformedOutputAndInventedCitationFallback() throws Exception {
        String id=create();when(model.complete(anyList(),anyList())).thenReturn(Map.of("content","<script>alert(1)</script>"));assertTrue(turn(id,"问答").at("/answer/fallback").asBoolean());
        when(model.complete(anyList(),anyList())).thenReturn(Map.of("content","{\"answer\":\"假的引用\",\"citationIds\":[\"invented\"]}"));assertTrue(turn(id,"问答").at("/answer/fallback").asBoolean());
    }
    @Test void retrievalKeepsCityScopeAndProvenance() {
        var found=knowledge.retrieve("beijing","天坛建筑",5);assertFalse(found.isEmpty());assertTrue(found.stream().anyMatch(c->c.id().startsWith("beijing-demo-")));
        assertTrue(found.stream().allMatch(c->c.sourceStatus().equals("pending")));
        assertTrue(knowledge.retrieve("xian","天坛",5).isEmpty());assertTrue(knowledge.retrieve("beijing","zzzz-no-match",5).isEmpty());
    }
    @Test void comparisonCardUsesServerData() throws Exception {
        when(model.complete(anyList(),anyList())).thenReturn(call("compare_spots","{\"spotIds\":[\"1\",\"2\"]}")).thenReturn(finish("资料待核实"));
        var result=turn(create(),"比较两个景点");assertEquals(2,result.at("/answer/comparison").size());assertEquals("永定门",result.at("/answer/comparison/0/name").asText());
    }
    @Test void deletingSessionDeletesTrace() throws Exception {
        String id=create();when(model.complete(anyList(),anyList())).thenReturn(finish("你好"));String trace=turn(id,"你好").path("traceId").asText();
        mvc.perform(delete("/api/ai/assistant/sessions/"+id).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        assertFalse(sessions.existsById(id));assertFalse(traces.existsById(trace));
    }
    @Test void concurrentSnapshotsCannotOverwrite() throws Exception {
        String id=create();var first=sessions.findById(id).orElseThrow();var stale=sessions.findById(id).orElseThrow();
        first.setCompanions("亲子");sessions.saveAndFlush(first);stale.setCompanions("朋友");
        assertThrows(org.springframework.dao.OptimisticLockingFailureException.class,()->sessions.saveAndFlush(stale));
    }
    @Test void rejectsBadRequestsAndAnonymousAccess() throws Exception {
        mvc.perform(get("/api/ai/assistant/sessions")).andExpect(status().isUnauthorized());
        String id=create();mvc.perform(post("/api/ai/assistant/sessions/"+id+"/messages").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/assistant/index.html")).andExpect(status().isOk());
    }
    @Test void providerFailureHasTraceReason() throws Exception {
        when(model.complete(anyList(),anyList())).thenThrow(com.travelmate.common.ApiException.serviceUnavailable("测试模型不可用"));
        var result=turn(create(),"你好");assertTrue(result.at("/answer/fallback").asBoolean());
        var trace=traces.findById(result.path("traceId").asText()).orElseThrow();assertTrue(trace.getFailureReason().contains("测试模型不可用"));
    }
    @Test void accountRecordsIncludeAndClearAssistantData() throws Exception {
        String id=create();when(model.complete(anyList(),anyList())).thenReturn(finish("你好"));
        String trace=turn(id,"你好").path("traceId").asText();
        mvc.perform(get("/api/data/export").header("Authorization","Bearer "+token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assistantSessions[0].id").value(id));
        mvc.perform(delete("/api/data/records").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        assertFalse(sessions.existsById(id));assertFalse(traces.existsById(trace));
    }
}
