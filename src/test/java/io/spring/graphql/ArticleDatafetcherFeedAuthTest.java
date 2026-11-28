package io.spring.graphql;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import io.spring.application.ArticleQueryService;
import io.spring.application.CursorPager;
import io.spring.application.CursorPager.Direction;
import io.spring.application.data.ArticleData;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import io.spring.graphql.exception.AuthenticationException;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

public class ArticleDatafetcherFeedAuthTest {

  private ArticleQueryService articleQueryService;
  private ArticleDatafetcher articleDatafetcher;

  @BeforeEach
  public void setUp() {
    articleQueryService = mock(ArticleQueryService.class);
    articleDatafetcher = new ArticleDatafetcher(articleQueryService, mock(UserRepository.class));
  }

  @AfterEach
  public void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  public void should_throw_authentication_exception_for_anonymous_token() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

    assertThrows(
        AuthenticationException.class,
        () -> articleDatafetcher.getFeed(10, null, null, null, null));
    verify(articleQueryService, never()).findUserFeedWithCursor(any(), any());
  }

  @Test
  public void should_query_feed_for_signed_in_user() {
    User user = new User("john@jacob.com", "johnjacob", "123", "", "");
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, Collections.emptyList()));
    when(articleQueryService.findUserFeedWithCursor(eq(user), any()))
        .thenReturn(new CursorPager<ArticleData>(new ArrayList<>(), Direction.NEXT, false));

    articleDatafetcher.getFeed(10, null, null, null, mock(DgsDataFetchingEnvironment.class));

    verify(articleQueryService).findUserFeedWithCursor(eq(user), any());
  }
}
