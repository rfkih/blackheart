package id.co.blackheart.service.risk;

/**
 * Result returned by sub-guards (RegimeGuardService, CorrelationGuardService)
 * indicating whether an entry is permitted and why it was blocked.
 */
public record GateVerdict(boolean allowed, String reason) {
    public static GateVerdict allow() { return new GateVerdict(true, null); }
    public static GateVerdict deny(String reason) { return new GateVerdict(false, reason); }
}
