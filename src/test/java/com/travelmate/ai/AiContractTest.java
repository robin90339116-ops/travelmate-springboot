package com.travelmate.ai;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.travelmate.common.ApiException;
import com.travelmate.repository.SpotRepository;
import com.travelmate.domain.Spot;
import java.util.*;

class AiContractTest {
 @Test void cameraObservationWorksWithoutInventingASpot(){
  var provider=mock(QwenClient.class);var repo=mock(SpotRepository.class);
  when(provider.visionModel()).thenReturn("vision");when(provider.chat(anyString(),anyList())).thenReturn("画面观察");
  var ai=new AiService(provider,repo);
  assertEquals("画面观察",ai.vision(new AiDtos.VisionRequest(null,"image","data:image/jpeg;base64,YQ==",null)).answer());
  verifyNoInteractions(repo);
  verify(provider).chat(eq("vision"),argThat(messages->messages.toString().contains("未提供地点资料")&&messages.toString().contains("不猜测所在地点")));
 }
 @Test void retriesTransientProviderFailure(){
  var builder=RestClient.builder();var mock=MockRestServiceServer.bindTo(builder).build();
  var client=new QwenClient("test","https://provider.invalid/v1","text","vision","test",builder.build());
  mock.expect(anything()).andRespond(withServerError());
  mock.expect(anything()).andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",MediaType.APPLICATION_JSON));
  assertEquals("ok",client.chat("text",List.of()));mock.verify();
 }
 @Test void routePlannerRejectsInventedIds() throws Exception {
  var provider=mock(QwenClient.class);var catalog=mock(com.travelmate.catalog.CatalogService.class);
  when(catalog.listSpots("test")).thenReturn(List.of(new com.travelmate.catalog.CatalogDtos.SpotView("1","test","A","type","intro","h","unknown","unknown",List.of(),"demo","pending",1d,1d)));
  when(provider.textModel()).thenReturn("text");when(provider.chat(anyString(),anyList())).thenReturn("{\"spotIds\":[\"999\"]}");
  var validator=mock(com.travelmate.assistant.RouteValidator.class);
  when(validator.validate(any(),anyList())).thenReturn(new com.travelmate.assistant.AssistantDtos.RouteCard(
      List.of(new com.travelmate.assistant.AssistantDtos.Stop("1","A",20,"unknown","pending")),20,120,"within_budget",List.of(),List.of()));
  var planner=new RoutePlanningController(provider,mock(SpotRepository.class),new ObjectMapper(),catalog,validator);
  assertThrows(ApiException.class,()->planner.generate(new RoutePlanningController.Request("test",2,"history")));
  when(provider.chat(anyString(),anyList())).thenReturn("{\"spotIds\":[\"1\"]}");
  assertNotNull(planner.generate(new RoutePlanningController.Request("test",2,"history")));
 }
 @Test void providerSuccessAndNoCredentials() {
  var builder=RestClient.builder();var mock=MockRestServiceServer.bindTo(builder).build();
  var client=new QwenClient("test","https://provider.invalid/v1","text","vision","test",builder.build());
  mock.expect(requestTo("https://provider.invalid/v1/chat/completions"))
      .andExpect(header("Authorization","Bearer test"))
      .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"测试回答\"}}]}",MediaType.APPLICATION_JSON));
  assertEquals("测试回答",client.chat("text",List.of(Map.of("role","user","content","test"))));mock.verify();
  assertThrows(ApiException.class,()->new QwenClient("","https://unused","t","v","test").chat("t",List.of()));
 }
 @Test void malformedProviderResponseIsControlledError(){
  var builder=RestClient.builder();var mock=MockRestServiceServer.bindTo(builder).build();
  var client=new QwenClient("test","https://provider.invalid/v1","text","vision","test",builder.build());
  mock.expect(anything()).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
  assertThrows(ApiException.class,()->client.chat("text",List.of()));mock.verify();
 }
 @Test void explanationContainsGroundingAndVisionContainsMedia(){
  var provider=mock(QwenClient.class);var repo=mock(SpotRepository.class);var spot=new Spot();
  spot.setId(1L);spot.setName("测试景点");spot.setSourceName("演示资料");spot.setSourceStatus("pending");
  when(repo.findById(1L)).thenReturn(Optional.of(spot));when(provider.textModel()).thenReturn("text");when(provider.visionModel()).thenReturn("vision");
  when(provider.chat(anyString(),anyList())).thenReturn("test");
  var ai=new AiService(provider,repo);
  assertEquals("待现场核实信息",ai.explanation(new AiDtos.ExplanationRequest("1","story",null)).factType());
  verify(provider).chat(eq("text"),argThat(messages->messages.toString().contains("availableFacts")&&messages.toString().contains("不得使用模型自带知识")));
  ai.vision(new AiDtos.VisionRequest("1","photo","https://example.invalid/photo.jpg",null));
  verify(provider).chat(eq("vision"),argThat(messages->messages.toString().contains("image_url")));
 }
}
