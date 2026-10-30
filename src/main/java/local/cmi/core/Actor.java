package local.cmi.core;
import java.util.UUID;
/** Missing ownership is intentional and never authorizes automatic fire. */
public record Actor(UUID owner, String iff, UUID platform) {}
