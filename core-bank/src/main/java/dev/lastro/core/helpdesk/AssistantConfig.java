package dev.lastro.core.helpdesk;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
class AssistantConfig {

    /** Com chave, o Lino usa o Claude; sem chave, as respostas prontas. A chave nunca fica no repositório. */
    @Bean
    @Primary
    Assistant assistant(FaqAssistant faq, @Value("${lastro.assistant.api-key:}") String apiKey,
            @Value("${lastro.assistant.model:" + ClaudeAssistant.MODEL + "}") String model) {
        if (apiKey == null || apiKey.isBlank()) {
            LoggerFactory.getLogger(AssistantConfig.class)
                    .info("Sem LASTRO_ANTHROPIC_API_KEY: o Lino responde com perguntas prontas");
            return faq;
        }
        var client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey.strip())
                .timeout(Duration.ofSeconds(25))
                .maxRetries(1)
                .build();
        return new ClaudeAssistant(client, model, faq);
    }
}
