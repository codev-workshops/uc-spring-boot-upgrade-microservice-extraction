package io.spring.comments.application.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.joda.time.DateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentData {
  private String id;
  private String body;
  private String articleId;
  private String userId;
  private DateTime createdAt;
  private DateTime updatedAt;
}
