package io.spring.application.comment;

import io.spring.application.CommentQueryService;
import io.spring.application.CursorPageParameter;
import io.spring.application.CursorPager;
import io.spring.application.CursorPager.Direction;
import io.spring.application.data.CommentData;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.comment.Comment;
import io.spring.core.comment.CommentRepository;
import io.spring.core.user.FollowRelation;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import io.spring.infrastructure.DbTestBase;
import io.spring.infrastructure.repository.MyBatisArticleRepository;
import io.spring.infrastructure.repository.MyBatisCommentRepository;
import io.spring.infrastructure.repository.MyBatisUserRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import({
  MyBatisCommentRepository.class,
  MyBatisUserRepository.class,
  CommentQueryService.class,
  MyBatisArticleRepository.class
})
public class CommentQueryServiceTest extends DbTestBase {
  @Autowired private CommentRepository commentRepository;

  @Autowired private UserRepository userRepository;

  @Autowired private CommentQueryService commentQueryService;

  @Autowired private ArticleRepository articleRepository;

  @Autowired private SqlSession sqlSession;

  private User user;

  @BeforeEach
  public void setUp() {
    user = new User("aisensiy@test.com", "aisensiy", "123", "", "");
    userRepository.save(user);
  }

  @Test
  public void should_read_comment_success() {
    Comment comment = new Comment("content", user.getId(), "123");
    commentRepository.save(comment);

    Optional<CommentData> optional = commentQueryService.findById(comment.getId(), user);
    Assertions.assertTrue(optional.isPresent());
    CommentData commentData = optional.get();
    Assertions.assertEquals(commentData.getProfileData().getUsername(), user.getUsername());
    Assertions.assertEquals("content", commentData.getBody());
    Assertions.assertFalse(commentData.getProfileData().isFollowing());
  }

  @Test
  public void should_return_empty_for_missing_comment() {
    Assertions.assertFalse(commentQueryService.findById("missing", user).isPresent());
  }

  @Test
  public void should_show_following_for_comment_of_followed_author() {
    User author = new User("author@test.com", "author", "123", "", "");
    userRepository.save(author);
    userRepository.saveRelation(new FollowRelation(user.getId(), author.getId()));
    Comment comment = new Comment("content", author.getId(), "123");
    commentRepository.save(comment);

    Assertions.assertTrue(
        commentQueryService.findById(comment.getId(), user).get().getProfileData().isFollowing());
    Assertions.assertFalse(
        commentQueryService.findById(comment.getId(), author).get().getProfileData().isFollowing());
  }

  @Test
  public void should_read_comments_with_cursor() throws InterruptedException {
    Article article = new Article("title", "desc", "body", Arrays.asList("java"), user.getId());
    articleRepository.save(article);
    User user2 = new User("user2@email.com", "user2", "123", "", "");
    userRepository.save(user2);
    userRepository.saveRelation(new FollowRelation(user.getId(), user2.getId()));
    commentRepository.save(new Comment("content1", user.getId(), article.getId()));
    Thread.sleep(20);
    commentRepository.save(new Comment("content2", user2.getId(), article.getId()));

    CursorPager<CommentData> firstPage =
        commentQueryService.findByArticleIdWithCursor(
            article.getId(), null, new CursorPageParameter<>(null, 1, Direction.NEXT));
    Assertions.assertEquals(1, firstPage.getData().size());
    Assertions.assertTrue(firstPage.hasNext());
    Assertions.assertEquals("content2", firstPage.getData().get(0).getBody());
    Assertions.assertFalse(firstPage.getData().get(0).getProfileData().isFollowing());

    sqlSession.clearCache();
    CursorPager<CommentData> all =
        commentQueryService.findByArticleIdWithCursor(
            article.getId(), user, new CursorPageParameter<>(null, 20, Direction.NEXT));
    Assertions.assertEquals(2, all.getData().size());
    Assertions.assertFalse(all.hasNext());
    Assertions.assertEquals("content2", all.getData().get(0).getBody());
    Assertions.assertTrue(all.getData().get(0).getProfileData().isFollowing());
    Assertions.assertFalse(all.getData().get(1).getProfileData().isFollowing());

    sqlSession.clearCache();
    CursorPager<CommentData> prevPage =
        commentQueryService.findByArticleIdWithCursor(
            article.getId(), user, new CursorPageParameter<>(null, 1, Direction.PREV));
    Assertions.assertEquals(1, prevPage.getData().size());
    Assertions.assertTrue(prevPage.hasPrevious());
    Assertions.assertEquals("content1", prevPage.getData().get(0).getBody());

    sqlSession.clearCache();
    CursorPager<CommentData> prevAll =
        commentQueryService.findByArticleIdWithCursor(
            article.getId(), user, new CursorPageParameter<>(null, 20, Direction.PREV));
    Assertions.assertEquals("content2", prevAll.getData().get(0).getBody());
    Assertions.assertEquals("content1", prevAll.getData().get(1).getBody());

    sqlSession.clearCache();
    CursorPager<CommentData> empty =
        commentQueryService.findByArticleIdWithCursor(
            "missing", user, new CursorPageParameter<>(null, 20, Direction.NEXT));
    Assertions.assertTrue(empty.getData().isEmpty());
    Assertions.assertFalse(empty.hasNext());
  }

  @Test
  public void should_read_comments_of_article() {
    Article article = new Article("title", "desc", "body", Arrays.asList("java"), user.getId());
    articleRepository.save(article);

    User user2 = new User("user2@email.com", "user2", "123", "", "");
    userRepository.save(user2);
    userRepository.saveRelation(new FollowRelation(user.getId(), user2.getId()));

    Comment comment1 = new Comment("content1", user.getId(), article.getId());
    commentRepository.save(comment1);
    Comment comment2 = new Comment("content2", user2.getId(), article.getId());
    commentRepository.save(comment2);

    List<CommentData> anonymous = commentQueryService.findByArticleId(article.getId(), null);
    Assertions.assertEquals(2, anonymous.size());
    anonymous.forEach(data -> Assertions.assertFalse(data.getProfileData().isFollowing()));

    List<CommentData> comments = commentQueryService.findByArticleId(article.getId(), user);
    Assertions.assertEquals(comments.size(), 2);
    for (CommentData data : comments) {
      Assertions.assertEquals(
          data.getProfileData().getId().equals(user2.getId()), data.getProfileData().isFollowing());
    }
  }
}
