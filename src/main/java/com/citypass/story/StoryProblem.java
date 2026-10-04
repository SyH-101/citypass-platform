package com.citypass.story;

public class StoryProblem extends RuntimeException {
    private final int status;
    public StoryProblem(int status, String message) { super(message); this.status = status; }
    public int getStatus() { return status; }
    public static StoryProblem missing() { return new StoryProblem(404, "笔记或附件不存在"); }
    public static StoryProblem conflict(String message) { return new StoryProblem(409, message); }
}
