package io.spring.application.comment;

import io.spring.application.CommentQueryService;
import io.spring.application.CursorPageParameter;
import io.spring.application.CursorPager;
import io.spring.application.CursorPager.Direction;
import io.spring.application.DateTimeCursor;
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
import org.joda.time.DateTime;
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

    List<CommentData> comments = commentQueryService.findByArticleId(article.getId(), user);
    Assertions.assertEquals(comments.size(), 2);
    Assertions.assertFalse(commentOf(comments, comment1).getProfileData().isFollowing());
    Assertions.assertTrue(commentOf(comments, comment2).getProfileData().isFollowing());
  }

  @Test
  public void should_return_empty_when_comment_not_found() {
    Optional<CommentData> optional = commentQueryService.findById("not-exist", user);
    Assertions.assertFalse(optional.isPresent());
  }

  @Test
  public void should_read_comment_with_following_flag() {
    User author = new User("author@email.com", "author", "123", "", "");
    userRepository.save(author);
    Comment comment = new Comment("content", author.getId(), "123");
    commentRepository.save(comment);

    Assertions.assertFalse(
        commentQueryService.findById(comment.getId(), user).get().getProfileData().isFollowing());

    userRepository.saveRelation(new FollowRelation(user.getId(), author.getId()));
    CommentData commentData = commentQueryService.findById(comment.getId(), user).get();
    Assertions.assertEquals(comment.getId(), commentData.getId());
    Assertions.assertEquals("content", commentData.getBody());
    Assertions.assertTrue(commentData.getProfileData().isFollowing());
  }

  @Test
  public void should_read_comments_of_article_without_user() {
    Article article = new Article("title", "desc", "body", Arrays.asList("java"), user.getId());
    articleRepository.save(article);
    commentRepository.save(new Comment("content1", user.getId(), article.getId()));

    List<CommentData> comments = commentQueryService.findByArticleId(article.getId(), null);
    Assertions.assertEquals(1, comments.size());
    Assertions.assertFalse(comments.get(0).getProfileData().isFollowing());
  }

  @Test
  public void should_return_empty_list_when_article_has_no_comments() {
    Article article = new Article("title", "desc", "body", Arrays.asList("java"), user.getId());
    articleRepository.save(article);

    Assertions.assertTrue(commentQueryService.findByArticleId(article.getId(), user).isEmpty());
  }

  @Test
  public void should_return_empty_pager_when_article_has_no_comments() {
    Article article = new Article("title", "desc", "body", Arrays.asList("java"), user.getId());
    articleRepository.save(article);

    CursorPager<CommentData> pager =
        commentQueryService.findByArticleIdWithCursor(
            article.getId(), user, new CursorPageParameter<>(null, 20, Direction.NEXT));
    Assertions.assertTrue(pager.getData().isEmpty());
    Assertions.assertFalse(pager.hasNext());
    Assertions.assertFalse(pager.hasPrevious());
    Assertions.assertNull(pager.getStartCursor());
  }

  @Test
  public void should_read_comments_of_article_with_cursor() {
    Article article = new Article("title", "desc", "body", Arrays.asList("java"), user.getId());
    articleRepository.save(article);

    User user2 = new User("user2@email.com", "user2", "123", "", "");
    userRepository.save(user2);
    userRepository.saveRelation(new FollowRelation(user.getId(), user2.getId()));

    DateTime now = new DateTime();
    Comment oldest = new Comment("oldest", user.getId(), article.getId(), now.minusHours(3));
    Comment middle = new Comment("middle", user2.getId(), article.getId(), now.minusHours(2));
    Comment newest = new Comment("newest", user.getId(), article.getId(), now.minusHours(1));
    commentRepository.save(oldest);
    commentRepository.save(middle);
    commentRepository.save(newest);

    CursorPager<CommentData> firstPage =
        commentQueryService.findByArticleIdWithCursor(
            article.getId(), user, new CursorPageParameter<>(null, 2, Direction.NEXT));
    Assertions.assertEquals(2, firstPage.getData().size());
    Assertions.assertEquals(newest.getId(), firstPage.getData().get(0).getId());
    Assertions.assertEquals(middle.getId(), firstPage.getData().get(1).getId());
    Assertions.assertTrue(firstPage.hasNext());
    Assertions.assertFalse(firstPage.hasPrevious());
    Assertions.assertFalse(firstPage.getData().get(0).getProfileData().isFollowing());
    Assertions.assertTrue(firstPage.getData().get(1).getProfileData().isFollowing());

    CursorPager<CommentData> secondPage =
        commentQueryService.findByArticleIdWithCursor(
            article.getId(),
            user,
            new CursorPageParameter<>(
                DateTimeCursor.parse(firstPage.getEndCursor().toString()), 2, Direction.NEXT));
    Assertions.assertEquals(1, secondPage.getData().size());
    Assertions.assertEquals(oldest.getId(), secondPage.getData().get(0).getId());
    Assertions.assertFalse(secondPage.hasNext());

    DateTime oldestCursor = DateTimeCursor.parse(secondPage.getStartCursor().toString());
    CursorPager<CommentData> prevPage =
        commentQueryService.findByArticleIdWithCursor(
            article.getId(), user, new CursorPageParameter<>(oldestCursor, 2, Direction.PREV));
    Assertions.assertEquals(2, prevPage.getData().size());
    Assertions.assertEquals(newest.getId(), prevPage.getData().get(0).getId());
    Assertions.assertEquals(middle.getId(), prevPage.getData().get(1).getId());
    Assertions.assertFalse(prevPage.hasPrevious());
    Assertions.assertFalse(prevPage.hasNext());
  }

  @Test
  public void should_trim_extra_comment_when_paging_backwards() {
    Article article = new Article("title", "desc", "body", Arrays.asList("java"), user.getId());
    articleRepository.save(article);

    DateTime now = new DateTime();
    Comment oldest = new Comment("oldest", user.getId(), article.getId(), now.minusHours(3));
    Comment middle = new Comment("middle", user.getId(), article.getId(), now.minusHours(2));
    Comment newest = new Comment("newest", user.getId(), article.getId(), now.minusHours(1));
    commentRepository.save(oldest);
    commentRepository.save(middle);
    commentRepository.save(newest);

    CursorPager<CommentData> prevPageWithExtra =
        commentQueryService.findByArticleIdWithCursor(
            article.getId(),
            user,
            new CursorPageParameter<>(oldest.getCreatedAt(), 1, Direction.PREV));
    Assertions.assertEquals(1, prevPageWithExtra.getData().size());
    Assertions.assertEquals(middle.getId(), prevPageWithExtra.getData().get(0).getId());
    Assertions.assertTrue(prevPageWithExtra.hasPrevious());
    Assertions.assertFalse(prevPageWithExtra.hasNext());
  }

  @Test
  public void should_not_fill_following_for_anonymous_cursor_query() {
    Article article = new Article("title", "desc", "body", Arrays.asList("java"), user.getId());
    articleRepository.save(article);

    User user2 = new User("user2@email.com", "user2", "123", "", "");
    userRepository.save(user2);
    userRepository.saveRelation(new FollowRelation(user.getId(), user2.getId()));

    commentRepository.save(new Comment("a", user.getId(), article.getId()));
    commentRepository.save(new Comment("b", user2.getId(), article.getId()));

    CursorPager<CommentData> withoutUser =
        commentQueryService.findByArticleIdWithCursor(
            article.getId(), null, new CursorPageParameter<>(null, 20, Direction.NEXT));
    Assertions.assertEquals(2, withoutUser.getData().size());
    Assertions.assertFalse(withoutUser.hasNext());
    withoutUser.getData().forEach(c -> Assertions.assertFalse(c.getProfileData().isFollowing()));
  }

  private CommentData commentOf(List<CommentData> comments, Comment comment) {
    return comments.stream()
        .filter(c -> c.getId().equals(comment.getId()))
        .findFirst()
        .orElseThrow(AssertionError::new);
  }
}
