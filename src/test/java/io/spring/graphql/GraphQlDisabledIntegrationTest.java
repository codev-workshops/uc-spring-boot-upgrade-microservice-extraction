package io.spring.graphql;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "feature.graphql.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class GraphQlDisabledIntegrationTest {

  private final MockMvc mockMvc;

  @Autowired
  public GraphQlDisabledIntegrationTest(MockMvc mockMvc) {
    this.mockMvc = mockMvc;
  }

  @Test
  public void disabledGraphQlEndpointReturnsGone() throws Exception {
    mockMvc
        .perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"{ tags }\"}"))
        .andExpect(status().isGone());
  }
}
