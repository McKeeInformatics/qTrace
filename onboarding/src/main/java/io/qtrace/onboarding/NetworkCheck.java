/*
 * qTrace — QuPath workflow provenance extension
 * Copyright (C) 2026 Romain Tourte
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package io.qtrace.onboarding;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Can this machine reach what qTrace needs? Behind an institutional proxy or firewall, this is
 * the most likely blocker — better said on the first screen than as a failed download later.
 * Any HTTP answer counts as reachable; only a connection failure does not.
 */
public final class NetworkCheck {

    private NetworkCheck() {}

    /** Names (keys of {@code targets}) that could not be reached. */
    public static List<String> unreachable(Map<String, String> targets) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        List<String> out = new ArrayList<>();
        targets.forEach((name, url) -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
                http.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                out.add(name);
            }
        });
        return out;
    }
}
