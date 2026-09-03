package io.spring.application.data;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The tag names of one article, in {@code article_tags} insertion order. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticleTagList {
  private String articleId;
  private List<String> tagList;
}
