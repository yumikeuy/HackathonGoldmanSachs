package com.hackathon.alerting.delivery.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * A notification recipient (table {@code users}). Belongs to exactly one team.
 *
 * <p>The channel for a given notification is chosen from {@link #getChannelPreferences()},
 * {@link #getWorkingHours()} and {@link #getTimezone()} (the logic lives in {@code delivery.routing}).
 */
@Entity
@Table(name = "users")
public class User {

    /** Default time zone; matches the DEFAULT of column {@code users.timezone}. */
    public static final ZoneId DEFAULT_TIMEZONE = ZoneId.of("Europe/Warsaw");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** Address for EMAIL; unique, optional. */
    @Column(name = "email", unique = true, length = 200)
    private String email;

    /** Number for SMS and CALL. */
    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "timezone", nullable = false, length = 50)
    private ZoneId timezone = DEFAULT_TIMEZONE;

    /** Channel preferences in descending priority. Never {@code null}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "channel_preferences", nullable = false)
    private List<ChannelPreference> channelPreferences = List.of();

    /** Working hours; {@code null} if not set. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "working_hours")
    private WorkingHours workingHours;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected User() {
        // for JPA
    }

    public User(Team team, String name) {
        setTeam(team);
        setName(name);
    }

    public Long getId() {
        return id;
    }

    public Team getTeam() {
        return team;
    }

    /** Move the user to another team. */
    public void setTeam(Team team) {
        this.team = Objects.requireNonNull(team, "team");
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public ZoneId getTimezone() {
        return timezone;
    }

    /** {@code null} means the default time zone ({@link #DEFAULT_TIMEZONE}). */
    public void setTimezone(ZoneId timezone) {
        this.timezone = timezone == null ? DEFAULT_TIMEZONE : timezone;
    }

    public List<ChannelPreference> getChannelPreferences() {
        return channelPreferences;
    }

    /** Stores an immutable copy; {@code null} means an empty list. */
    public void setChannelPreferences(List<ChannelPreference> channelPreferences) {
        this.channelPreferences = channelPreferences == null ? List.of() : List.copyOf(channelPreferences);
    }

    public WorkingHours getWorkingHours() {
        return workingHours;
    }

    public void setWorkingHours(WorkingHours workingHours) {
        this.workingHours = workingHours;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof User other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return User.class.hashCode();
    }

    @Override
    public String toString() {
        return "User{id=" + id + ", name='" + name + "', active=" + active + '}';
    }
}
