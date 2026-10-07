package com.travelmate.map;
import com.fasterxml.jackson.databind.*;
import com.travelmate.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
@SpringBootTest(properties={"app.ai.api-key=","spring.datasource.url=jdbc:h2:mem:places;MODE=MySQL","app.knowledge.wikipedia-enabled=false"})
@AutoConfigureMockMvc
class PlaceIntegrationTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json;
 @MockBean LiveLocationService locations;
 @MockBean com.travelmate.ai.QwenClient qwen;
 JsonNode login()throws Exception{
  String phone="13"+String.format("%09d",java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000_000));
  return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("phone",phone,"code","246810")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
 }
 String bearer(JsonNode u){return "Bearer "+u.path("accessToken").asText();}
 String fixture(JsonNode u, boolean selected){
  String token=UUID.randomUUID().toString();long uid=u.at("/user/id").asLong();
  var poi=new LiveLocationService.Poi("node/"+Math.abs(UUID.randomUUID().getMostSignificantBits()),"Real public place","Public address","museum","145,-37","https://www.openstreetmap.org/");
  when(locations.require(token,uid)).thenReturn(new LiveLocationService.Context(token,uid,new LiveLocationService.Position(144,-38,5,System.currentTimeMillis()),"144,-38","Device location",List.of(poi),selected?poi.id():"","",List.of(),System.currentTimeMillis()+90000));
  return token;
 }
 JsonNode imported(JsonNode user,String token)throws Exception{
  return json.readTree(mvc.perform(post("/api/places/import").header("Authorization",bearer(user)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("contextToken",token)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
 }
 @Test void requiresAuthentication()throws Exception{mvc.perform(post("/api/places/import").contentType(MediaType.APPLICATION_JSON).content("{\"contextToken\":\"x\"}")).andExpect(status().isUnauthorized());}
 @Test void rejectsUnselectedPoi()throws Exception{var u=login();var t=fixture(u,false);mvc.perform(post("/api/places/import").header("Authorization",bearer(u)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("contextToken",t)))).andExpect(status().isBadRequest());}
 @Test void rejectsOtherUsersContext()throws Exception{
  var owner=login();var other=login();String t=fixture(owner,true);
  when(locations.require(t,other.at("/user/id").asLong())).thenThrow(ApiException.badRequest("定位上下文已过期"));
  mvc.perform(post("/api/places/import").header("Authorization",bearer(other)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("contextToken",t)))).andExpect(status().isBadRequest());
 }
 @Test void importIsIdempotentAndStoresPublicPoiNotDeviceCoordinates()throws Exception{
  var u=login();String token=fixture(u,true);var a=imported(u,token);var b=imported(u,token);
  assertEquals(a.path("id"),b.path("id"));assertEquals(145,a.path("longitude").asDouble());assertEquals(-37,a.path("latitude").asDouble());
  assertEquals("community",a.path("sourceStatus").asText());
  mvc.perform(get("/api/places/"+a.path("id").asText()).header("Authorization",bearer(u))).andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("Real public place"));
 }
 @Test void importedPoiWorksWithFavoritesAndTeamQuestions()throws Exception{
  var u=login();var p=imported(u,fixture(u,true));String id=p.path("id").asText();
  mvc.perform(post("/api/favorites").header("Authorization",bearer(u)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("targetType","spot","targetId",id,"title","Real public place")))).andExpect(status().isOk());
  var result=mvc.perform(post("/api/teams").header("Authorization",bearer(u)).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk()).andReturn();
  String team=json.readTree(result.getResponse().getContentAsString()).at("/data/teamId").asText();
  mvc.perform(post("/api/teams/"+team+"/questions").header("Authorization",bearer(u)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("spotId",id,"question","What can I observe?")))).andExpect(status().isOk());
 }
 @Test void conversationPersistsAndIsPrivate()throws Exception{
  when(qwen.textModel()).thenReturn("test-model");when(qwen.chat(anyString(),anyList())).thenReturn("仅描述公开地点资料");
  var owner=login();var other=login();var p=imported(owner,fixture(owner,true));String id=p.path("id").asText();
  mvc.perform(post("/api/ai/conversation").header("Authorization",bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("spotId",id,"question","介绍一下")))).andExpect(status().isOk());
  mvc.perform(get("/api/ai/conversation").param("spotId",id).header("Authorization",bearer(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].question").value("介绍一下"));
  mvc.perform(get("/api/ai/conversation").param("spotId",id).header("Authorization",bearer(other))).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
  mvc.perform(post("/api/ai/conversation").header("Authorization",bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("spotId",id,"question","")))).andExpect(status().isBadRequest());
 }

 @Test void importedFactsReachTheAiPrompt()throws Exception{
  when(qwen.textModel()).thenReturn("test-model");when(qwen.chat(anyString(),anyList())).thenReturn("讲解");
  var u=login();String token=UUID.randomUUID().toString();long uid=u.at("/user/id").asLong();
  var poi=new LiveLocationService.Poi("node/"+Math.abs(UUID.randomUUID().getMostSignificantBits()),"Old Gate","Gate street","historic","145,-37",
    "https://www.openstreetmap.org/",Map.of("description","Nineteenth-century city gate","opening_hours","24/7","start_date","1856"));
  when(locations.require(token,uid)).thenReturn(new LiveLocationService.Context(token,uid,new LiveLocationService.Position(144,-38,5,System.currentTimeMillis()),"144,-38","Device location",List.of(poi),poi.id(),"",List.of(),System.currentTimeMillis()+90000));
  var p=imported(u,token);
  assertEquals("Nineteenth-century city gate",p.path("intro").asText());
  assertEquals("OpenStreetMap",p.path("sourceName").asText());
  assertTrue(p.path("openTime").asText().contains("24/7"));
  mvc.perform(post("/api/ai/explanations").header("Authorization",bearer(u)).contentType(MediaType.APPLICATION_JSON)
    .content(json.writeValueAsString(Map.of("spotId",p.path("id").asText(),"style","brief")))).andExpect(status().isOk())
    .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.startsWith("以下基于OpenStreetMap社区地点资料生成")));
  @SuppressWarnings("unchecked") org.mockito.ArgumentCaptor<List<Map<String,Object>>> sent=org.mockito.ArgumentCaptor.forClass(List.class);
  verify(qwen,atLeastOnce()).chat(anyString(),sent.capture());
  String prompt=sent.getValue().toString();
  assertTrue(prompt.contains("OpenStreetMap 描述：Nineteenth-century city gate"),prompt);
  assertTrue(prompt.contains("OpenStreetMap 建成或始建时间：1856"),prompt);
 }
}
