package local.cmi.core;
import java.util.UUID;
public record TargetId(Kind kind, UUID uuid) { public enum Kind { ENTITY, SABLE } }
