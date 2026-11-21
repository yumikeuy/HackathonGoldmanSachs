package com.hackathon.alerting.common.security;

import com.hackathon.alerting.common.web.NotFoundException;
import com.hackathon.alerting.delivery.model.Role;
import com.hackathon.alerting.delivery.repository.UserRepository;
import com.hackathon.alerting.rules.repository.AlertRepository;
import com.hackathon.alerting.rules.repository.RuleRepository;
import com.hackathon.alerting.runbooks.repository.RunbookRepository;
import com.hackathon.alerting.runbooks.repository.RunbookSummary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Team isolation for logged-in non-administrators (docs/security.md): before a controller runs, the request is checked
 * against the caller's team. Lists must ask for the caller's own team ({@code ?teamId=}), single resources must belong
 * to it, and operational pages (unrouted alerts, sources, simulator, failed events, field catalog, lag, generator, rule
 * dry-run) are for administrators only. Violations are 403 problem+json; unknown ids are left to the controller (404).
 *
 * <p>Runbooks: everyone reads their own team's and the global ones. Operators may also create, edit and delete runbooks
 * of their own team (the role itself is checked in {@link SecurityConfig}): the stored runbook and the {@code teamId} of
 * the upload must both be their team. Global runbooks apply to every team, so creating, changing or deleting them —
 * and moving a runbook to another team or making it global — is for administrators only.
 *
 * <p>Inactive while login is not enforced and for administrators. Teams and users lists are filtered by their
 * controllers (they are read whole, not by id).
 */
@Component
public class TeamScopeInterceptor implements HandlerInterceptor {

    private static final List<Pattern> ADMIN_ONLY = List.of(
            Pattern.compile("^/api/sources(/.*)?$"),
            Pattern.compile("^/api/simulator(/.*)?$"),
            Pattern.compile("^/api/events(/.*)?$"),
            Pattern.compile("^/api/ingest/lag$"),
            Pattern.compile("^/api/fields$"),
            Pattern.compile("^/api/dev(/.*)?$"),
            Pattern.compile("^/api/rules/dry-run$"),
            Pattern.compile("^/api/alerts/\\d+/team-suggestions$"));

    private static final Pattern ALERT = Pattern.compile("^/api/alerts/(\\d+)(/audit|/ack|/resolve)?$");
    private static final Pattern RULE = Pattern.compile("^/api/rules/(\\d+)$");
    private static final Pattern RUNBOOK = Pattern.compile("^/api/(?:runbooks|auth/runbook-links)/(\\d+)(/file)?$");
    private static final Pattern TEAM = Pattern.compile("^/api/teams/(\\d+)$");
    private static final Pattern USER = Pattern.compile("^/api/users/(\\d+)$");

    private final AuthSettings settings;
    private final AlertRepository alerts;
    private final RuleRepository rules;
    private final RunbookRepository runbooks;
    private final UserRepository users;

    public TeamScopeInterceptor(AuthSettings settings, AlertRepository alerts, RuleRepository rules,
                                RunbookRepository runbooks, UserRepository users) {
        this.settings = settings;
        this.alerts = alerts;
        this.rules = rules;
        this.runbooks = runbooks;
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!settings.enabled()) {
            return true;
        }
        Optional<TeamScope> current = TeamScope.current();
        if (current.isEmpty() || current.get().role() == Role.ADMIN) {
            return true;
        }
        TeamScope scope = current.get();
        String path = request.getRequestURI();
        String method = request.getMethod();

        for (Pattern pattern : ADMIN_ONLY) {
            if (pattern.matcher(path).matches()) {
                throw forbidden("Only administrators can open " + path);
            }
        }
        if (path.equals("/api/alerts") && "GET".equals(method)) {
            if ("true".equalsIgnoreCase(request.getParameter("unrouted"))) {
                throw forbidden("Unrouted alerts are visible to administrators only");
            }
            requireOwnTeamParam(request, scope, "alerts");
        } else if ((path.equals("/api/rules") || path.equals("/api/runbooks")) && "GET".equals(method)) {
            requireOwnTeamParam(request, scope, path.substring("/api/".length()));
        } else if (path.equals("/api/runbooks") && "POST".equals(method)) {
            requireOwnTeamForRunbook(request, scope);
        } else if (matches(ALERT, path) instanceof Matcher m) {
            alerts.findById(Long.parseLong(m.group(1))).ifPresent(alert ->
                    check(scope, alert.getTeam() == null ? null : alert.getTeam().getId(), "alert"));
        } else if (matches(RULE, path) instanceof Matcher m) {
            rules.findById(Long.parseLong(m.group(1))).ifPresent(rule -> check(scope, rule.getTeam().getId(), "rule"));
        } else if (matches(RUNBOOK, path) instanceof Matcher m) {
            boolean change = "PUT".equals(method) || "DELETE".equals(method);
            runbooks.findSummaryById(Long.parseLong(m.group(1))).ifPresent(runbook -> {
                if (change) {
                    checkRunbookChange(scope, runbook);
                    if ("PUT".equals(method)) {
                        requireOwnTeamForRunbook(request, scope);
                    }
                } else if (!runbook.isGlobal()) {
                    check(scope, runbook.teamId(), "runbook");
                }
            });
        } else if (matches(TEAM, path) instanceof Matcher m) {
            if (!scope.owns(Long.parseLong(m.group(1)))) {
                throw new NotFoundException("Team", m.group(1));
            }
        } else if (matches(USER, path) instanceof Matcher m) {
            users.findById(Long.parseLong(m.group(1))).ifPresent(user -> {
                if (!scope.owns(user.getTeam().getId())) {
                    throw new NotFoundException("User", m.group(1));
                }
            });
        }
        return true;
    }

    private static Matcher matches(Pattern pattern, String path) {
        Matcher matcher = pattern.matcher(path);
        return matcher.matches() ? matcher : null;
    }

    private static void requireOwnTeamParam(HttpServletRequest request, TeamScope scope, String what) {
        String teamId = request.getParameter("teamId");
        if (teamId == null || !teamId.matches("\\d+") || !scope.owns(Long.parseLong(teamId))) {
            throw forbidden("You can see " + what + " of your own team only: add teamId=" + scope.teamId());
        }
    }

    /**
     * The team a runbook is uploaded to or edited into ({@code teamId} form field) must be the caller's own:
     * no team means a global runbook, which only administrators may create.
     */
    private static void requireOwnTeamForRunbook(HttpServletRequest request, TeamScope scope) {
        String teamId = request.getParameter("teamId");
        if (teamId == null || teamId.isBlank()) {
            throw forbidden("Global runbooks apply to every team: only administrators can create them. Set teamId=" + scope.teamId());
        }
        if (!teamId.matches("\\d+") || !scope.owns(Long.parseLong(teamId))) {
            throw forbidden("You can manage runbooks of your own team only: set teamId=" + scope.teamId());
        }
    }

    /** An existing runbook may be changed or deleted by a non-administrator only if it belongs to their team. */
    private static void checkRunbookChange(TeamScope scope, RunbookSummary runbook) {
        if (runbook.isGlobal()) {
            throw forbidden("Global runbooks apply to every team: only administrators can change or delete them");
        }
        check(scope, runbook.teamId(), "runbook");
    }

    private static void check(TeamScope scope, Long teamId, String what) {
        if (!scope.owns(teamId)) {
            throw forbidden("This " + what + " belongs to another team");
        }
    }

    private static ErrorResponseException forbidden(String detail) {
        return new ErrorResponseException(HttpStatus.FORBIDDEN, ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, detail), null);
    }
}
