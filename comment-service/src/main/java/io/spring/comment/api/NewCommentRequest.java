package io.spring.comment.api;

import javax.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.joda.time.DateTime;

/** id and createdAt are supplied by the caller so dual-write produces identical rows. */
@Getter
@Setter
@NoArgsConstructor
public class NewCommentRequest {
  private String id;

  @NotBlank(message = "can't be empty")
  private String body;

  @NotBlank(message = "can't be empty")
  private String userId;

  private DateTime createdAt;
}
