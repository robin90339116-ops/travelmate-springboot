package com.travelmate.assistant;
import com.travelmate.domain.Spot;
import com.travelmate.repository.SpotRepository;
import com.travelmate.assistant.AssistantDtos.*;
import com.travelmate.common.ApiException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RouteValidatorTest {
    Spot spot(long id,String duration){var s=new Spot();s.setId(id);s.setCityKey("beijing");s.setName("地点"+id);s.setRecommendedDuration(duration);s.setSourceStatus("pending");return s;}
    @Test void removesOverBudgetStopsUsingRealLegs(){
        var repo=mock(SpotRepository.class);var map=mock(WalkingRouteService.class);var a=spot(1,"20 分钟");var b=spot(2,"90 分钟");
        when(repo.findById(1L)).thenReturn(Optional.of(a));when(repo.findById(2L)).thenReturn(Optional.of(b));
        when(map.walking(a,b)).thenReturn(new WalkingRouteService.Leg("1","2",30,1500,"verified"));
        var r=new RouteValidator(repo,map).validate(new Constraints("beijing",120,"",""),List.of("1","2"));
        assertEquals(List.of("2"),r.removedSpotIds());assertEquals(20,r.totalMinutes());assertEquals("within_budget",r.timingStatus());
    }
    @Test void unknownWalkingTimeNeverBecomesZero(){
        var repo=mock(SpotRepository.class);var map=mock(WalkingRouteService.class);var a=spot(1,"20 分钟");var b=spot(2,"90 分钟");
        when(repo.findById(1L)).thenReturn(Optional.of(a));when(repo.findById(2L)).thenReturn(Optional.of(b));
        when(map.walking(a,b)).thenReturn(new WalkingRouteService.Leg("1","2",null,null,"unavailable"));
        var r=new RouteValidator(repo,map).validate(new Constraints("beijing",120,"",""),List.of("1","2"));
        assertNull(r.totalMinutes());assertEquals("unverified",r.timingStatus());assertEquals(2,r.stops().size());
    }
    @Test void tooShortBudgetIsInfeasible(){var repo=mock(SpotRepository.class);when(repo.findById(1L)).thenReturn(Optional.of(spot(1,"90 分钟")));
        var r=new RouteValidator(repo,mock(WalkingRouteService.class)).validate(new Constraints("beijing",15,"",""),List.of("1"));
        assertEquals("infeasible",r.timingStatus());assertTrue(r.stops().isEmpty());}
    @Test void rejectsDuplicatesAndCrossCity(){var repo=mock(SpotRepository.class);when(repo.findById(1L)).thenReturn(Optional.of(spot(1,"20 分钟")));
        var r=new RouteValidator(repo,mock(WalkingRouteService.class));
        assertThrows(ApiException.class,()->r.resolve("beijing",List.of("1","1")));
        assertThrows(ApiException.class,()->r.resolve("xian",List.of("1")));}
    @Test void durationParserDoesNotGuessRanges(){assertNull(RouteValidator.stayMinutes("20-90 分钟"));assertNull(RouteValidator.stayMinutes("待核实"));assertEquals(90,RouteValidator.stayMinutes("90 分钟"));}
    @Test void contextKeepsCompletePairsWithinBudget(){var history=new ArrayList<Message>();for(int i=0;i<20;i++){history.add(new Message("user","u".repeat(1000)));history.add(new Message("assistant","a".repeat(1000)));}
        var window=AssistantService.window(history);assertEquals(8,window.size());assertEquals("user",window.get(0).role());assertEquals("assistant",window.get(7).role());}
}
