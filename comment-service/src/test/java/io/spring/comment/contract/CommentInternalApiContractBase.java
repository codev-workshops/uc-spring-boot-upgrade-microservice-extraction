package io.spring.comment.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.comment.JacksonCustomizations;
import io.spring.comment.api.CommentInternalApi;
import io.spring.comment.api.exception.CustomizeExceptionHandler;
import io.spring.comment.api.security.JwtTokenFilter;
import io.spring.comment.application.CommentCommandService;
import io.spring.comment.application.CommentQueryService;
import io.spring.comment.application.data.CommentData;
import io.spring.comment.core.service.JwtService;
import java.util.Arrays;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Base class for the provider-side tests Spring Cloud Contract generates from
 * src/test/resources/contracts. Standalone MockMvc around CommentInternalApi with mocked services;
 * the JwtTokenFilter runs with a stub JwtService that accepts the contract token.
 */
public abstract class CommentInternalApiContractBase {
  public static final String CONTRACT_TOKEN = "contract-token";
  public static final String USER_ID = "user-1";
  public static final DateTime T1 = new DateTime(1706696130123L, DateTimeZone.UTC);
  public static final DateTime T2 = new DateTime(1706609730123L, DateTimeZone.UTC);

  @BeforeEach
  public void setUp() {
    CommentQueryService queryService = mock(CommentQueryService.class);
    CommentCommandService commandService = mock(CommentCommandService.class);
    JwtService jwtService = mock(JwtService.class);

    when(jwtService.getSubFromToken(anyString())).thenReturn(Optional.empty());
    when(jwtService.getSubFromToken(CONTRACT_TOKEN)).thenReturn(Optional.of(USER_ID));

    CommentData c1 = new CommentData("comment-1", "Great article!", "article-1", "user-2", T1, T1);
    CommentData c2 =
        new CommentData("comment-2", "Thanks for sharing.", "article-1", "user-3", T2, T2);
    when(queryService.findByArticleId("article-1")).thenReturn(Arrays.asList(c1, c2));
    when(queryService.findByArticleIdWithCursor(eq("article-1"), any()))
        .thenReturn(Arrays.asList(c1, c2));
    when(queryService.findById("comment-1")).thenReturn(Optional.of(c1));
    when(queryService.findById("missing")).thenReturn(Optional.empty());

    CommentData created = new CommentData("comment-new", "Nice!", "article-1", USER_ID, T1, T1);
    when(commandService.create(eq("article-1"), eq("comment-new"), eq("Nice!"), eq(USER_ID), any()))
        .thenReturn(createResult(created, true));
    when(commandService.create(eq("article-1"), eq("comment-1"), anyString(), eq(USER_ID), any()))
        .thenReturn(
            createResult(
                new CommentData("comment-1", "Great article!", "article-1", USER_ID, T1, T1),
                false));

    ObjectMapper objectMapper =
        new ObjectMapper().registerModule(new JacksonCustomizations.RealWorldModules());
    RestAssuredMockMvc.mockMvc(
        MockMvcBuilders.standaloneSetup(new CommentInternalApi(queryService, commandService))
            .setControllerAdvice(new CustomizeExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .addFilters(new JwtTokenFilter(jwtService))
            .build());
  }

  private static CommentCommandService.CreateResult createResult(
      CommentData data, boolean created) {
    return new CommentCommandService.CreateResult(data, created);
  }
}
