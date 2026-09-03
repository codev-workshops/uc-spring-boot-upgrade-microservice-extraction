package io.spring.api.security;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.spring.core.service.JwtService;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import io.spring.infrastructure.extraction.user.UserServiceException;
import java.util.Optional;
import javax.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

public class JwtTokenFilterTest {
  private final UserRepository userRepository = mock(UserRepository.class);
  private final JwtService jwtService = mock(JwtService.class);
  private final FilterChain chain = mock(FilterChain.class);
  private final JwtTokenFilter filter = new JwtTokenFilter();
  private final User user = new User("u1", "john@jacob.com", "john", "", "", "");

  @BeforeEach
  public void setUp() {
    ReflectionTestUtils.setField(filter, "userRepository", userRepository);
    ReflectionTestUtils.setField(filter, "jwtService", jwtService);
    when(jwtService.getSubFromToken(eq("good"))).thenReturn(Optional.of("u1"));
    when(jwtService.getSubFromToken(eq("bad"))).thenReturn(Optional.empty());
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  public void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  public void a_known_subject_is_authenticated_through_the_repository() throws Exception {
    when(userRepository.findById("u1")).thenReturn(Optional.of(user));
    MockHttpServletRequest request = request("Token good");

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    Assertions.assertNotNull(authentication);
    Assertions.assertSame(user, authentication.getPrincipal());
    verify(chain).doFilter(eq(request), org.mockito.ArgumentMatchers.any());
  }

  @Test
  public void unknown_subjects_and_invalid_tokens_stay_anonymous() throws Exception {
    when(userRepository.findById("u1")).thenReturn(Optional.empty());
    filter.doFilter(request("Token good"), new MockHttpServletResponse(), chain);
    Assertions.assertNull(SecurityContextHolder.getContext().getAuthentication());

    filter.doFilter(request("Token bad"), new MockHttpServletResponse(), chain);
    Assertions.assertNull(SecurityContextHolder.getContext().getAuthentication());

    filter.doFilter(request("Token"), new MockHttpServletResponse(), chain);
    Assertions.assertNull(SecurityContextHolder.getContext().getAuthentication());

    filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
    Assertions.assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  public void a_user_service_failure_degrades_to_anonymous_instead_of_a_500() throws Exception {
    when(userRepository.findById("u1")).thenThrow(new UserServiceException("boom"));
    MockHttpServletRequest request = request("Token good");

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    Assertions.assertNull(SecurityContextHolder.getContext().getAuthentication());
    verify(chain).doFilter(eq(request), org.mockito.ArgumentMatchers.any());
  }

  private MockHttpServletRequest request(String authorization) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/user");
    request.addHeader("Authorization", authorization);
    return request;
  }
}
