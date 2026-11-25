package com.lakegov.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.postgresql.util.PGobject;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * PostgreSQL jsonb 列经 JdbcTemplate 返回为 PGobject，
 * 此处将其作为原始 JSON 输出，避免被序列化成转义字符串。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer pgObjectCustomizer() {
        return builder -> builder.serializerByType(PGobject.class, new JsonSerializer<PGobject>() {
            @Override
            public void serialize(PGobject value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                if (value == null || value.getValue() == null) {
                    gen.writeNull();
                } else {
                    gen.writeRawValue(value.getValue());
                }
            }
        });
    }
}
