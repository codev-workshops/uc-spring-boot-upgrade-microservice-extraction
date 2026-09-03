package io.spring.application.article;

import java.util.List;
import lombok.Value;

/** One offset page of article ids plus the total number of matching articles. */
@Value
public class ArticleIdPage {
  List<String> articleIds;
  int count;
}
