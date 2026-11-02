package io.github.blacknoize404.networkUtils.traffic;

import java.util.Objects;

/**
 * Which adapters a {@link TrafficMonitor} measures.
 *
 * @param mode        {@link Mode#AUTO} follows the adapter that carries the default route,
 *                    {@link Mode#ALL} adds up every adapter and {@link Mode#ONLY} measures one
 * @param interfaceId the {@link TrafficInterface#id()} for {@link Mode#ONLY}, otherwise {@code null}
 */
public record TrafficSelection(Mode mode, String interfaceId) {

    public enum Mode {
        AUTO, ALL, ONLY
    }

    public static final TrafficSelection AUTO = new TrafficSelection(Mode.AUTO, null);
    public static final TrafficSelection ALL = new TrafficSelection(Mode.ALL, null);

    public TrafficSelection {
        Objects.requireNonNull(mode);
        if (mode == Mode.ONLY && interfaceId == null) {
            throw new IllegalArgumentException("An interface id is required to measure a single interface");
        }
    }

    public static TrafficSelection only(String interfaceId) {
        return new TrafficSelection(Mode.ONLY, interfaceId);
    }

}
