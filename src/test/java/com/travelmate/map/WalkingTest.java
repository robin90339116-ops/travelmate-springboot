package com.travelmate.map;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmate.common.ApiException;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
class WalkingTest {
 static final String GOOD="{\"code\":\"Ok\",\"routes\":[{\"distance\":123,\"duration\":98,\"geometry\":{\"coordinates\":[[145,-37],[145.001,-37.001]]}}]}";
 @Test void usesPedestrianProviderAndActualPosition()throws Exception{
  var locations=mock(LiveLocationService.class);var b=RestClient.builder();var remote=MockRestServiceServer.bindTo(b).build();
  var c=new LiveLocationService.Context("x",1,new LiveLocationService.Position(145,-37,5,System.currentTimeMillis()),"","",List.of(new LiveLocationService.Poi("node/1","POI","","","145.001,-37.001","")),"node/1","",List.of(),System.currentTimeMillis()+90000);
  when(locations.require("x",1)).thenReturn(c);
  remote.expect(requestTo("https://valhalla1.openstreetmap.de/route")).andExpect(jsonPath("$.costing").value("pedestrian")).andExpect(jsonPath("$.locations[0].lon").value(145)).andRespond(withSuccess(GOOD,MediaType.APPLICATION_JSON));
  var service=new WalkingService(locations,b.build());assertEquals(123,service.route("x",1,c.gps()).distanceMeters());
  assertThrows(ApiException.class,()->service.route("x",1,c.gps()));remote.verify();
 }
 @Test void rejectsEmptyMalformedOrFailedRoutes()throws Exception{
  var json=new ObjectMapper();for(var data:List.of("{}","{\"code\":\"NoRoute\"}",GOOD.replace("123","-1"),GOOD.replace("145.001","999")))
   assertThrows(ApiException.class,()->WalkingService.parse(json.readTree(data),"node/1"));
 }
}
