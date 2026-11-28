package io.spring.graphql;

import io.spring.core.user.User;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

public class SecurityUtilTest extends GraphQLTestBase {

  @Test
  public void should_return_current_user_when_authenticated() {
    User user = new User("john@jacob.com", "johnjacob", "123", "", "");
    login(user);

    Optional<User> current = SecurityUtil.getCurrentUser();

    Assertions.assertTrue(current.isPresent());
    Assertions.assertEquals(user, current.get());
  }

  @Test
  public void should_return_empty_for_anonymous_user() {
    anonymous();

    Assertions.assertFalse(SecurityUtil.getCurrentUser().isPresent());
  }

  @Test
  public void should_return_empty_when_principal_is_null() {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(null, null));

    Assertions.assertFalse(SecurityUtil.getCurrentUser().isPresent());
  }
}
