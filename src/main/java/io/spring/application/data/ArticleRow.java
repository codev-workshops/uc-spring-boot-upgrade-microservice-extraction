package io.spring.application.data;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.joda.time.DateTime;

/**
 * An {@code articles} row plus its {@code tagList}, as owned by the Article domain: no author
 * profile, no favorite information. {@code ArticleQueryService} composes {@link ArticleData} from
 * it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticleRow {
  private String id;
  private String slug;
  private String title;
  private String description;
  private String body;
  private String userId;
  private DateTime createdAt;
  private DateTime updatedAt;
  private List<String> tagList;
}
