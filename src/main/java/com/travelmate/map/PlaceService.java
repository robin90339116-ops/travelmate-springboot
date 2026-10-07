package com.travelmate.map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmate.domain.Spot;
import com.travelmate.repository.SpotRepository;
import com.travelmate.common.ApiException;
import com.travelmate.catalog.CatalogDtos.SpotView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.ArrayList;
import java.util.List;

/** Imports only a POI already returned by the trusted map adapter, not client-supplied facts. */
@Service @RequiredArgsConstructor
public class PlaceService {
 private final LiveLocationService locations;
 private final SpotRepository spots;
 private final PlatformTransactionManager transactions;
 private final PlaceEnricher enricher;
 private final ObjectMapper json;

 public SpotView importSelected(String token,long uid){
  var context=locations.require(token,uid);
  var poi=context.places().stream().filter(p->p.id().equals(context.selectedId())).findFirst()
    .orElseThrow(()->ApiException.badRequest("请先确认本次定位中的地点"));
  var known=spots.findByExternalId(poi.id());
  if(known.isPresent()&&known.get().getExtraFacts()!=null)return view(known.get());
  // Network enrichment runs outside the transaction; failures only mean fewer facts.
  var enrichment=enricher.enrich(poi,context);
  String facts=encodeFacts(enrichment.facts());
  try {
   return new TransactionTemplate(transactions).execute(status -> {
    var existing=spots.findByExternalId(poi.id());
    if(existing.isPresent()){
     Spot s=existing.get();
     if(s.getExtraFacts()==null){apply(s,enrichment,facts,poi);spots.saveAndFlush(s);}
     return view(s);
    }
    String[] xy=poi.location().split(",");
    if(xy.length!=2)throw ApiException.badRequest("地点坐标无效");
    double lng=Double.parseDouble(xy[0]),lat=Double.parseDouble(xy[1]);
    if(!Double.isFinite(lng)||!Double.isFinite(lat)||Math.abs(lng)>180||Math.abs(lat)>85)
     throw ApiException.badRequest("地点坐标无效");
    Spot s=new Spot();s.setExternalId(poi.id());s.setCityKey("live-osm");
    s.setName(limit(poi.name(),64));s.setCategory(limit(poi.category(),32));
    s.setHighlight("请结合现场观察，勿将定位候选误认为镜头中的建筑");
    s.setRecommendedDuration("根据现场情况决定");s.setTags("");
    s.setSourceStatus("community");
    s.setLongitude(lng);s.setLatitude(lat);
    apply(s,enrichment,facts,poi);
    return view(spots.saveAndFlush(s));
   });
  }catch(DataIntegrityViolationException e){
   // A concurrent import may win the unique index. Read after the losing transaction rolled back.
   return spots.findByExternalId(poi.id()).map(PlaceService::view).orElseThrow(()->e);
  }
 }

 private static void apply(Spot s,PlaceEnricher.Enrichment e,String facts,LiveLocationService.Poi poi){
  s.setIntro(e.intro());s.setOpenTime(e.openTime());s.setSourceName(e.sourceName());
  s.setExtraFacts(facts);s.setSourceUrl(limit(poi.sourceUrl(),512));
 }

 /** JSON array, always non-null after import so a later import does not re-fetch; kept within the 4000-char column. */
 String encodeFacts(List<String> facts){
  try{
   var list=new ArrayList<String>();for(String f:facts)list.add(limit(f,1200));
   String out=json.writeValueAsString(list);
   while(out.length()>4000&&!list.isEmpty()){list.remove(list.size()-1);out=json.writeValueAsString(list);}
   return out;
  }catch(com.fasterxml.jackson.core.JsonProcessingException e){return "[]";}
 }

 public SpotView get(long id){return view(spots.findById(id).orElseThrow(()->ApiException.notFound("地点不存在")));}
 static String limit(String s,int n){return s==null?"":s.substring(0,Math.min(s.length(),n));}
 static SpotView view(Spot s){return new SpotView(s.getId().toString(),s.getCityKey(),s.getName(),s.getCategory(),s.getIntro(),s.getHighlight(),s.getOpenTime(),s.getRecommendedDuration(),List.of(),s.getSourceName(),s.getSourceStatus(),s.getLatitude(),s.getLongitude());}
}
