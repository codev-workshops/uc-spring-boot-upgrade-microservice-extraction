package io.spring.article.application.data;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** {"articleIds":[...],"count":N} — one page of ids plus the total matching count. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ArticleIdsData {
  private List<String> articleIds;
  private int count;
}
