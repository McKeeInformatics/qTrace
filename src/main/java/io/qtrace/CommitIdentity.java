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

package io.qtrace;

import org.eclipse.jgit.lib.PersonIdent;

/**
 * Who a qTrace Git commit belongs to — used as both author and committer.
 *
 * With a valid certificate: the certified name and the qtrace.ca account email, plus trailers
 * tying the commit to the validator key. Without one: the contributor's name, no email. qTrace
 * itself only when nobody is known. This is a declared identity, not a signature: the proof of
 * who validated comes from the signed stamp.
 */
public record CommitIdentity(String name, String email, String validatorKey, boolean certified) {

    static final String QTRACE_NAME  = "qTrace";
    static final String QTRACE_EMAIL = "noreply@qtrace.ca";

    public static CommitIdentity of(LicenseInfo license, String contributor) {
        if (license != null && !license.expired() && license.name() != null && !license.name().isBlank()) {
            return new CommitIdentity(license.name().strip(),
                license.email() != null ? license.email().strip() : "",
                license.validatorKey() != null && !license.validatorKey().isBlank() ? license.validatorKey() : null,
                license.verified());
        }
        if (contributor != null && !contributor.isBlank()) {
            return new CommitIdentity(contributor.strip(), "", null, false);
        }
        return new CommitIdentity(QTRACE_NAME, QTRACE_EMAIL, null, false);
    }

    /** A fresh ident (current time) for author and committer. */
    public PersonIdent person() {
        return new PersonIdent(name, email);
    }

    /** Git trailers (with the separating blank line) tying the commit to the certificate; "" without one. */
    public String trailers() {
        if (validatorKey == null) return "";
        return "\n\nqTrace-Validator-Key: " + validatorKey
             + "\nqTrace-Certified: " + (certified ? "yes" : "no");
    }

    /** Commit message with this identity's trailers. */
    public String message(String subject) {
        return subject + trailers();
    }
}
