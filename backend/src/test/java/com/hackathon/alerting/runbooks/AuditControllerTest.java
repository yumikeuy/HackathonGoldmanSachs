package com.hackathon.alerting.runbooks;

import com.hackathon.alerting.common.web.NotFoundException;
import com.hackathon.alerting.runbooks.api.AuditController;
import com.hackathon.alerting.runbooks.audit.AuditEntry;
import com.hackathon.alerting.runbooks.audit.AuditQueryService;
import com.hackathon.alerting.runbooks.model.AuditActions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuditController.class)
class AuditControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditQueryService auditQueries;

    @Test
    void getAlertAuditReturnsEntriesJson() throws Exception {
        Instant t1 = Instant.parse("2026-11-21T01:00:00Z");
        Instant t2 = Instant.parse("2026-11-21T01:05:00Z");
        when(auditQueries.findByAlertId(42L)).thenReturn(List.of(
                new AuditEntry(1L, 42L, "system", AuditActions.RUNBOOK_ATTACHED, JSON.readTree("{\"runbookId\":3}"), t1),
                new AuditEntry(2L, 42L, "anna@bank.com", AuditActions.ACK, JSON.readTree("{}"), t2)
        ));

        mockMvc.perform(get("/api/alerts/42/audit"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].alertId").value(42))
                .andExpect(jsonPath("$[0].actor").value("system"))
                .andExpect(jsonPath("$[0].actionType").value("RUNBOOK_ATTACHED"))
                .andExpect(jsonPath("$[0].details.runbookId").value(3))
                .andExpect(jsonPath("$[0].createdAt").value("2026-11-21T01:00:00Z"))
                .andExpect(jsonPath("$[1].actor").value("anna@bank.com"))
                .andExpect(jsonPath("$[1].actionType").value("ACK"));
    }

    @Test
    void getAlertAuditReturnsProblemJsonNotFoundWhenAlertMissing() throws Exception {
        when(auditQueries.findByAlertId(999L)).thenThrow(new NotFoundException("Alert", 999L));

        mockMvc.perform(get("/api/alerts/999/audit"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Alert 999 not found"));
    }
}
