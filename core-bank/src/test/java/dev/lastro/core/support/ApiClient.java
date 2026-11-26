package dev.lastro.core.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** Atalhos de chamada autenticada para os testes. */
public record ApiClient(MockMvc mvc, String bearer) {

    public MockHttpServletResponse read(String path) throws Exception {
        return mvc.perform(get("/api/v1" + path).header("Authorization", bearer)).andReturn().getResponse();
    }

    public MockHttpServletResponse send(String path, String json) throws Exception {
        return send(path, json, UUID.randomUUID().toString());
    }

    public MockHttpServletResponse send(String path, String json, String idempotencyKey) throws Exception {
        return mvc.perform(post("/api/v1" + path).header("Authorization", bearer)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn().getResponse();
    }
}
