package io.spring.user.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.user.api.UserInternalApi;
import io.spring.user.api.exception.CustomizeExceptionHandler;
import io.spring.user.api.exception.InvalidRequestException;
import io.spring.user.api.security.JwtTokenFilter;
import io.spring.user.application.UserCommandService;
import io.spring.user.application.UserCommandService.CreateResult;
import io.spring.user.application.UserQueryService;
import io.spring.user.application.data.UserData;
import io.spring.user.core.service.JwtService;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Base class for the provider-side tests Spring Cloud Contract generates from
 * src/test/resources/contracts. Standalone MockMvc around UserInternalApi with mocked services; the
 * JwtTokenFilter runs with a stub JwtService that accepts the contract token as user-1.
 */
public abstract class UserInternalApiContractBase {
  public static final String CONTRACT_TOKEN = "contract-token";
  public static final String USER_ID = "user-1";

  @BeforeEach
  public void setUp() {
    UserQueryService queryService = mock(UserQueryService.class);
    UserCommandService commandService = mock(UserCommandService.class);
    JwtService jwtService = mock(JwtService.class);

    when(jwtService.getSubFromToken(anyString())).thenReturn(Optional.empty());
    when(jwtService.getSubFromToken(CONTRACT_TOKEN)).thenReturn(Optional.of(USER_ID));

    UserData u1 =
        new UserData(
            "user-1",
            "johndoe",
            "john@example.com",
            "Full-stack developer and tech enthusiast",
            "https://api.dicebear.com/7.x/avataaars/svg?seed=John");
    UserData u2 =
        new UserData(
            "user-2",
            "janedoe",
            "jane@example.com",
            "Software architect passionate about clean code",
            "https://api.dicebear.com/7.x/avataaars/svg?seed=Jane");
    when(queryService.findById("user-1")).thenReturn(Optional.of(u1));
    when(queryService.findById("missing")).thenReturn(Optional.empty());
    when(queryService.findByUsername("johndoe")).thenReturn(Optional.of(u1));
    when(queryService.findByUsername("missing")).thenReturn(Optional.empty());
    when(queryService.findByEmail("john@example.com")).thenReturn(Optional.of(u1));
    when(queryService.findByEmail("missing@example.com")).thenReturn(Optional.empty());
    when(queryService.findByIds(Arrays.asList("user-1", "user-2")))
        .thenReturn(Arrays.asList(u1, u2));
    when(queryService.findByIds(Collections.emptyList())).thenReturn(Collections.emptyList());
    when(queryService.followingAuthors("user-1", Arrays.asList("user-2", "user-3")))
        .thenReturn(Collections.singletonList("user-2"));
    when(queryService.followedUsers("user-1")).thenReturn(Collections.singletonList("user-2"));
    when(queryService.isFollowing("user-1", "user-2")).thenReturn(true);
    when(queryService.isFollowing("user-1", "user-3")).thenReturn(false);

    UserData created =
        new UserData("user-new", "newbie", "new@example.com", "", "https://example.com/i.png");
    when(commandService.create(argThat(u -> u != null && "user-new".equals(u.getId()))))
        .thenReturn(new CreateResult(created, true));
    when(commandService.create(argThat(u -> u != null && "user-1".equals(u.getId()))))
        .thenReturn(new CreateResult(u1, false));
    when(commandService.create(argThat(u -> u != null && "user-dup-name".equals(u.getId()))))
        .thenThrow(new InvalidRequestException("username", "duplicated username"));
    when(commandService.create(argThat(u -> u != null && "user-dup-mail".equals(u.getId()))))
        .thenThrow(new InvalidRequestException("email", "duplicated email"));

    UserData updated =
        new UserData(
            "user-1",
            "johndoe",
            "john@example.com",
            "Updated bio",
            "https://api.dicebear.com/7.x/avataaars/svg?seed=John");
    when(commandService.update(argThat(u -> u != null && "Updated bio".equals(u.getBio()))))
        .thenReturn(updated);
    when(commandService.update(argThat(u -> u != null && "jane@example.com".equals(u.getEmail()))))
        .thenThrow(new InvalidRequestException("email", "duplicated email"));

    when(commandService.verifyCredentials(eq("user-1"), eq("password123"))).thenReturn(true);
    when(commandService.verifyCredentials(eq("user-1"), eq("wrong"))).thenReturn(false);
    when(commandService.verifyCredentials(eq("missing"), any())).thenReturn(false);

    RestAssuredMockMvc.mockMvc(
        MockMvcBuilders.standaloneSetup(new UserInternalApi(queryService, commandService))
            .setControllerAdvice(new CustomizeExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .addFilters(new JwtTokenFilter(jwtService))
            .build());
  }
}
