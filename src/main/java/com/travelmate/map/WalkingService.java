package com.travelmate.map;
import com.fasterxml.jackson.databind.JsonNode;
import com.travelmate.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Real pedestrian routing. Never substitutes a straight line when the provider fails. */
@Service
public class WalkingService {
 private final LiveLocationService locations;
 private final RestClient client;
 private final String base;
 private final AtomicLong next=new AtomicLong();
 @org.springframework.beans.factory.annotation.Autowired
 public WalkingService(LiveLocationService locations,@Value("${app.map.walking-url:https://valhalla1.openstreetmap.de}")String base){
  this.locations=locations;this.base=base;
  var f=new org.springframework.http.client.SimpleClientHttpRequestFactory();f.setConnectTimeout(5000);f.setReadTimeout(15000);
  this.client=RestClient.builder().requestFactory(f).build();
 }
 WalkingService(LiveLocationService locations,RestClient client){this.locations=locations;this.client=client;this.base="https://valhalla1.openstreetmap.de";}
 public record Route(double distanceMeters,double durationSeconds,List<List<Double>> coordinates,String provider,String destinationId){}
 public Route route(String token,long uid,LiveLocationService.Position position){
  var c=locations.require(token,uid);LiveLocationService.validate(position);
  var poi=c.places().stream().filter(p->p.id().equals(c.selectedId())).findFirst().orElseThrow(()->ApiException.badRequest("请先选择目标地点"));
  long now=System.currentTimeMillis(),previous=next.get();
  if(now<previous||!next.compareAndSet(previous,now+2000))throw ApiException.serviceUnavailable("路线查询冷却中，请稍后重试");
  var xy=poi.location().split(",");
  var body=Map.of("locations",List.of(Map.of("lat",position.latitude(),"lon",position.longitude()),Map.of("lat",Double.parseDouble(xy[1]),"lon",Double.parseDouble(xy[0]))),
      "costing","pedestrian","format","osrm","shape_format","geojson","units","kilometers");
  try{
   var data=client.post().uri(base.replaceAll("/+$","")+"/route").header("X-Client-Id","travelmate-backend-local-prototype")
     .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
   return parse(data,poi.id());
  }catch(org.springframework.web.client.RestClientException e){throw ApiException.serviceUnavailable("步行路线服务暂不可用，没有用直线冒充路线，请稍后重试");}
 }
 static Route parse(JsonNode data,String id){
  if(data==null||!"Ok".equals(data.path("code").asText()))throw ApiException.serviceUnavailable("未找到可用步行路线");
  var r=data.path("routes").path(0);var points=r.path("geometry").path("coordinates");
  double distance=r.path("distance").asDouble(Double.NaN),duration=r.path("duration").asDouble(Double.NaN);
  if(!Double.isFinite(distance)||distance<0||!Double.isFinite(duration)||duration<0||!points.isArray()||points.size()<2||points.size()>20000)
   throw ApiException.serviceUnavailable("路线数据不完整");
  var coords=new ArrayList<List<Double>>();
  for(var p:points){double lng=p.path(0).asDouble(Double.NaN),lat=p.path(1).asDouble(Double.NaN);
   if(!Double.isFinite(lng)||!Double.isFinite(lat)||Math.abs(lng)>180||Math.abs(lat)>85)throw ApiException.serviceUnavailable("路线坐标无效");
   coords.add(List.of(lng,lat));}
  return new Route(distance,duration,List.copyOf(coords),"Valhalla / OpenStreetMap",id);
 }
}
