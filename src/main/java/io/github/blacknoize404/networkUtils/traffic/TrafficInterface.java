package io.github.blacknoize404.networkUtils.traffic;

/**
 * A network adapter whose traffic can be measured.
 *
 * @param id          stable identifier: the adapter GUID on Windows, the interface name on Linux
 * @param name        the name the user knows it by ({@code Wi-Fi}, {@code eth0})
 * @param description the adapter model or driver, possibly empty
 * @param up          whether the adapter is connected right now
 */
public record TrafficInterface(String id, String name, String description, boolean up) {
}
