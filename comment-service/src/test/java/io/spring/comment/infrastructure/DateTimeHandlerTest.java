package io.spring.comment.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.spring.comment.infrastructure.mybatis.DateTimeHandler;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Calendar;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.jupiter.api.Test;

public class DateTimeHandlerTest {
  private final DateTimeHandler handler = new DateTimeHandler();
  private final DateTime t = new DateTime(1706696130123L, DateTimeZone.UTC);

  @Test
  public void writes_millis_as_utc_timestamp_and_null_as_null() throws SQLException {
    PreparedStatement ps = mock(PreparedStatement.class);
    handler.setParameter(ps, 1, t, null);
    verify(ps).setTimestamp(eq(1), eq(new Timestamp(t.getMillis())), any(Calendar.class));
    handler.setParameter(ps, 2, null, null);
    verify(ps).setTimestamp(eq(2), eq(null), any(Calendar.class));
  }

  @Test
  public void reads_by_name_index_and_callable_preserving_millis() throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getTimestamp(eq("c"), any(Calendar.class))).thenReturn(new Timestamp(t.getMillis()));
    when(rs.getTimestamp(eq(1), any(Calendar.class))).thenReturn(new Timestamp(t.getMillis()));
    assertEquals(t.getMillis(), handler.getResult(rs, "c").getMillis());
    assertEquals(t.getMillis(), handler.getResult(rs, 1).getMillis());
    assertNull(handler.getResult(rs, "missing"));
    assertNull(handler.getResult(rs, 2));

    CallableStatement cs = mock(CallableStatement.class);
    when(cs.getTimestamp(eq(1), any(Calendar.class))).thenReturn(new Timestamp(t.getMillis()));
    assertEquals(t.getMillis(), handler.getResult(cs, 1).getMillis());
    assertNull(handler.getResult(cs, 2));
  }
}
