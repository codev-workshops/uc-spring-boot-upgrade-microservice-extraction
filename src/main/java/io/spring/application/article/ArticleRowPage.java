package io.spring.application.article;

import io.spring.application.data.ArticleRow;
import java.util.List;
import lombok.Value;

/** One offset page of article rows plus the total number of matching articles. */
@Value
public class ArticleRowPage {
  List<ArticleRow> articles;
  int count;
}
