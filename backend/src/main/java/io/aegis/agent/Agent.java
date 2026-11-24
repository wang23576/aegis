package io.aegis.agent;

/** Agent metadata object. */
public class Agent {
    public String id;
    public String name;
    public String description;
    public Long ownerId;
    public boolean builtin;
    public String sysPrompt;
    public String toolsJson;
    public String model;
    public String createdAt;

    public Agent() {}
}
