package io.spring.infrastructure.mybatis.readservice;

import io.spring.application.data.UserData;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserReadService {

  UserData findByUsername(@Param("username") String username);

  UserData findById(@Param("id") String id);

  /** Batched profile lookup; {@code ids} must not be empty. */
  List<UserData> findByIds(@Param("ids") List<String> ids);
}
