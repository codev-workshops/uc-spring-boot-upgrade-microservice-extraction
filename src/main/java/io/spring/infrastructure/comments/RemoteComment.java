package io.spring.infrastructure.comments;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;

/** A comment as returned by comments-service. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RemoteComment {
  private String id;
  private String body;
  private String articleId;
  private String userId;
  private String createdAt;
  private String updatedAt;

  public DateTime createdAtTime() {
    return parse(createdAt);
  }

  public DateTime updatedAtTime() {
    return parse(updatedAt);
  }

  private static DateTime parse(String value) {
    return value == null
        ? null
        : ISODateTimeFormat.dateTimeParser().withOffsetParsed().parseDateTime(value);
  }
}
