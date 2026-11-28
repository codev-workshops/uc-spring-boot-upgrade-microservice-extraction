package io.spring.graphql;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import graphql.schema.DataFetchingEnvironment;
import io.spring.TestHelper;
import io.spring.api.exception.ResourceNotFoundException;
import io.spring.application.ProfileQueryService;
import io.spring.application.data.ArticleData;
import io.spring.application.data.CommentData;
import io.spring.application.data.ProfileData;
import io.spring.core.user.User;
import io.spring.graphql.types.Article;
import io.spring.graphql.types.Comment;
import io.spring.graphql.types.Profile;
import io.spring.graphql.types.ProfilePayload;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ProfileDatafetcherTest extends GraphQLTestBase {
  private ProfileQueryService profileQueryService;
  private ProfileDatafetcher profileDatafetcher;
  private User user;
  private User author;

  @BeforeEach
  public void setUp() {
    profileQueryService = mock(ProfileQueryService.class);
    profileDatafetcher = new ProfileDatafetcher(profileQueryService);
    user = new User("john@jacob.com", "johnjacob", "123", "", "");
    author = new User("author@test.com", "author", "123", "bio", "image");
  }

  private ProfileData authorProfile(boolean following) {
    return new ProfileData(
        author.getId(), author.getUsername(), author.getBio(), author.getImage(), following);
  }

  @Test
  public void should_get_profile_of_user_in_local_context() {
    login(user);
    DataFetchingEnvironment dfe = mock(DataFetchingEnvironment.class);
    when(dfe.getLocalContext()).thenReturn(author);
    when(profileQueryService.findByUsername(eq("author"), eq(user)))
        .thenReturn(Optional.of(authorProfile(true)));

    Profile profile = profileDatafetcher.getUserProfile(dfe);

    Assertions.assertEquals("author", profile.getUsername());
    Assertions.assertEquals("bio", profile.getBio());
    Assertions.assertEquals("image", profile.getImage());
    Assertions.assertTrue(profile.getFollowing());
  }

  @Test
  public void should_get_article_author_without_current_user() {
    anonymous();
    ArticleData articleData = TestHelper.articleDataFixture("1", author);
    Map<String, ArticleData> map = new HashMap<>();
    map.put(articleData.getSlug(), articleData);
    DataFetchingEnvironment dfe = mock(DataFetchingEnvironment.class);
    when(dfe.getLocalContext()).thenReturn(map);
    when(dfe.getSource()).thenReturn(Article.newBuilder().slug(articleData.getSlug()).build());
    when(profileQueryService.findByUsername(eq("author"), isNull()))
        .thenReturn(Optional.of(authorProfile(false)));

    Profile profile = profileDatafetcher.getAuthor(dfe);

    Assertions.assertEquals("author", profile.getUsername());
    Assertions.assertFalse(profile.getFollowing());
  }

  @Test
  public void should_get_comment_author() {
    login(user);
    DateTime now = new DateTime();
    CommentData commentData =
        new CommentData("comment-id", "body", "article-id", now, now, authorProfile(false));
    Map<String, CommentData> map = new HashMap<>();
    map.put("comment-id", commentData);
    DataFetchingEnvironment dfe = mock(DataFetchingEnvironment.class);
    when(dfe.getLocalContext()).thenReturn(map);
    when(dfe.getSource()).thenReturn(Comment.newBuilder().id("comment-id").build());
    when(profileQueryService.findByUsername(eq("author"), eq(user)))
        .thenReturn(Optional.of(authorProfile(false)));

    Profile profile = profileDatafetcher.getCommentAuthor(dfe);

    Assertions.assertEquals("author", profile.getUsername());
  }

  @Test
  public void should_query_profile_by_username_argument() {
    anonymous();
    DataFetchingEnvironment dfe = mock(DataFetchingEnvironment.class);
    when(dfe.getArgument("username")).thenReturn("author");
    when(profileQueryService.findByUsername(eq("author"), isNull()))
        .thenReturn(Optional.of(authorProfile(false)));

    ProfilePayload payload = profileDatafetcher.queryProfile("author", dfe);

    Assertions.assertEquals("author", payload.getProfile().getUsername());
    Assertions.assertEquals("bio", payload.getProfile().getBio());
  }

  @Test
  public void should_throw_not_found_for_unknown_profile() {
    anonymous();
    DataFetchingEnvironment dfe = mock(DataFetchingEnvironment.class);
    when(dfe.getArgument("username")).thenReturn("missing");
    when(profileQueryService.findByUsername(eq("missing"), isNull())).thenReturn(Optional.empty());

    Assertions.assertThrows(
        ResourceNotFoundException.class, () -> profileDatafetcher.queryProfile("missing", dfe));
  }
}
