package com.travelmate.ai;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
class QwenToolContractTest {
    @Test void preservesToolCallAndSendsDefinition(){
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var client=new QwenClient("test","https://model.invalid/v1","test","vision","mock",builder.build());
        server.expect(requestTo("https://model.invalid/v1/chat/completions"))
            .andExpect(jsonPath("$.tools[0].function.name").value("search_spots"))
            .andExpect(jsonPath("$.tool_choice").value("auto"))
            .andRespond(withSuccess("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"search_spots\",\"arguments\":\"{}\"}}]}}]}",MediaType.APPLICATION_JSON));
        var result=client.complete(List.of(Map.of("role","user","content","hello")),List.of(Map.of("type","function","function",Map.of("name","search_spots"))));
        assertNotNull(result.get("tool_calls"));server.verify();
    }
    @Test void malformedToolResponseFailsClosed(){
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var client=new QwenClient("test","https://model.invalid/v1","test","vision","mock",builder.build());
        server.expect(anything()).andRespond(withSuccess("{\"choices\":[{\"message\":{}}]}",MediaType.APPLICATION_JSON));
        assertThrows(com.travelmate.common.ApiException.class,()->client.complete(List.of(),List.of()));
    }
}
