package io.spring.infrastructure.extraction.user;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.spring.application.user.CredentialsPort;
import io.spring.application.user.LoginService;
import io.spring.application.user.UserCommandPort;
import io.spring.application.user.dto.UserRowDto;
import io.spring.core.user.FollowRelation;
import io.spring.core.user.User;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.Fallback;
import io.spring.infrastructure.extraction.ExtractionProperties.ReadMode;
import io.spring.infrastructure.extraction.ExtractionProperties.WriteMode;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import io.spring.infrastructure.repository.MyBatisUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@link RoutedUserRepository} is what {@code JwtTokenFilter}, the validators, {@code UserService}
 * and the profile follow endpoints see; this pins its routing, the 30 s id cache and the login path
 * ({@link LoginService} + {@link RemoteCredentialsAdapter}).
 */
public class RoutedUserRepositoryTest {
  private final MyBatisUserRepository monolith = mock(MyBatisUserRepository.class);
  private final UserCommandPort commands = mock(UserCommandPort.class);
  private final UserServiceClient client = mock(UserServiceClient.class);
  private final ExtractionProperties properties = new ExtractionProperties();
  private final ReadAfterWriteMarker marker = new ReadAfterWriteMarker();
  private final UserLookupCache cache = new UserLookupCache();
  private final RoutedUserRepository repository =
      new RoutedUserRepository(monolith, commands, client, properties, marker, cache);
  private final PasswordEncoder encoder = new BCryptPasswordEncoder();
  private final CredentialsPort credentials =
      new RemoteCredentialsAdapter(client, monolith, encoder, properties);
  private final LoginService login = new LoginService(repository, encoder, credentials);

  private final User localUser =
      new User("u1", "john@jacob.com", "john", encoder.encode("secret"), "local", "");
  private final UserRowDto remoteRow = new UserRowDto("u1", "john", "john@jacob.com", "remote", "");

  @BeforeEach
  public void setUp() {
    when(monolith.findById("u1")).thenReturn(Optional.of(localUser));
    when(monolith.findByUsername("john")).thenReturn(Optional.of(localUser));
    when(monolith.findByEmail("john@jacob.com")).thenReturn(Optional.of(localUser));
    when(monolith.findRelation("u1", "u2")).thenReturn(Optional.empty());
    when(client.findById("u1")).thenReturn(Optional.of(remoteRow));
    when(client.findByUsername("john")).thenReturn(Optional.of(remoteRow));
    when(client.findByEmail("john@jacob.com")).thenReturn(Optional.of(remoteRow));
    when(client.isFollowing("u1", "u2")).thenReturn(true);
  }

  @AfterEach
  public void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  public void flag_off_delegates_reads_to_mybatis_and_writes_to_the_command_port() {
    Assertions.assertSame(localUser, repository.findById("u1").get());
    Assertions.assertSame(localUser, repository.findByUsername("john").get());
    Assertions.assertSame(localUser, repository.findByEmail("john@jacob.com").get());
    Assertions.assertEquals(Optional.empty(), repository.findRelation("u1", "u2"));
    repository.saveRelation(new FollowRelation("u1", "u2"));
    repository.removeRelation(new FollowRelation("u1", "u2"));
    verify(commands).follow(new FollowRelation("u1", "u2"));
    verify(commands).unfollow(new FollowRelation("u1", "u2"));
    verifyNoInteractions(client);
  }

  @Test
  public void save_creates_unknown_users_and_updates_known_ones() {
    User fresh = new User("new@x.com", "new", "hash", "", "");
    when(monolith.findById(fresh.getId())).thenReturn(Optional.empty());
    repository.save(fresh);
    repository.save(localUser);
    verify(commands).create(fresh);
    verify(commands).update(localUser);
  }

  @Test
  public void extracted_reads_come_from_the_service_as_hashless_users() {
    extracted();
    User remote = repository.findByUsername("john").get();
    Assertions.assertEquals("remote", remote.getBio());
    Assertions.assertEquals("", remote.getPassword());
    Assertions.assertEquals("remote", repository.findByEmail("john@jacob.com").get().getBio());
    Assertions.assertEquals(
        new FollowRelation("u1", "u2"), repository.findRelation("u1", "u2").get());
    verify(monolith, never()).findByUsername(anyString());
  }

  @Test
  public void find_by_id_is_cached_for_the_jwt_filter_hot_path() {
    extracted();
    for (int i = 0; i < 5; i++) {
      Assertions.assertEquals("remote", repository.findById("u1").get().getBio());
    }
    verify(client, times(1)).findById("u1");

    repository.save(localUser);
    verify(client, times(2)).findById("u1");
    repository.findById("u1");
    verify(client, times(3)).findById("u1");
  }

  @Test
  public void find_by_id_is_not_cached_while_the_flag_is_off() {
    repository.findById("u1");
    repository.findById("u1");
    verify(monolith, times(2)).findById("u1");
  }

  @Test
  public void remote_failure_falls_back_to_the_local_table_by_default() {
    extracted();
    when(client.findById("u1")).thenThrow(new UserServiceException("down"));
    when(client.findByEmail("john@jacob.com")).thenThrow(new UserServiceException("down"));
    Assertions.assertSame(localUser, repository.findById("u1").get());
    Assertions.assertSame(localUser, repository.findByEmail("john@jacob.com").get());
  }

  @Test
  public void remote_failure_is_anonymous_with_fallback_empty_and_raises_with_fallback_fail() {
    extracted();
    when(client.findById("u1")).thenThrow(new UserServiceException("down"));
    properties.getUser().setFallback(Fallback.EMPTY);
    Assertions.assertEquals(Optional.empty(), repository.findById("u1"));
    properties.getUser().setFallback(Fallback.FAIL);
    Assertions.assertThrows(UserServiceException.class, () -> repository.findById("u1"));
    verify(monolith, never()).findById("u1");
  }

  @Test
  public void reads_after_a_write_in_the_request_stay_local_while_the_monolith_is_authoritative() {
    extracted();
    properties.getUser().setWrite(WriteMode.DUAL_WRITE);
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    marker.markWritten("user");
    Assertions.assertSame(localUser, repository.findByUsername("john").get());

    properties.getUser().setWrite(WriteMode.EXTRACTED);
    Assertions.assertEquals("remote", repository.findByUsername("john").get().getBio());
  }

  @Test
  public void login_checks_bcrypt_locally_while_the_flag_is_off() {
    Assertions.assertSame(localUser, login.login("john@jacob.com", "secret").get());
    Assertions.assertEquals(Optional.empty(), login.login("john@jacob.com", "wrong"));
    Assertions.assertEquals(Optional.empty(), login.login("nobody@jacob.com", "secret"));
    verifyNoInteractions(client);
  }

  @Test
  public void login_in_extracted_mode_verifies_credentials_through_the_service() {
    extracted();
    when(client.verifyCredentials("u1", "secret")).thenReturn(true);
    when(client.verifyCredentials("u1", "wrong")).thenReturn(false);
    Assertions.assertEquals("u1", login.login("john@jacob.com", "secret").get().getId());
    Assertions.assertEquals(Optional.empty(), login.login("john@jacob.com", "wrong"));
    verify(monolith, never()).findByEmail(anyString());
  }

  @Test
  public void login_falls_back_to_the_local_hash_when_verify_is_unavailable() {
    extracted();
    when(client.verifyCredentials(anyString(), anyString()))
        .thenThrow(new UserServiceException("down"));
    Assertions.assertTrue(login.login("john@jacob.com", "secret").isPresent());
    Assertions.assertFalse(login.login("john@jacob.com", "wrong").isPresent());

    properties.getUser().setFallback(Fallback.EMPTY);
    Assertions.assertFalse(login.login("john@jacob.com", "secret").isPresent());

    properties.getUser().setFallback(Fallback.FAIL);
    Assertions.assertThrows(
        UserServiceException.class, () -> login.login("john@jacob.com", "secret"));
  }

  private void extracted() {
    properties.getUser().setEnabled(true);
    properties.getUser().setRead(ReadMode.EXTRACTED);
  }
}
