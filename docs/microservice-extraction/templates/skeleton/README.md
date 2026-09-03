# Skeleton templates

File templates for an extracted `<domain>-service/`. Every file carries a `.template` suffix so the
root Gradle build (Spotless target `**/*.java`) never compiles or formats them. Placeholders:
`{{domain}}`, `{{Domain}}`, `{{port}}`, `{{Other}}`, `{{other}}` — see
[`../03-extracted-service-template.md`](../03-extracted-service-template.md) §0 for the substitution
script and the target location of each file:

| Template                                   | Target path in `<domain>-service/`                                              |
|--------------------------------------------|----------------------------------------------------------------------------------|
| `build.gradle.template`                    | `build.gradle`                                                                   |
| `settings.gradle.template`                 | `settings.gradle`                                                                |
| `application.properties.template`          | `src/main/resources/application.properties`                                      |
| `application-test.properties.template`     | `src/main/resources/application-test.properties`                                 |
| `V1__create_{{domain}}_tables.sql.template`| `src/main/resources/db/migration/V1__create_{{domain}}_tables.sql`               |
| `{{Domain}}ServiceApplication.java.template`| `src/main/java/io/spring/{{domain}}/{{Domain}}ServiceApplication.java`          |
| `{{Other}}ServiceClient.java.template`     | `src/main/java/io/spring/{{domain}}/application/client/{{Other}}ServiceClient.java` |
| `{{Other}}Dto.java.template`               | `src/main/java/io/spring/{{domain}}/application/dto/{{Other}}Dto.java`           |
| `{{Domain}}Mapper.java.template`           | `src/main/java/io/spring/{{domain}}/infrastructure/mybatis/mapper/{{Domain}}Mapper.java` |
| `{{Domain}}Mapper.xml.template`            | `src/main/resources/mapper/{{Domain}}Mapper.xml`                                 |
| `MyBatis{{Domain}}Repository.java.template`| `src/main/java/io/spring/{{domain}}/infrastructure/repository/MyBatis{{Domain}}Repository.java` |

The Java/XML examples are written for the Favorite domain (`article_favorites`); adapt entity fields
and SQL for other domains.
