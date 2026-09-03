package io.spring.user.infrastructure.mybatis.mapper;

import io.spring.user.core.user.FollowRelation;
import io.spring.user.core.user.User;
import io.spring.user.core.user.UserUpdate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {
  void insert(@Param("user") User user);

  void update(@Param("user") UserUpdate user);

  User findById(@Param("id") String id);

  User findByUsername(@Param("username") String username);

  User findByEmail(@Param("email") String email);

  List<User> findByIds(@Param("ids") List<String> ids);

  FollowRelation findRelation(@Param("userId") String userId, @Param("targetId") String targetId);

  void saveRelation(@Param("followRelation") FollowRelation followRelation);

  void deleteRelation(@Param("followRelation") FollowRelation followRelation);

  List<String> followingAuthors(@Param("userId") String userId, @Param("ids") List<String> ids);

  List<String> followedUsers(@Param("userId") String userId);
}
