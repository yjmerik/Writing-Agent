package com.writing.novel.llm.client;

/** A transport/service failure eligible for provider failover. Contains no remote body or credentials. */
public class LlmUnavailableException extends RuntimeException {
    public enum Reason { NETWORK, TIMEOUT, AUTH, QUOTA, RATE_LIMIT, SERVER, ENDPOINT, EMPTY_RESPONSE, PROTOCOL }
    private final Reason reason;
    private final int status;
    public LlmUnavailableException(Reason reason, int status) {
        super("LLM unavailable: " + reason + (status == 0 ? "" : " (HTTP " + status + ")"));
        this.reason = reason;
        this.status = status;
    }
    public Reason getReason() { return reason; }
    public int getStatus() { return status; }
}
