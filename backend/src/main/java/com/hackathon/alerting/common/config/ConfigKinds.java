package com.hackathon.alerting.common.config;

/** Values of {@link ConfigChangedEvent#what()}: which cached configuration changed. */
public final class ConfigKinds {

    public static final String TEAMS = "teams";
    public static final String USERS = "users";
    public static final String RULES = "rules";
    public static final String RUNBOOKS = "runbooks";
    public static final String SOURCES = "sources";

    private ConfigKinds() {
    }
}
