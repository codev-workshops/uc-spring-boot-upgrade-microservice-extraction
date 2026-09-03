package io.spring.article.application.data;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** {"articles":[row...],"count":N} — one feed page plus the total feed size. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ArticleListData {
  private List<ArticleData> articles;
  private int count;
}
