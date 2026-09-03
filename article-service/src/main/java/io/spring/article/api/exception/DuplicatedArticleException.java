package io.spring.article.api.exception;

/**
 * Rendered as 422 {"errors":{"title":["article name exists"]}} — the same envelope the monolith's
 * DuplicatedArticleValidator produces.
 */
@SuppressWarnings("serial")
public class DuplicatedArticleException extends RuntimeException {
  public DuplicatedArticleException() {
    super("article name exists");
  }
}
