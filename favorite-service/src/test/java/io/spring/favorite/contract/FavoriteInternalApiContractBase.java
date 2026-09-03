package io.spring.favorite.contract;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.favorite.api.FavoriteInternalApi;
import io.spring.favorite.api.exception.CustomizeExceptionHandler;
import io.spring.favorite.api.security.JwtTokenFilter;
import io.spring.favorite.application.FavoriteCommandService;
import io.spring.favorite.application.FavoriteQueryService;
import io.spring.favorite.application.data.ArticleFavoriteCount;
import io.spring.favorite.application.data.FavoriteData;
import io.spring.favorite.application.data.UserFavorites;
import io.spring.favorite.core.service.JwtService;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Base class for the provider-side tests Spring Cloud Contract generates from
 * src/test/resources/contracts. Standalone MockMvc around FavoriteInternalApi with mocked services;
 * the JwtTokenFilter runs with a stub JwtService that accepts the contract token.
 */
public abstract class FavoriteInternalApiContractBase {
  public static final String CONTRACT_TOKEN = "contract-token";
  public static final String USER_ID = "user-1";

  @BeforeEach
  public void setUp() {
    FavoriteQueryService queryService = mock(FavoriteQueryService.class);
    FavoriteCommandService commandService = mock(FavoriteCommandService.class);
    JwtService jwtService = mock(JwtService.class);

    when(jwtService.getSubFromToken(anyString())).thenReturn(Optional.empty());
    when(jwtService.getSubFromToken(CONTRACT_TOKEN)).thenReturn(Optional.of(USER_ID));

    when(queryService.articlesFavoriteCount(Arrays.asList("article-1", "article-2")))
        .thenReturn(
            Arrays.asList(
                new ArticleFavoriteCount("article-1", 2),
                new ArticleFavoriteCount("article-2", 0)));
    when(queryService.userFavorites(eq(USER_ID), anyList()))
        .thenReturn(new UserFavorites(USER_ID, Arrays.asList("article-1")));
    when(queryService.articleIdsFavoritedBy(USER_ID))
        .thenReturn(new UserFavorites(USER_ID, Arrays.asList("article-1", "article-2")));
    when(commandService.favorite("article-1", USER_ID))
        .thenReturn(new FavoriteData("article-1", USER_ID, true));

    RestAssuredMockMvc.mockMvc(
        MockMvcBuilders.standaloneSetup(new FavoriteInternalApi(queryService, commandService, 500))
            .setControllerAdvice(new CustomizeExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .addFilters(new JwtTokenFilter(jwtService))
            .build());
  }
}
