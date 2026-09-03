package io.spring.comment.infrastructure.repository;

import io.spring.comment.core.comment.Comment;
import io.spring.comment.core.comment.CommentRepository;
import io.spring.comment.infrastructure.mybatis.mapper.CommentMapper;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisCommentRepository implements CommentRepository {
  private final CommentMapper mapper;

  public MyBatisCommentRepository(CommentMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void save(Comment comment) {
    mapper.insert(comment);
  }

  @Override
  public Optional<Comment> findById(String articleId, String id) {
    return Optional.ofNullable(mapper.findById(articleId, id));
  }

  @Override
  public void remove(Comment comment) {
    mapper.delete(comment.getId());
  }
}
