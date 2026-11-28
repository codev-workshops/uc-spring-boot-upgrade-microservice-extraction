package io.spring.application;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.spring.application.data.UserData;
import io.spring.application.readservice.UserReadService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class UserQueryServiceTest {

  @Test
  public void should_find_user_by_id_or_return_empty() {
    UserReadService userReadService = mock(UserReadService.class);
    UserData userData = new UserData("id", "john@jacob.com", "johnjacob", "", "");
    when(userReadService.findById(eq("id"))).thenReturn(userData);
    UserQueryService userQueryService = new UserQueryService(userReadService);

    Assertions.assertEquals(userData, userQueryService.findById("id").get());
    Assertions.assertFalse(userQueryService.findById("missing").isPresent());
  }
}
