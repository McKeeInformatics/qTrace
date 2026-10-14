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

package io.qtrace.player.ext;

public class SimpleExtensionHandler implements QTraceExtensionHandler {

    private final String name;
    private final String installUrl;
    private final Compat compat;
    private final String caveat;

    public SimpleExtensionHandler(String name, String installUrl, Compat compat) {
        this(name, installUrl, compat, "");
    }

    public SimpleExtensionHandler(String name, String installUrl, Compat compat, String caveat) {
        this.name       = name;
        this.installUrl = installUrl;
        this.compat     = compat;
        this.caveat     = caveat != null ? caveat : "";
    }

    @Override public String getExtensionName() { return name; }
    @Override public String getInstallUrl()    { return installUrl; }
    @Override public Compat getCompat()        { return compat; }
    @Override public String getCaveat()        { return caveat; }

    @Override public String buildPreambleGroovy() { return ""; }
}
