package io.spring.application.data;

/** Profile exposed to extracted services; unlike {@link ProfileData} it includes the user id. */
public record InternalProfileData(
    String id, String username, String bio, String image, boolean following) {}
