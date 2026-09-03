package io.spring.article;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.ISODateTimeFormat;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Same DateTime rendering as the monolith's io.spring.JacksonCustomizations
 * (ISODateTimeFormat.dateTime() in UTC, e.g. 2024-01-31T10:15:30.123Z), plus the inverse
 * deserializer for the caller-supplied createdAt in POST bodies (ISO-8601 string or epoch millis).
 */
@Configuration
public class JacksonCustomizations {

  @Bean
  public Module realWorldModules() {
    return new RealWorldModules();
  }

  public static class RealWorldModules extends SimpleModule {
    public RealWorldModules() {
      addSerializer(DateTime.class, new DateTimeSerializer());
      addDeserializer(DateTime.class, new DateTimeDeserializer());
    }
  }

  public static class DateTimeSerializer extends StdSerializer<DateTime> {

    protected DateTimeSerializer() {
      super(DateTime.class);
    }

    @Override
    public void serialize(DateTime value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      if (value == null) {
        gen.writeNull();
      } else {
        gen.writeString(ISODateTimeFormat.dateTime().withZoneUTC().print(value));
      }
    }
  }

  public static class DateTimeDeserializer extends StdDeserializer<DateTime> {

    protected DateTimeDeserializer() {
      super(DateTime.class);
    }

    @Override
    public DateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      if (p.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT)) {
        return new DateTime(p.getLongValue(), DateTimeZone.UTC);
      }
      String text = p.getValueAsString();
      if (text == null || text.trim().isEmpty()) {
        return null;
      }
      return ISODateTimeFormat.dateTimeParser().withZoneUTC().parseDateTime(text.trim());
    }
  }
}
