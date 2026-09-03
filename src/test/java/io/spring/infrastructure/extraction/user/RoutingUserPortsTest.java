package io.spring.infrastructure.extraction.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.spring.application.data.UserData;
import io.spring.application.user.UserCommandPort;
import io.spring.core.user.FollowRelation;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import io.spring.infrastructure.extraction.ExtractionProperties.Fallback;
import io.spring.infrastructure.extraction.ExtractionProperties.ReadMode;
import io.spring.infrastructure.extraction.ExtractionProperties.WriteMode;
import io.spring.infrastructure.extraction.LoggingShadowComparator;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Read routing of {@link RoutingUserQueryPort} and {@link RoutingFollowPort} per mode. */
public class RoutingUserPortsTest {
  private final LocalUserQueryAdapter monolith = mock(LocalUserQueryAdapter.class);
  private final RemoteUserQueryAdapter remote = mock(RemoteUserQueryAdapter.class);
  private final LocalFollowAdapter localFollows = mock(LocalFollowAdapter.class);
  private final RemoteFollowAdapter remoteFollows = mock(RemoteFollowAdapter.class);
  private final UserCommandPort commands = mock(UserCommandPort.class);
  private final ExtractionProperties properties = new ExtractionProperties();
  private final LoggingShadowComparator shadow = new LoggingShadowComparator(Runnable::run);
  private final ReadAfterWriteMarker marker = new ReadAfterWriteMarker();
  private final RoutingUserQueryPort port =
      new RoutingUserQueryPort(monolith, remote, properties, shadow, marker);
  private final RoutingFollowPort follows =
      new RoutingFollowPort(localFollows, remoteFollows, commands, properties, shadow, marker);

  private final UserData local = new UserData("u1", "john@jacob.com", "john", "local", "");
  private final UserData remoteRow = new UserData("u1", "john@jacob.com", "john", "remote", "");
  private final List<String> ids = Arrays.asList("u1", "u2");

  @BeforeEach
  public void setUp() {
    when(monolith.findById("u1")).thenReturn(Optional.of(local));
    when(monolith.findByUsername("john")).thenReturn(Optional.of(local));
    when(monolith.findByEmail("john@jacob.com")).thenReturn(Optional.of(local));
    when(monolith.findByIds(ids)).thenReturn(Collections.singletonList(local));
    when(remote.findById("u1")).thenReturn(Optional.of(remoteRow));
    when(remote.findByUsername("john")).thenReturn(Optional.of(remoteRow));
    when(remote.findByEmail("john@jacob.com")).thenReturn(Optional.of(remoteRow));
    when(remote.findByIds(ids)).thenReturn(Collections.singletonList(remoteRow));

    when(localFollows.isFollowing("u1", "u2")).thenReturn(false);
    when(localFollows.followingAuthors("u1", ids)).thenReturn(new HashSet<>());
    when(localFollows.followedUsers("u1")).thenReturn(Collections.emptyList());
    when(remoteFollows.isFollowing("u1", "u2")).thenReturn(true);
    when(remoteFollows.followingAuthors("u1", ids))
        .thenReturn(new HashSet<>(Collections.singletonList("u2")));
    when(remoteFollows.followedUsers("u1")).thenReturn(Collections.singletonList("u2"));
  }

  @AfterEach
  public void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  public void defaults_route_everything_to_the_monolith_and_do_not_own_reads() {
    Assertions.assertSame(local, port.findById("u1").get());
    Assertions.assertSame(local, port.findByUsername("john").get());
    Assertions.assertSame(local, port.findByEmail("john@jacob.com").get());
    Assertions.assertEquals(1, port.findByIds(ids).size());
    Assertions.assertFalse(follows.isFollowing("u1", "u2"));
    Assertions.assertTrue(follows.followingAuthors("u1", ids).isEmpty());
    Assertions.assertTrue(follows.followedUsers("u1").isEmpty());
    Assertions.assertFalse(port.ownsUserReads());
    Assertions.assertFalse(follows.ownsFollowReads());
    verifyNoInteractions(remote, remoteFollows);
  }

  @Test
  public void modes_are_ignored_while_the_flag_is_off() {
    properties.getUser().setRead(ReadMode.EXTRACTED);
    Assertions.assertSame(local, port.findByUsername("john").get());
    Assertions.assertFalse(follows.isFollowing("u1", "u2"));
    Assertions.assertFalse(port.ownsUserReads());
    verifyNoInteractions(remote, remoteFollows);
  }

  @Test
  public void extracted_reads_come_from_the_service_and_the_ports_own_reads() {
    extracted();
    Assertions.assertSame(remoteRow, port.findById("u1").get());
    Assertions.assertSame(remoteRow, port.findByUsername("john").get());
    Assertions.assertSame(remoteRow, port.findByEmail("john@jacob.com").get());
    Assertions.assertSame(remoteRow, port.findByIds(ids).get(0));
    Assertions.assertTrue(follows.isFollowing("u1", "u2"));
    Assertions.assertEquals(Collections.singleton("u2"), follows.followingAuthors("u1", ids));
    Assertions.assertEquals(Collections.singletonList("u2"), follows.followedUsers("u1"));
    Assertions.assertTrue(port.ownsUserReads());
    Assertions.assertTrue(follows.ownsFollowReads());
    verifyNoInteractions(monolith, localFollows);
  }

  @Test
  public void shadow_returns_the_monolith_value_and_compares_the_remote_one() {
    properties.getUser().setEnabled(true);
    properties.getUser().setRead(ReadMode.SHADOW);
    Assertions.assertSame(local, port.findById("u1").get());
    Assertions.assertFalse(follows.isFollowing("u1", "u2"));
    Assertions.assertTrue(port.ownsUserReads());
    Assertions.assertEquals(2, shadow.mismatchCount());
    verify(remote).findById("u1");
    verify(remoteFollows).isFollowing("u1", "u2");
  }

  @Test
  public void fallback_monolith_uses_local_data_when_the_service_fails() {
    extracted();
    failRemote();
    Assertions.assertSame(local, port.findById("u1").get());
    Assertions.assertSame(local, port.findByEmail("john@jacob.com").get());
    Assertions.assertFalse(follows.isFollowing("u1", "u2"));
    Assertions.assertTrue(follows.followedUsers("u1").isEmpty());
  }

  @Test
  public void fallback_empty_returns_empty_results() {
    extracted();
    properties.getUser().setFallback(Fallback.EMPTY);
    failRemote();
    Assertions.assertEquals(Optional.empty(), port.findById("u1"));
    Assertions.assertEquals(Optional.empty(), port.findByUsername("john"));
    Assertions.assertTrue(port.findByIds(ids).isEmpty());
    Assertions.assertFalse(follows.isFollowing("u1", "u2"));
    Assertions.assertTrue(follows.followingAuthors("u1", ids).isEmpty());
    Assertions.assertTrue(follows.followedUsers("u1").isEmpty());
    verifyNoInteractions(monolith, localFollows);
  }

  @Test
  public void fallback_fail_rethrows_the_domain_exception() {
    extracted();
    properties.getUser().setFallback(Fallback.FAIL);
    failRemote();
    Assertions.assertThrows(UserServiceException.class, () -> port.findById("u1"));
    Assertions.assertThrows(UserServiceException.class, () -> follows.followedUsers("u1"));
    verifyNoInteractions(monolith, localFollows);
  }

  @Test
  public void reads_after_a_write_in_the_same_request_stay_local_while_dual_writing() {
    extracted();
    properties.getUser().setWrite(WriteMode.DUAL_WRITE);
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    Assertions.assertSame(remoteRow, port.findById("u1").get());
    marker.markWritten("user");
    Assertions.assertSame(local, port.findById("u1").get());
    Assertions.assertFalse(follows.isFollowing("u1", "u2"));

    properties.getUser().setWrite(WriteMode.EXTRACTED);
    Assertions.assertSame(remoteRow, port.findById("u1").get());
  }

  @Test
  public void follow_writes_go_through_the_command_port() {
    follows.follow("u1", "u2");
    follows.unfollow("u1", "u2");
    verify(commands).follow(new FollowRelation("u1", "u2"));
    verify(commands).unfollow(new FollowRelation("u1", "u2"));
    verifyNoInteractions(localFollows, remoteFollows);
  }

  private void extracted() {
    DomainRoute user = properties.getUser();
    user.setEnabled(true);
    user.setRead(ReadMode.EXTRACTED);
  }

  private void failRemote() {
    UserServiceException boom = new UserServiceException("down");
    when(remote.findById(anyString())).thenThrow(boom);
    when(remote.findByUsername(anyString())).thenThrow(boom);
    when(remote.findByEmail(anyString())).thenThrow(boom);
    when(remote.findByIds(anyList())).thenThrow(boom);
    when(remoteFollows.isFollowing(anyString(), anyString())).thenThrow(boom);
    when(remoteFollows.followingAuthors(anyString(), any())).thenThrow(boom);
    when(remoteFollows.followedUsers(anyString())).thenThrow(boom);
  }
}
