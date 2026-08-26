package me.justindevb.replay.config;

public enum ReplayConfigReloadScope {
    INTERNAL,
    IMMEDIATE,
    RETENTION_RESTART,
    NEW_SESSIONS_ONLY,
    RESTART_REQUIRED,
    FUTURE_ONLY
}