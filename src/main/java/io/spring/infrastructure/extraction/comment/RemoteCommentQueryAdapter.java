package io.spring.infrastructure.extraction.comment;

import io.spring.application.CursorPageParameter;
import io.spring.application.comment.CommentQueryPort;
import io.spring.application.comment.dto.CommentRowDto;
import io.spring.application.data.CommentData;
import io.spring.application.data.ProfileData;
import io.spring.application.data.UserData;
import io.spring.infrastructure.mybatis.readservice.UserReadService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;
import org.springframework.stereotype.Component;

/**
 * {@link CommentQueryPort} backed by comment-service. Rows come from the service; author profiles
 * are still local in Phase 2 and are fetched in one batched {@link UserReadService#findByIds} call
 * (no N+1). The composed {@link CommentData} mirrors {@code TransferData.xml#commentData}: {@code
 * updatedAt == createdAt}, {@code following == false}, and a missing author yields a {@code null}
 * profile exactly like the {@code LEFT JOIN users}.
 */
@Component
public class RemoteCommentQueryAdapter implements CommentQueryPort {
  private final CommentServiceClient client;
  private final UserReadService userReadService;

  public RemoteCommentQueryAdapter(CommentServiceClient client, UserReadService userReadService) {
    this.client = client;
    this.userReadService = userReadService;
  }

  @Override
  public CommentData findById(String id) {
    Optional<CommentRowDto> row = client.findById(id);
    if (!row.isPresent()) {
      return null;
    }
    List<CommentData> composed = compose(List.of(row.get()));
    return composed.isEmpty() ? null : composed.get(0);
  }

  /**
   * {@code CommentReadService.xml#findByArticleId} has no {@code ORDER BY}, so the monolith returns
   * SQLite insertion order, i.e. ascending creation time. comment-service answers {@code created_at
   * DESC}; the list is re-sorted here so both routes serialize identically.
   */
  @Override
  public List<CommentData> findByArticleId(String articleId) {
    List<CommentData> comments = compose(client.findByArticleId(articleId));
    comments.sort(Comparator.comparing(CommentData::getCreatedAt));
    return comments;
  }

  @Override
  public List<CommentData> findByArticleIdWithCursor(
      String articleId, CursorPageParameter<DateTime> page) {
    return compose(client.findByArticleIdWithCursor(articleId, page));
  }

  List<CommentData> compose(List<CommentRowDto> rows) {
    List<CommentData> result = new ArrayList<>(rows.size());
    if (rows.isEmpty()) {
      return result;
    }
    List<String> userIds =
        rows.stream().map(CommentRowDto::getUserId).distinct().collect(Collectors.toList());
    Map<String, UserData> users = new HashMap<>();
    for (UserData user : userReadService.findByIds(userIds)) {
      users.put(user.getId(), user);
    }
    for (CommentRowDto row : rows) {
      DateTime createdAt = parse(row.getCreatedAt());
      UserData author = users.get(row.getUserId());
      ProfileData profile =
          author == null
              ? null
              : new ProfileData(
                  author.getId(), author.getUsername(), author.getBio(), author.getImage(), false);
      result.add(
          new CommentData(
              row.getId(), row.getBody(), row.getArticleId(), createdAt, createdAt, profile));
    }
    return result;
  }

  /** Same instant and default-zone chronology as {@code DateTimeHandler} produces for a column. */
  static DateTime parse(String iso) {
    return iso == null ? null : new DateTime(ISODateTimeFormat.dateTimeParser().parseMillis(iso));
  }
}
