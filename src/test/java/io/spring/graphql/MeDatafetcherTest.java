package io.spring.graphql;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetchingEnvironment;
import io.spring.api.exception.ResourceNotFoundException;
import io.spring.application.UserQueryService;
import io.spring.application.data.UserData;
import io.spring.core.service.JwtService;
import io.spring.core.user.User;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MeDatafetcherTest extends GraphQLTestBase {
  private UserQueryService userQueryService;
  private JwtService jwtService;
  private MeDatafetcher meDatafetcher;
  private User user;

  @BeforeEach
  public void setUp() {
    userQueryService = mock(UserQueryService.class);
    jwtService = mock(JwtService.class);
    meDatafetcher = new MeDatafetcher(userQueryService, jwtService);
    user = new User("john@jacob.com", "johnjacob", "123", "", "");
  }

  @Test
  public void should_return_null_for_anonymous_user() {
    anonymous();

    Assertions.assertNull(meDatafetcher.getMe("Token abc", mock(DataFetchingEnvironment.class)));
    verifyNoInteractions(userQueryService);
  }

  @Test
  public void should_return_current_user_with_token_from_header() {
    login(user);
    when(userQueryService.findById(eq(user.getId())))
        .thenReturn(
            Optional.of(new UserData(user.getId(), user.getEmail(), user.getUsername(), "", "")));

    DataFetcherResult<io.spring.graphql.types.User> result =
        meDatafetcher.getMe("Token abc", mock(DataFetchingEnvironment.class));

    Assertions.assertEquals("john@jacob.com", result.getData().getEmail());
    Assertions.assertEquals("johnjacob", result.getData().getUsername());
    Assertions.assertEquals("abc", result.getData().getToken());
    Assertions.assertEquals(user, result.getLocalContext());
  }

  @Test
  public void should_throw_not_found_if_current_user_data_missing() {
    login(user);
    when(userQueryService.findById(eq(user.getId()))).thenReturn(Optional.empty());

    Assertions.assertThrows(
        ResourceNotFoundException.class,
        () -> meDatafetcher.getMe("Token abc", mock(DataFetchingEnvironment.class)));
  }

  @Test
  public void should_build_user_payload_user_with_new_token() {
    DataFetchingEnvironment dfe = mock(DataFetchingEnvironment.class);
    when(dfe.getLocalContext()).thenReturn(user);
    when(jwtService.toToken(eq(user))).thenReturn("jwt-token");

    DataFetcherResult<io.spring.graphql.types.User> result = meDatafetcher.getUserPayloadUser(dfe);

    Assertions.assertEquals("john@jacob.com", result.getData().getEmail());
    Assertions.assertEquals("johnjacob", result.getData().getUsername());
    Assertions.assertEquals("jwt-token", result.getData().getToken());
    Assertions.assertEquals(user, result.getLocalContext());
  }
}
