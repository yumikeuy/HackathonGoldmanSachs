package com.hackathon.alerting.delivery.api;

import com.hackathon.alerting.common.config.ConfigChangedPublisher;
import com.hackathon.alerting.common.config.ConfigKinds;
import com.hackathon.alerting.common.web.ConflictException;
import com.hackathon.alerting.common.web.InvalidRequestException;
import com.hackathon.alerting.common.web.NotFoundException;
import com.hackathon.alerting.delivery.model.ChannelPreference;
import com.hackathon.alerting.delivery.model.Role;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.delivery.model.User;
import com.hackathon.alerting.delivery.model.WorkingHours;
import com.hackathon.alerting.delivery.repository.TeamRepository;
import com.hackathon.alerting.delivery.repository.UserRepository;
import com.hackathon.alerting.delivery.routing.HourWindow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * CRUD {@code /api/users}. Validates the time zone and the {@code HH-HH} hour windows of channel preferences;
 * a duplicate email is 409. {@code password} (write-only, BCrypt-hashed) and {@code role} enable UI login;
 * the hash is never returned, only {@code canLogIn}. Every change is published as {@link ConfigKinds#USERS}, which reloads the
 * delivery routing cache.
 */
@RestController
@RequestMapping("/api/users")
@Transactional
public class UserController {

    private final UserRepository users;
    private final TeamRepository teams;
    private final ConfigChangedPublisher configChanged;
    private final PasswordEncoder passwords;

    public UserController(UserRepository users, TeamRepository teams, ConfigChangedPublisher configChanged,
                          PasswordEncoder passwords) {
        this.users = users;
        this.teams = teams;
        this.configChanged = configChanged;
        this.passwords = passwords;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<UserDto> list(@RequestParam(required = false) Long teamId) {
        List<User> result = teamId == null ? users.findAll(Sort.by("id")) : users.findAllByTeamIdOrderByIdAsc(teamId);
        return result.stream().map(UserDto::from).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public UserDto get(@PathVariable long id) {
        return UserDto.from(find(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto create(@Valid @RequestBody UserInput input) {
        String email = normalize(input.email());
        if (email != null && users.existsByEmail(email)) {
            throw new ConflictException("A user with email '" + email + "' already exists");
        }
        User user = new User(team(input.teamId()), input.name());
        apply(input, user);
        return save(user);
    }

    @PutMapping("/{id}")
    public UserDto update(@PathVariable long id, @Valid @RequestBody UserInput input) {
        User user = find(id);
        String email = normalize(input.email());
        if (email != null && !Objects.equals(email, user.getEmail()) && users.existsByEmail(email)) {
            throw new ConflictException("A user with email '" + email + "' already exists");
        }
        user.setTeam(team(input.teamId()));
        user.setName(input.name());
        apply(input, user);
        return save(user);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        users.delete(find(id));
        configChanged.publish(ConfigKinds.USERS);
    }

    private void apply(UserInput input, User user) {
        user.setEmail(normalize(input.email()));
        user.setPhone(normalize(input.phone()));
        user.setTimezone(zone(input.timezone()));
        List<ChannelPreference> preferences = input.channelPreferences() == null ? List.of() : input.channelPreferences();
        for (ChannelPreference preference : preferences) {
            if (preference == null) {
                throw new InvalidRequestException("channelPreferences must not contain null");
            }
            if (preference.hours() != null && !HourWindow.isValid(preference.hours())) {
                throw new InvalidRequestException("Invalid hours '" + preference.hours() + "': expected HH-HH with hours 00..24");
            }
        }
        user.setChannelPreferences(preferences);
        user.setWorkingHours(input.workingHours());
        user.setActive(input.active() == null || input.active());
        if (input.role() != null) {
            user.setRole(input.role());
        }
        if (input.password() != null) {
            // Only the BCrypt hash is stored; omitting the password on update keeps the current one
            user.setPasswordHash(input.password().isEmpty() ? null : passwords.encode(input.password()));
        }
    }

    private UserDto save(User user) {
        try {
            User saved = users.saveAndFlush(user);
            configChanged.publish(ConfigKinds.USERS);
            return UserDto.from(saved);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("A user with email '" + user.getEmail() + "' already exists", e);
        }
    }

    private User find(long id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("User", id));
    }

    private Team team(long teamId) {
        return teams.findById(teamId).orElseThrow(() -> new InvalidRequestException("Team " + teamId + " does not exist"));
    }

    private static ZoneId zone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return User.DEFAULT_TIMEZONE;
        }
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new InvalidRequestException("Unknown time zone '" + timezone + "'", e);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** {@code UserInput} of openapi.yaml. */
    public record UserInput(
            @NotNull Long teamId,
            @NotBlank @Size(max = 100) String name,
            @Email @Size(max = 200) String email,
            @Size(max = 30) String phone,
            String timezone,
            List<ChannelPreference> channelPreferences,
            WorkingHours workingHours,
            Boolean active,
            Role role,
            @Size(max = 72, message = "must be at most 72 characters")
            @Pattern(regexp = "^$|^.{8,}$", message = "must be at least 8 characters, or empty to disable login")
            String password) {
    }

    /** {@code User} of openapi.yaml. */
    public record UserDto(long id, long teamId, String name, String email, String phone, String timezone,
                          List<ChannelPreference> channelPreferences, WorkingHours workingHours, boolean active,
                          Role role, boolean canLogIn, Instant createdAt) {

        static UserDto from(User user) {
            return new UserDto(user.getId(), user.getTeam().getId(), user.getName(), user.getEmail(), user.getPhone(),
                    user.getTimezone().getId(), user.getChannelPreferences(), user.getWorkingHours(), user.isActive(),
                    user.getRole(), user.getPasswordHash() != null && user.getEmail() != null, user.getCreatedAt());
        }
    }
}
