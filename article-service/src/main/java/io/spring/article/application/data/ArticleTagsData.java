package io.spring.article.application.data;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** {"articleId":"...","tagList":[...]} as rendered by the internal API. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ArticleTagsData {
  private String articleId;
  private List<String> tagList;
}
